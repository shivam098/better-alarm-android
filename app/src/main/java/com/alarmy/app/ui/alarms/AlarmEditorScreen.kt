package com.alarmy.app.ui.alarms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alarmy.app.ui.AlarmsViewModel
import com.alarmy.app.ui.common.Format
import com.alarmy.app.ui.mission.MissionSection
import com.alarmy.core.model.Alarm
import com.alarmy.core.model.MissionGuardSettings
import com.alarmy.core.model.SnoozeSettings
import com.alarmy.core.model.SoundLibrary
import com.alarmy.core.model.Weekday

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditorScreen(
    viewModel: AlarmsViewModel,
    alarmId: String?,
    onDone: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val existing = remember(alarmId) { viewModel.alarm(alarmId) }
    var draft by remember { mutableStateOf(existing ?: Alarm()) }
    var confirmDelete by remember { mutableStateOf(false) }

    val timeState = rememberTimePickerState(
        initialHour = draft.hour,
        initialMinute = draft.minute,
        is24Hour = settings.use24HourClock
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New alarm" else "Edit alarm") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (existing != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete alarm")
                        }
                    }
                    TextButton(onClick = {
                        viewModel.save(
                            draft.copy(hour = timeState.hour, minute = timeState.minute)
                        )
                        onDone()
                    }) { Text("Save") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                TimePicker(state = timeState)
            }

            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            SectionCard("Repeat") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Format.orderedWeekdays.forEach { day ->
                        val selected = day in draft.repeatDays
                        FilterChip(
                            selected = selected,
                            onClick = {
                                draft = draft.copy(
                                    repeatDays = if (selected) {
                                        draft.repeatDays - day
                                    } else {
                                        draft.repeatDays + day
                                    }
                                )
                            },
                            label = { Text(day.shortName.take(2)) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { draft = draft.copy(repeatDays = Weekday.WEEKDAYS) }) {
                        Text("Weekdays")
                    }
                    TextButton(onClick = { draft = draft.copy(repeatDays = Weekday.WEEKEND) }) {
                        Text("Weekend")
                    }
                    TextButton(onClick = { draft = draft.copy(repeatDays = emptySet()) }) {
                        Text("Once")
                    }
                }
            }

            MissionSection(
                config = draft.mission,
                available = viewModel.availableMissions(),
                onChange = { draft = draft.copy(mission = it) }
            )

            SectionCard("Sound") {
                SoundDropdown(
                    selectedId = draft.soundId,
                    onSelect = { draft = draft.copy(soundId = it) }
                )
                Spacer(Modifier.height(8.dp))
                Text("Volume", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = draft.volume.toFloat(),
                    onValueChange = { draft = draft.copy(volume = it.toDouble()) },
                    valueRange = 0.1f..1f
                )
                ToggleRow(
                    label = "Gentle ramp",
                    description = "Start quietly and build up over 30 seconds.",
                    checked = draft.gentleRamp,
                    onChange = { draft = draft.copy(gentleRamp = it) }
                )
                ToggleRow(
                    label = "Vibrate",
                    checked = draft.vibrationEnabled,
                    onChange = { draft = draft.copy(vibrationEnabled = it) }
                )
            }

            SectionCard("Snooze") {
                ToggleRow(
                    label = "Allow snooze",
                    checked = draft.snooze.isEnabled,
                    onChange = { draft = draft.copy(snooze = draft.snooze.copy(isEnabled = it)) }
                )
                if (draft.snooze.isEnabled) {
                    Text("Duration: ${draft.snooze.duration} min", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SnoozeSettings.ALLOWED_DURATIONS.forEach { minutes ->
                            FilterChip(
                                selected = draft.snooze.duration == minutes,
                                onClick = {
                                    draft = draft.copy(snooze = draft.snooze.copy(duration = minutes))
                                },
                                label = { Text("$minutes") }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Limit: ${draft.snooze.limit?.toString() ?: "unlimited"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(1, 2, 3, 5, null).forEach { limit ->
                            FilterChip(
                                selected = draft.snooze.limit == limit,
                                onClick = {
                                    draft = draft.copy(snooze = draft.snooze.copy(limit = limit))
                                },
                                label = { Text(limit?.toString() ?: "∞") }
                            )
                        }
                    }
                    ToggleRow(
                        label = "Shrinking snoozes",
                        description = "Each snooze is shorter than the last.",
                        checked = draft.snooze.escalating,
                        onChange = {
                            draft = draft.copy(snooze = draft.snooze.copy(escalating = it))
                        }
                    )
                }
            }

            GuardSection(
                settings = draft.missionGuard,
                onChange = { draft = draft.copy(missionGuard = it) }
            )

            Spacer(Modifier.height(48.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this alarm?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    existing?.let { viewModel.delete(it.id) }
                    confirmDelete = false
                    onDone()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Mission Guard settings.
 *
 * The explanatory text is deliberately specific about what Android can and
 * cannot do. Promising protection the platform cannot deliver would be worse
 * than offering none, because the user would stop setting a backup alarm.
 */
@Composable
private fun GuardSection(
    settings: MissionGuardSettings,
    onChange: (MissionGuardSettings) -> Unit
) {
    SectionCard("Mission Guard") {
        Text(
            "If the alarm stops without the mission being completed — you swipe the " +
                "notification away, clear all notifications, or switch apps — it comes " +
                "back on a schedule until the mission is done.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "It cannot protect against force-stopping the app from Android settings, " +
                "which cancels every pending alarm. Nothing on Android can.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(8.dp))
        ToggleRow(
            label = "Enable Mission Guard",
            checked = settings.isEnabled,
            onChange = { onChange(settings.copy(isEnabled = it)) }
        )
        if (settings.isEnabled) {
            Text("Keep trying for", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MissionGuardSettings.ALLOWED_WINDOWS.forEach { minutes ->
                    FilterChip(
                        selected = settings.windowMinutes == minutes,
                        onClick = { onChange(settings.copy(windowMinutes = minutes)) },
                        label = { Text(if (minutes == 0) "Off" else "${minutes}m") }
                    )
                }
            }
            ToggleRow(
                label = "Get harder each time",
                description = "Later retries raise the mission difficulty.",
                checked = settings.escalates,
                onChange = { onChange(settings.copy(escalates = it)) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoundDropdown(selectedId: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = SoundLibrary.soundFor(selectedId)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selected.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Alarm sound") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SoundLibrary.all.forEach { sound ->
                DropdownMenuItem(
                    text = { Text(sound.displayName) },
                    onClick = {
                        onSelect(sound.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            content()
        }
    }
}

@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    description: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
