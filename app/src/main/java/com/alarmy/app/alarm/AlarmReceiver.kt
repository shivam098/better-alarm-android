package com.alarmy.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.alarmy.app.AppGraph

/**
 * The entry point for every alarm the system delivers.
 *
 * This runs on the main thread inside a system-held wake lock that is released
 * the moment [onReceive] returns, so it does exactly one thing: start the
 * foreground service, which takes its own wake lock. Any work done here instead
 * risks the device falling back asleep mid-alarm.
 *
 * Starting a foreground service from the background is normally forbidden since
 * Android 12, but delivery of an exact alarm scheduled with `setAlarmClock`
 * puts the app on a temporary allowlist for precisely this handoff.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AppGraph.ensureInstalled(context)

        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val alarmId = intent.getStringExtra(EXTRA_ALARM_ID).orEmpty()
        val scheduledAt = intent.getLongExtra(EXTRA_SCHEDULED_AT, System.currentTimeMillis())
        val watchdogIndex = intent.getIntExtra(EXTRA_WATCHDOG_INDEX, 0)

        // This alarm has now been consumed; it no longer exists in AlarmManager.
        AppGraph.repository.forgetPending(listOf(key))

        val service = Intent(context, RingingService::class.java).apply {
            action = RingingService.ACTION_START
            putExtra(EXTRA_KEY, key)
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_SCHEDULED_AT, scheduledAt)
            putExtra(EXTRA_WATCHDOG_INDEX, watchdogIndex)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
        } catch (e: Exception) {
            // If even this fails the alarm is lost, so leave a trace the
            // Reliability screen can point at rather than dying silently.
            Log.e(TAG, "Could not start ringing service for $key", e)
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        const val ACTION_FIRE = "com.alarmy.app.action.FIRE"
        const val EXTRA_KEY = "occurrence_key"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
        const val EXTRA_WATCHDOG_INDEX = "watchdog_index"
    }
}
