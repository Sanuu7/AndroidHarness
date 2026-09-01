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
        versionCode = 8
        versionName = "0.6-alpha"
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
        compose = true
        aidl = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt")
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
    implementation(libs.documentfile)
    // Custom Tabs for the MCP OAuth authorize screen.
    implementation(libs.browser)
    implementation(libs.security.crypto)
    implementation(libs.biometric)
    implementation(libs.fragment.ktx)

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
}
