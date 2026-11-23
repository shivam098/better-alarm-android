package com.alarmy.app.ui.mission

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.alarmy.app.AppGraph
import com.alarmy.app.camera.BitmapHashing
import com.alarmy.app.sensor.DeviceCapabilities
import com.alarmy.core.model.MissionConfig
import com.alarmy.core.model.MissionPermission
import com.alarmy.core.model.MissionType
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun MissionSetup(config: MissionConfig, onChange: (MissionConfig) -> Unit) {
    val context = LocalContext.current
    var message by remember(config.type) { mutableStateOf<String?>(null) }
    var permissionRevision by remember { mutableStateOf(0) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionRevision++
        message = if (granted) "Permission granted. You can now register or run this mission."
        else if (config.type == MissionType.STEPS) {
            "Permission denied. Walking will wait for permission; grant Physical activity in app settings."
        } else "Permission denied. Grant it in app settings or this alarm will use a fallback mission."
    }
    val capabilities = remember(permissionRevision) { DeviceCapabilities(context) }
    config.type.requiredPermissions.forEach { permission ->
        if (permission in capabilities.unavailablePermissions()) {
            Text(permission.rationale)
            capabilities.androidPermissionFor(permission)?.let { androidPermission ->
                TextButton(onClick = { permissions.launch(androidPermission) }) {
                    Text("Allow ${permission.displayName}")
                }
            }
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}")))
            }) { Text("Open app permission settings") }
        }
    }
    if (config.type == MissionType.TYPING) {
        OutlinedTextField(
            value = config.customPhrase,
            onValueChange = { onChange(config.copy(customPhrase = it.take(240))) },
            label = { Text("Custom sentence (optional)") },
            supportingText = { Text("Leave blank for built-in phrases. Custom sentences match exact case.") }
        )
    }
    if (config.type != MissionType.PHOTO && config.type != MissionType.QR) {
        message?.let { Text(it) }
        return
    }

    var label by remember { mutableStateOf("My target") }
    OutlinedTextField(value = label, onValueChange = { label = it.take(80) }, label = { Text("Target name") })
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap == null) message = "No photo taken; target unchanged."
        else {
            val target = AppGraph.targets.addPhoto(label.ifBlank { "My target" }, BitmapHashing.fingerprint(bitmap))
            onChange(config.copy(referenceAssetIds = listOf(target.id)))
            message = "Photo target saved. Choose a distinctive place, not a blank wall."
        }
    }
    val code = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents.isNullOrBlank()) message = "Nothing scanned; target unchanged."
        else {
            val target = AppGraph.targets.addCode(label.ifBlank { "My target" }, result.contents)
            onChange(config.copy(referenceAssetIds = listOf(target.id)))
            message = "Code target saved."
        }
    }
    val id = config.referenceAssetIds.firstOrNull()
    val registered = if (config.type == MissionType.PHOTO) id?.let { AppGraph.targets.photo(it)?.label }
    else id?.let { AppGraph.targets.code(it)?.label }
    Text("Registered target: ${registered ?: "None - register before saving"}")
    TextButton(onClick = {
        if (!DeviceCapabilities(context).cameraGranted) {
            permissions.launch(android.Manifest.permission.CAMERA)
        } else {
            try {
                if (config.type == MissionType.PHOTO) photo.launch(null)
                else code.launch(ScanOptions().setBeepEnabled(false).setOrientationLocked(false))
            } catch (e: android.content.ActivityNotFoundException) {
                Log.w("MissionSetup", "Camera activity unavailable", e)
                message = "No camera application is available. Choose a different mission."
            } catch (e: SecurityException) {
                Log.w("MissionSetup", "Camera permission changed", e)
                message = "Camera permission is unavailable. Grant it in app settings."
            }
        }
    }) { Text(if (config.type == MissionType.PHOTO) "Register photo" else "Register QR / barcode") }
    message?.let { Text(it) }
}
