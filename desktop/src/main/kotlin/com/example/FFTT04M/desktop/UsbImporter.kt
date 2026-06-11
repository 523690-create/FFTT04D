package com.example.FFTT04M.desktop

import java.io.File

/**
 * Pulls cough recordings + their per-recording metadata sidecars off a USB-connected Android device
 * (legacy FFTT04L or modern FFTT04M — both share appId com.example.FFTT04M) via adb, into a local
 * folder the analyzer can load. Mirrors the device-side "USB Upload" item added to the SHARE dialog.
 */
object UsbImporter {

    private const val APP_ID = "com.example.FFTT04M"
    // Where the apps keep recordings: public Documents/FFTT04M first (survives uninstall), then the
    // app-private external dir as a fallback.
    private val REMOTE_DIRS = listOf(
        "/sdcard/Documents/FFTT04M",
        "/sdcard/Android/data/$APP_ID/files",
    )

    data class Device(val serial: String, val model: String)
    data class PullResult(val ok: Boolean, val wavCount: Int, val dir: File, val message: String)

    // ---- adb resolution (PATH, then the standard Android SDK location) --------------------------
    @Volatile private var adbCached: String? = null
    private fun adb(): String? {
        adbCached?.let { return it }
        val candidates = buildList {
            (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))?.let {
                add(File(it, "platform-tools/adb.exe").absolutePath)
            }
            System.getenv("LOCALAPPDATA")?.let {
                add(File(it, "Android\\Sdk\\platform-tools\\adb.exe").absolutePath)
            }
            add("adb")
        }
        adbCached = candidates.firstOrNull { canRun(it) }
        return adbCached
    }

    fun adbAvailable(): Boolean = adb() != null

    private fun canRun(exe: String): Boolean = try {
        val p = ProcessBuilder(exe, "version").redirectErrorStream(true).start()
        p.inputStream.readBytes(); p.waitFor(); p.exitValue() == 0
    } catch (e: Exception) { false }

    private fun run(vararg args: String): Pair<Int, String> {
        val exe = adb() ?: return -1 to "adb not found"
        return try {
            val p = ProcessBuilder(listOf(exe) + args).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            p.waitFor()
            p.exitValue() to out
        } catch (e: Exception) { -1 to (e.message ?: "adb error") }
    }

    /** Connected, authorized devices (`adb devices -l`). */
    fun listDevices(): List<Device> {
        val (_, out) = run("devices", "-l")
        return out.lineSequence().drop(1).mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || !t.contains("device")) return@mapNotNull null
            val cols = t.split(Regex("\\s+"))
            if (cols.size < 2 || cols[1] != "device") return@mapNotNull null
            val model = Regex("model:(\\S+)").find(t)?.groupValues?.get(1) ?: "device"
            Device(cols[0], model)
        }.toList()
    }

    /** Pull all recordings from [device] into <importRoot>/<serial>/, trying each known remote dir. */
    fun pull(device: Device, importRoot: File): PullResult {
        val dest = File(importRoot, device.serial).apply { mkdirs() }
        var pulledFrom: String? = null
        for (remote in REMOTE_DIRS) {
            // Does the remote dir exist and hold any .wav?
            val (_, ls) = run("-s", device.serial, "shell", "ls", "$remote/*.wav")
            if (ls.contains("No such file", true) || ls.contains("not found", true) || ls.isBlank()) continue
            val (code, out) = run("-s", device.serial, "pull", "-a", remote, dest.absolutePath)
            if (code == 0) { pulledFrom = remote; break }
            else if (out.isNotBlank() && !out.contains("No such file", true)) {
                return PullResult(false, 0, dest, "adb pull failed: ${out.trim().take(200)}")
            }
        }
        if (pulledFrom == null) {
            return PullResult(false, 0, dest,
                "No recordings found on ${device.model}. Run SHARE → 'USB Upload (to desktop)' on the device first.")
        }
        val wavs = dest.walkTopDown().count { it.isFile && it.extension.equals("wav", true) }
        return PullResult(true, wavs, dest, "Pulled $wavs recording(s) from ${device.model} ($pulledFrom)")
    }
}
