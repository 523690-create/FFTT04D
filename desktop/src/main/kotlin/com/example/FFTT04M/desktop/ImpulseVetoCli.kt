package com.example.FFTT04M.desktop

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File

/**
 * Trains the [ImpulseVeto] head (coughs vs impulsive noise) on the CACHED mean-pooled HuBERT clip embeddings
 * (`data/codebooks/clipemb_{ALLDATA,p3,device}.bin`) — no GPU, ~1 min.
 *
 * Training clips: every ALLDATA cough (coswara cough-*, coughvid, dataset_1sec, coughdataset, ESC-50 coughing)
 * + the user's manually labelled device coughs (few, so up-weighted to [DEV_MASS] of the mass) as positives;
 * urban8k / ESC-50 [AutoLabel.IMPULSIVE] clips as negatives. Nothing else is trained on.
 *
 * Scores are honest by construction: GROUPED 5-fold (coswara participant, urban8k/ESC-50 source recording,
 * device serial + recording DAY — a device clip is scored by a model that never heard that day's session;
 * -Dveto.lodo=true groups by serial alone = "a phone it has never heard"), and the full model for clips it
 * did not train on. Output:
 *   default            → data/codebooks/impulse_veto.json + impulse_veto_scores.json   (the deployed pair)
 *   -Dveto.eval=<dir>  → <dir>/impulse_veto.json + scores.json, data/codebooks untouched   (experiments)
 *   -Dexclude.ids=<f>  → ids held out of training, together with their whole group
 *
 * Run: ./gradlew :desktop:impulseVeto [-Dveto.eval=D:/…/out -Dexclude.ids=D:/…/exclude.txt]
 */
object ImpulseVetoCli {

    private class S(val id: String, val x: FloatArray, val cough: Boolean, val group: String, val device: Boolean)

    /** -Dveto.devmass=X — share of the training mass given to the (few) device coughs. */
    private val DEV_MASS = System.getProperty("veto.devmass")?.toDoubleOrNull()?.coerceIn(0.0, 0.9) ?: 0.05
    private val LODO = System.getProperty("veto.lodo")?.toBoolean() == true
    /** -Dveto.l2=X — L2 strength of the head (see [ImpulseVeto.train]). */
    private val L2 = System.getProperty("veto.l2")?.toDoubleOrNull() ?: 1e-2
    private val DAY = Regex("20\\d{6}")

    /** true = cough, false = impulsive noise, null = not a training clip for this specialist. */
    private fun truthOf(id: String): Boolean? {
        val f = id.split("__")
        return when (f[0].lowercase()) {
            "coswara" -> if (f.getOrNull(2)?.startsWith("cough") == true) true else null
            "coughvid", "d1sec", "coughdataset" -> true
            "esc50" -> if (f.getOrNull(2) == "coughing") true else if (AutoLabel.isImpulsive(id)) false else null
            "urban8k" -> if (AutoLabel.isImpulsive(id)) false else null
            else -> null
        }
    }

    /** Clips that must never straddle train/test: one participant / one source recording. */
    private fun groupOf(id: String): String {
        val f = id.split("__"); val k = f.getOrNull(1) ?: return id
        return when (f[0].lowercase()) {
            "coswara" -> "coswara/$k"
            "urban8k" -> "urban8k/" + k.substringBefore('-')
            "esc50" -> "esc50/" + (k.split('-').getOrNull(1) ?: k)
            "d1sec" -> "d1sec/" + k.substringBeforeLast('_')
            else -> id
        }
    }

