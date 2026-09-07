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

    val isRinging: Boolean get() = _snapshot.value != null

    fun attach(host: RingingHost) {
        this.host = host
    }

    fun detach(host: RingingHost) {
        if (this.host === host) this.host = null
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
        val alarm = repository.alarm(alarmId)
            ?: repository.occurrence(rootKey)?.let { repository.alarm(it.alarmId) }
            ?: run {
                scheduler.cancelChain(key)
                return false
            }

        // Already finished this morning? A late watchdog must stay quiet.
        val existing = repository.occurrence(rootKey)
        if (existing?.outcome == AlarmOutcome.COMPLETED) {
            scheduler.cancelChain(key)
            return false
        }

        val decision = MissedWindowPolicy().decide(scheduledAt, now)
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
        val requested = if (watchdogIndex > 0) alarm.mission.escalated() else alarm.mission
        val resolved = MissionLibrary.resolve(requested, capabilities.availableMissions())
        val substituted = if (resolved.type != requested.type) requested.type else null

        machine = AlarmStateMachine(
            snoozePolicy = SnoozePolicy(
                baseMinutes = alarm.snooze.duration,
                maxSnoozes = alarm.snooze.limit ?: Int.MAX_VALUE,
                requiresMission = alarm.snooze.costsMission
            ),
            guarded = alarm.isGuarded,
            mission = resolved
        )
        state = AlarmState()
        missionStartedAt = null

        occurrence = buildOccurrence(alarm, rootKey, scheduledAt, now, resolved, substituted)
            .copy(snoozeCount = existing?.snoozeCount ?: 0)

        _snapshot.value = RingingSnapshot(
            alarm = alarm,
            occurrenceKey = key,
            scheduledAt = scheduledAt,
            firedAt = now,
            mission = resolved,
            substitutedFrom = substituted,
            state = state,
            disclosure = null,
            snoozesRemaining = alarm.snooze.limit ?: Int.MAX_VALUE,
            canSnooze = alarm.snooze.isEnabled
        )

        dispatch(AlarmEvent.Fired(occurrenceKey = key, watchdogIndex = watchdogIndex))
        return true
    }

    fun missionStarted() = dispatch(AlarmEvent.MissionStarted)
    fun missionCompleted() = dispatch(AlarmEvent.MissionCompleted)
    fun missionFailed() = dispatch(AlarmEvent.MissionFailed)
    fun snoozeRequested() = dispatch(AlarmEvent.SnoozeRequested)
    fun dismissedWithoutMission() = dispatch(AlarmEvent.DismissedWithoutMission)

    /**
     * The ringing surface disappeared without the user resolving the alarm.
     *
     * On iOS this can only be handled by a chain scheduled in advance, because
     * a terminated app never learns it happened. Here the service is still
     * alive, so the surface is brought straight back -- the watchdog chain
     * remains as the backstop for the case where the whole process dies.
     */
    fun surfaceLost() {
        if (!isRinging) return
        if (state.phase == AlarmPhase.COMPLETED || state.phase == AlarmPhase.SNOOZED) return
        dispatch(AlarmEvent.SurfaceLost)
        if (_snapshot.value?.alarm?.isGuarded == true) {
            host?.showSurface()
        }
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
            snoozesRemaining = snoozesRemaining()
        )

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
            is AlarmEffect.PresentMission -> host?.showSurface()
            AlarmEffect.DismissMission -> Unit
            AlarmEffect.ScheduleGuardChain ->
                scheduler.scheduleGuardChain(snapshot.alarm, snapshot.scheduledAt, snapshot.occurrenceKey)
            AlarmEffect.CancelGuardChain -> scheduler.cancelChain(snapshot.occurrenceKey)
            is AlarmEffect.ScheduleSnooze -> {
                occurrence = occurrence?.let { it.copy(snoozeCount = it.snoozeCount + 1) }
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
        occurrence = occurrence?.copy(
            outcome = outcome,
            resolvedAtEpochMillis = now.toEpochMilli(),
            timeToCompleteMs = if (started != null && outcome == AlarmOutcome.COMPLETED) {
                now.toEpochMilli() - started.toEpochMilli()
            } else {
                null
            },
            attempts = state.missionAttempts
        )
        occurrence?.let { AppGraph.repository.recordOccurrence(it) }

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
