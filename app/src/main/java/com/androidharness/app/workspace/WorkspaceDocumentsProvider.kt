package com.androidharness.app.workspace

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.androidharness.app.R
import java.io.File
import java.io.FileNotFoundException

/**
 * Lists the app workspace in the system file picker and in file managers that use
 * it (Files, Material Files and others), so files there can be copied and shared even
 * though Android blocks other apps from reading Android/data directly.
 *
 * Only the app workspace folder is exposed, never the rest of the app's storage. The
 * system grants access per pick, so no other app can open this provider on its own.
 */
class WorkspaceDocumentsProvider : DocumentsProvider() {

    private val root: File by lazy {
        val context = requireNotNull(context)
        (context.getExternalFilesDir(null) ?: context.filesDir).resolve("workspace").apply { mkdirs() }.canonicalFile
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val context = requireNotNull(context)
        return MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT_ID)
                .add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
                .add(Root.COLUMN_TITLE, context.getString(R.string.app_name))
                .add(Root.COLUMN_SUMMARY, context.getString(R.string.wp_documents_root_summary))
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY)
                .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                .add(Root.COLUMN_AVAILABLE_BYTES, root.usableSpace)
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addFile(this, documentId, fileFor(documentId)) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val parent = fileFor(parentDocumentId)
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            parent.listFiles().orEmpty().sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).forEach { child ->
                // A symlink that leads out of the workspace is left out, not followed.
                val id = idFor(child) ?: return@forEach
                addFile(this, id, child)
            }
            setNotificationUri(requireNotNull(context).contentResolver, childrenUri(parentDocumentId))
        }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = fileFor(documentId)
        if (file.isDirectory) throw FileNotFoundException("$documentId is a folder")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = runCatching {
        val parent = fileFor(parentDocumentId)
        val child = fileFor(documentId)
        child.path.startsWith(parent.path + File.separator)
    }.getOrDefault(false)

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileFor(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("$parentDocumentId is not a folder")
        val file = freeName(parent, safeName(displayName))
        val made = if (mimeType == Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile()
        if (!made) throw FileNotFoundException("Could not create $displayName")
        changed(parentDocumentId)
        return requireNotNull(idFor(file))
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileFor(documentId)
        if (file == root) throw FileNotFoundException("The workspace folder cannot be renamed")
        val target = File(file.parentFile, safeName(displayName))
        if (target.exists() || !file.renameTo(target)) throw FileNotFoundException("Could not rename to $displayName")
        file.parentFile?.let(::idFor)?.let(::changed)
        return requireNotNull(idFor(target))
    }

    override fun deleteDocument(documentId: String) {
        val file = fileFor(documentId)
        if (file == root) throw FileNotFoundException("The workspace folder cannot be deleted")
        val parentId = file.parentFile?.let(::idFor)
        if (!file.deleteRecursively()) throw FileNotFoundException("Could not delete $documentId")
        parentId?.let(::changed)
    }

    override fun getDocumentType(documentId: String): String = mimeOf(fileFor(documentId))

    /** Maps an id back to a file, refusing anything that resolves outside the workspace (for example through `..` or a symlink). */
    private fun fileFor(documentId: String): File {
        val relative = when {
            documentId == ROOT_ID -> ""
            documentId.startsWith("$ROOT_ID/") -> documentId.removePrefix("$ROOT_ID/")
            else -> throw FileNotFoundException("Unknown document $documentId")
        }
        val file = File(root, relative).canonicalFile
        if (file != root && !file.path.startsWith(root.path + File.separator)) throw FileNotFoundException("Outside the workspace: $documentId")
        if (!file.exists()) throw FileNotFoundException("Missing: $documentId")
        return file
    }

    /** Null when [file] resolves outside the workspace. */
    private fun idFor(file: File): String? {
        val canonical = file.canonicalFile
        return when {
            canonical == root -> ROOT_ID
            canonical.path.startsWith(root.path + File.separator) -> "$ROOT_ID/" + canonical.path.removePrefix(root.path + File.separator)
            else -> null
        }
    }

    private fun childrenUri(parentDocumentId: String) =
        DocumentsContract.buildChildDocumentsUri(requireNotNull(context).packageName + AUTHORITY_SUFFIX, parentDocumentId)

    private fun changed(parentDocumentId: String) =
        requireNotNull(context).contentResolver.notifyChange(childrenUri(parentDocumentId), null)

    private fun addFile(cursor: MatrixCursor, documentId: String, file: File) {
        var flags = 0
        if (file.isDirectory) {
            if (file.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (file.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (file != root && file.parentFile?.canWrite() == true) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, documentId)
            .add(Document.COLUMN_DISPLAY_NAME, if (file == root) requireNotNull(context).getString(R.string.app_name) else file.name)
            .add(Document.COLUMN_MIME_TYPE, mimeOf(file))
            .add(Document.COLUMN_SIZE, if (file.isDirectory) null else file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, flags)
    }

    companion object {
        private const val ROOT_ID = "workspace"
        // Matches android:authorities in the manifest, which is the package name plus this.
        private const val AUTHORITY_SUFFIX = ".workspace.documents"

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_AVAILABLE_BYTES,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        internal fun mimeOf(file: File): String {
            if (file.isDirectory) return Document.MIME_TYPE_DIR
            val extension = file.extension.lowercase()
            return TEXT_EXTENSIONS[extension]
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
        }

        // Scripts and source files mostly have no system MIME type, which makes file
        // managers offer only "open as binary". Calling them text lets editors open them.
        private val TEXT_EXTENSIONS = mapOf(
            "sh" to "text/x-shellscript", "py" to "text/x-python", "kt" to "text/plain", "kts" to "text/plain",
            "js" to "text/javascript", "ts" to "text/plain", "json" to "application/json", "md" to "text/markdown",
            "yml" to "text/plain", "yaml" to "text/plain", "toml" to "text/plain", "env" to "text/plain",
        )

        /** Keeps a name inside its folder: no path separators, no `.` or `..`. */
        internal fun safeName(name: String): String {
            val cleaned = name.replace('/', '_').replace('\u0000', '_').trim()
            if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") throw FileNotFoundException("Invalid name: $name")
            return cleaned
        }

        /** "notes.txt" becomes "notes (1).txt" when the name is taken, as file managers expect. */
        internal fun freeName(parent: File, name: String): File {
            var candidate = File(parent, name)
            if (!candidate.exists()) return candidate
            val dot = name.lastIndexOf('.').takeIf { it > 0 }
            val base = dot?.let { name.substring(0, it) } ?: name
            val ext = dot?.let { name.substring(it) } ?: ""
            var n = 1
            while (candidate.exists()) candidate = File(parent, "$base ($n)$ext").also { n++ }
            return candidate
        }
    }
}
