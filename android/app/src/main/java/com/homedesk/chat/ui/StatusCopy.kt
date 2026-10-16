package com.homedesk.chat.ui

internal fun statusHeadline(message: String): String {
    return when {
        "API key" in message -> "API key was rejected"
        "could not be found" in message ||
            "did not answer" in message ||
            "refused the connection" in message ||
            "Can't reach" in message ||
            "starts with http://" in message -> "Can't reach the desk"
        else -> "The desk needs attention"
    }
}
