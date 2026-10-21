package com.homedesk.chat.net

import com.homedesk.chat.data.OutboundMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class DeskCallException(message: String) : IOException(message)

@Serializable
data class ModelInfo(
    val name: String,
    @SerialName("parameter_size") val parameterSize: String? = null,
    val quantization: String? = null,
    val loaded: Boolean = false,
)

class DeskApi(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    fun test(baseUrl: String, apiKey: String): DeskStatus {
        val response = execute(baseUrl, apiKey, "/health", seconds = 12)
        val health = try {
            json.decodeFromString(HealthDto.serializer(), response)
        } catch (_: Exception) {
            return DeskStatus(
                ok = false,
                headline = "Unexpected reply",
                detail = "The server answered, but not in the way Desk expected. Check that the URL points at the Desk server.",
            )
        }
        return interpretHealth(health)
    }

    fun listModels(baseUrl: String, apiKey: String): List<ModelInfo> {
        val response = execute(baseUrl, apiKey, "/models", seconds = 20)
        val parsed = try {
            json.decodeFromString(ModelsDto.serializer(), response)
        } catch (_: Exception) {
            throw DeskCallException("The server sent a model list the app could not read.")
        }
        return parsed.models
    }

    fun selectModel(baseUrl: String, apiKey: String, model: String) {
        val payload = json.encodeToString(ModelBody.serializer(), ModelBody(model))
        execute(baseUrl, apiKey, "/model", method = "PUT", body = payload, seconds = 12)
    }

    fun openChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<OutboundMessage>,
        file: String? = null,
    ): ChatStream {
        val payload = json.encodeToString(
            ChatBody.serializer(),
            ChatBody(model = model, messages = messages, file = file),
        )
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(payload.toRequestBody(mediaType))
            .build()
        return ChatStream(client.newCall(request), json)
    }

    private fun execute(
        baseUrl: String,
        apiKey: String,
        path: String,
        method: String = "GET",
        body: String? = null,
        seconds: Long,
    ): String {
        val url = baseUrl.trimEnd('/') + path
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
        when (method) {
            "PUT" -> builder.put((body ?: "").toRequestBody(mediaType))
            "POST" -> builder.post((body ?: "").toRequestBody(mediaType))
            else -> builder.get()
        }
        val call = client.newCall(builder.build())
        call.timeout().timeout(seconds, TimeUnit.SECONDS)
        try {
            call.execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.code == 401) {
                    throw DeskCallException(
                        "The API key was rejected. Open config/.env on the PC and copy the value after API_KEY=.",
                    )
                }
                if (!response.isSuccessful) {
                    throw DeskCallException(httpDetail(response.code, raw))
                }
                return raw
            }
        } catch (error: DeskCallException) {
            throw error
        } catch (error: IOException) {
            throw DeskCallException(explainTransport(error, baseUrl))
        }
    }

    private fun httpDetail(code: Int, raw: String): String {
        val parsed = runCatching { json.decodeFromString(ErrorDto.serializer(), raw) }.getOrNull()
        val detail = parsed?.detail?.takeIf { it.isNotBlank() }
        if (detail != null) return detail
        return "The server returned an error ($code)."
    }
}

class ChatStream(
    private val call: Call,
    private val json: Json,
) {
    @Volatile
    private var canceled = false

    fun isCanceled(): Boolean = canceled || call.isCanceled()

    fun cancel() {
        canceled = true
        call.cancel()
    }

    fun read(onEvent: (StreamEvent) -> Unit) {
        try {
            call.execute().use { response ->
                val rawError = {
                    response.body?.string().orEmpty()
                }
                if (response.code == 401) {
                    throw DeskCallException(
                        "The API key was rejected. Open config/.env on the PC and copy the value after API_KEY=.",
                    )
                }
                if (!response.isSuccessful) {
                    val raw = rawError()
                    val detail = runCatching {
                        json.decodeFromString(ErrorDto.serializer(), raw).detail
                    }.getOrNull()
                    throw DeskCallException(detail?.takeIf { it.isNotBlank() } ?: "The server returned an error (${response.code}).")
                }
                val source = response.body?.source()
                    ?: throw DeskCallException("The server closed the reply before it started.")
                while (true) {
                    val line = try {
                        source.readUtf8Line()
                    } catch (error: IOException) {
                        if (isCanceled()) return
                        throw error
                    } ?: break
                    for (event in SseParser.parseLine(line)) {
                        onEvent(event)
                    }
                }
            }
        } catch (error: DeskCallException) {
            throw error
        } catch (error: IOException) {
            if (isCanceled()) return
            throw error
        }
    }
}

@Serializable
private data class ErrorDto(val detail: String? = null)

@Serializable
private data class ModelsDto(val models: List<ModelInfo> = emptyList())

@Serializable
private data class ModelBody(val model: String)

@Serializable
private data class ChatBody(
    val model: String,
    val messages: List<OutboundMessage>,
    val file: String? = null,
)

