package com.homedesk.chat

import com.homedesk.chat.net.ServerUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerUrlTest {
    @Test
    fun addsHttpAndStripsATrailingSlash() {
        assertEquals("http://192.168.1.20:8787", ServerUrl.normalize("  192.168.1.20:8787/ "))
        assertEquals("http://192.168.1.20:8787", ServerUrl.normalize("http://192.168.1.20:8787/"))
    }

    @Test
    fun keepsHttpsAndEmpty() {
        assertEquals("", ServerUrl.normalize("   "))
        assertEquals("https://desk.example", ServerUrl.normalize("https://desk.example/"))
    }
    @Test
    fun completesLocalAddressesAndCopiedBanner() {
        assertEquals("http://192.168.1.20:8787", ServerUrl.normalize("192.168.1.20"))
        assertEquals("http://100.90.20.5:8787", ServerUrl.normalize("http://100.90.20.5"))
        assertEquals("http://192.168.1.20:9000", ServerUrl.normalize("Phone URL on this Wi-Fi: http://192.168.1.20:9000"))
        assertEquals("https://desk.example", ServerUrl.normalize("https://desk.example"))
    }

    @Test
    fun rejectsWrongServiceLoopbackAndMalformedInput() {
        for (bad in listOf("http://localhost:8787", "http://127.0.0.1", "http://192.168.1.20:11434", "not an address", "ftp://desk.example", "http://user:pass@desk.example")) {
            assertThrows(IllegalArgumentException::class.java) { ServerUrl.normalize(bad) }
        }
    }

    @Test
    fun acceptsCopiedKeyAssignmentsAndBearerPrefix() {
        assertEquals("abc123", ServerUrl.normalizeKey(" API_KEY=\"abc123\" "))
        assertEquals("abc123", ServerUrl.normalizeKey("Bearer abc123"))
        assertThrows(IllegalArgumentException::class.java) { ServerUrl.normalizeKey("abc def") }
    }
}

