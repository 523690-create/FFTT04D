package com.example.FFTT04M.desktop

import com.google.gson.Gson
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.sound.sampled.AudioSystem
import javax.swing.BorderFactory
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

/**
 * Phoneme-FFT atlas breakout window. One cell per codebook phoneme, class-coloured, showing:
 *   - the AVERAGE FFT magnitude spectrum (± 1σ) of its member windows,
 *   - the located RIDGE ("squiggle") — tracked f(t) points + start/peak/end frequencies,
 *   - three exemplar-window tiles: FFT spectrogram · MFCC-gram · CWT scalogram (tall & narrow), and
 *   - click-to-play the exemplar window audio.
 * Reads data/codebooks/<tag>_phoneme_fft.json (+ <tag>_phoneme_fft/) from [PhonemeFftCli].
 */
object PhonemeFftAtlas {

    private class RidgePt(val tSec: Double, val hz: Double)
    private class Ridge(val durSec: Double, val startHz: Double, val peakHz: Double, val endHz: Double,
                        val vertexSec: Double, val r2: Double, val winSec: Double, val pts: List<RidgePt>)
    private class Ph(val code: String, val letter: String, val label: String, val n: Int,
                     val avg: DoubleArray, val std: DoubleArray, val fft: File?, val mfcc: File?, val cwt: File?,
                     val wav: File?, val sMs: Int, val eMs: Int, val ridge: Ridge?)

    fun show(parent: Component?) {
        val cbDir = Workspace.dir("codebooks")
        val json = cbDir.listFiles { f -> f.name.endsWith("_phoneme_fft.json") }?.sortedBy { it.name }?.firstOrNull()
        if (json == null) {
            JOptionPane.showMessageDialog(parent,
                "No phoneme-FFT data yet.\n\nBuild it once (GPU):\n  gradlew :desktop:phonemeFft -PuseOnnxGpu",
                "Phoneme FFT atlas", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        @Suppress("UNCHECKED_CAST")
        val root = Gson().fromJson(json.readText(), Map::class.java) as Map<String, Any>
        val tag = root["tag"] as? String ?: "p3"
        val dir = File(cbDir, "${tag}_phoneme_fft")
        fun f(m: Map<String, Any>, k: String) = (m[k] as? String)?.let { File(dir, it) }?.takeIf { it.isFile }
        val phs = (root["phonemes"] as List<*>).map { it as Map<String, Any> }.mapNotNull { m ->
            fun arr(k: String): DoubleArray? = (m[k] as? List<*>)?.map { (it as Number).toDouble() }?.toDoubleArray()
            if ((m["n"] as Number).toInt() <= 0) return@mapNotNull null
            val avgA = arr("avgMag") ?: return@mapNotNull null
            val stdA = arr("stdMag") ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val rm = m["ridge"] as? Map<String, Any>
            val ridge = rm?.let {
                fun d(k: String) = (it[k] as? Number)?.toDouble() ?: 0.0
                val pts = (it["points"] as? List<*>)?.mapNotNull { p -> (p as? List<*>)?.let { q -> RidgePt((q[0] as Number).toDouble(), (q[1] as Number).toDouble()) } } ?: emptyList()
                Ridge(d("durSec"), d("startHz"), d("peakHz"), d("endHz"), d("vertexSec"), d("r2"), d("winSec"), pts)
            }
            Ph(m["code"] as String, m["letter"] as String, m["label"] as String, (m["n"] as Number).toInt(),
                avgA, stdA, f(m, "fftPng"), f(m, "mfccPng"), f(m, "cwtPng"),
                (m["wav"] as? String)?.let { File(it) }?.takeIf { it.isFile },
                (m["exStartMs"] as? Number)?.toInt() ?: 0, (m["exEndMs"] as? Number)?.toInt() ?: 0, ridge)
        }.sortedWith(compareBy({ it.letter }, { it.code }))

        val vals = phs.flatMap { it.avg.toList() }.filter { it > -170 }
        val gMin = vals.minOrNull() ?: -120.0; val gMax = vals.maxOrNull() ?: -20.0

        SwingUtilities.invokeLater {
            val grid = JPanel(GridLayout(0, 3, 8, 8)).apply { background = Color(0x1e, 0x1e, 0x22); border = BorderFactory.createEmptyBorder(8, 8, 8, 8) }
            phs.forEach { grid.add(Cell(it, gMin, gMax)) }
            val counts = phs.groupingBy { it.label }.eachCount().entries.sortedByDescending { it.value }.joinToString("  ") { "${it.key}:${it.value}" }
            val hdr = JLabel("  Phoneme FFT atlas — ${phs.size} phonemes  ($counts)   ·   click a cell to play its exemplar", SwingConstants.LEFT).apply {
                foreground = Color(0xdd, 0xdd, 0xdd); background = Color(0x14, 0x14, 0x18); isOpaque = true; border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            }
            JFrame("Phoneme FFT atlas — $tag").apply {
                contentPane.background = Color(0x1e, 0x1e, 0x22)
                contentPane.add(hdr, BorderLayout.NORTH)
                contentPane.add(JScrollPane(grid).apply { verticalScrollBar.unitIncrement = 24; border = null }, BorderLayout.CENTER)
                size = Dimension(1200, 860); setLocationByPlatform(true); defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE; isVisible = true
            }
        }
    }

    private class Cell(val ph: Ph, val gMin: Double, val gMax: Double) : JPanel() {
        private val fftImg = ph.fft?.let { runCatching { ImageIO.read(it) }.getOrNull() }
        private val mfccImg = ph.mfcc?.let { runCatching { ImageIO.read(it) }.getOrNull() }
        private val cwtImg = ph.cwt?.let { runCatching { ImageIO.read(it) }.getOrNull() }
        init {
            preferredSize = Dimension(400, 250); background = Color(0x26, 0x26, 0x2c)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "${ph.code} · ${ph.label} — click to play"
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent?) { ph.wav?.let { PhonemePlayer.show(null, it) } }
            })
        }

