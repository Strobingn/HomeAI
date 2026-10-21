package com.homedesk.chat.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HealthDto(
    val status: String = "",
    val ollama: String = "",
    @SerialName("ollama_version") val ollamaVersion: String? = null,
    val model: String? = null,
    @SerialName("model_loaded") val modelLoaded: Boolean = false,
    val detail: String? = null,
    @SerialName("server_version") val serverVersion: String? = null,
    val capabilities: List<String>? = null,
)

data class DeskStatus(
    val ok: Boolean,
    val headline: String,
    val detail: String,
    val listModels: Boolean = false,
)

fun interpretHealth(health: HealthDto): DeskStatus {
    val model = health.model?.takeIf { it.isNotBlank() } ?: "the model"
    val folderReady = health.capabilities?.containsAll(listOf("workspace_chat", "workspace_apply"))
    val gateway = health.serverVersion?.takeIf { it.isNotBlank() }?.let { "PC gateway $it. " }.orEmpty()
    val compatibility = gateway + when (folderReady) {
        true -> "Folder chat and file review are available."
        false -> WORKSPACE_UPDATE_MESSAGE
        null -> "This gateway doesn't report folder support. If folder chat shows Not Found, update the PC gateway. " +
            GATEWAY_UPDATE_INSTRUCTIONS
    }
    if (health.status == "ok" && health.ollama == "up") {
        val detail = if (health.modelLoaded) {
            "$model is loaded and ready."
        } else {
            "$model is selected. The first message loads it."
        }
        val headline = when (folderReady) {
            true -> "Connected"
            false -> "Connected · update PC"
            null -> "Connected · check PC version"
        }
        return DeskStatus(ok = true, headline = headline,
            detail = "$detail\n\n$compatibility", listModels = true)
    }
    val detail = health.detail?.takeIf { it.isNotBlank() }
        ?: "The server answered, but the model is not ready."
    val headline = if (health.ollama == "up") "The model is not ready" else "Ollama is not running"
    return DeskStatus(ok = false, headline = headline, detail = "$detail\n\n$compatibility", listModels = true)
}

