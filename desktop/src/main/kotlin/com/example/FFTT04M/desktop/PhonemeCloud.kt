package com.example.FFTT04M.desktop

import com.google.gson.JsonParser
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JPanel
import kotlin.math.sqrt

/**
 * Interactive 2-D PCA scatter of the codebook's phonemes — a "phoneme cloud". Each dot is one phoneme
 * (cluster) from `<tag>_phonemes.json`, positioned by PCA of its 13-dim centroid, coloured by class
 * (same palette as the grid's decode line / Legend), sized by its training-fragment count. Hover for
 * the code + class + count. Reuses the power-iteration PCA approach from [CloudAnalysis].
 */
object PhonemeCloud {

    data class P(val code: String, val letter: String, val label: String, val centroid: DoubleArray, val n: Int)

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
        val proj = pca2d(ps.map { it.centroid })
        JFrame("Phoneme cloud — ${ps.size} phonemes (PCA-2D)").apply {
            contentPane.add(CloudPanel(ps, proj))
            size = Dimension(940, 720)
            setLocationByPlatform(true)
            defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            isVisible = true
        }
    }

    // ---- PCA: top-2 principal components of the (centred) centroids via power iteration ----
    private fun pca2d(vecs: List<DoubleArray>): List<Pair<Double, Double>> {
        val d = vecs[0].size
        val mean = DoubleArray(d)
        for (v in vecs) for (i in 0 until d) mean[i] += v[i]
        for (i in 0 until d) mean[i] /= vecs.size
        val cov = Array(d) { DoubleArray(d) }
        for (v in vecs) for (i in 0 until d) { val vi = v[i] - mean[i]; for (j in i until d) cov[i][j] += vi * (v[j] - mean[j]) }
        for (i in 0 until d) for (j in i until d) { cov[i][j] /= vecs.size; cov[j][i] = cov[i][j] }
        val pc1 = powerIter(cov); val pc2 = powerIter(deflate(cov, pc1))
        return vecs.map { v ->
            var a = 0.0; var b = 0.0
            for (i in 0 until d) { val c = v[i] - mean[i]; a += c * pc1[i]; b += c * pc2[i] }
            a to b
        }
    }

    private fun deflate(cov: Array<DoubleArray>, v: DoubleArray): Array<DoubleArray> {
        val d = cov.size
        var lam = 0.0; for (i in 0 until d) { var t = 0.0; for (j in 0 until d) t += cov[i][j] * v[j]; lam += v[i] * t }
        return Array(d) { i -> DoubleArray(d) { j -> cov[i][j] - lam * v[i] * v[j] } }
    }

    private fun powerIter(cov: Array<DoubleArray>): DoubleArray {
        val d = cov.size; var v = DoubleArray(d) { if (it == 0) 1.0 else 0.0 }
        repeat(150) {
            val nv = DoubleArray(d)
            for (i in 0 until d) { var s = 0.0; for (j in 0 until d) s += cov[i][j] * v[j]; nv[i] = s }
            var n = 0.0; for (x in nv) n += x * x; n = sqrt(n).coerceAtLeast(1e-12)
            for (i in 0 until d) nv[i] /= n
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

    private class CloudPanel(val ps: List<P>, val proj: List<Pair<Double, Double>>) : JPanel() {
        private val minX = proj.minOf { it.first }; private val maxX = proj.maxOf { it.first }
        private val minY = proj.minOf { it.second }; private val maxY = proj.maxOf { it.second }
        private val maxN = (ps.maxOfOrNull { it.n } ?: 1).coerceAtLeast(1)

        init { background = Color(0x1e, 0x1e, 0x22); toolTipText = ""; preferredSize = Dimension(940, 720) }

        private fun sx(x: Double) = (50 + (x - minX) / (maxX - minX + 1e-9) * (width - 250)).toInt()
        private fun sy(y: Double) = (height - 50 - (y - minY) / (maxY - minY + 1e-9) * (height - 100)).toInt()

        override fun paintComponent(g0: Graphics) {
            super.paintComponent(g0)
            val g = g0 as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            for (i in ps.indices) {
                val p = ps[i]; val x = sx(proj[i].first); val y = sy(proj[i].second)
                val rad = (3 + 10 * sqrt(p.n.toDouble()) / sqrt(maxN.toDouble())).toInt().coerceAtLeast(2)
                val c = classColor(p.letter)
                g.color = Color(c.red, c.green, c.blue, 160); g.fillOval(x - rad, y - rad, rad * 2, rad * 2)
                g.color = Color(c.red, c.green, c.blue, 220); g.drawOval(x - rad, y - rad, rad * 2, rad * 2)
            }
            g.font = Font("SansSerif", Font.PLAIN, 9); g.color = Color(0xdd, 0xdd, 0xdd, 150)
            for (i in ps.indices) g.drawString(ps[i].code, sx(proj[i].first) + 4, sy(proj[i].second) - 3)
            // legend (class → colour → #phonemes), most fragments first
            var ly = 22
            g.font = Font("SansSerif", Font.BOLD, 12); g.color = Color(0xcc, 0xcc, 0xcc)
            g.drawString("Phoneme cloud (PCA-2D)", width - 195, ly); ly += 20
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
                val dx = (sx(proj[i].first) - e.x).toDouble(); val dy = (sy(proj[i].second) - e.y).toDouble()
                val d = dx * dx + dy * dy; if (d < bd) { bd = d; best = i }
            }
            if (best < 0 || bd > 225) return null
            val p = ps[best]; return "${p.code} — ${p.label} (${p.n} frags)"
        }
    }
}
