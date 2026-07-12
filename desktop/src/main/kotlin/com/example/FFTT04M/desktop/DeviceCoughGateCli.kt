package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.fractionation.HubertKMeansUnits
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp

/**
 * Trains the on-device cough / not-cough VOTING head, IN-DOMAIN on the user's own labelled device clips
 * (p3 + device_ingest, cough-vs-not via [CoughTruth.fromManual]). The reliability problem (see memory
 * cough-detection-architecture / AutoReject comments): the ALLDATA-trained single models over-fire on the
 * USER's own voice. The fix is a fuser that LEARNS how much to trust each signal on the user's data.
 *
 * Votes fused (all cheaply computable BOTH on desktop for training AND on-device for inference):
 *   - forestP  : [CoughForest] P(cough) over [WholeClipFeatures] (DSP, always available)
 *   - headP    : bundled cough/not-cough head over the whole-clip HuBERT embedding (HuBERT tier only —
 *                NaN otherwise; mean-imputed so the fuser degrades gracefully to a DSP-only vote)
 *   - DSP cues : the [WholeClipFeatures] fields that carry speech/breath info (flatness, pitch_strength,
 *                syllabic_mod, hf_ratio, crest) — subsumes the heuristic SpeechRejector as learned weights.
 *
 * Reports the fused head (LR + MLP) vs each single signal at a fixed operating point, plus FP-by-label,
 * plus a HuBERT-vote-dropped variant (what a DSP-only phone gets). Saves the winner to
 * `data/codebooks/cough_vote.json` for the mobile CoughVote to load.
 *
 * HuBERT embeddings are cached/appended to `clipemb_device.bin` (reused from DeviceHubertEvalCli); only
 * clips not already cached need a GPU pass. Run:
 *   ./gradlew :desktop:deviceCoughGate [-PuseOnnxGpu]  [-Dvote.mlp=true to force saving the MLP]
 */
object DeviceCoughGateCli {

    private val workers = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private const val SR = 44100
    private val classes = listOf("not_cough", "cough")
    // WholeClipFeatures indices: 0 crest,6 flatness,9 hf_ratio,11 pitch_strength,12 syllabic_mod
    private val CUE_IDX = intArrayOf(11, 6, 12, 9, 0)  // pitch_strength, flatness, syllabic_mod, hf_ratio, crest
    private val FEATURE_NAMES = listOf("forestP", "headP", "pitch_strength", "flatness", "syllabic_mod", "hf_ratio", "crest")

    private fun fold(id: String) = ((id.hashCode() % 5) + 5) % 5

    private fun loadCache(f: File): HashMap<String, DoubleArray> {
        val m = HashMap<String, DoubleArray>()
        if (!f.isFile) return m
        DataInputStream(f.inputStream().buffered()).use { dis ->
            while (true) { val id = try { dis.readUTF() } catch (e: EOFException) { break }; val n = dis.readInt(); m[id] = DoubleArray(n) { dis.readFloat().toDouble() } }
        }
        return m
    }
    private fun writeEntry(dos: DataOutputStream, id: String, v: DoubleArray) {
        dos.writeUTF(id); dos.writeInt(v.size); for (x in v) dos.writeFloat(x.toFloat())
    }

    /** The bundled cough/not-cough head (768->2 LR): P(cough) from a whole-clip HuBERT embedding. */
    private class CoughHead(val mean: DoubleArray, val std: DoubleArray, val w: Array<DoubleArray>, val b: DoubleArray, val coughIdx: Int) {
        fun p(emb: DoubleArray): Double? {
            if (emb.size != mean.size) return null
            val z = DoubleArray(emb.size) { (emb[it] - mean[it]) / std[it] }
            val logits = DoubleArray(w.size) { k -> var s = b[k]; val wk = w[k]; for (j in z.indices) s += wk[j] * z[j]; s }
            val mx = logits.max(); var sum = 0.0; val p = DoubleArray(logits.size) { val e = exp(logits[it] - mx); sum += e; e }
            return p[coughIdx] / sum
        }
        companion object {
            fun load(f: File): CoughHead? = try {
                val o = JsonParser.parseString(f.readText()).asJsonObject
                val cls = o.getAsJsonArray("classes").map { it.asString }
                CoughHead(
                    o.getAsJsonArray("mean").map { it.asDouble }.toDoubleArray(),
                    o.getAsJsonArray("std").map { it.asDouble }.toDoubleArray(),
                    o.getAsJsonArray("w").map { r -> r.asJsonArray.map { it.asDouble }.toDoubleArray() }.toTypedArray(),
                    o.getAsJsonArray("b").map { it.asDouble }.toDoubleArray(),
                    cls.indexOfFirst { it.equals("cough", true) }.coerceAtLeast(0))
            } catch (e: Exception) { System.err.println("cough_head load: ${e.message}"); null }
        }
    }

