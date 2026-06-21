package com.example.FFTT04M.desktop

/**
 * Rule-based automatic labels derived purely from the clip id. The merged-dataset filename encodes a
 * clip's source and recording type as `source__id__rectype__…` (e.g.
 * `coswara__<uid>__counting-fast__…`, `urban8k__100032-3-0-0__dog_bark__…`, `train__<ep>__…`), so the
 * label can be inferred from the id alone — no dataset-dir lookups.
 *
 * These are treated as ground-truth labels **equal to human manual comments** for codebook training,
 * but rendered in a distinct colour in the grid (the ⚙ line) so they're visibly machine-derived.
 *
 *   - `train__…`                          → speech   (chopped-up old-time radio — all speech)
 *   - `urban8k__…`                        → noise    (UrbanSound8K — all environmental noise)
 *   - `coswara__…__vowel-*|counting-*__…` → speech   (spoken vowels / counting recordings)
 *
 * Manual comments always win over an auto-label (see PhonemeCodebookCli / RecordingsGrid).
 */
object AutoLabel {
    fun forId(id: String): String? {
        val f = id.split("__")
        val src = f.getOrNull(0)?.lowercase() ?: return null
        val rec = f.getOrNull(2)?.lowercase() ?: ""
        return when {
            // "voice" = the fused speech+music (vocal/tonal) class — the speech↔music boundary is too
            // fuzzy for melodic voices to be worth keeping, and both are simply "not cough".
            src == "urban8k" -> if (rec == "street_music" || rec == "siren" || rec == "car_horn") "voice" else "noise"
            src == "train" -> "voice"                              // old-time radio
            src == "coswara" && ("vowel" in rec || "counting" in rec) -> "voice"
            src == "esc50" -> when (rec) {
                "sneezing" -> "sneeze"                             // boosts the tiny SN class
                "snoring" -> "snoring"
                "crying_baby", "siren", "car_horn" -> "voice"
                else -> null                                      // coughing/breathing/laughing/noise left out
            }
            else -> null
        }
    }
}
