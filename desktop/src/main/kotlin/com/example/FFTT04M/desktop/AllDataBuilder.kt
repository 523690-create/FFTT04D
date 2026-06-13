package com.example.FFTT04M.desktop

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.SequenceInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Consolidates the several cough/sound datasets that live side-by-side under one root
 * (Coswara, CoughDataset-main, COUGHVID, dataset_1sec, ESC-50) into a single ALLDATA folder:
 *
 *  - every clip is transcoded to 44.1 kHz mono 16-bit PCM **WAV** (via ffmpeg),
 *  - all available metadata is merged into one **metadata.csv**, one row per output WAV,
 *  - a uniform set of analysis columns (`is_cough`, `health_status`, `sound_type`, …) is
 *    derived per source, while the full original metadata is preserved losslessly in
 *    `metadata_json`.
 *
 * Negatives for cough/non-cough discrimination come from Coswara's breathing/vowel/counting
 * clips and from ESC-50's non-`coughing` categories (`is_cough=false`).
 *
 * The run is **parallel** (one ffmpeg per core), **resumable** (an existing non-empty output
 * WAV is reused, never re-converted), and **cancellable** ([cancel]).
 */
object AllDataBuilder {

    /** Column order of the master metadata.csv. */
    val CSV_COLUMNS = listOf(
        "wav", "source", "original_id", "sound_type", "is_cough",
        "health_status", "age", "gender", "country", "cough_detected", "metadata_json"
    )

    data class Row(
        val wav: String, val source: String, val originalId: String, val soundType: String,
        val isCough: String, val healthStatus: String, val age: String, val gender: String,
        val country: String, val coughDetected: String, val metadataJson: String
    ) {
        fun toCsv(): String = listOf(
            wav, source, originalId, soundType, isCough, healthStatus,
            age, gender, country, coughDetected, metadataJson
        ).joinToString(",") { Csv.quote(it) }
    }

    /** A unit of conversion work: transcode [input] -> [output], then this clip's [row] is recorded. */
    private class Job(val input: File, val output: File, val deleteInput: Boolean, val row: Row)

    data class Progress(val phase: String, val done: Int, val total: Int, val message: String)
    data class Summary(
        val rows: Int, val converted: Int, val reused: Int, val failed: Int,
        val images: Int, val csvPath: String, val bySource: Map<String, Int>, val cancelled: Boolean
    )

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    /** Also render the 512×512 FFT (PNG) + Morlet-CWT (JPEG) image per clip. */
    @Volatile var generateImages = true

    private val gson = Gson()
    private val workers = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    // Collision-free output names + collected rows are shared across worker threads.
    private val usedNames = HashSet<String>()
    private val rows = ArrayList<Row>()
    private val converted = AtomicInteger()
    private val reused = AtomicInteger()
    private val failed = AtomicInteger()
    private val images = AtomicInteger()

    /**
     * Build ALLDATA from datasets found under [sourcesRoot] into [outDir].
     * [onProgress] is called from background threads (marshal to the EDT in the UI).
     */
    fun build(sourcesRoot: File, outDir: File, onProgress: (Progress) -> Unit): Summary {
        cancelled = false
        usedNames.clear(); rows.clear()
        converted.set(0); reused.set(0); failed.set(0); images.set(0)
        outDir.mkdirs()

        if (!AudioDecoder.ffmpegAvailable()) {
            onProgress(Progress("error", 0, 0, "ffmpeg not found — cannot convert audio. Install Gyan.FFmpeg."))
            return Summary(0, 0, 0, 0, 0, "", emptyMap(), false)
        }

        val bySource = LinkedHashMap<String, Int>()
        val pool = Executors.newFixedThreadPool(workers)
        try {
            // --- Plain-file sources: enumerate jobs up front, convert in parallel ---------------
            for (src in listOf(
                ::collectCoughDataset, ::collectCoughvid, ::collectDataset1sec, ::collectEsc50
            )) {
                if (cancelled) break
                val before = rows.size
                val jobs = src(sourcesRoot, outDir)
                if (jobs.isEmpty()) continue
                val label = jobs.first().row.source
                runJobs(jobs, pool, label, onProgress)
                bySource[label] = rows.size - before
            }

            // --- Coswara: streamed from split tars, one date at a time (bounds temp disk) -------
            if (!cancelled) {
                val before = rows.size
                buildCoswara(sourcesRoot, outDir, pool, onProgress)
                if (rows.size > before) bySource["Coswara"] = rows.size - before
            }
        } finally {
            pool.shutdown()
        }

        // Write the master CSV (sorted by source then name for stable diffs).
        val csv = File(outDir, "metadata.csv")
        onProgress(Progress("csv", rows.size, rows.size, "Writing ${rows.size} rows -> metadata.csv"))
        csv.bufferedWriter().use { w ->
            w.write(CSV_COLUMNS.joinToString(","))
            w.newLine()
            for (r in rows.sortedWith(compareBy({ it.source }, { it.wav }))) { w.write(r.toCsv()); w.newLine() }
        }

        return Summary(
            rows.size, converted.get(), reused.get(), failed.get(), images.get(),
            csv.absolutePath, bySource, cancelled
        )
    }

