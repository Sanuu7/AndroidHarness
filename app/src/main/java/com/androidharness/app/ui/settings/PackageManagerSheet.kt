package com.androidharness.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.env.EnvState
import com.androidharness.app.data.env.PkgMeta
import com.androidharness.app.ui.common.PackageInstallProgress
import com.androidharness.app.ui.common.packageProgress
import com.androidharness.app.ui.theme.HarnessMono
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.IconButton

private val POPULAR_PACKAGES = listOf(
    "ripgrep" to "Ultra-fast line-oriented regex search (rg)",
    "jq" to "Lightweight command-line JSON processor",
    "tree" to "Recursive visual directory listing",
    "tmux" to "Terminal multiplexer with split panes & sessions",
    "curl" to "Command-line tool for transferring data with URLs",
    "clang" to "C and C++ programming language compiler toolchain",
    "rust" to "Rust systems programming language & cargo",
    "htop" to "Interactive process monitor & resource viewer",
    "neovim" to "Extensible Vim-fork modal code editor",
    "openjdk-17" to "Java Development Kit version 17",
    "diffutils" to "GNU diff and cmp utilities",
    "strace" to "System call tracer & debugging utility",
)

/**
 * Manage and search Linux packages from the Termux repository.
 * Allows users to inspect installed tools, discover popular CLI utilities,
 * search the repository closure, and install or remove packages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackageManagerSheet(
    container: AppContainer,
    onDismiss: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val linuxEnv = container.linuxEnv
    val envState by linuxEnv.state.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<PkgMeta>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var installedList by remember { mutableStateOf(linuxEnv.installedPackages()) }
    var requestedPackage by remember { mutableStateOf<String?>(null) }

    val isBusy = requestedPackage != null || envState.packageProgress() != null
    val activePackage = (envState as? EnvState.Downloading)?.pkg ?: (envState as? EnvState.Installing)?.pkg

    fun installPackage(name: String) {
        if (isBusy) return
        requestedPackage = name
        errorMessage = null
        scope.launch {
            try {
                linuxEnv.install(listOf(name)).getOrThrow()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = e.message ?: uiContext.getString(R.string.ui_failed_to_install, (name).toString())
            } finally {
                installedList = linuxEnv.installedPackages()
                requestedPackage = null
            }
        }
    }

    // Refresh after each completed package, without rereading storage for every byte update.
    val packageIndex = (envState as? EnvState.Downloading)?.index ?: (envState as? EnvState.Installing)?.index
    LaunchedEffect(envState is EnvState.Ready, packageIndex) {
        installedList = linuxEnv.installedPackages()
    }

    // Debounced search
    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            searchResults = emptyList()
            searching = false
        } else {
            searching = true
            delay(250)
            searchResults = runCatching { linuxEnv.searchPackages(trimmed) }.getOrDefault(emptyList())
            searching = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Inventory2,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_packages_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        uiContext.getString(R.string.ui_installed_termux_repository, (installedList.size).toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Installation progress indicator
            if (isBusy) {
                Surface(
                    color = scheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        PackageInstallProgress(if (envState.packageProgress() != null) envState else EnvState.Preparing)
                    }
                }
            }

            // Error banner
            errorMessage?.let { err ->
                Surface(
                    color = scheme.errorContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            err,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { errorMessage = null },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.settings_action_dismiss),
                                tint = scheme.error,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                }
            }

            // Search Bar
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.settings_packages_search)) },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = scheme.onSurfaceVariant)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.settings_search_clear), tint = scheme.onSurfaceVariant)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            // Package listings
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 460.dp),
            ) {
                if (query.isNotBlank()) {
                    // Search mode
                    if (searching) {
                        item {
                            Text(
                                uiContext.getString(R.string.ui_searching_repository),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 16.dp),
                            )
                        }
                    } else if (searchResults.isEmpty()) {
                        item {
                            Text(
                                uiContext.getString(R.string.ui_no_packages_found_matching, (query).toString()),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 16.dp),
                            )
                        }
                    } else {
                        items(searchResults, key = { it.name }) { pkg ->
                            PackageItemRow(
                                name = pkg.name,
                                version = pkg.version,
                                description = pkg.description,
                                sizeBytes = pkg.size,
                                isInstalled = pkg.name in installedList,
                                isCore = pkg.name in linuxEnv.fullPackages,
                                isBusy = isBusy,
                                isInstalling = pkg.name == activePackage || pkg.name == requestedPackage,
                                onInstall = { installPackage(pkg.name) },
                                onReviewRemoval = { container.pendingSettingsScroll.value = "storage"; onDismiss() },
                            )
                        }
                    }
                } else {
                    // Default browse mode: Popular Tools
                    item {
                        Text(
                            uiContext.getString(R.string.ui_popular_tools),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                        )
                    }

                    items(POPULAR_PACKAGES, key = { it.first }) { (name, desc) ->
                        PackageItemRow(
                            name = name,
                            version = null,
                            description = desc,
                            sizeBytes = null,
                            isInstalled = name in installedList,
                            isCore = name in linuxEnv.fullPackages,
                            isBusy = isBusy,
                            isInstalling = name == activePackage || name == requestedPackage,
                            onInstall = { installPackage(name) },
                            onReviewRemoval = { container.pendingSettingsScroll.value = "storage"; onDismiss() },
                        )
                    }

                    // Installed Packages section
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            uiContext.getString(R.string.ui_all_installed, (installedList.size).toString()),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }

                    items(installedList.sorted(), key = { "installed-$it" }) { name ->
                        InstalledPackageRow(
                            name = name,
                            isCore = name in linuxEnv.fullPackages,
                            isBusy = isBusy,
                            onReviewRemoval = { container.pendingSettingsScroll.value = "storage"; onDismiss() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PackageItemRow(
    name: String,
    version: String?,
    description: String,
    sizeBytes: Long?,
    isInstalled: Boolean,
    isCore: Boolean,
    isBusy: Boolean,
    isInstalling: Boolean,
    onInstall: () -> Unit,
    onReviewRemoval: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = HarnessMono,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (version != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            version,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    if (sizeBytes != null && sizeBytes > 0) {
                        Spacer(Modifier.width(6.dp))
                        val sizeText = if (sizeBytes >= 1024 * 1024) {
                            "%.1f MB".format(sizeBytes.toDouble() / (1024 * 1024))
                        } else {
                            "${(sizeBytes + 1023) / 1024} KB"
                        }
                        Text(
                            sizeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.outline,
                        )
                    }
                }
                if (description.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            if (isInstalling) {
                Text(stringResource(R.string.settings_packages_installing), style = MaterialTheme.typography.labelMedium, color = scheme.primary)
            } else if (isInstalled) {
                Surface(
                    color = scheme.primaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            uiContext.getString(R.string.ui_installed),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                        )
                    }
                }
                if (!isCore) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = onReviewRemoval,
                        enabled = !isBusy,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Storage,
                            contentDescription = stringResource(R.string.settings_packages_review_storage),
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            } else {
                Button(
                    onClick = onInstall,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.settings_action_install), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun InstalledPackageRow(
    name: String,
    isCore: Boolean,
    isBusy: Boolean,
    onReviewRemoval: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainerLowest,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = HarnessMono,
                modifier = Modifier.weight(1f),
            )
            if (isCore) {
                Text(
                    uiContext.getString(R.string.ui_required_last7),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.outline,
                )
            } else {
                IconButton(
                    onClick = onReviewRemoval,
                    enabled = !isBusy,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Outlined.Storage,
                        contentDescription = stringResource(R.string.settings_packages_review_cd, name),
                        tint = scheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}
