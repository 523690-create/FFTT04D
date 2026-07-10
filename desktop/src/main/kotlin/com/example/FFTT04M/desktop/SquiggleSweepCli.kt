package com.example.FFTT04M.desktop

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headless runner / smoke-test for [SquiggleSweep] (also the autonomous entry point).
 *
 * Usage:
 *   ./gradlew :desktop:squiggleSweep -Dsweep.source=D:\AndroidProjects\true_cough -Dsweep.out=G:\squiggles -Dsweep.cwt=true
 */
object SquiggleSweepCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val source = File(
            args.getOrNull(0)
                ?: System.getProperty("sweep.source")?.takeIf { it.isNotBlank() }
                ?: (Workspace.repoRoot?.resolve("ALLDATA")?.absolutePath ?: "."))
        val outDir = File(System.getProperty("sweep.out")?.takeIf { it.isNotBlank() } ?: "G:\\squiggles")
        val cwt = System.getProperty("sweep.cwt")?.takeIf { it.isNotBlank() }?.toBoolean() ?: true

        println("=== SQUIGGLE SWEEP ===  source=$source  out=$outDir  cwt=$cwt")
        if (!source.isDirectory) { println("source is not a directory: $source"); return }

        val s = SquiggleSweep.run(source, outDir, cwt, AtomicBoolean(false)) { p ->
            if (p.done % 200 == 0 || p.done == p.total) println("  ${p.message}")
        }
        println(String.format(
            "done: %d squiggles from %d/%d clips (%d no-detect, %d failed), %d scalograms, %.1fs -> %s",
            s.squiggles, s.clipsWithSquiggles, s.totalClips, s.noDetect, s.failed,
            s.cwtRendered, s.elapsedS, s.outDir.absolutePath))
    }
}
