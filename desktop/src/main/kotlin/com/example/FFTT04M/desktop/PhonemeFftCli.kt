package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.fractionation.HubertKMeansUnits
import com.google.gson.GsonBuilder
import java.io.File
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Precompute an FFT "face" for every phoneme in a HuBERT codebook, for the desktop Phoneme-FFT atlas window.
 * A phoneme is a HuBERT centroid (no direct spectrum), so we assign each 180/90 ms window of a corpus to its
 * nearest centroid and, per phoneme, accumulate BOTH:
 *   - the AVERAGE FFT magnitude spectrum (+ ±1σ) of its member windows, and
 *   - the EXEMPLAR window (member nearest the centroid) → an FFT spectrogram PNG.
 *
 * Writes data/codebooks/<tag>_phoneme_fft.json + <tag>_phoneme_fft/<code>.png.
 * Run: ./gradlew :desktop:phonemeFft -PuseOnnxGpu
 */
object PhonemeFftCli {
    private const val SR = 44100
    private const val SR16 = 16000
    private const val WIN_MS = 180
    private const val HOP_MS = 90
    private const val BINS = 96
    private const val FMAX = 8000
    private const val FFTN = 4096

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val cbFile = File(System.getProperty("fft.codebook")?.takeIf { it.isNotBlank() }
            ?: File(Workspace.dir("codebooks"), "p3_phonemes.json").path)
        val corpus = File(System.getProperty("fft.corpus")?.takeIf { it.isNotBlank() } ?: File(repo, "p3").path)
        if (!cbFile.isFile) { println("no codebook: $cbFile"); return }
        if (!corpus.isDirectory) { println("no corpus dir: $corpus"); return }
        if (!HubertKMeansUnits.available) { println("HuBERT unavailable: ${HubertKMeansUnits.unavailableReason}"); return }

        @Suppress("UNCHECKED_CAST")
        val root = com.google.gson.Gson().fromJson(cbFile.readText(), Map::class.java) as Map<String, Any>
        val tag = (root["tag"] as? String) ?: cbFile.nameWithoutExtension.removeSuffix("_phonemes")
        val norm = root["norm"] as Map<String, Any>
        val mean = (norm["mean"] as List<*>).map { (it as Number).toDouble() }.toDoubleArray()
        val std = (norm["std"] as List<*>).map { (it as Number).toDouble() }.toDoubleArray()
        val phs = (root["phonemes"] as List<*>).map { it as Map<String, Any> }
        val K = phs.size
        val codes = phs.map { it["code"] as String }
        val letters = phs.map { it["letter"] as String }
        val labels = phs.map { it["label"] as String }
        val cent = phs.map { (it["centroid"] as List<*>).map { x -> (x as Number).toDouble() }.toDoubleArray() }
        val d = mean.size
        val outDir = File(Workspace.dir("codebooks"), "${tag}_phoneme_fft").apply { mkdirs() }
        // Class labels: draw each phoneme's exemplar + average ONLY from clips of ITS OWN class, else a
        // p3-trained "voice"/"snoring"/"dry" centroid grabs an unrelated ALLDATA window as its face
        // (D13→silence, V31→bronchitis, S2/S11→voice). Match on canonical label (cleanLabel both sides).
        val phLabelClean = labels.map { cleanLabel(it) ?: it.lowercase() }
        val phLabelSet = phLabelClean.toSet()
        val manual = loadManual()
        fun clipLabel(id: String): String? = manual[id] ?: cleanLabel(AutoLabel.forId(id))
        val roots = (if (corpus.isDirectory) listOf(corpus) else emptyList()) +
            listOf("p3", "ALLDATA", "true_cough", "device_ingest").map { File(repo, it) }.filter { it.isDirectory }
        val rootsD = roots.distinctBy { it.absolutePath }
        println("=== PHONEME-FFT atlas: $K phonemes, ${phLabelSet.size} classes, roots=${rootsD.joinToString { it.name }}, HuBERT ${HubertKMeansUnits.provider} ===")

