package com.example.FFTT04M.desktop

/**
 * Canonical cough / not-cough ground truth for evaluation, implementing the user's 5 hard conditions
 * (2026-07-10). Cough SUBTYPES are ignored (condition 1) — this is strictly binary.
 *
 *  2. Device recordings: the manual label is a HARD label (cough→POS; snore/voice/noise→NEG).
 *  3. Coswara / COUGHVID: if a cough is positively mentioned in title/metadata, a cough IS present
 *     somewhere (a POSITIVE BAG — other sounds may coexist). → POS, [bag]=true.
 *  4. If non-cough is mentioned (counting, vowels/syllables, breathing) OR the clip comes from a
 *     non-cough database (UrbanSound8K, train/radio, ESC-50 non-cough classes): any cough signal is a
 *     FALSE POSITIVE. → NEG (a hard negative).
 *
 * Conditions 3 & 4 are already encoded by the consolidated ALLDATA `is_cough` column (true = cough
 * mentioned = positive bag; false = non-cough = hard negative), so [fromIsCough] is the public-DB path
 * and [fromManual] is the device path.
 *
 * Evaluation consequence (see CoughEvalCli): POS (bag or hard) only ever informs RECALL — never
 * precision — because a positive bag may legitimately also contain non-cough sound. NEG informs the
 * FALSE-POSITIVE rate, which is the trustworthy specificity metric ("never call speech/breath a cough").
 */
object CoughTruth {

    enum class Truth { POS, NEG, SKIP }

    // Condition 1: subtype-agnostic — any cough word (regardless of subtype) is just "cough".
    private val COUGH_WORDS = listOf("cough", "bronchit", "hacking", "wheez", "expector", "productive", "phlegm")
    private val NOTCOUGH_WORDS = listOf(
        "speech", "voice", "talk", "sing", "music", "vowel", "counting",
        "snore", "snoring", "noise", "breath", "sniff", "throat clear", "silence")

    /** Device recordings (condition 2): a manual comment is a HARD label. Cough word ⇒ POS (a cough is
     *  present); an explicit non-cough word with no cough word ⇒ NEG; otherwise unusable ⇒ SKIP. */
    fun fromManual(comment: String?): Truth {
        val c = comment?.lowercase()?.trim().orEmpty()
        if (c.isEmpty()) return Truth.SKIP
        if (COUGH_WORDS.any { it in c }) return Truth.POS
        if (NOTCOUGH_WORDS.any { it in c }) return Truth.NEG
        return Truth.SKIP
    }

    /** Public DBs (conditions 3 & 4) via the ALLDATA `is_cough` flag. */
    fun fromIsCough(isCough: Boolean?): Truth = when (isCough) {
        true -> Truth.POS      // cough mentioned → cough present (bag)
        false -> Truth.NEG     // non-cough recording type / non-cough database → hard negative
        null -> Truth.SKIP
    }

    fun parseIsCough(s: String?): Boolean? = when (s?.trim()?.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> null
    }
}
