package com.alarmy.app.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.alarmy.app.AppGraph
import com.alarmy.app.ui.ringing.RingingActivity
import com.alarmy.core.model.Alarm

/**
 * Keeps the process alive and audible for the duration of a ringing alarm.
 *
 * Everything about this service is chosen to make it hard to stop by accident:
 *
 *  - It is a **foreground service**, so the system will not reclaim it under
 *    ordinary memory pressure.
 *  - `android:stopWithTask="false"` plus [onTaskRemoved] means swiping the app
 *    out of recents does not kill it -- instead the surface is brought back.
 *  - It holds a **partial wake lock**, because `setAlarmClock` only guarantees
 *    the CPU is awake long enough to deliver the broadcast, not for the several
 *    minutes a mission may take.
 *  - It returns `START_REDELIVER_INTENT`, so if the system does kill it the
 *    same alarm is restarted with the same occurrence key rather than being
 *    silently dropped.
 *
 * It contains no alarm logic. Decisions belong to [RingingController], which
 * delegates them to the unit-tested state machine.
 */
class RingingService : Service(), RingingHost {

    private lateinit var player: AlarmSoundPlayer
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        AppGraph.ensureInstalled(this)
        player = AlarmSoundPlayer(this)
        RingingController.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppGraph.ensureInstalled(this)

        val key = intent?.getStringExtra(AlarmReceiver.EXTRA_KEY)
        if (intent?.action != ACTION_START || key == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // The notification must exist before any other work: Android gives a
        // service five seconds to call startForeground, and losing that race
        // means an ANR instead of an alarm.
        promoteToForeground(placeholderNotification())

        val started = RingingController.begin(
            alarmId = intent.getStringExtra(AlarmReceiver.EXTRA_ALARM_ID).orEmpty(),
            key = key,
            scheduledAtMillis = intent.getLongExtra(
                AlarmReceiver.EXTRA_SCHEDULED_AT, System.currentTimeMillis()
            ),
            watchdogIndex = intent.getIntExtra(AlarmReceiver.EXTRA_WATCHDOG_INDEX, 0)
        )

        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }

        showSurface()
        armSafetyTimeout()
        return START_REDELIVER_INTENT
    }

    // ------------------------------------------------------------ RingingHost

    override fun startAudio(alarm: Alarm, escalate: Boolean) {
        player.start(
            soundId = alarm.soundId,
            volume = alarm.volume,
            gentleRamp = alarm.gentleRamp,
            vibrate = alarm.vibrationEnabled,
            escalated = escalate
        )
        if (alarm.isGuarded) player.startWatching()
        refreshNotification(
            title = alarm.name,
            body = if (escalate) {
                "Still ringing — the mission was not completed."
            } else {
                "Complete the mission to stop the alarm."
            },
            canSnooze = alarm.snooze.isEnabled
        )
    }

    override fun stopAudio() = player.stop()

    override fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // Bounded so a bug here can never flatten the battery.
            acquire(MAX_RING_MILLIS)
        }
    }

    override fun releaseWakeLock() {
        wakeLock?.runCatching { if (isHeld) release() }
        wakeLock = null
    }

    override fun refreshNotification(title: String, body: String, canSnooze: Boolean) {
        val key = RingingController.snapshot.value?.occurrenceKey ?: return
        promoteToForeground(
            AlarmNotifications.ringingNotification(this, title, body, key, canSnooze)
        )
    }

    /**
     * Brings the full-screen surface back.
     *
     * The notification's full-screen intent normally does this, but the system
     * only honours it when the screen is off or locked. If the user is awake
     * and simply switched apps, this direct start is what returns them to the
     * mission.
     */
    override fun showSurface() {
        val key = RingingController.snapshot.value?.occurrenceKey ?: return
        runCatching {
            startActivity(
                Intent(this, RingingActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_NO_USER_ACTION
                    )
                    .putExtra(AlarmReceiver.EXTRA_KEY, key)
            )
        }
    }

    override fun finish() {
        cancelSafetyTimeout()
        stopAudio()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --------------------------------------------------------------- lifecycle

    /**
     * The user swiped the app out of recents while it was ringing.
     *
     * This is Mission Guard's most common trigger. The service survives because
     * of `stopWithTask="false"`, and the surface is put straight back.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        RingingController.surfaceLost()
    }

    override fun onDestroy() {
        cancelSafetyTimeout()
        stopAudio()
        releaseWakeLock()
        RingingController.detach(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ----------------------------------------------------------------- helpers

    private fun promoteToForeground(notification: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(
                this, AlarmNotifications.NOTIFICATION_RINGING, notification, type
            )
        }
    }

    private fun placeholderNotification() = AlarmNotifications.ringingNotification(
        context = this,
        title = "Alarm",
        body = "Starting…",
        occurrenceKey = "boot",
        canSnooze = false
    )

    /**
     * Stops an alarm nobody is going to answer.
     *
     * Without this, an alarm that fires while the phone is in a bag rings until
     * the battery dies. The occurrence is recorded as missed so the user sees
     * it in their history rather than wondering what happened.
     */
    private fun armSafetyTimeout() {
        cancelSafetyTimeout()
        val runnable = Runnable {
            RingingController.dismissedWithoutMission()
            finish()
        }
        timeout = runnable
        handler.postDelayed(runnable, MAX_RING_MILLIS)
    }

    private fun cancelSafetyTimeout() {
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
    }

    companion object {
        const val ACTION_START = "com.alarmy.app.action.START_RINGING"
        private const val WAKE_LOCK_TAG = "BetterAlarm:ringing"

        /** Fifteen minutes, matching the longest watchdog gap in the guard chain. */
        private const val MAX_RING_MILLIS = 15 * 60 * 1000L
    }
}
