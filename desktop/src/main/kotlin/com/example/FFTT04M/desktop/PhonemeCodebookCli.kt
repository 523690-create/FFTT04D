package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.cough.RidgeExtractor
import com.example.FFTT04M.desktop.fractionation.HubertKMeansUnits
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
    private val K = System.getProperty("codebook.k")?.toIntOrNull() ?: 128   // -Dcodebook.k=256 → 256 phonemes
    private const val SHORT_MIN = 256          // skip fragments shorter than this many samples
    private const val WIN_MS = 180             // fixed-grid window — deterministic fragmentation (no onset-count variance)
    private const val HOP_MS = 90              // 50% overlap → robust to frame-shifts
    private const val K_ALPHA = 256            // single-alphabet unit count (hex [00]..[FF])
    // -Dsingle.alphabet=true → ONE global k-means (class-agnostic units), not per-label phonemes.
    private val SINGLE_ALPHABET = System.getProperty("single.alphabet")?.toBoolean() == true

    /** Fixed-grid overlapping windows. Replaces variable onset-based fractionation for the codebook:
     *  the window count depends only on clip duration, so similar clips get the same phoneme count
     *  (no over/under-segmentation), and the 50% overlap absorbs frame-shifts. */
    private fun framesFor(pcm: FloatArray, sr: Int): List<Pair<Int, Int>> {
        val durMs = (pcm.size.toLong() * 1000 / sr).toInt()
        if (durMs <= WIN_MS) return if (durMs > 0) listOf(0 to durMs) else emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        var s = 0
        while (s < durMs) {
            val e = (s + WIN_MS).coerceAtMost(durMs)
            if (e - s >= WIN_MS / 2) out.add(s to e)   // drop a tiny trailing window
            if (e >= durMs) break
            s += HOP_MS
        }
        return out
    }
    private const val AUTO_FRAG_CAP = 2000     // cap fragments per AUTO label (≈ largest manual class; manual is never capped)
    private const val RADIUS_PCTL = 0.75       // intra-cluster distance percentile for a phoneme's radius (the "?" gate); lower = stricter

    private val letterMap = linkedMapOf(
        "snoring" to "S", "bronchitis" to "B", "noise" to "N", "dry" to "D", "dry hacking" to "DH",
        "dry cough" to "D", "croup" to "C", "speech" to "SP", "sneeze" to "SN", "music" to "M", "voice" to "V",
        "typical bronchitis" to "BT",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val wavDir = File(args.getOrNull(0) ?: (Workspace.repoRoot?.resolve("p3")?.path ?: "p3"))
        val fragFile = File(args.getOrNull(1)
            ?: Workspace.dir("fractionation").resolve("FFTT04M/Spectral_Flux_Onset_segments.jsonl").path)
        val tag = args.getOrNull(2) ?: "p3"
        val outDir = File(Workspace.dir("codebooks").path).apply { mkdirs() }

        println("Phoneme codebook — wavDir=$wavDir  frags=${fragFile.name}  tag=$tag  K=$K")

        // ---- inputs ----
        val labels = loadLabels()                                   // id -> canonical label (cleaned)
        val wavById = wavDir.walkTopDown().filter { it.isFile && it.extension.equals("wav", true) }
            .associateBy { it.nameWithoutExtension }
        val fragsById = loadFragments(fragFile)                     // id -> [(startMs,endMs)]
        println("labels=${labels.size}  wavs=${wavById.size}  clips-with-frags=${fragsById.size}")
        if (USE_HUBERT) println("HuBERT features ON — available=${HubertKMeansUnits.available} provider=${HubertKMeansUnits.provider}" +
            (if (!HubertKMeansUnits.available) " (${HubertKMeansUnits.unavailableReason})" else ""))

        val codebookArg = args.getOrNull(3)        // when set: decode-only, reuse this codebook
        val gson = GsonBuilder().setPrettyPrinting().create()
        val phonemes: List<Phoneme>; val mean: DoubleArray; val std: DoubleArray
        var clsModel: HistogramClassifier.Model? = null
        var wcModel: WholeClipClassifier.Model? = null

        if (codebookArg != null) {
            // ---- decode-only: apply an existing codebook (e.g. p3's) to this dataset ----
            val cb = loadCodebook(File(codebookArg))
            if (cb == null) { println("Could not load codebook: $codebookArg"); return }
            phonemes = cb.first; mean = cb.second; std = cb.third
            val cbDir = File(codebookArg).parentFile; val cbName = File(codebookArg).name
            clsModel = HistogramClassifier.load(File(cbDir, cbName.replace("_phonemes", "_classifier")))
            wcModel = WholeClipClassifier.load(File(cbDir, cbName.replace("_phonemes", "_wholeclip")))
            println("decode-only: loaded ${phonemes.size} phonemes from $cbName" +
                (if (clsModel != null) " + classifier" else "") + (if (wcModel != null) " + whole-clip" else ""))
        } else {
            // ---- aggregate labelled clips across ALL datasets (label any dataset → feeds the codebook) ----
            // Merge every dataset's Spectral-Flux fragments, and index wavs from every known dataset dir,
            // so an id labelled in ALLDATA contributes even when this run's target is p3 (and vice-versa).
            val fracRoot = fragFile.absoluteFile.parentFile?.parentFile     // …/data/fractionation
            val buildFragsById = HashMap<String, List<Pair<Int, Int>>>()
            fracRoot?.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { sub ->
                File(sub, "Spectral_Flux_Onset_segments.jsonl").takeIf { it.isFile }
                    ?.let { buildFragsById.putAll(loadFragments(it)) }
            }
            val parent = wavDir.absoluteFile.parentFile
            val datasetRoots = (listOf(wavDir) + listOf("p3", "ALLDATA", "true_cough").map { File(parent ?: wavDir, it) })
                .filter { it.isDirectory }.distinctBy { it.absolutePath }
            val buildWavById = datasetRoots.asSequence().flatMap { it.walkTopDown() }
                .filter { it.isFile && it.extension.equals("wav", true) }.associateBy { it.nameWithoutExtension }
            println("aggregate build: ${buildWavById.size} wavs across ${datasetRoots.size} dataset dir(s) " +
                "(${datasetRoots.joinToString { it.name }}), ${buildFragsById.size} clips-with-frags")

            // ---- 1. featurize fragments of LABELLED clips (manual ⊕ auto) ----
            // Auto-labels (AutoLabel, from the clip id) are accepted as ground truth EQUAL to manual
            // comments. Manual clips are ALWAYS kept; only the big auto classes (speech/noise) are capped,
            // by fragment count, so they don't swamp the codebook (speech's long vowel/counting clips yield
            // many fragments each). Hash-ordered so a capped auto class samples evenly across its sources
            // (speech draws from both coswara vowels and old-time radio). Note: manual `noise`/`speech`
            // share a label with auto `urban8k`/`train`+coswara — correct, they're the same class.
            data class FV(val label: String, val vec: DoubleArray)
            val labelled = ArrayList<FV>()
            val perClip = ArrayList<Pair<String, List<DoubleArray>>>()   // (label, fragment vecs) per clip → classifier training
            val perClipWhole = ArrayList<Pair<DoubleArray, String>>()    // (14-dim whole-clip feature, label) → whole-clip classifier
            val autoFrags = HashMap<String, Int>()
            var usedClips = 0; var autoClips = 0
            for (id in buildFragsById.keys.sortedBy { it.hashCode() }) {
                val manual = labels[id]
                val label = bronchitisByDate(id, manual ?: AutoLabel.forId(id)) ?: continue
                if (manual == null && (autoFrags[label] ?: 0) >= AUTO_FRAG_CAP) continue
                val wav = buildWavById[id] ?: continue
                val pcm = AudioDecoder.decode(wav)?.also { rmsNormalize(it) } ?: continue
                val frags = framesFor(pcm, SR)
                val clipVecs = clipFeatures(pcm, SR, frags)   // DSP(13) or HuBERT(768) per window
                for (v in clipVecs) labelled.add(FV(label, v))
                if (clipVecs.isNotEmpty()) perClip.add(label to clipVecs)   // same vec refs → z-normed in step 2
                perClipWhole.add(wholeClipFeat(pcm, frags) to label)   // 14-dim whole-clip feature (always DSP)
                if (manual == null) { autoFrags[label] = autoFrags.getOrDefault(label, 0) + clipVecs.size; autoClips++ }
                usedClips++
            }
            println("labelled clips used=$usedClips (manual=${usedClips - autoClips}, auto=$autoClips)  fragments=${labelled.size}")
            if (labelled.size < K) { println("Too few labelled fragments (${labelled.size}) — label more clips first."); return }

            // ---- 2. z-normalize ----
            val dim = labelled.first().vec.size   // 13 (DSP) or 768 (HuBERT)
            val m = DoubleArray(dim); val s = DoubleArray(dim)
            for (f in labelled) for (i in 0 until dim) m[i] += f.vec[i]
            for (i in 0 until dim) m[i] /= labelled.size
            for (f in labelled) for (i in 0 until dim) { val d = f.vec[i] - m[i]; s[i] += d * d }
            for (i in 0 until dim) s[i] = sqrt(s[i] / labelled.size).coerceAtLeast(1e-9)
            labelled.forEach { znorm(it.vec, m, s) }

            // ---- 3. k-means → codebook ----
            val byLabel = labelled.groupBy { it.label }
            val built = ArrayList<Phoneme>()
            if (SINGLE_ALPHABET) {
                // ONE global, class-agnostic alphabet — hex units [00]..[FF]. The codebook is a pure
                // acoustic vocabulary; classification is a separate downstream step on the unit "words"
                // (so relabelling never forces a codebook rebuild — textless-NLP style).
                HistogramClassifier.unitLevel = true
                val vecs = labelled.map { it.vec }
                val kA = K_ALPHA.coerceAtMost(vecs.size)
                val (centroids, assign) = kmeans(vecs, kA)
                for (ci in centroids.indices) {
                    val members = vecs.filterIndexed { idx, _ -> assign[idx] == ci }
                    if (members.isEmpty()) continue
                    val dists = members.map { dist(it, centroids[ci]) }.sorted()
                    val radius = dists[(dists.size * RADIUS_PCTL).toInt().coerceIn(0, dists.size - 1)]
                    val code = "[%02X]".format(ci)
                    built.add(Phoneme(code, code, "", centroids[ci], radius, members.size))   // class-agnostic
                }
                println("single alphabet: ${built.size} units (K=$kA) over ${labelled.size} fragments, ${byLabel.size} classes")
            } else {
                // Balanced (√-proportional) per-label allocation: phonemes ∝ √(fragment count) so an
                // over-represented label doesn't dominate the catchment. Capped by #fragments.
                val sqrtTotal = byLabel.values.sumOf { sqrt(it.size.toDouble()) }.coerceAtLeast(1e-9)
                for ((label, frags) in byLabel.entries.sortedByDescending { it.value.size }) {
                    val letter = letterFor(label)
                    val kLabel = max(1, (K * sqrt(frags.size.toDouble()) / sqrtTotal).roundToInt()).coerceAtMost(frags.size)
                    val vecs = frags.map { it.vec }
                    val (centroids, assign) = kmeans(vecs, kLabel)
                    for (ci in centroids.indices) {
                        val members = vecs.filterIndexed { idx, _ -> assign[idx] == ci }
                        if (members.isEmpty()) continue
                        val dists = members.map { dist(it, centroids[ci]) }.sorted()
                        val radius = dists[(dists.size * RADIUS_PCTL).toInt().coerceIn(0, dists.size - 1)]
                        built.add(Phoneme("$letter${ci + 1}", letter, label, centroids[ci], radius, members.size))
                    }
                }
                println("phonemes=${built.size} across ${byLabel.size} labels: " +
                    byLabel.entries.sortedByDescending { it.value.size }.joinToString(" ") { "${letterFor(it.key)}=${it.value.size}f" })
            }
            File(outDir, "${tag}_phonemes.json").writeText(gson.toJson(mapOf(
                "tag" to tag, "k" to K, "feature" to "WholeClipFeatures[13] (minus syllabic), z-normed",
                "norm" to mapOf("mean" to m, "std" to s), "phonemes" to built)))
            phonemes = built; mean = m; std = s

            // ---- 3b. histogram classifier: predict the clip's class from its phoneme-letter distribution
            //          (robust to single-fragment flips, unlike the dominant-letter rule) ----
            val clsClasses = byLabel.keys.toList()
            val clsSamples = perClip.map { (label, vecs) ->
                vecs.map { v ->
                    var best: Phoneme? = null; var bd = Double.MAX_VALUE
                    for (p in built) { val d = dist(v, p.centroid); if (d < bd) { bd = d; best = p } }
                    if (best != null && bd <= best.radius) best.code else "?"
                } to label
            }
            val letToLabel = built.associate { it.letter to it.label }
            val base = clsSamples.count { (word, label) ->
                letToLabel[word.filter { it != "?" }.map { it.takeWhile { c -> c.isLetter() } }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key] == label
            }
            val cv = HistogramClassifier.crossVal(clsSamples, clsClasses)
            clsModel = HistogramClassifier.train(clsSamples, clsClasses)
            HistogramClassifier.save(clsModel, File(outDir, "${tag}_classifier.json"))
            println("classifier: 5-fold CV ${(cv * 100).roundToInt()}%  vs dominant-letter ${(100.0 * base / clsSamples.size).roundToInt()}%" +
                "  (${clsSamples.size} clips, ${clsClasses.size} classes)")

            // ---- 3c. whole-clip second-opinion classifier (one 14-dim vector/clip → stable on short coughs) ----
            val wcCv = WholeClipClassifier.crossVal(perClipWhole, clsClasses)
            wcModel = WholeClipClassifier.train(perClipWhole, clsClasses)
            WholeClipClassifier.save(wcModel, File(outDir, "${tag}_wholeclip.json"))
            println("whole-clip classifier: 5-fold CV ${(wcCv * 100).roundToInt()}%  (${perClipWhole.size} clips)")
        }

        // ---- 4. decode EVERY clip (parallel across all cores — the heavy step, esp. on ALLDATA) ----
        val decoded = java.util.concurrent.ConcurrentHashMap<String, Any?>()
        val cores = if (USE_HUBERT) 3 else Runtime.getRuntime().availableProcessors().coerceAtLeast(1)   // cap GPU concurrency for HuBERT
        val pool = java.util.concurrent.Executors.newFixedThreadPool(cores)
        println("decoding ${wavById.size} clips on $cores threads…")
        try {
            wavById.entries.map { (id, wav) ->
                pool.submit {
                    val pcm = AudioDecoder.decode(wav)?.also { rmsNormalize(it) } ?: return@submit
                    val frags = framesFor(pcm, SR)
                    val word = ArrayList<String>()
                    for (v in clipFeatures(pcm, SR, frags)) {
                        znorm(v, mean, std)
                        var best: Phoneme? = null; var bestD = Double.MAX_VALUE
                        for (p in phonemes) { val d = dist(v, p.centroid); if (d < bestD) { bestD = d; best = p } }
                        word.add(if (best != null && bestD <= best.radius) best.code else "?")
                    }
                    val hist = word.groupingBy { it }.eachCount()
                    val inferred = word.filter { it != "?" }.map { it.takeWhile { c -> c.isLetter() } }
                        .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "?"
                    val cls = clsModel?.predict(word)
                    val wc = wcModel?.predict(wholeClipFeat(pcm, frags))
                    decoded[id] = mapOf("manualLabel" to labels[id], "autoLabel" to AutoLabel.forId(id),
                        "inferredLetter" to inferred, "classLabel" to cls?.first, "classProb" to cls?.second,
                        "wholeClipLabel" to wc?.first, "wholeClipProb" to wc?.second,
                        "word" to word, "histogram" to hist)
                }
            }.forEach { it.get() }
        } finally { pool.shutdown() }
        val nDec = decoded.size
        File(outDir, "${tag}_decoded.json").writeText(gson.toJson(decoded))
        println("decoded $nDec clips → ${outDir}\\${tag}_decoded.json")

        // ---- 5. quick self-check: inferred letter vs manual label on the labelled clips ----
        var correct = 0; var labelledDec = 0
        for ((id, d0) in decoded) {
            val label = bronchitisByDate(id, labels[id] ?: AutoLabel.forId(id)) ?: continue   // manual wins, else auto; date-split bronchitis
            val d = d0 as? Map<*, *> ?: continue
            labelledDec++
            if (d["inferredLetter"] == letterFor(label)) correct++
        }
        if (labelledDec > 0)
            println("self-check (decode of labelled clips): ${correct}/${labelledDec} match their own label " +
                "(${(100.0 * correct / labelledDec).roundToInt()}%)")
    }

    data class Phoneme(val code: String, val letter: String, val label: String,
                       val centroid: DoubleArray, val radius: Double, val n: Int)

    /** Scale the whole clip to a target RMS so device mic-gain differences (e.g. Pixel 10 ≈ 8× the
     *  Pixel 3a) don't shift level-sensitive features. Applied uniformly, so relative levels between
     *  fragments — which ARE informative — are preserved. Silent clips are left alone. */
    private fun rmsNormalize(pcm: FloatArray, target: Float = 0.1f) {
        var s = 0.0; for (x in pcm) s += x.toDouble() * x
        val rms = sqrt(s / pcm.size.coerceAtLeast(1))
        if (rms > 1e-5) { val g = (target / rms).toFloat(); for (i in pcm.indices) pcm[i] *= g }
    }

    private val ridge = RidgeExtractor()   // stateless + CPU → safe to share across the parallel decode

    /** 14-dim WholeClipFeatures over the ACTIVE span (first→last fragment, so silence doesn't dilute the
     *  cough) PLUS 4 CWT-ridge "chirp" features (curvature, slope, centre-freq, R²) — the frequency-sweep
     *  pattern you can see in the CWT image (snoring's flowing ridge). 18-dim total. */
    private fun wholeClipFeat(pcm: FloatArray, frags: List<Pair<Int, Int>>): DoubleArray {
        val s = if (frags.isEmpty()) 0 else (frags.minOf { it.first } / 1000.0 * SR).toInt().coerceIn(0, pcm.size)
        val e = if (frags.isEmpty()) pcm.size else (frags.maxOf { it.second } / 1000.0 * SR).toInt().coerceIn(s, pcm.size)
        val base = WholeClipFeatures.extract(if (e - s > SHORT_MIN) pcm.copyOfRange(s, e) else pcm, SR)   // 14
        val rf = try { ridge.extract(pcm, s, e, SR).features } catch (_: Exception) { null }
        val rv = if (rf?.valid == true) doubleArrayOf(rf.curvature, rf.slope, rf.centerFreqHz, rf.rSquared)
                 else doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        return base + rv
    }

    // Experimental: -Dhubert.feat=true swaps the per-window feature from WholeClipFeatures(13) to a
    // pooled HuBERT embedding(768). Same fixed grid; only the feature changes.
    private val USE_HUBERT = System.getProperty("hubert.feat")?.toBoolean() == true

    /** Per-window feature vectors for a clip. DSP mode: fragVec per window (13-dim, short windows
     *  dropped). HuBERT mode: one HuBERT pass → mean-pool the frames in each window (768-dim). */
    private fun clipFeatures(pcm: FloatArray, sr: Int, frags: List<Pair<Int, Int>>): List<DoubleArray> {
        if (USE_HUBERT) {
            val emb = HubertKMeansUnits.frameEmbeddings(pcm, sr)
            if (emb != null && emb.isNotEmpty()) {
                val t = emb.size; val h = emb[0].size
                val durMs = (pcm.size.toLong() * 1000 / sr).toInt().coerceAtLeast(1)
                val msPerFrame = durMs.toDouble() / t
                return frags.map { (sMs, eMs) ->
                    val f0 = (sMs / msPerFrame).toInt().coerceIn(0, t - 1)
                    val f1 = (eMs / msPerFrame).toInt().coerceIn(f0 + 1, t)
                    val v = DoubleArray(h); var n = 0
                    for (f in f0 until f1) { val ef = emb[f]; for (j in 0 until h) v[j] += ef[j]; n++ }
                    if (n > 0) for (j in 0 until h) v[j] /= n
                    v
                }
            }
        }
        return frags.mapNotNull { (s, e) -> fragVec(pcm, s, e) }
    }

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
            // bronchitis + the user's variants/typos that mean it ("evin p", "quad cough")
            s.contains("bronchitis") || s.contains("brinchitis") || s.contains("evin") || s.contains("quad") -> "bronchitis"
            s.startsWith("dry hack") -> "dry hacking"
            s.startsWith("dry") -> "dry"
            s == "noise" -> "noise"
            s == "croup" -> "croup"
            // fused vocal/tonal class: speech, music, crying, singing all → "voice"
            s == "speech" || s.contains("music") || s.contains("singing") || s == "crying" || s == "cry" -> "voice"
            s == "sneeze" || s == "sneezing" -> "sneeze"
            else -> s
        }
    }

    /** The user's bronchitis cough dried out over the illness: typical/early → BT, later → dry hacking,
     *  with a fuzzy transition in the middle treated as unknown (excluded). Applies only to clips
     *  manually labelled "bronchitis" in the 2026-06 illness window (by the date in the filename);
     *  everything else (incl. non-2026 datasets) passes through unchanged. */
    private val dateRe = Regex("(20\\d{6})")
    private fun bronchitisByDate(id: String, label: String?): String? {
        if (label != "bronchitis") return label
        val date = dateRe.find(id)?.groupValues?.get(1)?.toIntOrNull() ?: return label
        return when {
            date in 20260601..20260612 -> "typical bronchitis"   // BT
            date in 20260613..20260615 -> null                   // fuzzy transition → unknown, exclude from training
            date >= 20260616 -> "dry hacking"
            else -> label                                        // outside the window → unchanged
        }
    }

    private val assignedLetters = HashMap<String, String>()
    private val usedLetters = HashSet<String>()
    /** A unique, DIGIT-FREE letter code per label so `[letters][number]` always parses unambiguously.
     *  Collisions extend with more of the label's own letters (croup→C, next C-word→CR/CRO…), then a
     *  trailing letter as a last resort. Never appends a digit. */
    @Synchronized private fun letterFor(label: String): String {
        assignedLetters[label]?.let { return it }
        val cands = LinkedHashSet<String>()
        letterMap[label]?.let { cands.add(it) }
        val initials = label.split(" ", "-", "_", "/", ",")
            .mapNotNull { w -> w.firstOrNull { it.isLetter() }?.uppercaseChar() }.joinToString("")
        for (len in 1..initials.length) cands.add(initials.take(len))
        val letters = label.filter { it.isLetter() }.uppercase()
        for (len in 1..minOf(5, letters.length)) cands.add(letters.take(len))
        var chosen = cands.firstOrNull { it.isNotBlank() && it !in usedLetters }
        if (chosen == null) {
            val base = cands.firstOrNull { it.isNotBlank() } ?: "X"
            var s = 'A'; while (s < 'Z' && "$base$s" in usedLetters) s++
            chosen = "$base$s"
        }
        usedLetters += chosen; assignedLetters[label] = chosen
        return chosen
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
