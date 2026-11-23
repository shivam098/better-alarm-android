package com.alarmy.app.alarm

import com.alarmy.app.AppGraph
import com.alarmy.app.sensor.DeviceCapabilities
import com.alarmy.core.mission.MissionLibrary
import com.alarmy.core.model.Alarm
import com.alarmy.core.model.AlarmOccurrence
import com.alarmy.core.model.AlarmOutcome
import com.alarmy.core.model.MissionConfig
import com.alarmy.core.model.MissionType
import com.alarmy.core.model.OccurrenceKey
import com.alarmy.core.schedule.MissedWindowDecision
import com.alarmy.core.schedule.MissedWindowPolicy
import com.alarmy.core.state.AlarmEffect
import com.alarmy.core.state.AlarmEvent
import com.alarmy.core.state.AlarmPhase
import com.alarmy.core.state.AlarmState
import com.alarmy.core.state.AlarmStateMachine
import com.alarmy.core.state.SnoozePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * What the ringing UI needs to draw itself.
 *
 * Deliberately a plain snapshot rather than a live handle on the service: the
 * ringing activity can be destroyed and recreated (rotation, system pressure,
 * the user returning from the camera) and must be able to rebuild from this
 * alone without restarting the mission.
 */
data class RingingSnapshot(
    val alarm: Alarm,
    val occurrenceKey: String,
    val scheduledAt: Instant,
    val firedAt: Instant,
    val mission: MissionConfig,
    val substitutedFrom: MissionType?,
    val state: AlarmState,
    val disclosure: String?,
    val snoozesRemaining: Int,
    val canSnooze: Boolean
)

/** What the controller needs the service to do for it. */
interface RingingHost {
    fun startAudio(alarm: Alarm, escalate: Boolean)
    fun stopAudio()
    fun acquireWakeLock()
    fun releaseWakeLock()
    fun refreshNotification(title: String, body: String, canSnooze: Boolean)
    fun showSurface()
    fun finish()
}

/**
 * Drives one ringing alarm from fire to resolution.
 *
 * A process-wide singleton because the ringing activity and the ringing service
 * are two views of one thing, and passing state through Intents would lose it
 * exactly when the app is under the memory pressure that makes losing it fatal.
 *
 * All decisions are delegated to [AlarmStateMachine], which is pure and covered
 * by unit tests. This class only translates [AlarmEffect]s into Android calls,
 * so a bug here is a wiring bug rather than a logic bug.
 */
object RingingController {

    private val _snapshot = MutableStateFlow<RingingSnapshot?>(null)
    val snapshot: StateFlow<RingingSnapshot?> = _snapshot.asStateFlow()

    private var host: RingingHost? = null
    private var machine: AlarmStateMachine? = null
    private var state = AlarmState()
    private var occurrence: AlarmOccurrence? = null
    private var missionStartedAt: Instant? = null
    private var surfaceVisible = false

    val isRinging: Boolean get() = _snapshot.value != null

    fun attach(host: RingingHost) {
        this.host = host
    }

    fun detach(host: RingingHost) {
        if (this.host === host) {
            this.host = null
            _snapshot.value = null
            machine = null
            occurrence = null
            missionStartedAt = null
            surfaceVisible = false
            state = AlarmState()
        }
    }

