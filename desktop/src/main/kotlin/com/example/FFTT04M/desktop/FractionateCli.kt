package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.fractionation.*
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Headless validation runner for the sound-fractionation methods (SOUND_FRACTIONATION.md §3).
 *
 * Runs every pure-Kotlin method over a folder of audio clips, prints a comparison table, writes
 * one `<method>_segments.jsonl` per method, and renders a boundary-overlay PNG per clip
 * (spectrogram on top, one lane per method showing its segment boundaries) so boundaries can be
 * eyeballed and methods compared as the §1 table predicts.
 *
 * Run:  ./gradlew :desktop:fractionateCli --args="D:\AndroidProjects\true_cough"
 * Args: [inputDir] [outputDir]
 */
object FractionateCli {

    private val pureKotlinMethods: List<Fractionator> = listOf(
        EnergyOnset(),
        SpectralFluxOnset(),
        SyllableNucleus(),
        CoughPhasesFractionator(),
        FeatureChangepoint(),
        FeatureClusterBoundaries(),
    )

    // One distinct colour per method lane / overlay (7th for HuBERT when active).
    private val laneColors = listOf(
        Color(0xFF5252), Color(0xFFB300), Color(0x69F0AE),
        Color(0x40C4FF), Color(0xE040FB), Color(0xFFFFFF), Color(0xFFD740),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        // Include HuBERT (method 6) as a 7th method when its ONNX model is present.
        val methods: List<Fractionator> =
            if (HubertKMeansUnits.available) pureKotlinMethods + HubertKMeansUnits
            else pureKotlinMethods
        if (!HubertKMeansUnits.available)
            println("(HuBERT method INACTIVE — ${HubertKMeansUnits.unavailableReason})\n")
        val inDir = File(args.getOrNull(0) ?: "D:\\AndroidProjects\\true_cough")
        val outDir = File(args.getOrNull(1) ?: File(System.getProperty("user.home"), "FFTT04M_fractionation_validation").path)
        outDir.mkdirs()
        val pngDir = File(outDir, "overlays").apply { mkdirs() }

        if (!inDir.isDirectory) { System.err.println("Not a directory: $inDir"); return }
        val files = (inDir.listFiles { f -> f.isFile && f.extension.equals("wav", true) } ?: emptyArray())
            .sortedBy { it.name }
        if (files.isEmpty()) { System.err.println("No .wav files in $inDir"); return }

        println("Fractionation validation — ${files.size} clip(s) from $inDir")
        println("Output → $outDir\n")

        // method name -> JSONL writer
        val writers = methods.associate { m ->
            m.name to File(outDir, "${m.name.replace(" ", "_")}_segments.jsonl").bufferedWriter()
        }

        // Accumulators for the comparison table: method -> (totalSegs, sumDurMs, count, minSeg, maxSeg)
        data class Stat(var segs: Int = 0, var clips: Int = 0, var nonTrivial: Int = 0)
        val stats = methods.associate { it.name to Stat() }

        // Per-file table header
        val colW = 16
        val header = buildString {
            append("clip".padEnd(28)).append("dur".padStart(7)).append("  ")
            for (m in methods) append(shortName(m.name).padStart(colW))
        }
        println(header)
        println("-".repeat(header.length))

        for (file in files) {
            val pcm = AudioDecoder.decode(file)
            if (pcm == null) { System.err.println("decode failed: ${file.name}"); continue }
            val sr = 44100
            val durMs = (pcm.size.toDouble() / sr * 1000).toInt()

            val perMethod = LinkedHashMap<String, List<Segment>>()
            for (m in methods) {
                val segs = try { m.fractionate(pcm, sr) } catch (e: Exception) {
                    System.err.println("${m.name} failed on ${file.name}: ${e.message}"); emptyList()
                }
                perMethod[m.name] = segs
                val st = stats.getValue(m.name)
                st.segs += segs.size; st.clips++
                if (segs.size > 1) st.nonTrivial++
                writers.getValue(m.name).let { w ->
                    for (s in segs) {
                        val lbl = s.label?.let { "\"$it\"" } ?: "null"
                        val cid = s.clusterId?.toString() ?: "null"
                        w.write("""{"file":"${file.name}","method":"${m.name}","startMs":${s.startMs},"endMs":${s.endMs},"label":$lbl,"clusterId":$cid}""")
                        w.newLine()
                    }
                }
            }

            // Row
            val row = buildString {
                append(file.name.take(27).padEnd(28))
                append("${durMs}ms".padStart(7)).append("  ")
                for (m in methods) append(perMethod.getValue(m.name).size.toString().padStart(colW))
            }
            println(row)

            renderOverlay(pcm, sr, durMs, perMethod, File(pngDir, "${file.nameWithoutExtension}.png"))
        }

        writers.values.forEach { it.flush(); it.close() }

        // Comparison summary
        println("\n=== Method comparison (over ${files.size} clips) ===")
        println("method".padEnd(26) + "segs".padStart(8) + "avg/clip".padStart(10) + "multi-seg clips".padStart(18))
        for (m in methods) {
            val st = stats.getValue(m.name)
            val avg = if (st.clips > 0) st.segs.toDouble() / st.clips else 0.0
            println(m.name.padEnd(26) + st.segs.toString().padStart(8) +
                String.format("%.2f", avg).padStart(10) +
                "${st.nonTrivial}/${st.clips}".padStart(18))
        }
        println("\nJSONL + overlays written to: $outDir")
    }

