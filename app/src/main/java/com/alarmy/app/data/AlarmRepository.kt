package com.alarmy.app.data

import com.alarmy.core.model.Alarm
import com.alarmy.core.model.AlarmOccurrence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * Single source of truth for alarms, history, and the set of alarm keys we
 * believe are currently registered with `AlarmManager`.
 *
 * Everything is held in memory and mirrored to disk on write. The data set is
 * small (an alarm list a user can scroll, plus a capped history), and an alarm
 * clock cannot afford to wait on I/O when the user taps "stop" at 6am.
 */
class AlarmRepository(directory: File) {

    private val store = JsonStore(directory)

    private val _alarms = MutableStateFlow(
        store.read(FILE_ALARMS, ListSerializer(Alarm.serializer()), emptyList())
    )
    val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()

    private val _occurrences = MutableStateFlow(
        store.read(FILE_OCCURRENCES, ListSerializer(AlarmOccurrence.serializer()), emptyList())
    )
    val occurrences: StateFlow<List<AlarmOccurrence>> = _occurrences.asStateFlow()

    /**
     * Occurrence keys we have handed to `AlarmManager`.
     *
     * This exists because `AlarmManager` has no "list my alarms" API. Without
     * our own record, an alarm scheduled before an app upgrade could never be
     * cancelled -- we would not know its request code. It is deliberately
     * excluded from cloud backup: restoring it onto a new device would describe
     * registrations that do not exist there.
     */
    private var pendingKeys: MutableSet<String> =
        store.read(FILE_PENDING, SetSerializer(String.serializer()), emptySet()).toMutableSet()

    fun alarm(id: String): Alarm? = _alarms.value.firstOrNull { it.id == id }

    fun upsert(alarm: Alarm) {
        val updated = alarm.copy(updatedAtEpochMillis = System.currentTimeMillis())
        val list = _alarms.value.toMutableList()
        val index = list.indexOfFirst { it.id == updated.id }
        if (index >= 0) list[index] = updated else list.add(updated)
        list.sortWith(compareBy({ it.hour }, { it.minute }, { it.name }))
        _alarms.value = list
        store.write(FILE_ALARMS, ListSerializer(Alarm.serializer()), list)
    }

    fun delete(id: String) {
        val list = _alarms.value.filterNot { it.id == id }
        _alarms.value = list
        store.write(FILE_ALARMS, ListSerializer(Alarm.serializer()), list)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        alarm(id)?.let { upsert(it.copy(isEnabled = enabled)) }
    }

    fun recordOccurrence(occurrence: AlarmOccurrence) {
        val list = _occurrences.value.toMutableList()
        val index = list.indexOfFirst { it.id == occurrence.id }
        if (index >= 0) list[index] = occurrence else list.add(occurrence)
        // Keep history bounded. Statistics only look back far enough for the
        // streak calculation, so an unbounded log would grow forever for nothing.
        val trimmed = list
            .sortedByDescending { it.scheduledAtEpochMillis }
            .take(MAX_HISTORY)
        _occurrences.value = trimmed
        store.write(FILE_OCCURRENCES, ListSerializer(AlarmOccurrence.serializer()), trimmed)
    }

    fun occurrence(key: String): AlarmOccurrence? =
        _occurrences.value.firstOrNull { it.occurrenceKey == key }

    fun clearHistory() {
        _occurrences.value = emptyList()
        store.write(FILE_OCCURRENCES, ListSerializer(AlarmOccurrence.serializer()), emptyList())
    }

    @Synchronized
    fun rememberPending(keys: Collection<String>) {
        pendingKeys.addAll(keys)
        persistPending()
    }

    @Synchronized
    fun forgetPending(keys: Collection<String>) {
        pendingKeys.removeAll(keys.toSet())
        persistPending()
    }

    @Synchronized
    fun pending(): Set<String> = pendingKeys.toSet()

    @Synchronized
    fun clearPending() {
        pendingKeys = mutableSetOf()
        persistPending()
    }

    private fun persistPending() {
        store.write(FILE_PENDING, SetSerializer(String.serializer()), pendingKeys)
    }

    private companion object {
        const val FILE_ALARMS = "alarms.json"
        const val FILE_OCCURRENCES = "occurrences.json"
        const val FILE_PENDING = "pending.json"
        const val MAX_HISTORY = 500
    }
}
