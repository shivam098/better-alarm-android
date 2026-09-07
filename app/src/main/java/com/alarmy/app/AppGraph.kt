package com.alarmy.app

import android.content.Context
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

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        appContext = context.applicationContext
        repository = AlarmRepository(appContext.filesDir)
        scheduler = AlarmScheduler(appContext, repository, timeSource)
        settings = AppSettingsStore(appContext.filesDir)
        targets = MissionTargetStore(appContext.filesDir)
        reliability = ReliabilityChecker(appContext, scheduler)
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
        if (!installed) install(context)
    }
}
