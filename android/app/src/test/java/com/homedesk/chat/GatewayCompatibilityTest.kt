package com.homedesk.chat

import com.homedesk.chat.data.OutboundMessage
import com.homedesk.chat.net.DeskCallException
import com.homedesk.chat.net.HealthDto
import com.homedesk.chat.net.StreamEvent
import com.homedesk.chat.net.WORKSPACE_UPDATE_MESSAGE
import com.homedesk.chat.net.WorkspaceApi
import com.homedesk.chat.net.WorkspaceProposal
import com.homedesk.chat.net.interpretHealth
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class GatewayCompatibilityTest {
    private val client = OkHttpClient()

    @Test
    fun oldGatewayConnectsButWarnsThatFolderChatNeedsAPcUpdate() {
        val old = Json.decodeFromString<HealthDto>("""{"status":"ok","ollama":"up","model":"qwen2.5:7b"}""")
        val status = interpretHealth(old)
        assertTrue(status.ok)
        assertTrue(status.listModels)
        assertEquals("Connected · check PC version", status.headline)
        assertTrue(status.detail.contains("doesn't report folder support"))
        assertTrue(status.detail.contains("Installing an APK does not update the PC gateway"))
    }

    @Test
    fun updatedGatewayReportsVersionAndFolderSupport() {
        val status = interpretHealth(HealthDto(status = "ok", ollama = "up", serverVersion = "2.1.1",
            capabilities = listOf("workspace_chat", "workspace_apply")))
        assertEquals("Connected", status.headline)
        assertTrue(status.detail.contains("PC gateway 2.1.1"))
        assertTrue(status.detail.contains("file review are available"))
        assertFalse(status.detail.contains("Update the PC"))
    }

    @Test
    fun missingWorkspaceRouteExplainsHowToUpdateThePc() {
        withServer(404, """{"detail":"Not Found"}""") { url ->
            val error = assertThrows(DeskCallException::class.java) {
                WorkspaceApi(client).openChat(url, "key", "F:/home AI App", "qwen2.5:7b",
                    listOf(OutboundMessage("user", "Hi"))).read { fail("A 404 is not a model response") }
            }
            assertEquals(WORKSPACE_UPDATE_MESSAGE, error.message)
        }
    }

    @Test
    fun genuineFolderErrorsArePreserved() {
        withServer(404, """{"detail":"That folder no longer exists."}""") { url ->
            val error = assertThrows(DeskCallException::class.java) {
                WorkspaceApi(client).openChat(url, "key", "F:/gone", "qwen2.5:7b",
                    listOf(OutboundMessage("user", "Hi"))).read {}
            }
            assertEquals("That folder no longer exists.", error.message)
        }
    }

    @Test
    fun applyDistinguishesAnOldGatewayFromAnExpiredProposalAndBadKey() {
        for ((code, raw, expected) in listOf(
            Triple(404, """{"detail":"Not Found"}""", WORKSPACE_UPDATE_MESSAGE),
            Triple(410, """{"detail":"This proposal expired. Ask the AI again."}""", "This proposal expired. Ask the AI again."),
            Triple(401, """{"detail":"Unauthorized"}""", "The API key was rejected."),
        )) {
            withServer(code, raw) { url ->
                val error = assertThrows(DeskCallException::class.java) {
                    WorkspaceApi(client).apply(url, "key", WorkspaceProposal("proposal-id", emptyList()))
                }
                assertTrue(error.message.orEmpty().startsWith(expected))
            }
        }
    }

    @Test
    fun updatedWorkspaceRouteStillStreamsContextAndReply() {
        withServer(200, "data: {\"context\":{\"root\":\"F:/project\",\"files\":[\"README.md\"]}}\n\n" +
            "data: {\"content\":\"Hello\"}\n\ndata: {\"done\":true}\n\n") { url ->
            val events = mutableListOf<StreamEvent>()
            WorkspaceApi(client).openChat(url, "key", "F:/project", "qwen2.5:7b",
                listOf(OutboundMessage("user", "Hi"))).read { events.add(it) }
            assertEquals(listOf("README.md"), (events[0] as StreamEvent.Context).report.files)
            assertEquals("Hello", (events[1] as StreamEvent.Token).text)
            assertTrue(events[2] is StreamEvent.Done)
        }
    }

    private fun withServer(code: Int, body: String, block: (String) -> Unit) {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
        server.start()
        try { block(server.url("/").toString().trimEnd('/')) }
        finally { server.shutdown() }
    }
}
