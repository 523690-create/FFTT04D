package com.example.FFTT04M.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Standalone, resumable analysis-image generation over a folder of ready WAVs (e.g. an ALLDATA
 * output). This is a deliberate *second pass*: the ALLDATA build now writes WAV + metadata only,
 * and the heavy spectrogram/scalogram rendering is run on demand here — which also lets CPU vs GPU
 * wavelets be compared live (each pass reports its device and clips/s).
 *
 * Every mode **skips any clip that already has its target image on disk**, so a pass can be stopped
 * and resumed, and re-running only fills gaps. Work is fanned across all cores; the GPU CWT path
 * funnels its FFT bank through the single device (see [GpuFft]) while the cores prep clips.
 */
object ImageBatch {

    enum class Mode(val label: String, val ext: String, val button: String) {
        FFT("FFT spectrogram (PNG)", "png", "FFT images"),
        CWT_CPU("CWT scalogram — CPU (JPG)", "jpg", "CWT images (CPU)"),
        CWT_GPU("CWT scalogram — GPU (JPG)", "jpg", "CWT images (GPU)"),
    }

    data class Progress(val done: Int, val total: Int, val message: String)
    data class Summary(
        val total: Int, val rendered: Int, val skipped: Int, val failed: Int,
        val elapsedS: Double, val cancelled: Boolean, val device: String
    )

    private val workers = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /**
     * Render [mode]'s image for every WAV under [folder] that doesn't already have one. [cancel] is
     * a per-run flag the caller owns, so several passes (e.g. CPU + GPU CWT) can run concurrently and
     * be cancelled independently — the GPU pass offloads its FFTs while the CPU passes use the cores.
     */
    fun run(folder: File, mode: Mode, cancel: AtomicBoolean,
            skipDirName: String? = null, onlyNames: Set<String>? = null,
            onProgress: (Progress) -> Unit): Summary {
        val startNs = System.nanoTime()

        val wantGpu = mode == Mode.CWT_GPU
        val gpuReady = wantGpu && GpuFft.available()
        val device = when {
            mode == Mode.CWT_GPU && gpuReady -> GpuFft.deviceName() ?: "GPU"
            mode == Mode.CWT_GPU             -> "CPU ($workers cores) — GPU unavailable"
            else                              -> "CPU ($workers cores)"
        }

        // ---- scan: find WAVs lacking their image. On a large ALLDATA tree (hundreds of thousands of
        // files) this phase alone takes seconds, so it reports progress and honours [cancel] — a
        // Cancel clicked here used to be ignored until the whole walk had finished.
        onProgress(Progress(0, 0, "Scanning ${folder.name} for WAVs needing ${mode.label}…"))
        var seen = 0
        val todo = ArrayList<File>()
        val walk = folder.walkTopDown()
            .onEnter { !cancel.get() }                        // stop descending once cancelled
            .filter { it.isFile && it.extension.equals("wav", true) }
            // optionally prune a subtree (e.g. `_rejected_lowP`) so a pass images only the review pile
            .filter { wav -> skipDirName == null || generateSequence(wav.parentFile) { it.parentFile }
                .takeWhile { it != folder.parentFile }.none { it.name.equals(skipDirName, true) } }
            // optionally restrict to an explicit filename allow-list (e.g. only cough-derived squiggles
            // in a mixed folder) — image only these, leaving the rest of the folder untouched
            .filter { wav -> onlyNames == null || wav.name in onlyNames }
        for (wav in walk) {
            if (cancel.get()) break
            seen++
            // A header-only WAV can't render, so it would count as "failed" again on every pass.
            if (!targetFor(wav, mode).isFile && AudioDecoder.hasSamples(wav)) todo.add(wav)
            if (seen % SCAN_PROGRESS_EVERY == 0)
                onProgress(Progress(0, 0, "Scanning… $seen WAVs seen, ${todo.size} need ${mode.label}"))
        }
        if (cancel.get()) {
            onProgress(Progress(0, 0, "Cancelled during scan ($seen WAVs seen)"))
            return Summary(total = 0, rendered = 0, skipped = seen, failed = 0,
                elapsedS = (System.nanoTime() - startNs) / 1e9, cancelled = true, device = device)
        }
        val total = todo.size
        onProgress(Progress(0, total, "Scanned $seen WAVs — $total need ${mode.label} · $device"))

        val rendered = AtomicInteger()
        val failed = AtomicInteger()
        val done = AtomicInteger()

        val pool = Executors.newFixedThreadPool(workers)
        try {
            val futures = todo.map { wav ->
                pool.submit {
                    if (!cancel.get()) {
                        try {
                            if (renderAtomically(wav, mode)) rendered.incrementAndGet()
                            else failed.incrementAndGet()
                        } catch (e: Exception) {
                            failed.incrementAndGet()
                            System.err.println("${mode.name} failed ${wav.name}: ${e.message}")
                        }
                        val d = done.incrementAndGet()
                        if (d % 10 == 0 || d == total) {
                            val cps = d / ((System.nanoTime() - startNs) / 1e9).coerceAtLeast(1e-9)
                            onProgress(Progress(d, total,
                                "${mode.label}: $d/$total · ${"%.1f".format(cps)} clips/s · $device"))
                        }
                    }
                }
            }
            // Wait in submission order, but stop waiting the moment a cancel lands: the not-yet-started
            // tasks are dropped outright (`cancel(false)` never interrupts a render in flight), so the
            // pass ends after at most one clip per worker instead of draining the whole queue.
            for (f in futures) {
                if (cancel.get()) break
                f.get()
            }
            if (cancel.get()) {
                futures.forEach { it.cancel(false) }
                onProgress(Progress(done.get(), total, "Cancelling — finishing ${workers.coerceAtMost(total)} clips in flight…"))
            }
        } finally {
            pool.shutdown()
            // In-flight renders finish (their .part files are renamed into place) before we report.
            pool.awaitTermination(10, TimeUnit.MINUTES)
        }

        return Summary(
            total = total, rendered = rendered.get(), skipped = seen - total,
            failed = failed.get(), elapsedS = (System.nanoTime() - startNs) / 1e9,
            cancelled = cancel.get(), device = device
        )
    }

