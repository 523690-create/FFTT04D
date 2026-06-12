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
}
