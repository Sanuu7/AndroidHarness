package com.androidharness.app.data.env

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** One downloadable archive from Google's SDK repository. */
internal data class SdkArchive(val file: String, val bytes: Long, val sha1: String)

internal object AndroidSdkRepository {
    const val BASE_URL = "https://dl.google.com/android/repository/"
    const val LISTING = "repository2-3.xml"
    val SUPPORTED_API = 26..36
    const val DEFAULT_API = 35

    private val archiveName = Regex("[A-Za-z0-9._-]+\\.zip")

    /**
     * Finds the platform archive for [api] in the repository listing. The listing is
     * untrusted input: only a plain zip name is accepted, so it can never point the
     * download at another host or path.
     */
    fun platformArchive(listing: String, api: Int): SdkArchive? {
        val block = Regex("<remotePackage path=\"platforms;android-$api\">(.*?)</remotePackage>", RegexOption.DOT_MATCHES_ALL)
            .find(listing)?.groupValues?.get(1) ?: return null
        val complete = Regex("<complete>(.*?)</complete>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1) ?: return null
        val size = Regex("<size>(\\d+)</size>").find(complete)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val sha1 = Regex("<checksum type=\"sha1\">([0-9a-fA-F]{40})</checksum>").find(complete)?.groupValues?.get(1) ?: return null
        val file = Regex("<url>([^<]+)</url>").find(complete)?.groupValues?.get(1)?.trim() ?: return null
        if (!archiveName.matches(file) || size <= 0) return null
        return SdkArchive(file, size, sha1.lowercase())
    }

    fun androidJar(sdkRoot: File, api: Int) = File(sdkRoot, "platforms/android-$api/android.jar")
}

/**
 * Installs the one SDK file the on-device tools cannot get from the Linux package
 * repository: android.jar. Everything else the build needs is a normal package.
 */
internal class AndroidSdkInstaller(
    private val sdkRoot: File,
    private val workDir: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(),
    private val baseUrl: String = AndroidSdkRepository.BASE_URL,
) {
    fun installed(api: Int): Boolean = AndroidSdkRepository.androidJar(sdkRoot, api).let { it.isFile && it.length() > MIN_JAR_BYTES }

    /** Returns the installed android.jar. [onProgress] gets downloaded and total bytes. */
    fun installPlatform(api: Int, onProgress: (Long, Long?) -> Unit = { _, _ -> }): File {
        require(api in AndroidSdkRepository.SUPPORTED_API) { "Android platform $api is not supported. Use ${AndroidSdkRepository.SUPPORTED_API}." }
        val jar = AndroidSdkRepository.androidJar(sdkRoot, api)
        if (installed(api)) return jar

        val listing = fetchText(baseUrl + AndroidSdkRepository.LISTING)
        val archive = AndroidSdkRepository.platformArchive(listing, api)
            ?: throw IllegalStateException("Google's SDK repository has no Android platform $api.")

        workDir.mkdirs()
        val zip = File(workDir, archive.file)
        try {
            download(baseUrl + archive.file, zip, archive, onProgress)
            extractJar(zip, "android-", jar)
        } finally {
            zip.delete()
        }
        check(installed(api)) { "android.jar was not extracted from ${archive.file}." }
        return jar
    }

    private fun fetchText(url: String): String = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
        check(response.isSuccessful) { "HTTP ${response.code} fetching the SDK listing." }
        checkNotNull(response.body) { "Empty SDK listing." }.string()
    }

    private fun download(url: String, dest: File, archive: SdkArchive, onProgress: (Long, Long?) -> Unit) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} downloading ${archive.file}." }
            val body = checkNotNull(response.body) { "Empty download." }
            val total = body.contentLength().takeIf { it > 0 } ?: archive.bytes
            val digest = MessageDigest.getInstance("SHA-1")
            var read = 0L
            var lastReport = 0L
            onProgress(0, total)
            dest.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        check(read <= archive.bytes * 2) { "${archive.file} is larger than the repository lists." }
                        val now = System.nanoTime()
                        if (now - lastReport >= 100_000_000L) { onProgress(read, total); lastReport = now }
                    }
                }
            }
            onProgress(read, total)
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != archive.sha1) {
                dest.delete()
                throw IllegalStateException("Checksum mismatch for ${archive.file}. Nothing was installed.")
            }
        }
    }

    /** Pulls only `<dir>/android.jar` out of the platform zip, atomically. */
    private fun extractJar(zip: File, dirPrefix: String, target: File) {
        target.parentFile!!.mkdirs()
        val temp = File(target.parentFile, "android.jar.part")
        var found = false
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val parts = entry.name.split('/')
                if (entry.isDirectory || parts.size != 2 || !parts[0].startsWith(dirPrefix) || parts[1] != "android.jar") continue
                temp.outputStream().use { out -> input.copyTo(out) }
                found = true
                break
            }
        }
        check(found) { "The platform archive has no android.jar." }
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IllegalStateException("Could not place android.jar at ${target.path}.")
        }
    }

    private companion object {
        const val MIN_JAR_BYTES = 1_000_000L
    }
}