    /** Convert all [jobs] in parallel, recording each successful clip's row. */
    private fun runJobs(jobs: List<Job>, pool: java.util.concurrent.ExecutorService,
                        phase: String, onProgress: (Progress) -> Unit) {
        val total = jobs.size
        val done = AtomicInteger()
        val futures = jobs.map { job ->
            pool.submit {
                if (!cancelled) convertOne(job)
                val d = done.incrementAndGet()
                if (d % 25 == 0 || d == total) {
                    val img = if (generateImages) ", imaged ${images.get()}" else ""
                    onProgress(Progress(phase, d, total,
                        "$phase: $d/$total  (converted ${converted.get()}, reused ${reused.get()}, failed ${failed.get()}$img)"))
                }
            }
        }
        for (f in futures) try { f.get() } catch (_: Exception) {}
    }

    /** Transcode one job (skip if a good output already exists) and record its row on success. */
    private fun convertOne(job: Job) {
        try {
            val ok = if (job.output.isFile && job.output.length() > 44L) {
                reused.incrementAndGet(); true
            } else if (AudioDecoder.convertToWav(job.input, job.output)) {
                converted.incrementAndGet(); true
            } else {
                failed.incrementAndGet(); false
            }
            if (ok) {
                synchronized(rows) { rows.add(job.row) }
                if (generateImages) renderImages(job.output)
            }
        } finally {
            if (job.deleteInput) job.input.delete()
        }
    }

    /** Render the FFT PNG + CWT JPEG beside [wav] (shared base name); skip if both already exist. */
    private fun renderImages(wav: File) {
        val base = wav.nameWithoutExtension
        val png = File(wav.parentFile, "$base.png")
        val jpg = File(wav.parentFile, "$base.jpg")
        if (png.isFile && jpg.isFile) return
        val pcm = AudioDecoder.decode(wav) ?: return   // output is canonical 44.1 kHz mono WAV
        if (pcm.isEmpty()) return
        if (!png.isFile) try { SpectrogramRenderer.renderFftPng(pcm, 44100, png); images.incrementAndGet() }
            catch (e: Exception) { System.err.println("FFT image failed ${wav.name}: ${e.message}") }
        if (!jpg.isFile) try { SpectrogramRenderer.renderCwtJpg(pcm, 44100, jpg); images.incrementAndGet() }
            catch (e: Exception) { System.err.println("CWT image failed ${wav.name}: ${e.message}") }
    }

    // ---- output-name allocation ----------------------------------------------------------------

    private val sanitize = Regex("[^A-Za-z0-9._-]")
    /** Longest output base name we allow, so dir + name stays well under Windows MAX_PATH (260). */
    private const val MAX_BASE = 150

    /**
     * Build a Job whose output WAV name encodes the clip's key metadata, and set [row].wav to that
     * exact name so the CSV row stays linked to the file on disk. The name is
     * `<prefix>__<id>__<soundType>__<cough|noncough>__<health>__a<age>__<gender>__<country>.wav`,
     * blanks skipped, sanitized, length-capped, and de-duplicated.
     */
    private fun jobFor(outDir: File, prefix: String, id: String, input: File,
                       deleteInput: Boolean, row: Row): Job {
        val out = outFile(outDir, listOf(prefix, id) + metaTags(row))
        return Job(input, out, deleteInput, row.copy(wav = out.name))
    }

