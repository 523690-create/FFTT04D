package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.cough.FFTUtils
import com.example.FFTT04M.desktop.cough.RidgeExtractor
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Squiggle atlas breakout — same cell format as [PhonemeFftAtlas] (FFT spectrogram + ridge f(t) plot +
 * avg spectrum ±1σ + MFCC + CWT tiles + ridge params), but one cell per detected squiggle from a
 * G:\squiggles sweep. Reads `squiggles_manifest.csv` so it can browse the WHOLE library with a toolbar
 * (sort · parent-category filter · min-R² · paging) instead of an arbitrary first-N slice. **Clicking a
 * cell opens the PARENT clip** (resolved from the sweep's recorded path) in [PhonemePlayer], which shows
 * that clip's phoneme decode + decoded class (auto interpretation) + manual comment.
 */
object SquiggleAtlas {

    private const val SR = 44100
    private const val LN10 = 2.302585
    private const val PAGE = 150

    /** One manifest row: the squiggle slice + which parent it came from + sort/filter params. */
    private data class Rec(
        val file: File, val parentId: String, val parentPath: String, val category: String,
        val r2: Double, val vertexHz: Int, val durMs: Int,
    )

    fun show(parent: Component?, dir: File) {
        val recs = loadRecs(dir)
        if (recs.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "No squiggles found in ${dir.absolutePath}\n(run a Squiggle Sweep first)",
                "Squiggle atlas", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        SwingUtilities.invokeLater { AtlasFrame(dir, recs).isVisible = true }
    }

    // ---- manifest / fallback loading -----------------------------------------------------------

    private fun loadRecs(dir: File): List<Rec> {
        val manifest = File(dir, "squiggles_manifest.csv")
        if (manifest.isFile) {
            return manifest.readLines().drop(1).mapNotNull { line ->
                val c = line.split(","); if (c.size < 12) return@mapNotNull null
                val f = File(dir, c[11]); if (!f.isFile) return@mapNotNull null
                Rec(f, c[0], c.getOrNull(12) ?: "", metaLabel(c[0]),
                    c[8].toDoubleOrNull() ?: 0.0, c[6].toIntOrNull() ?: 0, c[4].toIntOrNull() ?: 0)
            }
        }
        // No manifest → parse the params back out of the filenames.
        val rx = Regex("""^(.*)__sq\d+__\d+-\d+ms__v\d+ms_(\d+)Hz__dur(\d+)__r2(\d+)$""")
        return (dir.listFiles { f -> f.isFile && f.extension.equals("wav", true) } ?: emptyArray()).mapNotNull { f ->
            val m = rx.find(f.nameWithoutExtension) ?: return@mapNotNull null
            val (pid, vhz, dur, r2p) = m.destructured
            Rec(f, pid, "", metaLabel(pid), (r2p.toIntOrNull() ?: 0) / 100.0, vhz.toIntOrNull() ?: 0, dur.toIntOrNull() ?: 0)
        }
    }

    /** Coarse parent category from the merged-dataset filename (source__id__RECTYPE__cough|noncough__…). */
    private fun metaLabel(id: String): String {
        val p = id.split("__"); val src = p.getOrNull(0)?.lowercase() ?: ""; val rec = p.getOrNull(2)?.lowercase() ?: ""
        return when {
            "cough" in rec -> "cough"
            "breath" in rec -> "breath"
            "vowel" in rec || "counting" in rec -> "speech"
            src == "urban8k" -> "noise"; src == "train" -> "speech"
            else -> "other"
        }
    }

    /** The parent clip WAV: the recorded path if it still exists, else a few flat-folder candidates, else
     *  the slice itself (so click always plays something). */
    private fun resolveParent(rec: Rec): File {
        if (rec.parentPath.isNotBlank()) File(rec.parentPath).let { if (it.isFile) return it }
        val repo = Workspace.repoRoot
        val cands = buildList {
            add(File("C:\\AndroidStudio\\ALLDATA"))
            if (repo != null) listOf("ALLDATA", "p3", "true_cough", "device_ingest").forEach { add(File(repo, it)) }
        }
        for (d in cands) { val f = File(d, "${rec.parentId}.wav"); if (f.isFile) return f }
        return rec.file
    }

    // ---- window ---------------------------------------------------------------------------------

    private class AtlasFrame(val dir: File, val all: List<Rec>) : JFrame("Squiggle atlas — ${dir.name}") {
        private val cats = listOf("all") + all.map { it.category }.distinct().sorted()
        private val sortBox = JComboBox(arrayOf("R² (best first)", "Vertex Hz", "Duration", "Name"))
        private val catBox = JComboBox(cats.toTypedArray())
        private val minR2 = JSpinner(SpinnerNumberModel(0.0, 0.0, 1.0, 0.05))
        private val pageLbl = JLabel(" ")
        private val prevBtn = JButton("◀ Prev"); private val nextBtn = JButton("Next ▶")
        private val grid = JPanel(GridLayout(0, 3, 8, 8)).apply { background = Color(0x1e, 0x1e, 0x22); border = BorderFactory.createEmptyBorder(8, 8, 8, 8) }
        private var page = 0
        private var buildGen = 0   // cancels a still-running page build when the filter changes

        init {
            defaultCloseOperation = DISPOSE_ON_CLOSE
            size = Dimension(1200, 880); setLocationByPlatform(true)
            contentPane.background = Color(0x1e, 0x1e, 0x22)
            val bar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply { background = Color(0x14, 0x14, 0x18) }
            fun lab(s: String) = JLabel(s).apply { foreground = Color(0xcc, 0xcc, 0xcc) }
            bar.add(lab("Sort")); bar.add(sortBox)
            bar.add(lab("  Category")); bar.add(catBox)
            bar.add(lab("  min R²")); bar.add(minR2)
            bar.add(prevBtn); bar.add(nextBtn); bar.add(pageLbl.apply { foreground = Color(0xdd, 0xdd, 0xdd) })
            listOf(sortBox, catBox).forEach { it.addActionListener { page = 0; rebuild() } }
            minR2.addChangeListener { page = 0; rebuild() }
            prevBtn.addActionListener { if (page > 0) { page--; rebuild() } }
            nextBtn.addActionListener { page++; rebuild() }
            contentPane.add(bar, BorderLayout.NORTH)
            contentPane.add(JScrollPane(grid).apply { verticalScrollBar.unitIncrement = 24; border = null }, BorderLayout.CENTER)
            rebuild()
        }

        private fun filtered(): List<Rec> {
            val cat = catBox.selectedItem as String; val mr = (minR2.value as Number).toDouble()
            var f = all.filter { (cat == "all" || it.category == cat) && it.r2 >= mr }
            f = when (sortBox.selectedIndex) {
                0 -> f.sortedByDescending { it.r2 }
                1 -> f.sortedByDescending { it.vertexHz }
                2 -> f.sortedByDescending { it.durMs }
                else -> f.sortedBy { it.file.name }
            }
            return f
        }

        private fun rebuild() {
            val f = filtered()
            val pages = ceil(f.size / PAGE.toDouble()).toInt().coerceAtLeast(1)
            page = page.coerceIn(0, pages - 1)
            val sub = f.drop(page * PAGE).take(PAGE)
            prevBtn.isEnabled = page > 0; nextBtn.isEnabled = page < pages - 1
            pageLbl.text = "  page ${page + 1}/$pages · ${f.size} squiggles (of ${all.size})"
            val gen = ++buildGen
            grid.removeAll(); grid.revalidate(); grid.repaint()
            thread {
                val ridge = RidgeExtractor()
                for (rec in sub) {
                    if (gen != buildGen) return@thread            // filter changed — abandon this build
                    val view = buildView(rec, dir, ridge) ?: continue
                    SwingUtilities.invokeLater { if (gen == buildGen) { grid.add(Cell(view)); grid.revalidate() } }
                }
            }
        }
    }

    // ---- per-cell render data + builder ---------------------------------------------------------

    private class View(
        val parent: File, val header: String, val cc: Color,
        val fftImg: BufferedImage?, val mfccImg: BufferedImage?, val cwtImg: BufferedImage?,
        val avg: DoubleArray, val std: DoubleArray, val pts: List<RidgeExtractor.RidgePoint>,
        val durMs: Int, val startHz: Int, val peakHz: Int, val endHz: Int, val vertexSec: Double, val r2: Double,
        val category: String,
    )

    private fun buildView(rec: Rec, dir: File, ridge: RidgeExtractor): View? {
        val pcm = runCatching { AudioDecoder.decode(rec.file) }.getOrNull() ?: return null
        if (pcm.isEmpty()) return null
        val fftImg = runCatching {
            val t = File.createTempFile("sqfft", ".png"); SpectrogramRenderer.renderFftPng(pcm, SR, t)
            val im = ImageIO.read(t); t.delete(); im
        }.getOrNull()
        val mfccImg = runCatching { MfccHeatmap.render(pcm, SR, 160, 128) }.getOrNull()
        val cwtImg = File(dir, "${rec.file.nameWithoutExtension}.jpg").takeIf { it.isFile }
            ?.let { runCatching { ImageIO.read(it) }.getOrNull() }
        val rr = ridge.extract(pcm, 0, pcm.size, SR); val rf = rr.features
        val (avg, std) = avgSpectrum(pcm)
        val r2 = if (rf.valid) rf.rSquared else 0.0
        val header = "${rec.parentId.take(24)} · ${rec.category} · r²=${"%.2f".format(r2)}"
        return View(resolveParent(rec), header, r2Color(r2), fftImg, mfccImg, cwtImg, avg, std, rr.points,
            (rf.ridgeDurationSec * 1000).toInt(), rf.startFreqHz.toInt(), rf.peakFreqHz.toInt(), rf.endFreqHz.toInt(),
            rf.vertexTimeSec, r2, rec.category)
    }

    private fun avgSpectrum(pcm: FloatArray, rows: Int = 120): Pair<DoubleArray, DoubleArray> {
        val fft = 2048; val step = 1024; val bins = fft / 2; val n = pcm.size
        val frames = if (n < fft) 1 else (n - fft) / step + 1
        val hann = FloatArray(fft) { 0.5f - 0.5f * cos(2f * PI.toFloat() * it / (fft - 1)) }
        val re = FloatArray(fft); val im = FloatArray(fft)
        val sum = DoubleArray(bins); val sumsq = DoubleArray(bins)
        for (fr in 0 until frames) {
            val s0 = fr * step
            for (i in 0 until fft) { val s = s0 + i; re[i] = if (s < n) pcm[s] * hann[i] else 0f; im[i] = 0f }
            FFTUtils.compute(re, im)
            for (b in 0 until bins) {
                val db = 20.0 * ln(sqrt((re[b] * re[b] + im[b] * im[b]).toDouble()) + 1e-9) / LN10
                sum[b] += db; sumsq[b] += db * db
            }
        }
        val meanB = DoubleArray(bins) { sum[it] / frames }
        val stdB = DoubleArray(bins) { sqrt((sumsq[it] / frames - meanB[it] * meanB[it]).coerceAtLeast(0.0)) }
        val fmin = 50.0; val fmax = 8000.0; val avg = DoubleArray(rows); val std = DoubleArray(rows)
        for (r in 0 until rows) {
            val fHz = fmin * (fmax / fmin).pow(r.toDouble() / (rows - 1))
            val binF = (fHz * fft / SR).coerceIn(0.0, (bins - 1).toDouble())
            val b0 = binF.toInt(); val b1 = (b0 + 1).coerceAtMost(bins - 1); val f = binF - b0
            avg[r] = meanB[b0] * (1 - f) + meanB[b1] * f; std[r] = stdB[b0] * (1 - f) + stdB[b1] * f
        }
        return avg to std
    }

    private fun r2Color(r2: Double): Color {
        val t = r2.coerceIn(0.0, 1.0)
        return Color((0xE0 + (0x55 - 0xE0) * t).toInt().coerceIn(0, 255),
            (0xA0 + (0xDD - 0xA0) * t).toInt().coerceIn(0, 255), 0x55)
    }

    private class Cell(val v: View) : JPanel() {
        init {
            preferredSize = Dimension(400, 250); background = Color(0x26, 0x26, 0x2c)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "${v.parent.name} — click to open the parent clip (decode · interpretation · comments)"
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent?) { PhonemePlayer.show(null, v.parent) }
            })
        }

        override fun paintComponent(g0: Graphics) {
            super.paintComponent(g0)
            val g = g0 as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width; val h = height; val cc = v.cc
            g.color = Color(cc.red, cc.green, cc.blue, 210); g.fillRect(0, 0, w, 20)
            g.color = Color.white; g.font = g.font.deriveFont(11f); g.drawString(v.header, 4, 14)
            val cy = 22; val ch = h - cy - 2; val colW = 74
            drawTile(g, v.fftImg, 0, cy, colW, ch, "FFT")
            drawRidge(g, colW + 2, cy, colW, ch, cc)
            val rx = 2 * colW + 6; val rw = w - rx - 2; val specH = 78
            drawSpectrum(g, rx, cy, rw, specH, cc)
            val tY = cy + specH + 14; val tH = ch - specH - 14 - 16; val hw = (rw - 2) / 2
            drawTile(g, v.mfccImg, rx, tY, hw, tH, "MFCC")
            drawTile(g, v.cwtImg, rx + hw + 2, tY, rw - hw - 2, tH, "CWT")
            g.color = Color(0xcc, 0xcc, 0xcc); g.font = g.font.deriveFont(9.5f)
            val txt = if (v.pts.size >= 3) "ridge ${v.durMs}ms  ${v.startHz}→${v.peakHz}→${v.endHz}Hz  r²=${"%.2f".format(v.r2)}" else "no ridge"
            g.drawString(txt, rx, h - 6)
        }

        private fun drawSpectrum(g: Graphics2D, x: Int, y: Int, w: Int, h: Int, cc: Color) {
            g.color = Color(0x2c, 0x2c, 0x34); g.fillRect(x, y, w, h)
            val n = v.avg.size; var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
            for (a in v.avg) { if (a < lo) lo = a; if (a > hi) hi = a }; if (hi <= lo) hi = lo + 1
            fun yOf(vv: Double) = y + h - (((vv - lo) / (hi - lo)).coerceIn(0.0, 1.0) * h).toInt()
            fun xOf(i: Int) = x + i * (w - 1) / (n - 1)
            g.color = Color(cc.red, cc.green, cc.blue, 60); val band = java.awt.Polygon()
            for (i in 0 until n) band.addPoint(xOf(i), yOf(v.avg[i] + v.std[i]))
            for (i in n - 1 downTo 0) band.addPoint(xOf(i), yOf(v.avg[i] - v.std[i]))
            g.fillPolygon(band)
            g.color = Color(cc.red, cc.green, cc.blue).brighter(); g.stroke = java.awt.BasicStroke(1.3f)
            for (i in 1 until n) g.drawLine(xOf(i - 1), yOf(v.avg[i - 1]), xOf(i), yOf(v.avg[i]))
            g.color = Color(0x77, 0x77, 0x77); g.font = g.font.deriveFont(8f); g.drawString("FFT 50–8k log", x + 2, y + 9)
        }

        private fun drawRidge(g: Graphics2D, x: Int, y: Int, w: Int, h: Int, cc: Color) {
            g.color = Color(0x2c, 0x2c, 0x34); g.fillRect(x, y, w, h)
            val pts = v.pts
            if (pts.size < 3) { g.color = Color(0x66, 0x66, 0x66); g.font = g.font.deriveFont(9f); g.drawString("no ridge", x + w / 2 - 22, y + h / 2); return }
            val loHz = 200.0; val hiHz = 1200.0
            val winS = (pts.last().timeSec - pts.first().timeSec).coerceAtLeast(1e-3); val t0 = pts.first().timeSec
            fun px(t: Double) = x + ((t - t0) / winS * (w - 1)).toInt().coerceIn(0, w - 1)
            fun py(hz: Double) = y + h - (((hz - loHz) / (hiHz - loHz)).coerceIn(0.0, 1.0) * h).toInt()
            g.color = Color(cc.red, cc.green, cc.blue).brighter(); g.stroke = java.awt.BasicStroke(1.6f)
            for (i in 1 until pts.size) g.drawLine(px(pts[i - 1].timeSec), py(pts[i - 1].freqHz), px(pts[i].timeSec), py(pts[i].freqHz))
            for (p in pts) { g.color = Color(0xcc, 0xcc, 0xcc, 180); g.fillOval(px(p.timeSec) - 1, py(p.freqHz) - 1, 3, 3) }
            g.color = Color(0x66, 0xdd, 0x66); g.fillOval(px(pts.first().timeSec) - 2, py(v.startHz.toDouble()) - 2, 5, 5)
            g.color = Color(0xee, 0xdd, 0x44); g.fillOval(px(v.vertexSec) - 2, py(v.peakHz.toDouble()) - 2, 5, 5)
            g.color = Color(0xee, 0x66, 0x66); g.fillOval(px(pts.last().timeSec) - 2, py(v.endHz.toDouble()) - 2, 5, 5)
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
