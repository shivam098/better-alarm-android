package com.alarmy.app.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.alarmy.app.ui.alarms.AlarmEditorScreen
import com.alarmy.app.ui.alarms.AlarmListScreen
import com.alarmy.app.ui.history.HistoryScreen
import com.alarmy.app.ui.reliability.ReliabilityScreen
import com.alarmy.app.ui.settings.SettingsScreen
import com.alarmy.app.ui.theme.BetterAlarmTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* The Reliability screen reports the result either way. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // POST_NOTIFICATIONS is not a nicety here: the ringing screen is
        // launched by a notification's full-screen intent, so without it the
        // alarm has no way to appear at all.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val openReliability = intent?.getBooleanExtra(EXTRA_OPEN_RELIABILITY, false) ?: false

        setContent {
            BetterAlarmTheme {
                BetterAlarmApp(startOnReliability = openReliability)
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_RELIABILITY = "open_reliability"
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("alarms", "Alarms", Icons.Filled.Alarm),
    Tab("history", "History", Icons.Filled.History),
    Tab("reliability", "Reliability", Icons.Filled.HealthAndSafety),
    Tab("settings", "Settings", Icons.Filled.Settings)
)

@Composable
fun BetterAlarmApp(startOnReliability: Boolean) {
    val navController = rememberNavController()
    val viewModel: AlarmsViewModel = viewModel()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    LaunchedEffect(startOnReliability) {
        if (startOnReliability) navController.navigate("reliability")
    }

    Scaffold(
        bottomBar = {
            // The editor is a full-screen task; showing tabs under it invites
            // navigating away mid-edit and losing the alarm.
            val onEditor = currentRoute?.route?.startsWith("editor") == true
            if (!onEditor) {
                NavigationBar {
                    TABS.forEach { tab ->
                        val selected = currentRoute?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "alarms",
            modifier = Modifier.padding(padding)
        ) {
            composable("alarms") {
                AlarmListScreen(
                    viewModel = viewModel,
                    onAddAlarm = { navController.navigate("editor/new") },
                    onEditAlarm = { id -> navController.navigate("editor/$id") },
                    onOpenReliability = { navController.navigate("reliability") }
                )
            }
            composable("editor/{alarmId}") { entry ->
                val id = entry.arguments?.getString("alarmId")
                AlarmEditorScreen(
                    viewModel = viewModel,
                    alarmId = if (id == "new") null else id,
                    onDone = { navController.popBackStack() }
                )
            }
            composable("history") { HistoryScreen(viewModel) }
            composable("reliability") { ReliabilityScreen() }
            composable("settings") { SettingsScreen(viewModel) }
        }
    }
}