    private const val SCAN_PROGRESS_EVERY = 5000

    /**
     * Render one clip's image via a `.part` sibling, then atomically rename it into place. The pass is
     * resumable purely by "target image exists", so a truncated image left by a kill / crash mid-write
     * would otherwise be taken as done forever and never re-rendered. Returns false if the WAV could
     * not be decoded.
     */
    private fun renderAtomically(wav: File, mode: Mode): Boolean {
        val out = targetFor(wav, mode)
        val pcm = AudioDecoder.decode(wav)   // canonical 44.1 kHz mono WAV
        if (pcm == null || pcm.isEmpty()) return false
        val part = File(out.parentFile, out.name + PART_SUFFIX)
        try {
            when (mode) {
                Mode.FFT -> SpectrogramRenderer.renderFftPng(pcm, 44100, part)
                Mode.CWT_CPU -> SpectrogramRenderer.renderCwtJpg(pcm, 44100, part, useGpu = false)
                Mode.CWT_GPU -> SpectrogramRenderer.renderCwtJpg(pcm, 44100, part, useGpu = true)
            }
            Files.move(part.toPath(), out.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            part.delete()   // no-op after a successful move; cleans up after a failed render
        }
        return true
    }

    /** Temp-file suffix used by [renderAtomically]; a leftover `*.png.part` means an interrupted render. */
    const val PART_SUFFIX = ".part"

    private fun targetFor(wav: File, mode: Mode): File =
        File(wav.parentFile, "${wav.nameWithoutExtension}.${mode.ext}")
}
