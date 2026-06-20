package com.example.FFTT04M.desktop

import com.google.gson.JsonParser
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import kotlin.math.sqrt

/**
 * Interactive 2-D scatter of the codebook's phonemes — a "phoneme cloud". Each dot is one phoneme
 * (cluster) from `<tag>_phonemes.json`, coloured by class (grid palette), sized by training-fragment
 * count. Interactive: **drag to pan, wheel to zoom**, and pick which two dimensions are on the axes
 * (the top-5 PCA components or any of the 13 raw fragment features) via the X/Y selectors — so you can
 * "rotate" through projections. Hover any dot for its code / class / count.
 */
object PhonemeCloud {

    data class P(val code: String, val letter: String, val label: String, val centroid: DoubleArray, val n: Int)

    private const val D = 13
    private const val K = 5   // number of principal components offered as axes
    // The 13 fragment feature names = WholeClipFeatures' 14 minus syllabic (index 12).
    private val featNames = WholeClipFeatures.names.filterIndexed { i, _ -> i != 12 }

    fun load(): List<P> {
        val f = Workspace.dir("codebooks").listFiles { x -> x.name.endsWith("_phonemes.json") }
            ?.maxByOrNull { it.length() } ?: return emptyList()
        return try {
            val arr = JsonParser.parseString(f.readText()).asJsonObject.getAsJsonArray("phonemes")
            arr.map { el ->
                val o = el.asJsonObject
                P(o.get("code").asString, o.get("letter").asString, o.get("label").asString,
                    o.getAsJsonArray("centroid").map { it.asDouble }.toDoubleArray(),
                    o.get("n")?.asInt ?: 1)
            }
        } catch (e: Exception) { System.err.println("phoneme cloud load: ${e.message}"); emptyList() }
    }

