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
        return emptyList()
    }
}
