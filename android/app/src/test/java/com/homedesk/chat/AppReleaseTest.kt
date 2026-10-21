package com.homedesk.chat

import com.homedesk.chat.net.AppRelease
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReleaseTest {
    private val prefix = "https://github.com/Strobingn/HomeAI/releases/download/desk-dev/"
    private val release = AppRelease("com.homedesk.chat.dev", 10, "2.3.1-dev", prefix + "Desk-Dev-10.apk", "a".repeat(64), 1234, "b".repeat(64))

    @Test fun acceptsExpectedChannel() { release.validate("com.homedesk.chat.dev", prefix) }

    @Test fun rejectsWrongPackageHostChannelAndUnboundedFiles() {
        val invalid = listOf(
            release.copy(packageId = "other.app"), release.copy(size = -1), release.copy(size = 200L * 1024 * 1024),
            release.copy(sha256 = "bad"), release.copy(certificateSha256 = "bad"), release.copy(versionCode = 0),
            release.copy(url = "http://github.com/Strobingn/HomeAI/releases/download/desk-dev/test.apk"),
            release.copy(url = "https://github.com.evil.example/test.apk"), release.copy(url = prefix + "../test.apk"),
            release.copy(url = prefix.replace("desk-dev", "desk-stable") + "test.apk"), release.copy(url = prefix + "test.apk?x=y"),
        )
        invalid.forEach { assertTrue(runCatching { it.validate("com.homedesk.chat.dev", prefix) }.isFailure) }
    }
}
