package com.homedesk.chat.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

sealed interface StreamEvent {
    data class Token(val text: String) : StreamEvent
    data class Done(val model: String?, val proposed: String? = null) : StreamEvent
    data class Error(val message: String) : StreamEvent
}

object SseParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseLine(line: String): List<StreamEvent> {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return emptyList()
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return emptyList()
        val event = try {
            json.decodeFromString(StreamDto.serializer(), payload)
        } catch (_: SerializationException) {
            return listOf(StreamEvent.Error("The server sent a reply the app could not read."))
        }
        if (!event.error.isNullOrBlank()) {
            return listOf(StreamEvent.Error(event.error))
        }
        val events = mutableListOf<StreamEvent>()
        if (!event.content.isNullOrEmpty()) {
            events += StreamEvent.Token(event.content)
        }
        if (event.done) {
            events += StreamEvent.Done(event.model, event.proposed)
        }
        return events
    }
}

@Serializable
private data class StreamDto(
    val content: String? = null,
    val done: Boolean = false,
    val error: String? = null,
    val model: String? = null,
    val proposed: String? = null,
)