    /** Compact, human-readable metadata summary appended to the filename (full data is in the CSV). */
    private fun metaTags(row: Row): List<String> = buildList {
        if (row.soundType.isNotBlank()) add(row.soundType)
        add(if (row.isCough == "true") "cough" else "noncough")
        if (row.healthStatus.isNotBlank() && row.healthStatus != "unknown") add(row.healthStatus)
        if (row.age.isNotBlank()) add("a${row.age}")
        if (row.gender.isNotBlank()) add(row.gender)
        if (row.country.isNotBlank()) add(row.country)
    }

    /** Allocate a collision-free `<part>__<part>__….wav` from sanitized, non-blank [parts]. */
    private fun outFile(outDir: File, parts: List<String>): File {
        var base = parts.filter { it.isNotBlank() }
            .joinToString("__") { sanitize.replace(it, "_") }
        if (base.length > MAX_BASE) base = base.take(MAX_BASE).trimEnd('_')
        synchronized(usedNames) {
            var name = "$base.wav"
            var i = 2
            while (!usedNames.add(name)) { name = "${base}_$i.wav"; i++ }
            return File(outDir, name)
        }
    }

    // ---- per-dataset collectors ----------------------------------------------------------------

    /** CoughDataset-main: a `covid/` folder of cough clips, all presumed COVID. */
    private fun collectCoughDataset(root: File, outDir: File): List<Job> {
        val dir = findChild(root, "CoughDataset-main", "CoughDataset") ?: return emptyList()
        val audio = dir.resolve("covid").takeIf { it.isDirectory } ?: dir
        return audioFiles(audio).map { f ->
            val id = f.nameWithoutExtension
            val meta = mapOf("source" to "CoughDataset", "label_assumed" to "covid", "file" to f.name)
            jobFor(outDir, "coughdataset", id, f, false, Row(
                wav = "", source = "CoughDataset", originalId = id, soundType = "cough",
                isCough = "true", healthStatus = "covid", age = "", gender = "", country = "",
                coughDetected = "", metadataJson = gson.toJson(meta)
            ))
        }
    }

    /** COUGHVID: flat webm/ogg/wav + per-file json; rich metadata_compiled.csv keyed by uuid. */
    private fun collectCoughvid(root: File, outDir: File): List<Job> {
        val dir = findChild(root, "coughvid_20211012", "coughvid", "public_dataset_v3", "public_dataset") ?: return emptyList()
        val compiled = Csv.readKeyed(dir.resolve("metadata_compiled.csv"), keyCol = "uuid")
        return audioFiles(dir).map { f ->
            val uuid = f.nameWithoutExtension
            // Merge per-file json (datetime/cough_detected/lat/long) with the compiled csv row.
            val meta = LinkedHashMap<String, String>()
            meta["source"] = "COUGHVID"
            Json.flat(dir.resolve("$uuid.json")).forEach { (k, v) -> meta[k] = v }
            compiled[uuid]?.forEach { (k, v) -> if (v.isNotBlank()) meta[k] = v }
            jobFor(outDir, "coughvid", uuid, f, false, Row(
                wav = "", source = "COUGHVID", originalId = uuid, soundType = "cough",
                isCough = "true", healthStatus = canonStatus(meta["status"]),
                age = meta["age"] ?: "", gender = meta["gender"] ?: "", country = "",
                coughDetected = meta["cough_detected"] ?: "", metadataJson = gson.toJson(meta)
            ))
        }
    }

    /** dataset_1sec: covid/healthy/lower/obstructive/upper subfolders; folder name is the label. */
    private fun collectDataset1sec(root: File, outDir: File): List<Job> {
        val dir = findChild(root, "dataset_1sec") ?: return emptyList()
        val jobs = ArrayList<Job>()
        for (sub in dir.listFiles { f -> f.isDirectory } ?: emptyArray()) {
            val folder = sub.name
            for (f in audioFiles(sub)) {
                val id = f.nameWithoutExtension
                val meta = mapOf("source" to "dataset_1sec", "folder" to folder, "file" to f.name)
                jobs.add(jobFor(outDir, "d1sec", id, f, false, Row(
                    wav = "", source = "dataset_1sec", originalId = id, soundType = folder,
                    isCough = "true", healthStatus = canonStatus(folder), age = "", gender = "",
                    country = "", coughDetected = "", metadataJson = gson.toJson(meta)
                )))
            }
        }
        return jobs
    }

