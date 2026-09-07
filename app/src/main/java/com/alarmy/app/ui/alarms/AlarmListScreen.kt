package com.alarmy.app.ui.alarms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alarmy.app.AppGraph
import com.alarmy.app.ui.AlarmRow
import com.alarmy.app.ui.AlarmsViewModel
import com.alarmy.app.ui.common.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmListScreen(
    viewModel: AlarmsViewModel,
    onAddAlarm: () -> Unit,
    onEditAlarm: (String) -> Unit,
    onOpenReliability: () -> Unit
) {
    val rows by viewModel.rows.collectAsState()
    val next by viewModel.nextAlarm.collectAsState()
    val settings by viewModel.settings.collectAsState()

    // Recomputed on every composition of this screen rather than cached: a
    // permission can be revoked while the app is in the background, and a stale
    // "all good" banner is worse than no banner.
    val criticalFailures by produceState(initialValue = 0) {
        value = AppGraph.reliability.criticalFailures
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Alarms") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddAlarm) {
                Icon(Icons.Filled.Add, contentDescription = "Add alarm")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (criticalFailures > 0) {
                item {
                    ReliabilityBanner(criticalFailures, onOpenReliability)
                }
            }

            next?.let { row ->
                item {
                    NextAlarmCard(row, settings.use24HourClock)
                }
            }

            if (rows.isEmpty()) {
                item { EmptyState() }
            }

            items(rows, key = { it.alarm.id }) { row ->
                AlarmCard(
                    row = row,
                    use24Hour = settings.use24HourClock,
                    onToggle = { enabled -> viewModel.setEnabled(row.alarm.id, enabled) },
                    onClick = { onEditAlarm(row.alarm.id) }
                )
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }
}

@Composable
private fun ReliabilityBanner(failures: Int, onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.fillMaxWidth(0.04f))
            Column {
                Text(
                    "Your alarms may not ring",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    "$failures critical ${if (failures == 1) "problem" else "problems"} found. Tap to fix.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun NextAlarmCard(row: AlarmRow, use24Hour: Boolean) {
    val fire = row.nextFire ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Next alarm",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                Format.time(row.alarm.hour, row.alarm.minute, use24Hour),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                Format.countdown(AppGraph.timeSource.now(), fire),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun AlarmCard(
    row: AlarmRow,
    use24Hour: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val alarm = row.alarm
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    Format.time(alarm.hour, alarm.minute, use24Hour),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (alarm.isEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(
                    "${alarm.name} · ${Format.repeatSummary(alarm)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    buildString {
                        append(alarm.mission.type.displayName)
                        if (alarm.mission.type != com.alarmy.core.model.MissionType.NONE) {
                            append(" · ")
                            append(alarm.mission.difficulty.displayName)
                        }
                        if (alarm.isGuarded) append(" · Guarded")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Switch(checked = alarm.isEnabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No alarms yet", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Tap + to create one. Pick a mission you cannot complete while still asleep.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
