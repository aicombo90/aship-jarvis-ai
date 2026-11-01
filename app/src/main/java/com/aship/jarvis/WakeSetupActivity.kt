package com.aship.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.aship.jarvis.access.JarvisAccessibilityService
import com.aship.jarvis.prefs.AppPrefs
import com.aship.jarvis.service.JarvisWakeService
import com.aship.jarvis.voice.ModelLanguage
import com.aship.jarvis.voice.ModelManager

/**
 * v34: background bandobosto wali screenon ka saajha aadhaar.
 *
 * v33 tak wake-word / battery / accessibility / overlay ke
 * controls MainActivity ke neeche bikhre hue the. User ke
 * kehne par ve ab "Mobile" hub ke andar hain — vivo aur
 * Samsung ki apni-apni screenon par. Dono screenon ke controls
 * EK hi vyavhaar rakhte hain, isliye woh vyavhaar yahan EK
 * jagah hai (MainActivity se hila hua, bilkul waisa hi) —
 * do jagah alag-alag badlav se bhool nahi hogi.
 *
 * Layout me ye IDs hone chahiye (Samsung par batteryButton
 * nahi hai — wahan uski jagah Samsung wala khaas hissa hai):
 * wakeStatusText, wakeStartButton, wakeStopButton,
 * accessibilityStatusText, accessibilityButton,
 * overlayPermissionButton, batteryButton (sirf vivo).
 */
abstract class WakeSetupActivity : Activity() {

    protected lateinit var prefs: AppPrefs
    protected lateinit var modelManager: ModelManager

    private var wakeStatusText: TextView? = null
    private var wakeStartButton: Button? = null
    private var wakeStopButton: Button? = null
    private var accessibilityStatusText: TextView? = null

