package com.example.FFTT04M.desktop

import java.io.File

/**
 * Headless equivalent of the GUI's "Ingest → Retrain" ingest step: fold manually-labelled USB-import clips
 * (wavs with a non-empty `<base>.txt` sidecar) into `device_ingest/` + `data/manual_comments.json` so the
 * next codebook build uses them. Idempotent (dedup by id). Follow with the codebook retrain:
 *   :desktop:phonemeCodebookCli -Dhubert.feat=true -Dcodebook.k=256 -Dpurify.mixed=true -Dcodebook.only=true -PuseOnnxGpu
 *
 * Run: ./gradlew :desktop:ingestClips   (default source data/usb_import; override -Dingest.source=...)
 */
object IngestCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val src = File(args.getOrNull(0)?.takeIf { it.isNotBlank() }
            ?: System.getProperty("ingest.source")?.takeIf { it.isNotBlank() }
            ?: Workspace.dir("usb_import").path)
        if (!src.isDirectory) { println("no ingest source dir: $src"); return }
        println("=== INGEST from $src ===")
        val r = Ingester.ingest(src)
        println("scanned ${r.scanned} wav(s); ingested ${r.ingested} with manual comments → ${r.ingestDir}")
        println("manual_comments.json now holds ${r.totalComments} label(s)")
    }
}