    private fun category(id: String): String {
        val f = id.split("__")
        return when (f[0].lowercase()) {
            "coswara" -> "coswara " + (f.getOrNull(2)?.substringBefore('-') ?: "?")
            "urban8k", "esc50" -> if (AutoLabel.isImpulsive(id)) "${f[0]} ${f.getOrNull(2)}"
                                  else if (f.getOrNull(2) == "coughing") "esc50 coughing" else "${f[0]} (other)"
            else -> f[0]
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val cb = Workspace.dir("codebooks")
        val evalDir = System.getProperty("veto.eval")?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() } }
        val excluded = System.getProperty("exclude.ids")?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile }
            ?.readLines()?.mapNotNull { it.trim().substringBefore('\t').ifEmpty { null } }?.toSet() ?: emptySet()
        val heldGroups = excluded.mapTo(HashSet()) { groupOf(it) }

        val all = ImpulseVeto.loadEmb(File(cb, "clipemb_ALLDATA.bin"))
        if (all.isEmpty()) { println("no clipemb_ALLDATA.bin — run the embedding cache first (unsupervisedCluster)"); return }
        val dev = ImpulseVeto.loadEmb(File(cb, "clipemb_p3.bin")).apply { putAll(ImpulseVeto.loadEmb(File(cb, "clipemb_device.bin"))) }

        // device clips: manual hard labels + which device (p3/<serial>/…) recorded them
        val manual = HashMap<String, String>()
        runCatching { JsonParser.parseString(Workspace.file("manual_comments.json").readText()).asJsonObject }.getOrNull()
            ?.entrySet()?.forEach { if (it.value.isJsonPrimitive) manual[it.key] = it.value.asString }
        val serial = HashMap<String, String>()
        for (d in listOf(File(repo, "p3"), File(repo, "device_ingest"))) if (d.isDirectory) d.walkTopDown().forEach { f ->
            if (f.isFile && f.extension.equals("wav", true)) {
                val top = f.relativeTo(d).path.replace('\\', '/').substringBefore('/', "")
                serial.putIfAbsent(f.nameWithoutExtension, if (top.isEmpty() || top == f.name) d.name else top)
            }
        }

        val samples = ArrayList<S>(); var nHeld = 0
        for ((id, x) in all) {
            val t = truthOf(id) ?: continue
            val g = groupOf(id)
            if (id in excluded || g in heldGroups) { nHeld++; continue }
            samples.add(S(id, x, t, g, false))
        }
        val nAllData = samples.size
        for ((id, x) in dev) {
            if (id in all || id in excluded) continue
            if (CoughTruth.fromManual(manual[id]) != CoughTruth.Truth.POS) continue      // user text only (CoughTruth.userText)
            val day = if (LODO) "" else "/" + (DAY.find(id)?.value ?: "")
            samples.add(S(id, x, true, "device/" + (serial[id] ?: "unknown") + day, true))
        }
        val nDevCough = samples.size - nAllData
        val nPos = samples.count { it.cough }; val nNeg = samples.size - nPos
        println("=== IMPULSE VETO (cough vs impulsive noise, HuBERT ${all.values.first().size}-d clip embedding) ===")
        println("embeddings: ALLDATA ${all.size}, device ${dev.size} · training clips ${samples.size} " +
            "(cough $nPos incl. $nDevCough device, impulsive $nNeg) · held out $nHeld")
        if (nPos < 100 || nNeg < 100) { println("insufficient data"); return }
        // device coughs get DEV_MASS of the total mass:  w·nDev / (nAll + w·nDev) = DEV_MASS
        val devW = if (nDevCough > 0) (DEV_MASS / (1 - DEV_MASS) * nAllData / nDevCough).coerceAtLeast(1.0) else 1.0
        fun fit(sub: List<S>) = ImpulseVeto.train(sub.map { it.x }, BooleanArray(sub.size) { sub[it].cough },
            DoubleArray(sub.size) { if (sub[it].device) devW else 1.0 }, l2 = L2)

        // grouped 5-fold: out-of-fold score for every training clip
        val p = HashMap<String, Double>(all.size + dev.size)
        val origin = HashMap<String, String>()
        fun fold(g: String) = ((g.hashCode() % 5) + 5) % 5
        val t0 = System.currentTimeMillis()
        for (k in 0 until 5) {
            val m = fit(samples.filter { fold(it.group) != k })
            for (s in samples) if (fold(s.group) == k) { p[s.id] = m.pCough(s.x); origin[s.id] = "oof" }
        }
        val full = fit(samples)
        println("trained 5 grouped folds + full model in ${(System.currentTimeMillis() - t0) / 1000}s (device-cough weight ${"%.1f".format(devW)})")
        for ((id, x) in all) if (id !in p) { p[id] = full.pCough(x); origin[id] = if (truthOf(id) != null) "held" else "other" }
        for ((id, x) in dev) if (id !in p) { p[id] = full.pCough(x); origin[id] = "other" }

        // ---- report: % that PASS the veto (pCough >= tau). Coughs should pass; impulsive clips should not.
        val tau = ImpulseVeto.TAU
        data class G(var n: Int = 0, var pass: Int = 0)
        val rows = sortedMapOf<String, G>()
        fun add(name: String, id: String) { val g = rows.getOrPut(name) { G() }; g.n++; if (p.getValue(id) >= tau) g.pass++ }
        for (id in all.keys) {
            val o = origin[id] ?: continue
            if (o == "other" && excluded.isNotEmpty() && id !in excluded) continue      // eval run: only the listed probes
            val tag = when (o) { "oof" -> "[out-of-fold]"; "held" -> "[HELD OUT]"; else -> "[never trained on]" }
            add("${category(id)} $tag", id)
        }
        for (id in dev.keys) {
            if (id in all) continue
            val how = if (origin[id] != "oof") "[HELD OUT]" else if (LODO) "[leave-device-out]" else "[leave-day-out]"
            when (CoughTruth.fromManual(manual[id])) {
                CoughTruth.Truth.POS -> { add("device cough $how", id); add("device cough $how  ${serial[id] ?: "unknown"}", id) }
                CoughTruth.Truth.NEG -> add("device not-cough [never trained on]", id)
                else -> add("device unlabelled [never trained on]", id)
            }
        }
        println("\npasses the veto (pCough >= $tau) — coughs should pass, impulsive clips should not:")
        for ((name, g) in rows) println("  %-52s %6d  %5.1f%%".format(name, g.n, 100.0 * g.pass / g.n))

        val outDir = evalDir ?: cb
        val info = mapOf("trainedOn" to samples.size, "cough" to nPos, "impulsive" to nNeg, "deviceCough" to nDevCough,
            "heldOut" to nHeld, "tau" to tau)
        ImpulseVeto.save(full, File(outDir, ImpulseVeto.MODEL_FILE), info)
        val scoresFile = File(outDir, if (evalDir != null) "scores.json" else ImpulseVeto.SCORES_FILE)
        scoresFile.writeText(Gson().toJson(p.mapValues { Math.round(it.value * 1e5) / 1e5 }))
        println("\nwrote ${File(outDir, ImpulseVeto.MODEL_FILE)} + ${scoresFile.name} (${p.size} clips)" +
            if (evalDir != null) "  [eval run — data/codebooks untouched]" else "  — restart the desktop app to pick it up")
    }
}
