package com.homedesk.chat.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.homedesk.chat.net.FileEntry
import com.homedesk.chat.net.FileRoot
import com.homedesk.chat.ui.theme.FieldMetrics
import com.homedesk.chat.ui.theme.LocalChrome
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FilesScreen(
    picking: Boolean,
    onOpenFile: (String) -> Unit,
    onPicked: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: (() -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
    acceptShares: Boolean = !picking,
    onPickedFolder: ((String) -> Unit)? = null,
    onFolderChat: ((String) -> Unit)? = null,
) {
    val graph = LocalGraph.current
    val viewModel: FilesViewModel = viewModel(
        key = if (acceptShares) "files" else "files-pick",
        factory = viewModelFactory {
            initializer { FilesViewModel(graph, acceptShares = acceptShares) }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    var showFolder by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var renameName by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<FileEntry?>(null) }
    var downloadPath by remember { mutableStateOf<String?>(null) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) viewModel.stageUris(uris)
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val path = downloadPath
        if (uri != null && path != null) viewModel.downloadTo(path, uri)
        downloadPath = null
    }

    BackHandler(enabled = picking || !state.atRoots) {
        if (!state.atRoots) viewModel.up() else onBack?.invoke()
    }

    if (showFolder) {
        AlertDialog(
            onDismissRequest = { showFolder = false },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createFolder(folderName)
                        showFolder = false
                        folderName = ""
                    },
                    enabled = folderName.isNotBlank(),
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showFolder = false }) { Text("Cancel") }
            },
        )
    }
    renameTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.rename(entry, renameName)
                        renameTarget = null
                    },
                    enabled = renameName.isNotBlank(),
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }
    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Move to the trash?") },
            text = {
                Text("${entry.name} will be moved to the .trash folder on that drive. It is not deleted permanently.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(entry)
                        deleteTarget = null
                    },
                ) { Text("Move to trash", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    val title = when {
        picking -> "Choose file or folder"
        else -> state.title
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            FieldTopBar(
                title = title,
                onBack = if (picking || !state.atRoots) {
                    {
                        if (!state.atRoots) viewModel.up() else onBack?.invoke()
                    }
                } else {
                    null
                },
                actions = {
                    if (state.configured && !state.atRoots) {
                        IconButton(onClick = { pickFiles.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Filled.Upload, contentDescription = "Upload from this phone")
                        }
                        IconButton(
                            onClick = {
                                folderName = ""
                                showFolder = true
                            },
                        ) {
                            Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
                        }
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            when {
                !state.configured -> {
                    EmptyHint(
                        title = "Connect Desk first",
                        body = "Add the server URL and API key in Settings. Then you can browse the drives on the PC.",
                    )
                    DeskButton(
                        text = "Open Settings",
                        onClick = onOpenSettings,
                        modifier = Modifier.padding(horizontal = FieldMetrics.screenPadding),
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(FieldMetrics.screenPadding),
                        verticalArrangement = Arrangement.spacedBy(FieldMetrics.space12),
                    ) {
                        if (!state.path.isNullOrBlank() && !state.loading && state.error == null) {
                            val folder = state.path.orEmpty()
                            val action = if (picking) onPickedFolder else onFolderChat
                            if (action != null) item {
                                DeskButton(
                                    text = if (picking) "Use this folder" else "Chat with this folder",
                                    onClick = { action(folder) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        if (picking) {
                            item {
                                Text(
                                    "Open a folder and choose Use this folder, or tap a text file to attach it once.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        state.error?.let { message ->
                            item {
                                FieldCard(
                                    accent = MaterialTheme.colorScheme.error,
                                    onClick = viewModel::dismissError,
                                ) {
                                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        state.notice?.let { message ->
                            item {
                                FieldCard(
                                    accent = LocalChrome.current.success,
                                    onClick = viewModel::dismissNotice,
                                ) {
                                    Text(message, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        if (state.staged.isNotEmpty()) {
                            item {
                                FieldCard(accent = LocalChrome.current.success) {
                                    Text(
                                        if (state.staged.size == 1) {
                                            "1 file ready to send"
                                        } else {
                                            "${state.staged.size} files ready to send"
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    state.staged.forEach { staged ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = FieldMetrics.space8),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                "${staged.name} · ${formatBytes(staged.size)}",
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            TextButton(
                                                onClick = { viewModel.removeStaged(staged) },
                                                enabled = !state.uploading,
                                            ) { Text("Remove") }
                                        }
                                    }
                                    if (state.uploading) {
                                        Text(
                                            state.uploadLabel,
                                            modifier = Modifier.padding(top = FieldMetrics.space8),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        val fraction = state.uploadFraction
                                        if (fraction == null) {
                                            LinearProgressIndicator(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = FieldMetrics.space8),
                                            )
                                        } else {
                                            LinearProgressIndicator(
                                                progress = { fraction.coerceIn(0f, 1f) },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = FieldMetrics.space8),
                                            )
                                        }
                                        TextButton(onClick = viewModel::cancelUpload) { Text("Cancel upload") }
                                    } else {
                                        DeskButton(
                                            text = if (state.path.isNullOrBlank()) "Open a folder first" else "Upload here",
                                            onClick = viewModel::uploadStaged,
                                            enabled = !state.path.isNullOrBlank(),
                                            modifier = Modifier.padding(top = FieldMetrics.space12),
                                        )
                                    }
                                }
                            }
                        }
                        if (state.atRoots) {
                            item {
                                Text(
                                    if (state.mode == "all") {
                                        "Every drive on the PC. A USB disk shows up here after you plug it in."
                                    } else {
                                        "Folders Desk is allowed to use. ALLOWED_ROOTS in config/.env sets this list."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!state.loading && state.roots.isEmpty()) {
                                item {
                                    EmptyHint(
                                        title = "No drives found",
                                        body = "On the PC, check that a disk is connected and that Desk is running.",
                                    )
                                }
                            }
                            items(state.roots, key = { it.path }) { root ->
                                RootRow(root = root, onClick = { viewModel.openRoot(root) })
                            }
                        } else {
                            if (state.truncated) {
                                item {
                                    Text(
                                        "Showing the first 5000 items in this folder.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (!state.loading && state.entries.isEmpty()) {
                                item {
                                    EmptyHint(
                                        title = "This folder is empty",
                                        body = "Upload a file from the phone, or create a folder.",
                                    )
                                }
                            }
                            items(state.entries, key = { it.path }) { entry ->
                                EntryRow(
                                    entry = entry,
                                    onClick = {
                                        when {
                                            entry.blocked -> viewModel.showError("That shortcut points outside the drives Desk can use.")
                                            entry.kind == "dir" -> viewModel.openDir(entry)
                                            picking && entry.text -> onPicked(entry.path)
                                            picking -> viewModel.showError("Choose a text file to attach. Other files can still be downloaded from the Files tab.")
                                            entry.text -> onOpenFile(entry.path)
                                            else -> {
                                                downloadPath = entry.path
                                                saveFile.launch(entry.name)
                                            }
                                        }
                                    },
                                    onRename = {
                                        renameName = entry.name
                                        renameTarget = entry
                                    },
                                    onDelete = { deleteTarget = entry },
                                    onDownload = {
                                        downloadPath = entry.path
                                        saveFile.launch(entry.name)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RootRow(root: FileRoot, onClick: () -> Unit) {
    FieldCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Storage, contentDescription = null, modifier = Modifier.size(22.dp))
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(root.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (root.writable) root.path else "${root.path} · read only",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: FileEntry,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val icon = if (entry.kind == "dir") Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile
    FieldCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    entryMeta(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${entry.name}")
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (entry.kind != "dir") {
                        DropdownMenuItem(
                            text = { Text("Download to phone") },
                            onClick = {
                                menu = false
                                onDownload()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menu = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Move to trash") },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

private fun entryMeta(entry: FileEntry): String {
    if (entry.blocked) return "Shortcut outside the drives Desk can use"
    if (entry.kind == "dir") return if (entry.link) "Folder shortcut" else "Folder"
    val bits = mutableListOf<String>()
    entry.size?.let { bits += formatBytes(it) }
    formatModified(entry.modified)?.let { bits += it }
    if (entry.text) bits += "Text"
    if (entry.link) bits += "Shortcut"
    return bits.joinToString(" · ").ifBlank { "File" }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.1f GB", mb / 1024.0)
}

private fun formatModified(epochSeconds: Long?): String? {
    if (epochSeconds == null || epochSeconds <= 0) return null
    return SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(epochSeconds * 1000))
}

