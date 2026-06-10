package com.example.FFTT04M.desktop

import java.io.File

/**
 * Represents a single audio recording to analyze (abstracted over dataset sources).
 */
data class AudioRecording(
    val id: String,
    val audioFile: File,
    val sampleRate: Int = 44100,
    val metadata: Map<String, Any> = emptyMap(),
) {
    /** Human-readable label, if available. */
    fun label(): String? = metadata["label"] as? String
    fun coughDetectionScore(): Double? = metadata["cough_detected"] as? Double
}

/**
 * Loads recordings from various dataset formats.
 */
object DatasetLoader {

    /**
     * Load Cough Dataset 1 from public_dataset/ directory.
     * Expected structure: UUID.webm + UUID.json pairs.
     */
    fun loadCoughDataset1(rootPath: String): List<AudioRecording> {
        val dir = File(rootPath).resolve("public_dataset")
        if (!dir.isDirectory) return emptyList()

        val recordings = mutableListOf<AudioRecording>()
        val audioFiles = dir.listFiles { f -> f.extension in listOf("webm", "ogg", "wav") } ?: return emptyList()

        for (audioFile in audioFiles) {
            val baseName = audioFile.nameWithoutExtension
            val jsonFile = dir.resolve("$baseName.json")

            val metadata = if (jsonFile.exists()) {
                try {
                    val json = jsonFile.readText()
                    val map = mutableMapOf<String, Any>()
                    val content = json.trim().removeSurrounding("{", "}")
                    for (line in content.split(",")) {
                        val kv = line.trim().split(":")
                        if (kv.size == 2) {
                            val key = kv[0].trim().trim('"')
                            val value = kv[1].trim().trim('"')
                            map[key] = when {
                                value == "true" || value == "false" -> value.toBoolean()
                                value.toDoubleOrNull() != null -> value.toDouble()
                                else -> value
                            }
                        }
                    }
                    map
                } catch (e: Exception) {
                    emptyMap()
                }
            } else {
                emptyMap()
            }

            recordings.add(
                AudioRecording(
                    id = baseName,
                    audioFile = audioFile,
                    metadata = metadata
                )
            )
        }

        return recordings.sortedBy { it.id }
    }

    /**
     * Load ESC-50 dataset.
     * Expected structure: audio/*.wav, meta/esc50.csv with class labels.
     */
    fun loadESC50(rootPath: String): List<AudioRecording> {
        val audioDir = File(rootPath).resolve("audio")
        val csvFile = File(rootPath).resolve("meta/esc50.csv")

        if (!audioDir.isDirectory || !csvFile.exists()) return emptyList()

        // Parse CSV: filename, fold, target, esc10, src_file, take, ...
        val csv = csvFile.readLines().drop(1) // skip header
        val fileToLabel = mutableMapOf<String, String>()
        val fileToTarget = mutableMapOf<String, Int>()

        for (line in csv) {
            val parts = line.split(",")
            if (parts.size >= 3) {
                val filename = parts[0]
                val target = parts[2].toIntOrNull() ?: -1
                fileToLabel[filename] = TARGET_LABEL[target] ?: "unknown"
                fileToTarget[filename] = target
            }
        }

        val recordings = mutableListOf<AudioRecording>()
        val audioFiles = audioDir.listFiles { f -> f.extension == "wav" } ?: return emptyList()

        for (audioFile in audioFiles) {
            val label = fileToLabel[audioFile.name] ?: "unknown"
            val target = fileToTarget[audioFile.name] ?: -1
            recordings.add(
                AudioRecording(
                    id = audioFile.nameWithoutExtension,
                    audioFile = audioFile,
                    metadata = mapOf(
                        "label" to label,
                        "target" to target,
                        "source" to "ESC-50"
                    )
                )
            )
        }

        return recordings.sortedBy { it.id }
    }

    /**
     * Load Coswara dataset (WIP — requires extracting tar.gz files).
     * Expected structure: YYYYMMDD/YYYYMMDD.csv + extracted audio files.
     */
    fun loadCoswara(rootPath: String): List<AudioRecording> {
        val recordings = mutableListOf<AudioRecording>()
        val rootDir = File(rootPath)

        // For each date directory
        for (dateDir in rootDir.listFiles { f -> f.isDirectory && f.name.matches(Regex("\\d{8}")) } ?: emptyArray()) {
            val csvFile = dateDir.resolve("${dateDir.name}.csv")
            if (!csvFile.exists()) continue

            // Parse CSV: id, l_c, a, record_date, covid_status, ...
            val csv = csvFile.readLines().drop(1)
            for (line in csv) {
                val parts = line.split(",")
                if (parts.isNotEmpty()) {
                    val participantId = parts[0]
                    val covidStatus = if (parts.size > 4) parts[4] else "unknown"
                    val location = if (parts.size > 9) parts[9] else "unknown"

                    // TODO: Look for audio files (cough.wav, speech.wav, etc.)
                }
            }
        }

        return recordings
    }

    private val TARGET_LABEL = mapOf(
        0 to "dog", 1 to "rooster", 2 to "pig", 3 to "cow", 4 to "frog", 5 to "cat",
        6 to "hen", 7 to "insects", 8 to "sheep", 9 to "crow", 10 to "rain",
        11 to "sea_waves", 12 to "crackling_fire", 13 to "crickets", 14 to "chirping_birds",
        15 to "water_drops", 16 to "wind", 17 to "pouring_water", 18 to "toilet_flush",
        19 to "thunderstorm", 20 to "typing", 21 to "laughing", 22 to "brushing_teeth",
        23 to "sneezing", 24 to "drinking", 25 to "door_wood_knock", 26 to "mouse_click",
        27 to "keyboard_typing", 28 to "door_wood_creaks", 29 to "can_opening",
        30 to "washing_machine", 31 to "vacuum_cleaner", 32 to "clock_alarm", 33 to "clock_tick",
        34 to "glass_breaking", 35 to "helicopter", 36 to "chainsaw", 37 to "siren",
        38 to "car_horn", 39 to "engine_starting", 40 to "train", 41 to "church_bells",
        42 to "airplane", 43 to "fireworks", 44 to "hand_saw", 45 to "car_passing",
        46 to "zip_clock", 47 to "microwave_oven", 48 to "bus", 49 to "semitone",
    )
}
