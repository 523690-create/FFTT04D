package com.example.FFTT04M.desktop

import com.google.gson.GsonBuilder
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Supervised fragment-level phoneme codebook (see PHONEME_CODEBOOK.md).
 *
 * Build: from the manually-labelled clips of a dataset — Spectral-Flux fragments → 13-dim feature
 * (WholeClipFeatures minus `syllabic`) → per-label k-means → phonemes coded `[letter][number]`.
 * Decode: every clip → nearest-phoneme word (`?` when beyond a phoneme's radius) + histogram +
 * inferred letter. Writes data/codebooks/<tag>_phonemes.json and <tag>_decoded.json.
 *
 * Run: ./gradlew :desktop:phonemeCodebookCli --args="D:\AndroidProjects\p3 D:\AndroidProjects\data\fractionation\FFTT04M\Spectral_Flux_Onset_segments.jsonl p3"
 *      args: [wavDir] [spectralFluxJsonl] [outTag]
 */
object PhonemeCodebookCli {
    private const val SR = 44100
    private const val K = 128
    private const val SHORT_MIN = 256          // skip fragments shorter than this many samples

    private val letterMap = linkedMapOf(
        "snoring" to "S", "bronchitis" to "B", "noise" to "N", "dry" to "D", "dry hacking" to "DH",
        "dry cough" to "D", "croup" to "C", "speech" to "SP", "sneeze" to "SN",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val wavDir = File(args.getOrNull(0) ?: "D:\\AndroidProjects\\p3")
        val fragFile = File(args.getOrNull(1)
            ?: "D:\\AndroidProjects\\data\\fractionation\\FFTT04M\\Spectral_Flux_Onset_segments.jsonl")
        val tag = args.getOrNull(2) ?: "p3"
        val outDir = File(Workspace.dir("codebooks").path).apply { mkdirs() }

        println("Phoneme codebook — wavDir=$wavDir  frags=${fragFile.name}  tag=$tag  K=$K")

        // ---- inputs ----
        val labels = loadLabels()                                   // id -> canonical label (cleaned)
        val wavById = wavDir.walkTopDown().filter { it.isFile && it.extension.equals("wav", true) }
            .associateBy { it.nameWithoutExtension }
        val fragsById = loadFragments(fragFile)                     // id -> [(startMs,endMs)]
        println("labels=${labels.size}  wavs=${wavById.size}  clips-with-frags=${fragsById.size}")

        val codebookArg = args.getOrNull(3)        // when set: decode-only, reuse this codebook
        val gson = GsonBuilder().setPrettyPrinting().create()
        val phonemes: List<Phoneme>; val mean: DoubleArray; val std: DoubleArray

        if (codebookArg != null) {
            // ---- decode-only: apply an existing codebook (e.g. p3's) to this dataset ----
            val cb = loadCodebook(File(codebookArg))
            if (cb == null) { println("Could not load codebook: $codebookArg"); return }
            phonemes = cb.first; mean = cb.second; std = cb.third
            println("decode-only: loaded ${phonemes.size} phonemes from ${File(codebookArg).name}")
        } else {
            // ---- 1. featurize fragments of LABELLED clips ----
            data class FV(val label: String, val vec: DoubleArray)
            val labelled = ArrayList<FV>()
            var usedClips = 0
            for ((id, label) in labels) {
                val wav = wavById[id] ?: continue
                val frags = fragsById[id] ?: continue
                val pcm = AudioDecoder.decode(wav) ?: continue
                usedClips++
                for ((sMs, eMs) in frags) fragVec(pcm, sMs, eMs)?.let { labelled.add(FV(label, it)) }
            }
            println("labelled clips used=$usedClips  fragments=${labelled.size}")
            if (labelled.size < K) { println("Too few labelled fragments (${labelled.size}) — label more clips first."); return }

            // ---- 2. z-normalize ----
            val m = DoubleArray(13); val s = DoubleArray(13)
            for (f in labelled) for (i in 0 until 13) m[i] += f.vec[i]
            for (i in 0 until 13) m[i] /= labelled.size
            for (f in labelled) for (i in 0 until 13) { val d = f.vec[i] - m[i]; s[i] += d * d }
            for (i in 0 until 13) s[i] = sqrt(s[i] / labelled.size).coerceAtLeast(1e-9)
            labelled.forEach { znorm(it.vec, m, s) }

            // ---- 3. per-label k-means → phonemes ----
            val byLabel = labelled.groupBy { it.label }
            val built = ArrayList<Phoneme>()
            for ((label, frags) in byLabel.entries.sortedByDescending { it.value.size }) {
                val letter = letterFor(label)
                val kLabel = max(1, (K.toDouble() * frags.size / labelled.size).roundToInt())
                val vecs = frags.map { it.vec }
                val (centroids, assign) = kmeans(vecs, kLabel)
                for (ci in centroids.indices) {
                    val members = vecs.filterIndexed { idx, _ -> assign[idx] == ci }
                    if (members.isEmpty()) continue
                    val dists = members.map { dist(it, centroids[ci]) }.sorted()
                    val radius = dists[(dists.size * 0.9).toInt().coerceIn(0, dists.size - 1)]
                    built.add(Phoneme("$letter${ci + 1}", letter, label, centroids[ci], radius, members.size))
                }
            }
            println("phonemes=${built.size} across ${byLabel.size} labels: " +
                byLabel.entries.sortedByDescending { it.value.size }.joinToString(" ") { "${letterFor(it.key)}=${it.value.size}f" })
            File(outDir, "${tag}_phonemes.json").writeText(gson.toJson(mapOf(
                "tag" to tag, "k" to K, "feature" to "WholeClipFeatures[13] (minus syllabic), z-normed",
                "norm" to mapOf("mean" to m, "std" to s), "phonemes" to built)))
            phonemes = built; mean = m; std = s
        }

        // ---- 4. decode EVERY clip ----
        val decoded = LinkedHashMap<String, Any?>()
        var nDec = 0
        for ((id, wav) in wavById) {
            val frags = fragsById[id] ?: continue
            val pcm = AudioDecoder.decode(wav) ?: continue
            val word = ArrayList<String>()
            for ((sMs, eMs) in frags) {
                val v = fragVec(pcm, sMs, eMs)
                if (v == null) { word.add("?"); continue }
                znorm(v, mean, std)
                var best: Phoneme? = null; var bestD = Double.MAX_VALUE
                for (p in phonemes) { val d = dist(v, p.centroid); if (d < bestD) { bestD = d; best = p } }
                word.add(if (best != null && bestD <= best.radius) best.code else "?")
            }
            val hist = word.groupingBy { it }.eachCount()
            val inferred = word.filter { it != "?" }.map { it.takeWhile { c -> c.isLetter() } }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "?"
            decoded[id] = mapOf("manualLabel" to labels[id], "inferredLetter" to inferred,
                "word" to word, "histogram" to hist)
            nDec++
        }
        File(outDir, "${tag}_decoded.json").writeText(gson.toJson(decoded))
        println("decoded $nDec clips → ${outDir}\\${tag}_decoded.json")

        // ---- 5. quick self-check: inferred letter vs manual label on the labelled clips ----
        var correct = 0; var labelledDec = 0
        for ((id, label) in labels) {
            val d = decoded[id] as? Map<*, *> ?: continue
            labelledDec++
            if (d["inferredLetter"] == letterFor(label)) correct++
        }
        if (labelledDec > 0)
            println("self-check (decode of labelled clips): ${correct}/${labelledDec} match their own label " +
                "(${(100.0 * correct / labelledDec).roundToInt()}%)")
    }

    data class Phoneme(val code: String, val letter: String, val label: String,
                       val centroid: DoubleArray, val radius: Double, val n: Int)

    private fun fragVec(pcm: FloatArray, sMs: Int, eMs: Int): DoubleArray? {
        val s = (sMs / 1000.0 * SR).toInt().coerceIn(0, pcm.size)
        val e = (eMs / 1000.0 * SR).toInt().coerceIn(s, pcm.size)
        if (e - s < SHORT_MIN) return null
        val full = WholeClipFeatures.extract(pcm.copyOfRange(s, e), SR)   // 14-dim
        val v = DoubleArray(13)
        for (i in 0..11) v[i] = full[i]
        v[12] = full[13]                                                  // crestSpec; drop full[12]=syllabic
        for (i in 0 until 13) if (!v[i].isFinite()) v[i] = 0.0
        return v
    }

    private fun znorm(v: DoubleArray, mean: DoubleArray, std: DoubleArray) {
        for (i in v.indices) v[i] = (v[i] - mean[i]) / std[i]
    }

    private fun dist(a: DoubleArray, b: DoubleArray): Double {
        var d = 0.0; for (i in a.indices) { val x = a[i] - b[i]; d += x * x }; return sqrt(d)
    }

    private fun kmeans(data: List<DoubleArray>, k: Int, iters: Int = 60): Pair<Array<DoubleArray>, IntArray> {
        val n = data.size; val dim = data[0].size
        val kk = k.coerceIn(1, n)
        val centroids = Array(kk) { data[(it.toLong() * n / kk).toInt().coerceIn(0, n - 1)].copyOf() }
        val assign = IntArray(n)
        repeat(iters) {
            var moved = false
            for (i in 0 until n) {
                var best = 0; var bd = Double.MAX_VALUE
                for (c in 0 until kk) { val d = dist(data[i], centroids[c]); if (d < bd) { bd = d; best = c } }
                if (assign[i] != best) { assign[i] = best; moved = true }
            }
            if (!moved) return centroids to assign
            val sums = Array(kk) { DoubleArray(dim) }; val cnt = IntArray(kk)
            for (i in 0 until n) { val c = assign[i]; cnt[c]++; val di = data[i]; for (j in 0 until dim) sums[c][j] += di[j] }
            for (c in 0 until kk) if (cnt[c] > 0) for (j in 0 until dim) centroids[c][j] = sums[c][j] / cnt[c]
        }
        return centroids to assign
    }

    // ---- labels ----------------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun loadLabels(): Map<String, String> {
        val f = Workspace.file("manual_comments.json")
        if (!f.isFile) return emptyMap()
        val raw = com.google.gson.Gson().fromJson(f.readText(), Map::class.java) as? Map<String, String> ?: return emptyMap()
        val out = HashMap<String, String>()
        for ((id, v) in raw) cleanLabel(v)?.let { out[id] = it }
        return out
    }

    /** Strip auto-match dumps + "manual:" prefixes, canonicalize synonyms. Null if not a usable label. */
    private fun cleanLabel(raw: String?): String? {
        var s = raw?.trim() ?: return null
        val am = s.indexOf("auto-match", ignoreCase = true)
        if (am >= 0) s = s.substring(0, am)
        s = s.trim().removePrefix("manual:").trim().lowercase()
        if (s.isBlank()) return null
        return when {
            s == "snore" || s.contains("snor") -> "snoring"
            s.contains("bronchitis") || s.contains("brinchitis") -> "bronchitis"
            s.startsWith("dry hack") -> "dry hacking"
            s.startsWith("dry") -> "dry"
            s == "noise" -> "noise"
            s == "croup" -> "croup"
            s == "speech" -> "speech"
            s == "sneeze" || s == "sneezing" -> "sneeze"
            else -> s
        }
    }

    private val assignedLetters = HashMap<String, String>()
    private val usedLetters = HashSet<String>()
    /** A unique letter code per label — collisions (e.g. croup vs another C-word) get C2, C3, … */
    @Synchronized private fun letterFor(label: String): String {
        assignedLetters[label]?.let { return it }
        val base = (letterMap[label] ?: label.split(" ", "-", "_").filter { it.isNotBlank() }
            .joinToString("") { it.first().uppercaseChar().toString() }.take(2)).ifBlank { "X" }
        var cand = base; var i = 1
        while (cand in usedLetters) { i++; cand = "$base$i" }
        usedLetters += cand; assignedLetters[label] = cand
        return cand
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadCodebook(f: File): Triple<List<Phoneme>, DoubleArray, DoubleArray>? = try {
        val root = com.google.gson.Gson().fromJson(f.readText(), Map::class.java) as Map<String, Any>
        val norm = root["norm"] as Map<String, Any>
        val mean = (norm["mean"] as List<*>).map { (it as Number).toDouble() }.toDoubleArray()
        val std = (norm["std"] as List<*>).map { (it as Number).toDouble() }.toDoubleArray()
        val phs = (root["phonemes"] as List<*>).map { p ->
            val m = p as Map<String, Any>
            Phoneme(m["code"] as String, m["letter"] as String, m["label"] as String,
                (m["centroid"] as List<*>).map { (it as Number).toDouble() }.toDoubleArray(),
                (m["radius"] as Number).toDouble(), (m["n"] as Number).toInt())
        }
        Triple(phs, mean, std)
    } catch (e: Exception) { System.err.println("loadCodebook: ${e.message}"); null }

    private fun loadFragments(jsonl: File): Map<String, List<Pair<Int, Int>>> {
        if (!jsonl.isFile) return emptyMap()
        val out = HashMap<String, ArrayList<Pair<Int, Int>>>()
        val idRe = Regex("\"id\":\"([^\"]*)\"")
        val sRe = Regex("\"startMs\":(\\d+)"); val eRe = Regex("\"endMs\":(\\d+)")
        jsonl.bufferedReader().forEachLine { line ->
            val id = idRe.find(line)?.groupValues?.get(1) ?: return@forEachLine
            val s = sRe.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEachLine
            val e = eRe.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEachLine
            out.getOrPut(id) { ArrayList() }.add(s to e)
        }
        return out
    }
}
