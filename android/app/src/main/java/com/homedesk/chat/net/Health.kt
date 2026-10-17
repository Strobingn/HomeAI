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

enum class LinkState { Unknown, Ok, Warn, Bad }

data class SettingCheck(
    val label: String,
    val state: LinkState,
    val detail: String,
)

data class DeskStatus(
    val ok: Boolean,
    val headline: String,
    val detail: String,
    val listModels: Boolean = false,
    val desk: LinkState = LinkState.Unknown,
    val gpu: LinkState = LinkState.Unknown,
    val checks: List<SettingCheck> = emptyList(),
)

fun interpretHealth(health: HealthDto): DeskStatus {
    val model = health.model?.takeIf { it.isNotBlank() } ?: "the model"
    val reached = listOf(
        SettingCheck("Desk server", LinkState.Ok, "The PC answered."),
        SettingCheck("API key", LinkState.Ok, "The key was accepted."),
    )
    if (health.status == "ok" && health.ollama == "up") {
        val detail = if (health.modelLoaded) {
            "$model is loaded and ready."
        } else {
            "$model is selected. The first message loads it."
        }
        val version = health.ollamaVersion?.takeIf { it.isNotBlank() }
        val modelState = if (health.modelLoaded) LinkState.Ok else LinkState.Warn
        return DeskStatus(
            ok = true,
            headline = "Connected",
            detail = detail,
            listModels = true,
            desk = LinkState.Ok,
            gpu = modelState,
            checks = reached + listOf(
                SettingCheck(
                    "Ollama",
                    LinkState.Ok,
                    if (version != null) "Ollama $version is running." else "Ollama is running.",
                ),
                SettingCheck("Model", modelState, detail),
            ),
        )
    }
    val detail = health.detail?.takeIf { it.isNotBlank() }
        ?: "The server answered, but the model is not ready."
    val ollamaUp = health.ollama == "up"
    val headline = if (ollamaUp) "The model is not ready" else "Ollama is not running"
    return DeskStatus(
        ok = false,
        headline = headline,
        detail = detail,
        listModels = true,
        desk = LinkState.Ok,
        gpu = if (ollamaUp) LinkState.Warn else LinkState.Bad,
        checks = reached + listOf(
            SettingCheck(
                "Ollama",
                if (ollamaUp) LinkState.Ok else LinkState.Bad,
                if (ollamaUp) "Ollama is running." else detail,
            ),
            SettingCheck(
                "Model",
                if (ollamaUp) LinkState.Warn else LinkState.Unknown,
                if (ollamaUp) detail else "Waiting on Ollama.",
            ),
        ),
    )
}
