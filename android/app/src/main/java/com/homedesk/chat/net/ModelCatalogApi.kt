package com.homedesk.chat.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class CatalogModel(
    val model: String,
    val name: String,
    val category: String,
    val description: String,
    @SerialName("download_gb") val downloadGb: Double,
    val source: String,
    val uncensored: Boolean = false,
)

@Serializable
data class ModelCatalog(
    val models: List<CatalogModel>,
    @SerialName("uncensored_pack") val uncensoredPack: List<String>,
    val note: String,
)

@Serializable
data class ModelInstall(
    val id: String? = null,
    val phase: String = "idle",
    val models: List<String> = emptyList(),
    val finished: List<String> = emptyList(),
    val model: String? = null,
    val status: String = "",
    val completed: Long = 0,
    val total: Long = 0,
) {
    val active: Boolean get() = phase == "downloading"
}

@Serializable private data class InstallBody(val models: List<String>)
@Serializable private data class CancelInstall(val id: String)

class ModelCatalogApi(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    fun catalog(url: String, key: String): ModelCatalog =
        json.decodeFromString(call(url, key, "/models/catalog"))

    fun status(url: String, key: String): ModelInstall =
        json.decodeFromString(call(url, key, "/model-installs"))

    fun install(url: String, key: String, models: List<String>): ModelInstall =
        json.decodeFromString(call(url, key, "/model-installs", json.encodeToString(InstallBody.serializer(), InstallBody(models))))

    fun cancel(url: String, key: String, id: String): ModelInstall =
        json.decodeFromString(call(url, key, "/model-installs/cancel", json.encodeToString(CancelInstall.serializer(), CancelInstall(id))))

    private fun call(url: String, key: String, path: String, body: String? = null): String {
        val builder = Request.Builder().url(url.trimEnd('/') + path).header("Authorization", "Bearer $key")
        if (body != null) builder.post(body.toRequestBody(mediaType))
        val call = client.newCall(builder.build())
        call.timeout().timeout(12, TimeUnit.SECONDS)
        call.execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw DeskCallException(httpErrorMessage(response.code, raw, json,
                "Update the Desk PC gateway to 2.3 or later and restart start.bat to install models from this phone."))
            return raw
        }
    }
}
