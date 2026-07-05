package com.example.FFTT04M.desktop

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headless cough harvester: run the on-the-fly cough-isolation algorithm over a whole corpus and extract
 * the trimmed coughs into a review folder (bucketed by filename-metadata agreement) + a manifest, so the
 * user can hand-review a pre-sorted pile instead of the raw mass. Non-destructive; parallel over all
 * cores; DSP-only (no HuBERT), so it's compute- and time-cheap.
 *
 * Run: ./gradlew :desktop:harvestCoughs -Dharvest.source=D:\AndroidProjects\ALLDATA
 */
object HarvestCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val source = File(args.getOrNull(0)?.takeIf { it.isNotBlank() }
            ?: System.getProperty("harvest.source")?.takeIf { it.isNotBlank() } ?: File(repo, "ALLDATA").path)
        val outDir = File(args.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: System.getProperty("harvest.out")?.takeIf { it.isNotBlank() } ?: File(repo, "cough_harvest").path)
        if (!source.isDirectory) { println("no source dir: $source"); return }
        val cores = Runtime.getRuntime().availableProcessors()
        println("=== HARVEST coughs: $source → $outDir  ($cores cores) ===")

        val s = CoughIsolator.harvest(source, outDir, AtomicBoolean(false)) { p ->
            if (p.done % 1000 == 0 || p.done == p.total) println("  ${p.message}")
        }
        println("=== complete in ${"%.1f".format(s.elapsedS)}s ===")
        println("scanned ${s.total}  ·  coughs extracted ${s.confirmed + s.foundInOther + s.unknownMeta} " +
            "(confirmed ${s.confirmed}, found-in-other ${s.foundInOther}, unknown-meta ${s.unknownMeta})  ·  " +
            "no-detect ${s.noDetect}  ·  failed ${s.failed}")
        println("→ review the WAVs under $outDir (3 buckets) + harvest_manifest.csv")
    }
}
