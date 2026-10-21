package com.homedesk.chat.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
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
    Tab("files", "Workspace", Icons.Filled.Folder),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun DeskNav() {
    val nav = rememberNavController()
    val graph = LocalGraph.current
    val current by nav.currentBackStackEntryAsState()
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val wide = with(density) { window.width.toDp() >= 840.dp || (window.width.toDp() >= 600.dp && window.height.toDp() < 480.dp) }
    val showRail = wide && current?.destination?.route in tabs.map { it.route }
    val bar: @Composable () -> Unit = { if (!showRail) DeskBottomBar(nav) }

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

    Row(Modifier.fillMaxSize()) {
      if (showRail) DeskRail(nav)
      NavHost(navController = nav, startDestination = "home", modifier = Modifier.weight(1f)) {
        composable("home") {
            HomeScreen(
                onOpenSettings = {
                    nav.navigate("settings") { launchSingleTop = true }
                },
                onNewChat = ::openNewChat,
                onOpenFiles = { nav.navigate("files") { launchSingleTop = true } },
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
                onFolderChat = { path ->
                    graph.fileNav.chatWorkspace = path
                    openNewChat()
                },
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
                onPickedFolder = { path ->
                    graph.fileNav.chatWorkspace = path
                    nav.popBackStack()
                },
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
                onNewChat = ::openNewChat,
            )
        }
      }
    }
}

@Composable
private fun DeskRail(nav: NavHostController) {
    val current by nav.currentBackStackEntryAsState()
    NavigationRail(modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
        containerColor = MaterialTheme.colorScheme.background,
        header = { Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text("D", Modifier.padding(horizontal = 18.dp, vertical = 14.dp), style = MaterialTheme.typography.titleLarge)
        } }) {
        tabs.forEach { tab ->
            NavigationRailItem(selected = current?.destination?.route == tab.route,
                onClick = { nav.openTab(tab.route) },
                icon = { Icon(tab.icon, null) }, label = { Text(tab.label) })
        }
    }
}

private fun NavHostController.openTab(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun DeskBottomBar(nav: NavHostController) {
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    Column {
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
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
}
