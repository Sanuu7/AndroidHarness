import java.util.Properties
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.util.zip.GZIPInputStream
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// The native build needs the exact NDK version pinned below, and AGP fails
// Gradle configuration outright when it is missing. Tooling runners without an
// NDK (GitHub's default-setup CodeQL scan, which has no editable workflow to
// install one) only trace Kotlin and Java, so drop the whole native block when
// the NDK is absent. Real builds install the pinned NDK and keep native code.
// AGP resolves a pinned version only from <sdk>/ndk/<version>, so a foreign
// ANDROID_NDK_HOME (CI images export one) must not enable the native block.
val pinnedNdk = "27.2.12479018"
val githubBuildProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
// Load only from the ignored local build settings; never from public CI variables.
// Device Flow never embeds an OAuth client secret or auth backend.
val githubClientId = githubBuildProperties.getProperty("GITHUB_CLIENT_ID", "")
val distributionChannel = providers.gradleProperty("distribution").getOrElse("github")
require(distributionChannel in setOf("github", "play")) { "distribution must be github or play" }
require(githubClientId.matches(Regex("[A-Za-z0-9]*"))) { "Invalid GitHub OAuth Client ID" }
val hasNdk: Boolean = run {
    val sdk = System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) }
        ?: rootProject.file("local.properties").takeIf { it.exists() }
            ?.readLines()?.firstOrNull { it.trim().startsWith("sdk.dir=") }
            ?.substringAfter("=")?.trim()?.let { File(it) }
        ?: return@run false
    sdk.resolve("ndk/$pinnedNdk").isDirectory
}

android {
    if (distributionChannel == "play") dynamicFeatures += setOf(":local_gpu")
    sourceSets.getByName("main").java.srcDir("src/${distributionChannel}Delivery/java")
    namespace = "com.androidharness.app"
    // The in-app picker must be able to switch languages without a Play download.
    bundle { language { enableSplit = false } }
    compileSdk = 37
    if (hasNdk) {
        ndkVersion = pinnedNdk
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    } else {
        logger.warn("app: NDK $pinnedNdk not found, configuring without native code (libharness_local.so)")
    }

    defaultConfig {
        applicationId = "com.androidharness.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 35
        versionName = "3.1"
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"$githubClientId\"")
        buildConfigField("boolean", "PLAY_DISTRIBUTION", (distributionChannel == "play").toString())
        if (hasNdk) {
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
            externalNativeBuild {
                cmake { arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-DHARNESS_GPU_OUTPUT=${layout.buildDirectory.dir("generated/gpuNative").get().asFile}") }
            }
        }
        // Instrumented tests drive the real WebView (screenshots, history,
        // promise staging), which no JVM test can exercise.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["localImportFixtureEnabled"] = !providers.gradleProperty("cardBenchmark").isPresent
    }

    signingConfigs {
        getByName("debug") {
            // Prefer the stable debug key kept in the gitignored signing-keys/
            // folder. AGP's default path is machine-dependent (it resolved to
            // ~/.config/.android on this box and silently generated a fresh
            // key there), and a stray debug keystore makes every install on a
            // device that already has the app fail with a signature mismatch.
            val stableDebugKey = rootProject.file("signing-keys/debug.keystore")
            if (stableDebugKey.exists()) {
                storeFile = stableDebugKey
            }
        }
    }

    buildTypes {
        release {
            // Local alpha distribution: signed with the debug keystore so the
            // APK installs without a release keystore. Swap to a dedicated
            // signing config before any public/Play distribution.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
        // Opt-in, non-debuggable release-equivalent window for card frame measurements.
        // Test hosts never enter the normal release APK.
        if (providers.gradleProperty("cardBenchmark").isPresent) {
            create("benchmark") {
                initWith(getByName("release"))
                applicationIdSuffix = ".benchmark"
                matchingFallbacks += "release"
                isDebuggable = false
            }
        }
    }

    if (providers.gradleProperty("cardBenchmark").isPresent) {
        testBuildType = "benchmark"
        sourceSets.getByName("benchmark") {
            java.srcDir("src/debug/java")
            manifest.srcFile("src/debug/AndroidManifest.xml")
        }
    }

    lint {
        // lintVitalAnalyzeRelease crashes inside lint's own JavaDoc parser
        // (NoSuchMethodError in JavaDocParser.parseDataItem while analyzing
        // HarnessUserService.kt), an AGP/lint bug, not a lint finding. Skip
        // the release lint gate until the toolchain bug is fixed.
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
        aidl = true
        // BoringSSL ships as a prefab package for the on-device ADB pairing SPAKE2 step.
        prefab = true
    }

    packaging {
        // Delivered separately by Play or the pinned GitHub runtime package.
        jniLibs.excludes += "**/libggml-vulkan.so"
        jniLibs.excludes += "**/libharness_gpu_unavailable.so"
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt",
                "META-INF/LICENSE.md", "META-INF/LICENSE-notice.md", "META-INF/NOTICE.md",
            )
        }
    }
}