    fun show(parent: Component?) {
        val ps = load()
        if (ps.size < 2) {
            JOptionPane.showMessageDialog(parent, "No codebook found — run the codebook build first.")
            return
        }
        // Axis coordinates per phoneme = [K PCA projections] ++ [13 raw centroid dims].
        val cents = ps.map { it.centroid }
        val mean = DoubleArray(D)
        for (c in cents) for (i in 0 until D) mean[i] += c[i]
        for (i in 0 until D) mean[i] /= cents.size
        val pcs = topPCs(cents, mean)
        val coords = cents.map { c ->
            DoubleArray(K + D) { j ->
                if (j < K) { var s = 0.0; for (d in 0 until D) s += (c[d] - mean[d]) * pcs[j][d]; s }
                else c[j - K]
            }
        }
        val axisLabels = (1..K).map { "PC$it" } + featNames

        val panel = CloudPanel(ps, coords, axisLabels)
        val xc = JComboBox(axisLabels.toTypedArray()).apply { selectedIndex = 0 }
        val yc = JComboBox(axisLabels.toTypedArray()).apply { selectedIndex = 1 }
        val onAxis = { panel.setAxes(xc.selectedIndex, yc.selectedIndex) }
        xc.addActionListener { onAxis() }
        yc.addActionListener { onAxis() }
        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(JLabel("X:")); add(xc); add(JLabel("Y:")); add(yc)
            add(JButton("Reset view").apply { addActionListener { panel.resetView() } })
            add(JLabel("   drag = pan · wheel = zoom"))
        }
        JFrame("Phoneme cloud — ${ps.size} phonemes").apply {
            contentPane.add(controls, BorderLayout.NORTH)
            contentPane.add(panel, BorderLayout.CENTER)
            size = Dimension(1000, 780)
            setLocationByPlatform(true)
            defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            isVisible = true
        }
    }

    // ---- top-K principal components of the centred centroids (power iteration + deflation) ----
    private fun topPCs(cents: List<DoubleArray>, mean: DoubleArray): List<DoubleArray> {
        var cov = Array(D) { DoubleArray(D) }
        for (c in cents) for (i in 0 until D) { val vi = c[i] - mean[i]; for (j in i until D) cov[i][j] += vi * (c[j] - mean[j]) }
        for (i in 0 until D) for (j in i until D) { cov[i][j] /= cents.size; cov[j][i] = cov[i][j] }
        val out = ArrayList<DoubleArray>(K)
        repeat(K) { val pc = powerIter(cov); out.add(pc); cov = deflate(cov, pc) }
        return out
    }

    private fun deflate(cov: Array<DoubleArray>, v: DoubleArray): Array<DoubleArray> {
        var lam = 0.0; for (i in 0 until D) { var t = 0.0; for (j in 0 until D) t += cov[i][j] * v[j]; lam += v[i] * t }
        return Array(D) { i -> DoubleArray(D) { j -> cov[i][j] - lam * v[i] * v[j] } }
    }

    private fun powerIter(cov: Array<DoubleArray>): DoubleArray {
        var v = DoubleArray(D) { if (it == 0) 1.0 else 0.0 }
        repeat(150) {
            val nv = DoubleArray(D)
            for (i in 0 until D) { var s = 0.0; for (j in 0 until D) s += cov[i][j] * v[j]; nv[i] = s }
            var n = 0.0; for (x in nv) n += x * x; n = sqrt(n).coerceAtLeast(1e-12)
            for (i in 0 until D) nv[i] /= n
            v = nv
        }
        return v
    }

    // Same class palette as RecordingsGrid.letterColor (kept in sync by hand).
    private fun hex3(s: String): Color {
        fun d(c: Char) = Integer.parseInt("$c$c", 16)
        return Color(d(s[1]), d(s[2]), d(s[3]))
    }
    fun classColor(letter: String): Color = hex3(when (letter) {
        "S" -> "#5cf"; "B" -> "#f77"; "N" -> "#999"; "D" -> "#fb5"; "DH" -> "#f95"; "SP" -> "#9d9"
        "C" -> "#c9f"; "CR" -> "#b8e"; "CX" -> "#a7d"; "SN" -> "#fc9"; "E", "EP" -> "#dd9"
        "M" -> "#6cc"; "Q" -> "#cc8"; else -> "#bbb"
    })

    private class CloudPanel(
        val ps: List<P>, val coords: List<DoubleArray>, val axisLabels: List<String>,
    ) : JPanel() {
        private var xAxis = 0; private var yAxis = 1
        private var scale = 1.0; private var offX = 50.0; private var offY = 50.0
        private var fitted = false
        private var minX = 0.0; private var maxX = 1.0; private var minY = 0.0; private var maxY = 1.0
        private val maxN = (ps.maxOfOrNull { it.n } ?: 1).coerceAtLeast(1)
        private var dragX = 0; private var dragY = 0

        init {
            background = Color(0x1e, 0x1e, 0x22); toolTipText = ""; preferredSize = Dimension(1000, 720)
            val ma = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) { dragX = e.x; dragY = e.y }
                override fun mouseDragged(e: MouseEvent) {
                    offX += e.x - dragX; offY += e.y - dragY; dragX = e.x; dragY = e.y; repaint()
                }
                override fun mouseWheelMoved(e: MouseWheelEvent) {
                    val f = if (e.wheelRotation < 0) 1.12 else 1 / 1.12
                    offX = e.x - (e.x - offX) * f; offY = e.y - (e.y - offY) * f; scale *= f; repaint()
                }
            }
            addMouseListener(ma); addMouseMotionListener(ma); addMouseWheelListener(ma)
        }

        fun setAxes(x: Int, y: Int) { xAxis = x; yAxis = y; fitted = false; repaint() }
        fun resetView() { fitted = false; repaint() }

        private fun ensureFit() {
            if (fitted) return
            minX = Double.MAX_VALUE; maxX = -Double.MAX_VALUE; minY = Double.MAX_VALUE; maxY = -Double.MAX_VALUE
            for (c in coords) {
                val x = c[xAxis]; val y = c[yAxis]
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
            }
            scale = 1.0; offX = 50.0; offY = 50.0; fitted = true
        }
        private fun baseW() = (width - 250.0).coerceAtLeast(100.0)
        private fun baseH() = (height - 100.0).coerceAtLeast(100.0)
        private fun lx(c: DoubleArray) = (c[xAxis] - minX) / (maxX - minX + 1e-9) * baseW()
        private fun ly(c: DoubleArray) = (1 - (c[yAxis] - minY) / (maxY - minY + 1e-9)) * baseH()
        private fun sx(c: DoubleArray) = offX + lx(c) * scale
        private fun sy(c: DoubleArray) = offY + ly(c) * scale

        override fun paintComponent(g0: Graphics) {
            super.paintComponent(g0)
            ensureFit()
            val g = g0 as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            for (i in ps.indices) {
                val x = sx(coords[i]).toInt(); val y = sy(coords[i]).toInt()
                val rad = (3 + 10 * sqrt(ps[i].n.toDouble()) / sqrt(maxN.toDouble())).toInt().coerceAtLeast(2)
                val c = classColor(ps[i].letter)
                g.color = Color(c.red, c.green, c.blue, 160); g.fillOval(x - rad, y - rad, rad * 2, rad * 2)
                g.color = Color(c.red, c.green, c.blue, 220); g.drawOval(x - rad, y - rad, rad * 2, rad * 2)
            }
            g.font = Font("SansSerif", Font.PLAIN, 9); g.color = Color(0xdd, 0xdd, 0xdd, 150)
            for (i in ps.indices) g.drawString(ps[i].code, sx(coords[i]).toInt() + 4, sy(coords[i]).toInt() - 3)
            // axes caption
            g.font = Font("SansSerif", Font.PLAIN, 11); g.color = Color(0x99, 0x99, 0xa5)
            g.drawString("X: ${axisLabels[xAxis]}    Y: ${axisLabels[yAxis]}", 54, height - 16)
            // legend (class → colour → #phonemes), most fragments first
            var ly = 22
            g.font = Font("SansSerif", Font.BOLD, 12); g.color = Color(0xcc, 0xcc, 0xcc)
            g.drawString("Phoneme cloud", width - 195, ly); ly += 20
            g.font = Font("SansSerif", Font.PLAIN, 11)
            for ((letter, group) in ps.groupBy { it.letter }.entries.sortedByDescending { e -> e.value.sumOf { it.n } }) {
                g.color = classColor(letter); g.fillRect(width - 195, ly - 9, 11, 11)
                g.color = Color(0xcc, 0xcc, 0xcc)
                g.drawString("$letter  ${group.first().label} (${group.size}ph)", width - 178, ly); ly += 16
            }
        }

        override fun getToolTipText(e: MouseEvent): String? {
            var best = -1; var bd = Double.MAX_VALUE
            for (i in ps.indices) {
                val dx = sx(coords[i]) - e.x; val dy = sy(coords[i]) - e.y
                val d = dx * dx + dy * dy; if (d < bd) { bd = d; best = i }
            }
            if (best < 0 || bd > 256) return null
            val p = ps[best]; return "${p.code} — ${p.label} (${p.n} frags)"
        }
    }
}
