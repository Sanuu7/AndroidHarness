package com.androidharness.app.workspace

import android.os.Environment
import android.provider.DocumentsContract
import android.net.Uri
import java.io.File

/**
 * Maps a SAF tree uri to its real filesystem path when the picked folder
 * lives on shared storage (internal storage or an SD/USB volume). That lets
 * the picker upgrade a "file tools only" SAF workspace into a full-shell one.
 * Returns null for providers that don't correspond to a real path
 * (cloud providers, Recents, etc.).
 */
object SafPathResolver {

    fun resolve(treeUri: Uri): String? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return null
        return resolveDocumentId(treeUri.authority, docId, Environment.getExternalStorageDirectory().absolutePath)
    }

    internal fun resolveDocumentId(authority: String?, docId: String, primaryRoot: String): String? =
        when (authority) {
            "com.android.externalstorage.documents" -> resolveExternal(docId, primaryRoot)
            "com.android.providers.downloads.documents" -> when {
                docId == "downloads" -> File(primaryRoot, "Download").absolutePath
                // Android 9's Downloads provider uses raw:/storage/... tree IDs.
                docId.startsWith("raw:") -> File(docId.removePrefix("raw:"))
                    .takeIf { it.isAbsolute }?.path
                else -> resolveExternal(docId, primaryRoot)
            }
            else -> null
        }

    /** "primary:Download/foo" → /storage/emulated/0/Download/foo; "0F1C-2A3D:x" → /storage/0F1C-2A3D/x. */
    private fun resolveExternal(docId: String, primaryRoot: String): String? {
        val parts = docId.split(':', limit = 2)
        val volume = parts[0]
        val rel = parts.getOrElse(1) { "" }
        return when {
            volume.equals("primary", ignoreCase = true) ||
                volume.equals("home", ignoreCase = true) ->
                primaryRoot +
                    if (rel.isEmpty()) "" else "/$rel"
            volume.isNotBlank() ->
                "/storage/$volume" + if (rel.isEmpty()) "" else "/$rel"
            else -> null
        }
    }
}
