package com.homedesk.chat.net

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

fun explainTransport(error: Throwable, url: String): String {
    var current: Throwable? = error
    while (current != null) {
        when (current) {
            is UnknownHostException -> {
                return "That address could not be found. Check the IP, then try the Wi-Fi URL or the Tailscale URL from the server window."
            }
            is SocketTimeoutException -> {
                return "The server at $url did not answer in time. Check that start.sh or start.bat is still open on the PC."
            }
            is ConnectException -> {
                return "The PC refused the connection at $url. Check the port (8787) and that the Desk window is still open."
            }
            is SSLException -> {
                return "This server uses http:// on your home network or Tailscale. Change the URL so it starts with http://."
            }
        }
        val message = current.message?.lowercase().orEmpty()
        if ("cleartext" in message) {
            return "The phone blocked plain HTTP. Update the Desk app and try the address again."
        }
        current = current.cause
    }
    if (error is IOException) {
        return "Can't reach the server at $url. The PC may be asleep, the Desk window may be closed, or this phone is not on the same Wi-Fi or Tailscale."
    }
    return error.message ?: "Something went wrong talking to the server."
}
