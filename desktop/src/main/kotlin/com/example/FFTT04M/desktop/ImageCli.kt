package com.example.FFTT04M.desktop

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headless analysis-image generation over a folder of WAVs (same engine as the GUI's FFT/CWT buttons,
 * [ImageBatch]). Resumable (skips clips that already have their image) and fanned across all cores; the
 * CWT_GPU mode funnels its FFT bank through the GPU (jcufft) while the cores prep clips.
 *
 * Run: ./gradlew :desktop:cwtImages -Dimage.dir=D:\AndroidProjects\cough_harvest\cough_found_in_other -Dimage.skip=_rejected_lowP
 * Modes: -Dimage.mode=CWT_GPU (default) | CWT_CPU | FFT.  -Dimage.skip prunes a subtree by folder name.
 */
object ImageCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val dir = File(args.getOrNull(0)?.takeIf { it.isNotBlank() }
            ?: System.getProperty("image.dir")?.takeIf { it.isNotBlank() }
            ?: File(repo, "cough_harvest/cough_found_in_other").path)
        if (!dir.isDirectory) { println("no dir: $dir"); return }
        val mode = runCatching {
            ImageBatch.Mode.valueOf(System.getProperty("image.mode")?.takeIf { it.isNotBlank() }?.uppercase() ?: "CWT_GPU")
        }.getOrDefault(ImageBatch.Mode.CWT_GPU)
        val skip = System.getProperty("image.skip")?.takeIf { it.isNotBlank() }

        println("=== IMAGE ${mode.label} over $dir (skip=${skip ?: "none"}) ===")
        val sum = ImageBatch.run(dir, mode, AtomicBoolean(false), skip) { p ->
            if (p.done % 50 == 0 || p.done == p.total) println("  ${p.message}")
        }
        println("=== IMAGE done: rendered ${sum.rendered}, skipped ${sum.skipped}, failed ${sum.failed}, " +
            "${"%.1f".format(sum.elapsedS)}s, ${sum.device} ===")
    }
}
