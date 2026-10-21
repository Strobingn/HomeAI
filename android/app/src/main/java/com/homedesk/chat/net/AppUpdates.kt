package com.homedesk.chat.net

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.homedesk.chat.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

@Serializable
data class AppRelease(
    val packageId: String,
    val versionCode: Long,
    val versionName: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val certificateSha256: String,
) {
    fun validate(packageName: String, prefix: String) {
        require(packageId == packageName) { "This update is for a different app." }
        require(versionCode > 0 && versionName.isNotBlank() && versionName.length <= 80) { "Invalid update version." }
        val parsed = url.toHttpUrlOrNull()
        require(parsed != null && parsed.username.isEmpty() && parsed.password.isEmpty() &&
            url.startsWith(prefix) && parsed.query == null && parsed.fragment == null &&
            url.removePrefix(prefix).matches(Regex("[A-Za-z0-9._-]+\\.apk"))) { "The APK must come from the Desk release channel." }
        require(sha256.matches(Regex("[a-fA-F0-9]{64}")) && certificateSha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid update checksums." }
        require(size in 1..MAX_APK_BYTES) { "Invalid update size." }
    }
}

internal const val MAX_APK_BYTES = 150L * 1024 * 1024

data class AppUpdateState(
    val busy: Boolean = false,
    val message: String = "Check for a signed app update. Your chats and settings stay on this phone.",
    val release: AppRelease? = null,
    val progress: Float? = null,
    val ready: Boolean = false,
)

/** Separate HTTPS client: the PC API key is never attached to update requests. */
class AppUpdates(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).callTimeout(15, TimeUnit.MINUTES)
        .followSslRedirects(false).build()
    private val channel = if (BuildConfig.DEBUG) "dev" else "stable"
    val releasePage = "https://github.com/Strobingn/HomeAI/releases/tag/desk-$channel"
    private val prefix = "https://github.com/Strobingn/HomeAI/releases/download/desk-$channel/"
    private val directory = File(app.filesDir, "updates")
    private val apk = File(directory, "update.apk")
    private val saved = File(directory, "update.json")
    private var job: Job? = null
    @Volatile private var activeCall: Call? = null
    private val _state = MutableStateFlow(AppUpdateState())
    val state = _state.asStateFlow()

    init {
        job = scope.launch {
            if (apk.isFile && saved.isFile) {
                try {
                    val release = json.decodeFromString<AppRelease>(saved.readText())
                    verify(apk, release)
                    _state.value = AppUpdateState(release = release, ready = true, message = "${release.versionName} is downloaded and verified. Tap Install update.")
                } catch (_: Exception) {
                    apk.delete()
                    saved.delete()
                }
            }
        }
    }

    fun check() = runOperation {
        _state.value = AppUpdateState(busy = true, message = "Checking the $channel channel…")
        val raw = request(prefix + "$channel-update.json", maxBytes = 64 * 1024)
        val release = json.decodeFromString<AppRelease>(raw)
        release.validate(app.packageName, prefix)
        if (release.versionCode <= BuildConfig.VERSION_CODE) {
            apk.delete(); saved.delete()
            _state.value = AppUpdateState(message = "You're up to date · ${BuildConfig.VERSION_NAME}.")
        } else {
            checkCertificate(release)
            _state.value = AppUpdateState(release = release, message = "${release.versionName} is available · ${release.size / (1024 * 1024)} MB.")
        }
    }

    fun download() {
        val release = _state.value.release ?: return
        runOperation {
            checkCertificate(release)
            directory.mkdirs()
            val temporary = File(directory, "download.apk")
            _state.update { it.copy(busy = true, ready = false, progress = 0f, message = "Downloading ${release.versionName}…") }
            try {
                val call = client.newCall(Request.Builder().url(release.url).build())
                activeCall = call
                call.execute().use { response ->
                    if (!response.isSuccessful || !response.request.url.isHttps) throw IOException("The update download failed (${response.code}).")
                    val body = response.body ?: throw IOException("Empty update download.")
                    if (body.contentLength() > MAX_APK_BYTES) throw IOException("The update is too large.")
                    body.byteStream().use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var count = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                count += read
                                if (count > release.size || count > MAX_APK_BYTES) throw IOException("The update size did not match.")
                                output.write(buffer, 0, read)
                                _state.update { it.copy(progress = (count.toFloat() / release.size).coerceIn(0f, 1f)) }
                            }
                        }
                    }
                }
                verify(temporary, release)
                if (apk.exists() && !apk.delete()) throw IOException("Couldn't replace the earlier download.")
                if (!temporary.renameTo(apk)) throw IOException("Couldn't save the downloaded update.")
                saved.writeText(json.encodeToString(AppRelease.serializer(), release))
                _state.update { it.copy(busy = false, ready = true, progress = null, message = "Download verified. Tap Install update; Android will ask you to confirm.") }
            } finally {
                temporary.delete()
            }
        }
    }

    fun cancel() {
        job?.cancel()
        activeCall?.cancel()
    }

    fun install() = runOperation {
        val release = _state.value.release ?: throw IOException("Check for updates first.")
        verify(apk, release)
        withContext(Dispatchers.Main) {
            if (!app.packageManager.canRequestPackageInstalls()) {
                _state.update { it.copy(message = "Allow updates from Desk on the Android screen, return here, then tap Install update again.") }
                app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", apk)
                app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
    }

    private fun runOperation(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            try { block() }
            catch (error: Exception) {
                val cancelled = error is CancellationException || !coroutineContext.isActive
                _state.update { it.copy(busy = false, ready = false, progress = null,
                    message = if (cancelled) "Stopped. You can retry." else error.message ?: "The update could not finish. Try again.") }
                if (cancelled) throw CancellationException("Update stopped", error)
            }
            finally { activeCall = null }
        }
    }

    private fun request(url: String, maxBytes: Long): String {
        val call = client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build())
        activeCall = call
        call.execute().use { response ->
            if (response.code == 404) throw IOException("No signed update is published for this channel yet. The repository owner needs to configure private signing once.")
            if (!response.isSuccessful || !response.request.url.isHttps) throw IOException("Couldn't check for updates (${response.code}). Try again.")
            val body = response.body ?: throw IOException("Empty update manifest.")
            val bytes = java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > maxBytes) throw IOException("The update manifest is too large.")
                    bytes.write(buffer, 0, count)
                }
            }
            return bytes.toString("UTF-8")
        }
    }

    private fun checkCertificate(release: AppRelease) {
        release.validate(app.packageName, prefix)
        val installed = app.packageManager.getPackageInfo(app.packageName, signatureFlags())
        if (certificates(installed) != setOf(release.certificateSha256.lowercase())) {
            throw IOException("This build uses a different signing key. Install a privately signed Desk build once from Releases to start receiving in-app updates. Android cannot update across different signing keys.")
        }
    }

    private fun verify(file: File, release: AppRelease) {
        checkCertificate(release)
        if (release.versionCode <= BuildConfig.VERSION_CODE) throw IOException("This update is already installed.")
        if (!file.isFile || file.length() != release.size) throw IOException("The update is incomplete. Download it again.")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        if (!digest.digest().hex().equals(release.sha256, ignoreCase = true)) throw IOException("The update checksum did not match. Download it again.")
        val archive = app.packageManager.getPackageArchiveInfo(file.path, signatureFlags()) ?: throw IOException("The download isn't a valid APK.")
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        if (archive.packageName != app.packageName || code != release.versionCode || archive.versionName != release.versionName ||
            certificates(archive) != setOf(release.certificateSha256.lowercase())) throw IOException("The APK identity or signing certificate did not match.")
    }

    @Suppress("DEPRECATION")
    private fun signatureFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
