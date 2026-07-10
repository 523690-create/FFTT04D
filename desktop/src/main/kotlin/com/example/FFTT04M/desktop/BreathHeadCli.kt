package com.example.FFTT04M.desktop

import java.io.DataInputStream
import java.io.EOFException
import java.io.File

/**
 * Specialist cough-vs-{breath,speech} discriminator for step 1 (kill the residual breathing/speech false
 * positives the general clip-gate can't). Trained on COSWARA's MATCHED pairs — the same participants
 * recorded cough-heavy/shallow AND breathing-deep/shallow / vowel-* / counting-* on the same mic, so the
 * boundary is learned free of mic/domain confounds — using the CACHED whole-clip HuBERT embeddings
 * (data/codebooks/clipemb_ALLDATA.bin), so NO GPU is needed.
 *
 * Emits an OUT-OF-FOLD score for every training (coswara) clip and a full-model score for all other
 * embedded clips → cough_harvest/cough_specialist.csv (id,pSpec). OOF is essential: this score becomes a
 * feature of the clip-gate, and an in-sample score on the same clips the clip-gate is CV'd on would leak.
 *
 * Run: ./gradlew :desktop:breathHead   (reads clipemb_ALLDATA.bin + ALLDATA/metadata.csv)
 */
object BreathHeadCli {

    private val classes = listOf("not_cough", "cough")

    private fun loadEmb(f: File): HashMap<String, DoubleArray> {
        val m = HashMap<String, DoubleArray>(90_000)
        if (!f.isFile) return m
        DataInputStream(f.inputStream().buffered()).use { dis ->
            while (true) {
                val id = try { dis.readUTF() } catch (e: EOFException) { break }
                val n = dis.readInt(); m[id] = DoubleArray(n) { dis.readFloat().toDouble() }
            }
        }
        return m
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val alldata = File(System.getProperty("eval.alldata")?.takeIf { it.isNotBlank() } ?: File(repo, "ALLDATA").path)
        val harvest = File(System.getProperty("eval.harvest")?.takeIf { it.isNotBlank() } ?: File(repo, "cough_harvest").path)
        val emb = loadEmb(File(Workspace.dir("codebooks"), "clipemb_ALLDATA.bin"))
        if (emb.isEmpty()) { println("no clipemb_ALLDATA.bin embeddings"); return }
        val meta = File(alldata, "metadata.csv"); if (!meta.isFile) { println("missing $meta"); return }

        // metadata: id -> (source, soundType)
        val src = HashMap<String, Pair<String, String>>(90_000)
        meta.useLines { seq -> seq.drop(1).forEach { line ->
            val c = line.split(','); if (c.size >= 4) src[c[0].trim().removeSuffix(".wav")] = c[1].trim() to c[3].trim()
        } }

        // matched coswara training pairs (POS cough vs NEG breath/vowel/counting), with embeddings present
        data class S(val id: String, val x: DoubleArray, val pos: Boolean, val neg: String)
        val samples = ArrayList<S>()
        for ((id, e) in emb) {
            val si = src[id] ?: continue
            if (!si.first.equals("Coswara", true)) continue
            val st = si.second.lowercase()
            when {
                st.startsWith("cough") -> samples.add(S(id, e, true, "cough"))
                st.startsWith("breathing") -> samples.add(S(id, e, false, "breath"))
                st.startsWith("vowel") -> samples.add(S(id, e, false, "vowel"))
                st.startsWith("counting") -> samples.add(S(id, e, false, "counting"))
            }
        }
        val nPos = samples.count { it.pos }; val nNeg = samples.size - nPos
        println("=== BREATH/SPEECH SPECIALIST (coswara-matched, HuBERT ${emb.values.first().size}-d) ===")
        println("embeddings ${emb.size} · coswara train samples ${samples.size} (cough=$nPos, neg=$nNeg)")
        if (nPos < 100 || nNeg < 100) { println("insufficient matched coswara data"); return }

        // 5-fold OOF by id-hash; report cough recall + per-negative-type rejection
        fun fold(id: String) = ((id.hashCode() % 5) + 5) % 5
        val oof = HashMap<String, Double>()
        var tp = 0; var fn = 0
        val negTot = HashMap<String, Int>(); val negRej = HashMap<String, Int>()
        for (k in 0 until 5) {
            val tr = samples.filter { fold(it.id) != k }; val te = samples.filter { fold(it.id) == k }
            val model = WholeClipClassifier.train(tr.map { it.x to if (it.pos) "cough" else "not_cough" }, classes)
            for (s in te) {
                val (lab, p) = model.predict(s.x); val pc = if (lab == "cough") p else 1 - p
                oof[s.id] = pc
                if (s.pos) { if (pc >= 0.5) tp++ else fn++ }
                else { negTot[s.neg] = (negTot[s.neg] ?: 0) + 1; if (pc < 0.5) negRej[s.neg] = (negRej[s.neg] ?: 0) + 1 }
            }
        }
        println("5-fold: cough recall %.1f%%".format(100.0 * tp / (tp + fn).coerceAtLeast(1)))
        for (n in listOf("breath", "vowel", "counting"))
            println("  reject %-9s %.1f%%  (n=%d)".format(n, 100.0 * (negRej[n] ?: 0) / (negTot[n] ?: 1), negTot[n] ?: 0))

        // full model (all coswara) for scoring the NON-coswara embedded clips (no leakage there)
        val full = WholeClipClassifier.train(samples.map { it.x to if (it.pos) "cough" else "not_cough" }, classes)
        val out = File(harvest, "cough_specialist.csv")
        var scored = 0
        out.bufferedWriter().use { w ->
            w.write("id,pSpec\n")
            for ((id, e) in emb) {
                val p = oof[id] ?: run { val (lab, pp) = full.predict(e); if (lab == "cough") pp else 1 - pp }
                w.write("$id,${"%.4f".format(p)}\n"); scored++
            }
        }
        WholeClipClassifier.save(full, File(Workspace.dir("codebooks"), "cough_specialist.json"))
        println("wrote $scored specialist scores → $out  (+ cough_specialist.json)")
    }
}
