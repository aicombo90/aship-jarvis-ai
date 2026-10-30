package com.aship.jarvis.actions

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent

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

    /**
     * Opens another app by package name, e.g. YouTube / Facebook /
     * WhatsApp. Kuch apps ke ek se zyada package naam hote hain
     * (Facebook ka main app / Lite, WhatsApp ka aam / Business) —
     * [candidatePackages] ki kram se pehla jo phone me mile aur
     * khul jaye, wahi chalega.
     */
    fun openApp(context: Context, packageName: String): Boolean {
        for (candidate in candidatePackages(packageName)) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(candidate)
                ?: continue
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(launchIntent)
                return true
            } catch (e: Exception) {
                // Is package se launch nahi ho paya — agla candidate try karo.
            }
        }
        return false
    }

    /**
     * Ek app ke mumkin package naam, pasand ke kram me: Facebook ke
     * liye pehle main app (katana), na mile to Lite; WhatsApp ke liye
     * pehle aam wala, na mile to Business. Baaki apps ke liye sirf
     * diya gaya package naam.
     */
    private fun candidatePackages(packageName: String): List<String> =
        PACKAGE_FALLBACKS[packageName] ?: listOf(packageName)

    private val PACKAGE_FALLBACKS: Map<String, List<String>> = mapOf(
        "com.facebook.katana" to listOf("com.facebook.katana", "com.facebook.lite"),
        "com.whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b")
    )

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
    // v1.4 advanced pack.
    //
    // Battery / volume / media keys ko koi activity nahi chahiye —
    // ye system services se seedha hote hain, isliye background wake
    // service se bhi bina overlay bridge ke sach me chalte hain.
    // URL kholna aur timer lagana activity launch hai — unke liye
    // neeche background (overlay) variants bhi hain.
    // ------------------------------------------------------------------

    /** Launcher me dikhne wali ek app: naam (label) + package. */
    data class LauncherApp(
        val label: String,
        val packageName: String
    )

    /**
     * Phone ki saari launcher apps ki list (ek package ek hi baar).
     * Android 11+ par iske liye manifest me MAIN/LAUNCHER wala
     * <queries> block rakha hai — bina uske list aadhi milegi.
     */
    fun listLaunchableApps(context: Context): List<LauncherApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            pm.queryIntentActivities(intent, 0)
        } catch (e: Exception) {
            return emptyList()
        }
        val apps = LinkedHashMap<String, LauncherApp>()
        for (info in resolved) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (apps.containsKey(pkg)) continue
            val label = try {
                info.loadLabel(pm).toString()
            } catch (e: Exception) {
                pkg
            }
            apps[pkg] = LauncherApp(label, pkg)
        }
        return apps.values.toList()
    }

    /**
     * Battery ka haal: (percent, charging ho rahi hai kya).
     * Sticky ACTION_BATTERY_CHANGED broadcast se milta hai —
     * koi permission nahi chahiye. Jaankari na mile to null.
     */
    fun batteryStatus(context: Context): Pair<Int, Boolean>? {
        val intent = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) {
            null
        } ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return (level * 100 / scale) to charging
    }

    private fun audioManager(context: Context): AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** Music volume abhi kitne percent par hai; na mile to -1. */
    fun musicVolumePercent(context: Context): Int {
        val am = audioManager(context) ?: return -1
        return try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max <= 0) {
                -1
            } else {
                am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
            }
        } catch (e: Exception) {
            -1
        }
    }

    /** Ek step volume upar. Flags 0 = screen par volume UI nahi dikhega. */
    fun raiseMusicVolume(context: Context): Boolean {
        val am = audioManager(context) ?: return false
        return try {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Ek step volume neeche. */
    fun lowerMusicVolume(context: Context): Boolean {
        val am = audioManager(context) ?: return false
        return try {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Volume sabse upar (max). */
    fun maximizeMusicVolume(context: Context): Boolean {
        val am = audioManager(context) ?: return false
        return try {
            am.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                0
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Volume zero (mute ki tarah). */
    fun muteMusicVolume(context: Context): Boolean {
        val am = audioManager(context) ?: return false
        return try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Media button dabana (play / pause / next / previous) — jo bhi
     * music app chal rahi hai use signal jata hai. Down + Up dono
     * bhejte hain, asli button dabane ki tarah.
     */
    private fun dispatchMediaKey(context: Context, keyCode: Int): Boolean {
        val am = audioManager(context) ?: return false
        val now = SystemClock.uptimeMillis()
        return try {
            am.dispatchMediaKeyEvent(
                KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            )
            am.dispatchMediaKeyEvent(
                KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    fun mediaPlay(context: Context): Boolean =
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)

    fun mediaPause(context: Context): Boolean =
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE)

    fun mediaNext(context: Context): Boolean =
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)

    fun mediaPrevious(context: Context): Boolean =
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    /** Ek URL default browser (ya us link ki app, jaise YouTube) me kholo. */
    fun openUrl(context: Context, url: String): Boolean {
        return try {
            context.startActivity(buildViewIntent(url))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun buildViewIntent(url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Clock app me timer lagao. EXTRA_SKIP_UI true hai — jahan
     * support hoga timer bina screen dikhaye lag jayega; jahan
     * nahi hoga, clock app ka timer screen khul jayega.
     * ACTION_SET_TIMER ke liye koi permission nahi chahiye.
     */
    fun setTimer(context: Context, totalSeconds: Int): Boolean {
        return try {
            context.startActivity(buildTimerIntent(totalSeconds))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun buildTimerIntent(totalSeconds: Int): Intent =
        Intent(AlarmClock.ACTION_SET_TIMER).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(AlarmClock.EXTRA_LENGTH, totalSeconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis timer")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
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

    /** Background variant of [openApp] — wahi package fallback kram. */
    fun openAppFromBackground(context: Context, packageName: String): LaunchOutcome {
        val launchIntent = candidatePackages(packageName).firstNotNullOfOrNull { candidate ->
            context.packageManager.getLaunchIntentForPackage(candidate)
        } ?: return LaunchOutcome.NOT_AVAILABLE
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

    /** Background variant of [setTimer] (v1.4). */
    fun setTimerFromBackground(context: Context, totalSeconds: Int): LaunchOutcome =
        OverlayLauncher.launch(context, buildTimerIntent(totalSeconds))

    /**
     * Background variant of [openUrl] (v1.4) — Google / YouTube
     * search ke liye. Pehle ye bhi dekh lete hain ki link kholne
     * wala koi hai bhi ya nahi, taaki "nahi mila" sach bol sakein.
     */
    fun openUrlFromBackground(context: Context, url: String): LaunchOutcome {
        val intent = buildViewIntent(url)
        val hasHandler = try {
            intent.resolveActivity(context.packageManager) != null
        } catch (e: Exception) {
            false
        }
        if (!hasHandler) return LaunchOutcome.NOT_AVAILABLE
        return OverlayLauncher.launch(context, intent)
    }
}
