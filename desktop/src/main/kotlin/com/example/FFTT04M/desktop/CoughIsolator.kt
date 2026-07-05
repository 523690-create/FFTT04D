package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.cough.CoughAnalyzer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Isolate coughs": for each cough WAV under the chosen folders, trim the recording down to the
 * detected cough span (cutting the before/after), overwrite the WAV in place under its ORIGINAL
 * name, and delete its sibling `.png`/`.jpg` (they must be recomputed from the shortened clip).
 *
 * Skips non-cough clips (ALLDATA names containing "noncough"). Clips where no cough event is
 * detected are left untouched, so a detection miss never zeroes out a file. Originals are assumed
 * recoverable (zipped downloads / on-device), per the user's call — this overwrites in place.
 *
 * Parallel across cores, cancellable via a caller-owned [AtomicBoolean].
 */
object CoughIsolator {

    private const val SR = 44100
    private const val PAD_SEC = 0.08          // small margin kept before/after the cough
    private const val MIN_KEEP_SEC = 0.20     // never trim below this (avoid degenerate clips)

    data class Progress(val done: Int, val total: Int, val message: String)
    data class Summary(
        val total: Int, val trimmed: Int, val skippedNonCough: Int, val noDetect: Int,
        val failed: Int, val imagesDeleted: Int, val elapsedS: Double, val cancelled: Boolean
    )

