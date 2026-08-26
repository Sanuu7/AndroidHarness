package com.androidharness.app.tools

/**
 * TLS trust for the shell tier (Bug 1): the Termux-prefix userspace has no
 * CA bundle wired up (/etc/ssl is empty and no env var points at one), so
 * curl/python/node stall on certificate verification while plain HTTP works.
 *
 * The app ships Mozilla's CA bundle as an APK asset. [ensureInstalled]
 * materializes it once per build into the toolchain prefix, where every
 * spawned shell can read it, and [envVars] returns the standard variables
 * that point each TLS stack at it:
 *
 *  - SSL_CERT_FILE / CURL_CA_BUNDLE: curl, OpenSSL-based tools
 *  - REQUESTS_CA_BUNDLE / SSL_CERT_FILE: python requests + urllib/ssl
 *  - GIT_SSL_CAINFO: git https clones
 *  - NODE_EXTRA_CA_CERTS: node
 *  - JAVAX_NET_SSL_TRUSTSTORE: java/jdk tools expecting a JKS keystore,
 *    provisioned separately by EnvStatusTool/bootstrap when a JDK exists
 */
object NetTls {

    /** Asset path of the PEM bundle inside the APK. */
    const val ASSET_PATH = "net/mozilla-ca-bundle.pem"

    /** On-device location inside the toolchain prefix. */
    const val BUNDLE_RELATIVE_PATH = "etc/tls/cacert.pem"

    /**
     * Copies the bundled asset into the prefix when missing or stale.
     * Idempotent; safe to call from any thread before shell launches.
     */
    fun ensureInstalled(prefixDir: java.io.File, context: android.content.Context) {
        ensureInstalled(prefixDir) { context.assets.open(ASSET_PATH) }
    }

    /**
     * Core implementation with an injectable asset opener so JVM unit tests
     * can run it without an Android device.
     */
    fun ensureInstalled(prefixDir: java.io.File, openAsset: () -> java.io.InputStream) {
        val target = java.io.File(prefixDir, BUNDLE_RELATIVE_PATH)
        try {
            val expected = checksum(
                openAsset().use { input ->
                    // Hash first without keeping bytes around for large bundles
                    val md = java.security.MessageDigest.getInstance("SHA-256")
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                    }
                    md.digest()
                },
            )
            if (target.isFile && target.length() > 0 && checksumOf(target) == expected) return

            target.parentFile?.mkdirs()
            openAsset().use { input ->
                java.io.FileOutputStream(target).use { out -> input.copyTo(out) }
            }
        } catch (_: Exception) {
            // No asset or unwritable prefix: leave whatever exists in place;
            // callers still export the vars and the system store may work.
        }
    }

    private fun checksumOf(f: java.io.File): String = try {
        checksum(
            f.inputStream().use { input ->
                val md = java.security.MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
                md.digest()
            },
        )
    } catch (_: Exception) {
        ""
    }

    private fun checksum(digest: ByteArray): String =
        digest.joinToString("") { "%02x".format(it) }

    /**
     * Standard TLS variables pointing at [bundlePath]. Exported by every
     * shell tier so all clients verify against the same anchors by default.
     */
    fun envVars(bundlePath: String): Map<String, String> = mapOf(
        "SSL_CERT_FILE" to bundlePath,
        "CURL_CA_BUNDLE" to bundlePath,
        "REQUESTS_CA_BUNDLE" to bundlePath,
        "GIT_SSL_CAINFO" to bundlePath,
        "NODE_EXTRA_CA_CERTS" to bundlePath,
    )
}
