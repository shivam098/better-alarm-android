package com.alarmy.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.alarmy.core.mission.MotionOutcome
import com.alarmy.core.mission.ShakeCounter
import com.alarmy.core.mission.SquatRepCounter
import com.alarmy.core.mission.StepValidator
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Turns raw Android sensor events into the timestamped events the core
 * validators expect.
 *
 * The split matters: gesture *detection* (is this a shake?) is inherently
 * platform work and lives here, while gesture *validation* (are these shakes
 * coming from a human or from a phone taped to a fan?) is pure arithmetic and
 * lives in `com.alarmy.core.mission`, where it is unit-tested. This class
 * deliberately contains no anti-cheat logic at all.
 *
 * Timestamps come from `SensorEvent.timestamp`, which is a monotonic
 * nanosecond clock. Using wall-clock time would let a user defeat the
 * periodicity checks by changing the system time mid-mission.
 */
class MotionSensorSource(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private var shakeCounter: ShakeCounter? = null
    private var stepValidator: StepValidator? = null
    private var squatCounter: SquatRepCounter? = null

    private var onOutcome: ((MotionOutcome) -> Unit)? = null

    private var lastShakeSeconds = 0.0
    private var registered = false

    // ------------------------------------------------------------------- shake

    fun startShake(target: Int, onOutcome: (MotionOutcome) -> Unit) {
        stopAll()
        shakeCounter = ShakeCounter(target = target)
        this.onOutcome = onOutcome
        listen(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME)
    }

    fun startSteps(target: Int, onOutcome: (MotionOutcome) -> Unit) {
        stopAll()
        stepValidator = StepValidator(target = target)
        this.onOutcome = onOutcome
        // The hardware step detector emits one event per step and costs almost
        // no battery, unlike deriving steps from raw acceleration.
        listen(Sensor.TYPE_STEP_DETECTOR, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun startSquats(target: Int, onOutcome: (MotionOutcome) -> Unit) {
        stopAll()
        squatCounter = SquatRepCounter(target = target)
        this.onOutcome = onOutcome
        listen(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stopAll() {
        if (registered) {
            sensorManager?.unregisterListener(this)
            registered = false
        }
        shakeCounter = null
        stepValidator = null
        squatCounter = null
        onOutcome = null
        lastShakeSeconds = 0.0
    }

    private fun listen(type: Int, delay: Int) {
        val sensor = sensorManager?.getDefaultSensor(type) ?: return
        registered = sensorManager.registerListener(this, sensor, delay)
    }

    // ------------------------------------------------------------- sensor feed

    override fun onSensorChanged(event: SensorEvent) {
        val seconds = event.timestamp / 1_000_000_000.0
        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR ->
                stepValidator?.register(seconds)?.let { onOutcome?.invoke(it) }

            Sensor.TYPE_ACCELEROMETER -> {
                shakeCounter?.let { handleShake(event, seconds, it) }
                squatCounter?.let { handleSquat(event, seconds, it) }
            }
        }
    }

    /**
     * A shake is a peak in acceleration once gravity is removed.
     *
     * [SHAKE_REFRACTORY_SECONDS] stops one vigorous movement registering as
     * several. It is intentionally shorter than any plausible human shake
     * interval, so the periodicity analysis in `ShakeCounter` still sees the
     * true rhythm rather than one smoothed by this filter.
     */
    private fun handleShake(event: SensorEvent, seconds: Double, counter: ShakeCounter) {
        val magnitude = magnitudeOf(event)
        val netAcceleration = abs(magnitude - SensorManager.GRAVITY_EARTH)
        if (netAcceleration < SHAKE_THRESHOLD) return
        if (seconds - lastShakeSeconds < SHAKE_REFRACTORY_SECONDS) return
        lastShakeSeconds = seconds
        onOutcome?.invoke(counter.register(seconds))
    }

    /**
     * Approximates a squat from device tilt.
     *
     * With the phone held against the chest or in a trouser pocket, the angle
     * between the device's long axis and gravity tracks the torso: near 180
     * degrees standing, dropping sharply at the bottom of a squat. This is an
     * approximation of a body angle, not a measurement of one, which is why
     * `SquatRepCounter` also enforces a minimum rep duration -- tilt alone is
     * easy to fake by waving the phone, but tilt with a plausible tempo is not.
     */
    private fun handleSquat(event: SensorEvent, seconds: Double, counter: SquatRepCounter) {
        val magnitude = magnitudeOf(event)
        if (magnitude < 1e-3) return
        val cosine = (event.values[1] / magnitude).coerceIn(-1f, 1f)
        val degrees = Math.toDegrees(acos(cosine.toDouble()))
        onOutcome?.invoke(counter.register(degrees, seconds))
    }

    private fun magnitudeOf(event: SensorEvent): Float {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        return sqrt(x * x + y * y + z * z)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** Metres per second squared above gravity that counts as a shake. */
        const val SHAKE_THRESHOLD = 12.0f
        const val SHAKE_REFRACTORY_SECONDS = 0.15
    }
}
