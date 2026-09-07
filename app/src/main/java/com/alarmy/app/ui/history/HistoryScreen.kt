package com.alarmy.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alarmy.app.ui.AlarmsViewModel
import com.alarmy.app.ui.common.Format
import com.alarmy.core.model.AlarmOccurrence
import com.alarmy.core.model.AlarmOutcome
import com.alarmy.core.state.WakeStatistics
import java.time.Instant
import java.time.ZoneId

/**
 * Wake history.
 *
 * The point of this screen is not the numbers; it is showing the user evidence
 * that the app is working, and evidence when it is not. A late alarm is called
 * out explicitly, because on Android that is the failure mode that matters and
 * the user would otherwise blame themselves for oversleeping.
 */
@Composable
fun HistoryScreen(viewModel: AlarmsViewModel) {
    val stats by viewModel.statistics.collectAsState()
    val occurrences by viewModel.occurrences.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val zone = ZoneId.systemDefault()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { StatsCard(stats) }

        if (occurrences.isEmpty()) {
            item {
                Text(
                    "No alarms have rung yet. Once they do, every ring shows up " +
                        "here with how long the mission took and how late the " +
                        "alarm was.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 32.dp)
                )
            }
        } else {
            item {
                Text(
                    "Recent alarms",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(occurrences, key = { it.id }) { occurrence ->
                OccurrenceRow(occurrence, zone, settings.use24HourClock)
            }
        }
    }
}

@Composable
private fun StatsCard(stats: WakeStatistics) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Stat("${stats.currentStreak}", "day streak")
                Stat("${stats.reliabilityPercent}%", "woke on time")
                Stat("${stats.totalVerifiedWakes}", "verified wakes")
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            DetailRow("Longest streak", "${stats.longestStreak} days")
            DetailRow(
                "Average mission time",
                if (stats.averageMissionSeconds > 0) {
                    Format.duration(stats.averageMissionSeconds.toLong())
                } else {
                    "—"
                }
            )
            DetailRow("Snoozes per wake", String.format("%.1f", stats.snoozesPerWake))
            if (stats.averageLatenessSeconds >= 1) {
                DetailRow(
                    "Average delay",
                    Format.duration(stats.averageLatenessSeconds.toLong())
                )
                Spacer(Modifier.height(8.dp))
                // This is the Android-specific warning. On iOS it would never
                // be shown, because AlarmKit does not run late.
                Text(
                    "Your alarms are firing later than scheduled. That is almost " +
                        "always battery optimisation. Check the Reliability tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (stats.totalMissed > 0) {
                DetailRow("Missed", "${stats.totalMissed}")
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun OccurrenceRow(occurrence: AlarmOccurrence, zone: ZoneId, use24Hour: Boolean) {
    val fired = occurrence.firedAtEpochMillis?.let(Instant::ofEpochMilli)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    Format.dateTime(fired ?: occurrence.scheduledAt, zone, use24Hour),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    outcomeLabel(occurrence.outcome),
                    style = MaterialTheme.typography.labelLarge,
                    color = outcomeColor(occurrence.outcome)
                )
            }
            Text(
                buildString {
                    append(occurrence.missionType.displayName)
                    occurrence.timeToCompleteMs?.let {
                        append(" · ")
                        append(Format.duration(it / 1000))
                    }
                    if (occurrence.attempts > 1) {
                        append(" · ${occurrence.attempts} attempts")
                    }
                    if (occurrence.snoozeCount > 0) {
                        append(" · ${occurrence.snoozeCount} snoozes")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // A 30-second delay is noise; a two-minute delay is the difference
            // between catching a train and not.
            if (occurrence.latenessSeconds >= LATE_THRESHOLD_SECONDS) {
                Text(
                    "Fired ${Format.duration(occurrence.latenessSeconds)} late",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            occurrence.substitutedFrom?.let {
                Text(
                    "${it.displayName} wasn't available, so this used " +
                        occurrence.missionType.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun outcomeColor(outcome: AlarmOutcome?) = when (outcome) {
    AlarmOutcome.COMPLETED -> MaterialTheme.colorScheme.primary
    AlarmOutcome.MISSED, AlarmOutcome.DISMISSED_UNVERIFIED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun outcomeLabel(outcome: AlarmOutcome?) = when (outcome) {
    AlarmOutcome.COMPLETED -> "Woke up"
    AlarmOutcome.SNOOZED -> "Snoozed"
    AlarmOutcome.DISMISSED_UNVERIFIED -> "Dismissed"
    AlarmOutcome.MISSED -> "Missed"
    null -> "Unresolved"
}

private const val LATE_THRESHOLD_SECONDS = 60L