    private val workers = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    fun run(folders: List<File>, cancel: AtomicBoolean, onProgress: (Progress) -> Unit): Summary {
        val startNs = System.nanoTime()
        val wavs = folders.filter { it.isDirectory }.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension.equals("wav", true) }.toList()
        }
        val total = wavs.size
        val trimmed = AtomicInteger(); val skippedNonCough = AtomicInteger()
        val noDetect = AtomicInteger(); val failed = AtomicInteger()
        val imagesDeleted = AtomicInteger(); val done = AtomicInteger()
        onProgress(Progress(0, total, "Scanning $total WAVs to isolate coughs…"))

        val pool = Executors.newFixedThreadPool(workers)
        try {
            wavs.map { wav ->
                pool.submit {
                    if (!cancel.get()) {
                        try {
                            when {
                                wav.name.contains("noncough", ignoreCase = true) ->
                                    skippedNonCough.incrementAndGet()
                                else -> {
                                    val pcm = AudioDecoder.decode(wav)
                                    if (pcm == null || pcm.isEmpty()) {
                                        failed.incrementAndGet()
                                    } else {
                                        val span = coughSpan(pcm)
                                        if (span == null) {
                                            noDetect.incrementAndGet()
                                        } else {
                                            AudioDecoder.writeWavMono16(
                                                pcm.copyOfRange(span.first, span.second), SR, wav)  // overwrite
                                            val base = wav.nameWithoutExtension
                                            for (ext in listOf("png", "jpg")) {
                                                val img = File(wav.parentFile, "$base.$ext")
                                                if (img.isFile && img.delete()) imagesDeleted.incrementAndGet()
                                            }
                                            trimmed.incrementAndGet()
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            failed.incrementAndGet()
                            System.err.println("isolate failed ${wav.name}: ${e.message}")
                        }
                        val d = done.incrementAndGet()
                        if (d % 20 == 0 || d == total)
                            onProgress(Progress(d, total,
                                "Isolating coughs: $d/$total · trimmed ${trimmed.get()}"))
                    }
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }

        return Summary(total, trimmed.get(), skippedNonCough.get(), noDetect.get(),
            failed.get(), imagesDeleted.get(), (System.nanoTime() - startNs) / 1e9, cancel.get())
    }

    /** Padded cough span `[start,end)` in samples, or null if no event is detected. */
    private fun coughSpan(pcm: FloatArray): Pair<Int, Int>? {
        val events = CoughAnalyzer().analyze(pcm, SR).events
        if (events.isEmpty()) return null
        val use = events.filter { it.speech.isLikelyCough }.ifEmpty { events }
        var lo = Int.MAX_VALUE; var hi = Int.MIN_VALUE
        for (e in use) { lo = minOf(lo, e.segment.startSample); hi = maxOf(hi, e.segment.endSample) }
        if (lo == Int.MAX_VALUE || hi <= lo) return null

        val pad = (PAD_SEC * SR).toInt()
        var a = (lo - pad).coerceAtLeast(0)
        var b = (hi + pad).coerceAtMost(pcm.size)
        val minLen = (MIN_KEEP_SEC * SR).toInt()
        if (b - a < minLen) {                       // widen symmetrically to the minimum keep length
            val mid = (a + b) / 2
            a = (mid - minLen / 2).coerceAtLeast(0)
            b = (a + minLen).coerceAtMost(pcm.size)
            a = (b - minLen).coerceAtLeast(0)
        }
        return a to b
    }

    // ---- non-destructive HARVEST: extract likely coughs to a review folder, keep originals ----------

    data class HarvestSummary(
        val total: Int, val confirmed: Int, val foundInOther: Int, val unknownMeta: Int,
        val noDetect: Int, val failed: Int, val elapsedS: Double, val cancelled: Boolean, val outDir: File,
    )

    /** Coarse ground-truth from the merged-dataset filename (source__id__RECTYPE__…). */
    private fun metaLabel(id: String): String {
        val parts = id.split("__")
        val src = parts.getOrNull(0)?.lowercase() ?: ""
        val rec = parts.getOrNull(2)?.lowercase() ?: ""
        return when {
            "cough" in rec -> "cough"                              // coswara cough-*, esc50 coughing
            "breath" in rec -> "breath"
            "vowel" in rec || "counting" in rec -> "speech"
            src == "urban8k" -> "noise"
            src == "train" -> "speech"
            else -> "unknown"
        }
    }

    /**
     * For every WAV under [source], run the same cough detection and — where a cough is found — write the
     * TRIMMED cough segment into [outDir], bucketed by whether the filename metadata agrees it's a cough:
     *   cough_confirmed/       metadata says cough → clean cough training data
     *   cough_found_in_other/  metadata is breath/speech/noise → a cough hiding inside another recording
     *   cough_unknown/         metadata unknown
     * plus harvest_manifest.csv. Originals are untouched. Parallel across all cores; DSP-only (no HuBERT).
     */
    fun harvest(source: File, outDir: File, cancel: AtomicBoolean, onProgress: (Progress) -> Unit): HarvestSummary {
        val startNs = System.nanoTime()
        val wavs = source.walkTopDown().filter { it.isFile && it.extension.equals("wav", true) }.toList()
        val total = wavs.size
        val dirs = listOf("cough_confirmed", "cough_found_in_other", "cough_unknown")
            .associateWith { File(outDir, it).apply { mkdirs() } }
        val confirmed = AtomicInteger(); val found = AtomicInteger(); val unknown = AtomicInteger()
        val noDetect = AtomicInteger(); val failed = AtomicInteger(); val done = AtomicInteger()
        val manifest = java.util.concurrent.ConcurrentLinkedQueue<String>()
        onProgress(Progress(0, total, "Scanning $total WAVs to harvest coughs…"))

        val pool = Executors.newFixedThreadPool(workers)
        try {
            wavs.map { wav ->
                pool.submit {
                    if (cancel.get()) return@submit
                    try {
                        // Skip monster files before decoding — a >50 MB WAV (~9 min) is never a single cough
                        // and decoding it would balloon memory (esp. ×all-cores).
                        val pcm = if (wav.length() > 50_000_000L) null else AudioDecoder.decode(wav)
                        if (pcm == null || pcm.isEmpty()) failed.incrementAndGet()
                        else {
                            // Require GENUINE likely-cough events (no whole-clip fallback), and extract each one
                            // tightly — so a breathing/vowel recording with no real cough is skipped, not grabbed
                            // whole (that was the 17 s "cough" false positives).
                            val events = CoughAnalyzer().analyze(pcm, SR).events.filter { it.speech.isLikelyCough }
                            if (events.isEmpty()) noDetect.incrementAndGet()
                            else {
                                val id = wav.nameWithoutExtension
                                val meta = metaLabel(id)
                                val bucket = when (meta) { "cough" -> "cough_confirmed"; "unknown" -> "cough_unknown"; else -> "cough_found_in_other" }
                                val pad = (PAD_SEC * SR).toInt(); val minLen = (MIN_KEEP_SEC * SR).toInt()
                                var kept = 0
                                for ((ei, e) in events.withIndex()) {
                                    var a = (e.segment.startSample - pad).coerceAtLeast(0)
                                    var b = (e.segment.endSample + pad).coerceAtMost(pcm.size)
                                    if ((b - a).toDouble() / SR > 4.0) continue   // a single cough is never >4 s → anomaly
                                    if (b - a < minLen) { val mid = (a + b) / 2; a = (mid - minLen / 2).coerceAtLeast(0); b = (a + minLen).coerceAtMost(pcm.size); a = (b - minLen).coerceAtLeast(0) }
                                    val startMs = a * 1000L / SR; val endMs = b * 1000L / SR
                                    val out = File(dirs[bucket]!!, "${id}__cough${ei}_${startMs}-${endMs}ms.wav")
                                    AudioDecoder.writeWavMono16(pcm.copyOfRange(a, b), SR, out)
                                    manifest.add("$id,$meta,$bucket,$startMs,$endMs,${endMs - startMs},${out.name}")
                                    kept++
                                }
                                if (kept > 0) (when (bucket) { "cough_confirmed" -> confirmed; "cough_unknown" -> unknown; else -> found }).incrementAndGet()
                                else noDetect.incrementAndGet()
                            }
                        }
                    } catch (e: Exception) { failed.incrementAndGet(); System.err.println("harvest ${wav.name}: ${e.message}") }
                    val d = done.incrementAndGet()
                    if (d % 100 == 0 || d == total)
                        onProgress(Progress(d, total, "Harvesting: $d/$total · confirmed ${confirmed.get()} · found-in-other ${found.get()}"))
                }
            }.forEach { it.get() }
        } finally { pool.shutdown() }

        File(outDir, "harvest_manifest.csv").bufferedWriter().use { w ->
            w.write("id,metadata,bucket,startMs,endMs,durMs,file\n")
            for (row in manifest.sorted()) { w.write(row); w.write("\n") }
        }
        return HarvestSummary(total, confirmed.get(), found.get(), unknown.get(),
            noDetect.get(), failed.get(), (System.nanoTime() - startNs) / 1e9, cancel.get(), outDir)
    }
}
