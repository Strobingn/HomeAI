package com.homedesk.chat.net

object ServerUrl {
    fun normalize(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""
        if (!text.startsWith("http://") && !text.startsWith("https://")) {
            text = "http://$text"
        }
        while (text.endsWith("/")) {
            text = text.dropLast(1)
        }
        return text
    }
}
