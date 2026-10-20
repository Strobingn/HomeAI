package com.homedesk.chat.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class FilesApi(client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = client
    private val uploadClient = client.newBuilder()
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    fun roots(baseUrl: String, apiKey: String): FileRoots {
        return decode(execute(baseUrl, apiKey, "/files/roots"), FileRoots.serializer())
    }

    fun list(baseUrl: String, apiKey: String, path: String): FileList {
        return decode(execute(baseUrl, apiKey, "/files/list", queryPath = path), FileList.serializer())
    }

    fun read(baseUrl: String, apiKey: String, path: String): FileText {
        return decode(execute(baseUrl, apiKey, "/files/read", queryPath = path, seconds = 60), FileText.serializer())
    }

    fun write(baseUrl: String, apiKey: String, path: String, content: String): WriteResult {
        val payload = json.encodeToString(WriteBody.serializer(), WriteBody(path, content))
        val raw = execute(baseUrl, apiKey, "/files/write", method = "PUT", body = payload, seconds = 60)
        return decode(raw, WriteResult.serializer())
    }

    fun makeFolder(baseUrl: String, apiKey: String, path: String) {
        val payload = json.encodeToString(PathBody.serializer(), PathBody(path))
        execute(baseUrl, apiKey, "/files/folder", method = "POST", body = payload)
    }

    fun rename(baseUrl: String, apiKey: String, path: String, name: String) {
        val payload = json.encodeToString(RenameBody.serializer(), RenameBody(path, name))
        execute(baseUrl, apiKey, "/files/rename", method = "POST", body = payload)
    }

    fun delete(baseUrl: String, apiKey: String, path: String) {
        val payload = json.encodeToString(PathBody.serializer(), PathBody(path))
        execute(baseUrl, apiKey, "/files/delete", method = "POST", body = payload)
    }

    fun upload(
        baseUrl: String,
        apiKey: String,
        directory: String,
        name: String,
        file: File,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): SavedFile {
        val fileBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = file.length()
            override fun writeTo(sink: BufferedSink) {
                file.source().use { source -> sink.writeAll(source) }
            }
        }
        var lastReport = 0L
        val progress = ProgressRequestBody(fileBody) { sent, total ->
            val now = System.currentTimeMillis()
            if (sent == total || now - lastReport >= 150) {
                lastReport = now
                onProgress(sent, total)
            }
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("directory", directory)
            .addFormDataPart("file", name, progress)
            .build()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/files/upload")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        try {
            uploadClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.code == 401) throw DeskCallException(rejectedKey)
                if (!response.isSuccessful) throw DeskCallException(httpDetail(raw, response.code))
                val parsed = decode(raw, UploadResult.serializer())
                return parsed.saved.firstOrNull()
                    ?: throw DeskCallException("The server did not confirm that upload.")
            }
        } catch (error: DeskCallException) {
            throw error
        } catch (error: IOException) {
            throw DeskCallException(explainTransport(error, baseUrl))
        }
    }

    fun download(baseUrl: String, apiKey: String, path: String, output: OutputStream) {
        val request = Request.Builder()
            .url(url(baseUrl, "/files/download", path))
            .header("Authorization", "Bearer $apiKey")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 401) throw DeskCallException(rejectedKey)
                if (!response.isSuccessful) {
                    val raw = response.body?.string().orEmpty()
                    throw DeskCallException(httpDetail(raw, response.code))
                }
                val body = response.body ?: throw DeskCallException("The server sent an empty file.")
                body.byteStream().use { input -> input.copyTo(output) }
            }
        } catch (error: DeskCallException) {
            throw error
        } catch (error: IOException) {
            throw DeskCallException(explainTransport(error, baseUrl))
        }
    }

    fun openFileAi(baseUrl: String, apiKey: String, path: String, instruction: String): ChatStream {
        val payload = json.encodeToString(AiBody.serializer(), AiBody(path, instruction))
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/files/ai")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(payload.toRequestBody(jsonType))
            .build()
        return ChatStream(client.newCall(request), json)
    }

    private fun execute(
        baseUrl: String,
        apiKey: String,
        route: String,
        method: String = "GET",
        body: String? = null,
        queryPath: String? = null,
        seconds: Long = 30,
    ): String {
        val builder = Request.Builder()
            .url(url(baseUrl, route, queryPath))
            .header("Authorization", "Bearer $apiKey")
        when (method) {
            "POST" -> builder.post((body ?: "").toRequestBody(jsonType))
            "PUT" -> builder.put((body ?: "").toRequestBody(jsonType))
            else -> builder.get()
        }
        val call = client.newCall(builder.build())
        call.timeout().timeout(seconds, TimeUnit.SECONDS)
        try {
            call.execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.code == 401) throw DeskCallException(rejectedKey)
                if (!response.isSuccessful) throw DeskCallException(httpDetail(raw, response.code))
                return raw
            }
        } catch (error: DeskCallException) {
            throw error
        } catch (error: IOException) {
            throw DeskCallException(explainTransport(error, baseUrl))
        }
    }

    private fun <T> decode(raw: String, serializer: kotlinx.serialization.KSerializer<T>): T {
        return try {
            json.decodeFromString(serializer, raw)
        } catch (_: Exception) {
            throw DeskCallException("The server sent a file reply the app could not read.")
        }
    }

    private fun httpDetail(raw: String, code: Int): String {
        val detail = runCatching { json.decodeFromString(ErrorBody.serializer(), raw).detail }.getOrNull()
        if (!detail.isNullOrBlank()) return detail
        return when (code) {
            403 -> "That folder is outside the drives Desk can use."
            413 -> "That file is too large."
            else -> "The server returned an error ($code)."
        }
    }

    private fun url(baseUrl: String, route: String, queryPath: String?): String {
        val root = baseUrl.trimEnd('/') + route
        if (queryPath == null) return root
        return root + "?path=" + URLEncoder.encode(queryPath, Charsets.UTF_8.name())
    }

    private companion object {
        const val rejectedKey =
            "The API key was rejected. Open config/.env on the PC and copy the value after API_KEY=."
    }
}

private class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (sent: Long, total: Long) -> Unit,
) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        var sent = 0L
        val counting = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                sent += byteCount
                onProgress(sent, total)
            }
        }
        val buffered = counting.buffer()
        delegate.writeTo(buffered)
        buffered.flush()
    }
}

@Serializable
private data class ErrorBody(val detail: String? = null)

@Serializable
private data class PathBody(val path: String)

@Serializable
private data class WriteBody(val path: String, val content: String)

@Serializable
private data class RenameBody(val path: String, val name: String)

@Serializable
private data class AiBody(val path: String, val instruction: String)
