package com.example.FFTT04M.desktop

/**
 * Continuous cough-vs-not score for a decoded phoneme word; call it cough when > 0 (raise the threshold
 * for specificity):  (cough windows − respiratory windows − λ·other not-cough windows) / all windows.
 *
 * Shared by [PhonemeCodebookCli] (writes it into every decode record) and the recordings grid (computes it
 * at read time, so decodes that predate the field — and Tier-B segment edits — are scored the same way).
 *
 * Why not the dominant letter: a real cough recording is mostly NOT cough windows (gaps, voiced tails
 * decoded as voice/sneeze), so "which letter is most common" throws away clips whose coughs are
 * outnumbered — it scores 45.8% recall on the participant-level coswara holdout. Respiratory windows are
 * the evidence AGAINST (weight 1); other not-cough windows are weak evidence against (λ), because they
 * occur inside genuine cough clips too. Measured at threshold 0, λ sweep in data/_indomain_check_20260930/
 * (λ=1 ≈ the dominant-letter behaviour, λ=0 leaks device noise/voice):
 *   λ=0.4   coswara holdout acc 81.4% / recall 78.2% / breath-reject 84.6%   (dominant letter 67.0 / 45.8 / 88.4)
 *           device manual   acc 87.6% / recall 83.1% / not-cough-reject 89.5% (dominant letter 88.1 / 69.6 / 96.0)
 * λ was chosen on those same two sets and the device clips trained the codebook — treat it as a sensible
 * default, not a validated constant.
 */
object CoughScore {
    const val RESPIRATORY_LABEL = "snoring"   // the merged breath/snore class (see PhonemeCodebookCli.cleanLabel)
    val NOT_COUGH_LABELS = setOf("noise", "voice", "speech", "snoring", "sneeze", "music", "breathing")
    /** -Dcough.lambda=X — weight of the OTHER not-cough windows (noise/voice/sneeze). */
    val LAMBDA: Double = System.getProperty("cough.lambda")?.toDoubleOrNull() ?: 0.4

    /** Letter sets for [score], from the codebook's letter → class label pairs. */
    class Letters(val resp: Set<String>, val otherNotCough: Set<String>) {
        companion object {
            fun of(letterLabels: Iterable<Pair<String, String>>): Letters {
                val resp = HashSet<String>(); val other = HashSet<String>()
                for ((letter, label) in letterLabels) when {
                    label == RESPIRATORY_LABEL -> resp += letter
                    label in NOT_COUGH_LABELS -> other += letter
                }
                return Letters(resp, other)
            }
        }
    }

    fun score(word: List<String>, letters: Letters, lambda: Double = LAMBDA): Double? {
        if (word.isEmpty()) return null
        var cough = 0; var resp = 0; var other = 0
        for (code in word) {
            if (code == "?") continue
            when (code.takeWhile { it.isLetter() }) {
                in letters.resp -> resp++; in letters.otherNotCough -> other++; else -> cough++
            }
        }
        return (cough - resp - lambda * other) / word.size
    }
}
