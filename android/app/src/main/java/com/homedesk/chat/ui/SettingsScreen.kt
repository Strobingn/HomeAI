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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
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
    val updates by graph.updates.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

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
                Text(updates.message, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                if (updates.busy) {
                    val progress = updates.progress
                    if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                    else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                    TextButton(onClick = graph.updates::cancel) { Text("Stop") }
                } else {
                    DeskOutlinedButton("Check for app updates", graph.updates::check, Modifier.padding(top = 12.dp))
                    if (updates.ready) {
                        DeskButton("Install update", graph.updates::install)
                    } else if (updates.release != null) {
                        DeskButton("Download update", graph.updates::download)
                    }
                    TextButton(onClick = { uriHandler.openUri(graph.updates.releasePage) }) { Text("Release downloads") }
                }
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
                text = if (state.testing) "Testing…" else "Test connection & sync model",
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
                "Run one model at a time on your 12 GB GPU. Larger context uses more memory.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader("Model catalog")
            Text("Downloads go to Ollama on your PC and continue when you leave this screen.", style = MaterialTheme.typography.bodyMedium)
            DeskOutlinedButton(if (state.loadingCatalog) "Loading catalog…" else "Refresh catalog & downloads", onClick = {
                viewModel.refreshModels(); viewModel.refreshCatalog(); viewModel.pollInstall()
            }, enabled = !state.loadingCatalog)
            state.catalogMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            val install = state.install
            if (install.phase != "idle") {
                FieldCard {
                    Text(if (install.active) "Downloading to PC" else "Model download · ${install.phase}", style = MaterialTheme.typography.titleMedium)
                    install.model?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(install.status, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    Text("${install.finished.size} of ${install.models.size} models installed", style = MaterialTheme.typography.labelMedium)
                    if (install.active) {
                        if (install.total > 0) {
                            val fraction = (install.completed.toFloat() / install.total).coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                            Text("Current layer · ${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        } else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                        TextButton(onClick = viewModel::cancelInstall, enabled = !state.installBusy) { Text("Stop download") }
                    }
                }
            }
            state.catalog?.let { catalog ->
                DeskButton("Install uncensored pack · ~11.5 GB", onClick = { viewModel.installModels(catalog.uncensoredPack) },
                    enabled = !install.active && !state.installBusy)
                Text("Qwen 3.5 Abliterated 9B + Dolphin 3 8B. Your selected model stays selected.", style = MaterialTheme.typography.bodySmall)
                for (model in catalog.models) {
                    val installed = state.models.any { it.name.equals(model.model, ignoreCase = true) } ||
                        install.finished.any { it.equals(model.model, ignoreCase = true) }
                    FieldCard {
                        Text(model.name, style = MaterialTheme.typography.titleMedium)
                        Text("${model.category} · ~${model.downloadGb} GB download", style = MaterialTheme.typography.labelMedium)
                        Text(model.description, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        if (installed) {
                            DeskOutlinedButton(if (state.selectedModel.equals(model.model, ignoreCase = true)) "Selected" else "Use model",
                                onClick = { viewModel.useModel(model.model) }, enabled = !state.selectedModel.equals(model.model, ignoreCase = true))
                        } else {
                            DeskOutlinedButton("Install on PC", onClick = { viewModel.installModels(listOf(model.model)) }, enabled = !install.active && !state.installBusy)
                        }
                        TextButton(onClick = { uriHandler.openUri(model.source) }) { Text("Model details") }
                    }
                }
                Text(catalog.note, Modifier.padding(bottom = 28.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

