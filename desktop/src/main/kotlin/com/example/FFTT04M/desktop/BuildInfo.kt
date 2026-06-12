package com.example.FFTT04M.desktop

import java.util.Properties

/**
 * Per-build version letter (a..z,A..Z, cycling), stamped into `version.properties` by the
 * `generateVersionLetter` Gradle task and bundled on the classpath. Mirrors the mobile
 * launcher-icon letter so a desktop build can be identified at a glance. Empty if absent.
 */
object BuildInfo {
    val versionLetter: String by lazy {
        runCatching {
            BuildInfo::class.java.getResourceAsStream("/version.properties")?.use { s ->
                Properties().apply { load(s) }.getProperty("letter", "").trim()
            }.orEmpty()
        }.getOrDefault("")
    }
}
