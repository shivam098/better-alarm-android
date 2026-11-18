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
import com.alarmy.core.model.Alarm
import com.alarmy.core.mission.AlarmToneSynthesizer
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import java.io.File
import java.util.Locale
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
 * [SoundLibrary] entries are original PCM tones generated locally. A configured
 * backup, system ringtone, then emergency alarm-stream beeps provide recovery
 * if a player cannot open its source.
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
    private var rampSteps = 30
    private var speech: TextToSpeech? = null
    private var emergencyTone: ToneGenerator? = null
    private var emergencyLoop: Runnable? = null

    fun start(alarm: Alarm, escalated: Boolean) {
        stop()

        targetVolume = alarm.volume.coerceIn(0.05, 1.0).toFloat()
        rampSteps = alarm.rampSeconds.coerceIn(5, 120)

        // An escalating watchdog alert ignores the gentle ramp. The point of
        // escalation is that the gentle approach already failed.
        currentVolume = if (alarm.gentleRamp && !escalated) min(RAMP_START, targetVolume) else targetVolume

        val candidates = listOf(alarm.soundId, alarm.backupSoundId, SoundLibrary.DEFAULT_SOUND_ID, "system").distinct()
        for (id in candidates) {
            var candidate: MediaPlayer? = null
            try {
                val uri = resolveSound(id)
                val created = MediaPlayer()
                candidate = created
                created.apply {
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
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "Audio failed during playback: $what/$extra; using emergency tone")
                        startEmergencyTone()
                        true
                    }
                }
                player = candidate
                break
            } catch (e: java.io.IOException) {
                Log.e(TAG, "Could not play tone $id", e)
                candidate?.release()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Player could not start tone $id", e)
                candidate?.release()
            }
        }
        if (player == null) startEmergencyTone()

        if (alarm.gentleRamp && !escalated) scheduleRamp()
        if (alarm.vibrationEnabled) startVibration(escalated)
        if (alarm.speakLabel || alarm.speakTime) startSpeech(alarm)
    }

    private fun scheduleRamp() {
        val step = (targetVolume - currentVolume) / rampSteps
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
        emergencyLoop?.let { handler.removeCallbacks(it) }
        emergencyLoop = null
        emergencyTone?.release()
        emergencyTone = null
        speech?.stop()
        speech?.shutdown()
        speech = null
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
        if (soundId == "system") return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI
        val sound = SoundLibrary.soundFor(soundId)
        val tone = File(context.cacheDir, "${sound.id}-v1.wav")
        if (!tone.exists() || tone.length() != 96_044L) {
            tone.writeBytes(AlarmToneSynthesizer.wav(sound.id))
        }
        return Uri.fromFile(tone)
    }

    private fun startEmergencyTone() {
        if (emergencyTone != null) return
        Log.w(TAG, "Using synthesized alarm-stream emergency beeps")
        emergencyTone = ToneGenerator(AudioManager.STREAM_ALARM, 90)
        val loop = object : Runnable {
            override fun run() {
                emergencyTone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800)
                handler.postDelayed(this, 1_500)
            }
        }
        emergencyLoop = loop
        handler.post(loop)
    }

    private fun startSpeech(alarm: Alarm) {
        speech = TextToSpeech(context) { status ->
            val engine = speech ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "Speech unavailable; alarm tone continues")
                return@TextToSpeech
            }
            val voice = engine.voices?.firstOrNull {
                !it.isNetworkConnectionRequired && it.locale.language == Locale.getDefault().language
            }
            if (voice == null) {
                Log.w(TAG, "No offline voice installed; alarm tone continues")
                return@TextToSpeech
            }
            engine.voice = voice
            engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            val now = java.time.LocalTime.now()
            val text = listOfNotNull(
                alarm.name.takeIf { alarm.speakLabel },
                java.time.format.DateTimeFormatter.ofPattern("h:mm a").format(now).takeIf { alarm.speakTime }
            ).joinToString(". ")
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alarm-label")
        }
    }

    private companion object {
        const val TAG = "AlarmSoundPlayer"
        const val RAMP_START = 0.08f
        const val RAMP_INTERVAL_MS = 1_000L
        val PATTERN_NORMAL = longArrayOf(0, 500, 800)
        val PATTERN_URGENT = longArrayOf(0, 400, 200, 400, 200, 400, 900)
    }
}