        override fun paintComponent(g0: Graphics) {
            super.paintComponent(g0)
            val g = g0 as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width; val h = height; val cc = PhonemeCloud.classColor(ph.letter)
            // header (full width)
            g.color = Color(cc.red, cc.green, cc.blue, 210); g.fillRect(0, 0, w, 20)
            g.color = Color.white; g.font = g.font.deriveFont(11f); g.drawString("${ph.code}  ${ph.label}  n=${ph.n}", 4, 14)

            // FFT + ridge are TALL (full cell height) and NARROW, side by side on the left.
            val cy = 22; val ch = h - cy - 2; val colW = 74
            drawTile(g, fftImg, 0, cy, colW, ch, "FFT")
            drawRidge(g, colW + 2, cy, colW, ch, cc)

            // right region: avg spectrum (top) · MFCC | CWT (middle) · ridge label (bottom)
            val rx = 2 * colW + 6; val rw = w - rx - 2; val specH = 78
            drawSpectrum(g, rx, cy, rw, specH, cc)
            val tY = cy + specH + 14; val tH = ch - specH - 14 - 16; val hw = (rw - 2) / 2
            drawTile(g, mfccImg, rx, tY, hw, tH, "MFCC")
            drawTile(g, cwtImg, rx + hw + 2, tY, rw - hw - 2, tH, "CWT")
            g.color = Color(0xcc, 0xcc, 0xcc); g.font = g.font.deriveFont(9.5f)
            val r = ph.ridge
            val txt = if (r != null && r.pts.size >= 3)
                "ridge ${(r.durSec * 1000).toInt()}ms  ${r.startHz.toInt()}→${r.peakHz.toInt()}→${r.endHz.toInt()}Hz  r²=${"%.2f".format(r.r2)}"
            else "no ridge"
            g.drawString(txt, rx, h - 6)
        }

