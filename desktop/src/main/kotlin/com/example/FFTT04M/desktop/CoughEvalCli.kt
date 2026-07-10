package com.example.FFTT04M.desktop

import java.io.File

/**
 * Competitive cough/not-cough evaluation over the whole ALLDATA corpus under the canonical [CoughTruth]
 * label policy (user's 5 conditions, 2026-07-10). Compares every method that already has per-segment
 * scores — no GPU, no re-decode — by OR-pooling each method's per-segment calls up to the CLIP level
 * ("a cough was detected somewhere in this clip"), which is the correct operator for BOTH:
 *   - POSITIVE BAGS (coswara/coughvid cough clips): recall = fraction of positive clips called cough
 *     (a bag is satisfied by ≥1 cough hit; we never penalize it for also containing non-cough sound);
 *   - HARD NEGATIVES (non-cough recording types / non-cough DBs): FP-rate = fraction called cough
 *     (any cough call on these is a false positive by condition 4).
 *
 * Clips with no harvested candidate contribute as "not-cough" (correct reject / missed bag), so recall
 * is END-TO-END (DSP segmenter ∘ method ∘ OR-pool) and FP-rate is honest. Per-source/sound_type
 * breakdowns expose which cough kinds each method catches (condition 5: subtypes may differ).
 *
 * Run: ./gradlew :desktop:coughEval -Deval.alldata=D:\AndroidProjects\ALLDATA -Deval.harvest=D:\AndroidProjects\cough_harvest
 */
object CoughEvalCli {

    private val SUFFIX = Regex("__cough\\d+_\\d+-\\d+ms$")
    private fun parentOf(segId: String) = segId.replace(SUFFIX, "")

    private class Clip(val source: String, val soundType: String, val truth: CoughTruth.Truth) {
        var hasCand = false
        val calls = HashMap<String, Boolean>()
        fun or(method: String, v: Boolean) { calls[method] = (calls[method] ?: false) || v }
        fun called(method: String) = calls[method] ?: false
    }

    // display order. seg-OR = per-segment→clip OR-pooled (downstream of the DSP segmenter);
    // @wc = WHOLE-CLIP (segmenter-independent) from cough_wholeclip.csv.
    private val METHODS = listOf(
        "head", "wavelet", "consensus", "hallmark", "forest@seg",
        "squiggleR2>=.6", "squiggleR2>=.8", "fuser>=.5", "fuser>=.7", "fuser>=.8",
        "forest@wc>=.5", "forest@wc>=.7", "squiggle@wc>=.6")

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = Workspace.repoRoot ?: File(".")
        val alldata = File(System.getProperty("eval.alldata")?.takeIf { it.isNotBlank() } ?: File(repo, "ALLDATA").path)
        val harvest = File(System.getProperty("eval.harvest")?.takeIf { it.isNotBlank() } ?: File(repo, "cough_harvest").path)
        val meta = File(alldata, "metadata.csv")
        if (!meta.isFile) { println("missing $meta"); return }

        // ---- clip universe + canonical truth (conditions 3&4 via is_cough) ----
        val clips = HashMap<String, Clip>(90_000)
        meta.useLines { seq ->
            seq.drop(1).forEach { line ->
                val c = line.split(',')
                if (c.size >= 5) {
                    val id = c[0].trim().removeSuffix(".wav")
                    val truth = CoughTruth.fromIsCough(CoughTruth.parseIsCough(c[4]))
                    if (id.isNotEmpty()) clips[id] = Clip(c[1].trim(), c[3].trim(), truth)
                }
            }
        }

