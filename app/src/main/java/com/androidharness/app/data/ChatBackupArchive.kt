package com.androidharness.app.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One chat as listed in the manifest, so a restore can be previewed without reading any messages. */
@Serializable
data class BackupChatInfo(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: Int,
    val projectId: String? = null,
    val workspaceSelectionJson: String? = null,
)

@Serializable
data class BackupManifest(
    val format: String = ChatBackupArchive.FORMAT,
    val version: Int = ChatBackupArchive.VERSION,
    val exportedAt: Long = 0,
    val chats: List<BackupChatInfo> = emptyList(),
    val workspaces: List<BackupWorkspace> = emptyList(),
    val memories: Int = 0,
    val snippets: Int = 0,
    val memoryProjects: List<String> = emptyList(),
    /** The user-made projects the chats are filed under. */
    val workProjects: List<BackupWorkProject> = emptyList(),
)

@Serializable
data class BackupWorkProject(
    val id: String,
    val name: String,
    val selectionJson: String,
    val createdAt: Long,
    val chatNamePattern: String? = null,
)

/**
 * Zip backup written and read one entry at a time, so memory use stays flat no matter how many
 * chats a phone holds. Layout: manifest.json first, then chats/NNNNN.json, memories.json,
 * snippets.json, and the image files chats point at under images/ and images/originals/.
 */
@OptIn(ExperimentalSerializationApi::class)
object ChatBackupArchive {
    const val FORMAT = "androidharness-backup"
    const val VERSION = 6
    const val EXTENSION = "harnessbackup"
    const val MANIFEST = "manifest.json"
    private const val MEMORIES = "memories.json"
    private const val SNIPPETS = "snippets.json"
    private const val MAX_JSON_ENTRY = 512L * 1024 * 1024
    private const val MAX_IMAGE_ENTRY = 64L * 1024 * 1024
    private const val MAX_ENTRIES = 200_000
    private val CHAT_ENTRY = Regex("chats/\\d{1,6}\\.json")

    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** A zip starts with the local file header signature "PK\u0003\u0004". */
    fun isArchive(header: ByteArray): Boolean = header.size >= 4 && header[0] == 'P'.code.toByte() &&
        header[1] == 'K'.code.toByte() && header[2] == 3.toByte() && header[3] == 4.toByte()

