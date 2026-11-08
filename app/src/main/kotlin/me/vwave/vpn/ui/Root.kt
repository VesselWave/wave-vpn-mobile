package me.vwave.vpn.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import me.vwave.vpn.app

/** The desktop's sidebar tabs, same names and icons. */
private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Overview("overview", "Overview", Lucide.Power),
    Servers("servers", "Servers", Lucide.Globe),
    Logs("logs", "Logs", Lucide.Logs),
    Settings("settings", "Settings", Lucide.Sliders),
}

@Composable
fun WaveRoot(vm: AppViewModel = viewModel()) {
    val account by vm.account.collectAsStateWithLifecycle()
    if (account == null) {
        LoginScreen(vm)
        return
    }

    val context = LocalContext.current
    // Android shows its VPN consent dialog once; connect when the user accepts. Anything else is
    // reported on Overview: Android also closes this dialog by itself while another app is Always-on.
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) vm.connect() else context.app.tunnels.permissionDenied()
    }
    val connect = {
        val intent = if (vm.needsConsentFirst) context.app.tunnels.permissionIntent() else null
        if (intent != null) consent.launch(intent) else vm.connect()
    }
    val connectRequested by context.app.connectRequested.collectAsStateWithLifecycle()

    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val snackbar = remember { SnackbarHostState() }
    val notice by vm.notice.collectAsStateWithLifecycle()
    LaunchedEffect(notice) {
        notice?.let {
            snackbar.showSnackbar(it)
            vm.noticeShown()
        }
    }
    val t = tokens
    LaunchedEffect(connectRequested) {
        if (connectRequested) {
            context.app.connectRequested.value = false
            if (nav.currentDestination != null) nav.open(Tab.Overview)
            if (!context.app.tunnels.isActive) connect()
        }
    }

    Scaffold(
        containerColor = t.bg,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                Rule()
                NavigationBar(containerColor = t.sidebar, tonalElevation = 0.dp) {
                    val route = entry?.destination?.route
                    for (tab in Tab.entries) {
                        val selected = route == tab.route || (tab == Tab.Settings && route == "apps")
                        NavigationBarItem(
                            selected = selected,
                            onClick = { nav.open(tab) },
                            icon = { LucideIcon(tab.icon, if (selected) t.text else t.muted, 22.dp) },
                            // Fixed-width slots under icons: cap growth and never break a word.
                            label = { CapFontScale(1.5f) { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                            colors = NavigationBarItemDefaults.colors(
                                selectedTextColor = t.text,
                                unselectedTextColor = t.muted,
                                indicatorColor = t.pressed,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Screens pad for the status bar themselves; consume what the Scaffold already applied.
        // Switch screens instantly, like the desktop sidebar; the default 700 ms crossfade overlaps both.
        NavHost(
            nav,
            startDestination = Tab.Overview.route,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(Tab.Overview.route) {
                OverviewScreen(
                    vm,
                    connect,
                    onChooseServer = { nav.open(Tab.Servers) },
                    onLockSettings = { nav.open(Tab.Settings) },
                    onSplitTunneling = { nav.navigate("apps") },
                )
            }
            composable(Tab.Servers.route) { ServersScreen(vm, connect, onConnected = { nav.open(Tab.Overview) }) }
            composable(Tab.Logs.route) { LogsScreen(vm) }
            composable(Tab.Settings.route) { SettingsScreen(vm, onSplitTunneling = { nav.navigate("apps") }) }
            composable("apps") { AppsScreen(vm, onBack = { nav.popBackStack() }) }
        }
    }
}

private fun NavHostController.open(tab: Tab) = navigate(tab.route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
