package com.example.FFTT04M.desktop

import com.google.gson.Gson
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * Phoneme-FFT atlas breakout window: one cell per codebook phoneme, showing BOTH its average FFT magnitude
 * spectrum (± 1σ band) and its exemplar-window FFT spectrogram, grouped + coloured by class. Reads the
 * precomputed data/codebooks/<tag>_phoneme_fft.json (+ <tag>_phoneme_fft/<code>.png) from [PhonemeFftCli].
 */
object PhonemeFftAtlas {

    private class Ph(val code: String, val letter: String, val label: String, val n: Int,
                     val avg: DoubleArray, val std: DoubleArray, val png: File?)

    fun show(parent: Component?) {
        val cbDir = Workspace.dir("codebooks")
        val json = cbDir.listFiles { f -> f.name.endsWith("_phoneme_fft.json") }?.sortedBy { it.name }?.firstOrNull()
        if (json == null) {
            javax.swing.JOptionPane.showMessageDialog(parent,
                "No phoneme-FFT data yet.\n\nBuild it once (GPU):\n  gradlew :desktop:phonemeFft -PuseOnnxGpu",
                "Phoneme FFT atlas", javax.swing.JOptionPane.INFORMATION_MESSAGE)
            return
        }
        @Suppress("UNCHECKED_CAST")
        val root = Gson().fromJson(json.readText(), Map::class.java) as Map<String, Any>
        val tag = root["tag"] as? String ?: "p3"
        val pngDir = File(cbDir, "${tag}_phoneme_fft")
        val phs = (root["phonemes"] as List<*>).map { it as Map<String, Any> }.map { m ->
            fun arr(k: String) = (m[k] as List<*>).map { (it as Number).toDouble() }.toDoubleArray()
            val ex = m["exemplar"] as? String
            Ph(m["code"] as String, m["letter"] as String, m["label"] as String, (m["n"] as Number).toInt(),
                arr("avgMag"), arr("stdMag"), ex?.let { File(pngDir, it) }?.takeIf { it.isFile })
        }.filter { it.n > 0 }.sortedWith(compareBy({ it.letter }, { it.code }))

        // global magnitude range (ignore the -180 dB empty-bin floor) so cells are comparable
        val vals = phs.flatMap { it.avg.toList() }.filter { it > -170 }
        val gMin = (vals.minOrNull() ?: -120.0); val gMax = (vals.maxOrNull() ?: -20.0)

        SwingUtilities.invokeLater {
            val grid = JPanel(GridLayout(0, 5, 8, 8)).apply { background = Color(0x1e, 0x1e, 0x22); border = javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8) }
            phs.forEach { grid.add(Cell(it, gMin, gMax)) }
            val counts = phs.groupingBy { it.label }.eachCount().entries.sortedByDescending { it.value }
                .joinToString("  ") { "${it.key}:${it.value}" }
            val header = JLabel("  Phoneme FFT atlas — ${phs.size} phonemes  ($counts)", SwingConstants.LEFT).apply {
                foreground = Color(0xdd, 0xdd, 0xdd); background = Color(0x14, 0x14, 0x18); isOpaque = true
                border = javax.swing.BorderFactory.createEmptyBorder(6, 8, 6, 8)
            }
            val frame = JFrame("Phoneme FFT atlas — $tag")
            frame.contentPane.background = Color(0x1e, 0x1e, 0x22)
            frame.contentPane.add(header, BorderLayout.NORTH)
            frame.contentPane.add(JScrollPane(grid).apply { verticalScrollBar.unitIncrement = 24; border = null }, BorderLayout.CENTER)
            frame.size = Dimension(1180, 820)
            frame.setLocationByPlatform(true)
            frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            frame.isVisible = true
        }
    }

    /** One phoneme: class-tinted header, average spectrum (±1σ) on top, exemplar spectrogram below. */
    private class Cell(val ph: Ph, val gMin: Double, val gMax: Double) : JPanel() {
        private val img = ph.png?.let { runCatching { ImageIO.read(it) }.getOrNull() }
        init { preferredSize = Dimension(210, 176); background = Color(0x26, 0x26, 0x2c) }

        override fun paintComponent(g0: Graphics) {
            super.paintComponent(g0)
            val g = g0 as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width; val cc = PhonemeCloud.classColor(ph.letter)
            // header
            g.color = Color(cc.red, cc.green, cc.blue, 210); g.fillRect(0, 0, w, 20)
            g.color = Color.white; g.font = g.font.deriveFont(11f)
            g.drawString("${ph.code}  ${ph.label}  n=${ph.n}", 4, 14)

            // ---- average spectrum (+ ±1σ band) ----
            val sy = 22; val sh = 84; val n = ph.avg.size
            fun yOf(v: Double): Int { val t = ((v - gMin) / (gMax - gMin)).coerceIn(0.0, 1.0); return sy + sh - (t * sh).toInt() }
            g.color = Color(0x2c, 0x2c, 0x34); g.fillRect(0, sy, w, sh)
            // ±1σ band
            g.color = Color(cc.red, cc.green, cc.blue, 60)
            val bandTop = IntArray(n); val bandBot = IntArray(n); val xs = IntArray(n)
            for (i in 0 until n) { xs[i] = i * (w - 1) / (n - 1); bandTop[i] = yOf(ph.avg[i] + ph.std[i]); bandBot[i] = yOf(ph.avg[i] - ph.std[i]) }
            val poly = java.awt.Polygon()
            for (i in 0 until n) poly.addPoint(xs[i], bandTop[i])
            for (i in n - 1 downTo 0) poly.addPoint(xs[i], bandBot[i])
            g.fillPolygon(poly)
            // mean curve (filled to baseline, then a bright line)
            g.color = Color(cc.red, cc.green, cc.blue, 110)
            val fill = java.awt.Polygon(); fill.addPoint(0, sy + sh)
            for (i in 0 until n) fill.addPoint(xs[i], yOf(ph.avg[i])); fill.addPoint(w - 1, sy + sh)
            g.fillPolygon(fill)
            g.color = Color(cc.red, cc.green, cc.blue).brighter(); g.stroke = java.awt.BasicStroke(1.4f)
            for (i in 1 until n) g.drawLine(xs[i - 1], yOf(ph.avg[i - 1]), xs[i], yOf(ph.avg[i]))
            g.color = Color(0x88, 0x88, 0x88); g.font = g.font.deriveFont(8.5f)
            g.drawString("0", 1, sy + sh - 1); g.drawString("8k", w - 14, sy + sh - 1)

            // ---- exemplar spectrogram ----
            val iy = sy + sh + 2; val ih = height - iy - 2
            if (img != null) g.drawImage(img, 0, iy, w, ih, null)
            else { g.color = Color(0x33, 0x33, 0x3a); g.fillRect(0, iy, w, ih); g.color = Color(0x77, 0x77, 0x77); g.drawString("(no exemplar)", w / 2 - 34, iy + ih / 2) }
        }
    }
}
