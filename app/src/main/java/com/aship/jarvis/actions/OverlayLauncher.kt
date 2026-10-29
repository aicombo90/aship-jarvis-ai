package com.aship.jarvis.actions

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 2 fix: activity launches on behalf of the background wake
 * service.
 *
 * Android 10+ blocks startActivity() from background contexts
 * (Background Activity Launch restrictions) — silently: the call
 * returns normally but nothing opens. That is exactly what users
 * saw: Jarvis said "YouTube khol diya hai" while YouTube never
 * opened.
 *
 * One legitimate exemption is holding a visible window. With the
 * user's "Display over other apps" permission we briefly show a
 * 1x1 px, transparent, non-focusable, non-touchable overlay window,
 * fire the startActivity while it is up, and remove the overlay
 * ~600 ms later. Nothing visible ever appears on screen.
 *
 * Without the permission we do NOT pretend: [launch] returns
 * [LaunchOutcome.PERMISSION_NEEDED] so the caller can say so.
 */
object OverlayLauncher {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** True when the user has granted "Display over other apps". */
    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /**
     * Launches [intent] using the overlay bridge. Safe to call from
     * any thread: the window work is posted to the main thread and
     * the caller waits briefly for the outcome, so the result is
     * truthful — LAUNCHED means the intent really was dispatched.
     */
    fun launch(context: Context, intent: Intent): LaunchOutcome {
        if (!canDrawOverlays(context)) {
            return LaunchOutcome.PERMISSION_NEEDED
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return launchWithOverlay(context.applicationContext, intent)
        }
        val latch = CountDownLatch(1)
        var outcome = LaunchOutcome.FAILED
        mainHandler.post {
            outcome = launchWithOverlay(context.applicationContext, intent)
            latch.countDown()
        }
        return try {
            if (latch.await(LAUNCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                outcome
            } else {
                LaunchOutcome.FAILED
            }
        } catch (e: InterruptedException) {
            LaunchOutcome.FAILED
        }
    }

    /** WindowManager work: main thread only. */
    private fun launchWithOverlay(context: Context, intent: Intent): LaunchOutcome {
        val windowManager =
            context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: return LaunchOutcome.FAILED
        val overlay = View(context)
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            windowManager.addView(overlay, params)
        } catch (e: Exception) {
            // Permission revoked between the check and here, or the
            // window was refused: an honest failure, not a launch.
            return LaunchOutcome.FAILED
        }
        val outcome = try {
            context.startActivity(intent)
            LaunchOutcome.LAUNCHED
        } catch (e: Exception) {
            LaunchOutcome.FAILED
        }
        // The overlay only has to exist for the launch moment; take
        // it down shortly after so nothing invisible lingers.
        mainHandler.postDelayed({
            try {
                windowManager.removeView(overlay)
            } catch (e: Exception) {
                // Already removed: nothing to do.
            }
        }, OVERLAY_LINGER_MS)
        return outcome
    }

    private const val LAUNCH_TIMEOUT_MS = 3_000L
    private const val OVERLAY_LINGER_MS = 600L
}
