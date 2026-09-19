buildscript {
    dependencies {
        constraints {
            // Everything AGP pulls in for its own tooling: crypto for apksig,
            // HTTP for the SDK loader, XML for proguard, and the rest. None of
            // it reaches the app, so lifting these touches no shipped code.
            listOf(
                "org.bouncycastle:bcprov-jdk18on:1.85" to "CVE-2026-8763 and the 1.85 cert name-constraints bypass",
                "org.bouncycastle:bcpkix-jdk18on:1.85" to "aligned with bcprov",
                "org.bouncycastle:bcutil-jdk18on:1.85" to "aligned with bcprov",
                "org.jdom:jdom2:2.0.6.1" to "CVE-2021-33813",
                "org.bitbucket.b_c:jose4j:0.9.6" to "CVE-2024-29371",
                "org.apache.httpcomponents:httpclient:4.5.14" to "CVE-2020-13956",
                "org.apache.commons:commons-lang3:3.18.0" to "CVE-2025-48924",
            ).forEach { (gav, cve) ->
                add("classpath", gav) { because(cve) }
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
