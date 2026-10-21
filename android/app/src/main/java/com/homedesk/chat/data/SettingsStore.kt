package com.homedesk.chat.data

import android.content.Context
import com.homedesk.chat.net.ServerUrl

data class DeskSettings(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
) {
    val isReady: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && runCatching { ServerUrl.normalize(baseUrl); ServerUrl.normalizeKey(apiKey) }.isSuccess
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("desk", Context.MODE_PRIVATE)

    fun get(): DeskSettings {
        return DeskSettings(
            baseUrl = prefs.getString(URL, "").orEmpty(),
            apiKey = prefs.getString(KEY, "").orEmpty(),
            model = prefs.getString(MODEL, DeskDefaults.MODEL).orEmpty().ifBlank { DeskDefaults.MODEL },
        )
    }

    fun save(settings: DeskSettings) {
        val model = settings.model.trim().ifBlank { DeskDefaults.MODEL }
        prefs.edit()
            .putString(URL, ServerUrl.normalize(settings.baseUrl))
            .putString(KEY, ServerUrl.normalizeKey(settings.apiKey))
            .putString(MODEL, model)
            .commit()
    }

    private companion object {
        const val URL = "base_url"
        const val KEY = "api_key"
        const val MODEL = "model"
    }
}