        // ---- OR-pool each method's per-segment calls up to the parent clip ----
        File(harvest, "harvest_compare.csv").takeIf { it.isFile }?.useLines { seq ->
            seq.drop(1).forEach { line ->
                val c = line.split(','); val clip = clips[parentOf(c.getOrElse(0) { "" })] ?: return@forEach
                if (c.size >= 7) {
                    clip.hasCand = true
                    val wav = c[5].equals("true", true); val head = c[6].equals("true", true)
                    clip.or("wavelet", wav); clip.or("head", head); clip.or("consensus", wav && head)
                }
            }
        }
        File(harvest, "harvest_forest.csv").takeIf { it.isFile }?.useLines { seq ->
            seq.drop(1).forEach { line ->
                val c = line.split(','); val clip = clips[parentOf(c.getOrElse(0) { "" })] ?: return@forEach
                if (c.size >= 3) clip.or("forest@seg", (c[2].toDoubleOrNull() ?: 0.0) >= 0.5)
            }
        }
        File(harvest, "harvest_hallmark.csv").takeIf { it.isFile }?.useLines { seq ->
            seq.drop(1).forEach { line ->
                val c = line.split(','); val clip = clips[parentOf(c.getOrElse(0) { "" })] ?: return@forEach
                if (c.size >= 3) clip.or("hallmark", c[2].equals("true", true))
            }
        }
        File(harvest, "harvest_dsp.csv").takeIf { it.isFile }?.useLines { seq ->
            seq.drop(1).forEach { line ->
                val c = line.split(','); val clip = clips[parentOf(c.getOrElse(0) { "" })] ?: return@forEach
                if (c.size >= 2) { val r2 = c[1].toDoubleOrNull() ?: 0.0; clip.or("squiggleR2>=.6", r2 >= 0.6); clip.or("squiggleR2>=.8", r2 >= 0.8) }
            }
        }
        File(harvest, "cough_gate.csv").takeIf { it.isFile }?.let { f ->
            val pi = f.useLines { it.first().split(',').indexOf("pFused") }
            if (pi >= 0) f.useLines { seq ->
                seq.drop(1).forEach { line ->
                    val c = line.split(','); val clip = clips[parentOf(c.getOrElse(0) { "" })] ?: return@forEach
                    val p = c.getOrNull(pi)?.toDoubleOrNull() ?: return@forEach
                    clip.or("fuser>=.5", p >= 0.5); clip.or("fuser>=.7", p >= 0.7); clip.or("fuser>=.8", p >= 0.8)
                }
            }
        }

        // ---- whole-clip (segmenter-independent) methods, keyed directly by clip id ----
        File(harvest, "cough_wholeclip.csv").takeIf { it.isFile }?.useLines { seq ->
            seq.drop(1).forEach { line ->
                val c = line.split(','); val clip = clips[c.getOrElse(0) { "" }] ?: return@forEach
                val pForest = c.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                val sqR2 = c.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                clip.or("forest@wc>=.5", pForest >= 0.5); clip.or("forest@wc>=.7", pForest >= 0.7)
                clip.or("squiggle@wc>=.6", sqR2 >= 0.6)
            }
        }

        // ---- metrics ----
        val all = clips.values
        val pos = all.filter { it.truth == CoughTruth.Truth.POS }
        val neg = all.filter { it.truth == CoughTruth.Truth.NEG }
        println("=== COUGH EVAL (ALLDATA, canonical CoughTruth) ===")
        println("clips ${all.size}  ·  POS(bag/hard) ${pos.size}  ·  NEG(hard) ${neg.size}  ·  " +
            "with-candidate ${all.count { it.hasCand }}")
        println("\nmethod            recall(POS)   FP(NEG)    Youden-J")
        for (m in METHODS) {
            val rec = pos.count { it.called(m) }.toDouble() / pos.size.coerceAtLeast(1)
            val fp = neg.count { it.called(m) }.toDouble() / neg.size.coerceAtLeast(1)
            println("  %-16s %6.1f%%      %6.1f%%    %+6.1f".format(m, rec * 100, fp * 100, (rec - fp) * 100))
        }

        // ---- per-group breakdown (condition 5: which cough kinds / which negatives) ----
        fun group(c: Clip) = if (c.source.equals("Coswara", true)) "Coswara/${c.soundType}" else c.source
        val show = listOf("head", "hallmark", "forest@seg", "squiggleR2>=.6", "fuser>=.5", "fuser>=.7")
        println("\n-- RECALL by positive group --            " + show.joinToString("  ") { it.take(9).padStart(9) })
        pos.groupBy(::group).toSortedMap().forEach { (g, cs) ->
            val cells = show.joinToString("  ") { m -> "%8.1f%%".format(cs.count { it.called(m) } * 100.0 / cs.size) }
            println("  %-38s %s".format("$g (${cs.size})", cells))
        }
        println("\n-- FP by negative group --                " + show.joinToString("  ") { it.take(9).padStart(9) })
        neg.groupBy(::group).toSortedMap().forEach { (g, cs) ->
            val cells = show.joinToString("  ") { m -> "%8.1f%%".format(cs.count { it.called(m) } * 100.0 / cs.size) }
            println("  %-38s %s".format("$g (${cs.size})", cells))
        }

        // ---- per-clip dump ----
        val out = File(harvest, "cough_eval.csv")
        out.bufferedWriter().use { w ->
            w.write("id,source,soundType,truth,hasCandidate,${METHODS.joinToString(",")}\n")
            for ((id, c) in clips) {
                if (c.truth == CoughTruth.Truth.SKIP) continue
                w.write("$id,${c.source},${c.soundType},${c.truth},${c.hasCand}," +
                    METHODS.joinToString(",") { if (c.called(it)) "1" else "0" } + "\n")
            }
        }
        println("\nwrote per-clip eval → $out")
        println("NOTE: recall is END-TO-END (DSP segmenter ∘ method); a positive clip with no harvested " +
            "candidate counts as missed. Device recordings (condition 2, manual hard labels) evaluated separately.")
    }
}
