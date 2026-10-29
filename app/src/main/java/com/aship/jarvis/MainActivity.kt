package com.aship.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.aship.jarvis.commands.CommandEngine
import com.aship.jarvis.prefs.AppPrefs
import com.aship.jarvis.prefs.JarvisMode
import com.aship.jarvis.service.JarvisWakeService
import com.aship.jarvis.speaker.TtsSpeaker
import com.aship.jarvis.voice.ModelLanguage
import com.aship.jarvis.voice.ModelManager
import com.aship.jarvis.voice.SpeechEngine
import com.aship.jarvis.voice.SpeechEngineFactory
import com.aship.jarvis.voice.SpeechEngineListener
import java.util.concurrent.Executors

/**
 * ASHIP JARVIS AI — main screen (Phase 1 + Phase 2).
 *
 * Tap the mic button (or type a command), Jarvis runs it through the
 * offline CommandEngine and speaks the reply. Mode (Offline / Online /
 * Auto) is picked here and remembered between launches.
 *
 * Phase 2 adds the section at the bottom: start/stop the background
 * wake-word service (JarvisWakeService) and the battery-exemption
 * shortcut vivo phones need for it to survive.
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var recognizedText: TextView
    private lateinit var responseText: TextView
    private lateinit var micButton: Button
    private lateinit var commandInput: EditText
    private lateinit var modeGroup: RadioGroup

    // Phase 2: background wake-word section.
    private lateinit var wakeStatusText: TextView
    private lateinit var wakeStartButton: Button
    private lateinit var wakeStopButton: Button
    private var pendingWakeStart = false

    private lateinit var prefs: AppPrefs
    private lateinit var speaker: TtsSpeaker
    private lateinit var commandEngine: CommandEngine
    private lateinit var modelManager: ModelManager

    private var engine: SpeechEngine? = null
    private var listening = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = AppPrefs(this)
        speaker = TtsSpeaker(this)
        commandEngine = CommandEngine(this)
        modelManager = ModelManager(this)

        statusText = findViewById(R.id.statusText)
        recognizedText = findViewById(R.id.recognizedText)
        responseText = findViewById(R.id.responseText)
        micButton = findViewById(R.id.micButton)
        commandInput = findViewById(R.id.commandInput)
        modeGroup = findViewById(R.id.modeGroup)

        setupModeSelector()
        createEngine()
        updateStatus()

        micButton.setOnClickListener { toggleListening() }

        findViewById<Button>(R.id.sendButton).setOnClickListener {
            val typed = commandInput.text.toString()
            if (typed.isNotBlank()) {
                processCommand(typed)
                commandInput.text.clear()
            }
        }

        findViewById<Button>(R.id.downloadModelButton).setOnClickListener {
            downloadHindiModel()
        }

        wakeStatusText = findViewById(R.id.wakeStatusText)
        wakeStartButton = findViewById(R.id.wakeStartButton)
        wakeStopButton = findViewById(R.id.wakeStopButton)

        wakeStartButton.setOnClickListener { onWakeStartClicked() }
        wakeStopButton.setOnClickListener {
            if (JarvisWakeService.isRunning) {
                startService(JarvisWakeService.stopIntent(this))
            }
            mainHandler.postDelayed({ updateWakeUi() }, 600)
        }
        findViewById<Button>(R.id.batteryButton).setOnClickListener {
            onBatteryButtonClicked()
        }
        updateWakeUi()
    }

    override fun onResume() {
        super.onResume()
        updateWakeUi()
        // Keep the wake status line live while this screen is visible.
        mainHandler.removeCallbacks(wakeRefreshRunnable)
        mainHandler.postDelayed(wakeRefreshRunnable, WAKE_REFRESH_MS)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(wakeRefreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        engine?.destroy()
        engine = null
        speaker.shutdown()
        background.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Mode selection
    // ------------------------------------------------------------------

    private fun setupModeSelector() {
        val checkedId = when (prefs.mode) {
            JarvisMode.OFFLINE -> R.id.modeOffline
            JarvisMode.ONLINE -> R.id.modeOnline
            JarvisMode.AUTO -> R.id.modeAuto
        }
        modeGroup.check(checkedId)
        modeGroup.setOnCheckedChangeListener { _, id ->
            prefs.mode = when (id) {
                R.id.modeOffline -> JarvisMode.OFFLINE
                R.id.modeOnline -> JarvisMode.ONLINE
                else -> JarvisMode.AUTO
            }
            createEngine()
            updateStatus()
            if (prefs.mode == JarvisMode.ONLINE) {
                Toast.makeText(
                    this,
                    "Online AI Phase 3 me aayega — abhi offline commands chalenge.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ------------------------------------------------------------------
    // Speech engine plumbing
    // ------------------------------------------------------------------

    private fun createEngine() {
        engine?.destroy()
        engine = SpeechEngineFactory.create(this, prefs.mode, modelManager).also { created ->
            created.listener = engineListener
        }
    }

    private val engineListener = object : SpeechEngineListener {
        override fun onListeningStateChanged(isListening: Boolean) {
            listening = isListening
            micButton.text = if (isListening) {
                "🛑 Sun raha hoon... (band karne ke liye tap kijiye)"
            } else {
                "🎤 Tap to speak"
            }
        }

        override fun onPartialResult(text: String) {
            recognizedText.text = "Aap bol rahe hain: $text"
        }

        override fun onFinalResult(text: String) {
            processCommand(text)
        }

        override fun onError(message: String) {
            statusText.text = message
        }
    }

    private fun toggleListening() {
        if (listening) {
            engine?.stopListening()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }
        startListening()
    }

    private fun startListening() {
        // Don't let Jarvis hear its own voice.
        speaker.stop()
        engine?.startListening()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening()
            } else {
                Toast.makeText(
                    this,
                    "Mic permission ke bina voice command nahi chalega.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
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
                updateWakeUi()
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
            updateWakeUi()
        }
    }

    // ------------------------------------------------------------------
    // Command handling
    // ------------------------------------------------------------------

    private fun processCommand(text: String) {
        recognizedText.text = "Aapne kaha: $text"
        val result = commandEngine.handle(text)
        responseText.text = "Jarvis: ${result.responseText}"
        speaker.speak(result.responseText)
    }

    // ------------------------------------------------------------------
    // Offline model download
    // ------------------------------------------------------------------

    private fun downloadHindiModel() {
        statusText.text = "Hindi offline model download shuru ho raha hai..."
        background.execute {
            modelManager.downloadModel(
                ModelLanguage.HINDI,
                object : ModelManager.DownloadCallback {
                    override fun onProgress(message: String) {
                        mainHandler.post { statusText.text = message }
                    }

                    override fun onComplete(language: ModelLanguage) {
                        mainHandler.post {
                            statusText.text = "Hindi offline model ready hai."
                            createEngine()
                            updateStatus()
                        }
                    }

                    override fun onError(message: String) {
                        mainHandler.post { statusText.text = message }
                    }
                }
            )
        }
    }

    // ------------------------------------------------------------------
    // Phase 2: background wake-word listening
    // ------------------------------------------------------------------

    private val wakeRefreshRunnable = object : Runnable {
        override fun run() {
            updateWakeUi()
            mainHandler.postDelayed(this, WAKE_REFRESH_MS)
        }
    }

    private fun updateWakeUi() {
        if (!::wakeStatusText.isInitialized) return
        val modelReady = modelManager.isModelReady(ModelLanguage.HINDI)
        val running = JarvisWakeService.isRunning
        wakeStartButton.isEnabled = modelReady && !running
        wakeStopButton.isEnabled = running
        wakeStatusText.text = buildString {
            when {
                !modelReady -> append(
                    "Background sunna band hai: pehle Hindi offline model " +
                        "download kijiye (upar wala button) — bina model ke " +
                        "wake word sunna nahi chalega."
                )
                running -> append(
                    "Background sunna CHALU hai — ${JarvisWakeService.statusMessage}"
                )
                else -> append("Background sunna band hai.")
            }
            append("\nWake hits (kitni baar 'Jarvis' suna): ${prefs.wakeHitCount}")
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
                    "\nBattery bandobosto: nahi hua — vivo service ko band kar " +
                        "sakta hai, neeche wale button se theek kijiye."
                }
            )
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
            updateWakeUi()
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
        // Stopping this screen's own tap-to-speak engine first: two
        // recognizers must never fight over the microphone.
        if (listening) {
            engine?.stopListening()
        }
        try {
            startForegroundService(JarvisWakeService.startIntent(this))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Service start nahi ho paya: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
        mainHandler.postDelayed({ updateWakeUi() }, 600)
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

    private fun isBatteryOptimizationIgnored(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun onBatteryButtonClicked() {
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

    // ------------------------------------------------------------------
    // Status line
    // ------------------------------------------------------------------

    private fun updateStatus() {
        val modeLabel = when (prefs.mode) {
            JarvisMode.OFFLINE -> "Offline"
            JarvisMode.ONLINE -> "Online (AI Phase 3 me aayega)"
            JarvisMode.AUTO -> "Auto"
        }
        val engineName = engine?.displayName ?: "-"
        val modelState = if (modelManager.isModelReady(ModelLanguage.HINDI)) {
            "Hindi model: ready"
        } else {
            "Hindi model: not downloaded"
        }
        statusText.text = "Mode: $modeLabel | Engine: $engineName | $modelState"
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 1001
        private const val REQUEST_RECORD_AUDIO_WAKE = 1002
        private const val REQUEST_POST_NOTIFICATIONS = 1003
        private const val WAKE_REFRESH_MS = 2_000L
    }
}
