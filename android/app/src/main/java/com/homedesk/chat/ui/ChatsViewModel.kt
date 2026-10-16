package com.homedesk.chat.ui

import androidx.lifecycle.ViewModel
import com.homedesk.chat.AppGraph
import com.homedesk.chat.data.Conversation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChatsViewModel(private val graph: AppGraph) : ViewModel() {
    private val _items = MutableStateFlow<List<Conversation>>(emptyList())
    val items: StateFlow<List<Conversation>> = _items.asStateFlow()

    private val _pendingDelete = MutableStateFlow<Conversation?>(null)
    val pendingDelete: StateFlow<Conversation?> = _pendingDelete.asStateFlow()

    fun reload() {
        _items.value = graph.chats.list()
    }

    fun askDelete(conversation: Conversation) {
        _pendingDelete.value = conversation
    }

    fun dismissDelete() {
        _pendingDelete.value = null
    }

    fun confirmDelete() {
        val id = _pendingDelete.value?.id ?: return
        graph.chats.delete(id)
        _pendingDelete.value = null
        reload()
    }
}
