package com.example.FFTT04M.desktop.fractionation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Method 6 — HuBERT K-Means Units (ONNX-gated).
 *
 * Mirrors the GPU-optional pattern in `GpuFft.kt`: the ONNX Runtime jar is on the classpath, but the
 * method only activates when a `hubert_base.onnx` model is found under `desktop/native/hubert/`.
 * [available] probes once (model present + ORT session creatable) and caches the result; until then
 * the UI button shows [unavailableReason] and [fractionate] returns the whole clip as one segment.
 *
 * Pipeline when active: resample to 16 kHz → run HuBERT → per-frame hidden embeddings `[T, H]` →
 * k-means over frames → boundary wherever the frame's cluster assignment changes → merge sub-minimum
 * segments. Each [Segment] carries its `clusterId` so sub-units can feed a codebook downstream.
 *
 * The model is expected to take a single float input shaped `[1, numSamples]` (16 kHz waveform) and
 * emit a single `[1, T, H]` hidden-state output — the standard `transformers` HuBERT ONNX export.
 * Input/output names are auto-detected from the session, so numbered or named exports both work.
 */
object HubertKMeansUnits : Fractionator {

    override val name = "HuBERT K-Means Units"

    private const val TARGET_SR = 16000
    private const val K = 12                 // per-clip unit count (boundary granularity, not a codebook)
    private const val MAX_ITER = 50
    private const val MIN_SEGMENT_MS = 60

    @Volatile private var probed = false
    @Volatile private var ok = false
    @Volatile private var reason: String? = null
    @Volatile private var modelFile: File? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var inputName: String? = null

    /** True when a HuBERT ONNX model is present and a session was created. Probed once, then cached. */
    val available: Boolean
        @Synchronized get() {
            if (probed) return ok
            probed = true
            ok = try {
                val model = locateModel()
                if (model == null) {
                    reason = "hubert_base.onnx not found. Place it in desktop/native/hubert/ " +
                        "(or set FFTT04D_HUBERT_MODEL to its path)."
                    false
                } else {
                    val e = OrtEnvironment.getEnvironment()
                    val s = e.createSession(model.absolutePath, OrtSession.SessionOptions())
                    val inName = s.inputNames.firstOrNull()
                    if (inName == null) {
                        reason = "model exposes no inputs"; false
                    } else {
                        env = e; session = s; modelFile = model; inputName = inName; true
                    }
                }
            } catch (t: Throwable) {
                reason = (t.message ?: t.toString()).lineSequence().firstOrNull()?.take(180)
                System.err.println("HubertKMeansUnits unavailable: ${t.message}")
                false
            }
            return ok
        }

    val unavailableReason: String
        get() = reason ?: "ONNX Runtime not present. Add the onnxruntime dependency and place " +
            "hubert_base.onnx in desktop/native/hubert/ to enable this method."

    override fun fractionate(x: FloatArray, sr: Int): List<Segment> {
        val durMs = (x.size.toDouble() / sr * 1000).toInt()
        if (!available) return listOf(Segment(0, durMs, label = "unavailable-ONNX-not-present"))
        if (x.isEmpty()) return emptyList()

        val emb = try {
            embed(if (sr == TARGET_SR) x else resample(x, sr, TARGET_SR))
        } catch (t: Throwable) {
            System.err.println("HubertKMeansUnits inference failed: ${t.message}")
            return listOf(Segment(0, durMs, label = "hubert-inference-failed"))
        }
        val numFrames = emb.size
        if (numFrames == 0) return listOf(Segment(0, durMs))
        val msPerFrame = durMs.toDouble() / numFrames     // derive stride from actual output length

        val k = min(K, numFrames)
        val labels = kmeans(emb, k)

        // Boundaries wherever the cluster assignment changes between consecutive frames.
        val raw = mutableListOf<Segment>()
        var segStart = 0
        for (f in 1 until numFrames) {
            if (labels[f] != labels[f - 1]) {
                raw.add(Segment((segStart * msPerFrame).toInt(), (f * msPerFrame).toInt(), clusterId = labels[segStart]))
                segStart = f
            }
        }
        raw.add(Segment((segStart * msPerFrame).toInt(), durMs, clusterId = labels[segStart]))

        return mergeShort(raw, MIN_SEGMENT_MS)
    }

