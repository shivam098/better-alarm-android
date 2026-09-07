package com.alarmy.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alarmy.app.AppGraph
import com.alarmy.app.data.AppSettings
import com.alarmy.app.sensor.DeviceCapabilities
import com.alarmy.core.model.Alarm
import com.alarmy.core.model.AlarmOccurrence
import com.alarmy.core.model.MissionType
import com.alarmy.core.schedule.AlarmScheduleCalculator
import com.alarmy.core.state.WakeStatistics
import com.alarmy.core.state.WakeStatisticsCalculator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant

data class AlarmRow(
    val alarm: Alarm,
    val nextFire: Instant?
)

class AlarmsViewModel : ViewModel() {

    private val repository = AppGraph.repository
    private val scheduler = AppGraph.scheduler
    private val settingsStore = AppGraph.settings

    val settings: StateFlow<AppSettings> = settingsStore.settings

    val rows: StateFlow<List<AlarmRow>> = repository.alarms
        .map { alarms ->
            val calculator = AlarmScheduleCalculator(AppGraph.timeSource.zone())
            val now = AppGraph.timeSource.now()
            alarms.map { alarm ->
                AlarmRow(
                    alarm = alarm,
                    nextFire = if (alarm.isEnabled) calculator.nextFireDate(alarm, now) else null
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val occurrences: StateFlow<List<AlarmOccurrence>> = repository.occurrences

    val statistics: StateFlow<WakeStatistics> = repository.occurrences
        .map { list ->
            WakeStatisticsCalculator(AppGraph.timeSource.zone())
                .compute(list, AppGraph.timeSource.now())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WakeStatistics())

    /** The next alarm across all enabled alarms, shown at the top of the list. */
    val nextAlarm: StateFlow<AlarmRow?> = rows
        .map { list -> list.filter { it.nextFire != null }.minByOrNull { it.nextFire!! } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun availableMissions(): List<MissionType> =
        DeviceCapabilities(AppGraph.appContext).availableMissions()

    fun alarm(id: String?): Alarm? = id?.let { repository.alarm(it) }

    fun save(alarm: Alarm) {
        repository.upsert(alarm.copy(mission = alarm.mission.normalized()))
        scheduler.rescheduleAll()
    }

    fun delete(id: String) {
        scheduler.cancelChain(id)
        repository.delete(id)
        scheduler.rescheduleAll()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        repository.setEnabled(id, enabled)
        scheduler.rescheduleAll()
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsStore.update(transform)

    fun clearHistory() = repository.clearHistory()

    fun refreshSchedule() = scheduler.rescheduleAll()
}
