package com.example.FFTT04M.desktop

import jcuda.Pointer
import jcuda.Sizeof
import jcuda.jcufft.JCufft
import jcuda.jcufft.cufftHandle
import jcuda.jcufft.cufftType
import jcuda.runtime.JCuda
import jcuda.runtime.cudaDeviceProp
import jcuda.runtime.cudaMemcpyKind
import java.io.File

/**
 * Optional NVIDIA GPU acceleration for the FFT-heavy CWT, via cuFFT (JCuda/JCufft bindings).
 *
 * Best-effort and self-contained: [available] preloads the CUDA runtime DLLs (cudart/cufft) from a
 * local `native/cuda` folder — so no CUDA toolkit install or PATH edit is needed — then probes the
 * device once and caches the result. If anything is missing (no NVIDIA driver, DLLs not found, no
 * device) it returns false and callers fall back to the in-house CPU FFT.
 *
 * [inverseBatch] runs a batched complex inverse FFT (the CWT's 100 scales in one call), reusing a
 * cached plan and a persistent device buffer across clips. It is `@Synchronized` because there is
 * one GPU: many CPU worker threads prepare clips in parallel and take turns on the device.
 *
 * cuFFT is unnormalized; the inverse here divides by N to match `FFTUtils.inverse`.
 */
object GpuFft {

    @Volatile private var probed = false
    @Volatile private var ok = false
    @Volatile private var device: String? = null
    @Volatile private var dllDir: String? = null          // which folder the CUDA redist DLLs loaded from
    @Volatile private var reason: String? = null          // why GPU is unavailable (for accurate UI)

    // Persistent device resources (guarded by the object monitor via @Synchronized methods).
    private val plans = HashMap<Long, cufftHandle>()
    private var devBuf: Pointer? = null
    private var devCap = 0L

    /** True if an NVIDIA GPU + cuFFT are usable. Probed once, then cached. */
    @Synchronized
    fun available(): Boolean {
        if (probed) return ok
        probed = true
        ok = try {
            preloadCudaRuntime()
            if (dllDir == null)
                System.err.println("GpuFft: CUDA redist DLLs not found in any candidate folder; " +
                    "relying on PATH/jcuda-natives only.")
            JCuda.setExceptionsEnabled(true)
            JCufft.setExceptionsEnabled(true)
            val count = IntArray(1)
            JCuda.cudaGetDeviceCount(count)
            if (count[0] <= 0) { reason = "no CUDA device reported by the driver"; false } else {
                JCuda.cudaSetDevice(0)
                val prop = cudaDeviceProp()
                JCuda.cudaGetDeviceProperties(prop, 0)
                device = prop.getName().trim()
                // Confirm the cuFFT path actually executes (call core directly — ok isn't set yet).
                if (core(floatArrayOf(1f, 0f, 2f, 0f, 3f, 0f, 4f, 0f), 4, 1) != null) true
                else { reason = "cuFFT self-test failed"; false }
            }
        } catch (e: Throwable) {
            reason = (e.message ?: e.toString()).lineSequence().firstOrNull()?.take(160) +
                if (dllDir == null) " (CUDA redist DLLs were not located — see folder search)" else ""
            System.err.println("GpuFft unavailable: ${e.message}")
            false
        }
        return ok
    }

    fun deviceName(): String? = device

    /** Human-readable reason GPU is unavailable (null when available or not yet probed). */
    fun unavailableReason(): String? = reason

    /**
     * Batched **inverse** C2C FFT of [batch] signals of length [n], interleaved as `batch*n` complex
     * samples `[re,im,…]`. Returns a new interleaved array divided by N. Null on any failure.
     */
    @Synchronized
    fun inverseBatch(data: FloatArray, n: Int, batch: Int): FloatArray? {
        if (!ok) return null
        return core(data, n, batch)
    }

    /** The actual device work (no availability gate — used by the self-test and by [inverseBatch]). */
    private fun core(data: FloatArray, n: Int, batch: Int): FloatArray? = try {
        val bytes = data.size.toLong() * Sizeof.FLOAT
        ensureBuf(bytes)
        val dev = devBuf!!
        val plan = plans.getOrPut((n.toLong() shl 24) or batch.toLong()) {
            cufftHandle().also { JCufft.cufftPlan1d(it, n, cufftType.CUFFT_C2C, batch) }
        }
        JCuda.cudaMemcpy(dev, Pointer.to(data), bytes, cudaMemcpyKind.cudaMemcpyHostToDevice)
        JCufft.cufftExecC2C(plan, dev, dev, JCufft.CUFFT_INVERSE)
        val out = FloatArray(data.size)
        JCuda.cudaMemcpy(Pointer.to(out), dev, bytes, cudaMemcpyKind.cudaMemcpyDeviceToHost)
        val inv = 1f / n
        for (i in out.indices) out[i] *= inv
        out
    } catch (e: Throwable) {
        System.err.println("GpuFft.core failed: ${e.message}")
        null
    }

    private fun ensureBuf(bytes: Long) {
        if (devCap >= bytes && devBuf != null) return
        devBuf?.let { try { JCuda.cudaFree(it) } catch (_: Throwable) {} }
        val p = Pointer()
        JCuda.cudaMalloc(p, bytes)
        devBuf = p
        devCap = bytes
    }

    // ---- CUDA runtime DLL discovery -------------------------------------------------------------

    /**
     * Load the NVIDIA runtime DLLs the JCufft wrapper depends on, from a local folder, so neither a
     * CUDA toolkit install nor a PATH edit is required. Order matters (dependencies first).
     */
    private fun preloadCudaRuntime() {
        val names = listOf("nvJitLink_120_0.dll", "cudart64_12.dll", "cufft64_11.dll")
        // The DLLs live in `<module>/native/cuda`, i.e. `desktop/native/cuda`. Depending on how the
        // app is launched the "current location" is the module dir (Gradle/IDE: cwd=desktop), the repo
        // root (icon/.bat/.vbs: cwd=FFTT04D), the fat jar (…/desktop/build/libs), or the classes dir
        // (…/desktop/build/classes/kotlin/main). So at every base we try BOTH `native/cuda` and
        // `desktop/native/cuda`, and we walk up far enough to reach the module from a deep classes dir.
        val subPaths = listOf("native/cuda", "desktop/native/cuda")
        val bases = buildList {
            add(File(System.getProperty("user.dir")))
            var d: File? = jarDir()
            repeat(8) { d?.let { add(it); d = it.parentFile } }
        }
        val candidates = buildList {
            // An explicit override may point straight at the DLLs.
            System.getenv("FFTT04D_CUDA_DIR")?.let { add(File(it)) }
            for (b in bases) for (s in subPaths) add(File(b, s))
            System.getenv("CUDA_PATH")?.let { add(File(it, "bin")) }
        }
        val dir = candidates.firstOrNull { d -> names.all { File(d, it).isFile } } ?: return
        for (nm in names) try { System.load(File(dir, nm).absolutePath) } catch (_: Throwable) {}
        dllDir = dir.absolutePath
    }

    private fun jarDir(): File? = try {
        val src = File(GpuFft::class.java.protectionDomain.codeSource.location.toURI())
        if (src.isFile) src.parentFile else src
    } catch (e: Throwable) { null }
}
