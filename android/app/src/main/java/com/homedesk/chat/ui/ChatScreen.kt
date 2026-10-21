package com.homedesk.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
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
import com.homedesk.chat.data.previewDiff
import com.homedesk.chat.net.WorkspaceProposal
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
                graph.fileNav.consumeWorkspace()?.let(viewModel::setWorkspace)
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

    state.reviewing?.let { proposal ->
        WorkspaceReview(
            proposal = proposal,
            applying = state.applying,
            canApply = !state.sending,
            error = state.banner,
            onDismiss = { if (!state.applying) viewModel.review(null) },
            onApply = viewModel::applyProposal,
        )
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
                state.workspace?.let { folder ->
                    FieldCard(onClick = { if (!state.sending && !state.applying) viewModel.setWorkspace(null) }) {
                        Text("Folder workspace: ${pcFileName(folder)}", style = MaterialTheme.typography.labelLarge)
                        Text(folder, style = MaterialTheme.typography.bodySmall)
                        Text("Used on every message. Tap to disconnect.", style = MaterialTheme.typography.bodySmall)
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
                            Icon(Icons.Filled.AttachFile, contentDescription = "Choose a PC file or folder")
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
                            enabled = state.draft.isNotBlank() && state.ready && !state.applying,
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
                    state.context?.let { report ->
                        item(key = "workspace-context") {
                            var expanded by remember { mutableStateOf(false) }
                            FieldCard(onClick = { expanded = !expanded }) {
                                Text("Folder context: ${report.files.size} files read · ${report.omitted} omitted · ${report.skipped} skipped",
                                    style = MaterialTheme.typography.labelLarge)
                                if (report.truncated) Text("The folder listing was capped. Mention a path to focus the next question.")
                                if (expanded) Text(report.files.joinToString("\n").ifEmpty { "No file contents fit. Ask about a smaller folder." },
                                    style = MaterialTheme.typography.bodySmall)
                                else Text("Tap to see included files", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    items(state.messages.asReversed(), key = { it.id }) { message ->
                        MessageRow(
                            message = message,
                            pending = message.id == state.streamingId && message.content.isEmpty(),
                            onReview = viewModel::review,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(message: ChatMessage, pending: Boolean, onReview: (WorkspaceProposal) -> Unit) {
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
                message.proposal?.let { proposal ->
                    FieldCard {
                        Text(if (message.applied) "Changes saved" else "${proposal.changes.size} file changes proposed")
                        Text(proposal.changes.joinToString("\n") { it.path }, style = MaterialTheme.typography.bodySmall)
                        if (!message.applied) TextButton(onClick = { onReview(proposal) }) { Text("Review changes") }
                    }
                }
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceReview(
    proposal: WorkspaceProposal,
    applying: Boolean,
    canApply: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(16.dp).windowInsetsPadding(WindowInsets.navigationBars)) {
            Text("Review folder changes", style = MaterialTheme.typography.headlineSmall)
            Text("Save writes all ${proposal.changes.size} files. Existing files get .bak copies. Changed source files are rejected.",
                style = MaterialTheme.typography.bodySmall)
            if (!error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(proposal.changes, key = { it.path }) { change ->
                    var full by remember { mutableStateOf(false) }
                    val rows = remember(change) { previewDiff(change.before.orEmpty(), change.content) }
                    FieldCard {
                        Text((if (change.before == null) "Create: " else "Edit: ") + change.path,
                            style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { full = !full }) { Text(if (full) "Show diff" else "Show complete result") }
                        SelectionContainer {
                            Text(if (full) change.content else rows.take(250).joinToString("\n") {
                                (when (it.kind) { "add" -> "+ "; "remove" -> "− "; else -> "  " }) + it.text
                            } + if (rows.size > 250) "\n… Diff truncated; show complete result to inspect all text." else "",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }
                }
            }
            DeskButton(text = if (applying) "Saving…" else "Save all changes", onClick = onApply,
                enabled = !applying && canApply, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = onDismiss, enabled = !applying) { Text("Keep reviewing later") }
        }
    }
}