        private fun drawSpectrum(g: Graphics2D, x: Int, y: Int, w: Int, h: Int, cc: Color) {
            g.color = Color(0x2c, 0x2c, 0x34); g.fillRect(x, y, w, h)
            val n = ph.avg.size
            fun yOf(v: Double) = y + h - (((v - gMin) / (gMax - gMin)).coerceIn(0.0, 1.0) * h).toInt()
            fun xOf(i: Int) = x + i * (w - 1) / (n - 1)
            g.color = Color(cc.red, cc.green, cc.blue, 60)
            val band = java.awt.Polygon()
            for (i in 0 until n) band.addPoint(xOf(i), yOf(ph.avg[i] + ph.std[i]))
            for (i in n - 1 downTo 0) band.addPoint(xOf(i), yOf(ph.avg[i] - ph.std[i]))
            g.fillPolygon(band)
            g.color = Color(cc.red, cc.green, cc.blue).brighter(); g.stroke = java.awt.BasicStroke(1.3f)
            for (i in 1 until n) g.drawLine(xOf(i - 1), yOf(ph.avg[i - 1]), xOf(i), yOf(ph.avg[i]))
            g.color = Color(0x77, 0x77, 0x77); g.font = g.font.deriveFont(8f); g.drawString("FFT 0–8k", x + 2, y + 9)
        }

        private fun drawRidge(g: Graphics2D, x: Int, y: Int, w: Int, h: Int, cc: Color) {
            g.color = Color(0x2c, 0x2c, 0x34); g.fillRect(x, y, w, h)
            val r = ph.ridge
            if (r == null || r.pts.size < 3) { g.color = Color(0x66, 0x66, 0x66); g.font = g.font.deriveFont(9f); g.drawString("no ridge", x + w / 2 - 22, y + h / 2); return }
            val loHz = 200.0; val hiHz = 1200.0; val winS = r.winSec.coerceAtLeast(1e-3)
            fun px(t: Double) = x + (t / winS * (w - 1)).toInt().coerceIn(0, w - 1)
            fun py(hz: Double) = y + h - (((hz - loHz) / (hiHz - loHz)).coerceIn(0.0, 1.0) * h).toInt()
            // tracked ridge polyline (the squiggle) + points
            g.color = Color(cc.red, cc.green, cc.blue).brighter(); g.stroke = java.awt.BasicStroke(1.6f)
            for (i in 1 until r.pts.size) g.drawLine(px(r.pts[i - 1].tSec), py(r.pts[i - 1].hz), px(r.pts[i].tSec), py(r.pts[i].hz))
            for (p in r.pts) { g.color = Color(0xcc, 0xcc, 0xcc, 180); g.fillOval(px(p.tSec) - 1, py(p.hz) - 1, 3, 3) }
            // start (green) / peak (yellow) / end (red) markers
            g.color = Color(0x66, 0xdd, 0x66); g.fillOval(px(r.pts.first().tSec) - 2, py(r.startHz) - 2, 5, 5)
            g.color = Color(0xee, 0xdd, 0x44); g.fillOval(px(r.vertexSec) - 2, py(r.peakHz) - 2, 5, 5)
            g.color = Color(0xee, 0x66, 0x66); g.fillOval(px(r.pts.last().tSec) - 2, py(r.endHz) - 2, 5, 5)
            g.color = Color(0x77, 0x77, 0x77); g.font = g.font.deriveFont(8f); g.drawString("ridge f(t) 200–1200", x + 2, y + 9)
        }

        private fun drawTile(g: Graphics2D, img: BufferedImage?, x: Int, y: Int, w: Int, h: Int, cap: String) {
            if (img != null) g.drawImage(img, x, y, w, h, null)
            else { g.color = Color(0x33, 0x33, 0x3a); g.fillRect(x, y, w, h) }
            g.color = Color(0x00, 0x00, 0x00, 130); g.fillRect(x, y, w, 12)
            g.color = Color(0xcc, 0xcc, 0xcc); g.font = g.font.deriveFont(8.5f); g.drawString(cap, x + 2, y + 10)
        }
    }
}
