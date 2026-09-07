package com.alarmy.app.ui.ringing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alarmy.app.alarm.RingingSnapshot
import com.alarmy.app.ui.common.Format
import com.alarmy.app.ui.mission.MissionRunner
import com.alarmy.core.mission.MissionFailureReason
import com.alarmy.core.model.MissionType
import java.time.ZoneId

/**
 * The ringing UI.
 *
 * Everything on this screen exists to be answerable while half asleep: one
 * large clock, one instruction, one control. Anything that is not the mission
 * is deliberately small and low contrast so the eye goes to the task.
 */
@Composable
fun RingingScreen(
    snapshot: RingingSnapshot,
    is24Hour: Boolean,
    onMissionComplete: () -> Unit,
    onMissionFailed: (MissionFailureReason) -> Unit,
    onSnooze: () -> Unit
) {
    // Remounting the runner on each new occurrence, but not on recomposition,
    // is what stops mission progress being silently thrown away.
    var attempts by remember(snapshot.occurrenceKey) { mutableIntStateOf(0) }

    // The fired time, not the scheduled time: if the alarm was late the user
    // should see what the clock actually says.
    val fired = remember(snapshot.firedAt) {
        snapshot.firedAt.atZone(ZoneId.systemDefault())
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                Format.time(fired.hour, fired.minute, is24Hour),
                fontSize = 64.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (snapshot.alarm.label.isNotBlank()) {
                Text(
                    snapshot.alarm.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )
            }

            // Why the mission changed, if it did. Silently swapping a squat
            // mission for maths because the phone has no accelerometer would
            // look like a bug.
            snapshot.substitutedFrom?.let { original ->
                Box(Modifier.padding(top = 12.dp)) {
                    Text(
                        "${original.displayName} isn't available on this phone, " +
                            "so this alarm is using ${snapshot.mission.type.displayName} instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Box(Modifier.height(32.dp))

            MissionRunner(
                config = snapshot.mission,
                occurrenceKey = snapshot.occurrenceKey,
                onFailure = { reason ->
                    attempts++
                    onMissionFailed(reason)
                },
                onComplete = onMissionComplete
            )

            Box(Modifier.height(24.dp))

            if (snapshot.canSnooze) {
                TextButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (snapshot.snoozesRemaining > 0) {
                            "Snooze (${snapshot.snoozesRemaining} left)"
                        } else {
                            "Snooze"
                        }
                    )
                }
            }

            // The disclosure text is the app being honest about what it is
            // doing to the phone, in the moment it is doing it.
            snapshot.disclosure?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            if (attempts >= WRONG_ANSWER_HINT_THRESHOLD &&
                snapshot.mission.type != MissionType.NONE
            ) {
                Text(
                    "Still stuck? Missions get easier if you lower the difficulty " +
                        "in the alarm's settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

private const val WRONG_ANSWER_HINT_THRESHOLD = 4
