package com.alarmy.app.alarm

import android.content.Context
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import com.alarmy.core.model.SoundLibrary
import kotlin.math.max
import kotlin.math.min

/**
 * Plays the alarm and keeps it audible.
 *
 * ## Audio routing
 *
 * Everything goes to `USAGE_ALARM` / `CONTENT_TYPE_SONIFICATION`, which routes
 * to `STREAM_ALARM`. That stream is exempt from Do Not Disturb by default and
 * ignores the ringer's silent mode, so a phone on mute still rings -- matching
 * the iOS AlarmKit behaviour the user expects.
 *
 * ## Bundled sounds
 *
 * This repository ships **no audio files**. Distributing alarm tones means
 * distributing someone's copyright, and generating them would produce something
 * nobody wants to wake up to. [SoundLibrary] entries are looked up in `res/raw`
 * by name, and any that are missing fall back to the device's own default alarm
 * ringtone, which every Android device has. Dropping `radar.ogg` and friends
 * into `app/src/main/res/raw/` is all that is needed to enable them.
 *
 * ## Volume defence
 *
 * A user reaching for the volume rocker while half asleep is the single most
 * common way an alarm gets silenced. When Mission Guard is on, [startWatching]
 * observes the alarm stream and restores it to a floor if it is pulled below.
 * This is deliberately *not* done when Mission Guard is off: silently fighting
 * a user's explicit input is only defensible when they have asked for it.
 */
class AlarmSoundPlayer(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var volumeObserver: ContentObserver? = null

    private var rampRunnable: Runnable? = null
    private var targetVolume: Float = 1f
    private var currentVolume: Float = 1f
    private var volumeFloor: Int = 0

    fun start(soundId: String, volume: Double, gentleRamp: Boolean, vibrate: Boolean, escalated: Boolean) {
        stop()

        val uri = resolveSound(soundId)
        targetVolume = volume.coerceIn(0.05, 1.0).toFloat()

        // An escalating watchdog alert ignores the gentle ramp. The point of
        // escalation is that the gentle approach already failed.
        currentVolume = if (gentleRamp && !escalated) RAMP_START else targetVolume

        player = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                setVolume(currentVolume, currentVolume)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not play $uri", e)
            null
        }

        if (gentleRamp && !escalated) scheduleRamp()
        if (vibrate) startVibration(escalated)
    }

    private fun scheduleRamp() {
        val step = (targetVolume - RAMP_START) / RAMP_STEPS
        val runnable = object : Runnable {
            override fun run() {
                currentVolume = min(targetVolume, currentVolume + step)
                player?.runCatching { setVolume(currentVolume, currentVolume) }
                if (currentVolume < targetVolume) {
                    handler.postDelayed(this, RAMP_INTERVAL_MS)
                }
            }
        }
        rampRunnable = runnable
        handler.postDelayed(runnable, RAMP_INTERVAL_MS)
    }

    /**
     * Guards the alarm stream against being turned down.
     *
     * The floor is 60% of the device maximum rather than the maximum itself:
     * the goal is to stay audible, not to punish the user for touching the
     * volume rocker.
     */
    fun startWatching() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        volumeFloor = max((max * 0.6f).toInt(), 1)
        enforceFloor()

        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = enforceFloor()
        }
        volumeObserver = observer
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI, true, observer
            )
        }
    }

    private fun enforceFloor() {
        runCatching {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            if (current < volumeFloor) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, volumeFloor, 0)
            }
        }
    }

    private fun startVibration(escalated: Boolean) {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        vibrator = vib
        if (!vib.hasVibrator()) return

        val pattern = if (escalated) PATTERN_URGENT else PATTERN_NORMAL
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()
        runCatching {
            vib.vibrate(VibrationEffect.createWaveform(pattern, 0), attributes)
        }
    }

    fun stop() {
        rampRunnable?.let { handler.removeCallbacks(it) }
        rampRunnable = null

        player?.runCatching {
            if (isPlaying) stop()
            release()
        }
        player = null

        vibrator?.runCatching { cancel() }
        vibrator = null

        volumeObserver?.let { observer ->
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
        volumeObserver = null
    }

    private fun resolveSound(soundId: String): Uri {
        val sound = SoundLibrary.soundFor(soundId)
        val resId = context.resources.getIdentifier(
            sound.resourceName, "raw", context.packageName
        )
        if (resId != 0) {
            return Uri.parse("android.resource://${context.packageName}/$resId")
        }
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI
    }

    private companion object {
        const val TAG = "AlarmSoundPlayer"
        const val RAMP_START = 0.08f
        const val RAMP_STEPS = 30
        const val RAMP_INTERVAL_MS = 1_000L
        val PATTERN_NORMAL = longArrayOf(0, 500, 800)
        val PATTERN_URGENT = longArrayOf(0, 400, 200, 400, 200, 400, 900)
    }
}
