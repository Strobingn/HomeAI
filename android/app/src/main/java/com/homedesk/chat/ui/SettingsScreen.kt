package com.homedesk.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.homedesk.chat.net.LinkState
import com.homedesk.chat.net.SettingsAudit
import com.homedesk.chat.ui.theme.FieldMetrics
import com.homedesk.chat.ui.theme.LocalChrome

@Composable
fun SettingsScreen(bottomBar: @Composable () -> Unit = {}) {
    val graph = LocalGraph.current
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory { initializer { SettingsViewModel(graph) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val localChecks = SettingsAudit.local(state.baseUrl, state.apiKey, state.selectedModel)
    val status = state.status
    val deskState = status?.desk ?: LinkState.Unknown
    val gpuState = status?.gpu ?: LinkState.Unknown

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { FieldTopBar("Settings") },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = FieldMetrics.screenPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(FieldMetrics.space12),
        ) {
            FieldCard(accent = linkColor(if (status?.ok == true) LinkState.Ok else deskState)) {
                Text(
                    text = status?.headline ?: "Phone to PC",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = status?.detail
                        ?: "The address, key, and model are checked below. Test reaches the PC, then Ollama.",
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ConnectionRoute(desk = deskState, gpu = gpuState)
                BusyLine(
                    visible = state.testing,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            SectionHeader("On this phone")
            FieldCard {
                CheckList(localChecks)
            }

            SectionHeader("Server")
            Text(
                "Use the address printed by the Desk window. A bare IP is saved with port 8787. Pasting the whole API_KEY= line is fine.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = viewModel::onUrl,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Server URL") },
                placeholder = { Text("http://192.168.1.20:8787") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
            )
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = viewModel::onKey,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API key") },
                placeholder = { Text("paste the key from config/.env") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                visualTransformation = if (state.showKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onDone = { viewModel.test() }),
                leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = viewModel::toggleShowKey) {
                        Icon(
                            imageVector = if (state.showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (state.showKey) "Hide API key" else "Show API key",
                        )
                    }
                },
            )
            val notice = state.notice
            if (!notice.isNullOrBlank()) {
                Text(
                    notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalChrome.current.success,
                )
            }
            DeskOutlinedButton(text = "Save", onClick = viewModel::save)
            DeskButton(
                text = if (state.testing) "Testing…" else "Test connection",
                onClick = viewModel::test,
                enabled = !state.testing,
                icon = Icons.Filled.Link,
            )
            val remote = status?.checks.orEmpty()
            if (remote.isNotEmpty()) {
                SectionHeader("Last connection test")
                FieldCard(accent = linkColor(if (status?.ok == true) LinkState.Ok else LinkState.Bad)) {
                    CheckList(remote)
                }
            }

            SectionHeader("Model")
            if (state.loadingModels) {
                Text(
                    "Loading installed models…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ModelPicker(
                models = state.models,
                selected = state.selectedModel,
                manual = state.manualModel,
                message = state.modelsMessage,
                onManual = viewModel::onManualModel,
                onSelect = viewModel::useModel,
            )
            Text(
                "Default on a 12 GB GPU is qwen2.5:7b. It is fast and fits with room to spare.",
                modifier = Modifier.padding(bottom = 28.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