// AGP's own tooling drags in old copies of libraries we never ship. The Unified
// Test Platform pulls gRPC's netty 4.1.93/4.1.110, and the lint tool carries
// AGP's crypto, HTTP and XML jars. All of these configurations exist to run the
// build or tests, never to build the APK, so lifting them here touches nothing
// the app ships. Each group moves together on purpose: mixing 4.1.x netty
// modules across a version boundary breaks at runtime.
configurations.configureEach {
    if (name.startsWith("unified-test-platform") || name == "androidLintTool") {
        resolutionStrategy.eachDependency {
            when {
                requested.group == "io.netty" -> useVersion("4.1.137.Final")
                requested.group == "org.bouncycastle" -> useVersion("1.85")
                requested.name == "httpclient" -> useVersion("4.5.14")
                requested.name == "commons-lang3" -> useVersion("3.18.0")
                // CVE-2026-102554: 33.7.2 bounds the collections deserialization
                // arrays that 33.4 and 33.6 sized from attacker-declared counts.
                requested.group == "com.google.guava" && requested.name == "guava" -> useVersion("33.7.2-jre")
            }
        }
    }
}

dependencies {
    implementation(libs.backdrop)
    if (distributionChannel == "play") implementation("com.google.android.play:feature-delivery:2.1.0")
    constraints {
        // dev.cel asks for Guava 33.6 and room-compiler for 33.2.1. 33.7.2 is
        // the first release that stops sizing collection arrays from the
        // declared element count during deserialization (CVE-2026-102554).
        implementation("com.google.guava:guava:33.7.2-jre")
        ksp("com.google.guava:guava:33.7.2-jre")
    }
    ksp(libs.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation("com.materialkolor:material-kolor:5.0.1")
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)
    implementation(libs.documentfile)
    // Custom Tabs for the MCP OAuth authorize screen.
    implementation(libs.browser)
    // WebViewAssetLoader: serves workspace files to the agent browser over a
    // stable https origin without any sockets.
    implementation(libs.webkit)
    implementation(libs.security.crypto)
    implementation(libs.biometric)
    implementation(libs.fragment.ktx)
    implementation("androidx.appcompat:appcompat:1.6.1")

    implementation("com.github.mwiede:jsch:2.28.7")
    // Ed25519 SSH keys on Android versions without a matching platform provider.
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")
    implementation("org.conscrypt:conscrypt-android:2.8.0")
    // Self-signed certificates for the local ADB key used by wireless pairing.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85")
    // SPAKE2 for Android 11+ wireless debugging pairing, compiled into libadb.so.
    implementation("io.github.vvb2060.ndk:boringssl:20250114")
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    // CEL compiles and checks optional per-model reasoning request mappings on device.
    implementation("dev.cel:cel:0.14.0")
    implementation(libs.coroutines.android)
    implementation(libs.commons.compress)
    implementation(libs.commons.net)
    implementation(libs.xz)
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("com.github.topjohnwu.libsu:service:6.0.0")
    implementation("io.github.iamr0s:Dhizuku-API:2.6.0")
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    // In-app code editor (gutter, undo/redo, search engine) for the file manager.
    implementation(libs.sora.editor)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// The hash is bundled in the base app, so neither a changed release manifest nor
// a replaced download can authorize different executable code.
abstract class PrepareGpuRuntime @Inject constructor(private val execOps: ExecOperations) : DefaultTask() {
    @get:InputDirectory abstract val nativeDirectory: DirectoryProperty
    @get:Optional @get:InputDirectory abstract val externalPackages: DirectoryProperty
    @get:InputFile abstract val stripTool: RegularFileProperty
    @get:OutputDirectory abstract val assetDirectory: DirectoryProperty
    @get:OutputDirectory abstract val packageDirectory: DirectoryProperty
    @get:OutputDirectory abstract val featureDirectory: DirectoryProperty

    @TaskAction fun prepare() {
        listOf(assetDirectory, packageDirectory, featureDirectory).forEach { it.get().asFile.deleteRecursively(); it.get().asFile.mkdirs() }
        for (abi in listOf("armeabi-v7a", "x86")) {
            val stub = featureDirectory.file("$abi/libharness_gpu_unavailable.so").get().asFile
            stub.parentFile.mkdirs()
            nativeDirectory.file("$abi/libharness_gpu_unavailable.so").get().asFile.copyTo(stub, overwrite = true)
            execOps.exec { commandLine(stripTool.get().asFile, "--strip-unneeded", stub) }.assertNormalExitValue()
        }
        for (abi in listOf("arm64-v8a", "x86_64")) {
            val source = nativeDirectory.file("$abi/libggml-vulkan.so").get().asFile
            val library = featureDirectory.file("$abi/libggml-vulkan.so").get().asFile
            library.parentFile.mkdirs()
            val external = externalPackages.orNull?.asFile
            val supplied = external?.let { folder ->
                val manifest = folder.resolve("manifest.json").readText()
                check(manifest.contains("19cce6919992fdffd92e0ac152caaf1e2b8f254e")) { "GPU package revision must match this app's ggml core" }
                val pattern = Regex("\"asset\"\\s*:\\s*\"(gpu-$abi-[a-f0-9]{64}\\.so\\.gz)\"")
                folder.resolve(pattern.findAll(manifest).single().groupValues[1])
            }
            if (supplied == null) {
                check(source.isFile) { "GPU backend for $abi was not built" }
                source.copyTo(library, overwrite = true)
                execOps.exec { commandLine(stripTool.get().asFile, "--strip-unneeded", library) }.assertNormalExitValue()
            } else GZIPInputStream(supplied.inputStream()).use { input -> library.outputStream().use { output ->
                var bytes = 0L; val buffer = ByteArray(65536)
                while (true) { val size = input.read(buffer); if (size < 0) break; bytes += size; check(bytes <= 134217728); output.write(buffer, 0, size) }
            } }
            val digest = MessageDigest.getInstance("SHA-256").digest(library.readBytes()).joinToString("") { "%02x".format(it) }
            val name = "gpu-$abi-$digest.so.gz"
            if (supplied == null) GZIPOutputStream(packageDirectory.file(name).get().asFile.outputStream()).use { output -> library.inputStream().use { it.copyTo(output) } }
            else { check(supplied.name == name) { "GPU package hash does not match its filename" }; supplied.copyTo(packageDirectory.file(name).get().asFile, overwrite = true) }
            val manifest = assetDirectory.file("gpu-runtime/$abi.json").get().asFile
            manifest.parentFile.mkdirs()
            manifest.writeText("""{"schema":1,"abi":"$abi","sha256":"$digest","bytes":${library.length()},"asset":"$name","downloadBytes":${packageDirectory.file(name).get().asFile.length()}}""")
        }
    }
}

if (hasNdk) androidComponents.onVariants { variant ->
    val capital = variant.name.replaceFirstChar { it.uppercaseChar() }
    val sdk = githubBuildProperties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    val strip = fileTree("$sdk/ndk/$pinnedNdk/toolchains/llvm/prebuilt") { include("*/bin/llvm-strip", "*/bin/llvm-strip.exe") }.singleFile
    val prepare = tasks.register<PrepareGpuRuntime>("prepareGpuRuntime$capital") {
        dependsOn(tasks.matching { it.name == "externalNativeBuild$capital" })
        nativeDirectory.set(layout.buildDirectory.dir("generated/gpuNative/${if (variant.buildType == "debug") "Debug" else "RelWithDebInfo"}"))
        providers.gradleProperty("gpuRuntimePackages").orNull?.let { externalPackages.set(file(it)) }
        stripTool.set(strip)
        assetDirectory.set(layout.buildDirectory.dir("generated/gpuDelivery/${variant.name}/assets"))
        packageDirectory.set(layout.buildDirectory.dir("outputs/gpuRuntime/${variant.name}"))
        featureDirectory.set(layout.buildDirectory.dir("generated/gpuDelivery/${variant.name}/jniLibs"))
    }
    // Play authenticates native splits itself; its base APK has no download manifest.
    if (distributionChannel == "github") variant.sources.assets?.addGeneratedSourceDirectory(prepare, PrepareGpuRuntime::assetDirectory)
}
