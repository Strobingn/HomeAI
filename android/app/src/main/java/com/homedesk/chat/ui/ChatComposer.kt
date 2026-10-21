package com.homedesk.chat.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.homedesk.chat.data.pcFileName

@Composable
fun ChatComposer(
    state: ChatUiState,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: () -> Unit,
    onWorkspaceDetails: () -> Unit,
    onModel: () -> Unit,
    onDismissBanner: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.banner?.let { InlineNotice(it, onDismissBanner) }
            val attached = state.workspace ?: state.attachedPath
            if (attached != null) Surface(shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.FolderOpen, null, Modifier.size(20.dp))
                    TextButton(onClick = if (state.workspace != null) onWorkspaceDetails else onRemoveAttachment,
                        modifier = Modifier.weight(1f)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(pcFileName(attached), style = MaterialTheme.typography.labelLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (state.workspace != null) state.context?.let { "${it.files.size} files read · View context" }
                                ?: "Folder workspace · View details" else "Attached for your next message",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = onRemoveAttachment, enabled = !state.sending && !state.applying) {
                        Icon(Icons.Filled.Close, "Remove attachment")
                    }
                }
            }
            Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
                Column(Modifier.padding(8.dp)) {
                    TextField(value = state.draft, onValueChange = onDraft,
                        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
                        enabled = state.ready && !state.applying,
                        placeholder = { Text(if (state.workspace != null) "Ask about your project…" else "Message Desk…") },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onAttach, enabled = state.ready && !state.sending && !state.applying) {
                            Icon(Icons.Filled.Add, "Attach a file or choose a folder")
                        }
                        TextButton(onClick = onModel, enabled = !state.sending && !state.applying,
                            modifier = Modifier.widthIn(max = 210.dp)) {
                            Text(state.model.substringBefore(':'), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium)
                            Icon(Icons.Filled.ExpandMore, null, Modifier.size(18.dp).padding(start = 4.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        FilledIconButton(onClick = if (state.sending) onStop else onSend,
                            enabled = state.sending || (state.ready && state.draft.isNotBlank() && !state.applying)) {
                            if (state.sending) Icon(Icons.Filled.Stop, "Stop reply")
                            else Icon(Icons.Filled.ArrowUpward, "Send message")
                        }
                    }
                }
            }
        }
    }
}
