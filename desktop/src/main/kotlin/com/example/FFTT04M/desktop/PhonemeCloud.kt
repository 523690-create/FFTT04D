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
import javax.swing.SwingUtilities
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Interactive 3-D scatter of the codebook's phonemes — a "phoneme cloud". Each dot is one phoneme
 * (cluster) from `<tag>_phonemes.json`, coloured by class (grid palette), sized by training-fragment
 * count. Pick which three dimensions are on the X/Y/Z axes (top-5 PCA components or any of the 13 raw
 * fragment features). **Left-drag rotates** the cloud about the X/Y axes (cylinder-style), **right-drag
 * pans**, **wheel zooms**; nearer points are drawn larger/brighter for depth. Hover for code/class/count.
 */
object PhonemeCloud {

    data class P(val code: String, val letter: String, val label: String, val centroid: DoubleArray, val n: Int)

    private const val D = 13
    private const val K = 5   // principal components offered as axes
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
        val zc = JComboBox(axisLabels.toTypedArray()).apply { selectedIndex = minOf(2, axisLabels.size - 1) }
        val onAxis = { panel.setAxes(xc.selectedIndex, yc.selectedIndex, zc.selectedIndex) }
        xc.addActionListener { onAxis() }; yc.addActionListener { onAxis() }; zc.addActionListener { onAxis() }
        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(JLabel("X:")); add(xc); add(JLabel("Y:")); add(yc); add(JLabel("Z:")); add(zc)
            add(JButton("Reset view").apply { addActionListener { panel.resetView() } })
            add(JLabel("   left-drag = rotate · right-drag = pan · wheel = zoom"))
        }
        JFrame("Phoneme cloud (3-D) — ${ps.size} phonemes").apply {
            contentPane.add(controls, BorderLayout.NORTH)
            contentPane.add(panel, BorderLayout.CENTER)
            size = Dimension(1040, 820)
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
        private var xAxis = 0; private var yAxis = 1; private var zAxis = 2
        private var angX = 0.4; private var angY = 0.6        // rotation about X / Y (radians)
        private var scale = 1.0; private var offX = 0.0; private var offY = 0.0
        private var fitted = false
        private val mn = DoubleArray(3); private val rng = DoubleArray(3)   // per-axis min + range
        private val maxN = (ps.maxOfOrNull { it.n } ?: 1).coerceAtLeast(1)
        private var dragX = 0; private var dragY = 0

        init {
            background = Color(0x1e, 0x1e, 0x22); toolTipText = ""; preferredSize = Dimension(1040, 740)
            val ma = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) { dragX = e.x; dragY = e.y }
                override fun mouseDragged(e: MouseEvent) {
                    val dx = e.x - dragX; val dy = e.y - dragY; dragX = e.x; dragY = e.y
                    if (SwingUtilities.isRightMouseButton(e)) { offX += dx; offY += dy }
                    else { angY += dx * 0.01; angX += dy * 0.01 }   // left-drag rotates about Y (horiz) / X (vert)
                    repaint()
                }
                override fun mouseWheelMoved(e: MouseWheelEvent) {
                    scale *= if (e.wheelRotation < 0) 1.12 else 1 / 1.12; repaint()
                }
            }
            addMouseListener(ma); addMouseMotionListener(ma); addMouseWheelListener(ma)
        }

        fun setAxes(x: Int, y: Int, z: Int) { xAxis = x; yAxis = y; zAxis = z; fitted = false; repaint() }
        fun resetView() { angX = 0.4; angY = 0.6; scale = 1.0; offX = 0.0; offY = 0.0; fitted = false; repaint() }

        private fun ensureFit() {
            if (fitted) return
            val axes = intArrayOf(xAxis, yAxis, zAxis)
            for (a in 0 until 3) {
                var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
                for (c in coords) { val v = c[axes[a]]; if (v < lo) lo = v; if (v > hi) hi = v }
                mn[a] = lo; rng[a] = (hi - lo).coerceAtLeast(1e-9)
            }
            fitted = true
        }
        private fun nrm(i: Int, a: Int, axis: Int) = (coords[i][axis] - mn[a]) / rng[a] - 0.5  // → [-0.5,0.5]

        /** screenX, screenY, depth (larger = nearer the viewer). */
        private fun project(i: Int): DoubleArray {
            val nx = nrm(i, 0, xAxis); val ny = nrm(i, 1, yAxis); val nz = nrm(i, 2, zAxis)
            val cY = cos(angY); val sY = sin(angY)
            val x1 = nx * cY - nz * sY; val z1 = nx * sY + nz * cY
            val cX = cos(angX); val sX = sin(angX)
            val y2 = ny * cX - z1 * sX; val z2 = ny * sX + z1 * cX
            val plotW = (width - 190).coerceAtLeast(120); val size = minOf(plotW, height - 80).coerceAtLeast(120) * 0.42
            val cx = (width - 190) / 2.0; val cy = height / 2.0
            return doubleArrayOf(cx + x1 * size * scale + offX, cy + y2 * size * scale + offY, z2)
        }

        override fun paintComponent(g0: Graphics) {
            super.paintComponent(g0)
            ensureFit()
            val g = g0 as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val proj = Array(ps.size) { project(it) }
            val order = ps.indices.sortedBy { proj[it][2] }   // far first → near drawn on top
            for (i in order) {
                val x = proj[i][0].toInt(); val y = proj[i][1].toInt()
                val depth = ((proj[i][2] + 0.9) / 1.8).coerceIn(0.0, 1.0)   // 0 far … 1 near
                val baseR = 3 + 10 * sqrt(ps[i].n.toDouble()) / sqrt(maxN.toDouble())
                val rad = (baseR * (0.55 + 0.45 * depth)).toInt().coerceAtLeast(2)
                val c = classColor(ps[i].letter)
                val a = (70 + 170 * depth).toInt().coerceIn(40, 255)
                g.color = Color(c.red, c.green, c.blue, a); g.fillOval(x - rad, y - rad, rad * 2, rad * 2)
                g.color = Color(c.red, c.green, c.blue, (a + 40).coerceAtMost(255)); g.drawOval(x - rad, y - rad, rad * 2, rad * 2)
            }
            g.font = Font("SansSerif", Font.PLAIN, 9)
            for (i in order) {   // label only nearer half to reduce clutter
                if ((proj[i][2] + 0.9) / 1.8 < 0.45) continue
                g.color = Color(0xdd, 0xdd, 0xdd, 170); g.drawString(ps[i].code, proj[i][0].toInt() + 4, proj[i][1].toInt() - 3)
            }
            g.font = Font("SansSerif", Font.PLAIN, 11); g.color = Color(0x99, 0x99, 0xa5)
            g.drawString("X: ${axisLabels[xAxis]}   Y: ${axisLabels[yAxis]}   Z: ${axisLabels[zAxis]}", 14, height - 14)
            var ly = 22
            g.font = Font("SansSerif", Font.BOLD, 12); g.color = Color(0xcc, 0xcc, 0xcc)
            g.drawString("Phoneme cloud (3-D)", width - 185, ly); ly += 20
            g.font = Font("SansSerif", Font.PLAIN, 11)
            for ((letter, group) in ps.groupBy { it.letter }.entries.sortedByDescending { e -> e.value.sumOf { it.n } }) {
                g.color = classColor(letter); g.fillRect(width - 185, ly - 9, 11, 11)
                g.color = Color(0xcc, 0xcc, 0xcc)
                g.drawString("$letter  ${group.first().label} (${group.size}ph)", width - 168, ly); ly += 16
            }
        }

        override fun getToolTipText(e: MouseEvent): String? {
            var best = -1; var bd = Double.MAX_VALUE
            for (i in ps.indices) {
                val p = project(i); val dx = p[0] - e.x; val dy = p[1] - e.y
                val d = dx * dx + dy * dy; if (d < bd) { bd = d; best = i }
            }
            if (best < 0 || bd > 256) return null
            val p = ps[best]; return "${p.code} — ${p.label} (${p.n} frags)"
        }
    }
}