    /**
     * Starts a ringing session.
     *
     * Returns false if the alarm should not ring at all -- it was deleted, or
     * it is so late that ringing now would be worse than admitting it was
     * missed. Both cases are recorded rather than silently dropped.
     */
    fun begin(alarmId: String, key: String, scheduledAtMillis: Long, watchdogIndex: Int): Boolean {
        val repository = AppGraph.repository
        val scheduler = AppGraph.scheduler
        val now = AppGraph.timeSource.now()
        val scheduledAt = Instant.ofEpochMilli(scheduledAtMillis)

        // A watchdog identifies its parent, so a chain entry can still find its
        // alarm after the app has been restarted.
        val rootKey = OccurrenceKey.rootOf(key)
        if (_snapshot.value?.let { OccurrenceKey.rootOf(it.occurrenceKey) == rootKey } == true) {
            scheduler.cancel(key)
            return true
        }
        if (_snapshot.value != null) {
            scheduler.defer(alarmId, key, scheduledAtMillis, watchdogIndex)
            return true
        }
        val alarm = repository.alarm(alarmId)
            ?: repository.occurrence(rootKey)?.let { repository.alarm(it.alarmId) }
            ?: run {
                scheduler.cancelChain(key)
                return false
            }
        if (!alarm.isEnabled) {
            scheduler.cancelChain(key)
            return false
        }

        // Already finished this morning? A late watchdog must stay quiet.
        val existing = repository.occurrence(rootKey)
        if (existing?.outcome == AlarmOutcome.SNOOZED && key.contains("#wd")) {
            scheduler.cancel(key)
            return false
        }
        val wakeCheck = key.endsWith("#wake")
        if (wakeCheck && existing?.wakeCheckAtEpochMillis == null) {
            scheduler.cancelChain(key)
            return false
        }
        if (wakeCheck) AlarmNotifications.cancelWakeCheck(AppGraph.appContext, key)
        if (existing?.outcome == AlarmOutcome.COMPLETED && !wakeCheck) {
            scheduler.cancelChain(key)
            return false
        }

        val settings = AppGraph.settings.settings.value
        val decision = if (now.epochSecond - scheduledAt.epochSecond >= 60) {
            MissedWindowPolicy(settings.missedWindowRule, settings.missedWindowGraceMinutes)
                .decide(scheduledAt, now)
        } else MissedWindowDecision.NotYetDue
        if (decision is MissedWindowDecision.SkipAndRecordMissed) {
            repository.recordOccurrence(
                buildOccurrence(alarm, rootKey, scheduledAt, now, alarm.mission, null)
                    .copy(outcome = AlarmOutcome.MISSED, resolvedAtEpochMillis = now.toEpochMilli())
            )
            scheduler.cancelChain(key)
            scheduler.rescheduleAll()
            return false
        }

        val capabilities = DeviceCapabilities(AppGraph.appContext)
        val requested = if (watchdogIndex >= 3 && alarm.missionGuard.escalates) {
            alarm.mission.escalated()
        } else alarm.mission
        val resolved = MissionLibrary.resolve(
            // Walking handles permission/sensor recovery in its own UI. Do not
            // replace the user's walking goal with a different mission.
            requested, capabilities.availableMissions() + MissionType.STEPS,
            targetAvailable = { config ->
                when (config.type) {
                    MissionType.PHOTO -> config.referenceAssetIds.firstOrNull()?.let { AppGraph.targets.photo(it) } != null
                    MissionType.QR -> config.referenceAssetIds.firstOrNull()?.let { AppGraph.targets.code(it) } != null
                    else -> true
                }
            },
            fallback = alarm.fallbackMission
        )
        val substituted = if (resolved != requested.normalized()) requested.type else null

        machine = AlarmStateMachine(
            snoozePolicy = SnoozePolicy(
                baseMinutes = alarm.snooze.duration,
                maxSnoozes = if (!alarm.snooze.isEnabled || wakeCheck) 0 else alarm.snooze.limit ?: Int.MAX_VALUE,
                requiresMission = alarm.snooze.costsMission,
                escalating = alarm.snooze.escalating
            ),
            guarded = alarm.isGuarded,
            mission = resolved
        )
        state = AlarmState(snoozeCount = existing?.snoozeCount ?: 0)
        missionStartedAt = null

        occurrence = existing?.copy(outcome = null, resolvedAtEpochMillis = null,
            snoozedUntilEpochMillis = null, wakeCheckAtEpochMillis = null)
            ?: buildOccurrence(alarm, rootKey, scheduledAt, now, resolved, substituted)
        occurrence?.let { repository.recordOccurrence(it) }

        _snapshot.value = RingingSnapshot(
            alarm = alarm,
            occurrenceKey = key,
            scheduledAt = existing?.scheduledAt ?: scheduledAt,
            firedAt = now,
            mission = resolved,
            substitutedFrom = substituted,
            state = state,
            disclosure = if (wakeCheck) "Your wake check was not confirmed. Complete the mission again." else null,
            snoozesRemaining = ((alarm.snooze.limit ?: Int.MAX_VALUE) - state.snoozeCount).coerceAtLeast(0),
            canSnooze = alarm.snooze.isEnabled && !wakeCheck
        )

        dispatch(AlarmEvent.Fired(occurrenceKey = key, watchdogIndex = watchdogIndex))
        return true
    }

