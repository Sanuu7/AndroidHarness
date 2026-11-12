package com.androidharness.app.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.androidharness.app.R
import com.androidharness.app.data.storage.StorageCategory
import com.androidharness.app.data.storage.StorageGap
import com.androidharness.app.data.storage.StorageItem
import com.androidharness.app.data.storage.StorageRole
import com.androidharness.app.data.storage.StorageRole.*

/** Title (for roles without a name of their own) and explanation for each storage role. */
private fun roleText(role: StorageRole): Pair<Int?, Int> = when (role) {
    APP_PACKAGE -> R.string.storage_role_app_package to R.string.storage_role_app_package_note
    CHAT_DATABASE -> R.string.storage_role_chat_database to R.string.storage_role_chat_database_note
    CHAT_IMAGES -> R.string.storage_role_chat_images to R.string.storage_role_chat_images_note
    UNDO_CHECKPOINTS -> R.string.storage_role_undo to R.string.storage_role_undo_note
    FILE_HISTORY -> R.string.storage_role_file_history to R.string.storage_role_file_history_note
    EDITOR_DRAFTS -> R.string.storage_role_editor to R.string.storage_role_editor_note
    CANVAS -> R.string.storage_role_canvas to R.string.storage_role_canvas_note
    SETTINGS -> R.string.storage_role_settings to R.string.storage_role_settings_note
    SCRATCH -> R.string.storage_role_scratch to R.string.storage_role_scratch_note
    ROUTER -> R.string.storage_role_router to R.string.storage_role_router_note
    TELEGRAM -> R.string.storage_role_telegram to R.string.storage_role_telegram_note
    WEB_DATA -> R.string.storage_role_web_data to R.string.storage_role_web_data_note
    BACKGROUND_WORK -> R.string.storage_role_background_work to R.string.storage_role_background_work_note
    OTHER_APP_DATA -> R.string.storage_role_other_app_data to R.string.storage_role_other_app_data_note
    PLUGIN -> null to R.string.storage_role_plugin_note
    PLUGIN_LEFTOVERS -> R.string.storage_role_plugin_leftovers to R.string.storage_role_plugin_leftovers_note
    PLUGIN_REGISTRY -> R.string.storage_role_plugin_registry to R.string.storage_role_plugin_registry_note
    SKILL_USER -> null to R.string.storage_role_skill_user_note
    SKILL_PROJECT -> null to R.string.storage_role_skill_project_note
    SKILL_BUNDLED -> null to R.string.storage_role_skill_bundled_note
    WORKSPACE -> null to R.string.storage_role_workspace_note
    OTHER_REPOSITORIES -> R.string.storage_role_other_repos to R.string.storage_role_other_repos_note
    LINUX_PACKAGE -> null to R.string.storage_role_linux_package_note
    LINUX_UNTRACKED_PACKAGE -> null to R.string.storage_role_linux_untracked_note
    LINUX_SHARED_FILES -> R.string.storage_role_linux_shared to R.string.storage_role_linux_shared_note
    NPM_GLOBAL -> null to R.string.storage_role_npm_note
    PIP_PACKAGE -> null to R.string.storage_role_pip_note
    CODEGRAPH_RUNTIME -> R.string.storage_role_codegraph to R.string.storage_role_codegraph_note
    LINUX_HOME -> R.string.storage_role_linux_home to R.string.storage_role_linux_home_note
    LINUX_TMP -> R.string.storage_role_linux_tmp to R.string.storage_role_linux_tmp_note
    LINUX_OTHER -> R.string.storage_role_linux_other to R.string.storage_role_linux_other_note
    PACKAGE_RECORDS -> R.string.storage_role_package_records to R.string.storage_role_package_records_note
    LINUX_STAGING -> R.string.storage_role_staging to R.string.storage_role_staging_note
    SHELL_TIER_COPY -> R.string.storage_role_shell_tier to R.string.storage_role_shell_tier_note
    MODEL -> null to R.string.storage_role_model_note
    MODEL_PARTIAL -> null to R.string.storage_role_model_partial_note
    MODEL_OTHER -> R.string.storage_role_model_other to R.string.storage_role_model_other_note
    GPU_RUNTIME -> R.string.storage_role_gpu to R.string.storage_role_gpu_note
    CACHE_NPM -> R.string.storage_role_cache_npm to R.string.storage_role_cache_shared_note
    CACHE_PIP -> R.string.storage_role_cache_pip to R.string.storage_role_cache_shared_note
    CACHE_PACKAGE_OTHER -> R.string.storage_role_cache_package_other to R.string.storage_role_cache_package_other_note
    CACHE_GRADLE -> R.string.storage_role_cache_gradle to R.string.storage_role_cache_gradle_note
    CACHE_GRADLE_WRAPPER -> R.string.storage_role_cache_gradle_wrapper to R.string.storage_role_cache_gradle_wrapper_note
    CACHE_APT -> R.string.storage_role_cache_apt to R.string.storage_role_cache_apt_note
    CACHE_HOME_OTHER -> R.string.storage_role_cache_home_other to R.string.storage_role_cache_home_other_note
    CACHE_DEB -> R.string.storage_role_cache_deb to R.string.storage_role_cache_deb_note
    CACHE_PLUGIN_PREVIEWS -> R.string.storage_role_cache_previews to R.string.storage_role_cache_previews_note
    CACHE_SHARE -> R.string.storage_role_cache_share to R.string.storage_role_cache_share_note
    CACHE_REMOTE_IMAGES -> R.string.storage_role_cache_images to R.string.storage_role_cache_images_note
    CACHE_AUDIO -> R.string.storage_role_cache_audio to R.string.storage_role_cache_audio_note
    CACHE_MCP_LOGS -> R.string.storage_role_cache_mcp to R.string.storage_role_cache_mcp_note
    CACHE_MODEL_CATALOG -> R.string.storage_role_cache_model_catalog to R.string.storage_role_cache_model_catalog_note
    CACHE_APP_OTHER -> R.string.storage_role_cache_other to R.string.storage_role_cache_other_note
}

