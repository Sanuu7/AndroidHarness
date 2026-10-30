package com.androidharness.app.tools

import com.androidharness.app.data.env.AndroidSdkInstaller
import com.androidharness.app.data.env.AndroidSdkRepository
import com.androidharness.app.data.env.PackageInstallPhase
import com.androidharness.app.data.env.PackageInstallStatus
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AndroidSdkToolsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun listing(api: Int, file: String, size: Long, sha1: String) = """
        <sdk:sdk-repository>
          <remotePackage path="platforms;android-$api">
            <archives><archive><complete>
              <size>$size</size>
              <checksum type="sha1">$sha1</checksum>
              <url>$file</url>
            </complete></archive></archives>
          </remotePackage>
        </sdk:sdk-repository>
    """.trimIndent()

    private fun platformZip(api: Int, jarBytes: Int = 1_200_000): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("android-$api/source.properties")); zip.write("Pkg.Revision=2".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("android-$api/data/res/extra/android.jar")); zip.write(ByteArray(10)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("android-$api/android.jar")); zip.write(ByteArray(jarBytes) { 7 }); zip.closeEntry()
        }
    }.toByteArray()

    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun client(routes: Map<String, ByteArray>, requested: MutableList<String> = mutableListOf()) = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url.toString()
        requested += url
        val body = routes[url]
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(if (body == null) 404 else 200).message("fixture")
            .body((body ?: ByteArray(0)).toResponseBody()).build()
    }.build()

    @Test fun `listing yields the platform zip with its size and checksum`() {
        val archive = AndroidSdkRepository.platformArchive(listing(35, "platform-35_r02.zip", 64273788, "0BB560A90A7A2CBD0DD8348224D518B638FE7949"), 35)
        assertNotNull(archive)
        assertEquals("platform-35_r02.zip", archive!!.file)
        assertEquals(64273788L, archive.bytes)
        assertEquals("0bb560a90a7a2cbd0dd8348224d518b638fe7949", archive.sha1)
        assertNull(AndroidSdkRepository.platformArchive(listing(35, "platform-35_r02.zip", 1, "0".repeat(40)), 34))
    }

    @Test fun `a listing cannot redirect the download to another host or path`() {
        for (bad in listOf("https://evil.example/platform.zip", "../platform.zip", "a/b.zip", "platform.sh", "platform.zip?x=1")) {
            assertNull(bad, AndroidSdkRepository.platformArchive(listing(35, bad, 100, "a".repeat(40)), 35))
        }
        assertNull(AndroidSdkRepository.platformArchive(listing(35, "ok.zip", 100, "not-a-hash"), 35))
    }

    @Test fun `installer downloads verifies and keeps only android jar`() {
        val zip = platformZip(35)
        val requested = mutableListOf<String>()
        val http = client(mapOf(
            AndroidSdkRepository.BASE_URL + AndroidSdkRepository.LISTING to listing(35, "platform-35_r02.zip", zip.size.toLong(), sha1(zip)).toByteArray(),
            AndroidSdkRepository.BASE_URL + "platform-35_r02.zip" to zip,
        ), requested)
        val sdk = temp.newFolder("sdk"); val work = temp.newFolder("work")
        val progress = mutableListOf<Pair<Long, Long?>>()
        val jar = AndroidSdkInstaller(sdk, work, http).installPlatform(35) { done, total -> progress += done to total }

        assertEquals(File(sdk, "platforms/android-35/android.jar"), jar)
        assertEquals(1_200_000L, jar.length())
        assertEquals("Only the platform's own android.jar belongs in the SDK", listOf("android.jar"), jar.parentFile!!.list()!!.toList())
        assertEquals(zip.size.toLong(), progress.last().first)
        assertFalse("The archive is removed after extraction", File(work, "platform-35_r02.zip").exists())

        requested.clear()
        AndroidSdkInstaller(sdk, work, http).installPlatform(35)
        assertTrue("A present android.jar needs no network", requested.isEmpty())
    }

    @Test fun `checksum mismatch installs nothing`() {
        val zip = platformZip(35)
        val http = client(mapOf(
            AndroidSdkRepository.BASE_URL + AndroidSdkRepository.LISTING to listing(35, "platform-35_r02.zip", zip.size.toLong(), "0".repeat(40)).toByteArray(),
            AndroidSdkRepository.BASE_URL + "platform-35_r02.zip" to zip,
        ))
        val sdk = temp.newFolder("sdk"); val work = temp.newFolder("work")
        val error = runCatching { AndroidSdkInstaller(sdk, work, http).installPlatform(35) }.exceptionOrNull()
        assertTrue(error?.message, error?.message?.contains("Checksum mismatch") == true)
        assertFalse(File(sdk, "platforms/android-35/android.jar").exists())
        assertFalse(File(work, "platform-35_r02.zip").exists())
    }

    @Test fun `unsupported platforms are rejected before any download`() {
        val requested = mutableListOf<String>()
        val installer = AndroidSdkInstaller(temp.newFolder("sdk"), temp.newFolder("work"), client(emptyMap(), requested))
        assertTrue(runCatching { installer.installPlatform(12) }.isFailure)
        assertTrue(requested.isEmpty())
    }

    @Test fun `plan defaults to the base toolchain and adds gradle on request`() {
        val base = AndroidSdkInstallTool.plan(buildJsonObject {})
        assertEquals(35, base.api)
        assertEquals(listOf("openjdk-21", "d8", "apksigner", "aapt2"), base.packages)
        val gradle = AndroidSdkInstallTool.plan(buildJsonObject { put("platform", 34); put("gradle", true) })
        assertEquals(34, gradle.api)
        assertEquals("gradle", gradle.packages.last())
        assertTrue(runCatching { AndroidSdkInstallTool.plan(buildJsonObject { put("platform", 99) }) }.isFailure)
    }

    @Test fun `approval preview names the packages the download and the license`() {
        val preview = AndroidSdkInstallTool.preview("""{"platform":35,"gradle":true,"reason":"build the app"}""")!!
        assertTrue(preview, preview.contains("gradle"))
        assertTrue(preview, preview.contains("dl.google.com"))
        assertTrue(preview, preview.contains("License"))
        assertTrue(preview, preview.contains("build the app"))
        assertNull(AndroidSdkInstallTool.preview("""{"platform":3}"""))
    }

    @Test fun `tool installs packages then the platform and reports progress through one card`() = runBlocking {
        val calls = mutableListOf<String>()
        val statuses = mutableListOf<PackageInstallStatus>()
        val tool = AndroidSdkInstallTool(
            isReady = { true },
            installedPackages = { listOf("bash") },
            installPackages = { packages, _, progress ->
                calls += "packages:${packages.joinToString(",")}"
                progress(PackageInstallStatus(packages, PackageInstallPhase.DOWNLOADING, currentPackage = "d8"))
            },
            installPlatform = { api, progress ->
                calls += "platform:$api"
                progress(10, 100)
                File(temp.newFolder("jar"), "android.jar").apply { writeBytes(ByteArray(2_000_000)) }
            },
            afterInstall = { calls += "invalidate" },
        )
        val workspace = FileFs(temp.newFolder("ws"))
        val result = tool.execute(buildJsonObject { put("platform", 35) }, ToolContext(workspace, onPackageProgress = { statuses += it }))

        assertTrue(result.output, result.ok)
        assertEquals(listOf("packages:openjdk-21,d8,apksigner,aapt2", "platform:35", "invalidate"), calls)
        val platform = statuses.last()
        assertEquals(PackageInstallPhase.DOWNLOADING, platform.phase)
        assertEquals("Android SDK platform 35", platform.currentPackage)
        assertEquals(10L, platform.downloadedBytes)
        assertEquals(100L, platform.totalBytes)
        assertEquals(listOf("openjdk-21", "d8", "apksigner", "aapt2"), platform.completed)
        assertTrue(result.output, result.output.contains("ANDROID_JAR=\$HOME/android-sdk/platforms/android-35/android.jar"))
    }

    @Test fun `a failed download reports failure and skips the deploy refresh`() = runBlocking {
        var refreshed = false
        val tool = AndroidSdkInstallTool(
            isReady = { true }, installedPackages = { emptyList() },
            installPackages = { _, _, _ -> },
            installPlatform = { _, _ -> error("Checksum mismatch for platform-35_r02.zip. Nothing was installed.") },
            afterInstall = { refreshed = true },
        )
        val result = tool.execute(JsonObject(emptyMap()), ToolContext(FileFs(temp.newFolder("ws"))))
        assertFalse(result.ok)
        assertTrue(result.output, result.output.contains("Checksum mismatch"))
        assertFalse(refreshed)
    }

    @Test fun `tool refuses before the Linux environment exists`() = runBlocking {
        val tool = AndroidSdkInstallTool(
            isReady = { false }, installedPackages = { emptyList() },
            installPackages = { _, _, _ -> error("must not run") },
            installPlatform = { _, _ -> error("must not run") },
        )
        val result = tool.execute(JsonObject(mapOf("platform" to JsonPrimitive(35))), ToolContext(FileFs(temp.newFolder("ws"))))
        assertFalse(result.ok)
        assertTrue(result.output, result.output.contains("not ready"))
    }
}
