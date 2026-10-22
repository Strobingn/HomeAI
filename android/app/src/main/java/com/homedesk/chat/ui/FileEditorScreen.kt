package com.homedesk.chat.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.homedesk.chat.data.DiffRow
import com.homedesk.chat.data.previewDiff

@Composable
fun FileEditorScreen(path: String, onBack: () -> Unit) {
    val graph = LocalGraph.current
    val vm: FileEditorViewModel = viewModel(key = path,
        factory = viewModelFactory { initializer { FileEditorViewModel(path, graph) } })
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable(path) { mutableStateOf(0) }
    var confirmExit by remember { mutableStateOf(false) }
    val download = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        uri?.let(vm::downloadTo)
    }
    val leave = {
        if (!state.saving) {
            if (state.dirty || state.asking) confirmExit = true else onBack()
        }
    }
    BackHandler(enabled = state.saving || state.dirty || state.asking) { leave() }
    if (confirmExit) AlertDialog(
        onDismissRequest = { confirmExit = false },
        title = { Text("Leave this file?") },
        text = { Text(if (state.asking) "The AI reply will stop. Any unsaved edits will be discarded."
            else "Your unsaved edits will be discarded.") },
        confirmButton = { TextButton(onClick = { vm.stop(); confirmExit = false; onBack() }) { Text("Leave file") } },
        dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Keep editing") } },
    )
    val rows = remember(state.original, state.proposal) {
        state.proposal?.let { previewDiff(state.original, it) }.orEmpty()
    }
    Scaffold(
        topBar = {
            FieldTopBar(state.name.ifBlank { "File editor" }, onBack = leave, actions = {
                IconButton(onClick = { download.launch(state.name) }, enabled = !state.loading) {
                    Icon(Icons.Filled.Download, "Download to phone")
                }
            })
        },
        bottomBar = {
            if (state.editable && !state.loading && (tab == 0 || tab == 2)) Surface(
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(Modifier.imePadding().navigationBarsPadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tab == 0) {
                        DeskButton(if (state.saving) "Saving…" else if (state.dirty) "Save edits to PC" else "Saved on PC",
                            vm::saveDraft, enabled = state.dirty && !state.saving && !state.asking)
                    } else if (state.proposal != null) {
                        DeskButton(if (state.saving) "Saving…" else "Save reviewed version", vm::saveProposal,
                            enabled = !state.saving && !state.asking)
                        TextButton(onClick = { vm.useProposal(); tab = 0 }, enabled = !state.saving && !state.asking,
                            modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Continue editing this version") }
                    }
                }
            }
        },
    ) { insets ->
        Box(Modifier.padding(insets).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 1120.dp).fillMaxSize()) {
                if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.path, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val lines = remember(state.draft) { if (state.draft.isEmpty()) 0 else state.draft.count { it == '\n' } + 1 }
                        Text("${state.name.substringAfterLast('.', "text").uppercase()}  ·  $lines lines",
                            Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        Text(if (state.dirty) "UNSAVED EDITS" else "PC COPY",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.error?.let { InlineNotice(it, vm::dismissError) }
                    state.notice?.let { InlineNotice(it, vm::dismissNotice) }
                }
                if (!state.editable && !state.loading) {
                    EmptyHint("Preview unavailable", "You can download this file and open it with another app.")
                    DeskButton("Download file", { download.launch(state.name) }, Modifier.padding(20.dp))
                } else if (!state.loading) {
                    TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                        listOf("Edit", "Ask AI", "Review").forEachIndexed { index, title ->
                            Tab(selected = tab == index, onClick = { tab = index }, text = {
                                Text(if (index == 2 && state.proposal != null) "$title •" else title)
                            })
                        }
                    }
                    when (tab) {
                        0 -> OutlinedTextField(
                            value = state.draft, onValueChange = vm::onDraft,
                            enabled = !state.saving,
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            shape = RoundedCornerShape(18.dp),
                            placeholder = { Text("Start writing…") },
                        )
                        1 -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Make a better version.", style = MaterialTheme.typography.headlineMedium)
                            Text("Describe the change. You can review the result before saving it to your PC.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (state.dirty) InlineNotice("AI uses the saved PC copy. Save your edits first to include them.")
                            OutlinedTextField(state.instruction, vm::onInstruction, Modifier.fillMaxWidth(),
                                placeholder = { Text("Explain this file, fix a bug, or make a specific change…") },
                                minLines = 4, maxLines = 8, shape = RoundedCornerShape(18.dp), enabled = !state.asking)
                            if (state.asking) DeskOutlinedButton("Stop generation", vm::stop)
                            else DeskButton("Ask Desk", vm::ask, enabled = state.instruction.isNotBlank() && !state.saving)
                            if (state.reply.isNotBlank()) FieldCard {
                                Text(state.model ?: "Desk", style = MaterialTheme.typography.labelLarge)
                                ChatMarkdown(explanationOnly(state.reply), Modifier.padding(top = 12.dp))
                            }
                            if (state.proposal != null) DeskButton("Review suggested changes", { tab = 2 })
                        }
                        2 -> if (state.proposal == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Check, null, Modifier.size(36.dp))
                                EmptyHint("No changes to review", "Ask AI for a new version, then review its changes here.")
                                TextButton(onClick = { tab = 1 }) { Text("Go to Ask AI") }
                            }
                        } else Column(Modifier.weight(1f).padding(16.dp)) {
                            Text("+${rows.count { it.kind == "add" }} added  ·  −${rows.count { it.kind == "remove" }} removed",
                                Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.labelLarge)
                            Surface(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                SelectionContainer {
                                    Column(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp)) {
                                        if (rows.isEmpty()) Text("The suggested version matches the saved file.")
                                        rows.forEach { DiffLine(it) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffLine(row: DiffRow) {
    Text((when (row.kind) { "add" -> "+ "; "remove" -> "− "; else -> "  " }) + row.text.ifEmpty { " " },
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = if (row.kind in setOf("add", "remove")) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun explanationOnly(reply: String): String = reply.substringBefore("```file").trim()
    .ifBlank { "Preparing the file proposal…" }
