package com.homedesk.chat.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun DeskNav() {
    val nav = rememberNavController()
    val bar: @Composable () -> Unit = { DeskBottomBar(nav) }

    fun openNewChat() {
        nav.navigate("chat/${UUID.randomUUID()}")
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
        composable(
            route = "chat/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            ChatScreen(
                conversationId = entry.arguments?.getString("id").orEmpty(),
                onBack = { nav.popBackStack() },
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
                selected = route == tab.route,
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
