package com.alarmy.app.alarm

import com.alarmy.app.data.AlarmRepository
import com.alarmy.core.model.Alarm
import com.alarmy.core.model.AlarmOccurrence
import com.alarmy.core.model.AlarmOutcome
import com.alarmy.core.model.MissionDifficulty
import com.alarmy.core.model.MissionType
import com.alarmy.core.support.FixedTimeSource
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class AlarmPersistenceTest {
    @get:Rule val folder = TemporaryFolder()
    private val clock = FixedTimeSource(Instant.parse("2026-11-18T06:00:00Z"))

    @Test
    fun snoozeSurvivesProcessRestartAndFullRescheduling() {
        val directory = folder.newFolder()
        val repository = AlarmRepository(directory)
        val alarm = Alarm(hour = 7)
        repository.upsert(alarm)
        AlarmScheduler(RuntimeEnvironment.getApplication(), repository, clock)
            .scheduleSnooze(alarm, "root", 2, 5)
        val restored = AlarmRepository(directory)
        AlarmScheduler(RuntimeEnvironment.getApplication(), restored, clock).rescheduleAll()
        assertTrue(restored.pending().contains("root#snooze2"))
        assertEquals(clock.now().plusSeconds(300).toEpochMilli(),
            restored.deliveries().single { it.key == "root#snooze2" }.fireAtMillis)
    }

    @Test
    fun wakeCheckAndRealarmSurviveRestartAndCanBeConfirmedTogether() {
        val directory = folder.newFolder()
        val repository = AlarmRepository(directory)
        val alarm = Alarm(wakeCheckEnabled = true, wakeCheckMinutes = 5)
        repository.upsert(alarm)
        AlarmScheduler(RuntimeEnvironment.getApplication(), repository, clock).scheduleWakeCheck(alarm, "root")
        val restored = AlarmRepository(directory)
        val scheduler = AlarmScheduler(RuntimeEnvironment.getApplication(), restored, clock)
        scheduler.rescheduleAll()
        val prompt = restored.deliveries().single { it.key == "root#check" }
        val realarm = restored.deliveries().single { it.key == "root#wake" }
        assertEquals(60_000L, realarm.fireAtMillis - prompt.fireAtMillis)
        scheduler.cancelChain("root#check")
        assertFalse(restored.pending().any { it.startsWith("root#") })
        assertFalse(AlarmRepository(directory).deliveries().any { it.key.startsWith("root#") })
    }

    @Test
    fun occurrenceIsUpdatedByStableKeyInsteadOfDuplicatingAcrossRestarts() {
        val directory = folder.newFolder()
        val repository = AlarmRepository(directory)
        val occurrence = AlarmOccurrence(alarmId = "alarm", occurrenceKey = "root",
            scheduledAtEpochMillis = clock.now().toEpochMilli(), missionType = MissionType.MATH,
            difficulty = MissionDifficulty.MEDIUM, snoozeCount = 2)
        repository.recordOccurrence(occurrence)
        val restored = AlarmRepository(directory)
        restored.recordOccurrence(occurrence.copy(id = "different-instance", outcome = AlarmOutcome.COMPLETED))
        assertEquals(1, restored.occurrences.value.size)
        assertEquals(2, restored.occurrence("root")!!.snoozeCount)
        assertEquals(AlarmOutcome.COMPLETED, restored.occurrence("root")!!.outcome)
    }

    @Test
    fun disablingAlarmCancelsItsPersistedSnooze() {
        val repository = AlarmRepository(folder.newFolder())
        val alarm = Alarm()
        repository.upsert(alarm)
        val scheduler = AlarmScheduler(RuntimeEnvironment.getApplication(), repository, clock)
        scheduler.scheduleSnooze(alarm, "root", 1, 5)
        repository.setEnabled(alarm.id, false)
        scheduler.rescheduleAll()
        assertFalse(repository.pending().contains("root#snooze1"))
    }

    @Test
    fun historyDeletionDoesNotResetSnoozeLimitsOrRearmConsumedOneTimeAlarm() {
        val directory = folder.newFolder()
        val repository = AlarmRepository(directory)
        val alarm = Alarm()
        repository.upsert(alarm)
        val fired = System.currentTimeMillis() + 60_000
        val occurrence = AlarmOccurrence(alarmId = alarm.id, occurrenceKey = "root",
            scheduledAtEpochMillis = fired, missionType = MissionType.MATH,
            difficulty = MissionDifficulty.MEDIUM, snoozeCount = 2,
            outcome = AlarmOutcome.SNOOZED, snoozedUntilEpochMillis = fired + 300_000)
        repository.recordOccurrence(occurrence)
        repository.clearHistory()
        val restored = AlarmRepository(directory)
        assertEquals(2, restored.occurrence("root")!!.snoozeCount)
        assertTrue(restored.isConsumed(restored.alarm(alarm.id)!!))
        restored.recordOccurrence(occurrence.copy(outcome = AlarmOutcome.COMPLETED, snoozedUntilEpochMillis = null))
        restored.clearHistory()
        assertTrue(restored.occurrences.value.isEmpty())
        assertTrue(AlarmRepository(directory).let { it.isConsumed(it.alarm(alarm.id)!!) })
    }

    @Test
    fun committedWakeDeadlineRecoversEvenIfRegistrationWasInterrupted() {
        val repository = AlarmRepository(folder.newFolder())
        val alarm = Alarm(wakeCheckEnabled = true)
        repository.upsert(alarm)
        repository.recordOccurrence(AlarmOccurrence(
            alarmId = alarm.id, occurrenceKey = "root",
            scheduledAtEpochMillis = clock.now().toEpochMilli(),
            missionType = MissionType.MATH, difficulty = MissionDifficulty.MEDIUM,
            outcome = AlarmOutcome.COMPLETED,
            wakeCheckAtEpochMillis = clock.now().plusSeconds(300).toEpochMilli()
        ))
        AlarmScheduler(RuntimeEnvironment.getApplication(), repository, clock).rescheduleAll()
        assertTrue(repository.pending().containsAll(listOf("root#check", "root#wake")))
    }

    @Test
    fun overduePrimaryDeliveryUsesConfiguredRecoveryPolicy() {
        val directory = folder.newFolder()
        val repository = AlarmRepository(directory)
        val alarm = Alarm()
        repository.upsert(alarm)
        val due = clock.now().minusSeconds(1_200).toEpochMilli()
        repository.rememberDelivery(com.alarmy.core.schedule.PendingDelivery(alarm.id, "late-root", due))
        val settings = com.alarmy.app.data.AppSettings(
            missedWindowRule = com.alarmy.core.schedule.MissedWindowRule.ALWAYS_TRIGGER)
        AlarmScheduler(RuntimeEnvironment.getApplication(), repository, clock) { settings }.rescheduleAll()
        assertTrue(repository.pending().contains("late-root"))
        AlarmScheduler(RuntimeEnvironment.getApplication(), repository, clock) {
            settings.copy(missedWindowRule = com.alarmy.core.schedule.MissedWindowRule.ALWAYS_SKIP)
        }.rescheduleAll()
        assertFalse(repository.pending().contains("late-root"))
        assertEquals(AlarmOutcome.MISSED, repository.occurrence("late-root")!!.outcome)
    }

    @Test
    fun upcomingNotificationSettingIsAppliedToTheActualNotification() {
        val repository = AlarmRepository(folder.newFolder())
        repository.upsert(Alarm(hour = 7))
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        AlarmScheduler(context, repository, clock).rescheduleAll()
        assertNotNull(org.robolectric.Shadows.shadowOf(manager).getNotification(AlarmNotifications.NOTIFICATION_UPCOMING))
        AlarmScheduler(context, repository, clock) {
            com.alarmy.app.data.AppSettings(showNextAlarmNotification = false)
        }.rescheduleAll()
        assertNull(org.robolectric.Shadows.shadowOf(manager).getNotification(AlarmNotifications.NOTIFICATION_UPCOMING))
    }

    @Test
    fun legacyAlarmsMigrateOnUnlockEvenWhenProcessStartedDuringDirectBoot() {
        val context = RuntimeEnvironment.getApplication()
        val users = org.robolectric.Shadows.shadowOf(context.getSystemService(android.os.UserManager::class.java))
        val alarm = Alarm(name = "Existing saved alarm")
        AlarmRepository(context.filesDir).upsert(alarm)
        users.setUserUnlocked(false)
        com.alarmy.app.AppGraph.install(context)
        assertNull(com.alarmy.app.AppGraph.repository.alarm(alarm.id))
        users.setUserUnlocked(true)
        com.alarmy.app.AppGraph.ensureInstalled(context)
        assertEquals(alarm.name, com.alarmy.app.AppGraph.repository.alarm(alarm.id)?.name)
        assertFalse(java.io.File(context.filesDir, "alarms.json").exists())
    }

    @Test
    fun expiredWakeConfirmationCannotCancelRealarmButTimelyConfirmationCan() {
        val context = RuntimeEnvironment.getApplication()
        com.alarmy.app.AppGraph.install(context)
        val repository = com.alarmy.app.AppGraph.repository
        val alarm = Alarm(wakeCheckEnabled = true)
        repository.upsert(alarm)
        val occurrence = AlarmOccurrence(alarmId = alarm.id, occurrenceKey = "confirmation-root",
            scheduledAtEpochMillis = System.currentTimeMillis(),
            missionType = MissionType.MATH, difficulty = MissionDifficulty.MEDIUM,
            outcome = AlarmOutcome.COMPLETED,
            wakeCheckAtEpochMillis = System.currentTimeMillis() - 120_000)
        repository.recordOccurrence(occurrence)
        com.alarmy.app.AppGraph.scheduler.scheduleWakeCheck(alarm, occurrence.occurrenceKey)
        val intent = android.content.Intent(AlarmActionReceiver.ACTION_AWAKE)
            .putExtra(AlarmReceiver.EXTRA_KEY, "confirmation-root#check")
        AlarmActionReceiver().onReceive(context, intent)
        assertTrue(repository.pending().contains("confirmation-root#wake"))
        repository.recordOccurrence(occurrence.copy(wakeCheckAtEpochMillis = System.currentTimeMillis() + 300_000))
        AlarmActionReceiver().onReceive(context, intent)
        assertFalse(repository.pending().contains("confirmation-root#wake"))
        assertNull(repository.occurrence("confirmation-root")!!.wakeCheckAtEpochMillis)
    }

    @Test
    fun controllerKeepsCameraTransitionsAudibleAndDoesNotRelaunchMissionOnEveryEvent() {
        val context = RuntimeEnvironment.getApplication()
        com.alarmy.app.AppGraph.install(context)
        val alarm = Alarm(snooze = com.alarmy.core.model.SnoozeSettings(costsMission = true))
        com.alarmy.app.AppGraph.repository.upsert(alarm)
        var surfaces = 0
        var stops = 0
        val host = object : RingingHost {
            override fun startAudio(alarm: Alarm, escalate: Boolean) = Unit
            override fun stopAudio() { stops++ }
            override fun acquireWakeLock() = Unit
            override fun releaseWakeLock() = Unit
            override fun refreshNotification(title: String, body: String, canSnooze: Boolean) = Unit
            override fun showSurface() { surfaces++ }
            override fun finish() = Unit
        }
        RingingController.attach(host)
        try {
            assertTrue(RingingController.begin(alarm.id, "controller-root", System.currentTimeMillis(), 0))
            RingingController.surfaceShown()
            RingingController.missionStarted()
            RingingController.missionFailed()
            assertEquals(0, surfaces)
            RingingController.surfaceLost()
            assertEquals(0, stops)
            assertNotNull(RingingController.snapshot.value)
            RingingController.snoozeRequested()
            assertEquals(1, surfaces)
            assertTrue(RingingController.snapshot.value!!.state.snoozePendingMission)
            RingingController.missionCompleted()
            assertNull(RingingController.snapshot.value)
            val saved = com.alarmy.app.AppGraph.repository.occurrence("controller-root")!!
            assertEquals(1, saved.snoozeCount)
            assertEquals(AlarmOutcome.SNOOZED, saved.outcome)
            assertNotNull(saved.snoozedUntilEpochMillis)
        } finally {
            RingingController.detach(host)
        }
    }
}
