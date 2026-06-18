package com.example.FFTT04M.desktop

import java.io.File

/**
 * Single root for the desktop app's runtime data, so everything lives under the workspace
 * (…/AndroidProjects/data) instead of being scattered across the user home. Resolution order:
 *  1. env `FFTT04_DATA` (explicit override);
 *  2. `<workspace>/data`, where the workspace is the dir containing the FFTT04* repos, discovered by
 *     walking up from the jar/cwd (the same up-walk GpuFft / HubertKMeansUnits use);
 *  3. `~/FFTT04M_data` fallback, so nothing breaks if discovery fails.
 *
 * On first access, legacy `~/FFTT04M_*` data dirs are COPIED into the new root (one-time; originals
 * are left in place as a backup). Resolves once, then cached.
 */
object Workspace {
    val root: File by lazy { resolve().also { it.mkdirs(); migrateLegacy(it) } }

    fun dir(name: String): File = File(root, name).apply { mkdirs() }
    fun file(name: String): File = File(root, name)

    private fun resolve(): File {
        System.getenv("FFTT04_DATA")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        discoverWorkspace()?.let { return File(it, "data") }
        return File(System.getProperty("user.home"), "FFTT04M_data")
    }

    /** The dir that directly contains the FFTT04* repos (… /AndroidProjects), found above us. */
    private fun discoverWorkspace(): File? {
        val starts = buildList {
            add(File(System.getProperty("user.dir")))
            var d: File? = jarDir(); repeat(8) { d?.let { add(it); d = it.parentFile } }
        }
        for (s in starts) {
            var cur: File? = s
            repeat(10) {
                val c = cur ?: return@repeat
                if (File(c, "FFTT04D").isDirectory &&
                    (c.name.equals("AndroidProjects", true) || File(c, "FFTT04M").isDirectory)) return c
                cur = c.parentFile
            }
        }
        return null
    }

    private val legacyDirs = mapOf(
        "FFTT04M_fractionation" to "fractionation",
        "FFTT04M_fractionation_validation" to "fractionation_validation",
        "FFTT04M_usb_import" to "usb_import",
    )

    private fun migrateLegacy(root: File) {
        val home = File(System.getProperty("user.home"))
        for ((old, new) in legacyDirs) {
            val src = File(home, old); val dst = File(root, new)
            if (src.isDirectory && !dst.exists()) {
                try { src.copyRecursively(dst, overwrite = false) }
                catch (e: Exception) { System.err.println("Workspace migrate $old: ${e.message}") }
            }
        }
        val mc = File(home, "FFTT04M/manual_comments.json"); val mcDst = File(root, "manual_comments.json")
        if (mc.isFile && !mcDst.exists()) try { mc.copyTo(mcDst) } catch (_: Exception) {}
    }

    private fun jarDir(): File? = try {
        val s = File(Workspace::class.java.protectionDomain.codeSource.location.toURI())
        if (s.isFile) s.parentFile else s
    } catch (e: Throwable) { null }
}
