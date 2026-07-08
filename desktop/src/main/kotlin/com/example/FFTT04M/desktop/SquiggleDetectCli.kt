package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.cough.MultiRidgeExtractor
import java.io.File

/**
 * Headless verification harness for [MultiRidgeExtractor]: runs multi-event squiggle detection over
 * whole clips and prints each event's [t0,t1] ms span + vertex time/freq, alongside the decode windows
 * (180 ms / 90 ms grid, mirroring PhonemePlayer/PhonemeCodebookCli) it covers and their existing codes —
 * so a detected event can be eyeballed against the known "chopped squiggle" repro clips without a GUI.
 *
 * Run: ./gradlew :desktop:squiggleDetect -Dsquiggle.clips=20260608_092808,20260610_081332,20260608_102058
 */
object SquiggleDetectCli {
    private const val SR = 44100
    private const val WIN_MS = 180
    private const val HOP_MS = 90

    private fun windowsFor(durMs: Int): List<Pair<Int, Int>> {
        val wins = ArrayList<Pair<Int, Int>>()
        if (durMs <= WIN_MS) wins.add(0 to durMs)
        else { var s = 0; while (s < durMs) { val e = (s + WIN_MS).coerceAtMost(durMs); if (e - s >= WIN_MS / 2) wins.add(s to e); if (e >= durMs) break; s += HOP_MS } }
        return wins
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val ids = (System.getProperty("squiggle.clips")?.takeIf { it.isNotBlank() }
            ?: "20260608_092808,20260610_081332,20260608_102058")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

        // Same root order/precedence as PhonemeFftCli/RidgeCheckCli: later roots override earlier ones
        // on a duplicate basename, so this resolves to the exact wav a codebook rebuild would have used.
        val roots = listOf("p3", "ALLDATA", "true_cough", "device_ingest").map { File(repo, it) }.filter { it.isDirectory }
        val wavById = roots.asSequence().flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension.equals("wav", true) }.associateBy { it.nameWithoutExtension }

        println("=== SQUIGGLE-DETECT over ${ids.size} clip(s) ===")
        val detector = MultiRidgeExtractor()
        for (id in ids) {
            val wav = wavById[id]
            if (wav == null) { println("\n[$id] no wav found under ${roots.joinToString { it.name }}"); continue }
            val pcm = AudioDecoder.decode(wav)
            if (pcm == null) { println("\n[$id] decode failed: ${wav.absolutePath}"); continue }
            val durMs = (pcm.size.toLong() * 1000 / SR).toInt().coerceAtLeast(1)
            val dec = DecodeStore.get(id)
            val word = dec?.word ?: emptyList()
            val wins = windowsFor(durMs)
            val events = detector.detect(pcm, 0, pcm.size, SR)
            println("\n[$id] wav=${wav.absolutePath}  dur=${durMs}ms  windows=${wins.size}  word=${word.joinToString(" ")}")
            println("  events=${events.size}")
            for ((i, ev) in events.withIndex()) {
                val msLo = (ev.t0Sec * 1000).toInt(); val msHi = (ev.t1Sec * 1000).toInt()
                val vMs = (ev.vertexTimeSec * 1000).toInt()
                val idxs = wins.indices.filter { wins[it].second > msLo && wins[it].first < msHi }
                if (idxs.isEmpty()) continue
                val wFrom = idxs.first(); val wTo = idxs.last()
                val vertexWin = idxs.minByOrNull { w -> kotlin.math.abs((wins[w].first + wins[w].second) / 2 - vMs) } ?: wFrom
                val codes = idxs.map { word.getOrElse(it) { "?" } }
                val vertexCode = word.getOrElse(vertexWin) { "?" }
                val uniform = codes.toSet().size <= 1
                println(String.format(
                    "  #%-2d t=%4d-%4dms (%3dms)  vertex=%4dms %5.0fHz  win %d-%d -> [%s]  vertexWin=%d code=%s%s",
                    i + 1, msLo, msHi, msHi - msLo, vMs, ev.vertexFreqHz, wFrom, wTo,
                    codes.joinToString(" "), vertexWin, vertexCode, if (uniform) "  (already uniform)" else "",
                ))
            }
        }
    }
}
