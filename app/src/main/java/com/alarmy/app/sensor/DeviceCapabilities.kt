package com.alarmy.app.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.alarmy.core.mission.MissionLibrary
import com.alarmy.core.model.MissionPermission
import com.alarmy.core.model.MissionType

/**
 * What this particular device and permission set can actually do.
 *
 * The core library models mission availability abstractly; this is the one
 * place that asks Android. Keeping it separate means the fallback logic stays
 * unit-testable and the Android answers stay in a class small enough to read.
 */
class DeviceCapabilities(private val context: Context) {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    val hasAccelerometer: Boolean
        get() = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    /**
     * A hardware step counter, not the accelerometer.
     *
     * Deriving steps from raw acceleration is possible but drains the battery
     * and is easy to fool by waving the phone; the dedicated sensor is
     * low-power and is what `StepValidator`'s cadence thresholds were tuned
     * against.
     */
    val hasStepCounter: Boolean
        get() = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null

    val hasCamera: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    val cameraGranted: Boolean
        get() = granted(Manifest.permission.CAMERA)

    /**
     * `ACTIVITY_RECOGNITION` only became a runtime permission in Android 10.
     * Below that the step detector is readable without asking.
     */
    val activityRecognitionGranted: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            granted(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            true
        }

    fun availableMissions(): List<MissionType> = MissionLibrary.available(
        hasAccelerometer = hasAccelerometer,
        hasStepCounter = hasStepCounter,
        hasCamera = hasCamera,
        activityRecognitionGranted = activityRecognitionGranted,
        cameraGranted = cameraGranted
    )

    /** Permissions the user has not granted, used to pick a fallback mission. */
    fun unavailablePermissions(): Set<MissionPermission> = buildSet {
        if (!cameraGranted || !hasCamera) add(MissionPermission.CAMERA)
        if (!activityRecognitionGranted || !hasStepCounter) add(MissionPermission.ACTIVITY_RECOGNITION)
        if (!hasAccelerometer) add(MissionPermission.MOTION)
    }

    fun androidPermissionFor(permission: MissionPermission): String? = when (permission) {
        MissionPermission.CAMERA -> Manifest.permission.CAMERA
        MissionPermission.ACTIVITY_RECOGNITION ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Manifest.permission.ACTIVITY_RECOGNITION
            } else {
                null
            }
        // Motion sensors need no permission on Android. Modelled anyway so a
        // device without the hardware takes the same fallback path.
        MissionPermission.MOTION -> null
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
