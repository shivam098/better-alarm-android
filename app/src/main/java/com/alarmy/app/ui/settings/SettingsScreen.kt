package com.alarmy.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alarmy.app.ui.AlarmsViewModel
import com.alarmy.core.schedule.MissedWindowRule
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(viewModel: AlarmsViewModel) {
    val settings by viewModel.settings.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SettingsCard("Clock") {
                ToggleRow(
                    title = "24-hour time",
                    subtitle = "Show 07:30 instead of 7:30 AM",
                    checked = settings.use24HourClock,
                    onCheckedChange = { on ->
                        viewModel.updateSettings { it.copy(use24HourClock = on) }
                    }
                )
                ToggleRow(
                    title = "Show next alarm",
                    subtitle = "A quiet, ongoing notification with your next alarm time",
                    checked = settings.showNextAlarmNotification,
                    onCheckedChange = { on ->
                        viewModel.updateSettings { it.copy(showNextAlarmNotification = on) }
                        viewModel.refreshSchedule()
                    }
                )
            }
        }

        item {
            SettingsCard("If an alarm is missed") {
                Text(
                    "Your phone can be off, out of battery, or held back by the " +
                        "system when an alarm is due. This decides what happens " +
                        "when it comes back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                MissedWindowRule.entries.forEach { rule ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.updateSettings { it.copy(missedWindowRule = rule) }
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        RadioButton(
                            selected = settings.missedWindowRule == rule,
                            onClick = {
                                viewModel.updateSettings { it.copy(missedWindowRule = rule) }
                            }
                        )
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(rule.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                rule.explanation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (settings.missedWindowRule == MissedWindowRule.GRACE_PERIOD) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Grace period: ${settings.missedWindowGraceMinutes} minutes",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Slider(
                        value = settings.missedWindowGraceMinutes.toFloat(),
                        onValueChange = { value ->
                            viewModel.updateSettings {
                                it.copy(missedWindowGraceMinutes = value.roundToInt())
                            }
                        },
                        valueRange = 5f..60f,
                        steps = 10
                    )
                    Text(
                        "An alarm that was due more than this long ago is recorded " +
                            "as missed instead of ringing. Waking you at 11am for a " +
                            "7am alarm helps nobody.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            SettingsCard("History") {
                TextButton(onClick = { confirmClear = true }) {
                    Text("Clear wake history", color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "Deletes every recorded alarm, along with your streak and " +
                        "reliability figures. Your alarms themselves are kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SettingsCard("About") {
                Text(
                    "Better Alarm keeps everything on this phone. There is no " +
                        "account, no analytics and no network access — the app " +
                        "does not request the INTERNET permission at all, so it " +
                        "could not send your data anywhere even if it wanted to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear wake history?") },
            text = {
                Text(
                    "Your streak, reliability percentage and every recorded alarm " +
                        "will be deleted. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearHistory()
                    confirmClear = false
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
