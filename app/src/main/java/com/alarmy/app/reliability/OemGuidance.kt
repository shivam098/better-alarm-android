package com.alarmy.app.reliability

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Manufacturer-specific instructions for keeping an alarm alive.
 *
 * ## Why this file exists
 *
 * Several Android manufacturers ship aggressive background process management
 * that goes well beyond stock Doze, and it is the leading cause of alarm apps
 * failing on those devices. Crucially, **there is no API to detect any of it**:
 * an app cannot ask whether it is on Xiaomi's autostart list or in Huawei's
 * protected apps. The only honest response is to tell the user what to check.
 *
 * The activity names below are internal to the OEM system apps and are not
 * stable across versions, so every launch is wrapped in a resolve check and the
 * written steps are always shown regardless of whether the intent resolves.
 *
 * Reference for the underlying behaviours: https://dontkillmyapp.com
 */
data class OemGuidance(
    val manufacturer: String,
    val explanation: String,
    val steps: List<String>,
    private val candidateComponents: List<Pair<String, String>> = emptyList()
) {

    /** Returns an intent only if the OEM settings screen actually exists here. */
    fun settingsIntent(context: Context): Intent? {
        for ((pkg, cls) in candidateComponents) {
            val intent = Intent().setComponent(ComponentName(pkg, cls))
            val resolved = context.packageManager.resolveActivity(intent, 0)
            if (resolved != null) return intent
        }
        return null
    }

    companion object {

        fun forCurrentDevice(): OemGuidance? {
            val make = Build.MANUFACTURER.lowercase()
            val brand = Build.BRAND.lowercase()
            return when {
                make.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> XIAOMI
                make.contains("huawei") || brand.contains("honor") -> HUAWEI
                make.contains("samsung") -> SAMSUNG
                make.contains("oppo") || brand.contains("realme") -> OPPO
                make.contains("vivo") || brand.contains("iqoo") -> VIVO
                make.contains("oneplus") -> ONEPLUS
                make.contains("asus") -> ASUS
                else -> null
            }
        }

        private val XIAOMI = OemGuidance(
            manufacturer = "Xiaomi",
            explanation = "MIUI and HyperOS stop apps from starting in the background " +
                "unless Autostart is switched on, and will close apps that are not " +
                "locked in the recents list.",
            steps = listOf(
                "Settings › Apps › Manage apps › Better Alarm › enable Autostart",
                "In the same screen, set Battery saver to \"No restrictions\"",
                "Open recents, swipe down on Better Alarm and tap the padlock",
                "Settings › Apps › Permissions › Other permissions › allow \"Display pop-up windows while running in the background\""
            ),
            candidateComponents = listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        )

        private val HUAWEI = OemGuidance(
            manufacturer = "Huawei",
            explanation = "EMUI closes apps that are not in the protected list whenever " +
                "the screen turns off.",
            steps = listOf(
                "Settings › Battery › App launch › Better Alarm › switch to Manage manually",
                "Enable all three: Auto-launch, Secondary launch and Run in background",
                "Settings › Battery › More battery settings › turn off \"Close apps after screen lock\""
            ),
            candidateComponents = listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )
        )

        private val SAMSUNG = OemGuidance(
            manufacturer = "Samsung",
            explanation = "One UI puts apps it considers unused to sleep, which stops " +
                "them rescheduling repeating alarms.",
            steps = listOf(
                "Settings › Battery › Background usage limits",
                "Make sure Better Alarm is NOT in \"Sleeping apps\" or \"Deep sleeping apps\"",
                "Turn off \"Put unused apps to sleep\"",
                "Settings › Apps › Better Alarm › Battery › choose Unrestricted"
            ),
            candidateComponents = listOf(
                "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity"
            )
        )

        private val OPPO = OemGuidance(
            manufacturer = "OPPO",
            explanation = "ColorOS freezes background apps and blocks auto-start by default.",
            steps = listOf(
                "Settings › Battery › More settings › turn off Sleep standby optimisation",
                "Settings › Apps › Auto-launch › enable Better Alarm",
                "Settings › Apps › Better Alarm › Battery usage › Allow background activity"
            ),
            candidateComponents = listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            )
        )

        private val VIVO = OemGuidance(
            manufacturer = "vivo",
            explanation = "Funtouch OS and OriginOS block background running and " +
                "high-power consumption apps by default.",
            steps = listOf(
                "Settings › Battery › Background power consumption management › allow Better Alarm",
                "Settings › Apps › Autostart › enable Better Alarm",
                "i Manager › App manager › Autostart manager › enable Better Alarm"
            ),
            candidateComponents = listOf(
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
        )

        private val ONEPLUS = OemGuidance(
            manufacturer = "OnePlus",
            explanation = "OxygenOS applies advanced optimisation that can suspend an " +
                "alarm app overnight.",
            steps = listOf(
                "Settings › Battery › Battery optimisation › Better Alarm › Don't optimise",
                "Settings › Battery › More › turn off Advanced optimisation / Deep optimisation",
                "Lock Better Alarm in the recents list"
            )
        )

        private val ASUS = OemGuidance(
            manufacturer = "ASUS",
            explanation = "ZenUI's Auto-start Manager blocks background launches.",
            steps = listOf(
                "Open Mobile Manager › PowerMaster › Auto-start Manager › allow Better Alarm",
                "Settings › Battery › disable PowerMaster restrictions for Better Alarm"
            ),
            candidateComponents = listOf(
                "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity"
            )
        )
    }
}
