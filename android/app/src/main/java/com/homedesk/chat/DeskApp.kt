package com.homedesk.chat

import android.app.Application
import android.net.Uri
import com.homedesk.chat.data.ChatStore
import com.homedesk.chat.data.SettingsStore
import com.homedesk.chat.net.DeskApi
import com.homedesk.chat.net.FilesApi
import com.homedesk.chat.net.WorkspaceApi
import com.homedesk.chat.net.AppUpdates
import com.homedesk.chat.net.ModelCatalogApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.net.Proxy
import java.util.concurrent.TimeUnit

class DeskApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

class AppGraph(val app: Application) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))
        .build()

    val settings = SettingsStore(app)
    val chats = ChatStore(app)
    val api = DeskApi(http)
    val modelCatalog = ModelCatalogApi(http)
    val updates = AppUpdates(app)
    val files = FilesApi(http)
    val workspace = WorkspaceApi(http)
    val share = ShareBus()
    val fileNav = FileNav()
}

class ShareBus {
    data class Incoming(
        val id: Long = 0,
        val uris: List<Uri> = emptyList(),
        val text: String? = null,
    )

    private val _pending = MutableStateFlow(Incoming())
    val pending: StateFlow<Incoming> = _pending.asStateFlow()

    fun offer(uris: List<Uri>, text: String? = null) {
        if (uris.isEmpty() && text.isNullOrBlank()) return
        _pending.value = Incoming(
            id = System.nanoTime(),
            uris = uris,
            text = text?.takeIf { uris.isEmpty() },
        )
    }

    fun clear() {
        _pending.value = Incoming()
    }
}

class FileNav {
    @Volatile var editorPath: String? = null
    @Volatile var chatAttachment: String? = null
    @Volatile var chatWorkspace: String? = null

    fun consumeWorkspace(): String? {
        val value = chatWorkspace
        chatWorkspace = null
        return value
    }

    fun consumeAttachment(): String? {
        val value = chatAttachment
        chatAttachment = null
        return value?.takeIf { it.isNotBlank() }
    }
}

