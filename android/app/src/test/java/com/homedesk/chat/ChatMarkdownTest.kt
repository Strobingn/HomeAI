package com.homedesk.chat

import com.homedesk.chat.ui.messageBlocks
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMarkdownTest {
    @Test
    fun keepsCodeSeparateFromHeadingsAndProse() {
        val blocks = messageBlocks("# Result\n\nHere is the change.\n\n```python\n# code comment\nprint('ok')\n```\n\nDone.")
        assertEquals(listOf("heading", "prose", "code", "prose"), blocks.map { it.kind })
        assertEquals("python", blocks[2].language)
        assertEquals("# code comment\nprint('ok')", blocks[2].text)
    }

    @Test
    fun rendersIncompleteStreamingCodeWithoutLosingText() {
        val blocks = messageBlocks("Thinking\n```kotlin\nval answer = 42")
        assertEquals("prose", blocks.first().kind)
        assertEquals("code", blocks.last().kind)
        assertEquals("val answer = 42", blocks.last().text)
    }
}
