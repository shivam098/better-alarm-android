package com.alarmy.app.ui.reliability

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import com.alarmy.app.AppGraph
import com.alarmy.app.reliability.CheckSeverity
import com.alarmy.app.reliability.OemGuidance
import com.alarmy.app.reliability.ReliabilityCheck

/**
 * The screen that has no iOS equivalent.
 *
 * On iOS, AlarmKit guarantees delivery and there is nothing to check. On
 * Android an alarm app is only as reliable as the settings the user has not
 * been told about, so this screen exists to name them.
 *
 * Every item says what will *happen* rather than what is misconfigured. "Your
 * alarm may not ring" is actionable; "battery optimisation is enabled" is not.
 */
@Composable
fun ReliabilityScreen() {
    val context = LocalContext.current

    // Re-run on resume: the user leaves this screen to change a system
    // setting, and returning to a stale result would be worse than useless.
    var refreshToken by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshToken++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val checks = remember(refreshToken) { AppGraph.reliability.runAll() }
    val guidance = remember { OemGuidance.forCurrentDevice() }
    val failures = checks.count { !it.passed }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Summary(failures) }
        items(checks, key = { it.id }) { check ->
            CheckCard(check) { intent -> runCatching { context.startActivity(intent) } }
        }
        guidance?.let { oem ->
            item { OemCard(oem) }
        }
        item { Footnote() }
    }
}

@Composable
private fun Summary(failures: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (failures == 0) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (failures == 0) "Your alarms should ring" else "$failures things to fix",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (failures == 0) {
                    "Everything this app can check is set up correctly."
                } else {
                    "Android will not wake your phone reliably until these are sorted."
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun CheckCard(check: ReliabilityCheck, onFix: (Intent) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (check.passed) {
                        Icons.Filled.CheckCircle
                    } else when (check.severity) {
                        CheckSeverity.ADVISORY -> Icons.Filled.Info
                        else -> Icons.Filled.Warning
                    },
                    contentDescription = null,
                    tint = statusColor(check),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    check.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(check.explanation, style = MaterialTheme.typography.bodyMedium)
            if (!check.passed) {
                Spacer(Modifier.height(6.dp))
                Text(
                    check.consequence,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor(check),
                    fontWeight = FontWeight.Medium
                )
                val intent = check.fixIntent
                val label = check.fixLabel
                if (intent != null && label != null) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { onFix(intent) }) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun statusColor(check: ReliabilityCheck): Color = when {
    check.passed -> MaterialTheme.colorScheme.primary
    check.severity == CheckSeverity.CRITICAL -> MaterialTheme.colorScheme.error
    check.severity == CheckSeverity.IMPORTANT -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Manufacturer-specific instructions.
 *
 * These are written out as text rather than checked programmatically because
 * **no API exists to read them**. An app cannot tell whether MIUI has put it to
 * sleep; it can only tell the user where to look.
 */
@Composable
private fun OemCard(guidance: OemGuidance) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "${guidance.manufacturer} devices need extra steps",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
            Text(guidance.explanation, style = MaterialTheme.typography.bodyMedium)
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                guidance.steps.forEachIndexed { index, step ->
                    Text(
                        "${index + 1}. $step",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 3.dp)
                    )
                }
                guidance.settingsIntent(context)?.let { intent ->
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { runCatching { context.startActivity(intent) } }) {
                        Text("Open ${guidance.manufacturer} settings")
                    }
                }
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide steps" else "Show me how")
            }
        }
    }
}

@Composable
private fun Footnote() {
    Text(
        "Android alarms are best-effort. This app uses the highest-priority " +
            "alarm the system offers, re-arms every alarm after a restart, and " +
            "keeps a foreground service running while an alarm is ringing. What " +
            "it cannot do is override a manufacturer's battery manager — that " +
            "part is up to the settings above.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}
