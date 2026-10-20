package com.homedesk.chat

import com.homedesk.chat.net.SseParser
import com.homedesk.chat.net.StreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {
    @Test
    fun parsesTokenDoneAndError() {
        val token = SseParser.parseLine("""data: {"content":"Hi"}""")
        assertEquals(listOf(StreamEvent.Token("Hi")), token)

        val done = SseParser.parseLine("""data: {"done":true,"model":"qwen2.5:7b"}""")
        assertEquals(listOf(StreamEvent.Done("qwen2.5:7b")), done)

        val proposed = SseParser.parseLine("""data: {"done":true,"model":"qwen2.5-coder:14b","proposed":"print(1)\n"}""")
        assertEquals(listOf(StreamEvent.Done("qwen2.5-coder:14b", "print(1)\n")), proposed)

        val error = SseParser.parseLine("""data: {"error":"Ollama is not answering"}""")
        assertEquals(listOf(StreamEvent.Error("Ollama is not answering")), error)
    }

    @Test
    fun ignoresBlankLinesAndDoneMarker() {
        assertTrue(SseParser.parseLine("").isEmpty())
        assertTrue(SseParser.parseLine("data: [DONE]").isEmpty())
        assertTrue(SseParser.parseLine(": keep-alive").isEmpty())
    }

    @Test
    fun reportsUnreadablePayloads() {
        val events = SseParser.parseLine("data: {not json")
        assertTrue(events.single() is StreamEvent.Error)
    }
}
