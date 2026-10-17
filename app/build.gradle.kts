plugins { id("com.android.application") }

val sourceRevision = providers.gradleProperty("sourceRevision")
    .orElse(providers.environmentVariable("GITHUB_SHA"))
    .map { revision ->
        revision.trim().takeIf { it.matches(Regex("[0-9a-fA-F]{7,64}")) } ?: "unknown"
    }
    .orElse("unknown")

abstract class AttributionAssets : DefaultTask() {
    @get:InputFile abstract val credits: org.gradle.api.file.RegularFileProperty
    @get:InputFile abstract val provenance: org.gradle.api.file.RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: org.gradle.api.file.DirectoryProperty
    @TaskAction fun generate() {
        val directory = outputDirectory.get().asFile
        directory.mkdirs()
        credits.get().asFile.copyTo(directory.resolve("CREDITS.md"), overwrite = true)
        directory.resolve("licenses").mkdirs()
        provenance.get().asFile.copyTo(directory.resolve("licenses/REFERENCES.txt"), overwrite = true)
    }
}
android {
    namespace = "dev.omamobile.host"
    compileSdk = 37
    ndkVersion = "30.0.14904198"
    defaultConfig {
        applicationId = "dev.omamobile.host"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"
        buildConfigField("String", "SOURCE_REVISION", sourceRevision.map { "\"$it\"" }.get())
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true; aidl = true }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "4.1.2" }
    }
    packaging {
        jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libtawcroot.so" }
        resources {
            merges += "META-INF/LICENSE.md"
            pickFirsts += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}
androidComponents.onVariants { variant ->
    val attribution = tasks.register<AttributionAssets>("${variant.name}AttributionAssets") {
        credits.set(rootProject.layout.projectDirectory.file("CREDITS.md"))
        provenance.set(rootProject.layout.projectDirectory.file("docs/REFERENCES.md"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(attribution, AttributionAssets::outputDirectory)
}
dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.apache.commons:commons-compress:1.28.0")
    // OpenPGP detached-signature verification; 1.86 is the current release
    // (Maven Central/Bouncy Castle release page, checked 2026-10-15).
    implementation("org.bouncycastle:bcpg-jdk18on:1.86")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("com.github.luben:zstd-jni:1.5.7-23@aar")
    testImplementation("com.github.luben:zstd-jni:1.5.7-23")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// Debug builds support contributor CI without the separately built compositor.
// A release must not silently degrade into that command-only APK.
val verifyReleasePayload by tasks.registering(Exec::class) {
    val report = layout.buildDirectory.file("reports/release-payload.json")
    workingDir(rootDir)
    commandLine(providers.gradleProperty("python").getOrElse(
        if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"),
        "tools/native/verify_release.py", "--project", rootDir.absolutePath)
    doFirst {
        report.get().asFile.parentFile.mkdirs()
        standardOutput = report.get().asFile.outputStream()
        logger.lifecycle("Release payload report: ${report.get().asFile}")
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleasePayload) }
