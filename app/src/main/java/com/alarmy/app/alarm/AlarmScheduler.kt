package com.alarmy.app.alarm

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.alarmy.app.data.AlarmRepository
import com.alarmy.app.ui.MainActivity
import com.alarmy.core.model.Alarm
import com.alarmy.core.model.DeliveryTier
import com.alarmy.core.model.OccurrenceKey
import com.alarmy.core.schedule.AlarmScheduleCalculator
import com.alarmy.core.schedule.SchedulingBudget
import com.alarmy.core.schedule.WatchdogPlanner
import com.alarmy.core.support.SystemTimeSource
import com.alarmy.core.support.TimeSource
import java.time.Instant

/**
 * Everything that touches `AlarmManager`.
 *
 * ## Why this class carries so much weight
 *
 * On iOS, AlarmKit guarantees delivery: the alarm rings through Silent Mode,
 * Focus, app termination and reboot, and the hard problem is that the system
 * draws a Stop button we cannot remove. Android is the exact inverse. We draw
 * every pixel of the ringing screen, but *nothing* guarantees we are ever
 * invoked. Doze defers alarms, OEM battery managers kill background apps, a
 * force-stop silently cancels every pending alarm, and a reboot wipes them all.
 *
 * So this class is defensive in three ways:
 *
 *  1. **It uses the only Doze-exempt tier that exists.** `setAlarmClock` is the
 *     sole `AlarmManager` API the system refuses to defer, because it also
 *     surfaces in the status-bar alarm chip. `setExactAndAllowWhileIdle` is
 *     rate-limited to roughly once every nine minutes per app and is used only
 *     as a fallback on API 31-32 where exact-alarm permission can be revoked.
 *
 *  2. **It records what it scheduled.** `AlarmManager` has no "list my pending
 *     alarms" call. A `PendingIntent` is identified by request code plus
 *     `Intent.filterEquals`, neither of which has room for a UUID, so the
 *     request code is derived from the occurrence key by
 *     [OccurrenceKey.requestCode] and the keys are persisted. That is what lets
 *     the app cancel an alarm it scheduled in a previous process.
 *
 *  3. **It is re-runnable from cold.** [rescheduleAll] is idempotent and is the
 *     only scheduling entry point. Boot, timezone change, app upgrade, alarm
 *     edit and mission completion all funnel through it.
 */