    fun missionStarted() = dispatch(AlarmEvent.MissionStarted)
    fun missionCompleted() = dispatch(AlarmEvent.MissionCompleted)
    fun missionFailed() = dispatch(AlarmEvent.MissionFailed)
    fun snoozeRequested() {
        if (_snapshot.value?.canSnooze == true) {
            dispatch(AlarmEvent.SnoozeRequested)
            if (state.snoozePendingMission && !surfaceVisible) host?.showSurface()
        }
    }
    fun surfaceShown() { surfaceVisible = true }
    fun dismissedWithoutMission() = dispatch(AlarmEvent.DismissedWithoutMission)
    fun timedOut() = dispatch(AlarmEvent.GuardWindowElapsed)

    /**
     * The ringing surface disappeared without the user resolving the alarm.
     *
     * On iOS this can only be handled by a chain scheduled in advance, because
     * a terminated app never learns it happened. Here the service is still
     * alive, so audio continues and the notification returns to the mission.
     * The watchdog chain remains the backstop if the whole process dies.
     */
    fun surfaceLost() {
        surfaceVisible = false
        if (!isRinging) return
        if (state.phase == AlarmPhase.COMPLETED || state.phase == AlarmPhase.SNOOZED) return
        // Launching a camera or permission dialog also stops the activity.
        // Losing that window is never consent to silence or finish a mission.
        host?.refreshNotification("Alarm still ringing", "Return to finish your mission.", _snapshot.value?.canSnooze == true)
    }

    fun recordAttempt(failureReason: String?) {
        occurrence = occurrence?.let {
            it.copy(
                attempts = it.attempts + 1,
                failureReasons = if (failureReason != null) it.failureReasons + failureReason else it.failureReasons
            )
        }
    }

    // --------------------------------------------------------------- reduction

    @Synchronized
    private fun dispatch(event: AlarmEvent) {
        val engine = machine ?: return
        val transition = engine.reduce(state, event)
        state = transition.state

        if (event is AlarmEvent.MissionStarted && missionStartedAt == null) {
            missionStartedAt = AppGraph.timeSource.now()
        }

        var disclosure = _snapshot.value?.disclosure
        for (effect in transition.effects) {
            when (effect) {
                is AlarmEffect.Disclose -> disclosure = effect.message
                else -> perform(effect)
            }
        }

        _snapshot.value = _snapshot.value?.copy(
            state = state,
            disclosure = disclosure,
            snoozesRemaining = snoozesRemaining(),
            canSnooze = _snapshot.value?.canSnooze == true && snoozesRemaining() > 0
        )
        _snapshot.value?.let {
            host?.refreshNotification(it.alarm.name, disclosure ?: "Complete the mission to stop the alarm.", it.canSnooze)
        }

        if (state.phase == AlarmPhase.COMPLETED ||
            state.phase == AlarmPhase.SNOOZED ||
            state.phase == AlarmPhase.MISSED ||
            state.phase == AlarmPhase.GUARD_PENDING
        ) {
            finishSession()
        }
    }

