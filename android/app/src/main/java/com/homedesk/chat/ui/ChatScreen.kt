package com.homedesk.chat.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.homedesk.chat.data.ChatMessage
import com.homedesk.chat.ui.theme.FieldMetrics
import com.homedesk.chat.ui.theme.FieldShapes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onBack: () -> Unit,
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
                        Icon(
                            Icons.Filled.Memory,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 4.dp).size(16.dp),
                        )
                        Text(
                            state.model,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 120.dp),
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
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = state.draft,
                        onValueChange = viewModel::onDraft,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message") },
                        maxLines = 6,
                        shape = RoundedCornerShape(24.dp),
                    )
                    if (state.sending) {
                        FilledIconButton(
                            onClick = viewModel::stop,
                            modifier = Modifier.padding(bottom = 4.dp).size(48.dp),
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = "Stop")
                        }
                    } else {
                        FilledIconButton(
                            onClick = viewModel::send,
                            enabled = state.draft.isNotBlank() && state.ready,
                            modifier = Modifier.padding(bottom = 4.dp).size(48.dp),
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
                EmptyHint(
                    title = "Start a chat",
                    body = "Ask something. The reply streams from the PC as it is written.",
                    icon = Icons.AutoMirrored.Filled.Chat,
                    modifier = Modifier.padding(padding),
                )
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
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .padding(end = 8.dp, bottom = 2.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "D",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        val bubbleColor = when {
            isUser -> MaterialTheme.colorScheme.primary
            message.error -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        }
        val textColor = when {
            isUser -> MaterialTheme.colorScheme.onPrimary
            message.error -> MaterialTheme.colorScheme.onErrorContainer
            pending -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        }
        Surface(
            shape = if (isUser) {
                RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)
            } else {
                RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp)
            },
            color = bubbleColor,
            modifier = Modifier.fillMaxWidth(if (isUser) 0.82f else 0.88f),
        ) {
            if (pending) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text("Working…", style = MaterialTheme.typography.bodyLarge, color = textColor)
                }
            } else {
                Text(
                    message.content,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor,
                )
            }
        }
    }
}
