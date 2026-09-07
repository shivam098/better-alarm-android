package com.alarmy.app.data

import com.alarmy.core.schedule.MissedWindowRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

/**
 * App-wide preferences.
 *
 * [hasSeenReliabilityBriefing] is not a cosmetic flag. On Android an alarm app
 * that has not been exempted from battery optimisation will eventually fail to
 * ring, so the user is walked through the reliability checks once, on first
 * run, before they trust the app with a real alarm.
 */
@Serializable
data class AppSettings(
    val missedWindowRule: MissedWindowRule = MissedWindowRule.GRACE_PERIOD,
    val missedWindowGraceMinutes: Int = 15,
    val hasSeenReliabilityBriefing: Boolean = false,
    val hasSeenOemGuidance: Boolean = false,
    val use24HourClock: Boolean = false,
    val showNextAlarmNotification: Boolean = true
)

class AppSettingsStore(directory: File) {

    private val store = JsonStore(directory)

    private val _settings = MutableStateFlow(
        store.read(FILE, AppSettings.serializer(), AppSettings())
    )
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        store.write(FILE, AppSettings.serializer(), updated)
    }

    private companion object {
        const val FILE = "settings.json"
    }
}