    /** ESC-50: WAVs under audio\ plus meta\esc50.csv. Only `coughing` is a cough; rest are negatives. */
    private fun collectEsc50(root: File, outDir: File): List<Job> {
        val dir = findChild(root, "ESC-50-master", "ESC-50") ?: return emptyList()
        val audioDir = dir.resolve("audio").takeIf { it.isDirectory } ?: return emptyList()
        val meta = Csv.readKeyed(dir.resolve("meta/esc50.csv"), keyCol = "filename")
        return audioFiles(audioDir).map { f ->
            val row = meta[f.name] ?: emptyMap()
            val category = row["category"] ?: "unknown"
            val m = LinkedHashMap<String, String>(); m["source"] = "ESC-50"; m.putAll(row)
            jobFor(outDir, "esc50", f.nameWithoutExtension, f, false, Row(
                wav = "", source = "ESC-50", originalId = f.nameWithoutExtension, soundType = category,
                isCough = if (category == "coughing") "true" else "false",
                healthStatus = "na", age = "", gender = "", country = "",
                coughDetected = "", metadataJson = gson.toJson(m)
            ))
        }
    }

    // ---- Coswara (streamed split-tar extraction) -----------------------------------------------

    private fun buildCoswara(root: File, outDir: File, pool: java.util.concurrent.ExecutorService,
                             onProgress: (Progress) -> Unit) {
        val dir = findChild(root, "Coswara-Data-dataset-paper-publication", "Coswara-Data", "Coswara")
            ?: return
        // Master metadata keyed by participant id.
        val combined = Csv.readKeyed(dir.resolve("combined_data.csv"), keyCol = "id")
        val tmpRoot = File(outDir, "_tmp_coswara").apply { mkdirs() }

        val dateDirs = dir.listFiles { f -> f.isDirectory && f.name.matches(Regex("\\d{8}")) }
            ?.sortedBy { it.name } ?: emptyList()

        for ((di, dateDir) in dateDirs.withIndex()) {
            if (cancelled) break
            val parts = ('a'..'z').flatMap { c1 -> ('a'..'z').map { c2 -> "$c1$c2" } }
                .map { dateDir.resolve("${dateDir.name}.tar.gz.$it") }
                .filter { it.exists() }
            if (parts.isEmpty()) continue

            onProgress(Progress("Coswara", di, dateDirs.size,
                "Coswara ${dateDir.name} (${di + 1}/${dateDirs.size}) — extracting…"))

            // Stream the tar once: write each wav to a temp file, collect each metadata.json.
            val perParticipantJson = HashMap<String, Map<String, String>>()
            val staged = ArrayList<Triple<String, String, File>>() // participant, soundType, temp wav
            try {
                streamTar(parts) { name, bytes ->
                    val segs = name.split('/')
                    if (segs.size < 3) return@streamTar
                    val participant = segs[segs.size - 2]
                    val leaf = segs.last()
                    when {
                        leaf.equals("metadata.json", true) ->
                            perParticipantJson[participant] = Json.flatBytes(bytes)
                        leaf.endsWith(".wav", true) -> {
                            val tmp = File.createTempFile("cos_", ".wav", tmpRoot)
                            tmp.writeBytes(bytes)
                            staged.add(Triple(participant, leaf.removeSuffix(".wav").removeSuffix(".WAV"), tmp))
                        }
                    }
                }
            } catch (e: Exception) {
                onProgress(Progress("Coswara", di, dateDirs.size, "Coswara ${dateDir.name}: ${e.message}"))
            }

            // Build + convert this date's jobs in parallel.
            val jobs = staged.map { (participant, soundType, tmp) ->
                val meta = LinkedHashMap<String, String>()
                meta["source"] = "Coswara"; meta["record_folder"] = dateDir.name
                meta["participant"] = participant; meta["sound_type"] = soundType
                combined[participant]?.forEach { (k, v) -> if (v.isNotBlank()) meta[k] = v }
                perParticipantJson[participant]?.forEach { (k, v) -> if (v.isNotBlank()) meta[k] = v }
                jobFor(outDir, "coswara", participant, tmp, true, Row(
                    wav = "", source = "Coswara", originalId = participant, soundType = soundType,
                    isCough = if (soundType.startsWith("cough", true)) "true" else "false",
                    healthStatus = canonStatus(meta["covid_status"]),
                    age = meta["a"] ?: "", gender = meta["g"] ?: "", country = meta["l_c"] ?: "",
                    coughDetected = "", metadataJson = gson.toJson(meta)
                ))
            }
            runJobs(jobs, pool, "Coswara ${dateDir.name}", onProgress)
        }
        tmpRoot.deleteRecursively()
    }

