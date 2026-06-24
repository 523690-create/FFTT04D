package com.example.FFTT04M.desktop

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File

/**
 * Ingest USB-imported, manually-labelled clips into the training corpus so the next codebook build
 * uses them. **Manual comments only**: a clip is ingested only if it has a non-empty `<base>.txt`
 * sidecar (the desktop's USB pull preserves these). For each such clip:
 *   - merge its comment into `data/manual_comments.json` keyed by base id — **dedup by id**, so
 *     re-ingesting the same clip just updates its label rather than duplicating anything,
 *   - copy `<base>.wav` into `<repoRoot>/device_ingest/` (a build-scanned dataset root; same filename
 *     overwrites, so re-ingest is idempotent),
 *   - then `device_ingest`'s Spectral-Flux jsonl is regenerated from the wavs now present, marking
 *     each clip eligible for the build (the build re-windows audio on a fixed grid for features, so the
 *     jsonl only needs id + span).
 */
object Ingester {
    private const val SR = 44100

    data class Result(val scanned: Int, val ingested: Int, val totalComments: Int, val ingestDir: File)

    fun ingest(source: File): Result {
        val repo = Workspace.repoRoot ?: File(".")
        val ingestDir = File(repo, "device_ingest").apply { mkdirs() }
        val mcFile = Workspace.file("manual_comments.json")

        // Load existing comments (id -> raw comment) into a mutable, order-preserving map.
        val merged = LinkedHashMap<String, String>()
        if (mcFile.isFile) runCatching {
            JsonParser.parseString(mcFile.readText()).asJsonObject.entrySet()
                .forEach { merged[it.key] = it.value.asString }
        }

        var scanned = 0; var ingested = 0
        source.walkTopDown().forEach { wav ->
            if (!wav.isFile || !wav.extension.equals("wav", true)) return@forEach
            // Skip our own ingest dir if the user points the source at the repo.
            if (wav.absolutePath.startsWith(ingestDir.absolutePath)) return@forEach
            scanned++
            val base = wav.nameWithoutExtension
            val comment = File(wav.parentFile, "$base.txt").takeIf { it.isFile }?.readText()?.trim().orEmpty()
            if (comment.isEmpty()) return@forEach                 // manual comments only
            merged[base] = comment                                 // dedup by id (update in place)
            runCatching { wav.copyTo(File(ingestDir, "$base.wav"), overwrite = true) }
            ingested++
        }

        runCatching { mcFile.writeText(GsonBuilder().setPrettyPrinting().create().toJson(merged)) }
        regenerateFragments(ingestDir)
        return Result(scanned, ingested, merged.size, ingestDir)
    }

    /** One jsonl line per wav now in device_ingest (id + full-clip span). Regenerated from scratch each
     *  time, so it always matches the folder — no duplicate lines accumulate. */
    private fun regenerateFragments(ingestDir: File) {
        val outDir = File(Workspace.dir("fractionation"), "device_ingest").apply { mkdirs() }
        val sb = StringBuilder()
        ingestDir.listFiles { f -> f.isFile && f.extension.equals("wav", true) }
            ?.sortedBy { it.name }?.forEach { wav ->
                val pcm = AudioDecoder.decode(wav) ?: return@forEach
                val durMs = (pcm.size.toLong() * 1000 / SR).toInt().coerceAtLeast(1)
                sb.append("{\"id\":\"${wav.nameWithoutExtension}\",\"startMs\":0,\"endMs\":$durMs}\n")
            }
        File(outDir, "Spectral_Flux_Onset_segments.jsonl").writeText(sb.toString())
    }
}
