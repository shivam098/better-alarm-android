package com.alarmy.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.alarmy.app.AppGraph

/**
 * Handles the snooze button on the ringing notification.
 *
 * There is deliberately no dismiss action here. Dismissing requires completing
 * the mission, and the mission requires the full-screen surface -- a shortcut
 * on the notification shade would defeat the entire purpose of the app.
 */
class AlarmActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AppGraph.ensureInstalled(context)
        when (intent.action) {
            ACTION_SNOOZE -> {
                if (intent.getStringExtra(AlarmReceiver.EXTRA_KEY) ==
                    RingingController.snapshot.value?.occurrenceKey) RingingController.snoozeRequested()
            }
            ACTION_AWAKE -> {
                val key = intent.getStringExtra(AlarmReceiver.EXTRA_KEY) ?: return
                val occurrence = AppGraph.repository.occurrence(com.alarmy.core.model.OccurrenceKey.rootOf(key)) ?: return
                val due = occurrence.wakeCheckAtEpochMillis ?: return
                if (AppGraph.timeSource.now().toEpochMilli() > due + 60_000) {
                    android.util.Log.i("AlarmActionReceiver", "Wake confirmation arrived after its deadline; re-alarm remains armed")
                    return
                }
                AppGraph.repository.recordOccurrence(occurrence.copy(wakeCheckAtEpochMillis = null))
                AppGraph.scheduler.cancelChain(key)
                AlarmNotifications.cancelWakeCheck(context, key)
            }
        }
    }

    companion object {
        const val ACTION_SNOOZE = "com.alarmy.app.action.SNOOZE"
        const val ACTION_AWAKE = "com.alarmy.app.action.AWAKE"
    }
}