    private class Row(val id: String, val forestP: Double, val cues: DoubleArray, var headP: Double?, val emb: DoubleArray?, val pos: Boolean, val label: String)

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val dirs = listOf(File(repo, "p3"), File(repo, "device_ingest")).filter { it.isDirectory }
        val mcFile = Workspace.file("manual_comments.json")
        if (!mcFile.isFile) { println("missing manual_comments.json at $mcFile"); return }
        val manual = HashMap<String, String>()
        runCatching { JsonParser.parseString(mcFile.readText()).asJsonObject }.getOrNull()?.entrySet()?.forEach {
            if (it.value.isJsonPrimitive) manual[it.key] = it.value.asString
        }
        val forest = CoughForest.loadBundled()
        if (forest == null) { println("CoughForest unavailable — cannot compute forestP"); return }
        val head = CoughHead.load(File(Workspace.dir("codebooks"), "cough_head_ALLDATA.json"))
        if (head == null) println("WARN: cough_head_ALLDATA.json missing — headP disabled (DSP-only vote)")

        data class Item(val wav: File, val id: String, val pos: Boolean, val label: String)
        val items = ArrayList<Item>()
        val seen = HashSet<String>()
        for (d in dirs) d.walkTopDown().forEach { f ->
            if (f.isFile && f.extension.equals("wav", true)) {
                val id = f.nameWithoutExtension
                if (!seen.add(id)) return@forEach
                val lab = manual[id] ?: return@forEach
                val t = CoughTruth.fromManual(lab)
                if (t != CoughTruth.Truth.SKIP) items.add(Item(f, id, t == CoughTruth.Truth.POS, lab.lowercase().trim()))
            }
        }
        val nPos = items.count { it.pos }; val nNeg = items.size - nPos
        println("=== DEVICE COUGH-VOTE TRAINER (in-domain, cough vs not-cough) ===")
        println("dirs ${dirs.map { it.name }} · labelled device clips ${items.size} (cough=$nPos, not-cough=$nNeg)")
        if (nPos < 40 || nNeg < 40) { println("insufficient labelled device data"); return }

        // --- HuBERT whole-clip embeddings for headP (cached; embed only what's missing) ---
        val dir = Workspace.dir("codebooks")
        val cacheFile = File(dir, "clipemb_device.bin")
        val cache = loadCache(cacheFile)
        val todo = if (head == null) emptyList() else items.filter { !cache.containsKey(it.id) }
        println("headP embeddings: ${cache.size} cached, ${todo.size} to compute")
        if (todo.isNotEmpty()) {
            if (HubertKMeansUnits.available != true) {
                println("HuBERT unavailable (${HubertKMeansUnits.unavailableReason}) — headP will be NaN for ${todo.size} new clip(s)")
            } else {
                println("HuBERT ${HubertKMeansUnits.provider} — embedding ${todo.size} clips…")
                val newE = ConcurrentLinkedQueue<Pair<String, DoubleArray>>(); val done = AtomicInteger()
                val pool = Executors.newFixedThreadPool(workers)
                try {
                    todo.map { it -> pool.submit {
                        runCatching {
                            val pcm = AudioDecoder.decode(it.wav)
                            if (pcm != null && pcm.size in (SR / 16)..(SR * 30)) {
                                val fe = HubertKMeansUnits.frameEmbeddings(pcm, SR)
                                if (fe != null && fe.isNotEmpty()) {
                                    val h = fe[0].size; val v = DoubleArray(h)
                                    for (fr in fe) for (j in 0 until h) v[j] += fr[j]
                                    for (j in 0 until h) v[j] /= fe.size
                                    newE.add(it.id to v)
                                }
                            }
                        }.onFailure { e -> System.err.println("skip ${it.id}: ${e.message}") }
                        val n = done.incrementAndGet(); if (n % 200 == 0) println("  embedded $n/${todo.size}")
                    } }.forEach { it.get() }
                } finally { pool.shutdown() }
                if (newE.isNotEmpty()) {
                    val tmp = File(dir, "clipemb_device.bin.tmp")
                    DataOutputStream(tmp.outputStream().buffered()).use { dos ->
                        for ((id, v) in cache) writeEntry(dos, id, v); for ((id, v) in newE) writeEntry(dos, id, v)
                    }
                    tmp.copyTo(cacheFile, overwrite = true); tmp.delete()
                    for ((id, v) in newE) cache[id] = v
                }
            }
        }