    private fun shortName(n: String): String = when (n) {
        "Energy Onset" -> "Energy"
        "Spectral Flux Onset" -> "SpecFlux"
        "Syllable Nucleus" -> "Syllable"
        "Cough Phases" -> "CoughPh"
        "Feature Changepoint" -> "Changept"
        "Feature Cluster Boundaries" -> "Clusters"
        "HuBERT K-Means Units" -> "HuBERT"
        else -> n.take(8)
    }

    // ---- Overlay rendering ------------------------------------------------------------------------

    /** Spectrogram on top, then one lane per method with its segment boundaries drawn over time. */
    private fun renderOverlay(pcm: FloatArray, sr: Int, durMs: Int,
                              perMethod: Map<String, List<Segment>>, out: File) {
        // Base spectrogram from the existing renderer (512×512), reused as a stretched band.
        val tmp = File.createTempFile("spec", ".png")
        SpectrogramRenderer.renderFftPng(pcm, sr, tmp)
        val spec = try { ImageIO.read(tmp) } catch (e: Exception) { null }
        tmp.delete()

        val marginX = 90
        val W = 1100
        val specH = 260
        val laneH = 30
        val H = specH + perMethod.size * laneH + 24
        val plotW = W - marginX - 12

        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x14, 0x14, 0x18); g.fillRect(0, 0, W, H)

        // Spectrogram band (stretch the square render to the plot width / band height).
        if (spec != null) {
            g.drawImage(spec, marginX, 8, plotW, specH, null)
        }
        g.color = Color(0x55, 0x55, 0x60); g.drawRect(marginX, 8, plotW, specH)

        fun xOf(ms: Int): Int = marginX + (if (durMs > 0) ms.toDouble() / durMs else 0.0).times(plotW).toInt()

        g.font = Font("SansSerif", Font.PLAIN, 11)
        var lane = 0
        for ((name, segs) in perMethod) {
            val y = specH + 12 + lane * laneH
            val color = laneColors[lane % laneColors.size]
            // lane background
            g.color = Color(0x1d, 0x1d, 0x24); g.fillRect(marginX, y, plotW, laneH - 4)
            // alternating segment shading
            for ((i, s) in segs.withIndex()) {
                val x0 = xOf(s.startMs); val x1 = xOf(s.endMs)
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, if (i % 2 == 0) 0.22f else 0.10f)
                g.color = color; g.fillRect(x0, y, (x1 - x0).coerceAtLeast(1), laneH - 4)
            }
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f)
            // boundary lines
            g.color = color; g.stroke = BasicStroke(1.4f)
            for (s in segs) { val x = xOf(s.startMs); g.drawLine(x, y, x, y + laneH - 4) }
            // label + count
            g.color = Color(0xC8, 0xC8, 0xD0)
            g.drawString("${shortName(name)} (${segs.size})", 6, y + laneH / 2 + 2)
            lane++
        }

        // time axis ticks (every 250 ms)
        g.color = Color(0x88, 0x88, 0x92); g.stroke = BasicStroke(1f)
        var t = 0
        while (t <= durMs) {
            val x = xOf(t); g.drawLine(x, specH + 6, x, specH + 10)
            g.drawString("${t}ms", x - 12, H - 4); t += 250
        }
        g.dispose()
        ImageIO.write(img, "png", out)
    }
}
