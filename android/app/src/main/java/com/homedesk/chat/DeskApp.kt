package com.homedesk.chat

import android.app.Application
import com.homedesk.chat.data.ChatStore
import com.homedesk.chat.data.SettingsStore
import com.homedesk.chat.net.DeskApi
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

class AppGraph(app: Application) {
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
}
