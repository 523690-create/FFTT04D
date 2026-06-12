package com.example.FFTT04M.desktop

import java.io.File
import java.util.concurrent.Executors
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

    @Volatile private var cancelRequested = false
    fun cancel() { cancelRequested = true }

    /** Render [mode]'s image for every WAV under [folder] that doesn't already have one. */
    fun run(folder: File, mode: Mode, onProgress: (Progress) -> Unit): Summary {
        cancelRequested = false
        val startNs = System.nanoTime()

        val wavs = folder.walkTopDown()
            .filter { it.isFile && it.extension.equals("wav", true) }.toList()
        val todo = wavs.filter { !targetFor(it, mode).isFile }
        val total = todo.size

        // One device → set the renderer's mode once for the whole pass.
        val wantGpu = mode == Mode.CWT_GPU
        SpectrogramRenderer.useGpu = wantGpu
        val gpuReady = wantGpu && GpuFft.available()
        val device = when {
            mode == Mode.CWT_GPU && gpuReady -> GpuFft.deviceName() ?: "GPU"
            mode == Mode.CWT_GPU             -> "CPU ($workers cores) — GPU unavailable"
            else                              -> "CPU ($workers cores)"
        }
        onProgress(Progress(0, total,
            "Scanning ${wavs.size} WAVs — $total need ${mode.label} · $device"))

        val rendered = AtomicInteger()
        val failed = AtomicInteger()
        val done = AtomicInteger()

        val pool = Executors.newFixedThreadPool(workers)
        try {
            val futures = todo.map { wav ->
                pool.submit {
                    if (!cancelRequested) {
                        try {
                            val out = targetFor(wav, mode)
                            val pcm = AudioDecoder.decode(wav)   // canonical 44.1 kHz mono WAV
                            if (pcm != null && pcm.isNotEmpty()) {
                                when (mode) {
                                    Mode.FFT -> SpectrogramRenderer.renderFftPng(pcm, 44100, out)
                                    Mode.CWT_CPU, Mode.CWT_GPU -> SpectrogramRenderer.renderCwtJpg(pcm, 44100, out)
                                }
                                rendered.incrementAndGet()
                            } else failed.incrementAndGet()
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
            futures.forEach { it.get() }
        } finally {
            pool.shutdown()
        }

        return Summary(
            total = total, rendered = rendered.get(), skipped = wavs.size - total,
            failed = failed.get(), elapsedS = (System.nanoTime() - startNs) / 1e9,
            cancelled = cancelRequested, device = device
        )
    }

    private fun targetFor(wav: File, mode: Mode): File =
        File(wav.parentFile, "${wav.nameWithoutExtension}.${mode.ext}")
}
