package com.alarmy.app

import android.content.Context
import android.os.UserManager
import java.io.File
import com.alarmy.app.alarm.AlarmScheduler
import com.alarmy.app.data.AlarmRepository
import com.alarmy.app.data.AppSettingsStore
import com.alarmy.app.data.MissionTargetStore
import com.alarmy.app.reliability.ReliabilityChecker
import com.alarmy.core.support.SystemTimeSource
import com.alarmy.core.support.TimeSource

/**
 * Hand-rolled dependency container.
 *
 * There are six collaborators and they are all process-scoped singletons, which
 * is under the threshold where a DI framework starts paying for itself. Hilt
 * would pull in KSP and lengthen every build; more to the point, broadcast
 * receivers and services are constructed by the system, so they would each need
 * an entry point anyway.
 *
 * [install] is called from [BetterAlarmApplication.onCreate], which the system
 * guarantees runs before any receiver, service or activity in the process.
 */
object AppGraph {

    lateinit var appContext: Context
        private set

    lateinit var repository: AlarmRepository
        private set

    lateinit var scheduler: AlarmScheduler
        private set

    lateinit var settings: AppSettingsStore
        private set

    lateinit var targets: MissionTargetStore
        private set

    lateinit var reliability: ReliabilityChecker
        private set

    val timeSource: TimeSource = SystemTimeSource()

    private var installed = false
    private var storageUnlocked = false

    @Synchronized
    fun install(context: Context) {
        val unlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
        if (installed && appContext === context.applicationContext && (storageUnlocked || !unlocked)) return
        appContext = context.applicationContext
        val storage = appContext.createDeviceProtectedStorageContext()
        if (unlocked) {
            listOf("alarms.json", "occurrences.json", "pending.json", "deliveries.json", "settings.json", "targets.json").forEach { name ->
                val old = File(appContext.filesDir, name)
                val target = File(storage.filesDir, name)
                if (old.exists() && !target.exists()) {
                    val temp = File(storage.filesDir, "$name.migrate")
                    old.copyTo(temp, overwrite = true)
                    check(temp.renameTo(target)) { "Could not migrate $name to direct-boot storage" }
                    check(old.delete()) { "Could not remove migrated $name" }
                }
            }
        }
        repository = AlarmRepository(storage.filesDir)
        scheduler = AlarmScheduler(appContext, repository, timeSource,
            settingsProvider = { AppGraph.settings.settings.value })
        settings = AppSettingsStore(storage.filesDir)
        targets = MissionTargetStore(storage.filesDir)
        reliability = ReliabilityChecker(appContext, scheduler)
        storageUnlocked = unlocked
        installed = true
    }

    /**
     * Safety net for the direct-boot path.
     *
     * A receiver marked `directBootAware` can run before the Application object
     * has been created in some OEM implementations. Calling this first costs
     * nothing when the graph is already installed.
     */
    fun ensureInstalled(context: Context) {
        install(context)
    }
}
