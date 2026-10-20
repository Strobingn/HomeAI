package com.homedesk.chat.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.homedesk.chat.AppGraph
import com.homedesk.chat.ui.theme.LocalChrome
import java.util.UUID

val LocalGraph = compositionLocalOf<AppGraph> { error("Desk graph is missing") }

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Filled.Home),
    Tab("chats", "Chats", Icons.AutoMirrored.Filled.Chat),
    Tab("files", "Files", Icons.Filled.Folder),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun DeskNav() {
    val nav = rememberNavController()
    val graph = LocalGraph.current
    val bar: @Composable () -> Unit = { DeskBottomBar(nav) }

    LaunchedEffect(graph) {
        graph.share.pending.collect { incoming ->
            if (incoming.id == 0L) return@collect
            nav.navigate("files") {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    fun openNewChat() {
        nav.navigate("chat/${UUID.randomUUID()}")
    }

    fun openFile(path: String) {
        graph.fileNav.editorPath = path
        nav.navigate("file")
    }

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpenSettings = {
                    nav.navigate("settings") { launchSingleTop = true }
                },
                onNewChat = ::openNewChat,
                onOpenChat = { id -> nav.navigate("chat/$id") },
                bottomBar = bar,
            )
        }
        composable("chats") {
            ChatsScreen(
                onNewChat = ::openNewChat,
                onOpenChat = { id -> nav.navigate("chat/$id") },
                bottomBar = bar,
            )
        }
        composable("settings") { SettingsScreen(bottomBar = bar) }
        composable("files") {
            FilesScreen(
                picking = false,
                onOpenFile = ::openFile,
                onPicked = {},
                onOpenSettings = {
                    nav.navigate("settings") { launchSingleTop = true }
                },
                bottomBar = bar,
            )
        }
        composable("files-pick") {
            FilesScreen(
                picking = true,
                acceptShares = false,
                onOpenFile = {},
                onPicked = { path ->
                    graph.fileNav.chatAttachment = path
                    nav.popBackStack()
                },
                onOpenSettings = {
                    nav.navigate("settings") { launchSingleTop = true }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable("file") {
            val path = graph.fileNav.editorPath
            if (path.isNullOrBlank()) {
                FileEditorScreen(path = "", onBack = { nav.popBackStack() })
            } else {
                FileEditorScreen(path = path, onBack = { nav.popBackStack() })
            }
        }
        composable(
            route = "chat/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            ChatScreen(
                conversationId = entry.arguments?.getString("id").orEmpty(),
                onBack = { nav.popBackStack() },
                onAttach = { nav.navigate("files-pick") },
            )
        }
    }
}

@Composable
private fun DeskBottomBar(nav: NavHostController) {
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    NavigationBar(
        containerColor = LocalChrome.current.navBar,
        tonalElevation = 0.dp,
    ) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = route == tab.route || (tab.route == "files" && route == "file"),
                onClick = {
                    if (route != tab.route) {
                        nav.navigate(tab.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = LocalChrome.current.navSelected,
                    selectedTextColor = LocalChrome.current.navSelected,
                    indicatorColor = LocalChrome.current.navIndicator,
                    unselectedIconColor = LocalChrome.current.navUnselected,
                    unselectedTextColor = LocalChrome.current.navUnselected,
                ),
            )
        }
    }
}
