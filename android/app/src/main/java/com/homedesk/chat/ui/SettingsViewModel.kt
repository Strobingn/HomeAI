package com.homedesk.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homedesk.chat.AppGraph
import com.homedesk.chat.data.DeskDefaults
import com.homedesk.chat.data.DeskSettings
import com.homedesk.chat.net.DeskCallException
import com.homedesk.chat.net.DeskStatus
import com.homedesk.chat.net.ModelInfo
import com.homedesk.chat.net.ModelCatalog
import com.homedesk.chat.net.ModelInstall
import com.homedesk.chat.net.ServerUrl
import com.homedesk.chat.net.explainTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

data class SettingsUiState(
    val baseUrl: String = "",
    val apiKey: String = "",
    val showKey: Boolean = false,
    val testing: Boolean = false,
    val status: DeskStatus? = null,
    val models: List<ModelInfo> = emptyList(),
    val modelsMessage: String? = null,
    val manualModel: String = DeskDefaults.MODEL,
    val selectedModel: String = DeskDefaults.MODEL,
    val loadingModels: Boolean = false,
    val catalog: ModelCatalog? = null,
    val catalogMessage: String? = null,
    val loadingCatalog: Boolean = false,
    val install: ModelInstall = ModelInstall(),
    val installBusy: Boolean = false,
)

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    private var installPoll: Job? = null
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        val saved = graph.settings.get()
        _state.value = SettingsUiState(
            baseUrl = saved.baseUrl,
            apiKey = saved.apiKey,
            manualModel = saved.model,
            selectedModel = saved.model,
        )
        if (saved.isReady) { refreshModels(); refreshCatalog() }
    }

    fun onUrl(value: String) = _state.update { it.copy(baseUrl = value) }

    fun onKey(value: String) = _state.update { it.copy(apiKey = value) }

    fun onManualModel(value: String) = _state.update { it.copy(manualModel = value) }

    fun toggleShowKey() = _state.update { it.copy(showKey = !it.showKey) }

    fun save() {
        val snapshot = _state.value
        try {
            require(ServerUrl.normalize(snapshot.baseUrl).isNotBlank()) { "Enter the PC's server address." }
            require(ServerUrl.normalizeKey(snapshot.apiKey).isNotBlank()) { "Enter the API key from config/.env." }
        } catch (error: IllegalArgumentException) {
            _state.update { it.copy(status = DeskStatus(ok = false, headline = "Check settings", detail = error.message.orEmpty())) }
            return
        }
        graph.settings.save(
            DeskSettings(
                baseUrl = snapshot.baseUrl,
                apiKey = snapshot.apiKey,
                model = snapshot.selectedModel,
            ),
        )
        val saved = graph.settings.get()
        _state.update {
            it.copy(baseUrl = saved.baseUrl, apiKey = saved.apiKey, selectedModel = saved.model)
        }
    }

    fun test() {
        val snapshot = _state.value
        try {
            require(ServerUrl.normalize(snapshot.baseUrl).isNotBlank()) { "Enter the server address." }
            require(ServerUrl.normalizeKey(snapshot.apiKey).isNotBlank()) { "Enter the API key." }
        } catch (error: IllegalArgumentException) {
            _state.update { it.copy(status = DeskStatus(ok = false, headline = "Check settings", detail = error.message.orEmpty())) }
            return
        }
        if (snapshot.baseUrl.isBlank()) {
            _state.update {
                it.copy(
                    status = DeskStatus(
                        ok = false,
                        headline = "URL needed",
                        detail = "Enter the server URL. It looks like http://192.168.1.20:8787.",
                    ),
                )
            }
            return
        }
        if (snapshot.apiKey.isBlank()) {
            _state.update {
                it.copy(
                    status = DeskStatus(
                        ok = false,
                        headline = "API key needed",
                        detail = "Enter the API key from config/.env on the PC.",
                    ),
                )
            }
            return
        }
        save()
        val settings = graph.settings.get()
        viewModelScope.launch {
            _state.update { it.copy(testing = true, status = null) }
            val status = withContext(Dispatchers.IO) {
                try {
                    graph.api.test(settings.baseUrl, settings.apiKey)
                } catch (error: DeskCallException) {
                    val message = error.message ?: "The server rejected the connection."
                    DeskStatus(ok = false, headline = statusHeadline(message), detail = message)
                } catch (error: IOException) {
                    DeskStatus(
                        ok = false,
                        headline = "Can't reach the desk",
                        detail = explainTransport(error, settings.baseUrl),
                    )
                }
            }
            _state.update { it.copy(testing = false, status = status) }
            if (status.ok && !status.model.isNullOrBlank()) {
                graph.settings.saveModel(status.model)
                _state.update { it.copy(selectedModel = status.model, manualModel = status.model) }
            }
            if (status.listModels) {
                refreshModels()
                refreshCatalog()
            }
        }
    }

    fun refreshModels() {
        val settings = graph.settings.get()
        if (!settings.isReady) {
            _state.update {
                it.copy(modelsMessage = "Save the server URL and API key, then test the connection. You can still type a model name.")
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loadingModels = true) }
            try {
                val models = withContext(Dispatchers.IO) {
                    graph.api.listModels(settings.baseUrl, settings.apiKey)
                }
                _state.update {
                    it.copy(
                        loadingModels = false,
                        models = models,
                        modelsMessage = if (models.isEmpty()) {
                            "Ollama has no models yet. Install one from the catalog below."
                        } else {
                            null
                        },
                    )
                }
            } catch (error: DeskCallException) {
                _state.update { it.copy(loadingModels = false, modelsMessage = error.message) }
            } catch (error: IOException) {
                _state.update {
                    it.copy(loadingModels = false, modelsMessage = explainTransport(error, settings.baseUrl))
                }
            }
        }
    }

    fun useModel(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) {
            _state.update { it.copy(modelsMessage = "Type a model name, for example qwen2.5:7b.") }
            return
        }
        if (_state.value.baseUrl.isNotBlank() && _state.value.apiKey.isNotBlank()) save()
        val settings = graph.settings.get()
        if (!settings.isReady) {
            graph.settings.saveModel(clean)
            _state.update { it.copy(selectedModel = clean, manualModel = clean) }
            _state.update { it.copy(modelsMessage = "$clean is saved on this phone. Connect to the PC to load it.") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                graph.api.selectModel(settings.baseUrl, settings.apiKey, clean)
                graph.settings.saveModel(clean)
                _state.update { it.copy(selectedModel = clean, manualModel = clean, modelsMessage = "$clean selected. Ollama is loading it on the PC.") }
            } catch (error: DeskCallException) {
                _state.update { it.copy(modelsMessage = error.message) }
            } catch (error: IOException) {
                _state.update { it.copy(modelsMessage = explainTransport(error, settings.baseUrl)) }
            }
        }
    }

    fun refreshCatalog() {
        if (_state.value.loadingCatalog) return
        val settings = graph.settings.get()
        if (!settings.isReady) {
            _state.update { it.copy(catalogMessage = "Save the PC connection above to browse and install models.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loadingCatalog = true, catalogMessage = null) }
            try {
                val catalog = withContext(Dispatchers.IO) { graph.modelCatalog.catalog(settings.baseUrl, settings.apiKey) }
                _state.update { it.copy(catalog = catalog, loadingCatalog = false) }
                if (installPoll?.isActive != true) pollInstall()
            } catch (error: IOException) {
                _state.update { it.copy(loadingCatalog = false, catalogMessage = error.message ?: explainTransport(error, settings.baseUrl)) }
            } catch (_: kotlinx.serialization.SerializationException) {
                _state.update { it.copy(loadingCatalog = false, catalogMessage = "The PC sent an unreadable catalog. Update the PC gateway and retry.") }
            }
        }
    }

    fun installModels(models: List<String>) {
        val settings = graph.settings.get()
        if (!settings.isReady || _state.value.installBusy || _state.value.install.active) return
        installPoll?.cancel()
        installPoll = viewModelScope.launch {
            _state.update { it.copy(installBusy = true, catalogMessage = null) }
            try {
                val result = withContext(Dispatchers.IO) { graph.modelCatalog.install(settings.baseUrl, settings.apiKey, models) }
                _state.update { it.copy(install = result, installBusy = false) }
                observeInstall(settings)
            } catch (error: IOException) {
                _state.update { it.copy(installBusy = false, catalogMessage = error.message) }
            } catch (_: kotlinx.serialization.SerializationException) {
                _state.update { it.copy(installBusy = false, catalogMessage = "Couldn't read download status. Refresh to reconnect to the PC download.") }
            }
        }
    }

    fun pollInstall() {
        if (installPoll?.isActive == true) return
        val settings = graph.settings.get()
        if (!settings.isReady) return
        installPoll = viewModelScope.launch { observeInstall(settings) }
    }

    private suspend fun observeInstall(settings: DeskSettings) {
        try {
            do {
                val result = withContext(Dispatchers.IO) { graph.modelCatalog.status(settings.baseUrl, settings.apiKey) }
                _state.update { it.copy(install = result, catalogMessage = null) }
                if (!result.active) { if (result.finished.isNotEmpty()) refreshModels(); break }
                delay(2000)
            } while (true)
        } catch (error: IOException) {
            _state.update { it.copy(catalogMessage = "Lost contact with the PC. The download may still be running. Refresh to reconnect. ${error.message.orEmpty()}") }
        } catch (_: kotlinx.serialization.SerializationException) {
            _state.update { it.copy(catalogMessage = "Couldn't read download status. Refresh to reconnect.") }
        }
    }

    fun cancelInstall() {
        val id = _state.value.install.id ?: return
        if (_state.value.installBusy) return
        installPoll?.cancel()
        val settings = graph.settings.get()
        installPoll = viewModelScope.launch {
            _state.update { it.copy(installBusy = true) }
            try {
                val result = withContext(Dispatchers.IO) { graph.modelCatalog.cancel(settings.baseUrl, settings.apiKey, id) }
                _state.update { it.copy(install = result, installBusy = false) }
            } catch (error: IOException) {
                _state.update { it.copy(installBusy = false, catalogMessage = error.message) }
            } catch (_: kotlinx.serialization.SerializationException) {
                _state.update { it.copy(installBusy = false, catalogMessage = "Refresh to check whether the download stopped.") }
            }
        }
    }
}