    private fun perform(effect: AlarmEffect) {
        val snapshot = _snapshot.value ?: return
        val scheduler = AppGraph.scheduler
        when (effect) {
            is AlarmEffect.StartRinging -> host?.startAudio(snapshot.alarm, effect.escalate)
            AlarmEffect.StopRinging -> host?.stopAudio()
            is AlarmEffect.PresentMission -> Unit
            AlarmEffect.DismissMission -> Unit
            AlarmEffect.ScheduleGuardChain ->
                scheduler.scheduleGuardChain(snapshot.alarm, snapshot.scheduledAt, OccurrenceKey.rootOf(snapshot.occurrenceKey))
            AlarmEffect.CancelGuardChain -> scheduler.cancelChain(snapshot.occurrenceKey)
            is AlarmEffect.ScheduleSnooze -> {
                occurrence = occurrence?.copy(
                    snoozeCount = state.snoozeCount,
                    outcome = AlarmOutcome.SNOOZED,
                    snoozedUntilEpochMillis = AppGraph.timeSource.now().plusSeconds(effect.minutes * 60L).toEpochMilli()
                )
                occurrence?.let { AppGraph.repository.recordOccurrence(it) }
                scheduler.scheduleSnooze(
                    snapshot.alarm,
                    snapshot.occurrenceKey,
                    state.snoozeCount,
                    effect.minutes
                )
            }
            is AlarmEffect.RecordOutcome -> recordOutcome(effect.outcome)
            AlarmEffect.AcquireWakeLock -> host?.acquireWakeLock()
            AlarmEffect.ReleaseWakeLock -> host?.releaseWakeLock()
            is AlarmEffect.Disclose -> Unit
        }
    }

    private fun recordOutcome(outcome: AlarmOutcome) {
        val now = AppGraph.timeSource.now()
        val started = missionStartedAt
        val snapshot = _snapshot.value
        val wakeDue = if (outcome == AlarmOutcome.COMPLETED &&
            snapshot?.alarm?.wakeCheckEnabled == true && !snapshot.occurrenceKey.endsWith("#wake")) {
            now.plusSeconds(snapshot.alarm.wakeCheckMinutes.coerceIn(1, 30) * 60L).toEpochMilli()
        } else null
        occurrence = occurrence?.copy(
            outcome = outcome,
            wakeCheckAtEpochMillis = wakeDue,
            snoozedUntilEpochMillis = if (outcome == AlarmOutcome.SNOOZED) occurrence?.snoozedUntilEpochMillis else null,
            resolvedAtEpochMillis = now.toEpochMilli(),
            timeToCompleteMs = if (started != null && outcome == AlarmOutcome.COMPLETED) {
                now.toEpochMilli() - started.toEpochMilli()
            } else {
                null
            },
            attempts = state.missionAttempts
        )
        occurrence?.let { AppGraph.repository.recordOccurrence(it) }
        if (outcome == AlarmOutcome.COMPLETED && snapshot != null) {
            AlarmNotifications.cancelWakeCheck(AppGraph.appContext, snapshot.occurrenceKey)
            if (snapshot.alarm.wakeCheckEnabled && !snapshot.occurrenceKey.endsWith("#wake")) {
                AppGraph.scheduler.scheduleWakeCheck(snapshot.alarm, OccurrenceKey.rootOf(snapshot.occurrenceKey),
                    Instant.ofEpochMilli(requireNotNull(wakeDue)))
            }
        }

        // A repeating alarm needs its next instance armed now that this one is
        // consumed. rescheduleAll is idempotent, so this is safe to call on
        // every outcome including a snooze.
        AppGraph.scheduler.rescheduleAll()
    }

    private fun buildOccurrence(
        alarm: Alarm,
        key: String,
        scheduledAt: Instant,
        firedAt: Instant,
        mission: MissionConfig,
        substituted: MissionType?
    ): AlarmOccurrence {
        val lateness = (firedAt.epochSecond - scheduledAt.epochSecond).coerceAtLeast(0)
        return AlarmOccurrence(
            alarmId = alarm.id,
            occurrenceKey = key,
            scheduledAtEpochMillis = scheduledAt.toEpochMilli(),
            firedAtEpochMillis = firedAt.toEpochMilli(),
            missionType = mission.type,
            difficulty = mission.difficulty,
            deliveryTier = AppGraph.scheduler.currentTier(),
            substitutedFrom = substituted,
            latenessSeconds = lateness
        )
    }

    private fun snoozesRemaining(): Int {
        val limit = _snapshot.value?.alarm?.snooze?.limit ?: return Int.MAX_VALUE
        return (limit - state.snoozeCount).coerceAtLeast(0)
    }

    private fun finishSession() {
        host?.stopAudio()
        host?.releaseWakeLock()
        host?.finish()
        _snapshot.value = null
        machine = null
        occurrence = null
        missionStartedAt = null
        state = AlarmState()
    }
}
