package com.homedesk.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.homedesk.chat.data.ChatMessage
import com.homedesk.chat.data.pcFileName
import com.homedesk.chat.ui.theme.FieldMetrics
import com.homedesk.chat.ui.theme.FieldShapes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onBack: () -> Unit,
    onAttach: () -> Unit,
) {
    val graph = LocalGraph.current
    val viewModel: ChatViewModel = viewModel(
        key = conversationId,
        factory = viewModelFactory {
            initializer { ChatViewModel(conversationId, graph) }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showModels by rememberSaveable { mutableStateOf(false) }
    var manual by remember(state.model) { mutableStateOf(state.model) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                graph.fileNav.consumeAttachment()?.let(viewModel::attach)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    if (showModels) {
        ModalBottomSheet(
            onDismissRequest = { showModels = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = FieldShapes.bottomSheet,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = FieldMetrics.screenPadding)
                    .padding(bottom = 28.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                Text("Model", style = MaterialTheme.typography.headlineSmall)
                ModelPicker(
                    models = state.models,
                    selected = state.model,
                    manual = manual,
                    message = state.modelsMessage,
                    onManual = { manual = it },
                    onSelect = { name ->
                        viewModel.selectModel(name)
                        showModels = false
                    },
                )
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            FieldTopBar(
                title = if (state.title == "New chat") "Chat" else state.title,
                onBack = onBack,
                actions = {
                    TextButton(
                        onClick = {
                            manual = state.model
                            viewModel.loadModels()
                            showModels = true
                        },
                    ) {
                        Text(
                            state.model,
                            maxLines = 1,
                            modifier = Modifier.widthIn(max = 140.dp),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val banner = state.banner
                if (!banner.isNullOrBlank()) {
                    FieldCard(accent = MaterialTheme.colorScheme.error, onClick = viewModel::dismissBanner) {
                        Text(
                            banner,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                val attached = state.attachedPath
                if (!attached.isNullOrBlank()) {
                    FieldCard(onClick = viewModel::clearAttachment) {
                        Text("Attached from the PC", style = MaterialTheme.typography.labelMedium)
                        Text(
                            pcFileName(attached),
                            modifier = Modifier.padding(top = FieldMetrics.space4),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Tap to remove. The file is only context for the next message.",
                            modifier = Modifier.padding(top = FieldMetrics.space4),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (!state.sending) {
                        IconButton(
                            onClick = onAttach,
                            enabled = state.ready,
                            modifier = Modifier.padding(bottom = 4.dp),
                        ) {
                            Icon(Icons.Filled.AttachFile, contentDescription = "Attach a file from the PC")
                        }
                    }
                    OutlinedTextField(
                        value = state.draft,
                        onValueChange = viewModel::onDraft,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message") },
                        maxLines = 6,
                        shape = FieldShapes.search,
                    )
                    if (state.sending) {
                        IconButton(onClick = viewModel::stop, modifier = Modifier.padding(bottom = 4.dp)) {
                            Icon(Icons.Filled.Stop, contentDescription = "Stop")
                        }
                    } else {
                        IconButton(
                            onClick = viewModel::send,
                            enabled = state.draft.isNotBlank() && state.ready,
                            modifier = Modifier.padding(bottom = 4.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        },
    ) { padding ->
        when {
            !state.ready -> {
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Opening chat…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.messages.isEmpty() -> {
                Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Ask something. The reply streams from the PC.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.messages.asReversed(), key = { it.id }) { message ->
                        MessageRow(
                            message = message,
                            pending = message.id == state.streamingId && message.content.isEmpty(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(message: ChatMessage, pending: Boolean) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (isUser) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceBright,
                modifier = Modifier.fillMaxWidth(0.86f),
            ) {
                Text(
                    message.content,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Desk",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = when {
                        pending -> "Working…"
                        else -> message.content
                    },
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = when {
                        message.error -> MaterialTheme.colorScheme.error
                        pending -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onBackground
                    },
                )
            }
        }
    }
}
