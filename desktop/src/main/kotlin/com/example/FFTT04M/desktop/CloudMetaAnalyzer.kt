package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.cough.CoughAnalysis
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * Cloud meta-analysis (stage 1): turn an ALLDATA `metadata.csv` row into (a) its labels — one sound
 * cloud + a multi-label qualifier set — and (b) a per-recording feature vector (aggregated cough DSP
 * + the paroxysm block). Later stages add image descriptors, cloud building, and k-NN matching.
 *
 * Label rules are the user's agreed taxonomy (see memory `meta-analysis-cloud-design`):
 *  - COUGH = one cloud (all cough clips); every non-cough category is its own separate cloud.
 *  - Qualifiers are multi-label, pulled from columns AND `metadata_json`; COUGHVID's ≤4 expert
 *    opinions are collapsed by MAJORITY vote; any `field:true` boolean becomes qualifier `field`.
 *  - Collapses: pneumonia→lower; wheezing/asthma/cld→obstructive; cold/congestion/pseudocough→upper;
 *    *_infection / respiratory_* / diagnosis values map to upper/lower/obstructive; covid-19→covid;
 *    healthy_cough→healthy. stridor stays its own cloud. na/unknown dropped.
 */
object CloudMetaAnalyzer {

    val dspNames = listOf("ridge_curv", "ridge_slope", "ridge_cfreq", "duration_s",
        "ridge_energy", "ridge_bw", "q_ratio", "fmax_hz")
    val mfccNames = (0 until 13).map { "mfcc_$it" }
    val paroxNames = listOf("n_paroxysms", "coughs_per_parox_mean", "coughs_per_parox_max",
        "intra_parox_rate", "parox_dur_mean", "inter_parox_gap", "coughs_per_min", "cough_count")
    val featureNames: List<String> = dspNames + mfccNames + paroxNames

    /** Coughs within this gap (s) belong to the same paroxysm/bout. */
    private const val PAROX_GAP = 2.0

    data class Labels(val soundCloud: String, val isCough: Boolean, val qualifiers: Set<String>)

    private val COLLAPSE = mapOf(
        "pneumonia" to "lower",
        "wheezing" to "obstructive", "asthma" to "obstructive",
        "cld" to "obstructive", "chronic_lung_disease" to "obstructive",
        "cold" to "upper", "congestion" to "upper", "pseudocough" to "upper",
        "obstructive_disease" to "obstructive", "respiratory_obstructive" to "obstructive",
        "lower_infection" to "lower", "respiratory_lower" to "lower",
        "upper_infection" to "upper", "respiratory_upper" to "upper",
        "covid-19" to "covid", "covid_19" to "covid", "healthy_cough" to "healthy",
    )
    private val DROP = setOf("", "na", "n/a", "nan", "none", "null", "unknown", "unlabeled")
    /** Flags explicitly not cough/breathing-relevant — dropped (user). `smoker` is KEPT.
     *  `if`/`test` are spurious keys. */
    private val DROP_QUALIFIER = setOf(
        "mp", "diabetes", "ihd", "ht", "diarrhoea", "if", "test",
        "fever_muscle_pain", "fever", "ftg", "loss_of_smell", "others_preexist")
    /** metadata_json keys that are never qualifiers (ids, coords, numbers, free text). */
    private val NON_QUALIFIER_KEYS = setOf("a", "g", "age", "gender", "id", "uuid", "source", "file",
        "filename", "src_file", "participant", "record_folder", "record_date", "datetime", "date",
        "latitude", "longitude", "cough_detected", "covid_status", "sound_type", "label_assumed",
        "category", "esc10", "fold", "take", "target", "status", "status_ssl", "quality",
        "test_status", "testtype", "test_date", "ctscan", "ctscore", "ctdate", "vacc", "dt", "dv",
        "fv", "ep", "ru", "l_c", "l_l", "l_s", "um", "st")

    private fun collapse(q: String): String = COLLAPSE[q.lowercase()] ?: q.lowercase()

    /** Sound cloud + multi-label qualifier set for one metadata.csv row. */
    fun labelsFor(row: Map<String, String>): Labels {
        val source = (row["source"] ?: "")
        val soundType = (row["sound_type"] ?: "").lowercase()
        val isCough = (row["is_cough"] ?: "").equals("true", true)
        val raw = LinkedHashSet<String>()

        if (isCough) {
            (row["health_status"] ?: "").lowercase().takeIf { it !in DROP }?.let { raw.add(it) }
            if (soundType == "cough-heavy") raw.add("heavy")
            if (soundType == "cough-shallow") raw.add("shallow")
            val json = row["metadata_json"]
            if (!json.isNullOrBlank()) runCatching {
                val o = JsonParser.parseString(json).asJsonObject
                // Plain boolean flags (Coswara style): field:true -> qualifier `field`.
                for ((k, v) in o.entrySet()) {
                    if (k.matches(Regex(".*_\\d$"))) continue            // skip COUGHVID per-expert slots
                    if (k.lowercase() in NON_QUALIFIER_KEYS) continue
                    if (isTrue(v.toString().trim('"'))) raw.add(k.lowercase())
                }
                // COUGHVID expert labels collapsed by majority vote.
                majorityString(o, "cough_type")?.let { if (it !in DROP) raw.add(it) }
                majorityString(o, "severity")?.let { if (it !in DROP) raw.add(it) }
                majorityString(o, "diagnosis")?.let { if (it !in DROP) raw.add(it) }
                for (f in listOf("dyspnea", "wheezing", "stridor", "choking", "congestion", "respiratory_condition"))
                    if (majorityBool(o, f)) raw.add(f)
            }
        }
        val quals = raw.map { collapse(it) }.filter { it !in DROP && it !in DROP_QUALIFIER }.toSet()
        val soundCloud = if (isCough) "COUGH" else soundType.ifBlank { "unknown_sound" }
        return Labels(soundCloud, isCough, quals)
    }

