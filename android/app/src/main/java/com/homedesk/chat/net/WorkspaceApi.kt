package com.homedesk.chat.net

import com.homedesk.chat.data.OutboundMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

const val GATEWAY_UPDATE_INSTRUCTIONS = "Update the PC copy of HomeAI to the codex/folder-ai-and-reliability branch, " +
    "close the old gateway window, and run start.bat from the updated folder. " +
    "Installing an APK does not update the PC gateway."

const val WORKSPACE_UPDATE_MESSAGE = "Folder chat is unavailable on this PC gateway. " +
    GATEWAY_UPDATE_INSTRUCTIONS + " Then send your question again."

@Serializable
data class WorkspaceContext(
    val root: String,
    val files: List<String> = emptyList(),
    val indexed: Int = 0,
    val omitted: Int = 0,
    val skipped: Int = 0,
    val truncated: Boolean = false,
)

@Serializable
data class WorkspaceChange(val path: String, val before: String? = null, val content: String)

@Serializable
data class WorkspaceProposal(val id: String, val changes: List<WorkspaceChange>)

class WorkspaceApi(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val type = "application/json; charset=utf-8".toMediaType()

    fun openChat(baseUrl: String, apiKey: String, folder: String, model: String,
                 messages: List<OutboundMessage>): ChatStream {
        val body = WorkspaceBody(folder, model, messages.filter { it.role != "system" })
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/workspace/chat")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(json.encodeToString(WorkspaceBody.serializer(), body).toRequestBody(type))
            .build()
        return ChatStream(client.newCall(request), json, missingRouteMessage = WORKSPACE_UPDATE_MESSAGE)
    }

    fun apply(baseUrl: String, apiKey: String, proposal: WorkspaceProposal) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/workspace/apply")
            .header("Authorization", "Bearer $apiKey")
            .post(json.encodeToString(ApplyBody.serializer(), ApplyBody(proposal.id)).toRequestBody(type))
            .build()
        val call = client.newCall(request)
        call.timeout().timeout(90, TimeUnit.SECONDS)
        call.execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw DeskCallException(httpErrorMessage(response.code, raw, json, WORKSPACE_UPDATE_MESSAGE))
            }
        }
    }
}

@Serializable
private data class WorkspaceBody(val folder: String, val model: String, val messages: List<OutboundMessage>)

@Serializable
private data class ApplyBody(val proposal_id: String)

