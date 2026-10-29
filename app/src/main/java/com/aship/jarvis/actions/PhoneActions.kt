package com.aship.jarvis.actions

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings

/** Outcome of one activity launch attempted for the background service. */
enum class LaunchOutcome {
    /** The launch intent was actually dispatched. */
    LAUNCHED,

    /** Target missing (app not installed, no handler): nothing to launch. */
    NOT_AVAILABLE,

    /** "Display over other apps" permission missing: Android blocks the launch. */
    PERMISSION_NEEDED,

    /** The launch was attempted but threw / was refused. */
    FAILED
}

/**
 * Small, permission-safe actions Jarvis can perform on the owner's phone.
 * Every function returns false instead of crashing when something is
 * unavailable (app not installed, no flash hardware, no clock app, ...).
 */
object PhoneActions {

    /** Opens another app by package name, e.g. YouTube / Facebook / WhatsApp. */
    fun openApp(context: Context, packageName: String): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launchIntent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Opens the camera in still-photo capture mode. */
    fun openCamera(context: Context): Boolean {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Opens the Android Settings home screen. */
    fun openSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Turns the camera flash (torch) on or off. Uses the first camera whose
     * characteristics report a flash unit. No CAMERA permission is needed
     * for torch mode on Android 6+.
     */
    fun setTorch(context: Context, on: Boolean): Boolean {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return false
        return try {
            val torchCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cameraManager.setTorchMode(torchCameraId, on)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Opens the clock app's alarm screen. When [hour]/[minute] are given
     * they are pre-filled so the user only has to confirm.
     */
    fun setAlarm(context: Context, hour: Int?, minute: Int?): Boolean {
        return try {
            context.startActivity(buildAlarmIntent(hour, minute))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun buildAlarmIntent(hour: Int?, minute: Int?): Intent =
        Intent(AlarmClock.ACTION_SET_ALARM).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (hour != null) {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute ?: 0)
            }
            putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis alarm")
        }

    // ------------------------------------------------------------------
    // Background (wake service) variants.
    //
    // Android 10+ silently swallows startActivity() from a background
    // service, so these route through OverlayLauncher and report the
    // truth: PERMISSION_NEEDED when "Display over other apps" has not
    // been granted, instead of letting Jarvis claim the app opened.
    // The torch does NOT need any of this (CameraManager, no activity).
    // ------------------------------------------------------------------

    /** Background variant of [openApp]. */
    fun openAppFromBackground(context: Context, packageName: String): LaunchOutcome {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return LaunchOutcome.NOT_AVAILABLE
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return OverlayLauncher.launch(context, launchIntent)
    }

    /** Background variant of [openCamera]. */
    fun openCameraFromBackground(context: Context): LaunchOutcome {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return OverlayLauncher.launch(context, intent)
    }

    /** Background variant of [openSettings]. */
    fun openSettingsFromBackground(context: Context): LaunchOutcome {
        val intent = Intent(Settings.ACTION_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return OverlayLauncher.launch(context, intent)
    }

    /** Background variant of [setAlarm]. */
    fun setAlarmFromBackground(context: Context, hour: Int?, minute: Int?): LaunchOutcome =
        OverlayLauncher.launch(context, buildAlarmIntent(hour, minute))
}
