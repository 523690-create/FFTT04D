package com.example.FFTT04M.desktop

import java.io.File

/**
 * Headless "Build ALLDATA" — same [AllDataBuilder.build] the GUI button runs, for timing and
 * regression checks (e.g. a resumed build against a small sources folder) without the window.
 *
 * Run: ./gradlew :desktop:allDataBuild -Dalldata.src=<sources root> -Dalldata.out=<output dir>
 *      [-Dalldata.images=true]   (images default OFF here, unlike the GUI)
 */
object AllDataBuildCli {

    @JvmStatic
    fun main(args: Array<String>) {
        val src = System.getProperty("alldata.src")?.takeIf { it.isNotBlank() }
        val out = System.getProperty("alldata.out")?.takeIf { it.isNotBlank() }
        if (src == null || out == null) { println("need -Dalldata.src=<dir> -Dalldata.out=<dir>"); return }
        AllDataBuilder.generateImages = System.getProperty("alldata.images") == "true"

        val t0 = System.nanoTime()
        val s = AllDataBuilder.build(File(src), File(out)) { p ->
            if (p.phase != "Coswara" || p.message.contains("extract") || p.done == p.total)
                println("[%6.1fs] %s".format((System.nanoTime() - t0) / 1e9, p.message))
        }
        println("SUMMARY rows=${s.rows} converted=${s.converted} reused=${s.reused} failed=${s.failed} " +
            "images=${s.images} cancelled=${s.cancelled} bySource=${s.bySource} " +
            "elapsed=%.1fs".format((System.nanoTime() - t0) / 1e9))
    }
}
