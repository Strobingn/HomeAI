package com.homedesk.chat.net

import java.net.URI

data class UrlReview(
    val normalized: String,
    val host: String,
    val port: Int,
    val state: LinkState,
    val note: String,
)

object ServerUrl {
    const val DEFAULT_PORT = 8787

    private val embeddedUrl = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val knownPaths = listOf("/health", "/models", "/model", "/chat")

    fun normalize(raw: String): String {
        var text = extract(raw)
        if (text.isEmpty()) return ""
        if (!text.startsWith("http://", ignoreCase = true) && !text.startsWith("https://", ignoreCase = true)) {
            text = "http://$text"
        }
        val schemeSep = text.indexOf("://")
        if (schemeSep > 0) {
            text = text.substring(0, schemeSep).lowercase() + text.substring(schemeSep)
        }
        text = stripTrail(text)
        val uri = runCatching { URI(text) }.getOrNull() ?: return text
        val host = uri.host ?: return text
        var scheme = uri.scheme ?: return text
        var port = uri.port
        if (isHomeHost(host) && scheme == "https" && port in setOf(-1, 443, DEFAULT_PORT)) {
            scheme = "http"
            if (port == 443) port = -1
        }
        if (scheme == "http" && port == -1) {
            port = DEFAULT_PORT
        }
        val hostPart = if (host.contains(':')) "[$host]" else host
        return if (port == -1) "$scheme://$hostPart" else "$scheme://$hostPart:$port"
    }

    fun review(raw: String): UrlReview {
        if (raw.isBlank()) {
            return UrlReview(
                normalized = "",
                host = "",
                port = -1,
                state = LinkState.Bad,
                note = "Paste the address from the Desk window on the PC.",
            )
        }
        val normalized = normalize(raw)
        val uri = runCatching { URI(normalized) }.getOrNull()
        val host = uri?.host.orEmpty()
        val port = uri?.port ?: -1
        if (uri == null || host.isBlank() || uri.scheme !in setOf("http", "https")) {
            return UrlReview(
                normalized = normalized,
                host = host,
                port = port,
                state = LinkState.Bad,
                note = "That address is not a valid URL. It should look like http://192.168.1.20:8787.",
            )
        }
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0.0.0.0") {
            return UrlReview(
                normalized = normalized,
                host = host,
                port = if (port == -1) DEFAULT_PORT else port,
                state = LinkState.Bad,
                note = "That address is this phone, not the PC. Use the Wi-Fi or Tailscale address from the Desk window.",
            )
        }
        if (port == 11434) {
            return UrlReview(
                normalized = normalized,
                host = host,
                port = port,
                state = LinkState.Bad,
                note = "Port 11434 is Ollama. The phone talks to Desk on port 8787.",
            )
        }
        val shownPort = if (port == -1 && uri.scheme == "http") DEFAULT_PORT else port
        val note = when {
            raw.contains("https://", ignoreCase = true) && normalized.startsWith("http://") ->
                "Will save as $normalized. Desk on your home network uses http, not https."
            !hasExplicitPort(raw) ->
                networkNote(host, shownPort) + " Port $DEFAULT_PORT is added when you leave it out."
            else -> networkNote(host, shownPort)
        }
        val state = if (uri.scheme == "https") LinkState.Warn else LinkState.Ok
        return UrlReview(normalized, host, shownPort, state, note)
    }

    private fun extract(raw: String): String {
        var text = raw.trim().trim('"').trim('\'')
        if (text.isEmpty()) return ""
        val found = embeddedUrl.find(text)
        if (found != null) {
            text = found.value
        }
        return text.trimEnd('.', ',', ';', ')')
    }

    private fun stripTrail(text: String): String {
        var value = text
        while (value.endsWith("/")) value = value.dropLast(1)
        var changed = true
        while (changed) {
            changed = false
            for (suffix in knownPaths) {
                if (value.endsWith(suffix, ignoreCase = true)) {
                    value = value.dropLast(suffix.length)
                    while (value.endsWith("/")) value = value.dropLast(1)
                    changed = true
                }
            }
        }
        return value
    }

    private fun hasExplicitPort(raw: String): Boolean {
        val candidate = extract(raw).substringAfter("://")
        val hostAndPort = candidate.substringBefore("/")
        val portPart = if (hostAndPort.startsWith("[")) {
            hostAndPort.substringAfter("]", "")
        } else {
            val colon = hostAndPort.lastIndexOf(':')
            if (colon == -1) "" else hostAndPort.substring(colon)
        }
        return portPart.startsWith(":") && portPart.drop(1).all { it.isDigit() }
    }

    private fun networkNote(host: String, port: Int): String {
        val onPort = if (port > 0) " on port $port" else ""
        return when {
            host.startsWith("100.") ->
                "Tailscale address$onPort. Tailscale has to be connected on this phone."
            isHomeHost(host) ->
                "Home address$onPort. This phone has to be on the same Wi-Fi as the PC."
            else -> "Address looks ready$onPort."
        }
    }

    private fun isHomeHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local")) return true
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return false
        val numbers = parts.map { it.toInt() }
        if (numbers.any { it !in 0..255 }) return false
        val (a, b) = numbers
        return a == 10 ||
            a == 127 ||
            (a == 192 && b == 168) ||
            (a == 172 && b in 16..31) ||
            (a == 100 && b in 64..127)
    }
}
