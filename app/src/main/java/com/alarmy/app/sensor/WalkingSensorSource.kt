package com.alarmy.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.alarmy.core.mission.MotionOutcome
import com.alarmy.core.mission.WalkingCounter
import com.alarmy.core.mission.WalkingPeakDetector
import kotlin.math.sqrt

internal interface WalkingSensors {
    fun register(type: Int, receive: (FloatArray, Double) -> Unit): Boolean
    fun stop()
}

private class AndroidWalkingSensors(context: Context) : WalkingSensors {
    private val manager = context.getSystemService(SensorManager::class.java)
    private var listener: SensorEventListener? = null

    override fun register(type: Int, receive: (FloatArray, Double) -> Unit): Boolean {
        val sensor = manager?.getDefaultSensor(type) ?: return false
        val next = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                receive(event.values, event.timestamp / 1_000_000_000.0)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        listener = next
        // Zero batching latency matters for a short, interactive mission.
        return manager.registerListener(next, sensor, SensorManager.SENSOR_DELAY_GAME, 0)
    }

    override fun stop() {
        listener?.let { manager?.unregisterListener(it) }
        listener = null
    }
}

internal class WalkingSensorSource(
    context: Context,
    private val sensors: WalkingSensors = AndroidWalkingSensors(context),
    private val permissionGranted: () -> Boolean = { DeviceCapabilities(context).activityRecognitionGranted }
) {
    private val handler = Handler(Looper.getMainLooper())
    private var progress: WalkingCounter? = null
    private var peaks = WalkingPeakDetector()
    private var active = false
    private var completed = false
    private var generation = 0
    private var sourceIndex = -1
    private var outcome: (MotionOutcome) -> Unit = {}
    private var status: (String) -> Unit = {}
    private val types = listOf(Sensor.TYPE_STEP_DETECTOR, Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_ACCELEROMETER)
    private val timeout = Runnable {
        if (sourceIndex < types.lastIndex) selectNext()
        else status("No steps detected. Keep this screen open, put the phone in your trouser pocket and walk. " +
            "If it stays at zero, tap Retry sensors; check Physical activity permission in App settings.")
    }

    fun start(target: Int, onOutcome: (MotionOutcome) -> Unit, onStatus: (String) -> Unit) {
        stop()
        completed = false
        progress = WalkingCounter(target)
        outcome = onOutcome
        status = onStatus
        if (!permissionGranted()) {
            status("Physical activity permission is denied. Allow it to start Walking; no steps are being counted.")
            return
        }
        resume()
    }

    fun resume() {
        if (active || completed || progress == null) return
        active = true
        sourceIndex = -1
        selectNext()
    }

    private fun selectNext() {
        handler.removeCallbacks(timeout)
        sensors.stop()
        generation++
        if (!active) return
        if (!permissionGranted()) {
            stop()
            status("Physical activity permission was revoked. Allow it and retry Walking.")
            return
        }
        progress?.changeSource()
        peaks = WalkingPeakDetector()
        while (++sourceIndex < types.size) {
            val type = types[sourceIndex]
            val token = generation
            val registered = try {
                sensors.register(type) { values, seconds ->
                    if (active && token == generation) feed(type, values, seconds)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Walking sensor permission denied", e)
                stop()
                status("Sensor access denied. Allow Physical activity in App settings and retry.")
                return
            }
            if (registered) {
                status(when (type) {
                    Sensor.TYPE_STEP_DETECTOR -> "Using step detector. Keep this screen open and walk with the phone."
                    Sensor.TYPE_STEP_COUNTER -> "Using step counter. Waiting for a starting reading; only mission steps count."
                    else -> "Using foreground motion counting because dedicated step sensors are unavailable or silent. " +
                        "Keep this screen open and walk with the phone in your trouser pocket."
                })
                handler.postDelayed(timeout, 8_000)
                return
            }
            Log.w(TAG, "Walking sensor $type unavailable or registration failed")
            sensors.stop()
        }
        active = false
        status("No usable walking sensor. Retry sensors or check Physical activity permission in App settings. " +
            "Walking has not been completed.")
    }

    private fun feed(type: Int, values: FloatArray, seconds: Double) {
        if (!permissionGranted()) {
            stop()
            status("Physical activity permission was revoked. Allow it and retry Walking.")
            return
        }
        val counter = progress ?: return
        val result = when (type) {
            Sensor.TYPE_STEP_DETECTOR -> if (values.firstOrNull() == 1f) counter.step(seconds) else MotionOutcome.Ignored
            Sensor.TYPE_STEP_COUNTER -> values.firstOrNull()?.let { counter.cumulative(it.toDouble(), seconds) }
                ?: MotionOutcome.Ignored
            else -> {
                if (values.size < 3) return
                val magnitude = sqrt(values.take(3).sumOf { it.toDouble() * it })
                if (peaks.sample(magnitude, seconds)) counter.step(seconds) else MotionOutcome.Ignored
            }
        }
        when (result) {
            is MotionOutcome.Completed -> {
                completed = true
                stop()
                outcome(result)
            }
            is MotionOutcome.Counted -> {
                handler.removeCallbacks(timeout)
                handler.postDelayed(timeout, 8_000)
                outcome(result)
            }
            is MotionOutcome.Rejected -> outcome(result)
            MotionOutcome.Ignored -> Unit
        }
    }

    fun stop() {
        active = false
        generation++
        handler.removeCallbacks(timeout)
        sensors.stop()
    }

    private companion object {
        const val TAG = "WalkingSensorSource"
    }
}
