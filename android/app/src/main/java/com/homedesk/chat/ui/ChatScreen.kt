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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.launch
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
    onNewChat: () -> Unit,
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
    var showContext by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var manual by remember(state.model) { mutableStateOf(state.model) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val owner = LocalLifecycleOwner.current
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val widePadding = ((width - 840.dp) / 2).coerceAtLeast(0.dp)
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty() && listState.firstVisibleItemIndex < 2) listState.animateScrollToItem(0)
    }
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
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
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

    if (showContext && state.workspace != null) ModalBottomSheet(
        onDismissRequest = { showContext = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Project context", style = MaterialTheme.typography.headlineMedium)
                Text(state.workspace.orEmpty(), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
            val report = state.context
            item {
                if (report == null) Text("Send a message to see which files Desk reads.")
                else {
                    Text("${report.files.size} files read · ${report.omitted} omitted · ${report.skipped} skipped",
                        style = MaterialTheme.typography.titleSmall)
                    if (report.truncated) InlineNotice("The folder listing was capped. Choose a smaller subfolder or name the file you need.")
                }
                TextButton(onClick = { showContext = false; onAttach() }, enabled = !state.sending && !state.applying) {
                    Text("Choose another folder")
                }
                HorizontalDivider()
            }
            items(report?.files.orEmpty(), key = { it }) { path ->
                Text(path, style = MaterialTheme.typography.bodyMedium)
            }
            item { Spacer(Modifier.height(24.dp)) }
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
                title = if (state.workspace != null) "Workspace chat" else if (state.title == "New chat") "New conversation" else state.title,
                onBack = onBack,
                actions = {
                    if (state.workspace != null) IconButton(onClick = { showContext = true }) {
                        Icon(Icons.Filled.FolderOpen, "View project context")
                    }
                    IconButton(onClick = onNewChat, enabled = !state.applying) { Icon(Icons.Filled.Add, "Start another chat") }
                },
            )
        },
        bottomBar = {
            ChatComposer(state, viewModel::onDraft, viewModel::send, viewModel::stop, onAttach,
                onRemoveAttachment = { if (state.workspace != null) viewModel.setWorkspace(null) else viewModel.clearAttachment() },
                onWorkspaceDetails = { showContext = true },
                onModel = { manual = state.model; viewModel.loadModels(); showModels = true },
                onDismissBanner = viewModel::dismissBanner)
        },
    ) { padding ->
        when {
            !state.ready -> {
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Opening chat…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.messages.isEmpty() -> {
                Column(Modifier.padding(padding).padding(horizontal = 24.dp + widePadding).fillMaxSize()
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                    Text(if (state.workspace != null) "Your project.\nA fresh perspective." else "A little clarity.\nA new idea.",
                        style = MaterialTheme.typography.displaySmall)
                    Text(if (state.workspace != null) "Ask about this folder or describe a change you want to make."
                        else "Ask a question, untangle a problem, or bring a folder into the conversation.",
                        Modifier.padding(top = 12.dp, bottom = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val suggestions = if (state.workspace != null) listOf(
                        "Explain this project" to "Explain how this project is organized and where it starts.",
                        "Find possible bugs" to "Review the relevant files for likely bugs. Explain the evidence before proposing changes.",
                        "Plan a change" to "Help me plan a change to this project. Start by explaining what is here.",
                    ) else listOf(
                        "Help me think it through" to "Help me think through a problem. Start by asking what I want to solve.",
                        "Turn an idea into a plan" to "Help me turn an idea into clear, practical next steps.",
                    )
                    suggestions.forEach { (label, prompt) ->
                        FieldCard(Modifier.padding(bottom = 10.dp), onClick = { viewModel.onDraft(prompt) }) {
                            Text(label, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    if (state.workspace == null) TextButton(onClick = onAttach) { Text("Choose a project folder") }
                }
            }
            else -> {
                Box(Modifier.padding(padding).padding(horizontal = widePadding).fillMaxSize()) {
                  LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                  ) {
                    items(state.messages.asReversed(), key = { it.id }) { message ->
                        MessageRow(
                            message = message,
                            pending = message.id == state.streamingId && message.content.isEmpty(),
                            streaming = message.id == state.streamingId,
                            onReview = viewModel::review,
                        )
                    }
                  }
                  if (listState.firstVisibleItemIndex > 1) ExtendedFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                    icon = { Icon(Icons.Filled.ArrowDownward, null) }, text = { Text("Latest") })
                }
            }
        }
    }
}

@Composable
private fun MessageRow(message: ChatMessage, pending: Boolean, streaming: Boolean, onReview: (WorkspaceProposal) -> Unit) {
    val isUser = message.role == "user"
    val clipboard = LocalClipboardManager.current
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
                SelectionContainer {
                    Text(message.content, Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("DESK", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!streaming && message.content.isNotBlank()) IconButton(onClick = {
                        clipboard.setText(AnnotatedString(message.content))
                    }) { Icon(Icons.Filled.ContentCopy, "Copy response") }
                }
                message.proposal?.let { proposal ->
                    FieldCard {
                        Text(if (message.applied) "Changes saved" else "${proposal.changes.size} file changes proposed")
                        Text(proposal.changes.joinToString("\n") { it.path }, style = MaterialTheme.typography.bodySmall)
                        if (!message.applied) TextButton(onClick = { onReview(proposal) }) { Text("Review changes") }
                    }
                }
                if (pending) Text("Thinking…", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else ChatMarkdown(message.content.substringBefore("```desk-changes"), Modifier.padding(top = 4.dp))
                if (streaming && "```desk-changes" in message.content) Text("Preparing file changes…",
                    Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)

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
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = !applying, dismissOnClickOutside = false)) {
      Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
            FieldTopBar("Review changes", onBack = onDismiss)
            Text("${proposal.changes.size} files ready for review", style = MaterialTheme.typography.headlineSmall)
            Text("Save writes all ${proposal.changes.size} files. Existing files get .bak copies. Changed source files are rejected.",
                style = MaterialTheme.typography.bodySmall)
            if (!error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            LazyColumn(Modifier.weight(1f).padding(vertical = 12.dp),
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
            TextButton(onClick = onDismiss, enabled = !applying) { Text("Back to conversation") }
        }
    }
}
}
