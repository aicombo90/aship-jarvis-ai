package com.aship.jarvis.actions

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings

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
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (hour != null) {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute ?: 0)
            }
            putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis alarm")
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
