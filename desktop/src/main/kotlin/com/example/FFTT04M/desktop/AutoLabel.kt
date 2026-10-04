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
 *   - `coswara__…__breathing-*__…`        → breathing (deep/shallow breathing recordings)
 *   - `esc50__…__<impulsive>__…`          → noise    (knocks, barks, clicks — see IMPULSIVE)
 *
 * Manual comments always win over an auto-label (see PhonemeCodebookCli / RecordingsGrid).
 */
object AutoLabel {
    /** Short, percussive non-cough sounds — the categories the live codebook most often calls "cough"
     *  (2026-10-03 per-category table: gun_shot 42 %, door_wood_knock 45 %, can_opening 42 %, dog/rooster
     *  40 %, at λ=0.4). An onset burst followed by a decay looks like a cough's explosive phase.
     *  Shared by urban8k (`gun_shot`, `dog_bark`) and ESC-50; all of them are the `noise` class. */
    val IMPULSIVE = setOf(
        "gun_shot", "dog_bark",                                                       // urban8k
        "dog", "door_wood_knock", "can_opening", "rooster", "glass_breaking", "fireworks",
        "clock_tick", "mouse_click", "water_drops", "footsteps", "clapping", "drinking_sipping",
    )

    /** True for an urban8k/ESC-50 clip in an IMPULSIVE category (see PhonemeCodebookCli's impulse budget). */
    fun isImpulsive(id: String): Boolean {
        val f = id.split("__")
        val src = f.getOrNull(0)?.lowercase()
        return (src == "urban8k" || src == "esc50") && f.getOrNull(2)?.lowercase() in IMPULSIVE
    }

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
            // Coswara's breathing-deep / breathing-shallow recordings. The codebook conflates
            // breathing with snoring into one respiratory class (PhonemeCodebookCli.cleanLabel),
            // which is where this canonicalizes to — so these thousands of clips finally give that
            // class real breath exemplars instead of scattering into the cough classes.
            src == "coswara" && "breathing" in rec -> "breathing"
            src == "esc50" -> when (rec) {
                "sneezing" -> "sneeze"                             // boosts the tiny SN class
                "snoring" -> "snoring"
                "breathing" -> "breathing"                         // same respiratory class as snoring
                "crying_baby", "siren", "car_horn" -> "voice"
                in IMPULSIVE -> "noise"
                else -> null                                      // coughing/laughing/noise left out
            }
            else -> null
        }
    }
}
