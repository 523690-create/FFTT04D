package com.example.FFTT04M.desktop

/**
 * Maps a recording's dataset metadata to a coarse acoustic GROUP (used to label discovered codebook
 * units) plus a finer source name. The device keeps RESPIRATORY tokens and rejects SPEECH / NOISE.
 *
 * Heuristic and deliberately extensible — refine the rules as more labelled databases are imported
 * (stridor / wheeze have no public corpus yet, so those units only appear once such data exists).
 */
object RespiratoryTaxonomy {

    enum class Group { RESPIRATORY, SPEECH, NOISE, UNKNOWN }

    data class Label(val group: Group, val fine: String)

    /** Classify by source + sound_type/category/label fields written by DatasetLoader. */
    fun classify(metadata: Map<String, Any>): Label {
        fun m(k: String) = (metadata[k] as? String)?.lowercase()?.trim()
        val source = m("source") ?: ""
        val soundType = m("sound_type") ?: ""           // Coswara: cough-heavy, breathing-deep, vowel-a, counting-normal …
        val category = m("category") ?: m("label") ?: "" // ESC-50 / generic class

        // Coswara sound-type prefixes are the most specific signal.
        when {
            soundType.startsWith("cough") -> return Label(Group.RESPIRATORY, "cough")
            soundType.startsWith("breathing") -> return Label(Group.RESPIRATORY, "breathing")
            soundType.startsWith("vowel") || soundType.startsWith("counting") ||
                soundType.startsWith("speech") -> return Label(Group.SPEECH, "speech")
        }

        // ESC-50 / labelled categories.
        when (category) {
            "coughing", "cough" -> return Label(Group.RESPIRATORY, "cough")
            "sneezing", "sneeze" -> return Label(Group.RESPIRATORY, "sneeze")
            "breathing" -> return Label(Group.RESPIRATORY, "breathing")
            "snoring" -> return Label(Group.RESPIRATORY, "snoring")
        }
        // Any other ESC-50/UrbanSound8K category is environmental noise.
        if (category.isNotEmpty()) return Label(Group.NOISE, category)

        return when {
            source.startsWith("coughdataset") -> Label(Group.RESPIRATORY, "cough")
            source.startsWith("usb") -> Label(Group.UNKNOWN, "field-capture")   // unlabelled user captures
            else -> Label(Group.UNKNOWN, "unknown")
        }
    }
}
