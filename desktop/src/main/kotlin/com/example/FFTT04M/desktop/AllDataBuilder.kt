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
        excludeDir = outDir.canonicalFile   // never re-ingest the output folder (guardrail vs ALLDATA→ALLDATA)

        if (!AudioDecoder.ffmpegAvailable()) {
            onProgress(Progress("error", 0, 0, "ffmpeg not found — cannot convert audio. Install Gyan.FFmpeg."))
            return Summary(0, 0, 0, 0, 0, "", emptyMap(), false)
        }

        val bySource = LinkedHashMap<String, Int>()
        val pool = Executors.newFixedThreadPool(workers)
        try {
            // --- Plain-file sources: enumerate jobs up front, convert in parallel ---------------
            for ((name, src) in listOf<Pair<String, (File, File) -> List<Job>>>(
                "CoughDataset" to ::collectCoughDataset, "COUGHVID" to ::collectCoughvid,
                "dataset_1sec" to ::collectDataset1sec, "ESC-50" to ::collectEsc50,
                "UrbanSound8K" to ::collectUrbanSound8K, "train" to ::collectTrain
            )) {
                if (cancelled) break
                onProgress(Progress("scan", 0, 0, "Scanning for $name under ${sourcesRoot.name}\\ … (enumerating files can be slow on external drives)"))
                val before = rows.size
                val jobs = src(sourcesRoot, outDir)
                if (jobs.isEmpty()) {
                    onProgress(Progress("warn", 0, 0, "$name: not found or no audio clips — skipping"))
                    continue
                }
                val label = jobs.first().row.source
                onProgress(Progress("found", 0, jobs.size, "$name: ${jobs.size} clips found — converting to WAV…"))
                runJobs(jobs, pool, label, onProgress)
                bySource[label] = rows.size - before
                onProgress(Progress("done", 0, 0, "$name done: +${rows.size - before} rows (running totals: converted ${converted.get()}, reused ${reused.get()}, failed ${failed.get()})"))
            }

            // --- Coswara: streamed from split tars, one date at a time (bounds temp disk) -------
            if (!cancelled) {
                onProgress(Progress("scan", 0, 0, "Scanning Coswara (streaming/extracting split tars — slow on external drives)…"))
                val before = rows.size
                buildCoswara(sourcesRoot, outDir, pool, onProgress)
                if (rows.size > before) {
                    bySource["Coswara"] = rows.size - before
                    onProgress(Progress("done", 0, 0, "Coswara done: +${rows.size - before} rows"))
                } else onProgress(Progress("warn", 0, 0, "Coswara: not found or no clips"))
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
        File(outDir, "_tmp_train").deleteRecursively()   // remove any leftover speech-segment chunks

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
        val warned = AtomicInteger()
        val futures = jobs.map { job ->
            pool.submit {
                if (!cancelled) {
                    if (!convertOne(job) && warned.getAndIncrement() < 8)
                        onProgress(Progress("warn", 0, 0, "  ⚠ could not convert ${job.input.name} (ffmpeg/decode failed)"))
                }
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

    /** Transcode one job (skip if a good output already exists) and record its row. Returns success. */
    private fun convertOne(job: Job): Boolean {
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
            return ok
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
        // CWT is the compute hot path. HETEROGENEOUS scheduling: the single GPU is ~2.7x faster than
        // ONE CPU thread but ~7.5x slower than all 20 cores together, so we let the GPU process a clip
        // ONLY when it's free (one-permit), while the other worker threads render on CPU — GPU
        // throughput ADDS to the CPU pool instead of serializing every clip through one device.
        if (!jpg.isFile) {
            val onGpu = GpuFft.available() && gpuPermit.tryAcquire()
            try { SpectrogramRenderer.renderCwtJpg(pcm, 44100, jpg, useGpu = onGpu); images.incrementAndGet() }
            catch (e: Exception) { System.err.println("CWT image failed ${wav.name}: ${e.message}") }
            finally { if (onGpu) gpuPermit.release() }
        }
    }

    /** One concurrent GPU CWT at a time (GpuFft is single-device/@Synchronized); the rest go to CPU. */
    private val gpuPermit = java.util.concurrent.Semaphore(1)

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

    // ---- metadata merge (boolean-aware) --------------------------------------------------------

    // Word booleans seen across the datasets (Coswara True/blank & y/n; COUGHVID True/False).
    // Numeric 0/1 are NOT treated as booleans (e.g. cough_detected="0.0" is a real probability).
    private val TRUE_TOKENS = setOf("true", "yes", "y")
    private val FALSE_TOKENS = setOf("false", "no", "n")

    /**
     * Merge a source's key/value pairs into [meta] with CORRECT boolean handling, so misleading
     * metadata never transfers:
     *  - a **true-like** field contributes only its KEY as a present qualifier (`key=true`) — e.g. a
     *    Coswara `smoker=True` column or a COUGHVID `wheezing_1=True` annotation becomes the tag name;
     *  - a **false-like** field is OMITTED entirely (no `key=false` — this was the transfer bug);
     *  - any other non-blank value is kept verbatim as `key=value` (age, gender, status, …).
     * Applies identically to CSV boolean-flag columns and JSON boolean qualifiers.
     */
    private fun mergeMeta(meta: MutableMap<String, String>, src: Map<String, String>) {
        for ((k, raw) in src) {
            val v = raw.trim()
            if (v.isEmpty()) continue
            when (v.lowercase()) {
                in TRUE_TOKENS -> meta[k] = "true"     // keep the qualifier name; presence == true
                in FALSE_TOKENS -> { /* reject: misleading false qualifier */ }
                else -> meta[k] = v                    // genuine value column
            }
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
            mergeMeta(meta, Json.flat(dir.resolve("$uuid.json")))
            compiled[uuid]?.let { mergeMeta(meta, it) }
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

    /** UrbanSound8K: 10 environmental classes (none are coughs → all negatives). Real metadata in
     *  metadata\UrbanSound8K.csv keyed by slice_file_name; audio under audio\fold1..fold10\. */
    private fun collectUrbanSound8K(root: File, outDir: File): List<Job> {
        val dir = findChild(root, "UrbanSound8K") ?: return emptyList()
        val meta = Csv.readKeyed(dir.resolve("metadata/UrbanSound8K.csv"), keyCol = "slice_file_name")
        val audioRoot = dir.resolve("audio").takeIf { it.isDirectory } ?: dir
        val files = (audioRoot.listFiles { f -> f.isDirectory } ?: emptyArray())
            .flatMap { audioFiles(it) }.ifEmpty { audioFiles(audioRoot) }
        return files.map { f ->
            val row = meta[f.name] ?: emptyMap()
            val cls = row["class"] ?: "unknown"
            val m = LinkedHashMap<String, String>(); m["source"] = "UrbanSound8K"; mergeMeta(m, row)
            jobFor(outDir, "urban8k", f.nameWithoutExtension, f, false, Row(
                wav = "", source = "UrbanSound8K", originalId = f.nameWithoutExtension, soundType = cls,
                isCough = "false", healthStatus = "na", age = "", gender = "", country = "",
                coughDetected = "", metadataJson = gson.toJson(m)
            ))
        }
    }

    /** Long-form speech (old-time radio): no per-file metadata, blanket-labelled "mostly speech"
     *  negatives. Each episode is ffmpeg-segmented into [TRAIN_SEG_SEC]s WAV chunks (capped per file)
     *  so ALLDATA gets clip-sized speech, not multi-hundred-MB whole episodes. */
    private fun collectTrain(root: File, outDir: File): List<Job> {
        val dir = findChild(root, "train") ?: return emptyList()
        val tmp = File(outDir, "_tmp_train").apply { mkdirs() }
        val jobs = ArrayList<Job>()
        var capped = 0
        for (f in audioFiles(dir)) {
            if (cancelled) break
            val chunks = AudioDecoder.segmentToWav(f, tmp, "tr_" + sanitize.replace(f.nameWithoutExtension.take(36), "_"),
                seconds = TRAIN_SEG_SEC)
            val use = if (chunks.size > TRAIN_MAX_CHUNKS) { capped++; chunks.take(TRAIN_MAX_CHUNKS).also { drop -> chunks.drop(TRAIN_MAX_CHUNKS).forEach { it.delete() } } } else chunks
            for (chunk in use) {
                val id = chunk.nameWithoutExtension
                val m = mapOf("source" to "train", "label" to "mostly speech", "file" to f.name)
                jobs.add(jobFor(outDir, "train", id, chunk, true, Row(
                    wav = "", source = "train", originalId = id, soundType = "speech",
                    isCough = "false", healthStatus = "na", age = "", gender = "", country = "",
                    coughDetected = "", metadataJson = gson.toJson(m)
                )))
            }
        }
        if (capped > 0) System.err.println("train: capped $capped episode(s) to $TRAIN_MAX_CHUNKS × ${TRAIN_SEG_SEC}s chunks")
        return jobs
    }

    private const val TRAIN_SEG_SEC = 6
    private const val TRAIN_MAX_CHUNKS = 60   // ≤6 min of each long episode (bounds the speech pool)

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
                combined[participant]?.let { mergeMeta(meta, it) }
                perParticipantJson[participant]?.let { mergeMeta(meta, it) }
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
    /** Build output folder, excluded from ALL dataset discovery so it can't be re-ingested into itself. */
    @Volatile private var excludeDir: File? = null
    private fun isExcluded(f: File): Boolean {
        val ex = excludeDir ?: return false
        return try { val p = f.canonicalFile.path; p == ex.path || p.startsWith(ex.path + File.separator) }
        catch (e: Exception) { false }
    }

    /** Locate a dataset under [root] by BFS through the directory subtree (so it's found wherever it's
     *  nested, not only as a direct child), SKIPPING the build output folder, then drill to its content. */
    private fun findChild(root: File, vararg names: String): File? =
        searchTree(root, names)?.let { drillToContent(it) }

    private fun searchTree(root: File, names: Array<out String>): File? {
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            val subs = (dir.listFiles { f -> f.isDirectory && !isExcluded(f) } ?: continue).toList()
            for (n in names) subs.firstOrNull { it.name.equals(n, true) }?.let { return it }
            for (n in names) subs.firstOrNull { it.name.startsWith(n, true) }?.let { return it }
            if (depth < 3) subs.forEach { queue.add(it to depth + 1) }   // descend up to 3 levels
        }
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
            // Cheap dataset-marker FILE checks FIRST, so we never enumerate a huge flat folder (e.g.
            // COUGHVID's ~68k files on an external drive) just to look for subdirectories.
            if (d.resolve("metadata_compiled.csv").isFile || d.resolve("combined_data.csv").isFile ||
                d.resolve("meta").isDirectory) return d
            val subs = d.listFiles { f -> f.isDirectory && !isExcluded(f) } ?: return d
            val markerDir = subs.any { it.name.matches(Regex("\\d{8}")) || it.name.equals("audio", true) }
            if (markerDir || subs.size != 1) return d
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
        val u8 = findChild(sourcesRoot, "UrbanSound8K")
        line("UrbanSound8K", u8) { "metadata=${u8!!.resolve("metadata/UrbanSound8K.csv").isFile}, audio=${u8.resolve("audio").isDirectory}" }
        val tr = findChild(sourcesRoot, "train")
        line("train", tr) { "${(tr!!.listFiles { f -> f.isFile && f.extension.lowercase() in AUDIO_EXT } ?: emptyArray()).size} audio files (mostly speech)" }
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
