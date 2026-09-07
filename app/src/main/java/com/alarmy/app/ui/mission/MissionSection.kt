package com.alarmy.app.ui.mission

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alarmy.app.ui.alarms.SectionCard
import com.alarmy.core.mission.MissionLibrary
import com.alarmy.core.model.MissionCategory
import com.alarmy.core.model.MissionConfig
import com.alarmy.core.model.MissionDifficulty
import com.alarmy.core.model.MissionType

/**
 * Mission chooser embedded in the alarm editor.
 *
 * Missions unavailable on this device are shown greyed out with the reason
 * rather than hidden. A user who cannot find "Walking" will assume the app is
 * broken; one who is told their phone has no step counter will not.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MissionSection(
    config: MissionConfig,
    available: List<MissionType>,
    onChange: (MissionConfig) -> Unit
) {
    SectionCard("Mission") {
        val grouped = MissionLibrary.descriptors.groupBy { it.type.category }

        MissionCategory.entries.forEach { category ->
            val descriptors = grouped[category].orEmpty()
            if (descriptors.isEmpty()) return@forEach

            Text(
                category.displayName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                descriptors.forEach { descriptor ->
                    val enabled = descriptor.type in available
                    FilterChip(
                        selected = config.type == descriptor.type,
                        enabled = enabled,
                        onClick = {
                            onChange(
                                config.copy(
                                    type = descriptor.type,
                                    targetCount = MissionConfig.defaultTargetCount(descriptor.type)
                                ).normalized()
                            )
                        },
                        label = { Text(descriptor.type.displayName) }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        val descriptor = MissionLibrary.descriptor(config.type)
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(descriptor.title, fontWeight = FontWeight.SemiBold)
                Text(descriptor.summary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    descriptor.howItWorks,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (config.requiredPermissions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        config.requiredPermissions.forEach { permission ->
                            AssistChip(
                                onClick = {},
                                label = { Text("Needs ${permission.displayName}") }
                            )
                        }
                    }
                }
            }
        }

        if (config.type != MissionType.NONE) {
            Spacer(Modifier.height(12.dp))
            Text("Difficulty", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MissionDifficulty.entries.forEach { difficulty ->
                    FilterChip(
                        selected = config.difficulty == difficulty,
                        onClick = { onChange(config.copy(difficulty = difficulty)) },
                        label = { Text(difficulty.displayName) }
                    )
                }
            }

            val range = MissionConfig.targetRange(config.type)
            if (range.first != range.last) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${countLabel(config.type)}: ${config.targetCount}",
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = config.targetCount.toFloat(),
                    onValueChange = { onChange(config.copy(targetCount = it.toInt()).normalized()) },
                    valueRange = range.first.toFloat()..range.last.toFloat()
                )
            }
        }

        if (config.type == MissionType.COMBINATION) {
            Spacer(Modifier.height(8.dp))
            CombinationEditor(config = config, available = available, onChange = onChange)
        }
    }
}

private fun countLabel(type: MissionType): String = when (type) {
    MissionType.SHAKE -> "Shakes"
    MissionType.STEPS -> "Steps"
    MissionType.SQUAT -> "Squats"
    MissionType.TYPING -> "Phrases"
    MissionType.PHOTO, MissionType.QR -> "Scans"
    MissionType.COMBINATION -> "Missions in the chain"
    else -> "Rounds"
}

/**
 * Builds the chain for a Combination mission.
 *
 * Nested combinations are not offered: the core allows them structurally, but a
 * mission tree is not something anyone should be able to build by accident and
 * then have to solve while half asleep.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CombinationEditor(
    config: MissionConfig,
    available: List<MissionType>,
    onChange: (MissionConfig) -> Unit
) {
    val slots = config.targetCount
    val children = (0 until slots).map { index ->
        config.children.getOrElse(index) { MissionConfig(type = MissionType.MATH) }
    }

    Text(
        "Complete these in order:",
        style = MaterialTheme.typography.labelLarge
    )
    children.forEachIndexed { index, child ->
        Column(Modifier.padding(vertical = 4.dp)) {
            Text("${index + 1}.", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MissionType.entries
                    .filter { it != MissionType.COMBINATION && it != MissionType.NONE }
                    .forEach { type ->
                        FilterChip(
                            selected = child.type == type,
                            enabled = type in available,
                            onClick = {
                                val updated = children.toMutableList()
                                updated[index] = child.copy(
                                    type = type,
                                    targetCount = MissionConfig.defaultTargetCount(type)
                                ).normalized()
                                onChange(config.copy(children = updated))
                            },
                            label = { Text(type.displayName) }
                        )
                    }
            }
        }
    }
}

@Composable
fun MissionSummaryRow(config: MissionConfig, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Column {
            Text(config.type.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${config.difficulty.displayName} · ${config.targetCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
