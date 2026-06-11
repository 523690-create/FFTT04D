package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.cough.CoughAnalysis
import com.example.FFTT04M.desktop.cough.CoughAnalyzer
import com.example.FFTT04M.desktop.cough.CoughSchemaJson
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Desktop batch driver for the Tier-1 cough engine — the SAME DSP the blue_sky Android app runs
 * (homologous [com.example.FFTT04M.desktop.cough] package), but fanned out across every CPU core.
 *
 * Each recording is decoded then analysed on a worker thread; the engine keeps [workers] clips in
 * flight at once. On a many-core desktop this is roughly a [workers]x throughput win over the old
 * single-threaded RMS/Peak loop, and it produces the full feature set (FFT q-ratio/fmax, ridge
 * parabola, T1/T2/T3 phases, 13-band MFCC, speech verdict) plus a `segments.jsonl` export.
 *
 * Note on GPU/NPU: genuinely offloading to the desktop GPU or NPU needs native bindings (CUDA/
 * OpenCL/DirectML/ONNX-Runtime) that aren't available to this dependency-light Java 8 build, so we
 * do not pretend to use them. The per-clip FFT/MFCC work is CPU-bound and embarrassingly parallel,
 * so saturating all cores is where the real desktop-resource win is — and that we do for real.
 */
class ParallelCoughAnalyzer(
    /** Worker threads. Defaults to every available core (the user asked for "more CPU cores"). */
    val workers: Int = Runtime.getRuntime().availableProcessors(),
    private val sampleRate: Int = 44100,
) {
    data class ClipResult(
        val recording: AudioRecording,
        val durationSec: Double,
        val analysis: CoughAnalysis?,   // null if decode failed / unsupported
        val error: String? = null,
    )

    /**
     * Analyse [recordings] in parallel.
     * @param onProgress called (completed, total) after each clip finishes, from worker threads.
     * Results are returned in the SAME order as [recordings].
     */
    fun analyzeAll(
        recordings: List<AudioRecording>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<ClipResult> {
        if (recordings.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(workers)
        val done = AtomicInteger(0)
        val total = recordings.size
        try {
            val futures = recordings.map { rec ->
                pool.submit<ClipResult> {
                    val r = analyzeOne(rec)
                    onProgress(done.incrementAndGet(), total)
                    r
                }
            }
            return futures.map { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    private fun analyzeOne(rec: AudioRecording): ClipResult {
        val ext = rec.audioFile.extension.lowercase()
        if (ext != "wav") {
            return ClipResult(rec, 0.0, null, error = "unsupported format .$ext (WebM/OGG need ffmpeg)")
        }
        return try {
            val pcm = AudioDecoder.decode(rec.audioFile)
                ?: return ClipResult(rec, 0.0, null, error = "decode failed")
            // A fresh analyzer per clip keeps worker threads independent (the engine holds reusable
            // scratch buffers, so it is not safe to share one instance across threads).
            val analysis = CoughAnalyzer().analyze(pcm, sampleRate)
            ClipResult(rec, pcm.size.toDouble() / sampleRate, analysis)
        } catch (e: Exception) {
            ClipResult(rec, 0.0, null, error = e.message ?: e.javaClass.simpleName)
        }
    }

    /** Concatenate every clip's events into one `segments.jsonl` blob for training/export. */
    fun toSegmentsJsonl(results: List<ClipResult>): String {
        val sb = StringBuilder()
        for (r in results) {
            val a = r.analysis ?: continue
            sb.append(CoughSchemaJson.toJsonl(a, recordingId = r.recording.id))
            if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
        }
        return sb.toString()
    }
}
