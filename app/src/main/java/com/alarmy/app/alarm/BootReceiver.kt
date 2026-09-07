package com.alarmy.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.alarmy.app.AppGraph

/**
 * Restores the schedule after any event that can silently destroy it.
 *
 * This is the most important receiver in the app, and it has no equivalent on
 * iOS. `AlarmManager` registrations live in RAM in `system_server`: a reboot
 * discards every one of them, and so does a force-stop from the app-info
 * screen. Without this receiver an overnight reboot would mean no alarm, with
 * no warning and no way for the user to know until they woke up late.
 *
 * `LOCKED_BOOT_COMPLETED` matters as much as `BOOT_COMPLETED`. On a device with
 * file-based encryption the ordinary boot broadcast is not delivered until the
 * user unlocks -- which, if the phone rebooted at 2am, is exactly when the
 * alarm should already have rung. Handling the locked variant means alarms are
 * re-armed as soon as the system is up.
 *
 * `TIME_SET` and `TIMEZONE_CHANGED` are here because every fire time is derived
 * from wall-clock rules. Flying across a timezone changes what "7am" means, and
 * only a full recompute gets it right.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED) return

        AppGraph.ensureInstalled(context)

        // Anything we thought was pending died with the reboot or the upgrade.
        // Clearing first prevents cancel() calls against PendingIntents that no
        // longer exist, which would otherwise recreate them.
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            AppGraph.repository.clearPending()
        }

        val pending = goAsync()
        try {
            AppGraph.scheduler.rescheduleAll()
        } finally {
            pending.finish()
        }
    }

    private companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED
        )
    }
}
