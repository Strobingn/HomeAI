package com.homedesk.chat.net

object SettingsAudit {
    private val modelName = Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$")

    fun local(url: String, key: String, model: String): List<SettingCheck> {
        val urlReview = ServerUrl.review(url)
        val keyReview = ApiKey.review(key)
        val trimmed = model.trim()
        val modelState = when {
            modelName.matches(trimmed) -> LinkState.Ok
            else -> LinkState.Bad
        }
        val modelNote = if (modelState == LinkState.Ok) trimmed else "Use a name like qwen2.5:7b."
        return listOf(
            SettingCheck("Server address", urlReview.state, urlReview.note),
            SettingCheck("API key", keyReview.state, keyReview.note),
            SettingCheck("Model", modelState, modelNote),
        )
    }
}
