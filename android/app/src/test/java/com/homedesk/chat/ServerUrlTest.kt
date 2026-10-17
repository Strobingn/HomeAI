package com.homedesk.chat

import com.homedesk.chat.net.ServerUrl
import org.junit.Assert.assertEquals
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
    fun addsTheDeskPortWhenAnAddressOmitsIt() {
        assertEquals("http://192.168.1.20:8787", ServerUrl.normalize("192.168.1.20"))
        assertEquals("http://192.168.1.20:8787", ServerUrl.normalize("http://192.168.1.20"))
    }

    @Test
    fun pullsAUrlOutOfTheServerBannerAndDropsHealth() {
        assertEquals(
            "http://192.168.1.20:8787",
            ServerUrl.normalize("Phone URL on this Wi-Fi:  http://192.168.1.20:8787"),
        )
        assertEquals("http://192.168.1.20:8787", ServerUrl.normalize("http://192.168.1.20:8787/health"))
    }

    @Test
    fun rewritesHomeHttpsToHttp() {
        assertEquals("http://192.168.1.20:8787", ServerUrl.normalize("https://192.168.1.20:8787"))
        assertEquals("http://100.64.1.5:8787", ServerUrl.normalize("https://100.64.1.5"))
    }
}