        // --- compute forestP + DSP cues (+ headP) per clip ---
        println("computing forestP + DSP cues…")
        val bag = ConcurrentLinkedQueue<Row>(); val done = AtomicInteger(); val pool = Executors.newFixedThreadPool(workers)
        try {
            items.map { it -> pool.submit {
                runCatching {
                    val pcm = AudioDecoder.decode(it.wav)
                    if (pcm != null && pcm.size > 2048) {
                        val wcf = WholeClipFeatures.extract(pcm, SR)
                        val forestP = forest.coughProb(wcf)
                        val cues = DoubleArray(CUE_IDX.size) { i -> wcf[CUE_IDX[i]] }
                        val emb = cache[it.id]
                        val headP = head?.let { h -> emb?.let { e -> h.p(e) } }
                        bag.add(Row(it.id, forestP, cues, headP, emb, it.pos, it.label))
                    }
                }
                if (done.incrementAndGet() % 500 == 0) println("  features ${done.get()}/${items.size}")
            } }.forEach { it.get() }
        } finally { pool.shutdown() }

        val rows = bag.toList()
        val embCov = rows.count { it.emb != null }
        println("feature rows ${rows.size} · HuBERT-emb coverage ${embCov}/${rows.size}")
        val ids = rows.map { it.id }
        val y = rows.map { it.pos }

        // --- IN-DOMAIN HuBERT head: a 768->2 MLP trained on the USER's own device clips (not ALLDATA).
        // This is the strong in-domain method (round-4/5 finding ≈33% FP vs the OOD cough_head's ~58%).
        // OOF predictions here (train on 4 folds, predict the 5th) so it feeds the fuser WITHOUT leakage.
        val devHeadOOF = DoubleArray(rows.size) { 0.5 }
        if (embCov > rows.size / 2) {
            for (k in 0 until 5) {
                val tr = rows.indices.filter { rows[it].emb != null && fold(ids[it]) != k }
                val te = rows.indices.filter { rows[it].emb != null && fold(ids[it]) == k }
                if (tr.size < 40 || te.isEmpty()) continue
                val m = Mlp.train(tr.map { rows[it].emb!! to if (y[it]) "cough" else "not_cough" }, classes, hidden = 32)
                for (i in te) { val (lab, p) = m.predict(rows[i].emb!!); devHeadOOF[i] = if (lab == "cough") p else 1 - p }
            }
        }
        val devHeadMean = devHeadOOF.average()

        // feature vector for the fuser. useHead=false → DSP-only fallback (drops the in-domain head vote).
        fun vec(r: Row, i: Int, useHead: Boolean): DoubleArray {
            val h = if (useHead) devHeadOOF[i] else devHeadMean
            return doubleArrayOf(r.forestP, h, r.cues[0], r.cues[1], r.cues[2], r.cues[3], r.cues[4])
        }

        // 5-fold id-hash OOF for a given feature-builder + model
        fun oofLR(build: (Int) -> DoubleArray): DoubleArray {
            val x = rows.indices.map(build); val out = DoubleArray(x.size)
            for (k in 0 until 5) {
                val tr = x.indices.filter { fold(ids[it]) != k }; val te = x.indices.filter { fold(ids[it]) == k }
                if (tr.isEmpty() || te.isEmpty()) continue
                val m = WholeClipClassifier.train(tr.map { x[it] to if (y[it]) "cough" else "not_cough" }, classes)
                for (i in te) { val (lab, p) = m.predict(x[i]); out[i] = if (lab == "cough") p else 1 - p }
            }
            return out
        }
        fun oofMLP(build: (Int) -> DoubleArray): DoubleArray {
            val x = rows.indices.map(build); val out = DoubleArray(x.size)
            for (k in 0 until 5) {
                val tr = x.indices.filter { fold(ids[it]) != k }; val te = x.indices.filter { fold(ids[it]) == k }
                if (tr.isEmpty() || te.isEmpty()) continue
                val m = Mlp.train(tr.map { x[it] to if (y[it]) "cough" else "not_cough" }, classes, hidden = 12)
                for (i in te) { val (lab, p) = m.predict(x[i]); out[i] = if (lab == "cough") p else 1 - p }
            }
            return out
        }
        // a single raw signal scored directly (identity "probability")
        fun single(v: (Row) -> Double): DoubleArray = rows.map(v).toDoubleArray()

