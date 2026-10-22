package com.homedesk.chat.data

import kotlinx.serialization.Serializable
import com.homedesk.chat.net.WorkspaceProposal

object DeskDefaults {
    const val MODEL = "qwen2.5:7b"
}

@Serializable
data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val error: Boolean = false,
    val proposal: WorkspaceProposal? = null,
    val applied: Boolean = false,
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val model: String,
    val updatedAt: Long,
    val messages: List<ChatMessage> = emptyList(),
    val workspace: String? = null,
)

@Serializable
data class OutboundMessage(
    val role: String,
    val content: String,
)

fun conversationTitle(messages: List<ChatMessage>): String {
    val first = messages.firstOrNull { it.role == "user" && it.content.isNotBlank() }?.content
        ?: return "New chat"
    val oneLine = first.lineSequence().first().trim()
    return if (oneLine.length <= 48) oneLine else oneLine.take(47).trimEnd() + "…"
}

/** What the model should see: recent real turns, without error placeholders. */
fun modelHistory(messages: List<ChatMessage>, limit: Int = 40): List<OutboundMessage> {
    return messages
        .asSequence()
        .filter { message ->
            !message.error &&
                message.content.isNotBlank() &&
                message.role in setOf("system", "user", "assistant")
        }
        .map { OutboundMessage(it.role, it.content) }
        .toList()
        .takeLast(limit)
}

