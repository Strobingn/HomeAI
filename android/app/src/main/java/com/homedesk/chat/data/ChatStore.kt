package com.homedesk.chat.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ChatStore(context: Context) {
    private val file = File(context.filesDir, "chats.json")
    private val lock = ReentrantLock()
    private val json = Json { ignoreUnknownKeys = true }
    private var memory: StoreFile? = null

    fun list(): List<Conversation> = lock.withLock {
        load().conversations.sortedByDescending { it.updatedAt }
    }

    fun get(id: String): Conversation? = lock.withLock {
        load().conversations.firstOrNull { it.id == id }
    }

    fun upsert(conversation: Conversation) = lock.withLock {
        val others = load().conversations.filter { it.id != conversation.id }
        save(StoreFile(others + conversation))
    }

    fun delete(id: String) = lock.withLock {
        save(StoreFile(load().conversations.filter { it.id != id }))
    }

    private fun load(): StoreFile {
        memory?.let { return it }
        if (!file.exists()) {
            val empty = StoreFile()
            memory = empty
            return empty
        }
        val parsed = runCatching {
            json.decodeFromString(StoreFile.serializer(), file.readText())
        }.getOrElse { StoreFile() }
        memory = parsed
        return parsed
    }

    private fun save(data: StoreFile) {
        memory = data
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "chats.json.tmp")
        temporary.writeText(json.encodeToString(StoreFile.serializer(), data))
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
    }
}

@Serializable
private data class StoreFile(
    val conversations: List<Conversation> = emptyList(),
)
