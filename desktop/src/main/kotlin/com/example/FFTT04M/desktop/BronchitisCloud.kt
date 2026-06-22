package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.fractionation.HubertKMeansUnits
import com.google.gson.Gson
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sqrt

/**
 * One-off experiment: visualise the user's bronchitis progression — "typical/early" (BT, ≤2026-06-12)
 * vs "recovering/dry-hacking" (DH, ≥2026-06-16) clips — as a 2-D PCA cloud over **per-clip HuBERT
 * embeddings** (the gold-standard features). Points are coloured by recording DATE (blue→red), shaped
 * by class (BT square, DH circle, the fuzzy middle a grey ×). If the cloud forms a trajectory the
 * progression is acoustically real; a single blob means it's a true continuum.
 *
 * Run:  ./gradlew :desktop:bronchitisCloud -PuseOnnxGpu   (HuBERT must be available)
 */
object BronchitisCloud {
    private const val SR = 44100

    @JvmStatic
    fun main(args: Array<String>) {
        if (!HubertKMeansUnits.available) { println("HuBERT unavailable: ${HubertKMeansUnits.unavailableReason}"); return }
        println("HuBERT provider=${HubertKMeansUnits.provider}")

        val repo = Workspace.repoRoot ?: File(".")
        val wavById = File(repo, "p3").walkTopDown().filter { it.isFile && it.extension.equals("wav", true) }
            .associateBy { it.nameWithoutExtension }
        val labels = readBronchitis()
        val dateRe = Regex("(20\\d{6})")

        data class Pt(val date: Int, val cls: Int, val vec: DoubleArray)   // cls: 0=BT 1=DH 2=middle
        val pts = ArrayList<Pt>()
        for ((id, _) in labels) {
            val wav = wavById[id] ?: continue
            val date = dateRe.find(id)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val cls = when { date in 20260601..20260612 -> 0; date >= 20260616 -> 1; date in 20260613..20260615 -> 2; else -> continue }
            val pcm = AudioDecoder.decode(wav)?.also { rms(it) } ?: continue
            val emb = HubertKMeansUnits.frameEmbeddings(pcm, SR) ?: continue
            if (emb.isEmpty()) continue
            val h = emb[0].size; val v = DoubleArray(h)
            for (f in emb) for (j in 0 until h) v[j] += f[j]
            for (j in 0 until h) v[j] /= emb.size
            pts.add(Pt(date, cls, v))
        }
        if (pts.size < 3) { println("too few bronchitis clips (${pts.size})"); return }
        println("clips: BT=${pts.count { it.cls == 0 }} DH=${pts.count { it.cls == 1 }} middle=${pts.count { it.cls == 2 }}")

        // z-norm + PCA-2D
        val d = pts[0].vec.size
        val mean = DoubleArray(d); for (p in pts) for (i in 0 until d) mean[i] += p.vec[i]; for (i in 0 until d) mean[i] /= pts.size
        val std = DoubleArray(d); for (p in pts) for (i in 0 until d) { val e = p.vec[i] - mean[i]; std[i] += e * e }
        for (i in 0 until d) std[i] = sqrt(std[i] / pts.size).coerceAtLeast(1e-9)
        val z = pts.map { p -> DoubleArray(d) { (p.vec[it] - mean[it]) / std[it] } }
        val (pc1, pc2) = topTwoPCs(z, d)
        val proj = z.map { v -> var a = 0.0; var b = 0.0; for (i in 0 until d) { a += v[i] * pc1[i]; b += v[i] * pc2[i] }; a to b }

        // render
        val minD = pts.minOf { it.date }; val maxD = pts.maxOf { it.date }
        val xs = proj.map { it.first }; val ys = proj.map { it.second }
        val W = 1100; val H = 820
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB); val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x1e, 0x1e, 0x22); g.fillRect(0, 0, W, H)
        fun sx(x: Double) = (60 + (x - xs.min()) / (xs.max() - xs.min() + 1e-9) * (W - 320)).toInt()
        fun sy(y: Double) = (H - 60 - (y - ys.min()) / (ys.max() - ys.min() + 1e-9) * (H - 120)).toInt()
        for (i in pts.indices) {
            val p = pts[i]; val x = sx(proj[i].first); val y = sy(proj[i].second)
            val t = if (maxD > minD) (p.date - minD).toFloat() / (maxD - minD) else 0.5f   // 0 early → 1 late
            g.color = Color(t, 0.25f, 1 - t)   // blue (early) → red (late)
            when (p.cls) {
                0 -> g.fillRect(x - 5, y - 5, 10, 10)                       // BT square
                1 -> g.fillOval(x - 5, y - 5, 10, 10)                       // DH circle
                else -> { g.stroke = BasicStroke(2f); g.color = Color(0x99, 0x99, 0x99); g.drawLine(x - 4, y - 4, x + 4, y + 4); g.drawLine(x - 4, y + 4, x + 4, y - 4) }
            }
        }
        // legend
        g.font = Font("SansSerif", Font.BOLD, 14); g.color = Color(0xdd, 0xdd, 0xdd)
        g.drawString("Bronchitis progression (HuBERT, PCA-2D)", W - 250, 28)
        g.font = Font("SansSerif", Font.PLAIN, 12)
        g.color = Color(0xcc, 0xcc, 0xcc)
        g.fillRect(W - 250, 44, 10, 10); g.drawString("BT — typical/early (square)", W - 234, 54)
        g.drawOval(W - 250, 60, 10, 10); g.drawString("DH — dry-hacking/late (circle)", W - 234, 70)
        g.color = Color(0x99, 0x99, 0x99); g.drawString("× middle (excluded)", W - 234, 86)
        g.color = Color(0x66, 0x99, 0xff); g.drawString("blue = early date", W - 250, 110)
        g.color = Color(0xff, 0x66, 0x66); g.drawString("red = late date", W - 250, 126)
        g.dispose()

        val out = File(Workspace.dir("codebooks"), "bronchitis_cloud.png")
        ImageIO.write(img, "png", out)
        println("wrote $out  (${pts.size} clips)")
    }

    private fun readBronchitis(): Map<String, String> {
        val f = Workspace.file("manual_comments.json"); if (!f.isFile) return emptyMap()
        @Suppress("UNCHECKED_CAST")
        val raw = Gson().fromJson(f.readText(), Map::class.java) as? Map<String, String> ?: return emptyMap()
        return raw.filterValues { v -> v.lowercase().let { it.contains("bronchitis") || it.contains("brinchitis") || it.contains("evin") || it.contains("quad") } }
    }

    private fun rms(pcm: FloatArray, target: Float = 0.1f) {
        var s = 0.0; for (x in pcm) s += x.toDouble() * x
        val r = sqrt(s / pcm.size.coerceAtLeast(1)); if (r > 1e-5) { val gn = (target / r).toFloat(); for (i in pcm.indices) pcm[i] *= gn }
    }

    private fun topTwoPCs(z: List<DoubleArray>, d: Int): Pair<DoubleArray, DoubleArray> {
        val cov = Array(d) { DoubleArray(d) }
        for (v in z) for (i in 0 until d) { val vi = v[i]; for (j in i until d) cov[i][j] += vi * v[j] }
        for (i in 0 until d) for (j in i until d) { cov[i][j] /= z.size; cov[j][i] = cov[i][j] }
        val pc1 = powerIter(cov, d)
        var lam = 0.0; for (i in 0 until d) { var t = 0.0; for (j in 0 until d) t += cov[i][j] * pc1[j]; lam += pc1[i] * t }
        val cov2 = Array(d) { i -> DoubleArray(d) { j -> cov[i][j] - lam * pc1[i] * pc1[j] } }
        return pc1 to powerIter(cov2, d)
    }
    private fun powerIter(cov: Array<DoubleArray>, d: Int): DoubleArray {
        var v = DoubleArray(d) { if (it == 0) 1.0 else 0.0 }
        repeat(150) {
            val nv = DoubleArray(d); for (i in 0 until d) { var s = 0.0; for (j in 0 until d) s += cov[i][j] * v[j]; nv[i] = s }
            var n = 0.0; for (x in nv) n += x * x; n = sqrt(n).coerceAtLeast(1e-12); for (i in 0 until d) nv[i] /= n; v = nv
        }
        return v
    }
}
