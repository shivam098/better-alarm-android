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
            ACTION_SNOOZE -> RingingController.snoozeRequested()
        }
    }

    companion object {
        const val ACTION_SNOOZE = "com.alarmy.app.action.SNOOZE"
    }
}
