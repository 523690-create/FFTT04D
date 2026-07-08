package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.cough.CoughAnalyzer
import com.google.gson.JsonParser
import java.io.File

/**
 * Ridge ("squiggle") stats grouped by MANUAL LABEL, straight from the labelled clips (not the codebook
 * exemplars). For each labelled clip it runs the full CoughAnalyzer and collects every event's fitted
 * ridge, then reports per label: count, % negative curvature (downward ∩ chirp), median curvature/slope,
 * and median start→peak→end frequency. Answers "is curvature/slope negative for <label>?" directly.
 *
 * Run: ./gradlew :desktop:ridgeCheck -Dridge.labels=croup,dry hacking,bronchitis,typical bronchitis,dry
 */
object RidgeCheckCli {
    private const val SR = 44100

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val want = (System.getProperty("ridge.labels")?.takeIf { it.isNotBlank() } ?: "croup")
            .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val mcFile = File(Workspace.dir("."), "manual_comments.json").let { if (it.isFile) it else File(repo, "data/manual_comments.json") }
        if (!mcFile.isFile) { println("no manual_comments.json"); return }
        val manual = HashMap<String, String>()
        runCatching { JsonParser.parseString(mcFile.readText()).asJsonObject.entrySet().forEach { manual[it.key] = it.value.asString.trim().lowercase() } }
        val roots = listOf("p3", "ALLDATA", "true_cough", "device_ingest").map { File(repo, it) }.filter { it.isDirectory }
        val wavById = roots.asSequence().flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension.equals("wav", true) }.associateBy { it.nameWithoutExtension }
        println("=== RIDGE-BY-LABEL over ${manual.size} manual labels · wavs indexed ${wavById.size} · labels=${want} ===")

        val analyzer = CoughAnalyzer()
        data class R(val a: Double, val b: Double, val s: Double, val pk: Double, val e: Double, val r2: Double, val dur: Double)
        val byLabel = HashMap<String, MutableList<R>>()
        var clipsSeen = 0
        for ((id, lbl) in manual) {
            if (want.none { lbl.contains(it) }) continue
            val key = want.first { lbl.contains(it) }
            val wav = wavById[id] ?: run { println("  (missing wav: $id)"); continue }
            val pcm = AudioDecoder.decode(wav)?.also { rms(it) } ?: continue
            clipsSeen++
            for (e in analyzer.analyze(pcm, SR).events) {
                val g = e.ridge
                if (g.valid) byLabel.getOrPut(key) { ArrayList() }
                    .add(R(g.curvature, g.slope, g.startFreqHz, g.peakFreqHz, g.endFreqHz, g.rSquared, g.ridgeDurationSec))
            }
        }
        println("  analysed $clipsSeen matching clip(s)\n")
        fun med(x: List<Double>) = x.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        println(String.format("  %-18s %5s %8s %10s %9s %-22s", "label", "nRdg", "a<0(down)", "med curv", "med slope", "med start→peak→end Hz"))
        for (k in want) {
            val rs = byLabel[k] ?: emptyList<R>()
            if (rs.isEmpty()) { println(String.format("  %-18s %5d   (no valid ridges)", k, 0)); continue }
            val neg = rs.count { it.a < 0 }
            println(String.format("  %-18s %5d %6d/%-3d %10.0f %9.0f   %.0f→%.0f→%.0f  (medR²=%.2f)",
                k, rs.size, neg, rs.size, med(rs.map { it.a }), med(rs.map { it.b }),
                med(rs.map { it.s }), med(rs.map { it.pk }), med(rs.map { it.e }), med(rs.map { it.r2 })))
        }
    }

    private fun rms(pcm: FloatArray, target: Float = 0.1f) { var s = 0.0; for (v in pcm) s += v.toDouble() * v; val r = Math.sqrt(s / pcm.size.coerceAtLeast(1)); if (r > 1e-5) { val g = (target / r).toFloat(); for (i in pcm.indices) pcm[i] *= g } }
}
