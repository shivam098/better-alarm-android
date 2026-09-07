package com.alarmy.app.ui.ringing

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import com.alarmy.app.AppGraph
import com.alarmy.app.alarm.RingingController
import com.alarmy.app.ui.theme.BetterAlarmTheme

/**
 * Host for the ringing UI, launched by the full-screen intent.
 *
 * This activity is the one place where the Android version can do something the
 * iOS version cannot: draw the *entire* alarm screen. On iOS, AlarmKit always
 * renders a Stop button we have no control over, and the mission can only be
 * offered after that button is pressed. Here nothing appears that we did not
 * put there, which is why Mission Guard is enforceable rather than advisory.
 *
 * The manifest sets `excludeFromRecents` and `taskAffinity=""` so this activity
 * never joins the main task; if it did, dismissing the app from Recents would
 * take the ringing screen with it.
 *
 * It deliberately does not implement `RingingHost` -- audio, the wake lock and
 * the notification belong to [com.alarmy.app.alarm.RingingService], which
 * outlives this activity. The activity is only a window onto the session.
 */
class RingingActivity : ComponentActivity() {

    /** True once the mission is completed, snoozed, or given up on. */
    private var resolved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        showOverLockScreen()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Back must not dismiss a guarded alarm. Swallowing the gesture is
        // honest here -- the ringing screen tells the user a mission is
        // required, so nothing is being hidden from them.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val guarded = RingingController.snapshot.value?.alarm?.isGuarded ?: false
                    if (!guarded) finish()
                }
            }
        )

        setContent {
            val snapshot by RingingController.snapshot.collectAsState()
            val settings by AppGraph.settings.settings.collectAsState()

            // A null snapshot means the session ended -- either resolved here,
            // or stopped from the notification while this screen was hidden.
            // Either way there is nothing left to show.
            LaunchedEffect(snapshot) {
                if (snapshot == null) {
                    resolved = true
                    finish()
                }
            }

            BetterAlarmTheme(darkTheme = true) {
                snapshot?.let { current ->
                    LaunchedEffect(current.occurrenceKey) {
                        RingingController.missionStarted()
                    }
                    RingingScreen(
                        snapshot = current,
                        is24Hour = settings.use24HourClock,
                        onMissionComplete = {
                            resolved = true
                            RingingController.missionCompleted()
                        },
                        onMissionFailed = { reason ->
                            RingingController.recordAttempt(reason.name)
                            RingingController.missionFailed()
                        },
                        onSnooze = {
                            resolved = true
                            RingingController.snoozeRequested()
                        }
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the screen with the mission unfinished is exactly the evasion
        // Mission Guard exists to catch. The controller decides what to do --
        // for an unguarded alarm, nothing.
        if (!resolved && !isFinishing) {
            RingingController.surfaceLost()
        }
    }

    /**
     * Makes the activity appear on top of the lock screen with the display on.
     *
     * The flag-based path is deprecated but still required below API 27, and an
     * alarm clock is precisely the app that must keep working on old phones.
     */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            // Only dismisses an *insecure* keyguard; a PIN or biometric lock is
            // never bypassed. The alarm screen draws above it instead.
            keyguard.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
