plugins { id("com.android.application") }
android {
    namespace = "dev.omamobile.host"
    compileSdk = 35
    ndkVersion = "30.0.14904198"
    defaultConfig {
        applicationId = "dev.omamobile.host"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"
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
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libtawcroot.so" } }
}
dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.apache.commons:commons-compress:1.28.0")
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
