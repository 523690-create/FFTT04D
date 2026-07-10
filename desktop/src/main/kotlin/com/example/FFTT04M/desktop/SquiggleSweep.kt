package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.cough.MultiRidgeExtractor
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Squiggle sweep": scan a folder of COMPLETE clips, detect every 300–2000 Hz parabolic-ridge chirp
 * ("squiggle") in each via [MultiRidgeExtractor], and for each squiggle:
 *   - extract its `[t0,t1]` span (padded, min-kept) into [outDir] (default `G:\squiggles`) as a mono-16
 *     WAV named with its parameters,
 *   - optionally render a Morlet-CWT scalogram (`<wav>.jpg`) so the breakout grid's Wavelet column
 *     populates (the grid renders FFT + MFCC live from the WAV itself, so only the CWT jpg needs to
 *     exist on disk),
 *   - and log its parameters (start/end/dur, vertex time+freq, parabola curvature, R², frame count,
 *     mean energy) to `squiggles_manifest.csv`.
 *
 * Non-destructive (originals untouched), parallel across all cores, cancellable, and resumable (a
 * squiggle whose WAV already exists is skipped). Mirrors the [CoughIsolator.harvest] pattern.
 */
object SquiggleSweep {

    private const val SR = 44100
    private const val PAD_SEC = 0.04           // small margin kept before/after each squiggle
    private const val MIN_KEEP_SEC = 0.12      // never write a clip shorter than this
    private const val MAX_SQUIGGLE_SEC = 2.0   // a single chirp span is never longer than this → anomaly

    data class Progress(val done: Int, val total: Int, val message: String)
    data class Summary(
        val totalClips: Int, val clipsWithSquiggles: Int, val squiggles: Int,
        val noDetect: Int, val failed: Int, val cwtRendered: Int, val elapsedS: Double,
        val cancelled: Boolean, val outDir: File,
    )

    private val workers = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    fun run(source: File, outDir: File, renderCwt: Boolean, cancel: AtomicBoolean,
            onProgress: (Progress) -> Unit): Summary {
        val startNs = System.nanoTime()
        outDir.mkdirs()
        val wavs = source.walkTopDown().filter { it.isFile && it.extension.equals("wav", true) }.toList()
        val total = wavs.size
        val clipsWith = AtomicInteger(); val squiggles = AtomicInteger()
        val noDetect = AtomicInteger(); val failed = AtomicInteger()
        val cwt = AtomicInteger(); val done = AtomicInteger()
        val manifest = ConcurrentLinkedQueue<String>()
        // CWT is rendered on the CPU (thread-safe across cores: kernelsFor is synchronized, each bank is
        // local). The single-device GPU path is NOT safe to hammer from every core — run the separate
        // "CWT images (GPU)" button on G:\squiggles afterwards if GPU speed is wanted.
        onProgress(Progress(0, total, "Scanning $total clips for squiggles…"))

        val pool = Executors.newFixedThreadPool(workers)
        try {
            wavs.map { wav ->
                pool.submit {
                    if (cancel.get()) return@submit
                    try {
                        // Skip monster files before decoding (a >50 MB WAV would balloon memory ×all-cores).
                        val pcm = if (wav.length() > 50_000_000L) null else AudioDecoder.decode(wav)
                        if (pcm == null || pcm.isEmpty()) failed.incrementAndGet()
                        else {
                            val events = MultiRidgeExtractor().detect(pcm, 0, pcm.size, SR)
                            if (events.isEmpty()) noDetect.incrementAndGet()
                            else {
                                val id = wav.nameWithoutExtension
                                val pad = (PAD_SEC * SR).toInt(); val minLen = (MIN_KEEP_SEC * SR).toInt()
                                var kept = 0
                                for ((ei, e) in events.withIndex()) {
                                    var a = ((e.t0Sec * SR).toInt() - pad).coerceAtLeast(0)
                                    var b = ((e.t1Sec * SR).toInt() + pad).coerceAtMost(pcm.size)
                                    if ((b - a).toDouble() / SR > MAX_SQUIGGLE_SEC) continue
                                    if (b - a < minLen) {
                                        val mid = (a + b) / 2
                                        a = (mid - minLen / 2).coerceAtLeast(0)
                                        b = (a + minLen).coerceAtMost(pcm.size)
                                        a = (b - minLen).coerceAtLeast(0)
                                    }
                                    val startMs = a * 1000L / SR; val endMs = b * 1000L / SR
                                    val vMs = (e.vertexTimeSec * 1000).toInt()
                                    val vHz = e.vertexFreqHz.toInt()
                                    val durMs = endMs - startMs
                                    val r2i = (e.rSquared * 100).toInt()
                                    val name = "${id}__sq${ei}__${startMs}-${endMs}ms__v${vMs}ms_${vHz}Hz__dur${durMs}__r2${r2i}.wav"
                                    val out = File(outDir, name)
                                    val slice = pcm.copyOfRange(a, b)
                                    if (!out.exists()) AudioDecoder.writeWavMono16(slice, SR, out)
                                    if (renderCwt) {
                                        val jpg = File(outDir, "${out.nameWithoutExtension}.jpg")
                                        if (!jpg.exists()) runCatching {
                                            SpectrogramRenderer.renderCwtJpg(slice, SR, jpg, useGpu = false)
                                        }.onSuccess { cwt.incrementAndGet() }
                                    }
                                    manifest.add(
                                        "$id,$ei,$startMs,$endMs,$durMs,$vMs,$vHz," +
                                        "${"%.6f".format(e.curvature)},${"%.3f".format(e.rSquared)}," +
                                        "${e.frameCount},${"%.6f".format(e.meanEnergy)},${out.name}," +
                                        wav.absolutePath.replace(',', ';'))   // parent clip path (for atlas click-through)
                                    kept++
                                }
                                if (kept > 0) { clipsWith.incrementAndGet(); squiggles.addAndGet(kept) }
                                else noDetect.incrementAndGet()
                            }
                        }
                    } catch (e: Exception) {
                        failed.incrementAndGet(); System.err.println("squiggle ${wav.name}: ${e.message}")
                    }
                    val d = done.incrementAndGet()
                    if (d % 50 == 0 || d == total)
                        onProgress(Progress(d, total, "Sweeping squiggles: $d/$total · ${squiggles.get()} found"))
                }
            }.forEach { it.get() }
        } finally { pool.shutdown() }

        // Manifest is fully derived → rewrite it each run (rows sorted for stable diffs).
        File(outDir, "squiggles_manifest.csv").bufferedWriter().use { w ->
            w.write("id,squiggleIdx,startMs,endMs,durMs,vertexMs,vertexHz,curvature,r2,frames,meanEnergy,file,parentPath\n")
            for (row in manifest.sorted()) { w.write(row); w.write("\n") }
        }
        return Summary(total, clipsWith.get(), squiggles.get(), noDetect.get(), failed.get(),
            cwt.get(), (System.nanoTime() - startNs) / 1e9, cancel.get(), outDir)
    }
}
