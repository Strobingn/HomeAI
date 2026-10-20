package com.homedesk.chat

import com.homedesk.chat.data.joinPcPath
import com.homedesk.chat.data.pcFileName
import com.homedesk.chat.data.previewDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileDiffTest {
    @Test
    fun identicalTextHasNoRows() {
        assertTrue(previewDiff("alpha\nbeta\n", "alpha\nbeta\n").isEmpty())
    }

    @Test
    fun showsTheChangedLineAndKeepsContext() {
        val rows = previewDiff("one\ntwo\nthree\n", "one\nTWO\nthree\n")
        assertTrue(rows.any { it.kind == "remove" && it.text == "two" })
        assertTrue(rows.any { it.kind == "add" && it.text == "TWO" })
        assertTrue(rows.any { it.kind == "same" && it.text == "one" })
    }
}

class PcPathTest {
    @Test
    fun joinsWindowsDrivesAndPosixPaths() {
        assertEquals("C:\\Users\\Desktop", joinPcPath("C:\\Users", "Desktop"))
        assertEquals("C:\\notes.txt", joinPcPath("C:\\", "notes.txt"))
        assertEquals("/home/a", joinPcPath("/home", "a"))
        assertEquals("/etc", joinPcPath("/", "etc"))
    }

    @Test
    fun readsTheFileName() {
        assertEquals("notes.txt", pcFileName("C:\\Users\\notes.txt"))
        assertEquals("C:", pcFileName("C:\\"))
    }
}
