package com.homedesk.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homedesk.chat.ui.DeskNav
import com.homedesk.chat.ui.LocalGraph
import com.homedesk.chat.ui.theme.DeskTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val incoming = MutableStateFlow(SharedLaunch())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incoming.value = sharedLaunch(intent)
        val graph = (application as DeskApp).graph
        setContent {
            val pending by incoming.collectAsStateWithLifecycle()
            LaunchedEffect(pending.id) {
                if (pending.id == 0L) return@LaunchedEffect
                graph.share.offer(pending.uris, pending.text)
                incoming.value = SharedLaunch()
                setIntent(Intent(Intent.ACTION_MAIN))
            }
            CompositionLocalProvider(LocalGraph provides graph) {
                DeskTheme {
                    DeskNav()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming.value = sharedLaunch(intent)
    }
}

private data class SharedLaunch(
    val id: Long = 0,
    val uris: List<Uri> = emptyList(),
    val text: String? = null,
)

private fun sharedLaunch(intent: Intent?): SharedLaunch {
    val uris = shareUris(intent)
    val text = intent?.takeIf {
        it.action == Intent.ACTION_SEND && uris.isEmpty()
    }?.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
    if (uris.isEmpty() && text.isNullOrBlank()) return SharedLaunch()
    return SharedLaunch(id = System.nanoTime(), uris = uris, text = text)
}

@Suppress("DEPRECATION")
private fun shareUris(intent: Intent?): List<Uri> {
    if (intent == null) return emptyList()
    if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) {
        return emptyList()
    }
    val found = linkedSetOf<Uri>()
    if (intent.action == Intent.ACTION_SEND) {
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { found += it }
    } else {
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { uri ->
            if (uri != null) found += uri
        }
    }
    val clip = intent.clipData
    if (clip != null) {
        for (index in 0 until clip.itemCount) {
            clip.getItemAt(index)?.uri?.let { found += it }
        }
    }
    return found.toList()
}
