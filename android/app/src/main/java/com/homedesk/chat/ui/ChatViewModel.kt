package com.homedesk.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homedesk.chat.AppGraph
import com.homedesk.chat.data.ChatMessage
import com.homedesk.chat.data.Conversation
import com.homedesk.chat.data.DeskDefaults
import com.homedesk.chat.data.conversationTitle
import com.homedesk.chat.data.modelHistory
import com.homedesk.chat.data.pcFileName
import com.homedesk.chat.net.ChatStream
import com.homedesk.chat.net.DeskCallException
import com.homedesk.chat.net.ModelInfo
import com.homedesk.chat.net.StreamEvent
import com.homedesk.chat.net.WorkspaceContext
import com.homedesk.chat.net.WorkspaceProposal
import com.homedesk.chat.net.explainTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

data class ChatUiState(
    val ready: Boolean = false,
    val title: String = "New chat",
    val model: String = DeskDefaults.MODEL,
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val attachedPath: String? = null,
    val workspace: String? = null,
    val context: WorkspaceContext? = null,
    val reviewing: WorkspaceProposal? = null,
    val applying: Boolean = false,
    val sending: Boolean = false,
    val streamingId: String? = null,
    val banner: String? = null,
    val models: List<ModelInfo> = emptyList(),
    val modelsMessage: String? = null,
)

