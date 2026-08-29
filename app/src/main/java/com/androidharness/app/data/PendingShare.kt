package com.androidharness.app.data

import android.net.Uri

/**
 * Payload received from an ACTION_SEND share into the app. The chat composer
 * consumes it once: text lands in the input, an image stream rides the normal
 * attach pipeline.
 */
data class PendingShare(
    val mime: String,
    val text: String? = null,
    val stream: Uri? = null,
)
