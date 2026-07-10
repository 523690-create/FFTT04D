package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.fractionation.HubertKMeansUnits
import com.google.gson.JsonParser
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * THE gating validation for the breath-specificity breakthrough ([BreathSpecCli] round 3): the coswara
 * result (linear HuBERT head 4.9% breath-FP -> MLP 1.1% -> fused 1.0%, meeting the user's <=1-2% target)
 * is measured entirely on a PUBLIC dataset. The established OOD lesson ([[cough-eval-framework]] step 2,
 * `DeviceEvalCli`) is that ALLDATA-trained whole-clip models are unusable in-domain on the user's own
 * device recordings. This CLI runs the SAME linear-vs-MLP comparison, but IN-DOMAIN: whole-clip HuBERT
 * embeddings computed directly on the user's manually-labelled device clips (condition 2 hard labels),
 * 5-fold cross-validated on THAT data only — no ALLDATA/coswara training involved.
 *
 * HuBERT embedding requires `desktop/native/hubert/hubert_base.onnx` to be present (CPU inference is fine
 * for ~1-2k clips; GPU via -PuseOnnxGpu is optional here, not required). Caches to
 * `data/codebooks/clipemb_device.bin` (append-only) so re-runs after adding more labelled clips are fast.
 *
 * Run: ./gradlew :desktop:deviceHubertEval -Ddevice.dir=... -Ddevice.manual=...
 */
object DeviceHubertEvalCli {

    private val workers = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private const val SR = 44100
    private val classes = listOf("not_cough", "cough")

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

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val dirs = (System.getProperty("device.dir")?.split(File.pathSeparator)?.map { File(it) }
            ?: listOf(File(repo, "p3"), File(repo, "device_ingest"))).filter { it.isDirectory }
        val mcFile = File(System.getProperty("device.manual")?.takeIf { it.isNotBlank() } ?: Workspace.file("manual_comments.json").path)
        if (!mcFile.isFile) { println("missing manual_comments.json at $mcFile"); return }

        val manual = HashMap<String, String>()
        runCatching { JsonParser.parseString(mcFile.readText()).asJsonObject }.getOrNull()?.entrySet()?.forEach {
            if (it.value.isJsonPrimitive) manual[it.key] = it.value.asString
        }

        data class Item(val wav: File, val id: String, val truth: CoughTruth.Truth, val label: String)
        val items = ArrayList<Item>()
        for (d in dirs) d.walkTopDown().forEach { f ->
            if (f.isFile && f.extension.equals("wav", true)) {
                val lab = manual[f.nameWithoutExtension] ?: return@forEach
                val t = CoughTruth.fromManual(lab)
                if (t != CoughTruth.Truth.SKIP) items.add(Item(f, f.nameWithoutExtension, t, lab.lowercase().trim()))
            }
        }
        val nPos = items.count { it.truth == CoughTruth.Truth.POS }; val nNeg = items.size - nPos
        println("=== DEVICE HuBERT EVAL (in-domain, manual hard labels) ===")
        println("dirs ${dirs.map { it.name }} · labelled device clips ${items.size} (cough=$nPos, not-cough=$nNeg)")
        if (nPos < 50 || nNeg < 50) { println("insufficient labelled device data for a 5-fold in-domain eval"); return }

        val dir = Workspace.dir("codebooks")
        val cacheFile = File(dir, "clipemb_device.bin")
        val cache = loadCache(cacheFile)
        println("embedding cache: ${cache.size} present, ${items.size - items.count { cache.containsKey(it.id) }} to compute")

        var hubertReady: Boolean? = null
        val newEntries = ConcurrentLinkedQueue<Pair<String, DoubleArray>>()
        val emb = HashMap<String, DoubleArray>(items.size)
        val done = AtomicInteger()
        val todo = items.filter { !cache.containsKey(it.id) }
        if (todo.isNotEmpty()) {
            hubertReady = HubertKMeansUnits.available
            if (hubertReady != true) {
                println("HuBERT unavailable (${HubertKMeansUnits.unavailableReason}) -- cannot compute new device embeddings")
            } else {
                println("HuBERT ${HubertKMeansUnits.provider} -- embedding ${todo.size} device clips…")
                val pool = Executors.newFixedThreadPool(workers)
                try {
                    todo.map { it -> pool.submit {
                        runCatching {
                            val pcm = AudioDecoder.decode(it.wav)
                            if (pcm != null && pcm.size in (SR / 16)..(SR * 30)) {
                                val fe = HubertKMeansUnits.frameEmbeddings(pcm, SR)
                                if (fe != null && fe.isNotEmpty()) {
                                    val h = fe[0].size; val v = DoubleArray(h)
                                    for (f in fe) for (j in 0 until h) v[j] += f[j]
                                    for (j in 0 until h) v[j] /= fe.size
                                    newEntries.add(it.id to v)
                                }
                            }
                        }.onFailure { e -> System.err.println("skip ${it.id}: ${e.message}") }
                        val d = done.incrementAndGet(); if (d % 200 == 0) println("  embedded $d/${todo.size}")
                    } }.forEach { it.get() }
                } finally { pool.shutdown() }
                if (newEntries.isNotEmpty()) {
                    val tmp = File(dir, "clipemb_device.bin.tmp")
                    DataOutputStream(tmp.outputStream().buffered()).use { dos ->
                        for ((id, v) in cache) writeEntry(dos, id, v)
                        for ((id, v) in newEntries) writeEntry(dos, id, v)
                    }
                    tmp.copyTo(cacheFile, overwrite = true); tmp.delete()
                }
            }
        }
        for ((id, v) in cache) emb[id] = v
        for ((id, v) in newEntries) emb[id] = v
        println("embeddings ready: ${emb.size}")

        val labelled = items.filter { emb.containsKey(it.id) }
        val nPosE = labelled.count { it.truth == CoughTruth.Truth.POS }; val nNegE = labelled.size - nPosE
        println("labelled clips WITH embeddings: ${labelled.size} (cough=$nPosE, not-cough=$nNegE)")
        if (nPosE < 50 || nNegE < 50) { println("insufficient embedded+labelled data"); return }

        val ids = labelled.map { it.id }
        val y = labelled.map { it.truth == CoughTruth.Truth.POS }
        val x = labelled.map { emb[it.id]!! }

        fun oofLinear(): DoubleArray {
            val out = DoubleArray(x.size)
            for (k in 0 until 5) {
                val tr = x.indices.filter { fold(ids[it]) != k }; val te = x.indices.filter { fold(ids[it]) == k }
                if (tr.isEmpty() || te.isEmpty()) continue
                val model = WholeClipClassifier.train(tr.map { x[it] to if (y[it]) "cough" else "not_cough" }, classes)
                for (i in te) { val (lab, p) = model.predict(x[i]); out[i] = if (lab == "cough") p else 1 - p }
            }
            return out
        }
        fun oofMlp(): DoubleArray {
            val out = DoubleArray(x.size)
            for (k in 0 until 5) {
                val tr = x.indices.filter { fold(ids[it]) != k }; val te = x.indices.filter { fold(ids[it]) == k }
                if (tr.isEmpty() || te.isEmpty()) continue
                val model = Mlp.train(tr.map { x[it] to if (y[it]) "cough" else "not_cough" }, classes)
                for (i in te) { val (lab, p) = model.predict(x[i]); out[i] = if (lab == "cough") p else 1 - p }
            }
            return out
        }

        fun report(name: String, p: DoubleArray) {
            var tp = 0; var fn = 0; var fp = 0; var tn = 0
            for (i in x.indices) { val c = p[i] >= 0.5; if (y[i] && c) tp++ else if (y[i]) fn++ else if (c) fp++ else tn++ }
            val acc = (tp + tn).toDouble() / x.size
            val rec = tp.toDouble() / (tp + fn).coerceAtLeast(1); val rej = tn.toDouble() / (tn + fp).coerceAtLeast(1)
            println("  %-18s acc %5.1f%%  recall %5.1f%%  reject %5.1f%%".format(name, acc * 100, rec * 100, rej * 100))
            val coughScores = x.indices.filter { y[it] }.map { p[it] }.sortedDescending()
            if (coughScores.size >= 10) {
                val thr = coughScores[(0.90 * (coughScores.size - 1)).toInt()]
                var tp2 = 0; var fn2 = 0; var fp2 = 0; var tn2 = 0
                for (i in x.indices) { val c = p[i] >= thr; if (y[i] && c) tp2++ else if (y[i]) fn2++ else if (c) fp2++ else tn2++ }
                val rec2 = tp2.toDouble() / (tp2 + fn2).coerceAtLeast(1); val fpr2 = fp2.toDouble() / (fp2 + tn2).coerceAtLeast(1)
                println("  %-18s @90%% recall op point: thr=%.3f recall=%5.1f%%  FP=%5.1f%%".format(name, thr, rec2 * 100, fpr2 * 100))
            }
            // per-negative-label breakdown (which real-world sounds fool this classifier in-domain?)
            labelled.indices.filter { !y[it] }.groupBy { keyOf(labelled[it].label) }.toList()
                .sortedByDescending { it.second.size }.forEach { (lab, idxs) ->
                    val fpRate = idxs.count { p[it] >= 0.5 }.toDouble() / idxs.size
                    println("    FP by label %-10s %5.1f%%  (n=%d)".format(lab, fpRate * 100, idxs.size))
                }
        }

        println("\n=== 5-fold in-domain comparison (id-hash folds) ===")
        report("LINEAR", oofLinear())
        report("MLP", oofMlp())
    }

    private fun keyOf(lab: String) = when {
        "snor" in lab -> "snore"
        "voice" in lab || "speech" in lab || "talk" in lab -> "voice"
        "breath" in lab -> "breath"
        "noise" in lab -> "noise"
        else -> lab.take(10)
    }
}
