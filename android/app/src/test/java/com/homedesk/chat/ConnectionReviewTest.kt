package com.homedesk.chat

import com.homedesk.chat.net.ApiKey
import com.homedesk.chat.net.HealthDto
import com.homedesk.chat.net.LinkState
import com.homedesk.chat.net.ServerUrl
import com.homedesk.chat.net.SettingsAudit
import com.homedesk.chat.net.interpretHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionReviewTest {
    @Test
    fun apiKeyDropsTheLabelBearerAndQuotes() {
        val pasted = "API_KEY=\"paste-this-into-the-phone-123456\""
        assertEquals("paste-this-into-the-phone-123456", ApiKey.clean(pasted))
        assertEquals("paste-this-into-the-phone-123456", ApiKey.clean("Bearer paste-this-into-the-phone-123456"))
        assertEquals(LinkState.Ok, ApiKey.review(pasted).state)
        assertEquals(LinkState.Bad, ApiKey.review("   ").state)
        assertEquals(LinkState.Warn, ApiKey.review("short-key").state)
    }

    @Test
    fun addressReviewCatchesTheMistakesThatLookLikeADeadPc() {
        assertEquals(LinkState.Bad, ServerUrl.review("http://127.0.0.1:8787").state)
        assertTrue(ServerUrl.review("localhost").note.contains("this phone"))
        assertEquals(LinkState.Bad, ServerUrl.review("http://192.168.1.20:11434").state)
        assertTrue(ServerUrl.review("http://192.168.1.20:11434").note.contains("8787"))

        val bare = ServerUrl.review("192.168.1.20")
        assertEquals(LinkState.Ok, bare.state)
        assertEquals("http://192.168.1.20:8787", bare.normalized)
        assertTrue(bare.note.contains("same Wi-Fi"))

        val tailscale = ServerUrl.review("100.80.1.4")
        assertEquals("http://100.80.1.4:8787", tailscale.normalized)
        assertTrue(tailscale.note.contains("Tailscale"))
    }

    @Test
    fun localChecklistCoversAddressKeyAndModel() {
        val checks = SettingsAudit.local(
            url = "192.168.1.20",
            key = "API_KEY=paste-this-into-the-phone-123456",
            model = "qwen2.5:7b",
        )
        assertEquals(3, checks.size)
        assertTrue(checks.all { it.state == LinkState.Ok })

        val broken = SettingsAudit.local("localhost", "", "bad name")
        assertEquals(LinkState.Bad, broken[0].state)
        assertEquals(LinkState.Bad, broken[1].state)
        assertEquals(LinkState.Bad, broken[2].state)
    }

    @Test
    fun aDownOllamaStillCountsAsReachingTheDesk() {
        val status = interpretHealth(
            HealthDto(status = "degraded", ollama = "down", model = "qwen2.5:7b", detail = "Ollama is not answering."),
        )
        assertFalse(status.ok)
        assertEquals(LinkState.Ok, status.desk)
        assertEquals(LinkState.Bad, status.gpu)
        assertEquals("Ollama is not running", status.headline)
        assertTrue(status.checks.any { it.label == "Desk server" && it.state == LinkState.Ok })
    }
}