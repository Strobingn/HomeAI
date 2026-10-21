package com.homedesk.chat.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homedesk.chat.AppGraph
import com.homedesk.chat.data.pcFileName
import com.homedesk.chat.net.ChatStream
import com.homedesk.chat.net.DeskCallException
import com.homedesk.chat.net.StreamEvent
import com.homedesk.chat.net.WriteResult
import com.homedesk.chat.net.explainTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class EditorUiState(
    val path: String,
    val name: String,
    val loading: Boolean = true,
    val editable: Boolean = true,
    val original: String = "",
    val draft: String = "",
    val saving: Boolean = false,
    val instruction: String = "",
    val asking: Boolean = false,
    val reply: String = "",
    val proposal: String? = null,
    val model: String? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val dirty: Boolean get() = editable && draft != original
}

class FileEditorViewModel(
    private val path: String,
    private val graph: AppGraph,
) : ViewModel() {
    private val _state = MutableStateFlow(EditorUiState(path = path, name = pcFileName(path)))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()
    private var stream: ChatStream? = null

    init {
        reload()
    }

    fun onDraft(value: String) = _state.update { it.copy(draft = value) }

    fun onInstruction(value: String) = _state.update { it.copy(instruction = value) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = graph.settings.get()
            if (!settings.isReady) {
                _state.update {
                    it.copy(
                        loading = false,
                        editable = false,
                        error = "Add the server URL and API key in Settings.",
                    )
                }
                return@launch
            }
            _state.update { it.copy(loading = true, error = null) }
            try {
                val file = graph.files.read(settings.baseUrl, settings.apiKey, path)
                _state.update {
                    it.copy(
                        loading = false,
                        editable = true,
                        path = file.path.ifBlank { path },
                        name = file.name.ifBlank { pcFileName(path) },
                        original = file.content,
                        draft = file.content,
                        proposal = null,
                        reply = "",
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        editable = false,
                        error = failure(error, settings.baseUrl),
                    )
                }
            }
        }
    }

    fun saveDraft() {
        save(_state.value.draft, fromProposal = false)
    }

    fun saveProposal() {
        val proposal = _state.value.proposal ?: return
        save(proposal, fromProposal = true)
    }

    fun useProposal() {
        val proposal = _state.value.proposal ?: return
        _state.update { it.copy(draft = proposal, proposal = null, notice = "Loaded the proposal into the editor. Save when you want it on the PC.") }
    }

    fun ask() {
        val snapshot = _state.value
        val instruction = snapshot.instruction.trim()
        if (instruction.isEmpty() || snapshot.asking || snapshot.loading) return
        val settings = graph.settings.get()
        if (!settings.isReady) {
            _state.update { it.copy(error = "Add the server URL and API key in Settings.") }
            return
        }
        _state.update {
            it.copy(asking = true, reply = "", proposal = null, model = null, error = null, notice = null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val call = graph.files.openFileAi(settings.baseUrl, settings.apiKey, path, instruction)
            stream = call
            try {
                call.read { event ->
                    when (event) {
                        is StreamEvent.Context -> Unit
                        is StreamEvent.Token -> _state.update { state ->
                            state.copy(reply = state.reply + event.text)
                        }
                        is StreamEvent.Done -> _state.update { state ->
                            state.copy(model = event.model ?: state.model, proposal = event.proposed)
                        }
                        is StreamEvent.Error -> _state.update { state ->
                            state.copy(error = event.message)
                        }
                    }
                }
                _state.update { state ->
                    if (state.error == null && state.proposal == null && state.reply.isNotBlank()) {
                        state.copy(notice = "The model replied, but it did not include a full new file. You can still edit the file yourself and save.")
                    } else {
                        state
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: DeskCallException) {
                _state.update { it.copy(error = error.message ?: "The server rejected that request.") }
            } catch (error: IOException) {
                if (!call.isCanceled()) {
                    _state.update { it.copy(error = explainTransport(error, settings.baseUrl)) }
                }
            } finally {
                stream = null
                _state.update { it.copy(asking = false) }
            }
        }
    }

    fun stop() {
        stream?.cancel()
    }

    fun downloadTo(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = graph.settings.get()
            if (!settings.isReady) {
                _state.update { it.copy(error = "Add the server URL and API key in Settings.") }
                return@launch
            }
            try {
                val output = graph.app.contentResolver.openOutputStream(uri)
                    ?: throw DeskCallException("The phone could not open a place to save that file.")
                output.use { stream ->
                    graph.files.download(settings.baseUrl, settings.apiKey, path, stream)
                }
                _state.update { it.copy(notice = "Saved ${_state.value.name} on this phone.", error = null) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                _state.update { it.copy(error = failure(error, settings.baseUrl)) }
            }
        }
    }

    override fun onCleared() {
        stream?.cancel()
        super.onCleared()
    }

    private fun save(content: String, fromProposal: Boolean) {
        if (_state.value.saving) return
        val settings = graph.settings.get()
        if (!settings.isReady) {
            _state.update { it.copy(error = "Add the server URL and API key in Settings.") }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = graph.files.write(settings.baseUrl, settings.apiKey, path, content)
                _state.update {
                    it.copy(
                        saving = false,
                        original = content,
                        draft = content,
                        proposal = if (fromProposal) null else it.proposal,
                        notice = saveNotice(result),
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                _state.update { it.copy(saving = false, error = failure(error, settings.baseUrl)) }
            }
        }
    }

    private fun saveNotice(result: WriteResult): String {
        if (result.unchanged) return "Already saved."
        return if (result.backup.isNullOrBlank()) {
            "Saved on the PC."
        } else {
            "Saved on the PC. The previous copy is in a .bak file beside it."
        }
    }

    private fun failure(error: Throwable, url: String): String {
        return when (error) {
            is DeskCallException -> error.message ?: "The server rejected that request."
            is IOException -> explainTransport(error, url)
            else -> error.message ?: "Something went wrong."
        }
    }
}

