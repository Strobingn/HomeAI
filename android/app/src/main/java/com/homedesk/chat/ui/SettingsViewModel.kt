package com.homedesk.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homedesk.chat.AppGraph
import com.homedesk.chat.data.DeskDefaults
import com.homedesk.chat.data.DeskSettings
import com.homedesk.chat.net.ApiKey
import com.homedesk.chat.net.DeskCallException
import com.homedesk.chat.net.DeskStatus
import com.homedesk.chat.net.LinkState
import com.homedesk.chat.net.ModelInfo
import com.homedesk.chat.net.ServerUrl
import com.homedesk.chat.net.explainTransport
import kotlinx.coroutines.Dispatchers
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
    val notice: String? = null,
)

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()
    private var testGeneration = 0

    init {
        val saved = graph.settings.get()
        _state.value = SettingsUiState(
            baseUrl = saved.baseUrl,
            apiKey = saved.apiKey,
            manualModel = saved.model,
            selectedModel = saved.model,
        )
        if (saved.isReady &&
            ServerUrl.review(saved.baseUrl).state != LinkState.Bad &&
            ApiKey.review(saved.apiKey).state != LinkState.Bad
        ) {
            test()
        }
    }

    fun onUrl(value: String) {
        testGeneration += 1
        _state.update { it.copy(baseUrl = value, status = null, notice = null, testing = false) }
    }

    fun onKey(value: String) {
        testGeneration += 1
        _state.update { it.copy(apiKey = value, status = null, notice = null, testing = false) }
    }

    fun onManualModel(value: String) = _state.update { it.copy(manualModel = value) }

    fun toggleShowKey() = _state.update { it.copy(showKey = !it.showKey) }

    fun save() {
        val snapshot = _state.value
        graph.settings.save(
            DeskSettings(
                baseUrl = snapshot.baseUrl,
                apiKey = snapshot.apiKey,
                model = snapshot.selectedModel,
            ),
        )
        val saved = graph.settings.get()
        _state.update {
            it.copy(
                baseUrl = saved.baseUrl,
                apiKey = saved.apiKey,
                selectedModel = saved.model,
                notice = "Saved on this phone.",
            )
        }
    }

    fun test() {
        val snapshot = _state.value
        val urlReview = ServerUrl.review(snapshot.baseUrl)
        val keyReview = ApiKey.review(snapshot.apiKey)
        if (snapshot.baseUrl.isBlank() || urlReview.state == LinkState.Bad) {
            testGeneration += 1
            _state.update {
                it.copy(
                    notice = null,
                    status = DeskStatus(
                        ok = false,
                        headline = "Check the server address",
                        detail = urlReview.note,
                        desk = LinkState.Bad,
                    ),
                )
            }
            return
        }
        if (snapshot.apiKey.isBlank() || keyReview.state == LinkState.Bad) {
            testGeneration += 1
            _state.update {
                it.copy(
                    notice = null,
                    status = DeskStatus(
                        ok = false,
                        headline = "Check the API key",
                        detail = keyReview.note,
                    ),
                )
            }
            return
        }
        save()
        _state.update { it.copy(notice = null) }
        val settings = graph.settings.get()
        val generation = ++testGeneration
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
            if (generation != testGeneration) return@launch
            _state.update { it.copy(testing = false, status = status) }
            if (status.listModels) {
                refreshModels()
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
                            "Ollama has no models yet. On the PC run: ollama pull qwen2.5:7b"
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
        _state.update { it.copy(selectedModel = clean, manualModel = clean) }
        save()
        val settings = graph.settings.get()
        if (!settings.isReady) {
            _state.update { it.copy(modelsMessage = "$clean is saved on this phone. Connect to the PC to load it.") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                graph.api.selectModel(settings.baseUrl, settings.apiKey, clean)
                _state.update { it.copy(modelsMessage = "$clean will stay loaded on the PC.") }
            } catch (error: DeskCallException) {
                _state.update { it.copy(modelsMessage = error.message) }
            } catch (error: IOException) {
                _state.update { it.copy(modelsMessage = explainTransport(error, settings.baseUrl)) }
            }
        }
    }
}