internal fun storageTitle(context: Context, item: StorageItem): String =
    roleText(item.role).first?.takeUnless { item.role.named && item.name.isNotBlank() }?.let(context::getString)
        ?: item.name.ifBlank { item.key }

internal fun storageNote(context: Context, item: StorageItem): String = context.getString(roleText(item.role).second)

@StringRes internal fun storageGapText(gap: StorageGap): Int = when (gap) {
    StorageGap.PERMISSION_DENIED -> R.string.storage_gap_permission
    StorageGap.SAF_FOLDER -> R.string.storage_gap_saf
    StorageGap.SSH_HOST -> R.string.storage_gap_ssh
    StorageGap.MISSING -> R.string.storage_gap_missing
    StorageGap.TIMED_OUT -> R.string.storage_gap_timed_out
    StorageGap.SCAN_ERROR -> R.string.storage_gap_error
    StorageGap.REGISTRY_UNREADABLE -> R.string.storage_gap_registry
    StorageGap.UNTRACKED -> R.string.storage_untracked_reason
    StorageGap.SAME_FOLDER, StorageGap.INSIDE_APP_PACKAGE -> R.string.storage_gap_error
}

@StringRes internal fun storageCategoryName(category: StorageCategory): Int = when (category) {
    StorageCategory.APP_DATA -> R.string.storage_category_app_data
    StorageCategory.PLUGINS -> R.string.storage_category_plugins
    StorageCategory.SKILLS -> R.string.storage_category_skills
    StorageCategory.WORKSPACES -> R.string.storage_category_workspaces
    StorageCategory.LINUX_PACKAGES -> R.string.storage_category_linux
    StorageCategory.LOCAL_MODELS -> R.string.storage_category_models
    StorageCategory.CACHES -> R.string.storage_category_caches
}

internal fun storageCategoryIcon(category: StorageCategory): ImageVector = when (category) {
    StorageCategory.APP_DATA -> Icons.Rounded.Apps
    StorageCategory.PLUGINS -> Icons.Rounded.Extension
    StorageCategory.SKILLS -> Icons.Rounded.AutoAwesome
    StorageCategory.WORKSPACES -> Icons.Rounded.Folder
    StorageCategory.LINUX_PACKAGES -> Icons.Rounded.Terminal
    StorageCategory.LOCAL_MODELS -> Icons.Rounded.Memory
    StorageCategory.CACHES -> Icons.Rounded.Cached
}
