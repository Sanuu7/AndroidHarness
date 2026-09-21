plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.androidharness.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.androidharness.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "1.1"
        // Instrumented tests drive the real WebView (screenshots, history,
        // promise staging), which no JVM test can exercise.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
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
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt")
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
            }
        }
    }
}

dependencies {
    ksp(libs.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
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

    implementation("com.github.mwiede:jsch:2.28.7")
    // Ed25519 SSH keys on Android versions without a matching platform provider.
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    // In-app code editor (gutter, undo/redo, search engine) for the file manager.
    implementation(libs.sora.editor)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
