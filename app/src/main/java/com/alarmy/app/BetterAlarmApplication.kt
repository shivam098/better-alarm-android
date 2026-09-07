package com.alarmy.app

import android.app.Application
import com.alarmy.app.alarm.AlarmNotifications

class BetterAlarmApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppGraph.install(this)
        AlarmNotifications.createChannels(this)

        // Re-arm on every cold start.
        //
        // This is belt-and-braces against the failure BootReceiver cannot see:
        // a force-stop from the app-info screen cancels every AlarmManager
        // registration *and* prevents the app receiving any broadcast until it
        // is next launched manually. Rebuilding here means opening the app is
        // always enough to repair the schedule.
        runCatching { AppGraph.scheduler.rescheduleAll() }
    }
}
