package io.github.gl1ch5.linuxarm.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.gl1ch5.linuxarm.App

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        route = intent?.getStringExtra(EXTRA_ROUTE)
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalActivityRoute provides route) { AppTheme { AppRoot() } }
        }
    }

    private var route: String? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { r ->
            route = r
            setContent {
                androidx.compose.runtime.CompositionLocalProvider(LocalActivityRoute provides r) { AppTheme { AppRoot() } }
            }
        }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}

val LocalActivityRoute = androidx.compose.runtime.staticCompositionLocalOf<String?> { null }

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("home", "Дистрибутивы", Icons.Filled.Computer),
    Tab("drivers", "Драйверы", Icons.Filled.Memory),
    Tab("controls", "Управление", Icons.Filled.Gamepad),
    Tab("logs", "Логи", Icons.Filled.Article),
    Tab("updates", "Обновления", Icons.Filled.SystemUpdate),
)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val app = App.instance
    LaunchedEffect(Unit) {
        app.sessions.messages.collect { snackbar.showSnackbar(it, withDismissAction = true) }
    }
    val latest by app.updates.latest.collectAsState()
    val hasUpdate = app.updates.isNewer(latest)
    val startRoute = LocalActivityRoute.current
    LaunchedEffect(startRoute) { startRoute?.let { nav.navigateTab(it) } }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = {
                                if (tab.route == "updates" && hasUpdate) BadgedBox(badge = { Badge() }) { Icon(tab.icon, null) }
                                else Icon(tab.icon, null)
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") { HomeScreen(nav, snackbar) }
            composable("drivers") { DriversScreen(snackbar) }
            composable("logs") { LogsScreen() }
            composable("controls") { ControlsScreen() }
            composable("updates") { UpdatesScreen() }
            composable("apps/{id}") { AppsScreen(nav, it.arguments?.getString("id") ?: "", snackbar) }
            composable("distro/{id}") { DistroSettingsScreen(nav, it.arguments?.getString("id") ?: "") }
            composable("diagnostics/{id}") { DiagnosticsScreen(nav, it.arguments?.getString("id") ?: "") }
        }
    }
}

private fun NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo("home") { saveState = true }
    launchSingleTop = true
    restoreState = true
}