    private var pendingWakeStart = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // MainActivity ki tarah: screen dikh rahi ho tab tak har
    // 2 s me status lines taza karo — Settings se wapas aate
    // hi (aur service chalu/band hote hi) haalat khud badle.
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshSetupUi()
            mainHandler.postDelayed(this, REFRESH_MS)
        }
    }

    protected fun initSetup() {
        prefs = AppPrefs(this)
        modelManager = ModelManager(this)
    }

    /** Saajhe controls ko dhoondh kar unke listener bandho. */
    protected fun bindSetupControls() {
        wakeStatusText = findViewById(R.id.wakeStatusText)
        wakeStartButton = findViewById(R.id.wakeStartButton)
        wakeStopButton = findViewById(R.id.wakeStopButton)
        accessibilityStatusText = findViewById(R.id.accessibilityStatusText)

        wakeStartButton?.setOnClickListener { onWakeStartClicked() }
        wakeStopButton?.setOnClickListener {
            if (JarvisWakeService.isRunning) {
                startService(JarvisWakeService.stopIntent(this))
            }
            mainHandler.postDelayed({ refreshSetupUi() }, 600)
        }
        // batteryButton sirf vivo layout me hai; Samsung layout
        // me ye null milega aur koi listener nahi bandhega.
        val battery: Button? = findViewById(R.id.batteryButton)
        battery?.setOnClickListener { onBatteryButtonClicked() }
        val accessibility: Button? = findViewById(R.id.accessibilityButton)
        accessibility?.setOnClickListener { onAccessibilityButtonClicked() }
        val overlay: Button? = findViewById(R.id.overlayPermissionButton)
        overlay?.setOnClickListener { onOverlayPermissionClicked() }
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized) {
            refreshSetupUi()
            mainHandler.removeCallbacks(refreshRunnable)
            mainHandler.postDelayed(refreshRunnable, REFRESH_MS)
        }
    }

    override fun onPause() {
        mainHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    /**
     * Wake + accessibility status lines ko taza karo —
     * MainActivity.updateWakeUi ka wohi hissa jo in screenon
     * par chala gaya hai (shabd-ba-shabd waisa hi).
     */
    fun refreshSetupUi() {
        if (!::prefs.isInitialized) return
        val status = wakeStatusText ?: return
        val modelReady = modelManager.isModelReady(ModelLanguage.HINDI)
        val running = JarvisWakeService.isRunning
        wakeStartButton?.isEnabled = modelReady && !running
        wakeStopButton?.isEnabled = running
        status.text = buildString {
            when {
                !modelReady -> append(
                    "Background sunna band hai: pehle Hindi offline model " +
                        "download kijiye (main screen wala button) — bina model ke " +
                        "wake word sunna nahi chalega."
                )
                running -> append(
                    "Background sunna CHALU hai — ${JarvisWakeService.statusMessage}"
                )
                else -> append("Background sunna band hai.")
            }
            append("\nWake hits (kitni baar 'Jarvis' suna): ${prefs.wakeHitCount}")
            val lastHeard = if (
                prefs.lastHeardPartialAtMs > prefs.lastHeardAtMs &&
                prefs.lastHeardPartialText.isNotBlank()
            ) {
                prefs.lastHeardPartialText
            } else {
                prefs.lastHeardText.ifBlank { prefs.lastHeardPartialText }
            }
            val lastHeardLabel = if (lastHeard.isBlank()) "—" else "'$lastHeard'"
            append("\nMic ne aakhri suna: $lastHeardLabel")
            if (running && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                append(
                    "\nNote: notification permission nahi hai — upar wala " +
                        "notice nahi dikhega, service phir bhi chal raha hai."
                )
            }
            append(
                if (isBatteryOptimizationIgnored()) {
                    "\nBattery bandobosto: ho gaya hai (whitelist me hai)."
                } else {
                    "\nBattery bandobosto: nahi hua — phone service ko band kar " +
                        "sakta hai, neeche wale bandobosto se theek kijiye."
                }
            )
            append(
                if (Settings.canDrawOverlays(this@WakeSetupActivity)) {
                    "\nOverlay permission ('Display over other apps'): mila hai — " +
                        "background se app khul sakta hai."
                } else {
                    "\nOverlay permission ('Display over other apps'): NAHI mila — " +
                        "background se koi app nahi khulega; neeche wale " +
                        "OVERLAY button se dijiye."
                }
            )
        }
        accessibilityStatusText?.text = if (
            JarvisAccessibilityService.isEnabled(this)
        ) {
            "Accessibility: চালু আছে — 'মোবাইল ডাটা অন করো' বললেই হবে।"
        } else {
            "Accessibility: নেই — মোবাইল ডাটা কমান্ডের জন্য " +
                "নিচের বোতাম থেকে চালু করুন।"
        }
    }

    private fun onWakeStartClicked() {
        if (!modelManager.isModelReady(ModelLanguage.HINDI)) {
            Toast.makeText(
                this,
                "Pehle Hindi offline model download kijiye — bina model ke " +
                    "background sunna nahi chalega.",
                Toast.LENGTH_LONG
            ).show()
            refreshSetupUi()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingWakeStart = true
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO_WAKE
            )
            return
        }
        requestNotificationsPermissionIfNeeded()
        startWakeServiceNow()
    }

    private fun startWakeServiceNow() {
        try {
            startForegroundService(JarvisWakeService.startIntent(this))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Service start nahi ho paya: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
        mainHandler.postDelayed({ refreshSetupUi() }, 600)
    }

    /** Android 13+: ask for the notification permission; denial is fine. */
    private fun requestNotificationsPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS
            )
        }
    }

    protected fun isBatteryOptimizationIgnored(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    /**
     * Battery bandobosto wala button (vivo screen) —
     * MainActivity wala wohi raasta: seedha is app ke liye
     * exemption maango, woh na khule to aam list kholo.
     */
    protected fun onBatteryButtonClicked() {
        if (isBatteryOptimizationIgnored()) {
            Toast.makeText(
                this,
                "Battery bandobosto pehle se ho gaya hai — Jarvis whitelist me hai.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(
                    this,
                    "Settings > Battery > ASHIP JARVIS AI > No restrictions chunein.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * "Accessibility চালু করো" — seedha sahi Settings page
     * kholta hai. Wahan "Installed apps" ke andar ASHIP
     * JARVIS AI ko ON karna hai; wapas aane par status line
     * khud badal jayegi.
     */
    private fun onAccessibilityButtonClicked() {
        if (JarvisAccessibilityService.isEnabled(this)) {
            Toast.makeText(
                this,
                "Accessibility pehle se chalu hai — 'mobile data on karo' bolkar dekho.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Settings > Accessibility > Installed apps > " +
                    "ASHIP JARVIS AI > On kijiye.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * "Display over other apps" — bina iske Android background
     * service se koi app kholne nahi deta. Settings ka page
     * kholta hai; wahan Allow karke wapas aana hai.
     */
    private fun onOverlayPermissionClicked() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Overlay permission pehle se mila hai — background se app khul sakta hai.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (e2: Exception) {
                Toast.makeText(
                    this,
                    "Settings > Apps > ASHIP JARVIS AI > 'Display over other apps' Allow kijiye.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_WAKE) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted && pendingWakeStart) {
                pendingWakeStart = false
                requestNotificationsPermissionIfNeeded()
                startWakeServiceNow()
            } else {
                pendingWakeStart = false
                Toast.makeText(
                    this,
                    "Mic permission ke bina background sunna nahi chalega.",
                    Toast.LENGTH_LONG
                ).show()
                refreshSetupUi()
            }
        }
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Toast.makeText(
                    this,
                    "Notification permission nahi mila — service chalega, " +
                        "par upar wala notice nahi dikhega.",
                    Toast.LENGTH_LONG
                ).show()
            }
            refreshSetupUi()
        }
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO_WAKE = 1002
        private const val REQUEST_POST_NOTIFICATIONS = 1003
        private const val REFRESH_MS = 2_000L
    }
}
