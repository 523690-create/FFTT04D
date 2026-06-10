package com.example.FFTT04M.desktop

import java.io.File

data class AudioRecording(
    val id: String,
    val audioFile: File,
    val sampleRate: Int = 44100,
    val metadata: Map<String, Any> = emptyMap(),
) {
    fun label(): String? = metadata["label"] as? String
    fun coughDetectionScore(): Double? = metadata["cough_detected"] as? Double
}

object DatasetLoader {
    fun loadCoughDataset1(rootPath: String): List<AudioRecording> {
        val dir = File(rootPath).resolve("public_dataset")
        if (!dir.isDirectory) return emptyList()
        val recordings = mutableListOf<AudioRecording>()
        val audioFiles = dir.listFiles { f -> f.extension in listOf("webm", "ogg", "wav") } ?: return emptyList()
        for (audioFile in audioFiles) {
            val baseName = audioFile.nameWithoutExtension
            val jsonFile = dir.resolve("$baseName.json")
            recordings.add(AudioRecording(id = baseName, audioFile = audioFile))
        }
        return recordings.sortedBy { it.id }
    }

    fun loadESC50(rootPath: String): List<AudioRecording> {
        val audioDir = File(rootPath).resolve("audio")
        if (!audioDir.isDirectory) return emptyList()
        val recordings = mutableListOf<AudioRecording>()
        val audioFiles = audioDir.listFiles { f -> f.extension == "wav" } ?: return emptyList()
        for (audioFile in audioFiles) {
            recordings.add(AudioRecording(id = audioFile.nameWithoutExtension, audioFile = audioFile))
        }
        return recordings.sortedBy { it.id }
    }

    fun loadCoswara(rootPath: String): List<AudioRecording> {
        val recordings = mutableListOf<AudioRecording>()
        val rootDir = File(rootPath)

        // For each date directory, read the CSV to get participant info
        for (dateDir in rootDir.listFiles { f -> f.isDirectory && f.name.matches(Regex("\\d{8}")) } ?: emptyArray()) {
            val csvFile = dateDir.resolve("${dateDir.name}.csv")
            if (!csvFile.exists()) continue

            // Parse CSV: id, l_c, a, record_date, covid_status, ...
            val lines = csvFile.readLines()
            if (lines.size < 2) continue

            val csv = lines.drop(1)
            for (line in csv) {
                val parts = line.split(",")
                if (parts.isNotEmpty()) {
                    val participantId = parts[0].trim()
                    val age = if (parts.size > 2) parts[2].trim() else "?"
                    val covidStatus = if (parts.size > 4) parts[4].trim() else "unknown"
                    val country = if (parts.size > 1) parts[1].trim() else "?"
                    val location = if (parts.size > 9) parts[9].trim() else ""

                    // Create recording entry with metadata (audio files in tar.gz archives)
                    val id = "$participantId-${dateDir.name}"
                    val metadata = mapOf(
                        "age" to age,
                        "covid_status" to covidStatus,
                        "country" to country,
                        "location" to location,
                        "source" to "Coswara",
                        "note" to "Audio in ${dateDir.name}.tar.gz (split .aa/.ab/.ac/.ad - extraction pending)"
                    )

                    // Dummy file path (audio archived in tar.gz, not accessible without extraction)
                    val dummyFile = File("${rootPath}/${participantId}-${dateDir.name}.wav")
                    recordings.add(AudioRecording(id = id, audioFile = dummyFile, metadata = metadata))
                }
            }
        }

        return recordings.take(100).sortedBy { it.id }  // Limit to first 100 for performance
    }
}
