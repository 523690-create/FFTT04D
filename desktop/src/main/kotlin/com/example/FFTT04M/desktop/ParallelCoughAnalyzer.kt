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
        val ffmpegFormats = setOf("ogg", "webm", "mp3", "m4a", "flac")
        when {
            ext == "wav" -> {}
            ext in ffmpegFormats && !AudioDecoder.ffmpegAvailable() ->
                return ClipResult(rec, 0.0, null, error = ".$ext needs ffmpeg (not found)")
            ext !in ffmpegFormats ->
                return ClipResult(rec, 0.0, null, error = "unsupported format .$ext")
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

    /**
     * Concatenate every clip's events into one `segments.jsonl` blob for training/export.
     * Each line is augmented with a `"dataset_metadata"` object carrying the source file path and
     * the dataset's own metadata (Coswara age/covid/country, ESC-50 category, Cough-Dataset-1
     * sidecar fields, etc.) so the export is self-describing.
     */
    fun toSegmentsJsonl(results: List<ClipResult>): String {
        val sb = StringBuilder()
        for (r in results) {
            val a = r.analysis ?: continue
            val meta = datasetMetaJson(r.recording)
            for (line in CoughSchemaJson.toJsonl(a, recordingId = r.recording.id).split('\n')) {
                if (line.isBlank()) continue
                if (line.endsWith("}")) {
                    sb.append(line.dropLast(1)).append(", \"dataset_metadata\": ").append(meta).append("}\n")
                } else {
                    sb.append(line).append('\n')
                }
            }
        }
        return sb.toString()
    }

    private fun datasetMetaJson(rec: AudioRecording): String {
        val m = LinkedHashMap<String, String>()
        m["source_file"] = rec.audioFile.absolutePath
        for ((k, v) in rec.metadata) m[k] = v.toString()
        return m.entries.joinToString(prefix = "{", postfix = "}") { (k, v) -> jsonStr(k) + ": " + jsonStr(v) }
    }

    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ") + "\""
}
