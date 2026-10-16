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
)

data class DeskStatus(
    val ok: Boolean,
    val headline: String,
    val detail: String,
    val listModels: Boolean = false,
)

fun interpretHealth(health: HealthDto): DeskStatus {
    val model = health.model?.takeIf { it.isNotBlank() } ?: "the model"
    if (health.status == "ok" && health.ollama == "up") {
        val detail = if (health.modelLoaded) {
            "$model is loaded and ready."
        } else {
            "$model is selected. The first message loads it."
        }
        return DeskStatus(ok = true, headline = "Connected", detail = detail, listModels = true)
    }
    val detail = health.detail?.takeIf { it.isNotBlank() }
        ?: "The server answered, but the model is not ready."
    val headline = if (health.ollama == "up") "The model is not ready" else "Ollama is not running"
    return DeskStatus(ok = false, headline = headline, detail = detail, listModels = true)
}
