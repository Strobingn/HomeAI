package com.homedesk.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homedesk.chat.AppGraph
import com.homedesk.chat.data.Conversation
import com.homedesk.chat.net.DeskCallException
import com.homedesk.chat.net.DeskStatus
import com.homedesk.chat.net.LinkState
import com.homedesk.chat.net.SettingCheck
import com.homedesk.chat.net.explainTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

data class HomeUiState(
    val configured: Boolean = false,
    val checking: Boolean = false,
    val ok: Boolean = false,
    val headline: String = "",
    val detail: String = "",
    val desk: LinkState = LinkState.Unknown,
    val gpu: LinkState = LinkState.Unknown,
    val checks: List<SettingCheck> = emptyList(),
    val recent: Conversation? = null,
)

class HomeViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val settings = graph.settings.get()
            val recent = withContext(Dispatchers.IO) { graph.chats.list().firstOrNull() }
            if (!settings.isReady) {
                _state.value = HomeUiState(
                    configured = false,
                    recent = recent,
                    headline = "Connect this phone to the PC",
                    detail = "Open Settings and enter the server URL and the API key.",
                )
                return@launch
            }
            _state.update { it.copy(configured = true, checking = it.headline.isEmpty(), recent = recent) }
            val status = withContext(Dispatchers.IO) {
                try {
                    graph.api.test(settings.baseUrl, settings.apiKey)
                } catch (error: DeskCallException) {
                    authOrServer(error)
                } catch (error: IOException) {
                    DeskStatus(
                        ok = false,
                        headline = "Can't reach the desk",
                        detail = explainTransport(error, settings.baseUrl),
                    )
                }
            }
            _state.update {
                it.copy(
                    configured = true,
                    checking = false,
                    ok = status.ok,
                    headline = status.headline,
                    detail = status.detail,
                    desk = status.desk,
                    gpu = status.gpu,
                    checks = status.checks,
                    recent = recent,
                )
            }
        }
    }

    private fun authOrServer(error: DeskCallException): DeskStatus {
        val message = error.message ?: "The server rejected the connection."
        return DeskStatus(ok = false, headline = statusHeadline(message), detail = message)
    }
}
