package com.aship.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import com.aship.jarvis.access.JarvisAccessibilityService
import com.aship.jarvis.ai.GeminiBrain
import com.aship.jarvis.chat.AddaAction
import com.aship.jarvis.chat.AddaEngine
import com.aship.jarvis.commands.CommandEngine
import com.aship.jarvis.prefs.AppPrefs
import com.aship.jarvis.update.UpdateChecker
import com.aship.jarvis.update.UpdateDownloader
import com.aship.jarvis.prefs.JarvisMode
import com.aship.jarvis.service.JarvisWakeService
import com.aship.jarvis.speaker.TtsSpeaker
import com.aship.jarvis.voice.ModelLanguage
import com.aship.jarvis.voice.ModelManager
import com.aship.jarvis.voice.SpeechEngine
import com.aship.jarvis.voice.SpeechEngineFactory
import com.aship.jarvis.voice.SpeechEngineListener
import com.aship.jarvis.voice.SystemSpeechEngine
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

    // v17: Accessibility (mobile data) section — battery wale
    // bandobosto ki tarah hi ek status line + ek button.
    private lateinit var accessibilityStatusText: TextView

    // v21 Phase 3: Gemini (online brain) section — key sirf
    // phone par rehti hai; status line kabhi key nahi dikhati.
    private lateinit var geminiKeyStatusText: TextView
    private lateinit var geminiKeyInput: EditText

    // v21: is screen ki Gemini baatchit ki yaad — aakhiri 10
    // user/model turn. Activity zinda hai tab tak rehti hai.
    private val geminiHistory = mutableListOf<Pair<String, String>>()

    // In-app update (GitHub Releases): pending download bookkeeping.
    private var updateDownloadId: Long = -1L
    private var updateReceiver: BroadcastReceiver? = null
    private var updateDialogShowing = false

    private lateinit var prefs: AppPrefs
    private lateinit var speaker: TtsSpeaker
    private lateinit var commandEngine: CommandEngine
    private lateinit var addaEngine: AddaEngine
    private lateinit var modelManager: ModelManager

    private var engine: SpeechEngine? = null
    private var listening = false

    // System recognizer "start" par hi fail ho jaye (unavailable /
    // mic start error) to AUTO/ONLINE me ek baar Vosk par girne ke
    // liye: engineStartedOk batata hai ki is koshish me sunna sach
    // me shuru hua tha ya nahi, aur systemStartFallbackUsed us
    // ek-baar-wale badlav ko loop banne se rokta hai.
    private var engineStartedOk = false
    private var systemStartFallbackUsed = false

    // Mic-sharing (v1.3): foreground sunna shuru karne se pehle wake
    // service ko mic ke liye roka tha ya nahi. Sunna band hone par
    // (ya screen band hone par) isi jhande ko dekh kar ek hi baar
    // resume bhejte hain — resume idempotent aur bina-nuksan hai.
    private var wakePauseSent = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = AppPrefs(this)
        speaker = TtsSpeaker(this)
        commandEngine = CommandEngine(this)
        addaEngine = AddaEngine()
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
        accessibilityStatusText = findViewById(R.id.accessibilityStatusText)
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            onAccessibilityButtonClicked()
        }
        findViewById<Button>(R.id.overlayPermissionButton).setOnClickListener {
            onOverlayPermissionClicked()
        }
        findViewById<Button>(R.id.updateCheckButton).setOnClickListener {
            checkForUpdate(manual = true)
        }
        geminiKeyStatusText = findViewById(R.id.geminiKeyStatusText)
        geminiKeyInput = findViewById(R.id.geminiKeyInput)
        findViewById<Button>(R.id.geminiKeySaveButton).setOnClickListener {
            onGeminiKeySaveClicked()
        }
        findViewById<Button>(R.id.geminiKeyClearButton).setOnClickListener {
            GeminiBrain.clearKey(this)
            geminiKeyInput.text.clear()
            refreshGeminiKeyUi()
            Toast.makeText(this, "Gemini key hata di gayi.", Toast.LENGTH_SHORT).show()
        }
        refreshGeminiKeyUi()
        updateWakeUi()

        // In-app update: app khulne par ek baar chup-chaap check.
        // Fail ho to koi error nahi dikhta (silent by design).
        checkForUpdate(manual = false)
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
        // Screen peeche ja rahi hai: mic wake service ka tha, to
        // wapas kar do — user dusri app me jaakar 'Jarvis' bolega
        // to wake sunna wahan chalu hona chahiye.
        resumeWakeService()
        super.onPause()
    }

    override fun onStop() {
        // onPause ke baad wala doosra jaal: kisi bhi raaste se app
        // background me gaya ho, wake service ruka na reh jaye.
        resumeWakeService()
        super.onStop()
    }

    override fun onDestroy() {
        // Screen ja rahi hai aur mic wake service ko diya hua tha to
        // wapas kar do — warna wake sunna 12 s wale safety timer tak
        // ruka rahega.
        resumeWakeService()
        unregisterUpdateReceiver()
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
                    "Online AI abhi aaya nahi hai — abhi offline commands chalenge.",
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
        systemStartFallbackUsed = false
        engine = SpeechEngineFactory.create(this, prefs.mode, modelManager).also { created ->
            created.listener = engineListener
        }
    }

    private val engineListener: SpeechEngineListener = object : SpeechEngineListener {
        override fun onListeningStateChanged(isListening: Boolean) {
            listening = isListening
            if (isListening) {
                engineStartedOk = true
                // Sunna SACH ME shuru hua — ab wake service ko mic
                // ke liye roko (v1.6). Pehle ye startListening() par
                // hota tha; wahan rokne se aise session bhi rukte
                // the jo kabhi shuru hi nahi hue.
                pauseWakeServiceForMic()
            } else {
                // Foreground sunna band — mic wake service ko wapas
                // do (agar is session ke liye use roka tha to hi).
                resumeWakeService()
            }
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
            // Session khatm ho gaya hai. Kuch flows me iske baad
            // onListeningStateChanged(false) aata hi nahi — wahi
            // v1.5 wala atkav tha — isliye mic yahin se wake
            // service ko wapas kar do.
            resumeWakeService()
            processCommand(text)
        }

        override fun onError(message: String) {
            // System recognizer sunna shuru hi nahi kar paya
            // (unavailable / mic start error) aur mode AUTO/ONLINE
            // hai, to ek baar Vosk engine par gir jao aur phir se
            // sunna shuru karo. Sunna ek baar shuru ho chuka ho to
            // ye aam error hai — waisa hi dikhao jaise pehle tha.
            if (!engineStartedOk && !systemStartFallbackUsed &&
                engine is SystemSpeechEngine &&
                prefs.mode != JarvisMode.OFFLINE &&
                modelManager.isModelReady(ModelLanguage.HINDI)
            ) {
                systemStartFallbackUsed = true
                engine?.destroy()
                engine = SpeechEngineFactory.createVoskOrSystem(
                    this@MainActivity, modelManager
                )
                engine?.listener = engineListener
                updateStatus()
                startListening()
                return
            }
            // Error ke saath session khatm — mic wake service ko
            // wapas kar do. (Fallback wale raaste par naya session
            // shuru hote hi khud pause bhej dega.)
            resumeWakeService()
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
        // Mic-sharing (v1.3, v1.6): wake service ko rokna YAHAN
        // nahi hai — woh onListeningStateChanged(true) me hota hai,
        // jab sunna sach me shuru ho jata hai. Do recorder ek saath
        // chal hi nahi sakte, isliye asal shuruaat wala pal hi
        // rokne ka sahi pal hai.
        engineStartedOk = false
        engine?.startListening()
    }

    /**
     * Wake service zinda ho to use mic chhodne ko kaho. Service na
     * ho to kuch mat karo — use sirf rokne ke liye start karna
     * galat hoga (isliye pehle prefs wala jhanda dekho).
     */
    private fun pauseWakeServiceForMic() {
        if (!prefs.wakeServiceRunning) return
        try {
            startService(JarvisWakeService.pauseIntent(this))
            wakePauseSent = true
        } catch (e: Exception) {
            // Service nahi mila — foreground sunna phir bhi try karo.
        }
    }

    /**
     * Roka hua wake service phir chalu karne ko kaho. Ek hi baar
     * bhejta hai; session kabhi shuru hi na hua ho to bina kuch
     * kiye laut jata hai.
     */
    private fun resumeWakeService() {
        if (!wakePauseSent) return
        wakePauseSent = false
        if (!prefs.wakeServiceRunning) return
        try {
            startService(JarvisWakeService.resumeIntent(this))
        } catch (e: Exception) {
            // Service band ho chuka hai — kuch nahi karna.
        }
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
        // v13: adda trigger ya is screen par chalu adda ho to pehle
        // Maya ka AddaEngine — uski state is activity instance me
        // har tap ke beech bani rehti hai. CommandEngine baad me.
        if (addaEngine.shouldHandle(text)) {
            val step = addaEngine.respond(text)
            val spokenText = if (step.action == AddaAction.OPEN_YOUTUBE) {
                val launchResult = commandEngine.handle("youtube kholo")
                if (launchResult.responseText == "YouTube खोल दिया है।") {
                    step.text
                } else {
                    addaEngine.endSession()
                    launchResult.responseText
                }
            } else {
                step.text
            }
            responseText.text = if (addaEngine.isActive) {
                "Maya: $spokenText\nAdda mode: ON — 'bye' bolne par band hoga."
            } else {
                "Maya: $spokenText"
            }
            speaker.speak(spokenText)
            return
        }
        val result = commandEngine.handle(text)
        // v21 Phase 3: offline engine miss kare aur online brain
        // uplabdh ho to pehle Gemini ko ek mauka — uske jawab ka
        // intezaar karo, purana fallback jawab abhi mat bolo.
        // Gemini null de to askGeminiBrain ke andar wahi purana
        // jawab chalega. Handled command kabhi yahan nahi aata.
        if (!result.handled && geminiBrainAllowed()) {
            responseText.text = "Maya online soch rahi hai..."
            askGeminiBrain(text, result.responseText)
            return
        }
        responseText.text = "Jarvis: ${result.responseText}"
        speaker.speak(result.responseText)
    }

    // ------------------------------------------------------------------
    // Phase 3 (v21): Gemini online brain — key rakhna + fallback
    // ------------------------------------------------------------------

    /** Key save karo: khaali na ho tabhi band karo, khana turant saaf. */
    private fun onGeminiKeySaveClicked() {
        val typed = geminiKeyInput.text.toString().trim()
        if (typed.isEmpty()) {
            Toast.makeText(this, "Pehle khane me key likho.", Toast.LENGTH_SHORT).show()
            return
        }
        GeminiBrain.setKey(this, typed)
        geminiKeyInput.text.clear()
        refreshGeminiKeyUi()
        Toast.makeText(
            this,
            "Key save ho gayi — ab Maya online sawalon ke jawab bhi degi.",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Key ki haalat sirf haan/na me dikhao — key ka text screen
     * par kabhi wapas nahi aata. Key set ho to khana khaali
     * rahega; badalna ho to naya key likhkar save karna hoga.
     */
    private fun refreshGeminiKeyUi() {
        if (!::geminiKeyStatusText.isInitialized) return
        if (GeminiBrain.isConfigured(this)) {
            geminiKeyStatusText.text = "Key: set hai ✓"
            geminiKeyInput.hint = "Key set hai — badalna hai to naya key likho"
        } else {
            geminiKeyStatusText.text = "Key: nahi hai"
            geminiKeyInput.hint = "Yahan apna Gemini API key likho"
        }
    }

    /**
     * v21 gate (wake service wala hi niyam): mode pure-Offline
     * na ho, key set ho aur net chalu ho — tabhi Gemini bulao.
     */
    private fun geminiBrainAllowed(): Boolean {
        if (prefs.mode == JarvisMode.OFFLINE) return false
        if (!GeminiBrain.isConfigured(this)) return false
        return isNetworkUp()
    }

    /** Is pal internet uplabdh hai ya nahi (ConnectivityManager se). */
    private fun isNetworkUp(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Gemini se jawab maango (jawab uske apne thread par aata
     * hai; UI ka saara kaam main thread par wapas aakar hota
     * hai). Jawab mile to dikhao + bolo aur baatchit yaad me
     * jodo; na mile to engine wala purana fallback jawab hi
     * dikhao + bolo — bilkul pehle jaisa.
     */
    private fun askGeminiBrain(text: String, fallbackReply: String) {
        GeminiBrain.chat(this, geminiHistory.toList(), text) { reply ->
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (reply == null) {
                    responseText.text = "Jarvis: $fallbackReply"
                    speaker.speak(fallbackReply)
                    return@post
                }
                geminiHistory.add("user" to text)
                geminiHistory.add("model" to reply)
                while (geminiHistory.size > MAX_GEMINI_HISTORY_ENTRIES) {
                    geminiHistory.removeAt(0)
                    geminiHistory.removeAt(0)
                }
                responseText.text = "Maya: $reply"
                speaker.speak(reply)
            }
        }
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
                    "\nBattery bandobosto: nahi hua — vivo service ko band kar " +
                        "sakta hai, neeche wale button se theek kijiye."
                }
            )
            append(
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    "\nOverlay permission ('Display over other apps'): mila hai — " +
                        "background se app khul sakta hai."
                } else {
                    "\nOverlay permission ('Display over other apps'): NAHI mila — " +
                        "background se koi app nahi khulega; neeche wale " +
                        "OVERLAY button se dijiye."
                }
            )
        }
        // v17: Accessibility ki haalat — ye function onResume par
        // aur har 2 s me chalta hai, isliye Settings se wapas
        // aate hi line khud taza ho jati hai.
        if (::accessibilityStatusText.isInitialized) {
            accessibilityStatusText.text = if (
                JarvisAccessibilityService.isEnabled(this)
            ) {
                "Accessibility: চালু আছে — 'মোবাইল ডাটা অন করো' বললেই হবে।"
            } else {
                "Accessibility: নেই — মোবাইল ডাটা কমান্ডের জন্য " +
                    "নিচের বোতাম থেকে চালু করুন।"
            }
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

    /**
     * v17: "Accessibility চালু করো" — battery wale button ki
     * tarah seedha sahi Settings page kholta hai. Wahan
     * "Installed apps" ke andar ASHIP JARVIS AI ko ON karna
     * hai; wapas aane par status line khud badal jayegi.
     * Service pehle se chalu ho to sirf bata do.
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
     * "Display over other apps" — bina iske Android background service
     * se koi app kholne nahi deta (Phase 2 fix). Settings ka page
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

    // ------------------------------------------------------------------
    // In-app update (GitHub Releases)
    // ------------------------------------------------------------------

    /**
     * GitHub par latest release dekhta hai. Auto-check (app start par)
     * fail ho to bilkul chup rehta hai; haath se check karne par
     * "latest hai" / "check nahi ho paya" batata hai.
     */
    private fun checkForUpdate(manual: Boolean) {
        UpdateChecker.checkLatest { result ->
            when (result) {
                is UpdateChecker.Result.UpdateAvailable -> showUpdateDialog(result.info)
                UpdateChecker.Result.UpToDate -> {
                    if (manual) {
                        Toast.makeText(
                            this,
                            "Aap latest version par hain.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                UpdateChecker.Result.Failed -> {
                    if (manual) {
                        Toast.makeText(
                            this,
                            "Update check nahi ho paya — internet check karke phir koshish kijiye.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun showUpdateDialog(info: UpdateChecker.UpdateInfo) {
        if (isFinishing || updateDialogShowing) return
        updateDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("Naya version aa gaya hai!")
            .setMessage(
                "ASHIP JARVIS AI ka naya version (${info.tagName}) taiyaar hai. " +
                    "Download karke install kijiye."
            )
            .setPositiveButton("Download karo") { _, _ ->
                startUpdateDownload(info.downloadUrl)
            }
            .setNegativeButton("Baad me", null)
            .setOnDismissListener { updateDialogShowing = false }
            .show()
    }

    private fun startUpdateDownload(downloadUrl: String) {
        if (!packageManager.canRequestPackageInstalls()) {
            // Pehle Android se "unknown apps install" ki ijazat leni
            // hogi; bina uske installer APK khol hi nahi payega.
            Toast.makeText(
                this,
                "Pehle is list me ASHIP JARVIS AI ko Allow kijiye, " +
                    "phir wapas aakar Download dabaiye.",
                Toast.LENGTH_LONG
            ).show()
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Settings > Apps > ASHIP JARVIS AI > Install unknown apps > Allow kijiye.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        val downloadId = UpdateDownloader.startDownload(this, downloadUrl)
        if (downloadId < 0L) {
            Toast.makeText(this, "Download shuru nahi ho paya.", Toast.LENGTH_LONG).show()
            return
        }
        updateDownloadId = downloadId
        registerUpdateReceiver()
        Toast.makeText(
            this,
            "Naya version download ho raha hai — poora hote hi installer khul jayega.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun registerUpdateReceiver() {
        if (updateReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                if (id != updateDownloadId) return
                unregisterUpdateReceiver()
                onUpdateDownloadFinished()
            }
        }
        updateReceiver = receiver
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // ACTION_DOWNLOAD_COMPLETE system bhejta hai (dusra process),
            // isliye Android 13+ par EXPORTED ke saath register karo.
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    private fun unregisterUpdateReceiver() {
        updateReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
            } catch (e: Exception) {
                // Already unregistered: nothing to do.
            }
        }
        updateReceiver = null
    }

    private fun onUpdateDownloadFinished() {
        val finishedId = updateDownloadId
        updateDownloadId = -1L
        if (UpdateDownloader.isDownloadSuccessful(this, finishedId)) {
            try {
                startActivity(UpdateDownloader.installIntent(this))
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Installer khul nahi paya — Files app se jarvis-update.apk install kijiye.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            Toast.makeText(
                this,
                "Update download nahi ho paya — internet check karke phir koshish kijiye.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ------------------------------------------------------------------
    // Status line
    // ------------------------------------------------------------------

    private fun updateStatus() {
        val modeLabel = when (prefs.mode) {
            JarvisMode.OFFLINE -> "Offline"
            JarvisMode.ONLINE -> "Online (AI abhi aaya nahi hai)"
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

        /** v21: Gemini yaad ki gehrai — 10 turn, ek turn = user+model jodi. */
        private const val MAX_GEMINI_HISTORY_ENTRIES = 20
    }
}
