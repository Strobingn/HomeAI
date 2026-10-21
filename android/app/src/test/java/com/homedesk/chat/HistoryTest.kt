package com.homedesk.chat

import com.homedesk.chat.data.ChatMessage
import com.homedesk.chat.data.conversationTitle
import com.homedesk.chat.data.modelHistory
import com.homedesk.chat.net.HealthDto
import com.homedesk.chat.net.interpretHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    @Test
    fun titleUsesTheFirstUserLine() {
        val messages = listOf(
            ChatMessage("1", "user", "Where are the attic vents?\nSecond line", 1L),
        )
        assertEquals("Where are the attic vents?", conversationTitle(messages))
    }

    @Test
    fun historyDropsErrorsAndKeepsTheLatestTurns() {
        val messages = (1..50).map { index ->
            ChatMessage(index.toString(), if (index % 2 == 0) "assistant" else "user", "m$index", index.toLong())
        } + ChatMessage("err", "assistant", "boom", 99L, error = true)
        val history = modelHistory(messages, limit = 40)
        assertEquals(40, history.size)
        assertTrue(history.none { it.content == "boom" })
        assertEquals("m11", history.first().content)
        assertEquals("m50", history.last().content)
    }

    @Test
    fun healthCopyDistinguishesAWarmModelFromAMissingOne() {
        val ready = interpretHealth(
            HealthDto(status = "ok", ollama = "up", model = "qwen2.5:7b", modelLoaded = true),
        )
        assertTrue(ready.ok)
        assertEquals("Connected", ready.headline)
        assertTrue(ready.detail.contains("loaded"))
        assertEquals(null, ready.serverVersion)

        val withServer = interpretHealth(
            HealthDto(status = "ok", ollama = "up", model = "qwen2.5:7b", modelLoaded = true, version = "1.1.2"),
        )
        assertEquals("1.1.2", withServer.serverVersion)

        val missing = interpretHealth(
            HealthDto(
                status = "degraded",
                ollama = "up",
                model = "qwen2.5:7b",
                detail = "qwen2.5:7b is not downloaded. On this PC run: ollama pull qwen2.5:7b",
            ),
        )
        assertFalse(missing.ok)
        assertTrue(missing.listModels)
        assertTrue(missing.detail.contains("ollama pull"))
    }
}