        val sumMag = Array(K) { DoubleArray(BINS) }; val sumSq = Array(K) { DoubleArray(BINS) }; val count = IntArray(K)
        val exDist = DoubleArray(K) { Double.MAX_VALUE }; val exClip = arrayOfNulls<File>(K)
        val exS = IntArray(K); val exE = IntArray(K)
        val lock = Any()
        val wavs = rootsD.asSequence().flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension.equals("wav", true) && clipLabel(it.nameWithoutExtension) in phLabelSet }.toList()
        println("  ${wavs.size} labelled clips whose class matches a phoneme")
        val done = AtomicInteger()
        val pool = Executors.newFixedThreadPool(4)
        try {
            wavs.map { wav ->
                pool.submit {
                    try {
                        val pcm = AudioDecoder.decode(wav)?.also { rms(it) } ?: return@submit
                        if (pcm.size < SR / 10 || pcm.size > SR * 45) return@submit
                        val clipLbl = clipLabel(wav.nameWithoutExtension) ?: return@submit
                        val fe = HubertKMeansUnits.frameEmbeddings(pcm, SR) ?: return@submit
                        if (fe.isEmpty()) return@submit
                        val pcm16 = resample(pcm, SR, SR16)
                        val durMs = (pcm.size.toLong() * 1000 / SR).toInt().coerceAtLeast(1); val T = fe.size
                        for ((sMs, eMs) in framesFor(durMs)) {
                            // HuBERT feature for this window → nearest centroid
                            val f0 = (sMs.toLong() * T / durMs).toInt().coerceIn(0, T - 1)
                            val f1 = (eMs.toLong() * T / durMs).toInt().coerceIn(f0 + 1, T)
                            val v = DoubleArray(d); for (f in f0 until f1) { val fr = fe[f]; for (j in 0 until d) v[j] += fr[j] }
                            val inv = 1.0 / (f1 - f0); for (j in 0 until d) v[j] = (v[j] * inv - mean[j]) / std[j]
                            var best = 0; var bd = Double.MAX_VALUE
                            for (c in 0 until K) { var s = 0.0; val cc = cent[c]; for (j in 0 until d) { val e = v[j] - cc[j]; s += e * e }; if (s < bd) { bd = s; best = c } }
                            if (clipLbl != phLabelClean[best]) continue   // keep the window only for a SAME-CLASS phoneme
                            // FFT magnitude of the window audio (16 kHz view, matches HuBERT)
                            val a = (sMs * SR16 / 1000).coerceIn(0, pcm16.size - 1)
                            val b = (eMs * SR16 / 1000).coerceIn(a + 1, pcm16.size)
                            val mag = magSpectrum(pcm16, a, b)
                            synchronized(lock) {
                                val sm = sumMag[best]; val sq = sumSq[best]
                                for (j in 0 until BINS) { sm[j] += mag[j]; sq[j] += mag[j] * mag[j] }
                                count[best]++
                                if (bd < exDist[best]) { exDist[best] = bd; exClip[best] = wav; exS[best] = sMs; exE[best] = eMs }
                            }
                        }
                    } catch (_: Exception) {}
                    val n = done.incrementAndGet(); if (n % 1000 == 0 || n == wavs.size) println("  scanned $n/${wavs.size} clips · covered ${count.count { it > 0 }}/$K phonemes")
                }
            }.forEach { it.get() }
        } finally { pool.shutdown() }

        // Per phoneme: from the exemplar window (±60 ms context) render FFT + MFCC-gram + CWT tiles, fit
        // the ridge ("squiggle") → start/peak/end freq + points (for the atlas overlay + click-to-play).
        val mfcc = com.example.FFTT04M.desktop.cough.MfccExtractor()
        val ridgeEx = com.example.FFTT04M.desktop.cough.RidgeExtractor()
        val exJson = HashMap<Int, Map<String, Any?>>()
        var pngs = 0
        for (c in 0 until K) {
            val wav = exClip[c] ?: continue
            val pcm = AudioDecoder.decode(wav)?.also { rms(it) } ?: continue
            val a = ((exS[c] - 60) * SR / 1000).coerceAtLeast(0)
            val b = ((exE[c] + 60) * SR / 1000).coerceAtMost(pcm.size)
            if (b - a < SR / 20) continue
            val win = pcm.copyOfRange(a, b)
            runCatching { SpectrogramRenderer.renderFftPng(win, SR, File(outDir, "${codes[c]}_fft.png")) }
            runCatching { SpectrogramRenderer.renderCwtJpg(win, SR, File(outDir, "${codes[c]}_cwt.jpg"), useGpu = false) }
            runCatching { renderMfccPng(mfcc.frames(win, 0, win.size, SR), File(outDir, "${codes[c]}_mfcc.png")) }
            pngs++
            val rr = runCatching { ridgeEx.extract(win, 0, win.size, SR) }.getOrNull()
            val rf = rr?.features
            val ridgeMap: Map<String, Any?>? = if (rf != null && rf.valid) mapOf(
                "durSec" to round3(rf.ridgeDurationSec), "startHz" to round1(rf.startFreqHz),
                "peakHz" to round1(rf.peakFreqHz), "endHz" to round1(rf.endFreqHz),
                "vertexSec" to round3(rf.vertexTimeSec), "r2" to round3(rf.rSquared),
                "curv" to Math.round(rf.curvature), "slope" to Math.round(rf.slope),
                "winSec" to round3(win.size.toDouble() / SR),
                "points" to rr!!.points.map { listOf(round3(it.timeSec), round1(it.freqHz)) }) else null
            exJson[c] = mapOf("fftPng" to "${codes[c]}_fft.png", "cwtPng" to "${codes[c]}_cwt.jpg",
                "mfccPng" to "${codes[c]}_mfcc.png", "wav" to wav.absolutePath,
                "exStartMs" to exS[c], "exEndMs" to exE[c], "ridge" to ridgeMap)
        }

        val gson = GsonBuilder().create()
        val phonemes: List<Map<String, Any?>> = (0 until K).map { c ->
            val n = count[c].coerceAtLeast(1)
            val avg = DoubleArray(BINS) { sumMag[c][it] / n }
            val sd = DoubleArray(BINS) { sqrt((sumSq[c][it] / n - avg[it] * avg[it]).coerceAtLeast(0.0)) }
            val base: Map<String, Any?> = mapOf("code" to codes[c], "letter" to letters[c], "label" to labels[c], "n" to count[c],
                "avgMag" to avg.map { round1(it) }, "stdMag" to sd.map { round1(it) })
            base + (exJson[c] ?: emptyMap<String, Any?>())
        }
        File(Workspace.dir("codebooks"), "${tag}_phoneme_fft.json").writeText(gson.toJson(
            mapOf("tag" to tag, "bins" to BINS, "fmaxHz" to FMAX, "winMs" to WIN_MS,
                "corpus" to corpus.name, "phonemes" to phonemes)))
        println("=== wrote ${tag}_phoneme_fft.json (${count.count { it > 0 }}/$K phonemes, $pngs exemplars ×3 tiles) ===")
    }

    private fun framesFor(durMs: Int): List<Pair<Int, Int>> {
        if (durMs <= WIN_MS) return if (durMs > 0) listOf(0 to durMs) else emptyList()
        val out = ArrayList<Pair<Int, Int>>(); var s = 0
        while (s < durMs) { val e = (s + WIN_MS).coerceAtMost(durMs); if (e - s >= WIN_MS / 2) out.add(s to e); if (e >= durMs) break; s += HOP_MS }
        return out
    }

    /** Hann-windowed FFT magnitude of pcm16[a,b), binned into [BINS] log-magnitude bins over [0,FMAX]. */
    private fun magSpectrum(x: FloatArray, a: Int, b: Int): DoubleArray {
        val re = DoubleArray(FFTN); val im = DoubleArray(FFTN)
        val len = (b - a).coerceAtMost(FFTN)
        for (i in 0 until len) { val w = 0.5 - 0.5 * cos(2 * PI * i / (len - 1).coerceAtLeast(1)); re[i] = x[a + i] * w }
        fft(re, im)
        val maxK = (FMAX.toDouble() / SR16 * FFTN).toInt().coerceIn(1, FFTN / 2)
        val out = DoubleArray(BINS); val cnt = IntArray(BINS)
        for (k in 1..maxK) { val mag = sqrt(re[k] * re[k] + im[k] * im[k]); val bi = ((k - 1) * BINS / maxK).coerceIn(0, BINS - 1); out[bi] += mag; cnt[bi]++ }
        for (j in 0 until BINS) out[j] = 20.0 * ln((if (cnt[j] > 0) out[j] / cnt[j] else 0.0) + 1e-9) / ln(10.0)
        return out
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size; var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len; val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return x
        val n = (x.size.toLong() * to / from).toInt(); val out = FloatArray(n); val r = from.toDouble() / to
        for (i in 0 until n) { val s = i * r; val i0 = s.toInt(); val f = s - i0; out[i] = if (i0 + 1 < x.size) (x[i0] * (1 - f) + x[i0 + 1] * f).toFloat() else x[i0.coerceAtMost(x.size - 1)] }
        return out
    }

    private fun rms(pcm: FloatArray, target: Float = 0.1f) { var s = 0.0; for (v in pcm) s += v.toDouble() * v; val r = sqrt(s / pcm.size.coerceAtLeast(1)); if (r > 1e-5) { val g = (target / r).toFloat(); for (i in pcm.indices) pcm[i] *= g } }
    private fun round1(x: Double) = Math.round(x * 10.0) / 10.0
    private fun round3(x: Double) = Math.round(x * 1000.0) / 1000.0

    /** Canonicalize a raw label to the codebook's class string (mirrors PhonemeCodebookCli.cleanLabel). */
    private fun cleanLabel(raw: String?): String? {
        var s = raw?.trim() ?: return null
        val am = s.indexOf("auto-match", ignoreCase = true); if (am >= 0) s = s.substring(0, am)
        s = s.trim().removePrefix("manual:").trim().lowercase(); if (s.isBlank()) return null
        return when {
            s == "snore" || s.contains("snor") -> "snoring"
            s.contains("bronchitis") || s.contains("brinchitis") || s.contains("evin") || s.contains("quad") -> "bronchitis"
            s.startsWith("dry hack") -> "dry hacking"
            s.startsWith("dry") -> "dry"
            s == "noise" -> "noise"
            s == "croup" -> "croup"
            s == "speech" || s.contains("music") || s.contains("singing") || s == "crying" || s == "cry" -> "voice"
            s == "sneeze" || s == "sneezing" -> "sneeze"
            else -> s
        }
    }
    private fun loadManual(): Map<String, String> {
        val f = Workspace.file("manual_comments.json"); if (!f.isFile) return emptyMap()
        @Suppress("UNCHECKED_CAST")
        val raw = runCatching { com.google.gson.Gson().fromJson(f.readText(), Map::class.java) as Map<String, String> }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, String>(); for ((id, v) in raw) cleanLabel(v)?.let { out[id] = it }; return out
    }

    /** MFCC-gram heatmap: coeffs c1.. as rows (low→high, bottom→top), frames as cols. c0 (energy) skipped. */
    private fun renderMfccPng(frames: List<DoubleArray>, out: File) {
        if (frames.isEmpty()) return
        val rows = (frames[0].size - 1).coerceAtLeast(1); val cols = frames.size.coerceAtLeast(1)
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (fr in frames) for (k in 1 until fr.size) { if (fr[k] < lo) lo = fr[k]; if (fr[k] > hi) hi = fr[k] }
        val rng = (hi - lo).coerceAtLeast(1e-9)
        val img = java.awt.image.BufferedImage(cols, rows, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (x in 0 until cols) for (r in 0 until rows) img.setRGB(x, rows - 1 - r, viridis(((frames[x][r + 1] - lo) / rng).coerceIn(0.0, 1.0)))
        ImageIO.write(img, "png", out)
    }
    private fun viridis(t: Double): Int {
        val r = (255 * t * t).toInt().coerceIn(0, 255); val g = (255 * kotlin.math.sqrt(t)).toInt().coerceIn(0, 255)
        val b = (255 * ((1 - t) * 0.7) + 40).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }
}
