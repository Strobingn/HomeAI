package com.homedesk.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.homedesk.chat.ui.theme.FieldMetrics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatsScreen(
    onNewChat: () -> Unit,
    onOpenChat: (String) -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    val graph = LocalGraph.current
    val viewModel: ChatsViewModel = viewModel(
        factory = viewModelFactory { initializer { ChatsViewModel(graph) } },
    )
    val items by viewModel.items.collectAsStateWithLifecycle()
    val pending by viewModel.pendingDelete.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    var workspacesOnly by remember { mutableStateOf(false) }
    val visible = items.filter { it.title.contains(search, ignoreCase = true) && (!workspacesOnly || it.workspace != null) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) { viewModel.reload() }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.reload()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    pending?.let { conversation ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text("Delete this chat?") },
            text = { Text(conversation.title) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            FieldTopBar(
                title = "Chats",
                actions = {
                    IconButton(onClick = onNewChat) {
                        Icon(Icons.Filled.Add, contentDescription = "New chat")
                    }
                },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        if (items.isEmpty()) {
            EmptyHint(
                title = "No chats yet",
                body = "Start one from Home. Chats stay on this phone.",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(FieldMetrics.screenPadding),
                verticalArrangement = Arrangement.spacedBy(FieldMetrics.space12),
            ) {
                item {
                    Text("${items.size} conversations", style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(value = search, onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp), singleLine = true,
                        placeholder = { Text("Search conversations") }, shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                    FilterChip(selected = workspacesOnly, onClick = { workspacesOnly = !workspacesOnly },
                        label = { Text("Folder workspaces") })
                    if (visible.isEmpty()) Text("No matching conversations", style = MaterialTheme.typography.bodyMedium)
                }
                items(visible, key = { it.id }) { conversation ->
                    FieldCard(onClick = { onOpenChat(conversation.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    conversation.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    formatWhen(conversation.updatedAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { viewModel.askDelete(conversation) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete chat")
                            }
                        }
                        conversation.workspace?.let { folder ->
                            Text("Workspace · ${com.homedesk.chat.data.pcFileName(folder)}",
                                modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium)
                        }
                        val preview = conversation.messages.lastOrNull()?.content
                            ?.replace('\n', ' ')
                            ?.trim()
                            .orEmpty()
                        if (preview.isNotBlank()) {
                            Text(
                                preview,
                                modifier = Modifier.padding(top = FieldMetrics.space4),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatWhen(epochMs: Long): String {
    val delta = System.currentTimeMillis() - epochMs
    return when {
        delta < 60_000 -> "Just now"
        delta < 3_600_000 -> "${delta / 60_000} min ago"
        delta < 86_400_000 -> "${delta / 3_600_000} h ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMs))
    }
}

