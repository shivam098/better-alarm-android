package com.alarmy.app.sensor

import android.hardware.Sensor
import android.os.Looper
import com.alarmy.core.mission.MotionOutcome
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class WalkingSensorSourceTest {
    private class Sensors(val supported: Set<Int>) : WalkingSensors {
        val registrations = mutableListOf<Int>()
        val feeds = mutableListOf<(FloatArray, Double) -> Unit>()
        var current = -1
        override fun register(type: Int, receive: (FloatArray, Double) -> Unit): Boolean {
            registrations += type
            if (type !in supported) return false
            current = type
            feeds += receive
            return true
        }
        override fun stop() { current = -1 }
        fun emit(value: Float, time: Double) = feeds.last()(floatArrayOf(value), time)
    }

    @Test fun counterOnlyPhoneCountsMissionDeltaAndCompletesOnce() {
        val sensors = Sensors(setOf(Sensor.TYPE_STEP_COUNTER))
        val results = mutableListOf<MotionOutcome>()
        val source = WalkingSensorSource(RuntimeEnvironment.getApplication(), sensors) { true }
        source.start(5, results::add) {}
        assertEquals(Sensor.TYPE_STEP_COUNTER, sensors.current)
        sensors.emit(5000f, 1.0)
        assertTrue(results.isEmpty())
        sensors.emit(5003f, 3.0)
        sensors.emit(5005f, 4.0)
        sensors.emit(5006f, 5.0)
        assertEquals(listOf(MotionOutcome.Counted(3, 5), MotionOutcome.Completed(5)), results)
        assertEquals(-1, sensors.current)
    }

    @Test fun silentDetectorSwitchesToCounterAndDiscardsQueuedOldEventsWithoutDoubleCounting() {
        val sensors = Sensors(setOf(Sensor.TYPE_STEP_DETECTOR, Sensor.TYPE_STEP_COUNTER))
        val results = mutableListOf<MotionOutcome>()
        val source = WalkingSensorSource(RuntimeEnvironment.getApplication(), sensors) { true }
        source.start(3, results::add) {}
        sensors.emit(1f, 1.0)
        val oldFeed = sensors.feeds.last()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        assertEquals(Sensor.TYPE_STEP_COUNTER, sensors.current)
        oldFeed(floatArrayOf(1f), 2.0)
        sensors.emit(100f, 10.0)
        sensors.emit(102f, 11.0)
        assertEquals(MotionOutcome.Completed(3), results.last())
        assertEquals(2, results.size)
    }

    @Test fun absentDedicatedSensorsUseActualAccelerationPeaksAndExposeSilentRecovery() {
        val sensors = Sensors(setOf(Sensor.TYPE_ACCELEROMETER))
        val results = mutableListOf<MotionOutcome>()
        val messages = mutableListOf<String>()
        val source = WalkingSensorSource(RuntimeEnvironment.getApplication(), sensors) { true }
        source.start(10, results::add, messages::add)
        assertEquals(Sensor.TYPE_ACCELEROMETER, sensors.current)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        assertTrue(messages.last().contains("Retry sensors"))
        for (i in 0..350) {
            val time = i * 0.02
            sensors.feeds.last()(floatArrayOf(0f, 0f, (9.81 + 2 * sin(2 * PI * 2 * time)).toFloat()), time)
        }
        assertEquals(MotionOutcome.Completed(10), results.last())
        assertEquals(1, results.count { it is MotionOutcome.Completed })
    }

    @Test fun deniedPermissionRegistersNothingAndRevocationStopsAllCounting() {
        val sensors = Sensors(setOf(Sensor.TYPE_STEP_DETECTOR))
        var granted = false
        val results = mutableListOf<MotionOutcome>()
        val messages = mutableListOf<String>()
        val source = WalkingSensorSource(RuntimeEnvironment.getApplication(), sensors) { granted }
        source.start(3, results::add, messages::add)
        assertTrue(sensors.registrations.isEmpty())
        assertTrue(messages.last().contains("denied"))
        granted = true
        source.start(3, results::add, messages::add)
        sensors.emit(1f, 1.0)
        granted = false
        sensors.emit(1f, 2.0)
        assertEquals(-1, sensors.current)
        assertEquals(1, results.size)
        assertTrue(messages.last().contains("revoked"))
    }

    @Test fun rejectedOrMissingRegistrationsAreVisibleAndNeverComplete() {
        val sensors = Sensors(emptySet())
        val messages = mutableListOf<String>()
        val source = WalkingSensorSource(RuntimeEnvironment.getApplication(), sensors) { true }
        source.start(10, { fail("Missing sensors must not complete") }, messages::add)
        assertEquals(listOf(Sensor.TYPE_STEP_DETECTOR, Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_ACCELEROMETER),
            sensors.registrations)
        assertTrue(messages.last().contains("No usable walking sensor"))
    }

    @Test fun foregroundPauseStopsEventsAndResumeUsesNewCounterBaselineWithProgressPreserved() {
        val sensors = Sensors(setOf(Sensor.TYPE_STEP_COUNTER))
        val results = mutableListOf<MotionOutcome>()
        val source = WalkingSensorSource(RuntimeEnvironment.getApplication(), sensors) { true }
        source.start(3, results::add) {}
        sensors.emit(100f, 1.0)
        sensors.emit(101f, 2.0)
        source.stop()
        sensors.emit(103f, 3.0)
        source.resume()
        sensors.emit(103f, 4.0)
        sensors.emit(105f, 5.0)
        assertEquals(listOf(MotionOutcome.Counted(1, 3), MotionOutcome.Completed(3)), results)
    }

    @Test fun counterOnlyPhoneOffersWalkingButDoesNotAssumePermission() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(android.hardware.SensorManager::class.java)
        shadowOf(manager).addSensor(org.robolectric.shadows.ShadowSensor.newInstance(Sensor.TYPE_STEP_COUNTER))
        shadowOf(context).denyPermissions(android.Manifest.permission.ACTIVITY_RECOGNITION)
        val capabilities = DeviceCapabilities(context)
        assertTrue(capabilities.configurableMissions().contains(com.alarmy.core.model.MissionType.STEPS))
        assertFalse(capabilities.availableMissions().contains(com.alarmy.core.model.MissionType.STEPS))
        shadowOf(context).grantPermissions(android.Manifest.permission.ACTIVITY_RECOGNITION)
        assertTrue(capabilities.availableMissions().contains(com.alarmy.core.model.MissionType.STEPS))
    }

    @Test fun accelerometerOnlyPhoneOffersWalkingAfterPermissionIsGranted() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(android.hardware.SensorManager::class.java)
        shadowOf(manager).addSensor(org.robolectric.shadows.ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        shadowOf(context).grantPermissions(android.Manifest.permission.ACTIVITY_RECOGNITION)
        assertTrue(DeviceCapabilities(context).availableMissions().contains(com.alarmy.core.model.MissionType.STEPS))
    }

    @Test fun securityFailureIsVisibleAndCannotBeBypassedByAccelerometerFallback() {
        var registrations = 0
        var stopped = false
        val sensors = object : WalkingSensors {
            override fun register(type: Int, receive: (FloatArray, Double) -> Unit): Boolean {
                registrations++
                throw SecurityException("Sensor permission refused")
            }
            override fun stop() { stopped = true }
        }
        val messages = mutableListOf<String>()
        val source = WalkingSensorSource(RuntimeEnvironment.getApplication(), sensors) { true }
        source.start(3, { fail("Denied sensor access must never complete") }, messages::add)
        assertEquals(1, registrations)
        assertTrue(stopped)
        assertTrue(messages.last().contains("Sensor access denied"))
    }

    @Test fun bothDedicatedSensorsSilentAdvanceToForegroundMotionAndShowRecovery() {
        val sensors = Sensors(setOf(Sensor.TYPE_STEP_DETECTOR, Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_ACCELEROMETER))
        val messages = mutableListOf<String>()
        val source = WalkingSensorSource(RuntimeEnvironment.getApplication(), sensors) { true }
        source.start(10, { fail("Silence must never complete") }, messages::add)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        assertEquals(Sensor.TYPE_ACCELEROMETER, sensors.current)
        assertTrue(messages.last().contains("foreground motion"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        assertTrue(messages.last().contains("No steps detected"))
        source.stop()
    }

    @Test fun walkingStaysWalkingWithDeniedPermissionAndLiveStepsDismissOnlyAtTheGoal() {
        val context = RuntimeEnvironment.getApplication()
        com.alarmy.app.AppGraph.install(context)
        shadowOf(context).denyPermissions(android.Manifest.permission.ACTIVITY_RECOGNITION)
        val alarm = com.alarmy.core.model.Alarm(mission = com.alarmy.core.model.MissionConfig(
            type = com.alarmy.core.model.MissionType.STEPS, targetCount = 3))
        com.alarmy.app.AppGraph.repository.upsert(alarm)
        val host = object : com.alarmy.app.alarm.RingingHost {
            override fun startAudio(alarm: com.alarmy.core.model.Alarm, escalate: Boolean) = Unit
            override fun stopAudio() = Unit
            override fun acquireWakeLock() = Unit
            override fun releaseWakeLock() = Unit
            override fun refreshNotification(title: String, body: String, canSnooze: Boolean) = Unit
            override fun showSurface() = Unit
            override fun finish() = Unit
        }
        val controller = com.alarmy.app.alarm.RingingController
        controller.attach(host)
        val sensors = Sensors(setOf(Sensor.TYPE_STEP_COUNTER))
        val source = WalkingSensorSource(context, sensors)
        val onResult: (MotionOutcome) -> Unit = { if (it is MotionOutcome.Completed) controller.missionCompleted() }
        try {
            assertTrue(controller.begin(alarm.id, "walking-test", System.currentTimeMillis(), 0))
            assertEquals(com.alarmy.core.model.MissionType.STEPS, controller.snapshot.value!!.mission.type)
            source.start(3, onResult) {}
            assertTrue(sensors.registrations.isEmpty())
            shadowOf(context).grantPermissions(android.Manifest.permission.ACTIVITY_RECOGNITION)
            source.start(3, onResult) {}
            sensors.emit(200f, 1.0)
            sensors.emit(202f, 2.0)
            assertNotNull(controller.snapshot.value)
            sensors.emit(203f, 3.0)
            assertNull(controller.snapshot.value)
            assertEquals(com.alarmy.core.model.AlarmOutcome.COMPLETED,
                com.alarmy.app.AppGraph.repository.occurrence("walking-test")!!.outcome)
        } finally {
            source.stop()
            controller.detach(host)
        }
    }
}
