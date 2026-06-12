plugins {
    kotlin("jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    jvmToolchain(8)
}

dependencies {
    // Kotlin stdlib
    implementation(kotlin("stdlib"))

    // Audio decoding
    // javax.sound.sampled is included in JDK

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // TAR/GZIP support for Coswara dataset extraction
    implementation("org.apache.commons:commons-compress:1.24.0")

    // Optional GPU acceleration (NVIDIA): JCuda runtime + cuFFT, with Windows x86_64 natives.
    // The natives jars bundle the CUDA runtime/cuFFT libs, so only an NVIDIA driver is required
    // (no CUDA toolkit install). GpuFft falls back to the CPU FFT if any of this fails to load.
    val jcudaVer = "12.6.0"
    val jcudaClassifier = "windows-x86_64"
    implementation("org.jcuda:jcuda:$jcudaVer") { isTransitive = false }
    implementation("org.jcuda:jcufft:$jcudaVer") { isTransitive = false }
    implementation("org.jcuda:jcuda-natives:$jcudaVer:$jcudaClassifier")
    implementation("org.jcuda:jcufft-natives:$jcudaVer:$jcudaClassifier")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.example.FFTT04M.desktop.MainKt")
}

// Create fat JAR for direct execution (visible window)
tasks.register<Jar>("fatJar") {
    manifest {
        attributes["Main-Class"] = "com.example.FFTT04M.desktop.MainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.exists() }.map { if (it.isDirectory) it else zipTree(it) }
    })
    archiveFileName.set("CoughAnalyzer.jar")
}

// ---- Version-letter stamp (parity with the mobile launcher-icon letter) ---------------------
// Each build advances a letter a..z,A..Z (cycling) and writes it into a generated resource the app
// reads (BuildInfo) and shows top-right in the title. Mirrors the phone's icon_letter_index.txt.
val versionLetterDir = layout.buildDirectory.dir("generated/version").get().asFile
val generateVersionLetter = tasks.register("generateVersionLetter") {
    val counter = file("version_letter_index.txt")
    val outDir = versionLetterDir
    outputs.dir(outDir)
    outputs.upToDateWhen { false }   // advance the letter on every build
    doLast {
        val seq = (('a'..'z') + ('A'..'Z'))
        val idx = counter.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        val letter = seq[((idx % 52) + 52) % 52]
        counter.writeText((idx + 1).toString())
        outDir.mkdirs()
        outDir.resolve("version.properties").writeText("letter=$letter\nindex=$idx\n")
        println("Desktop version letter: '$letter' (build index $idx)")
    }
}
sourceSets["main"].resources.srcDir(versionLetterDir)
tasks.named("processResources") { dependsOn(generateVersionLetter) }
