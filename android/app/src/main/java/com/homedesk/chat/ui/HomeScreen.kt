package com.homedesk.chat.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.homedesk.chat.ui.theme.FieldMetrics
import com.homedesk.chat.ui.theme.LocalChrome

@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
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
        topBar = { FieldTopBar("Desk") },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(FieldMetrics.screenPadding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(FieldMetrics.space16),
        ) {
            Text(
                "The model runs on your PC. This phone is the screen.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val accent = when {
                !state.configured -> MaterialTheme.colorScheme.outline
                state.ok -> LocalChrome.current.success
                state.headline.isEmpty() -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.error
            }
            FieldCard(accent = accent) {
                Text(
                    text = when {
                        state.checking && state.headline.isEmpty() -> "Checking the desk…"
                        state.headline.isBlank() -> "Desk"
                        else -> state.headline
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                if (state.detail.isNotBlank()) {
                    Text(
                        text = state.detail,
                        modifier = Modifier.padding(top = FieldMetrics.space8),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!state.configured) {
                DeskButton(text = "Open Settings", onClick = onOpenSettings)
            } else {
                DeskButton(text = "New chat", onClick = onNewChat)
                if (!state.ok && state.detail.isNotBlank()) {
                    DeskOutlinedButton(text = "Check again", onClick = viewModel::refresh)
                }
                val recent = state.recent
                if (recent != null) {
                    SectionHeader("Continue")
                    FieldCard(onClick = { onOpenChat(recent.id) }) {
                        Text(recent.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        val preview = recent.messages.lastOrNull()?.content?.replace('\n', ' ')?.trim().orEmpty()
                        if (preview.isNotBlank()) {
                            Text(
                                preview,
                                modifier = Modifier.padding(top = FieldMetrics.space4),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }
}
