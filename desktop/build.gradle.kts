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

    // Optional HuBERT acoustic-unit inference (fractionation method 6). Code is gated at runtime by
    // HubertKMeansUnits.available (a model-file probe, mirroring the GpuFft pattern): when
    // desktop/native/hubert/hubert_base.onnx is absent the method is an inert no-op, so this jar is
    // only exercised once a model is dropped in.
    //
    // CPU by default (onnxruntime, pinned to a Java 8-runtime-compatible release). Build with
    // -PuseOnnxGpu to swap in the CUDA-12 GPU build instead — a much larger jar (~400 MB provider
    // DLL) that also needs cuDNN 9 + CUDA-12 cuBLAS on the native search path. The ORT jars are built
    // on Java 11 but RUN on Java 8+, so both satisfy the Java 8 toolchain above. The CUDA-12 Java
    // path is only correct on recent ORT (see microsoft/onnxruntime#19960), hence the newer GPU pin.
    // Presence of -PuseOnnxGpu enables it; -PuseOnnxGpu=false disables. (A bare -PuseOnnxGpu sets the
    // value to "" — treat empty as "on" so the flag works without =true.)
    val useOnnxGpuProp = project.findProperty("useOnnxGpu")?.toString()?.lowercase()
    val useOnnxGpu = useOnnxGpuProp != null && useOnnxGpuProp !in listOf("false", "0", "no")
    if (useOnnxGpu) {
        implementation("com.microsoft.onnxruntime:onnxruntime_gpu:1.20.0")
    } else {
        implementation("com.microsoft.onnxruntime:onnxruntime:1.16.3")
    }

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

// Headless validation runner for the fractionation methods (SOUND_FRACTIONATION.md §3).
// Usage: ./gradlew :desktop:fractionateCli --args="D:\AndroidProjects\true_cough"
tasks.register<JavaExec>("fractionateCli") {
    group = "application"
    description = "Run all pure-Kotlin fractionation methods over a folder; dump JSONL + overlay PNGs."
    mainClass.set("com.example.FFTT04M.desktop.FractionateCli")
    classpath = sourceSets["main"].runtimeClasspath
    systemProperty("java.awt.headless", "true")
}

// Supervised fragment-level phoneme codebook + decode (PHONEME_CODEBOOK.md).
tasks.register<JavaExec>("phonemeCodebookCli") {
    group = "application"
    description = "Build a per-label phoneme codebook from labelled clips, then decode all clips."
    mainClass.set("com.example.FFTT04M.desktop.PhonemeCodebookCli")
    classpath = sourceSets["main"].runtimeClasspath
    systemProperty("java.awt.headless", "true")
    systemProperty("hubert.feat", System.getProperty("hubert.feat") ?: "")   // -Dhubert.feat=true → HuBERT window features
    systemProperty("single.alphabet", System.getProperty("single.alphabet") ?: "")   // -Dsingle.alphabet=true → one global hex alphabet
    systemProperty("codebook.k", System.getProperty("codebook.k") ?: "")   // -Dcodebook.k=256 → 256 phonemes
    systemProperty("purify.mixed", System.getProperty("purify.mixed") ?: "")   // -Dpurify.mixed=true → route bg windows out of cough-tag clips
    systemProperty("codebook.only", System.getProperty("codebook.only") ?: "")   // -Dcodebook.only=true → build codebook, skip decode-all
}

// One-off: visualise the bronchitis typical→recovering progression as a HuBERT PCA cloud.
tasks.register<JavaExec>("bronchitisCloud") {
    group = "application"
    description = "Render BT (typical) vs DH (recovering) bronchitis clips as a HuBERT PCA-2D cloud PNG."
    mainClass.set("com.example.FFTT04M.desktop.BronchitisCloud")
    classpath = sourceSets["main"].runtimeClasspath
    systemProperty("java.awt.headless", "true")
}

// Unsupervised cluster discovery over whole-clip HuBERT embeddings (labels used only for scoring).
tasks.register<JavaExec>("unsupervisedCluster") {
    group = "application"
    description = "Cluster HuBERT whole-clip embeddings unsupervised; score vs labels (purity/NMI/ARI) + PCA PNG."
    mainClass.set("com.example.FFTT04M.desktop.UnsupervisedCluster")
    classpath = sourceSets["main"].runtimeClasspath
    systemProperty("java.awt.headless", "true")
    systemProperty("cluster.corpus", System.getProperty("cluster.corpus") ?: "")   // -Dcluster.corpus=p3|ALLDATA
    systemProperty("cluster.ks", System.getProperty("cluster.ks") ?: "")            // -Dcluster.ks=5,8,10,12
    systemProperty("cluster.max", System.getProperty("cluster.max") ?: "")          // -Dcluster.max=30000 sample cap
    systemProperty("cluster.exclude", System.getProperty("cluster.exclude") ?: "")  // -Dcluster.exclude=voice,noise
    systemProperty("cluster.binary", System.getProperty("cluster.binary") ?: "")    // -Dcluster.binary=true → cough/not-cough eval
    systemProperty("cluster.segbinary", System.getProperty("cluster.segbinary") ?: "")  // -Dcluster.segbinary=true → segment-level
    for (p in listOf("seg.max", "seg.win", "seg.hop", "seg.maxwin")) systemProperty(p, System.getProperty(p) ?: "")
    // HARD heap cap: a runaway allocation OOMs this forked JVM instead of thrashing the whole machine.
    maxHeapSize = "3g"
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
