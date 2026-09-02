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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.data.env.EnvState
import com.androidharness.app.data.env.PkgMeta
import com.androidharness.app.ui.common.ThinLinearProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    val isBusy = envState is EnvState.Downloading || envState is EnvState.Installing || envState is EnvState.Preparing

    // Keep installed list fresh when state transitions to Ready
    LaunchedEffect(envState) {
        if (envState is EnvState.Ready) {
            installedList = linuxEnv.installedPackages()
        }
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
                    Text("Linux Packages", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${installedList.size} installed · Termux repository",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Installation progress indicator
            if (isBusy) {
                val pkgName = (envState as? EnvState.Downloading)?.pkg
                    ?: (envState as? EnvState.Installing)?.pkg
                val stepText = when (val s = envState) {
                    is EnvState.Downloading -> "Downloading ${s.pkg} (${s.index + 1}/${s.total})…"
                    is EnvState.Installing -> "Installing ${s.pkg} (${s.index + 1}/${s.total})…"
                    is EnvState.Preparing -> "Resolving dependencies…"
                    else -> "Setting up packages…"
                }
                Surface(
                    color = scheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            stepText,
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.primary,
                        )
                        Spacer(Modifier.height(6.dp))
                        ThinLinearProgress(Modifier.fillMaxWidth())
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
                            modifier = Modifier.size(20.dp),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = "Dismiss", tint = scheme.error)
                        }
                    }
                }
            }

            // Search Bar
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search packages (e.g. jq, ripgrep, clang, tmux)…") },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = scheme.onSurfaceVariant)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear search", tint = scheme.onSurfaceVariant)
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
                                "Searching repository…",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 16.dp),
                            )
                        }
                    } else if (searchResults.isEmpty()) {
                        item {
                            Text(
                                "No packages found matching \"$query\".",
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
                                isCore = pkg.name in linuxEnv.corePackages,
                                isBusy = isBusy,
                                onInstall = {
                                    errorMessage = null
                                    scope.launch {
                                        try {
                                            linuxEnv.install(listOf(pkg.name))
                                            installedList = linuxEnv.installedPackages()
                                        } catch (e: Exception) {
                                            errorMessage = e.message ?: "Failed to install ${pkg.name}"
                                        }
                                    }
                                },
                                onUninstall = {
                                    errorMessage = null
                                    scope.launch {
                                        try {
                                            linuxEnv.uninstallPackage(pkg.name)
                                            installedList = linuxEnv.installedPackages()
                                        } catch (e: Exception) {
                                            errorMessage = e.message ?: "Failed to remove ${pkg.name}"
                                        }
                                    }
                                },
                            )
                        }
                    }
                } else {
                    // Default browse mode: Popular Tools
                    item {
                        Text(
                            "POPULAR TOOLS",
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
                            isCore = name in linuxEnv.corePackages,
                            isBusy = isBusy,
                            onInstall = {
                                errorMessage = null
                                scope.launch {
                                    try {
                                        linuxEnv.install(listOf(name))
                                        installedList = linuxEnv.installedPackages()
                                    } catch (e: Exception) {
                                        errorMessage = e.message ?: "Failed to install $name"
                                    }
                                }
                            },
                            onUninstall = {
                                errorMessage = null
                                scope.launch {
                                    try {
                                        linuxEnv.uninstallPackage(name)
                                        installedList = linuxEnv.installedPackages()
                                    } catch (e: Exception) {
                                        errorMessage = e.message ?: "Failed to remove $name"
                                    }
                                }
                            },
                        )
                    }

                    // Installed Packages section
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "ALL INSTALLED (${installedList.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }

                    items(installedList.sorted(), key = { "installed-$it" }) { name ->
                        InstalledPackageRow(
                            name = name,
                            isCore = name in linuxEnv.corePackages,
                            isBusy = isBusy,
                            onUninstall = {
                                errorMessage = null
                                scope.launch {
                                    try {
                                        linuxEnv.uninstallPackage(name)
                                        installedList = linuxEnv.installedPackages()
                                    } catch (e: Exception) {
                                        errorMessage = e.message ?: "Failed to remove $name"
                                    }
                                }
                            },
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
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
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
                        fontFamily = FontFamily.Monospace,
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

            if (isInstalled) {
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
                            "Installed",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                        )
                    }
                }
                if (!isCore) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = onUninstall,
                        enabled = !isBusy,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "Uninstall",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
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
                    Text("Install", style = MaterialTheme.typography.labelMedium)
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
    onUninstall: () -> Unit,
) {
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
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            if (isCore) {
                Text(
                    "core",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.outline,
                )
            } else {
                IconButton(
                    onClick = onUninstall,
                    enabled = !isBusy,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Uninstall $name",
                        tint = scheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}
