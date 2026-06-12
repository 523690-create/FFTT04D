package com.example.FFTT04M.desktop

import com.google.gson.JsonParser
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.SequenceInputStream

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
    /** Max recordings a single Load returns, to keep the UI list responsive. */
    private const val MAX_RECORDINGS = 5000

    fun loadCoughDataset1(rootPath: String): List<AudioRecording> {
        val dir = File(rootPath).resolve("public_dataset")
        if (!dir.isDirectory) return emptyList()
        val recordings = mutableListOf<AudioRecording>()
        val audioFiles = dir.listFiles { f -> f.extension in listOf("webm", "ogg", "wav") } ?: return emptyList()
        for (audioFile in audioFiles.sortedBy { it.name }) {
            val baseName = audioFile.nameWithoutExtension
            // Sidecar metadata: <uuid>.json next to the audio file.
            val meta = flatJson(dir.resolve("$baseName.json")).toMutableMap()
            meta["source"] = "CoughDataset1"
            recordings.add(AudioRecording(id = baseName, audioFile = audioFile, metadata = meta))
            if (recordings.size >= MAX_RECORDINGS) break
        }
        return recordings
    }

    fun loadESC50(rootPath: String): List<AudioRecording> {
        val audioDir = File(rootPath).resolve("audio")
        if (!audioDir.isDirectory) return emptyList()

        // Map filename -> {category, fold, target, esc10} from meta/esc50.csv.
        val csvMeta = HashMap<String, Map<String, String>>()
        val csv = File(rootPath).resolve("meta/esc50.csv")
        if (csv.isFile) {
            val lines = csv.readLines()
            // header: filename,fold,target,category,esc10,src_file,take
            for (line in lines.drop(1)) {
                val p = line.split(",")
                if (p.size >= 5) csvMeta[p[0].trim()] = mapOf(
                    "label" to p[3].trim(), "category" to p[3].trim(),
                    "fold" to p[1].trim(), "target" to p[2].trim(), "esc10" to p[4].trim()
                )
            }
        }

        val recordings = mutableListOf<AudioRecording>()
        val audioFiles = audioDir.listFiles { f -> f.extension == "wav" } ?: return emptyList()
        for (audioFile in audioFiles.sortedBy { it.name }) {
            val meta = (csvMeta[audioFile.name] ?: emptyMap()).toMutableMap()
            meta["source"] = "ESC-50"
            recordings.add(AudioRecording(id = audioFile.nameWithoutExtension, audioFile = audioFile, metadata = meta))
        }
        return recordings
    }

    /** Load recordings pulled off a device by [UsbImporter]: each <base>.wav + <base>.json sidecar. */
    fun loadDeviceImport(dir: File, deviceLabel: String): List<AudioRecording> =
        loadFolder(dir, "USB:$deviceLabel")

    /**
     * Generic recursive WAV-folder loader — used for ALLDATA output and USB import locations alike.
     * Picks up an optional `<base>.json` metadata sidecar and `<base>.txt` comment beside each WAV
     * (ALLDATA has neither; its WAV name carries the metadata and its rows live in metadata.csv).
     */
    fun loadFolder(dir: File, source: String): List<AudioRecording> {
        if (!dir.isDirectory) return emptyList()
        val recordings = mutableListOf<AudioRecording>()
        dir.walkTopDown().forEach { f ->
            if (f.isFile && f.extension.equals("wav", true)) {
                val meta = flatJson(File(f.parentFile, "${f.nameWithoutExtension}.json")).toMutableMap()
                meta["source"] = source
                File(f.parentFile, "${f.nameWithoutExtension}.txt").takeIf { it.isFile }
                    ?.let { meta["comment"] = it.readText().trim().take(200) }
                recordings.add(AudioRecording(id = f.nameWithoutExtension, audioFile = f, metadata = meta))
            }
        }
        return recordings.sortedBy { it.id }
    }

    /** Read a flat JSON object's top-level primitive members into a String map (via gson). */
    private fun flatJson(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return try {
            val obj = JsonParser.parseString(file.readText()).asJsonObject
            val out = LinkedHashMap<String, String>()
            for ((k, v) in obj.entrySet()) {
                if (v.isJsonPrimitive) out[k] = v.asString
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun loadCoswara(rootPath: String): List<AudioRecording> {
        val recordings = mutableListOf<AudioRecording>()
        val rootDir = File(rootPath)

        // For each date directory, extract tar.gz and load audio files
        for (dateDir in rootDir.listFiles { f -> f.isDirectory && f.name.matches(Regex("\\d{8}")) } ?: emptyArray()) {
            val csvFile = dateDir.resolve("${dateDir.name}.csv")
            if (!csvFile.exists()) continue

            // Parse CSV to get participant metadata
            val lines = csvFile.readLines()
            if (lines.size < 2) continue

            val csvData = mutableMapOf<String, Map<String, String>>()
            for (line in lines.drop(1)) {
                val parts = line.split(",")
                if (parts.isNotEmpty()) {
                    val participantId = parts[0].trim()
                    csvData[participantId] = mapOf(
                        "age" to (if (parts.size > 2) parts[2].trim() else "?"),
                        "covid_status" to (if (parts.size > 4) parts[4].trim() else "unknown"),
                        "country" to (if (parts.size > 1) parts[1].trim() else "?"),
                        "location" to (if (parts.size > 9) parts[9].trim() else "")
                    )
                }
            }

            val extractDir = dateDir.resolve("${dateDir.name}-extracted")
            val alreadyExtracted = extractDir.resolve(dateDir.name).let { it.isDirectory && (it.list()?.isNotEmpty() == true) }

            try {
                // Skip extraction when the archive was already unpacked (e.g. by unpack-coswara.sh).
                if (!alreadyExtracted) {
                    val tarGzParts = (listOf("aa", "ab", "ac", "ad"))
                        .map { dateDir.resolve("${dateDir.name}.tar.gz.$it") }
                        .filter { it.exists() }
                    if (tarGzParts.isEmpty()) continue
                    extractDir.mkdirs()
                    extractTarGz(tarGzParts, extractDir)
                }

                findAudioFiles(extractDir, dateDir.name, csvData).forEach { recordings.add(it) }
                if (recordings.size >= MAX_RECORDINGS) return recordings.take(MAX_RECORDINGS).sortedBy { it.id }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return recordings.sortedBy { it.id }
    }

    private fun extractTarGz(splitParts: List<File>, outputDir: File) {
        // Concatenate split tar.gz parts and extract
        val inputStreams = splitParts.map { FileInputStream(it) }
        val enumeration = object : java.util.Enumeration<java.io.InputStream> {
            private var index = 0
            override fun hasMoreElements() = index < inputStreams.size
            override fun nextElement() = inputStreams[index++]
        }
        val combinedStream = SequenceInputStream(enumeration)
        val gzipInput = GzipCompressorInputStream(combinedStream)
        val tarInput = TarArchiveInputStream(gzipInput)

        var entry = tarInput.nextTarEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                val outputFile = File(outputDir, entry.name)
                outputFile.parentFile?.mkdirs()
                outputFile.outputStream().use { output ->
                    tarInput.copyTo(output)
                }
            }
            entry = tarInput.nextTarEntry
        }
        tarInput.close()
        gzipInput.close()
        combinedStream.close()
    }

    private fun findAudioFiles(dir: File, dateStr: String, csvData: Map<String, Map<String, String>>): List<AudioRecording> {
        val results = mutableListOf<AudioRecording>()
        val audioExtensions = setOf("wav", "mp3", "ogg")
        // Cache one metadata.json read per participant directory.
        val metaCache = HashMap<String, Map<String, String>>()

        dir.walkTopDown().forEach { file ->
            if (file.isFile && file.extension.lowercase() in audioExtensions) {
                val participantDir = file.parentFile
                val participantId = participantDir?.name ?: file.nameWithoutExtension

                val metadata = LinkedHashMap<String, String>()
                metadata["source"] = "Coswara"
                metadata["recording_date"] = dateStr
                // Sound type encoded in the filename (cough-heavy, breathing-deep, vowel-a, ...).
                metadata["sound_type"] = file.nameWithoutExtension
                // CSV row (age/covid/country/location).
                csvData[participantId]?.let { metadata.putAll(it) }
                // Richer per-participant metadata.json (l_c, a, g, l_s, l_l, dT, ...), if present.
                val pm = metaCache.getOrPut(participantId) {
                    participantDir?.resolve("metadata.json")?.let { flatJson(it) } ?: emptyMap()
                }
                for ((k, v) in pm) metadata.putIfAbsent(k, v)

                results.add(AudioRecording(
                    id = "$participantId-$dateStr-${file.nameWithoutExtension}",
                    audioFile = file,
                    metadata = metadata
                ))
            }
        }
        return results
    }
}