class ChatViewModel(
    private val conversationId: String,
    private val graph: AppGraph,
) : ViewModel() {
    private val _state = MutableStateFlow(ChatUiState(model = graph.settings.get().model))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()
    private var stream: ChatStream? = null
    private var tokensSinceSave = 0

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = graph.chats.get(conversationId)
            val settings = graph.settings.get()
            val model = settings.model.ifBlank { existing?.model ?: DeskDefaults.MODEL }
            _state.update {
                it.copy(
                    ready = true,
                    title = existing?.title ?: "New chat",
                    model = model,
                    messages = existing?.messages ?: emptyList(),
                    workspace = it.workspace ?: existing?.workspace,
                )
            }
        }
    }

    fun onDraft(value: String) = _state.update { it.copy(draft = value) }

    fun attach(path: String) {
        if (path.isBlank()) return
        _state.update { it.copy(attachedPath = path, workspace = null, context = null, banner = null) }
    }

    fun setWorkspace(path: String?) {
        if (_state.value.sending || _state.value.applying) return
        _state.update { it.copy(workspace = path, attachedPath = null, context = null) }
        viewModelScope.launch(Dispatchers.IO) { persist() }
    }

    fun review(proposal: WorkspaceProposal?) = _state.update { it.copy(reviewing = proposal) }

    fun applyProposal() {
        val snapshot = _state.value
        val proposal = snapshot.reviewing ?: return
        if (snapshot.applying || snapshot.sending) return
        _state.update { it.copy(applying = true, banner = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val settings = graph.settings.get()
            try {
                graph.workspace.apply(settings.baseUrl, settings.apiKey, proposal)
                _state.update { state -> state.copy(
                    reviewing = null,
                    banner = "Saved ${proposal.changes.size} files. Previous versions have .bak copies.",
                    messages = state.messages.map { if (it.proposal?.id == proposal.id) it.copy(applied = true) else it },
                ) }
                persist()
            } catch (error: IOException) {
                _state.update { it.copy(banner = error.message ?: "Could not save changes.") }
            } finally {
                _state.update { it.copy(applying = false) }
            }
        }
    }

    fun clearAttachment() = _state.update { it.copy(attachedPath = null) }

    fun dismissBanner() = _state.update { it.copy(banner = null) }

    fun loadModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = graph.settings.get()
            if (!settings.isReady) {
                _state.update {
                    it.copy(modelsMessage = "Save the server URL and API key in Settings to load the model list. You can still type a model name.")
                }
                return@launch
            }
            try {
                val models = graph.api.listModels(settings.baseUrl, settings.apiKey)
                _state.update {
                    it.copy(
                        models = models,
                        modelsMessage = if (models.isEmpty()) {
                            "Ollama has no models yet. On the PC run: ollama pull qwen2.5:7b"
                        } else {
                            null
                        },
                    )
                }
            } catch (error: DeskCallException) {
                _state.update { it.copy(modelsMessage = error.message) }
            } catch (error: IOException) {
                _state.update { it.copy(modelsMessage = explainTransport(error, settings.baseUrl)) }
            }
        }
    }

    fun selectModel(model: String) {
        val clean = model.trim()
        if (clean.isEmpty()) return
        _state.update { it.copy(model = clean, banner = null) }
        graph.settings.saveModel(clean)
        viewModelScope.launch(Dispatchers.IO) {
            val settings = graph.settings.get()
            if (!settings.isReady) return@launch
            try {
                graph.api.selectModel(settings.baseUrl, settings.apiKey, clean)
            } catch (error: DeskCallException) {
                _state.update { it.copy(banner = error.message) }
            } catch (error: IOException) {
                _state.update { it.copy(banner = explainTransport(error, settings.baseUrl)) }
            }
        }
    }

    fun send() {
        val snapshot = _state.value
        val text = snapshot.draft.trim()
        if (text.isEmpty() || snapshot.sending || snapshot.applying || !snapshot.ready) return
        val settings = graph.settings.get()
        if (!settings.isReady) {
            _state.update { it.copy(banner = "Add the server URL and API key in Settings.") }
            return
        }
        val attached = snapshot.attachedPath
        val shown = if (attached.isNullOrBlank()) {
            text
        } else {
            "Attached from the PC: ${pcFileName(attached)}\n\n$text"
        }
        val user = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = shown,
            createdAt = System.currentTimeMillis(),
        )
        val assistantId = UUID.randomUUID().toString()
        val assistant = ChatMessage(
            id = assistantId,
            role = "assistant",
            content = "",
            createdAt = System.currentTimeMillis(),
        )
        val messages = snapshot.messages + user + assistant
        _state.update {
            it.copy(
                draft = "",
                attachedPath = null,
                sending = true,
                banner = null,
                messages = messages,
                title = conversationTitle(messages),
                streamingId = assistantId,
            )
        }
        val outbound = modelHistory(messages.filter { it.id != assistantId })
        val model = _state.value.model
        viewModelScope.launch(Dispatchers.IO) {
            persist()
            var call: ChatStream? = null
            try {
                call = if (snapshot.workspace != null) {
                    graph.workspace.openChat(settings.baseUrl, settings.apiKey, snapshot.workspace, model, outbound)
                } else {
                    graph.api.openChat(settings.baseUrl, settings.apiKey, model, outbound, file = attached)
                }
                stream = call
                call.read { event ->
                    when (event) {
                        is StreamEvent.Token -> appendToken(assistantId, event.text)
                        is StreamEvent.Context -> _state.update { it.copy(context = event.report) }
                        is StreamEvent.Done -> if (event.proposal != null) {
                            _state.update { state -> state.copy(messages = state.messages.map {
                                if (it.id == assistantId) it.copy(
                                    proposal = event.proposal,
                                    content = it.content.substringBefore("```desk-changes").trim()
                                        .ifBlank { "Review the proposed file changes below." },
                                ) else it
                            }) }
                        }
                        is StreamEvent.Error -> showError(assistantId, event.message)
                    }
                }
            } catch (error: DeskCallException) {
                showError(assistantId, error.message ?: "The server rejected that message.")
            } catch (error: IOException) {
                if (call?.isCanceled() != true) {
                    showError(assistantId, explainTransport(error, settings.baseUrl))
                }
            } finally {
                stream = null
                _state.update { it.copy(sending = false, streamingId = null) }
                persist()
            }
        }
    }

    fun stop() {
        stream?.cancel()
    }

    override fun onCleared() {
        stream?.cancel()
        super.onCleared()
    }

    private fun appendToken(id: String, text: String) {
        _state.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.id == id) message.copy(content = message.content + text) else message
                },
            )
        }
        tokensSinceSave += 1
        if (tokensSinceSave >= 24) {
            tokensSinceSave = 0
            persist()
        }
    }

    private fun showError(id: String, message: String) {
        _state.update { state ->
            state.copy(
                messages = state.messages.map { item ->
                    if (item.id != id) {
                        item
                    } else if (item.content.isBlank()) {
                        item.copy(content = message, error = true)
                    } else {
                        item.copy(content = item.content + "\n\n" + message, error = true)
                    }
                },
            )
        }
    }

    private fun persist() {
        val snapshot = _state.value
        val kept = snapshot.messages.filter { it.content.isNotBlank() || it.proposal != null }
        if (kept.isEmpty()) return
        graph.chats.upsert(
            Conversation(
                id = conversationId,
                title = conversationTitle(kept),
                model = snapshot.model,
                updatedAt = System.currentTimeMillis(),
                messages = kept,
                workspace = snapshot.workspace,
            ),
        )
    }
}

