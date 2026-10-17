package com.homedesk.chat.net

data class KeyReview(
    val cleaned: String,
    val state: LinkState,
    val note: String,
)

object ApiKey {
    private val assignment = Regex("""(?i)API_KEY\s*=\s*(\S+)""")

    fun clean(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""
        val assigned = assignment.find(text)
        text = if (assigned != null) {
            assigned.groupValues[1]
        } else {
            text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                .orEmpty()
        }
        text = text.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
        if (text.length >= 2) {
            val wrapped = (text.first() == '"' && text.last() == '"') ||
                (text.first() == '\'' && text.last() == '\'')
            if (wrapped) {
                text = text.substring(1, text.length - 1).trim()
            }
        }
        return text
    }

    fun review(raw: String): KeyReview {
        val cleaned = clean(raw)
        if (cleaned.isEmpty()) {
            return KeyReview(
                cleaned = "",
                state = LinkState.Bad,
                note = "Paste the value after API_KEY= from config/.env. Leave the label out, or paste the whole line.",
            )
        }
        if (cleaned.any { it.isWhitespace() }) {
            return KeyReview(
                cleaned = cleaned,
                state = LinkState.Bad,
                note = "The key still has a space in it. Copy only the value after API_KEY=.",
            )
        }
        if (cleaned.length < 16) {
            return KeyReview(
                cleaned = cleaned,
                state = LinkState.Warn,
                note = "This is shorter than the key start.sh prints. Check that the whole value was copied.",
            )
        }
        val note = if (raw.contains("API_KEY", ignoreCase = true)) {
            "The API_KEY= label is removed when this is saved."
        } else {
            "Key is ready to test."
        }
        return KeyReview(cleaned, LinkState.Ok, note)
    }
}
