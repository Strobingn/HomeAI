package com.homedesk.chat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.homedesk.chat.data.DiffRow
import com.homedesk.chat.data.previewDiff
import com.homedesk.chat.ui.theme.FieldMetrics
import com.homedesk.chat.ui.theme.LocalChrome

@Composable
fun FileEditorScreen(
    path: String,
    onBack: () -> Unit,
) {
    val graph = LocalGraph.current
    val viewModel: FileEditorViewModel = viewModel(
        key = path,
        factory = viewModelFactory { initializer { FileEditorViewModel(path, graph) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) viewModel.downloadTo(uri)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            FieldTopBar(
                title = state.name,
                onBack = onBack,
                actions = {
                    IconButton(onClick = { saveFile.launch(state.name) }) {
                        Icon(Icons.Filled.Download, contentDescription = "Download to phone")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = FieldMetrics.screenPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(FieldMetrics.space12),
        ) {
            if (state.loading || state.saving) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            state.error?.let { message ->
                FieldCard(accent = MaterialTheme.colorScheme.error, onClick = viewModel::dismissError) {
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            state.notice?.let { message ->
                FieldCard(accent = LocalChrome.current.success, onClick = viewModel::dismissNotice) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
            }
            when {
                state.loading && state.draft.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 32.dp))
                }
                !state.editable -> {
                    Text(
                        "You can still download this file to the phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DeskButton(text = "Download to phone", onClick = { saveFile.launch(state.name) })
                }
                else -> {
                    Text(
                        state.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.draft,
                        onValueChange = viewModel::onDraft,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp, max = 420.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        shape = RoundedCornerShape(16.dp),
                        label = { Text("File") },
                    )
                    DeskButton(
                        text = if (state.saving) "Saving…" else "Save",
                        onClick = viewModel::saveDraft,
                        enabled = state.dirty && !state.saving && !state.asking,
                    )
                    SectionHeader("Ask AI")
                    Text(
                        "The model reads the copy on the PC and suggests a new version. Nothing is overwritten until you tap Save.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.dirty) {
                        Text(
                            "Unsaved edits stay on the phone. Ask AI uses the copy already saved on the PC.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = state.instruction,
                        onValueChange = viewModel::onInstruction,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Instruction") },
                        placeholder = { Text("Explain what you want changed") },
                        minLines = 2,
                        maxLines = 6,
                        shape = RoundedCornerShape(16.dp),
                    )
                    if (state.asking) {
                        DeskOutlinedButton(text = "Stop", onClick = viewModel::stop)
                    } else {
                        DeskButton(
                            text = "Ask AI",
                            onClick = viewModel::ask,
                            enabled = state.instruction.isNotBlank() && !state.saving,
                        )
                    }
                    if (state.reply.isNotBlank() || state.asking) {
                        FieldCard {
                            val model = state.model
                            Text(
                                if (model.isNullOrBlank()) "Reply" else "Reply from $model",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            SelectionContainer {
                                Text(
                                    text = when {
                                        state.asking && state.reply.isBlank() -> "Working…"
                                        state.proposal != null -> explanationOnly(state.reply)
                                        else -> state.reply.ifBlank { "Working…" }
                                    },
                                    modifier = Modifier.padding(top = FieldMetrics.space8),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    val proposal = state.proposal
                    if (proposal != null) {
                        SectionHeader("Preview")
                        val rows = previewDiff(state.original, proposal)
                        if (rows.isEmpty()) {
                            Text(
                                "The proposal matches the file on the PC.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            FieldCard {
                                Column(
                                    modifier = Modifier
                                        .heightIn(max = 280.dp)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    rows.forEach { row -> DiffLine(row) }
                                }
                            }
                        }
                        DeskButton(
                            text = "Save to PC",
                            onClick = viewModel::saveProposal,
                            enabled = !state.saving && !state.asking,
                        )
                        DeskOutlinedButton(
                            text = "Edit this version",
                            onClick = viewModel::useProposal,
                            enabled = !state.saving && !state.asking,
                        )
                    }
                }
            }
            Text(
                "",
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun DiffLine(row: DiffRow) {
    val color = when (row.kind) {
        "add" -> LocalChrome.current.success
        "remove" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val prefix = when (row.kind) {
        "add" -> "+ "
        "remove" -> "− "
        "skip" -> ""
        else -> "  "
    }
    Text(
        text = prefix + row.text.ifEmpty { " " },
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = color,
    )
}

private fun explanationOnly(reply: String): String {
    val marker = reply.lastIndexOf("```file")
    val trimmed = if (marker > 0) reply.substring(0, marker).trim() else reply.trim()
    return trimmed.ifBlank { "Review the preview below, then save if you want it on the PC." }
}