    // ---- ONNX inference ---------------------------------------------------------------------------

    /** Run HuBERT on a 16 kHz mono waveform → `[T, H]` per-frame hidden embeddings. */
    private fun embed(wav16k: FloatArray): Array<FloatArray> {
        val e = env!!; val s = session!!; val inName = inputName!!
        OnnxTensor.createTensor(e, FloatBuffer.wrap(wav16k), longArrayOf(1, wav16k.size.toLong())).use { input ->
            s.run(java.util.Collections.singletonMap(inName, input)).use { result ->
                val out = result[0].value
                // Expected shape [1, T, H]; unwrap the batch dim.
                @Suppress("UNCHECKED_CAST")
                return (out as Array<Array<FloatArray>>)[0]
            }
        }
    }

    // ---- k-means (self-contained; the gated method stays independent of the other Fractionators) --

    private fun kmeans(data: Array<FloatArray>, k: Int): IntArray {
        val n = data.size
        val dim = data[0].size
        val labels = IntArray(n)
        if (k <= 1) return labels

        // Deterministic spread-out seeding (every n/k-th frame), so results are reproducible.
        val centroids = Array(k) { data[(it.toLong() * n / k).toInt().coerceIn(0, n - 1)].copyOf() }

        repeat(MAX_ITER) {
            var moved = false
            for (i in 0 until n) {
                var best = 0; var bestD = Float.MAX_VALUE
                for (c in 0 until k) {
                    var d = 0f
                    val cc = centroids[c]; val di = data[i]
                    for (j in 0 until dim) { val v = di[j] - cc[j]; d += v * v }
                    if (d < bestD) { bestD = d; best = c }
                }
                if (labels[i] != best) { labels[i] = best; moved = true }
            }
            if (!moved) return labels
            val sums = Array(k) { FloatArray(dim) }
            val counts = IntArray(k)
            for (i in 0 until n) { val c = labels[i]; counts[c]++; val di = data[i]; val sc = sums[c]; for (j in 0 until dim) sc[j] += di[j] }
            for (c in 0 until k) if (counts[c] > 0) for (j in 0 until dim) centroids[c][j] = sums[c][j] / counts[c]
        }
        return labels
    }

    /** Merge any segment shorter than [minMs] into its previous neighbour (keeps the earlier label). */
    private fun mergeShort(segs: List<Segment>, minMs: Int): List<Segment> {
        if (segs.size <= 1) return segs
        val out = mutableListOf<Segment>()
        for (s in segs) {
            val prev = out.lastOrNull()
            if (prev != null && (s.endMs - s.startMs) < minMs) {
                out[out.size - 1] = prev.copy(endMs = s.endMs)
            } else out.add(s)
        }
        return out
    }

    // ---- model discovery (parity with GpuFft's DLL search) ----------------------------------------

    private fun locateModel(): File? {
        System.getenv("FFTT04D_HUBERT_MODEL")?.let { val f = File(it); if (f.isFile) return f }
        val subPaths = listOf("native/hubert/hubert_base.onnx", "desktop/native/hubert/hubert_base.onnx")
        val bases = buildList {
            add(File(System.getProperty("user.dir")))
            var d: File? = jarDir()
            repeat(8) { d?.let { add(it); d = it.parentFile } }
        }
        for (b in bases) for (s in subPaths) { val f = File(b, s); if (f.isFile) return f }
        return null
    }

    private fun jarDir(): File? = try {
        val src = File(HubertKMeansUnits::class.java.protectionDomain.codeSource.location.toURI())
        if (src.isFile) src.parentFile else src
    } catch (t: Throwable) { null }

    // ---- resampling (linear; HuBERT needs 16 kHz) -------------------------------------------------

    private fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        val ratio = from.toDouble() / to
        val outLen = max(1, (x.size / ratio).toInt())
        return FloatArray(outLen) { i ->
            val pos = i * ratio
            val idx = pos.toInt()
            if (idx >= x.size - 1) x[x.size - 1]
            else { val frac = (pos - idx).toFloat(); x[idx] * (1 - frac) + x[idx + 1] * frac }
        }
    }
}
