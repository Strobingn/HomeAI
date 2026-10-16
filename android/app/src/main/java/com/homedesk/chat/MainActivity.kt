package com.homedesk.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.homedesk.chat.ui.DeskNav
import com.homedesk.chat.ui.LocalGraph
import com.homedesk.chat.ui.theme.DeskTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as DeskApp).graph
        setContent {
            CompositionLocalProvider(LocalGraph provides graph) {
                DeskTheme {
                    DeskNav()
                }
            }
        }
    }
}
