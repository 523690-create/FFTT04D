package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.cough.FFTUtils
import com.example.FFTT04M.desktop.cough.RidgeExtractor
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
import javax.swing.BorderFactory
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Squiggle atlas breakout window — the SAME format as [PhonemeFftAtlas], but one cell per detected
 * squiggle (from a G:\squiggles sweep folder). Each cell shows, over the extracted squiggle WAV:
 *   - the FFT spectrogram (tall/narrow) and the tracked RIDGE ("squiggle") f(t) polyline with
 *     start(green)/vertex(yellow)/end(red) markers,
 *   - the AVERAGE FFT magnitude spectrum (± 1σ over the clip's frames),
 *   - MFCC-gram + CWT scalogram tiles (CWT loaded from the sweep's <wav>.jpg), and
 *   - click-to-play (opens [PhonemePlayer] on the squiggle).
 * FFT + MFCC are rendered live from the WAV; the CWT is the pre-generated jpg. Cells build in the
 * background and appear incrementally.
 */
object SquiggleAtlas {

    private const val SR = 44100
    private const val LN10 = 2.302585

    private class Sq(
        val wav: File, val header: String, val cc: Color,
        val fftImg: BufferedImage?, val mfccImg: BufferedImage?, val cwtImg: BufferedImage?,
        val avg: DoubleArray, val std: DoubleArray,
        val pts: List<RidgeExtractor.RidgePoint>,
        val durMs: Int, val startHz: Int, val peakHz: Int, val endHz: Int, val vertexSec: Double, val r2: Double,
    )

    fun show(parent: Component?, dir: File, maxCells: Int = 500) {
        val wavs = dir.listFiles { f -> f.isFile && f.extension.equals("wav", true) }
            ?.sortedBy { it.name } ?: emptyList()
        if (wavs.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "No squiggle WAVs in ${dir.absolutePath}",
                "Squiggle atlas", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val shown = wavs.take(maxCells)
        val capped = wavs.size > shown.size

        SwingUtilities.invokeLater {
            val grid = JPanel(GridLayout(0, 3, 8, 8)).apply {
                background = Color(0x1e, 0x1e, 0x22); border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            }
            val hdr = JLabel("  Squiggle atlas — ${dir.name}  ·  ${shown.size} squiggles" +
                (if (capped) " (of ${wavs.size}, showing first ${shown.size})" else "") +
                "   ·   click a cell to play it", SwingConstants.LEFT).apply {
                foreground = Color(0xdd, 0xdd, 0xdd); background = Color(0x14, 0x14, 0x18); isOpaque = true
                border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            }
            JFrame("Squiggle atlas — ${dir.name}").apply {
                contentPane.background = Color(0x1e, 0x1e, 0x22)
                contentPane.add(hdr, BorderLayout.NORTH)
                contentPane.add(JScrollPane(grid).apply { verticalScrollBar.unitIncrement = 24; border = null }, BorderLayout.CENTER)
                size = Dimension(1200, 860); setLocationByPlatform(true)
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE; isVisible = true
            }
            // Build cells off the EDT, adding each as it's ready so the window fills progressively.
            thread {
                val ridge = RidgeExtractor()
                var built = 0
                for (wav in shown) {
                    val sq = buildSq(wav, dir, ridge) ?: continue
                    built++
                    SwingUtilities.invokeLater {
                        grid.add(Cell(sq)); grid.revalidate()
                        hdr.text = "  Squiggle atlas — ${dir.name}  ·  $built/${shown.size} loaded" +
                            (if (capped) " (of ${wavs.size})" else "") + "   ·   click a cell to play it"
                    }
                }
            }
        }
    }

    private fun buildSq(wav: File, dir: File, ridge: RidgeExtractor): Sq? {
        val pcm = runCatching { AudioDecoder.decode(wav) }.getOrNull() ?: return null
        if (pcm.isEmpty()) return null
        val fftImg = runCatching {
            val t = File.createTempFile("sqfft", ".png"); SpectrogramRenderer.renderFftPng(pcm, SR, t)
            val im = ImageIO.read(t); t.delete(); im
        }.getOrNull()
        val mfccImg = runCatching { MfccHeatmap.render(pcm, SR, 160, 128) }.getOrNull()
        val cwtImg = File(dir, "${wav.nameWithoutExtension}.jpg").takeIf { it.isFile }
            ?.let { runCatching { ImageIO.read(it) }.getOrNull() }
        val rr = ridge.extract(pcm, 0, pcm.size, SR)
        val rf = rr.features
        val (avg, std) = avgSpectrum(pcm)
        val r2 = rf.rSquared
        val cc = r2Color(if (rf.valid) r2 else 0.0)
        val durMs = (rf.ridgeDurationSec * 1000).toInt()
        val header = "${wav.nameWithoutExtension.take(28)}  ${durMs}ms  r²=${"%.2f".format(if (rf.valid) r2 else 0.0)}"
        return Sq(wav, header, cc, fftImg, mfccImg, cwtImg, avg, std, rr.points,
            durMs, rf.startFreqHz.toInt(), rf.peakFreqHz.toInt(), rf.endFreqHz.toInt(), rf.vertexTimeSec, if (rf.valid) r2 else 0.0)
    }

    /** Mean ± std log-magnitude spectrum over the clip's STFT frames, resampled to 120 log rows 50–8k. */
    private fun avgSpectrum(pcm: FloatArray, rows: Int = 120): Pair<DoubleArray, DoubleArray> {
        val fft = 2048; val step = 1024; val bins = fft / 2; val n = pcm.size
        val frames = if (n < fft) 1 else (n - fft) / step + 1
        val hann = FloatArray(fft) { 0.5f - 0.5f * cos(2f * PI.toFloat() * it / (fft - 1)) }
        val re = FloatArray(fft); val im = FloatArray(fft)
        val sum = DoubleArray(bins); val sumsq = DoubleArray(bins)
        for (f in 0 until frames) {
            val s0 = f * step
            for (i in 0 until fft) { val s = s0 + i; re[i] = if (s < n) pcm[s] * hann[i] else 0f; im[i] = 0f }
            FFTUtils.compute(re, im)
            for (b in 0 until bins) {
                val db = 20.0 * ln(sqrt((re[b] * re[b] + im[b] * im[b]).toDouble()) + 1e-9) / LN10
                sum[b] += db; sumsq[b] += db * db
            }
        }
        val meanB = DoubleArray(bins) { sum[it] / frames }
        val stdB = DoubleArray(bins) { sqrt((sumsq[it] / frames - meanB[it] * meanB[it]).coerceAtLeast(0.0)) }
        val fmin = 50.0; val fmax = 8000.0
        val avg = DoubleArray(rows); val std = DoubleArray(rows)
        for (r in 0 until rows) {
            val fHz = fmin * (fmax / fmin).pow(r.toDouble() / (rows - 1))
            val binF = (fHz * fft / SR).coerceIn(0.0, (bins - 1).toDouble())
            val b0 = binF.toInt(); val b1 = (b0 + 1).coerceAtMost(bins - 1); val fr = binF - b0
            avg[r] = meanB[b0] * (1 - fr) + meanB[b1] * fr
            std[r] = stdB[b0] * (1 - fr) + stdB[b1] * fr
        }
        return avg to std
    }

    /** Fit-quality colour: low R² → amber, high R² → green. */
    private fun r2Color(r2: Double): Color {
        val t = r2.coerceIn(0.0, 1.0)
        val red = (0xE0 + (0x55 - 0xE0) * t).toInt()
        val grn = (0xA0 + (0xDD - 0xA0) * t).toInt()
        return Color(red.coerceIn(0, 255), grn.coerceIn(0, 255), 0x55)
    }

    private class Cell(val sq: Sq) : JPanel() {
        init {
            preferredSize = Dimension(400, 250); background = Color(0x26, 0x26, 0x2c)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "${sq.wav.name} — click to play"
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent?) { PhonemePlayer.show(null, sq.wav) }
            })
        }

        override fun paintComponent(g0: Graphics) {
            super.paintComponent(g0)
            val g = g0 as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width; val h = height; val cc = sq.cc
            g.color = Color(cc.red, cc.green, cc.blue, 210); g.fillRect(0, 0, w, 20)
            g.color = Color.white; g.font = g.font.deriveFont(11f); g.drawString(sq.header, 4, 14)

            val cy = 22; val ch = h - cy - 2; val colW = 74
            drawTile(g, sq.fftImg, 0, cy, colW, ch, "FFT")
            drawRidge(g, colW + 2, cy, colW, ch, cc)

            val rx = 2 * colW + 6; val rw = w - rx - 2; val specH = 78
            drawSpectrum(g, rx, cy, rw, specH, cc)
            val tY = cy + specH + 14; val tH = ch - specH - 14 - 16; val hw = (rw - 2) / 2
            drawTile(g, sq.mfccImg, rx, tY, hw, tH, "MFCC")
            drawTile(g, sq.cwtImg, rx + hw + 2, tY, rw - hw - 2, tH, "CWT")
            g.color = Color(0xcc, 0xcc, 0xcc); g.font = g.font.deriveFont(9.5f)
            val txt = if (sq.pts.size >= 3)
                "ridge ${sq.durMs}ms  ${sq.startHz}→${sq.peakHz}→${sq.endHz}Hz  r²=${"%.2f".format(sq.r2)}"
            else "no ridge"
            g.drawString(txt, rx, h - 6)
        }

        private fun drawSpectrum(g: Graphics2D, x: Int, y: Int, w: Int, h: Int, cc: Color) {
            g.color = Color(0x2c, 0x2c, 0x34); g.fillRect(x, y, w, h)
            val n = sq.avg.size
            var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
            for (v in sq.avg) { if (v < lo) lo = v; if (v > hi) hi = v }
            if (hi <= lo) hi = lo + 1
            fun yOf(v: Double) = y + h - (((v - lo) / (hi - lo)).coerceIn(0.0, 1.0) * h).toInt()
            fun xOf(i: Int) = x + i * (w - 1) / (n - 1)
            g.color = Color(cc.red, cc.green, cc.blue, 60)
            val band = java.awt.Polygon()
            for (i in 0 until n) band.addPoint(xOf(i), yOf(sq.avg[i] + sq.std[i]))
            for (i in n - 1 downTo 0) band.addPoint(xOf(i), yOf(sq.avg[i] - sq.std[i]))
            g.fillPolygon(band)
            g.color = Color(cc.red, cc.green, cc.blue).brighter(); g.stroke = java.awt.BasicStroke(1.3f)
            for (i in 1 until n) g.drawLine(xOf(i - 1), yOf(sq.avg[i - 1]), xOf(i), yOf(sq.avg[i]))
            g.color = Color(0x77, 0x77, 0x77); g.font = g.font.deriveFont(8f); g.drawString("FFT 50–8k log", x + 2, y + 9)
        }

        private fun drawRidge(g: Graphics2D, x: Int, y: Int, w: Int, h: Int, cc: Color) {
            g.color = Color(0x2c, 0x2c, 0x34); g.fillRect(x, y, w, h)
            val pts = sq.pts
            if (pts.size < 3) { g.color = Color(0x66, 0x66, 0x66); g.font = g.font.deriveFont(9f); g.drawString("no ridge", x + w / 2 - 22, y + h / 2); return }
            val loHz = 200.0; val hiHz = 1200.0
            val winS = (pts.last().timeSec - pts.first().timeSec).coerceAtLeast(1e-3)
            val t0 = pts.first().timeSec
            fun px(t: Double) = x + ((t - t0) / winS * (w - 1)).toInt().coerceIn(0, w - 1)
            fun py(hz: Double) = y + h - (((hz - loHz) / (hiHz - loHz)).coerceIn(0.0, 1.0) * h).toInt()
            g.color = Color(cc.red, cc.green, cc.blue).brighter(); g.stroke = java.awt.BasicStroke(1.6f)
            for (i in 1 until pts.size) g.drawLine(px(pts[i - 1].timeSec), py(pts[i - 1].freqHz), px(pts[i].timeSec), py(pts[i].freqHz))
            for (p in pts) { g.color = Color(0xcc, 0xcc, 0xcc, 180); g.fillOval(px(p.timeSec) - 1, py(p.freqHz) - 1, 3, 3) }
            g.color = Color(0x66, 0xdd, 0x66); g.fillOval(px(pts.first().timeSec) - 2, py(sq.startHz.toDouble()) - 2, 5, 5)
            g.color = Color(0xee, 0xdd, 0x44); g.fillOval(px(sq.vertexSec) - 2, py(sq.peakHz.toDouble()) - 2, 5, 5)
            g.color = Color(0xee, 0x66, 0x66); g.fillOval(px(pts.last().timeSec) - 2, py(sq.endHz.toDouble()) - 2, 5, 5)
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
