package com.homedesk.chat.net

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException

fun explainTransport(error: Throwable, url: String): String {
    var current: Throwable? = error
    while (current != null) {
        when (current) {
            is UnknownHostException -> {
                return "That address could not be found. Check the IP, then try the Wi-Fi URL or the Tailscale URL from the server window. ${whereHint(url)}"
            }
            is SocketTimeoutException -> {
                return "The server at $url did not answer in time. Check that start.sh or start.bat is still open on the PC. ${whereHint(url)}"
            }
            is ConnectException -> {
                return "The PC refused the connection at $url. Check the port (8787) and that the Desk window is still open. ${whereHint(url)}"
            }
            is NoRouteToHostException -> {
                return "The phone has no route to $url. ${whereHint(url)}"
            }
            is SSLException -> {
                return "This server uses http:// on your home network or Tailscale. Change the URL so it starts with http://."
            }
        }
        val message = current.message?.lowercase().orEmpty()
        if ("cleartext" in message) {
            return "The phone blocked plain HTTP. Update the Desk app and try the address again."
        }
        if ("network is unreachable" in message || "enetunreach" in message || "no route to host" in message) {
            return "The phone has no route to $url. ${whereHint(url)}"
        }
        current = current.cause
    }
    if (error is IOException) {
        return "Can't reach the server at $url. The PC may be asleep, the Desk window may be closed, or this phone is not on the same Wi-Fi or Tailscale."
    }
    return error.message ?: "Something went wrong talking to the server."
}

private fun whereHint(url: String): String {
    val host = runCatching { URI(url).host }.getOrNull().orEmpty()
    return when {
        host == "localhost" || host == "127.0.0.1" || host == "::1" ->
            "localhost is this phone. Use the PC's Wi-Fi or Tailscale address."
        host.startsWith("100.") ->
            "Turn Tailscale on for this phone, then use the Tailscale URL from the Desk window."
        host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.") ->
            "Join the same Wi-Fi as the PC. Guest networks often block phones from seeing computers."
        else ->
            "The Desk window has to stay open, and this phone has to be on that network."
    }
}
