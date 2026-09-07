package com.alarmy.app.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.alarmy.app.R
import com.alarmy.app.ui.MainActivity
import com.alarmy.app.ui.ringing.RingingActivity

/**
 * Notification channels and builders.
 *
 * The ringing channel is the load-bearing one. Three of its settings are not
 * cosmetic:
 *
 *  - **IMPORTANCE_HIGH** is the minimum at which Android will honour a
 *    full-screen intent. At `DEFAULT` the alarm would silently become a
 *    heads-up banner that a sleeping user never sees.
 *  - **`setSound(null, null)`** because [AlarmSoundPlayer] owns the audio. A
 *    channel sound cannot be ramped, cannot be looped past its length, and
 *    cannot be routed to `STREAM_ALARM` reliably.
 *  - **`setBypassDnd(true)`** is attempted but is only granted to apps with
 *    notification-policy access. It is a bonus, not a dependency: audio played
 *    with `USAGE_ALARM` is already exempt from Do Not Disturb by default.
 */
object AlarmNotifications {

    const val CHANNEL_RINGING = "ringing"
    const val CHANNEL_UPCOMING = "upcoming"
    const val CHANNEL_WARNINGS = "warnings"

    const val NOTIFICATION_RINGING = 1001
    const val NOTIFICATION_WARNING = 1002

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val ringing = NotificationChannel(
            CHANNEL_RINGING,
            context.getString(R.string.channel_ringing_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_ringing_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }

        val upcoming = NotificationChannel(
            CHANNEL_UPCOMING,
            context.getString(R.string.channel_upcoming_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_upcoming_desc)
            setShowBadge(false)
        }

        val warnings = NotificationChannel(
            CHANNEL_WARNINGS,
            context.getString(R.string.channel_warnings_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.channel_warnings_desc)
        }

        manager.createNotificationChannels(listOf(ringing, upcoming, warnings))
    }

    /**
     * The notification that keeps the ringing service in the foreground.
     *
     * `setFullScreenIntent(..., true)` is what launches [RingingActivity] over
     * the lock screen. Since Android 14 this is only honoured for apps whose
     * core function is an alarm or a call; this app qualifies, and the
     * Reliability screen checks `canUseFullScreenIntent()` at runtime and warns
     * the user if the system disagrees.
     *
     * `setOngoing` plus a null delete intent means the notification cannot be
     * swiped away, and "Clear all" will not remove it -- that is Mission Guard's
     * first line of defence.
     */
    fun ringingNotification(
        context: Context,
        title: String,
        body: String,
        occurrenceKey: String,
        canSnooze: Boolean
    ): Notification {
        val fullScreen = PendingIntent.getActivity(
            context,
            occurrenceKey.hashCode() and 0x7FFFFFFF,
            Intent(context, RingingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(AlarmReceiver.EXTRA_KEY, occurrenceKey)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_alarm_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)

        if (canSnooze) {
            builder.addAction(
                R.drawable.ic_alarm_notification,
                "Snooze",
                actionIntent(context, AlarmActionReceiver.ACTION_SNOOZE, occurrenceKey)
            )
        }
        // Note the absence of a "Dismiss" action. Dismissing requires the
        // mission, and the mission requires the full-screen surface. Offering a
        // shortcut here would undo the entire point of the app.
        builder.addAction(
            R.drawable.ic_alarm_notification,
            "Open",
            fullScreen
        )
        return builder.build()
    }

    fun warningNotification(context: Context, title: String, body: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_WARNINGS)
            .setSmallIcon(R.drawable.ic_alarm_notification)
            .setContentTitle(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_OPEN_RELIABILITY, true),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun actionIntent(context: Context, action: String, key: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (action + key).hashCode() and 0x7FFFFFFF,
            Intent(context, AlarmActionReceiver::class.java)
                .setAction(action)
                .putExtra(AlarmReceiver.EXTRA_KEY, key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
