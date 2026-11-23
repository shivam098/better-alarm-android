package com.alarmy.app.ui.mission

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alarmy.app.sensor.DeviceCapabilities
import com.alarmy.app.sensor.WalkingSensorSource
import com.alarmy.core.mission.MissionFailureReason
import com.alarmy.core.mission.MotionOutcome
import com.alarmy.core.model.MissionConfig
import com.alarmy.core.model.MissionPermission

@Composable
internal fun WalkingMission(config: MissionConfig, onFailure: (MissionFailureReason) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val capabilities = DeviceCapabilities(context)
    var retry by remember { mutableIntStateOf(0) }
    var count by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    val failure by rememberUpdatedState(onFailure)
    val done by rememberUpdatedState(onDone)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { retry++ }
    DisposableEffect(config.targetCount, retry, lifecycle) {
        count = 0
        val source = WalkingSensorSource(context)
        source.start(config.targetCount, { result ->
            when (result) {
                is MotionOutcome.Counted -> count = result.count
                is MotionOutcome.Completed -> { count = result.count; done() }
                is MotionOutcome.Rejected -> {
                    message = "Movement was too fast to count. Walk at your normal pace; accepted steps are kept."
                    failure(result.reason)
                }
                MotionOutcome.Ignored -> Unit
            }
        }, { message = it })
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> source.resume()
                Lifecycle.Event.ON_PAUSE -> source.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) source.stop()
        onDispose { lifecycle.removeObserver(observer); source.stop() }
    }
    Column(Modifier.fillMaxWidth()) {
        Text("Get up and walk")
        Text("$count of ${config.targetCount} steps")
        LinearProgressIndicator(progress = { (count.toFloat() / config.targetCount).coerceIn(0f, 1f) })
        Text(message)
        if (!capabilities.activityRecognitionGranted) {
            Button(onClick = {
                capabilities.androidPermissionFor(MissionPermission.ACTIVITY_RECOGNITION)?.let { permission.launch(it) }
            }) { Text("Allow Physical activity") }
        }
        OutlinedButton(onClick = { retry++ }) { Text("Retry sensors (restarts step count)") }
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")))
        }) { Text("App settings") }
    }
}