    /** Concatenate split gzip parts and call [onEntry] with each file entry's name + bytes. */
    private fun streamTar(parts: List<File>, onEntry: (String, ByteArray) -> Unit) {
        val streams = parts.map { FileInputStream(it) }
        val seq = SequenceInputStream(java.util.Collections.enumeration(streams))
        TarArchiveInputStream(GzipCompressorInputStream(seq)).use { tar ->
            var entry = tar.nextTarEntry
            while (entry != null) {
                if (!entry.isDirectory) onEntry(entry.name, tar.readBytes())
                entry = tar.nextTarEntry
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private val AUDIO_EXT = setOf("wav", "webm", "ogg", "mp3", "flac", "m4a")
    private fun audioFiles(dir: File): List<File> =
        (dir.listFiles { f -> f.isFile && f.extension.lowercase() in AUDIO_EXT } ?: emptyArray())
            .sortedBy { it.name }

    /**
     * First child dir of [root] matching any [names] (exact, then case-insensitive prefix), then
     * **descends through unzip wrappers**: a folder whose real content sits one (or more) levels
     * deeper inside a subfolder that also matches a requested name — e.g. `X/X` from extracting an
     * archive "into a folder named after the zip", or `public_dataset_v3/coughvid_20211012`.
     */
    private fun findChild(root: File, vararg names: String): File? =
        matchChild(root, names)?.let { drillToContent(it) }

    private fun matchChild(root: File, names: Array<out String>): File? {
        val children = root.listFiles { f -> f.isDirectory } ?: return null
        for (n in names) children.firstOrNull { it.name.equals(n, true) }?.let { return it }
        for (n in names) children.firstOrNull { it.name.startsWith(n, true) }?.let { return it }
        return null
    }

    /**
     * Automatic drill-down: descend through single-subfolder wrappers (any depth or naming — `X/X`
     * from "extract into a folder named after the zip", or `public_dataset_v3/coughvid_20211012`)
     * until reaching the folder that actually holds the dataset — i.e. one that has audio/CSV files,
     * `YYYYMMDD` date dirs, an `audio/` subdir, or more than one subfolder. Content-based, so it works
     * however the archive was unzipped without hard-coding wrapper names.
     */
    private fun drillToContent(start: File): File {
        var d = start
        repeat(6) {
            val subs = d.listFiles { f -> f.isDirectory } ?: return d
            // Content root if it has a recognisable marker (date dirs / audio dir / dataset CSV) or
            // it isn't a single-subfolder wrapper. Marker checks are cheap (no listing huge flat dirs).
            val marker = subs.any { it.name.matches(Regex("\\d{8}")) || it.name.equals("audio", true) } ||
                d.resolve("metadata_compiled.csv").isFile || d.resolve("combined_data.csv").isFile ||
                d.resolve("meta").isDirectory
            if (marker || subs.size != 1) return d
            d = subs[0]
        }
        return d
    }

    /** Report which datasets the builder resolves under [sourcesRoot] (fast — no full file scan). */
    fun diagnose(sourcesRoot: File): String = buildString {
        appendLine("Datasets under ${sourcesRoot.path}:")
        fun line(label: String, dir: File?, detail: () -> String) =
            appendLine(if (dir == null) "  $label: NOT FOUND" else "  $label -> ${dir.path}  [${detail()}]")
        val cos = findChild(sourcesRoot, "Coswara-Data-dataset-paper-publication", "Coswara-Data", "Coswara")
        line("Coswara", cos) { "${cos!!.listFiles { f -> f.isDirectory && f.name.matches(Regex("\\d{8}")) }?.size ?: 0} date dirs, combined_data.csv=${cos.resolve("combined_data.csv").isFile}" }
        val cd = findChild(sourcesRoot, "CoughDataset-main", "CoughDataset")
        line("CoughDataset", cd) { "covid=${cd!!.resolve("covid").isDirectory || cd.name.equals("covid", true)}" }
        val cv = findChild(sourcesRoot, "coughvid_20211012", "coughvid", "public_dataset_v3", "public_dataset")
        line("COUGHVID", cv) { "metadata_compiled.csv=${cv!!.resolve("metadata_compiled.csv").isFile}" }
        val d1 = findChild(sourcesRoot, "dataset_1sec")
        line("dataset_1sec", d1) { "folders=${(d1!!.listFiles { f -> f.isDirectory } ?: emptyArray()).joinToString(",") { it.name }}" }
        val esc = findChild(sourcesRoot, "ESC-50-master", "ESC-50")
        line("ESC-50", esc) { "audio=${esc!!.resolve("audio").isDirectory}, esc50.csv=${esc.resolve("meta/esc50.csv").isFile}" }
    }

    /** Canonical health bucket; raw value is always retained in metadata_json. */
    private fun canonStatus(raw: String?): String {
        val s = raw?.trim()?.lowercase() ?: return "unknown"
        return when {
            s.isEmpty() -> "unknown"
            s.contains("covid") || s.startsWith("positive") -> "covid"
            s == "healthy" || s.contains("no_resp") || s.startsWith("negative") -> "healthy"
            s.contains("symptomatic") || s.contains("resp_illness") -> "symptomatic"
            s.contains("recovered") -> "recovered"
            s in setOf("lower", "upper", "obstructive") -> "respiratory_$s"
            else -> s
        }
    }

}

/** Minimal RFC-4180-ish CSV read/write (handles quotes, embedded commas, CRLF). */
private object Csv {
    fun quote(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** Parse [file] and index rows by the value in column [keyCol]. Returns col->val maps. */
    fun readKeyed(file: File, keyCol: String): Map<String, Map<String, String>> {
        if (!file.isFile) return emptyMap()
        val rows = parse(file.readText())
        if (rows.isEmpty()) return emptyMap()
        val header = rows.first()
        val keyIdx = header.indexOfFirst { it.trim() == keyCol }.let { if (it >= 0) it else 0 }
        val out = LinkedHashMap<String, Map<String, String>>()
        for (r in rows.drop(1)) {
            if (r.size <= keyIdx) continue
            val key = r[keyIdx].trim()
            if (key.isEmpty()) continue
            val m = LinkedHashMap<String, String>()
            for (i in header.indices) {
                val col = header[i].trim()
                if (col.isEmpty()) continue
                m[col] = r.getOrElse(i) { "" }.trim()
            }
            out[key] = m
        }
        return out
    }

    /** Parse CSV text into a list of records (each a list of fields). */
    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var field = StringBuilder()
        var record = ArrayList<String>()
        var inQuotes = false
        var i = 0
        fun endField() { record.add(field.toString()); field = StringBuilder() }
        fun endRecord() { endField(); rows.add(record); record = ArrayList() }
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> endField()
                c == '\r' -> { if (i + 1 < text.length && text[i + 1] == '\n') i++; endRecord() }
                c == '\n' -> endRecord()
                else -> field.append(c)
            }
            i++
        }
        // Trailing field/record (file not ending in newline).
        if (field.isNotEmpty() || record.isNotEmpty()) endRecord()
        return rows
    }
}

/** Flatten a JSON object's top-level primitive members into a String map. */
private object Json {
    fun flat(file: File): Map<String, String> =
        if (file.isFile) flatText(file.readText()) else emptyMap()

    fun flatBytes(bytes: ByteArray): Map<String, String> = flatText(String(bytes, Charsets.UTF_8))

    private fun flatText(text: String): Map<String, String> = try {
        val obj = JsonParser.parseString(text).asJsonObject
        val out = LinkedHashMap<String, String>()
        for ((k, v) in obj.entrySet()) if (v.isJsonPrimitive) out[k] = v.asString
        out
    } catch (e: Exception) { emptyMap() }
}
