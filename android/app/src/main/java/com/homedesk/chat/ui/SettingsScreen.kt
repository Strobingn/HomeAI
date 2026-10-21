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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.homedesk.chat.BuildConfig
import com.homedesk.chat.ui.theme.FieldMetrics
import com.homedesk.chat.ui.theme.LocalChrome

@Composable
fun SettingsScreen(bottomBar: @Composable () -> Unit = {}) {
    val graph = LocalGraph.current
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory { initializer { SettingsViewModel(graph) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

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
            FieldCard {
                Text("Desk ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineMedium)
                Text("Build ${BuildConfig.VERSION_CODE} · ${if (BuildConfig.DEBUG) "Development" else "Private release"}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${BuildConfig.APPLICATION_ID}", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
            SectionHeader("Desktop connection")
            Text(
                "Type the address from the Desk window on the PC, and the API key from config/.env.",
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
                trailingIcon = {
                    TextButton(onClick = viewModel::toggleShowKey) {
                        Text(if (state.showKey) "Hide" else "Show")
                    }
                },
            )
            DeskOutlinedButton(text = "Save", onClick = viewModel::save)
            DeskButton(
                text = if (state.testing) "Testing…" else "Test connection",
                onClick = viewModel::test,
                enabled = !state.testing,
            )
            val status = state.status
            if (status != null) {
                val accent = if (status.ok) LocalChrome.current.success else MaterialTheme.colorScheme.error
                FieldCard(accent = accent) {
                    Text(status.headline, style = MaterialTheme.typography.titleMedium)
                    Text(
                        status.detail,
                        modifier = Modifier.padding(top = FieldMetrics.space8),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionHeader("AI model")
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