    private fun isTrue(s: String) = s.equals("true", true) || s == "1" || s.equals("y", true) || s.equals("yes", true)

    /** Most common non-blank string across `${base}_1.._4`; null on none, blank on a tie. */
    private fun majorityString(o: JsonObject, base: String): String? {
        val counts = HashMap<String, Int>()
        for (n in 1..4) o.get("${base}_$n")?.takeIf { it.isJsonPrimitive }?.asString
            ?.lowercase()?.trim()?.takeIf { it !in DROP }?.let { counts[it] = (counts[it] ?: 0) + 1 }
        if (counts.isEmpty()) return null
        val top = counts.entries.sortedByDescending { it.value }
        return if (top.size > 1 && top[0].value == top[1].value) null else top[0].key
    }

    /** True if the majority of present `${base}_1.._4` booleans are true. */
    private fun majorityBool(o: JsonObject, base: String): Boolean {
        var t = 0; var f = 0
        for (n in 1..4) o.get("${base}_$n")?.takeIf { it.isJsonPrimitive }?.asString?.let {
            when { it.equals("true", true) -> t++; it.equals("false", true) -> f++ }
        }
        return t > f
    }

    /** Aggregated cough-DSP means + paroxysm block for one analysed recording. */
    fun recordingVector(a: CoughAnalysis): DoubleArray {
        val sr = a.sampleRate
        val coughs = a.events.filter { it.speech.isLikelyCough }.sortedBy { it.segment.startSample }
        val dsp = DoubleArray(8); val mfcc = DoubleArray(13)
        if (coughs.isNotEmpty()) {
            for (e in coughs) {
                val fv = e.featureVector(); for (i in 0 until 8) dsp[i] += fv[i]
                e.mfcc?.mean?.let { m -> for (i in 0 until 13) if (i < m.size) mfcc[i] += m[i] }
            }
            for (i in 0 until 8) dsp[i] /= coughs.size
            for (i in 0 until 13) mfcc[i] /= coughs.size
        }
        return dsp + mfcc + paroxysmBlock(coughs, a.totalSamples.toDouble() / sr)
    }

    private fun paroxysmBlock(coughs: List<com.example.FFTT04M.desktop.cough.CoughEvent>, durSec: Double): DoubleArray {
        if (coughs.isEmpty()) return DoubleArray(paroxNames.size)
        val groups = ArrayList<MutableList<com.example.FFTT04M.desktop.cough.CoughEvent>>()
        for (e in coughs) {
            val g = groups.lastOrNull()
            if (g != null && e.segment.startSec - g.last().segment.endSec <= PAROX_GAP) g.add(e)
            else groups.add(mutableListOf(e))
        }
        val sizes = groups.map { it.size }
        val durs = groups.map { it.last().segment.endSec - it.first().segment.startSec }
        val multi = groups.filter { it.size > 1 }
        val intraRate = if (multi.isNotEmpty()) multi.map { g ->
            val span = g.last().segment.endSec - g.first().segment.startSec
            if (span > 0) g.size / span else 0.0
        }.average() else 0.0
        val interGaps = (1 until groups.size).map {
            groups[it].first().segment.startSec - groups[it - 1].last().segment.endSec
        }
        return doubleArrayOf(
            groups.size.toDouble(),
            sizes.average(),
            sizes.max().toDouble(),
            intraRate,
            durs.average(),
            if (interGaps.isEmpty()) 0.0 else interGaps.average(),
            if (durSec > 0) coughs.size / (durSec / 60.0) else 0.0,
            coughs.size.toDouble(),
        )
    }

    // ---- metadata.csv reader (RFC4180: quoted fields, "" escapes) -------------------------------

    fun readMetadata(csv: File): List<Map<String, String>> {
        val text = csv.readText()
        val records = splitCsvRecords(text)
        if (records.isEmpty()) return emptyList()
        val header = records[0]
        return records.drop(1).filter { it.size >= header.size }.map { fields ->
            header.indices.associate { header[it] to (fields.getOrNull(it) ?: "") }
        }
    }

    private fun splitCsvRecords(text: String): List<List<String>> {
        val out = ArrayList<List<String>>()
        val field = StringBuilder()
        var row = ArrayList<String>()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> { row.add(field.toString()); field.setLength(0) }
                c == '\n' -> { row.add(field.toString()); field.setLength(0); out.add(row); row = ArrayList() }
                c == '\r' -> {}
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); out.add(row) }
        return out
    }
}