class AlarmScheduler(
    private val context: Context,
    private val repository: AlarmRepository,
    private val timeSource: TimeSource = SystemTimeSource()
) {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Android has no documented cap on pending alarms (unlike iOS's hard limit
     * of 64 pending notifications), but scheduling hundreds is wasteful and
     * makes the reliability screen unreadable. The budget bounds it and, more
     * importantly, makes the *trimming order* explicit and tested.
     */
    private val budget = SchedulingBudget(capacity = 100)

    fun currentTier(): DeliveryTier = when {
        canScheduleExact() -> DeliveryTier.EXACT_ALARM_CLOCK
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> DeliveryTier.EXACT_WHILE_IDLE
        else -> DeliveryTier.INEXACT
    }

    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

    // ---------------------------------------------------------------- planning

    /**
     * Recomputes the whole schedule from the stored alarms and re-registers it.
     *
     * Deliberately blunt: it cancels every key we remember and rebuilds. An
     * alarm clock that is subtly out of sync is worse than one that spends
     * twenty milliseconds re-registering, and this makes the boot path and the
     * edit path exercise identical code.
     */
    fun rescheduleAll() {
        cancelAllKnown()

        val now = timeSource.now()
        val calculator = AlarmScheduleCalculator(timeSource.zone())
        val requests = mutableListOf<SchedulingBudget.Request>()
        val detail = mutableMapOf<String, Pair<Alarm, Instant>>()

        for (alarm in repository.alarms.value.filter { it.isEnabled }) {
            // Two occurrences per alarm, not one: if the device is asleep when
            // the first fires and the app is killed before it can schedule the
            // next, the second still stands.
            val occurrences = calculator.occurrences(alarm, now, limit = 2)
            for (occurrence in occurrences) {
                requests += SchedulingBudget.Request(
                    key = occurrence.key,
                    fireDate = occurrence.fireDate,
                    isWatchdog = false,
                    isRepeating = alarm.isRepeating
                )
                detail[occurrence.key] = alarm to occurrence.fireDate
            }
        }

        val prioritised = budget.prioritise(requests, now)
        val scheduled = mutableListOf<String>()
        for (request in prioritised) {
            val (alarm, fireDate) = detail[request.key] ?: continue
            scheduleExact(alarm.id, request.key, fireDate, watchdogIndex = 0)
            scheduled += request.key
        }
        repository.rememberPending(scheduled)
    }

    /**
     * Arms the Mission Guard chain for a ringing alarm.
     *
     * On iOS this chain has to be scheduled *before* the alarm fires, because
     * a terminated app gets no chance to react. On Android the ringing service
     * is alive and holding a wake lock, so the chain is armed reactively at the
     * moment of firing. That is strictly better: it costs nothing when the user
     * dismisses properly, and the entries are cancelled the instant the mission
     * is completed.
     *
     * Note what this cannot defend against: a force-stop from the app-info
     * screen cancels every `AlarmManager` alarm including these. Only
     * [BootReceiver] and the next app launch can recover from that, and the
     * Reliability screen says so in plain language.
     */
    fun scheduleGuardChain(alarm: Alarm, parentFireDate: Instant, parentKey: String) {
        if (!alarm.isGuarded) return
        val plan = WatchdogPlanner().plan(alarm, parentFireDate, parentKey)
        if (plan.isEmpty) return
        val keys = mutableListOf<String>()
        for (entry in plan.entries) {
            scheduleExact(alarm.id, entry.key, entry.fireDate, watchdogIndex = entry.index)
            keys += entry.key
        }
        repository.rememberPending(keys)
    }

    fun scheduleSnooze(alarm: Alarm, parentKey: String, round: Int, minutes: Int) {
        val key = OccurrenceKey.snooze(OccurrenceKey.rootOf(parentKey), round)
        val fireDate = timeSource.now().plusSeconds(minutes * 60L)
        scheduleExact(alarm.id, key, fireDate, watchdogIndex = 0)
        repository.rememberPending(listOf(key))
    }

    // -------------------------------------------------------------- scheduling

    @SuppressLint("MissingPermission")
    private fun scheduleExact(alarmId: String, key: String, fireDate: Instant, watchdogIndex: Int) {
        val triggerAt = fireDate.toEpochMilli()
        val operation = broadcastIntent(alarmId, key, triggerAt, watchdogIndex)

        try {
            if (canScheduleExact()) {
                // The only tier Doze will not defer.
                val info = AlarmManager.AlarmClockInfo(triggerAt, showIntent())
                alarmManager.setAlarmClock(info, operation)
            } else {
                // API 31-32 with exact-alarm permission revoked. Pierces Doze
                // but is rate-limited, so it may land minutes late. The
                // occurrence records the lateness and the Reliability screen
                // explains it.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            }
        } catch (e: SecurityException) {
            // The permission was revoked between the check and the call.
            // Degrade rather than crash -- a late alarm beats no alarm.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        }
    }

    // ---------------------------------------------------------------- cancelling

    fun cancel(key: String) {
        alarmManager.cancel(broadcastIntent(alarmId = "", key = key, triggerAt = 0, watchdogIndex = 0))
        repository.forgetPending(listOf(key))
    }

    /**
     * Cancels a parent alarm together with its whole guard and snooze chain.
     *
     * Every derived key starts with the parent key followed by `#`, so the root
     * is recoverable from any member. This is what makes "mission completed"
     * a single atomic operation instead of a race between the completion and
     * the next watchdog.
     */
    fun cancelChain(anyKeyInChain: String) {
        val root = OccurrenceKey.rootOf(anyKeyInChain)
        val doomed = repository.pending().filter { OccurrenceKey.rootOf(it) == root }
        for (key in doomed) {
            alarmManager.cancel(broadcastIntent("", key, 0, 0))
        }
        repository.forgetPending(doomed)
    }

    fun cancelAllKnown() {
        val keys = repository.pending()
        for (key in keys) {
            alarmManager.cancel(broadcastIntent("", key, 0, 0))
        }
        repository.clearPending()
    }

    // ------------------------------------------------------------------ intents

    /**
     * Builds the `PendingIntent` for an occurrence.
     *
     * Two details matter for correctness:
     *  - the request code comes from the occurrence key, so the same key always
     *    produces the same `PendingIntent` even across process deaths;
     *  - the key is also in the intent's *data* URI. Extras are ignored by
     *    `Intent.filterEquals`, so without a distinguishing URI two different
     *    occurrences could collide if their hashes ever did.
     */
    private fun broadcastIntent(
        alarmId: String,
        key: String,
        triggerAt: Long,
        watchdogIndex: Int
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            data = Uri.parse("betteralarm://occurrence/$key")
            putExtra(AlarmReceiver.EXTRA_KEY, key)
            putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmReceiver.EXTRA_SCHEDULED_AT, triggerAt)
            putExtra(AlarmReceiver.EXTRA_WATCHDOG_INDEX, watchdogIndex)
        }
        return PendingIntent.getBroadcast(
            context,
            OccurrenceKey.requestCode(key),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Opens the app when the user taps the status-bar alarm chip. */
    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
