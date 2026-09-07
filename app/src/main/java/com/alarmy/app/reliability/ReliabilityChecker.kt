package com.alarmy.app.reliability

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.alarmy.app.alarm.AlarmNotifications
import com.alarmy.app.alarm.AlarmScheduler

enum class CheckSeverity { CRITICAL, IMPORTANT, ADVISORY }

/**
 * One thing that can stop this app from waking you up.
 *
 * [fixIntent] is null when there is no way to take the user to a settings page
 * -- notably for most OEM battery managers, where the only honest answer is
 * written instructions.
 */
data class ReliabilityCheck(
    val id: String,
    val title: String,
    val passed: Boolean,
    val severity: CheckSeverity,
    val explanation: String,
    val consequence: String,
    val fixLabel: String? = null,
    val fixIntent: Intent? = null
)

/**
 * Answers the only question that matters on Android: *will this alarm actually
 * ring?*
 *
 * On iOS this class would not exist. AlarmKit guarantees delivery, so there is
 * nothing to check and nothing the user can misconfigure. On Android an alarm
 * app is only as reliable as a stack of permissions and OEM battery settings
 * that the user has to be walked through, and which can be silently revoked
 * afterwards.
 *
 * The design principle here is that a failing check must always say what
 * *happens* if it stays unfixed, in plain language. "Battery optimisation is
 * enabled" means nothing to a user; "your alarm may be delayed or may not ring
 * at all" means everything.
 */
class ReliabilityChecker(
    private val context: Context,
    private val scheduler: AlarmScheduler
) {

    fun runAll(): List<ReliabilityCheck> = listOfNotNull(
        exactAlarms(),
        notificationsEnabled(),
        ringingChannelEnabled(),
        fullScreenIntent(),
        batteryOptimisation(),
        oemGuidance()
    )

    val criticalFailures: Int
        get() = runAll().count { !it.passed && it.severity == CheckSeverity.CRITICAL }

    // ------------------------------------------------------------------ checks

    private fun exactAlarms(): ReliabilityCheck {
        val ok = scheduler.canScheduleExact()
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !ok) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:${context.packageName}"))
        } else {
            null
        }
        return ReliabilityCheck(
            id = "exact_alarms",
            title = "Exact alarms",
            passed = ok,
            severity = CheckSeverity.CRITICAL,
            explanation = "Lets the app schedule an alarm for an exact minute. " +
                "Without it Android is free to batch the alarm with other background work.",
            consequence = "Your alarm could go off many minutes late, or be postponed " +
                "indefinitely while the phone is idle overnight.",
            fixLabel = if (intent != null) "Allow exact alarms" else null,
            fixIntent = intent
        )
    }

    private fun notificationsEnabled(): ReliabilityCheck {
        val ok = NotificationManagerCompat.from(context).areNotificationsEnabled()
        return ReliabilityCheck(
            id = "notifications",
            title = "Notifications",
            passed = ok,
            severity = CheckSeverity.CRITICAL,
            explanation = "The alarm screen is launched by a notification. It is the " +
                "mechanism, not a courtesy message.",
            consequence = "The alarm cannot appear at all. Nothing will wake you.",
            fixLabel = "Open notification settings",
            fixIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        )
    }

    /**
     * Checked separately from notifications as a whole, because a user can
     * disable this one channel from a long-press on the notification -- which
     * is exactly what a half-asleep person does at 6am to make it stop.
     */
    private fun ringingChannelEnabled(): ReliabilityCheck {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.getNotificationChannel(AlarmNotifications.CHANNEL_RINGING)
        } else {
            null
        }
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            channel != null && channel.importance >= NotificationManager.IMPORTANCE_HIGH
        } else {
            true
        }
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, AlarmNotifications.CHANNEL_RINGING)
        } else {
            null
        }
        return ReliabilityCheck(
            id = "ringing_channel",
            title = "Ringing alarms channel",
            passed = ok,
            severity = CheckSeverity.CRITICAL,
            explanation = "This channel must stay set to the highest importance. " +
                "Android only shows a full-screen alarm for high-importance notifications.",
            consequence = "The alarm becomes a silent banner instead of a full-screen alert.",
            fixLabel = if (intent != null) "Restore importance" else null,
            fixIntent = intent
        )
    }

    private fun fullScreenIntent(): ReliabilityCheck {
        val manager = context.getSystemService(NotificationManager::class.java)
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager?.canUseFullScreenIntent() ?: false
        } else {
            true
        }
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !ok) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                .setData(Uri.parse("package:${context.packageName}"))
        } else {
            null
        }
        return ReliabilityCheck(
            id = "full_screen_intent",
            title = "Full-screen alerts",
            passed = ok,
            severity = CheckSeverity.CRITICAL,
            explanation = "Permission to show the alarm over your lock screen. " +
                "Android 14 restricted this to alarm and calling apps.",
            consequence = "The alarm will ring but the mission screen will not appear " +
                "until you unlock the phone and tap the notification.",
            fixLabel = if (intent != null) "Allow full-screen alerts" else null,
            fixIntent = intent
        )
    }

    /**
     * The single most common cause of a missed Android alarm.
     *
     * Note this is `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which Google Play
     * permits for alarm clocks specifically. It is offered, never demanded, and
     * the app keeps working without it -- just less reliably, which the
     * consequence text says outright.
     */
    private fun batteryOptimisation(): ReliabilityCheck {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ok = power?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        val intent = if (!ok) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
        } else {
            null
        }
        return ReliabilityCheck(
            id = "battery_optimisation",
            title = "Battery optimisation",
            passed = ok,
            severity = CheckSeverity.IMPORTANT,
            explanation = "Android puts unused apps into a deep sleep to save power. " +
                "An alarm clock needs to be woken from it.",
            consequence = "Alarms may be delayed, and repeating alarms may stop being " +
                "rescheduled after a few days of not opening the app.",
            fixLabel = if (intent != null) "Turn off optimisation" else null,
            fixIntent = intent
        )
    }

    /**
     * Manufacturer-specific killers, which no API can detect.
     *
     * There is no way to ask Android whether MIUI's "Autostart" is on or
     * whether the device is in Huawei's protected-apps list, so this check is
     * always reported as unknown on affected devices and links to written
     * instructions instead of pretending to know.
     */
    private fun oemGuidance(): ReliabilityCheck? {
        val guidance = OemGuidance.forCurrentDevice() ?: return null
        return ReliabilityCheck(
            id = "oem",
            title = "${guidance.manufacturer} battery manager",
            passed = false,
            severity = CheckSeverity.IMPORTANT,
            explanation = guidance.explanation,
            consequence = "On ${guidance.manufacturer} devices this is the most common " +
                "reason an alarm never rings. Android provides no way for the app to " +
                "check it, so please confirm it manually.",
            fixLabel = guidance.settingsIntent(context)?.let { "Open settings" },
            fixIntent = guidance.settingsIntent(context)
        )
    }

    /** Warns the user out-of-band when something critical is broken. */
    fun notifyIfBroken() {
        val failures = runAll().filter { !it.passed && it.severity == CheckSeverity.CRITICAL }
        if (failures.isEmpty()) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val first = failures.first()
        runCatching {
            manager.notify(
                AlarmNotifications.NOTIFICATION_WARNING,
                AlarmNotifications.warningNotification(
                    context,
                    "Your alarms may not ring",
                    "${first.title}: ${first.consequence}"
                )
            )
        }
    }
}
