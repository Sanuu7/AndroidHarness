package com.androidharness.app.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens the OAuth authorize URL in a Custom Tab so the user stays inside the
 * app's visual context instead of handing the URL (client_id, PKCE challenge,
 * state) to whatever app claims the default browser role. Browsers without
 * Custom Tabs support just receive the ACTION_VIEW fallback, and when no
 * browser resolves at all the caller's runCatching shows the error inline.
 */
fun openOAuthBrowser(context: Context, url: Uri) {
    try {
        CustomTabsIntent.Builder().build().launchUrl(context, url)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, url))
    }
}
