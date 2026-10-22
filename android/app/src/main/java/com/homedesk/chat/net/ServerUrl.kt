package com.homedesk.chat.net

import java.net.URI
import java.util.Locale

object ServerUrl {
    fun normalize(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""
        Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE).find(text)?.let { text = it.value }
        if (!text.contains("://")) text = "http://$text"
        val uri = try { URI(text) } catch (_: Exception) {
            throw IllegalArgumentException("Enter a valid Desk URL, such as http://192.168.1.20:8787.")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "Use an http:// or https:// server address." }
        val host = uri.host ?: throw IllegalArgumentException("The address needs a valid PC hostname or IP.")
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Use the server address without a password, query, or fragment."
        }
        require(uri.port == -1 || uri.port in 1..65535) { "The port must be between 1 and 65535." }
        require(uri.port != 11434) { "Port 11434 is Ollama. Use the Desk gateway on port 8787." }
        val bareHost = host.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
        require(bareHost !in setOf("localhost", "127.0.0.1", "::1", "0.0.0.0")) {
            "Use the PC's Wi-Fi or Tailscale address. This address points at the phone itself."
        }
        val local = !bareHost.contains('.') || bareHost.contains(':') ||
            bareHost.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) || bareHost.endsWith(".ts.net") || bareHost.endsWith(".local")
        val port = if (uri.port == -1 && scheme == "http" && local) 8787 else uri.port
        return URI(scheme, null, host, port, uri.path.orEmpty().trimEnd('/'), null, null).toASCIIString()
    }

    fun normalizeKey(raw: String): String {
        var key = raw.trim()
        if (key.startsWith("API_KEY=", ignoreCase = true)) key = key.substringAfter('=').trim()
        key = key.trim('"', '\'')
        if (key.startsWith("Bearer ", ignoreCase = true)) key = key.substring(7).trim()
        require(key.none { it.isISOControl() || it.isWhitespace() }) { "The API key must be one line without spaces." }
        return key
    }
}