        fun report(name: String, p: DoubleArray) {
            // operating point: threshold at ~90% cough recall on POS, then measure not-cough reject + FP
            val cs = rows.indices.filter { y[it] }.map { p[it] }.sortedDescending()
            if (cs.size < 10) { println("  %-26s (too few cough)".format(name)); return }
            val thr = cs[(0.90 * (cs.size - 1)).toInt()]
            var tp = 0; var fn = 0; var fp = 0; var tn = 0
            for (i in rows.indices) { val c = p[i] >= thr; if (y[i] && c) tp++ else if (y[i]) fn++ else if (c) fp++ else tn++ }
            val rec = tp.toDouble() / (tp + fn).coerceAtLeast(1); val rej = tn.toDouble() / (tn + fp).coerceAtLeast(1)
            val prec = tp.toDouble() / (tp + fp).coerceAtLeast(1); val acc = (tp + tn).toDouble() / rows.size
            println("  %-26s @90%%rec: acc %5.1f%%  reject %5.1f%%  FP %5.1f%%  prec %5.1f%%  (thr %.3f)".format(
                name, acc * 100, rej * 100, (100.0 - rej * 100), prec * 100, thr))
        }
        fun fpByLabel(name: String, p: DoubleArray) {
            val cs = rows.indices.filter { y[it] }.map { p[it] }.sortedDescending()
            val thr = cs[(0.90 * (cs.size - 1)).toInt()]
            println("  FP-by-label ($name @90% recall):")
            rows.indices.filter { !y[it] }.groupBy { key(rows[it].label) }.toList().sortedByDescending { it.second.size }.forEach { (lab, idxs) ->
                val fpr = idxs.count { p[it] >= thr }.toDouble() / idxs.size
                println("    %-10s %5.1f%%  (n=%d)".format(lab, fpr * 100, idxs.size))
            }
        }

        println("\n=== single signals ===")
        val pForest = single { it.forestP }; report("forestP (current gate)", pForest)
        if (head != null) { val pOod = single { it.headP ?: 0.5 }; report("cough_head (ALLDATA/OOD)", pOod) }
        report("in-domain HuBERT-MLP", devHeadOOF)

        println("\n=== fused votes (5-fold id-hash CV) ===")
        val pLR = oofLR { vec(rows[it], it, true) }; report("VOTE LR (all signals)", pLR)
        val pMLP = oofMLP { vec(rows[it], it, true) }; report("VOTE MLP (all signals)", pMLP)
        val pLRnoHead = oofLR { vec(rows[it], it, false) }; report("VOTE LR (DSP-only)", pLRnoHead)

        fpByLabel("VOTE LR", pLR)

        // --- save the deployable in-domain HuBERT head (768->2 MLP, trained on ALL device clips) so the
        // phone runs the SAME strong in-domain method it feeds into the vote. Bundle alongside cough_vote. ---
        val gson = GsonBuilder().setPrettyPrinting().create()
        if (embCov > rows.size / 2) {
            val embRows = rows.filter { it.emb != null }
            val headModel = Mlp.train(embRows.map { it.emb!! to if (it.pos) "cough" else "not_cough" }, classes, hidden = 32)
            Mlp.save(headModel, File(dir, "device_cough_head.json"))
            println("saved in-domain head → ${File(dir, "device_cough_head.json")} (768->32->2 MLP, ${embRows.size} clips)")
        }

        // --- save the deployable fuser (LR — it beat the MLP; forceable with -Dvote.mlp=true) ---
        val useMlp = System.getProperty("vote.mlp")?.toBoolean() == true
        val fa = com.google.gson.JsonArray(); FEATURE_NAMES.forEach { fa.add(it) }
        if (useMlp) {
            val model = Mlp.train(rows.indices.map { vec(rows[it], it, true) to if (rows[it].pos) "cough" else "not_cough" }, classes, hidden = 12)
            Mlp.save(model, File(dir, "cough_vote.json"))
            val o = JsonParser.parseString(File(dir, "cough_vote.json").readText()).asJsonObject
            o.addProperty("type", "mlp"); o.addProperty("headMean", devHeadMean); o.add("features", fa)
            File(dir, "cough_vote.json").writeText(gson.toJson(o))
        } else {
            val model = WholeClipClassifier.train(rows.indices.map { vec(rows[it], it, true) to if (rows[it].pos) "cough" else "not_cough" }, classes)
            val tmp = File(dir, "cough_vote_lr.tmp.json"); WholeClipClassifier.save(model, tmp)
            val o = JsonParser.parseString(tmp.readText()).asJsonObject
            o.addProperty("type", "lr"); o.addProperty("headMean", devHeadMean); o.add("features", fa)
            File(dir, "cough_vote.json").writeText(gson.toJson(o)); tmp.delete()
        }
        println("\nsaved fuser → ${File(dir, "cough_vote.json")}  (type=${if (useMlp) "mlp" else "lr"}, features=$FEATURE_NAMES, headMean=%.3f)".format(devHeadMean))
    }

    private fun key(lab: String) = when {
        "snor" in lab || "breath" in lab -> "breath/snore"
        "voice" in lab || "speech" in lab || "talk" in lab || "sing" in lab || "music" in lab -> "voice"
        "noise" in lab -> "noise"
        "sneeze" in lab -> "sneeze"
        else -> lab.take(10)
    }
}
