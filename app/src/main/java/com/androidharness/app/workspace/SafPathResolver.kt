package com.androidharness.app.workspace

import android.os.Environment
import android.provider.DocumentsContract
import android.net.Uri

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
        return when (treeUri.authority) {
            "com.android.externalstorage.documents" -> resolveExternal(docId)
            "com.android.providers.downloads.documents" ->
                if (docId == "downloads") {
                    java.io.File(Environment.getExternalStorageDirectory(), "Download").absolutePath
                } else {
                    resolveExternal(docId)
                }
            else -> null
        }
    }

    /** "primary:Download/foo" → /storage/emulated/0/Download/foo; "0F1C-2A3D:x" → /storage/0F1C-2A3D/x. */
    private fun resolveExternal(docId: String): String? {
        val parts = docId.split(':', limit = 2)
        val volume = parts[0]
        val rel = parts.getOrElse(1) { "" }
        return when {
            volume.equals("primary", ignoreCase = true) ||
                volume.equals("home", ignoreCase = true) ->
                Environment.getExternalStorageDirectory().absolutePath +
                    if (rel.isEmpty()) "" else "/$rel"
            volume.isNotBlank() ->
                "/storage/$volume" + if (rel.isEmpty()) "" else "/$rel"
            else -> null
        }
    }
}
