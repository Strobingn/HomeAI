package com.homedesk.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.homedesk.chat.net.LinkState
import com.homedesk.chat.ui.theme.FieldMetrics

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

    val routeState = when {
        !state.configured -> LinkState.Unknown
        state.checking && state.headline.isEmpty() -> LinkState.Unknown
        state.ok -> LinkState.Ok
        state.desk == LinkState.Unknown && state.headline.isNotEmpty() -> LinkState.Bad
        else -> state.desk
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { FieldTopBar("Desk") },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = FieldMetrics.screenPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(FieldMetrics.space16),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                        ),
                    )
                    .padding(20.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(linkColor(routeState).copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = when {
                                state.ok -> Icons.Filled.CheckCircle
                                !state.configured -> Icons.Filled.Settings
                                else -> Icons.Filled.DesktopWindows
                            },
                            contentDescription = null,
                            tint = linkColor(if (state.ok) LinkState.Ok else routeState),
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Text(
                        text = when {
                            state.checking && state.headline.isEmpty() -> "Checking the desk…"
                            state.headline.isBlank() -> "Desk"
                            else -> state.headline
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    if (state.detail.isNotBlank()) {
                        Text(
                            text = state.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    ConnectionRoute(
                        desk = if (state.configured) state.desk else LinkState.Unknown,
                        gpu = if (state.configured) state.gpu else LinkState.Unknown,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            BusyLine(visible = state.checking)
            if (!state.configured) {
                DeskButton(
                    text = "Open Settings",
                    onClick = onOpenSettings,
                    icon = Icons.Filled.Settings,
                )
            } else {
                DeskButton(text = "New chat", onClick = onNewChat, icon = Icons.Filled.Add)
                if (!state.ok && state.detail.isNotBlank()) {
                    DeskOutlinedButton(text = "Check again", onClick = viewModel::refresh)
                }
                if (state.checks.isNotEmpty() && !state.ok) {
                    SectionHeader("What failed")
                    FieldCard(accent = linkColor(LinkState.Bad)) {
                        CheckList(state.checks)
                    }
                }
                val recent = state.recent
                if (recent != null) {
                    SectionHeader("Continue")
                    FieldCard(onClick = { onOpenChat(recent.id) }, accent = MaterialTheme.colorScheme.primary) {
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
            Text(
                "The model runs on your PC. This phone is the screen.",
                modifier = Modifier.padding(bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
