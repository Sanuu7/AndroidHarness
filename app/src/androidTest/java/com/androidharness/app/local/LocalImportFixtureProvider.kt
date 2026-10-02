package com.androidharness.app.local

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

/** A real document provider with unknown file sizes, like some cloud file managers. */
class LocalImportFixtureProvider : DocumentsProvider() {
    private val files = listOf("config.json", "tokenizer.json", "tokenizer_config.json", "model.safetensors")
    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS)
    private lateinit var root: File

    override fun onCreate(): Boolean {
        val context = requireNotNull(context)
        root = File(context.cacheDir, "local-import-fixture").apply { mkdirs() }
        files.forEach { name -> context.assets.open("local-import-fixture/$name").use { input ->
            File(root, name).outputStream().use { output -> input.copyTo(output) }
        } }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)
        return MatrixCursor(cols).apply { newRow().apply {
            add(Root.COLUMN_ROOT_ID, "fixture"); add(Root.COLUMN_DOCUMENT_ID, "fixture")
            add(Root.COLUMN_TITLE, "Local import test"); add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
        } }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: columns).apply { addDocument(documentId) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        require(parentDocumentId == "fixture")
        return MatrixCursor(projection ?: columns).apply { files.forEach { addDocument(it) } }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String) = parentDocumentId == "fixture" && documentId in files

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        require(mode == "r" && documentId in files)
        return ParcelFileDescriptor.open(File(root, documentId), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun MatrixCursor.addDocument(id: String) {
        require(id == "fixture" || id in files)
        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id); add(Document.COLUMN_DISPLAY_NAME, id)
            add(Document.COLUMN_MIME_TYPE, if (id == "fixture") Document.MIME_TYPE_DIR else "application/octet-stream")
            add(Document.COLUMN_SIZE, null); add(Document.COLUMN_FLAGS, 0)
        }
    }
}
