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
        val root = try {
            execute(baseUrl, null, "/", seconds = 8)
        } catch (error: DeskCallException) {
            val detail = error.message ?: "The PC did not answer."
            return DeskStatus(
                ok = false,
                headline = "Can't reach the desk",
                detail = detail,
                desk = LinkState.Bad,
                gpu = LinkState.Unknown,
                checks = listOf(
                    SettingCheck("Desk server", LinkState.Bad, detail),
                    SettingCheck("API key", LinkState.Unknown, "Not checked, because the PC did not answer."),
                    SettingCheck("Ollama", LinkState.Unknown, "Not checked."),
                    SettingCheck("Model", LinkState.Unknown, "Not checked."),
                ),
            )
        }
        if ("Ollama is running" in root && "home-desk" !in root) {
            return notDesk(
                "That address is Ollama, not Desk",
                "Port 11434 is where Ollama listens. Use the Desk URL from the PC window. It ends with :8787.",
            )
        }
        if ("home-desk" !in root) {
            return notDesk(
                "That address is not Desk",
                "Something answered at $baseUrl, but it was not the Desk server. Use the URL printed by start.sh or start.bat.",
            )
        }
        val response = try {
            execute(baseUrl, apiKey, "/health", seconds = 25)
        } catch (error: DeskCallException) {
            return healthFailure(baseUrl, error.message ?: "The status check failed.")
        }
        val health = try {
            json.decodeFromString(HealthDto.serializer(), response)
        } catch (_: Exception) {
            return DeskStatus(
                ok = false,
                headline = "Unexpected reply",
                detail = "The server answered, but not in the way Desk expected. Check that the URL points at the Desk server.",
                desk = LinkState.Warn,
                checks = listOf(
                    SettingCheck("Desk server", LinkState.Warn, "The reply was not a Desk status."),
                ),
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
        execute(baseUrl, apiKey, "/model", method = "PUT", body = payload, seconds = 20)
    }

    fun openChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<OutboundMessage>,
    ): ChatStream {
        val payload = json.encodeToString(
            ChatBody.serializer(),
            ChatBody(model = model, messages = messages),
        )
        val request = try {
            Request.Builder()
                .url(baseUrl.trimEnd('/') + "/chat")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "text/event-stream")
                .post(payload.toRequestBody(mediaType))
                .build()
        } catch (error: IllegalArgumentException) {
            throw DeskCallException(
                "That server URL is not valid. Use the address from the Desk window, like http://192.168.1.20:8787.",
            )
        }
        return ChatStream(client.newCall(request), json)
    }

    private fun execute(
        baseUrl: String,
        apiKey: String?,
        path: String,
        method: String = "GET",
        body: String? = null,
        seconds: Long,
    ): String {
        val url = baseUrl.trimEnd('/') + path
        try {
            val builder = Request.Builder().url(url)
            if (!apiKey.isNullOrEmpty()) {
                builder.header("Authorization", "Bearer $apiKey")
            }
            when (method) {
                "PUT" -> builder.put((body ?: "").toRequestBody(mediaType))
                "POST" -> builder.post((body ?: "").toRequestBody(mediaType))
                else -> builder.get()
            }
            val call = client.newCall(builder.build())
            call.timeout().timeout(seconds, TimeUnit.SECONDS)
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
        } catch (error: IllegalArgumentException) {
            throw DeskCallException(
                "That server URL is not valid. Use the address from the Desk window, like http://192.168.1.20:8787.",
            )
        } catch (error: IOException) {
            throw DeskCallException(explainTransport(error, baseUrl))
        }
    }

    private fun healthFailure(baseUrl: String, message: String): DeskStatus {
        val rejected = "API key" in message
        val slow = "did not answer" in message
        return DeskStatus(
            ok = false,
            headline = when {
                rejected -> "API key was rejected"
                slow -> "Desk is up, status is slow"
                else -> "The desk needs attention"
            },
            detail = if (slow) {
                "The PC answered, but Ollama did not finish the status check in time. It may be loading the model. Wait a moment and test again."
            } else {
                message
            },
            desk = LinkState.Ok,
            gpu = if (slow) LinkState.Warn else LinkState.Unknown,
            checks = listOf(
                SettingCheck("Desk server", LinkState.Ok, "The PC answered at $baseUrl."),
                SettingCheck(
                    "API key",
                    if (rejected) LinkState.Bad else LinkState.Warn,
                    if (rejected) message else "The key was sent, but the status check did not finish.",
                ),
                SettingCheck(
                    "Ollama",
                    if (slow) LinkState.Warn else LinkState.Unknown,
                    if (slow) "Ollama is taking too long to answer." else "Not checked.",
                ),
                SettingCheck("Model", LinkState.Unknown, "Not checked."),
            ),
        )
    }

    private fun notDesk(headline: String, detail: String): DeskStatus {
        return DeskStatus(
            ok = false,
            headline = headline,
            detail = detail,
            desk = LinkState.Bad,
            checks = listOf(SettingCheck("Desk server", LinkState.Bad, detail)),
        )
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
)
