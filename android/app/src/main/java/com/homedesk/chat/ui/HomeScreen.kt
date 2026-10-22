package com.homedesk.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
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
import com.homedesk.chat.ui.theme.LocalChrome
import com.homedesk.chat.BuildConfig

@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenChat: (String) -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    val graph = LocalGraph.current
    val viewModel: HomeViewModel = viewModel(
        factory = viewModelFactory { initializer { HomeViewModel(graph) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) { viewModel.refresh() }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            FieldTopBar("Desk", actions = {
                Surface(shape = RoundedCornerShape(100.dp), color = MaterialTheme.colorScheme.surfaceBright) {
                    Text("${BuildConfig.VERSION_NAME}", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium)
                }
                IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, "Refresh connection") }
            })
        },
        bottomBar = bottomBar,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item {
                    Text("YOUR HOME AI", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Think here.\nBuild on your PC.", style = MaterialTheme.typography.displaySmall,
                        modifier = Modifier.padding(top = 10.dp))
                    Text("Conversations, project folders, and file changes in one place.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                }
                item {
                    FieldCard(onClick = onOpenSettings) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceBright) {
                                Icon(Icons.Filled.Computer, null, Modifier.padding(12.dp).size(24.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(if (state.checking) "Checking connection…" else state.headline.ifBlank { "Your desktop" },
                                    style = MaterialTheme.typography.titleMedium)
                                Text(graph.settings.get().baseUrl.ifBlank { "Set up your connection" },
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(if (state.checking) "…" else if (state.ok) "ONLINE" else if (state.configured) "OFFLINE" else "SETUP",
                                style = MaterialTheme.typography.labelSmall)
                        }
                        if (state.detail.isNotBlank()) Text(state.detail, Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FieldCard(Modifier.weight(1f), onClick = if (state.configured) onNewChat else onOpenSettings) {
                            Icon(Icons.AutoMirrored.Filled.Chat, null, Modifier.size(28.dp))
                            Spacer(Modifier.height(24.dp))
                            Text("New chat", style = MaterialTheme.typography.titleMedium)
                            Text("Ask. Explore. Plan.", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FieldCard(Modifier.weight(1f), onClick = if (state.configured) onOpenFiles else onOpenSettings) {
                            Icon(Icons.Filled.FolderOpen, null, Modifier.size(28.dp))
                            Spacer(Modifier.height(24.dp))
                            Text("Workspace", style = MaterialTheme.typography.titleMedium)
                            Text("Work across files.", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (!state.configured) item { DeskButton("Connect your desktop", onOpenSettings) }
                state.recent?.let { recent ->
                    item {
                        SectionHeader("Pick up where you left off")
                        FieldCard(onClick = { onOpenChat(recent.id) }) {
                            Text(recent.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(recent.messages.lastOrNull()?.content.orEmpty().replace('\n', ' '),
                                Modifier.padding(top = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${recent.messages.size} messages · ${recent.model}", Modifier.padding(top = 12.dp),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    FieldCard {
                        Text("From a question to a finished change", style = MaterialTheme.typography.titleMedium)
                        Text("01  Choose a project folder\n02  Ask Desk to explain or change it\n03  Review the diff and save",
                            Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge)
                        Text("You review every AI file change before it reaches your PC.", Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