    /** Image file names a message points at; only plain names, never paths. */
    fun imageNames(imagesJson: String): List<String> = runCatching {
        (json.parseToJsonElement(imagesJson) as? JsonArray).orEmpty().mapNotNull { item ->
            ((item as? JsonObject)?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
        }.filter(::safeName)
    }.getOrDefault(emptyList())

    fun safeName(name: String): Boolean = name.isNotBlank() && name.length <= 255 && !name.startsWith('.') &&
        '/' !in name && '\\' !in name && name.none { it.isISOControl() }

    class Writer(output: OutputStream) : java.io.Closeable {
        private val zip = ZipOutputStream(output.buffered(256 * 1024)).apply { setLevel(Deflater.BEST_SPEED) }
        private val entry = object : FilterOutputStream(zip) {
            override fun write(b: ByteArray, off: Int, len: Int) = zip.write(b, off, len)
            override fun close() = flush()
        }
        private var chats = 0

        private fun <T> put(name: String, serializer: KSerializer<T>, value: T) {
            zip.putNextEntry(ZipEntry(name))
            json.encodeToStream(serializer, value, entry)
            zip.closeEntry()
        }

        fun manifest(manifest: BackupManifest) = put(MANIFEST, BackupManifest.serializer(), manifest)
        fun chat(session: BackupSession) = put("chats/%05d.json".format(chats++), BackupSession.serializer(), session)
        fun memories(memories: List<BackupMemory>) = put(MEMORIES, ListSerializer(BackupMemory.serializer()), memories)
        fun snippets(snippets: List<BackupSnippet>) = put(SNIPPETS, ListSerializer(BackupSnippet.serializer()), snippets)

        fun image(name: String, original: Boolean, input: InputStream) {
            require(safeName(name)) { "Unsafe image name" }
            zip.putNextEntry(ZipEntry((if (original) "images/originals/" else "images/") + name))
            input.copyTo(zip, 64 * 1024)
            zip.closeEntry()
        }

        override fun close() = zip.close()
    }

    /** Callbacks for [read]; each one sees a single entry, never the whole archive. */
    interface Visitor {
        suspend fun manifest(manifest: BackupManifest): Boolean = true
        suspend fun chat(session: BackupSession) {}
        suspend fun memories(memories: List<BackupMemory>) {}
        suspend fun snippets(snippets: List<BackupSnippet>) {}
        suspend fun image(name: String, original: Boolean, input: InputStream) {}
    }

    /** Throws [ChatBackupException] for anything that is not a backup this app can read. */
    suspend fun readManifest(input: InputStream): BackupManifest {
        var found: BackupManifest? = null
        read(input, object : Visitor {
            override suspend fun manifest(manifest: BackupManifest): Boolean { found = manifest; return false }
        })
        return found ?: throw ChatBackupException("Not a backup file")
    }

    suspend fun read(input: InputStream, visitor: Visitor) {
        val zip = try { ZipInputStream(input.buffered(256 * 1024)) } catch (e: Exception) { throw ChatBackupException("Not a backup file") }
        zip.use {
            var count = 0
            var sawManifest = false
            while (true) {
                val next = try { zip.nextEntry } catch (e: java.util.zip.ZipException) {
                    throw ChatBackupException("This backup file is damaged")
                } ?: break
                if (++count > MAX_ENTRIES) throw ChatBackupException("This backup file has too many entries")
                val name = next.name
                if (!sawManifest) {
                    if (name != MANIFEST) throw ChatBackupException("Not a backup file")
                    sawManifest = true
                    val manifest = decode(zip, BackupManifest.serializer(), "Not a backup file")
                    if (manifest.format != FORMAT) throw ChatBackupException("Not a backup file")
                    if (manifest.version > VERSION) throw ChatBackupException("This backup was written by a newer version of the app")
                    if (!visitor.manifest(manifest)) return
                    continue
                }
                when {
                    next.isDirectory -> Unit
                    CHAT_ENTRY.matches(name) -> visitor.chat(decode(zip, BackupSession.serializer(), "A chat in this backup is damaged"))
                    name == MEMORIES -> visitor.memories(decode(zip, ListSerializer(BackupMemory.serializer()), "Saved memories in this backup are damaged"))
                    name == SNIPPETS -> visitor.snippets(decode(zip, ListSerializer(BackupSnippet.serializer()), "Prompt snippets in this backup are damaged"))
                    name.startsWith("images/originals/") -> name.removePrefix("images/originals/").takeIf(::safeName)
                        ?.let { visitor.image(it, true, Limited(zip, MAX_IMAGE_ENTRY)) }
                    name.startsWith("images/") -> name.removePrefix("images/").takeIf(::safeName)
                        ?.let { visitor.image(it, false, Limited(zip, MAX_IMAGE_ENTRY)) }
                    // Entries from later versions are skipped rather than rejected.
                    else -> Unit
                }
            }
            if (!sawManifest) throw ChatBackupException("Not a backup file")
        }
    }

    private fun <T> decode(zip: ZipInputStream, serializer: KSerializer<T>, failure: String): T = try {
        json.decodeFromStream(serializer, Limited(zip, MAX_JSON_ENTRY))
    } catch (e: ChatBackupException) { throw e } catch (e: OutOfMemoryError) {
        throw ChatBackupException("A chat in this backup is too large for this phone's memory")
    } catch (e: Exception) { throw ChatBackupException(failure) }

    /** Reads at most [limit] bytes of the current entry and never closes the zip underneath. */
    private class Limited(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var read = 0L
        private fun count(n: Int): Int {
            if (n > 0) { read += n; if (read > limit) throw ChatBackupException("An entry in this backup is too large") }
            return n
        }
        override fun read(): Int = super.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = count(super.read(b, off, len))
        override fun close() = Unit
    }
}
