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
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.aship.jarvis.ai.AgentBrain
import com.aship.jarvis.ai.AgentTools
import com.aship.jarvis.ai.BrainRules
import com.aship.jarvis.ai.LearnedRoutines
import com.aship.jarvis.ai.ProviderKeys
import com.aship.jarvis.ai.ReplyGuard
import com.aship.jarvis.ai.ScreenConfirm
import com.aship.jarvis.ai.GeminiBrain
import com.aship.jarvis.ai.QuotaManager
import com.aship.jarvis.ai.TaughtCommands
import com.aship.jarvis.chat.AddaAction
import com.aship.jarvis.chat.AddaEngine
import com.aship.jarvis.commands.CommandEngine
import com.aship.jarvis.commands.CommandHistory
import com.aship.jarvis.commands.CommandResult
import com.aship.jarvis.commands.HearingGate
import com.aship.jarvis.commands.HistoryCommands
import com.aship.jarvis.commands.LearnCommands
import com.aship.jarvis.commands.Persona
import com.aship.jarvis.commands.PersonaVocative
import com.aship.jarvis.commands.PlayIntent
import com.aship.jarvis.commands.RuleCommands
import com.aship.jarvis.commands.SendButtonCommands
import com.aship.jarvis.commands.SessionCommands
import com.aship.jarvis.commands.TeachCommands
import com.aship.jarvis.commands.UrlIntent
import com.aship.jarvis.commands.spokenLang
import com.aship.jarvis.prefs.AppPrefs
import com.aship.jarvis.update.UpdateChecker
import com.aship.jarvis.update.UpdateDownloader
import com.aship.jarvis.prefs.JarvisMode
import com.aship.jarvis.service.JarvisWakeService
import com.aship.jarvis.speaker.TtsSpeaker
import com.aship.jarvis.voice.BanglaModelManager
import com.aship.jarvis.voice.CascadeOrder
import com.aship.jarvis.voice.MicArbiter
import com.aship.jarvis.voice.ModelLanguage
import com.aship.jarvis.voice.ModelManager
import com.aship.jarvis.voice.SpeechEngine
import com.aship.jarvis.voice.SpeechEngineFactory
import com.aship.jarvis.voice.SpeechEngineListener
import com.aship.jarvis.voice.SystemSpeechEngine
import com.aship.jarvis.voice.VoskSpeechEngine
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * ASHIP JARVIS AI — main screen (Phase 1 + Phase 2).
 *
 * Tap the mic button (or type a command), Jarvis runs it through the
 * offline CommandEngine and speaks the reply. Mode (Offline / Online /
 * Auto) is picked here and remembered between launches.
 *
 * v34: background wake-word ke bandobosto is screen se
 * "Mobile" hub me chale gaye hain (vivo / Samsung screenen);
 * yahan sirf unhe kholne wala ek button hai.
 *
 * v35: woh button ab phone ki company khud pehchan kar
 * seedha sahi brand screen kholta hai. Aur saamne wala
 * sunna mazboot: system recognizer ek bhasha me na sun
 * paye to agli bhasha khud aazmata hai (cascade), service
 * hi kharab ho to Vosk par girta hai, aur offline engine
 * jo baat na samjhe woh Gemini agent ke paas jati hai —
 * "samajh nahi aaya" par rukna band.
 *
 * v36: play-iraade wala sanyojit hukm ("YouTube khulo
 * aur gaana chalao") routing ki sabse pehli seedhi hai
 * (PlayIntent) — agent uplabdh ho to agent, warna seedha
 * youtube_play auzaar; aur saare model 429 par marein to
 * aam haar ki jagah imaandaar "online seema khatm" wala
 * jawab, user ki bhasha me.
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var recognizedText: TextView
    private lateinit var responseText: TextView
    private lateinit var micButton: Button
    private lateinit var commandInput: EditText
    private lateinit var modeGroup: RadioGroup

    // v34: background wake-word ke bandobosto (start/stop,
    // battery, accessibility, overlay) ab "Mobile" hub ke
    // andar hain — VivoSetupActivity / SamsungSetupActivity,
    // saajha aadhaar WakeSetupActivity. Is screen par unhe
    // kholne wala sirf ek button bacha hai.

    // v29: contacts permission app khulne par zyada se
    // zyada ek baar maangi jati hai — mana ho to baar-
    // baar tang (nag) nahi karna.
    private var contactsPermissionRequested = false

    // v23: panel-scan report ki line — Accessibility service
    // har data koshish ke baad SharedPreferences "jarvis_diag"
    // me report chhodti hai; yahan sirf use dikhaya jata hai.
    private lateinit var panelScanText: TextView

    // v33: YouTube tap + agent chaal ki report lines, aur
    // shune wali bhasha badalne wala button.
    private lateinit var youtubeTapText: TextView
    private lateinit var agentRunText: TextView
    private lateinit var listenLangButton: Button

    // v37: screen-agent (screen_task) ki report line —
    // "jarvis_diag" ki last_screen_run report yahan dikhti hai.
    private lateinit var screenRunText: TextView

    // v46: aakhiri screen-kaam ki audit report line —
    // ScreenAudit "last_screen_audit" bandhta hai
    // (<kaam> — <n> actions, <natija>); poora audit
    // record app ke andar wali file me rehta hai,
    // yahan sirf uska saar dikhta hai.
    private lateinit var screenAuditText: TextView

    // v41: Bangla offline model (Zipformer) ki haalat —
    // status line + download button is screen par hi hain
    // taaki malik ek nazar me dekhe: model aaya ya nahi,
    // download kitna hua, aur aakhiri baar offline kaan
    // ne kya suna (last_bangla_asr wali report line).
    private lateinit var banglaModelStatusText: TextView
    private lateinit var banglaModelButton: Button
    private lateinit var banglaAsrText: TextView
    private lateinit var banglaModelManager: BanglaModelManager

    // v43: aaj ke Gemini kota-hisaab ki line —
    // QuotaManager app ki apni ginati rakhta hai
    // (kis model par kitni koshish, main khatm hai
    // kya); yahan sirf usi ka jod dikhta hai.
    private lateinit var quotaText: TextView

    // v48: aakhiri background session ki shaksiyat +
    // uska ant — wake service "last_persona" aur
    // "last_session_end" bandhta hai (maya-hindi /
    // tara-bengali, aur ant ki wajah: completed /
    // not-understood / redirect-bn ...). Yahan sirf
    // dono ka jod dikhta hai.
    private lateinit var personaText: TextView

    // v49: seekhe hue kaamon ki kul ginati —
    // [LearnedRoutines] ki godam se taaza padhkar
    // dikhti hai (kaam ke naam yahan nahi, woh
    // "কী কী শিখেছ" bolne par awaaz se milte hain)।
    private lateinit var learnedText: TextView
    private lateinit var taughtText: TextView
    private lateinit var rulesText: TextView

    // v53: API Keys wala button + command history wali list।
    private lateinit var apiKeysButton: Button
    private lateinit var historyList: LinearLayout
    private lateinit var historyClearButton: Button

    // v51 (item 1): sunne ki report wali teen nayi
    // linein — background ne aakhiri kya suna
    // (last_background_heard), saamne wali screen ne
    // aakhiri kya suna (last_foreground_heard), aur
    // sunne ka raasta (last_hear_path: engine +
    // bhasha + error code)। Teeno jarvis_diag se
    // aati hain — v50 tak pehli do sirf file me band
    // hoti thi, screen par dikhti hi nahi thi।
    private lateinit var backgroundHeardText: TextView
    private lateinit var foregroundHeardText: TextView
    private lateinit var hearPathText: TextView

    // v51 (item 4): Hindi offline pack wali salaah
    // line — pack safal hone ya malik ke band karne
    // tak dikhti hai।
    private lateinit var offlinePackHintText: TextView

    // v51 (item 8): "offline kaan pehle" wala
    // vaikalpik badlav (switch) — default band।
    private lateinit var offlineEarsFirstSwitch: Switch

    // v51 (item 2): saamne wale gate se ruka natija
    // bina boli ke kitni baar phir suna gaya।
    private var fgGateRestarts: Int = 0

    // v37: ye screen is pal saamne hai kya — screen-agent
    // ke sawal par khud sunna shuru karna ho to yahi jhanda
    // faisla karta hai (peeche gayi screen mic nahi pakdegi).
    private var activityInForeground: Boolean = false

    /**
     * v37: screen-agent ke sawalon ki is screen ki
     * mezbaani ([ScreenConfirm.Host]) — sawal aane par
     * use dikhao + bolo; screen saamne ho aur mic ki
     * ijazat ho to boli khatm hote hi sunna khud shuru
     * kar do (jawab processCommand se hokar pul tak
     * jayega). Peeche gayi screen par sirf boli/screen
     * wala hissa chalega — jawab wahan se wake service
     * ya likhkar bhi aa sakta hai.
     */
    private val screenConfirmHost = ScreenConfirm.Host { question ->
        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            responseText.text = "Maya: $question"
            speaker.speak(question)
            if (activityInForeground && !listening &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                // Sunna boli khatm hone ke BAAD shuru ho —
                // warna startListening wala speaker.stop()
                // sawal ki boli hi kaat dega. Ek-baar wala
                // callback hai: purana (is screen par aam
                // taur par null) khatm hote hi wapas laga do.
                val previous = speaker.onUtteranceFinished
                speaker.onUtteranceFinished = {
                    speaker.onUtteranceFinished = previous
                    mainHandler.post {
                        if (!isFinishing && !isDestroyed &&
                            ScreenConfirm.hasPending() && !listening
                        ) {
                            startListening()
                        }
                    }
                }
            }
        }
    }

    // v35: shune wali bhasha ki chhoti si label (button ke
    // upar) — galat setting ek nazar me dikh jaye.
    private lateinit var listenLangLabel: TextView

    // v35: bhasha cascade ki haalat — system recognizer
    // pehli bhasha me na sun paye to kataar ki agli bhasha
    // khud aazmata hai: user ki pasand, phir bn-IN, phir
    // hi-IN (dohraaye gaye hata kar). cascadeIndex batata
    // hai ki is turn me kaunsi koshish chal rahi hai.
    private var cascadeLanguages: List<String> = emptyList()
    private var cascadeIndex: Int = 0

    // v35: ek hi turn me "samajh nahi aaya" wala vinamr
    // jawab awaaz se zyada se zyada ek baar — dobara ho to
    // sirf screen par dikhega (baar-baar wahi boli user ko
    // sabse zyada khalti thi).
    private var politeFailureSpokenThisTurn: Boolean = false

    // v21 Phase 3: Gemini (online brain) section — key sirf
    // phone par rehti hai; status line kabhi key nahi dikhati.
    private lateinit var geminiKeyStatusText: TextView

    // v24: aakhiri online koshish ki report ki line —
    // GeminiBrain har chat koshish ke baad SharedPreferences
    // "jarvis_diag" me report chhodta hai; yahan sirf use
    // dikhaya jata hai (panelScanText ki tarah hi).
    private lateinit var geminiDiagText: TextView
    private lateinit var geminiKeyInput: EditText
    private lateinit var geminiKeySaveButton: Button

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
        banglaModelManager = BanglaModelManager(this)

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
            submitTypedCommand()
        }

        findViewById<Button>(R.id.downloadModelButton).setOnClickListener {
            downloadHindiModel()
        }

        // v34: Mobile hub — background/wake/battery/accessibility/
        // overlay ke saare bandobosto ab is ek button ke peeche
        // hain (vivo / Samsung ke alag-alag hisse).
        // v35: button ab phone ki company khud pehchan kar
        // seedha usi brand ki screen kholta hai; company na
        // mile to purana hub (chunne wali screen) hi khulta
        // hai. Har brand screen se hub dobara bhi khul sakta
        // hai ("অন্য ফোনের সেটিং দেখুন").
        findViewById<Button>(R.id.mobileSettingsButton).setOnClickListener {
            val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
            val target = when {
                manufacturer.contains("vivo") ||
                    manufacturer.contains("iqoo") ->
                    VivoSetupActivity::class.java
                manufacturer.contains("samsung") ->
                    SamsungSetupActivity::class.java
                else -> MobileHubActivity::class.java
            }
            startActivity(Intent(this, target))
        }
        panelScanText = findViewById(R.id.panelScanText)
        youtubeTapText = findViewById(R.id.youtubeTapText)
        agentRunText = findViewById(R.id.agentRunText)
        screenRunText = findViewById(R.id.screenRunText)
        screenAuditText = findViewById(R.id.screenAuditText)
        banglaModelStatusText = findViewById(R.id.banglaModelStatusText)
        banglaModelButton = findViewById(R.id.banglaModelButton)
        banglaAsrText = findViewById(R.id.banglaAsrText)
        quotaText = findViewById(R.id.quotaText)
        personaText = findViewById(R.id.personaText)
        learnedText = findViewById(R.id.learnedText)
        taughtText = findViewById(R.id.taughtText)
        rulesText = findViewById(R.id.rulesText)
        // v51 (item 1): sunne ki report wali linein।
        backgroundHeardText = findViewById(R.id.backgroundHeardText)
        foregroundHeardText = findViewById(R.id.foregroundHeardText)
        hearPathText = findViewById(R.id.hearPathText)
        // v51 (item 4): salaah line band karne wala
        // button — ek baar band to phir nahi dikhegi
        // (haalat prefs me bandh jati hai)।
        offlinePackHintText = findViewById(R.id.offlinePackHintText)
        findViewById<Button>(R.id.offlinePackHintClose).setOnClickListener {
            prefs.offlinePackHintDismissed = true
            refreshOfflinePackHint()
        }
        // v51 (item 8): offline kaan pehle wala badlav।
        offlineEarsFirstSwitch = findViewById(R.id.offlineEarsFirstSwitch)
        offlineEarsFirstSwitch.isChecked = prefs.offlineEarsFirst
        offlineEarsFirstSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.offlineEarsFirst = checked
            // Saamne wala engine bhi naye niyam se bane।
            createEngine()
            updateStatus()
        }
        banglaModelButton.setOnClickListener { downloadBanglaModel() }
        refreshBanglaModelUi()
        listenLangLabel = findViewById(R.id.listenLangLabel)
        listenLangButton = findViewById(R.id.listenLangButton)
        listenLangButton.setOnClickListener {
            // v33: shune wali bhasha ghumao — Bangla -> Hindi -> English.
            prefs.listenLanguage = when (prefs.listenLanguage) {
                "bn-IN" -> "hi-IN"
                "hi-IN" -> "en-IN"
                else -> "bn-IN"
            }
            refreshListenLangButton()
        }
        findViewById<Button>(R.id.updateCheckButton).setOnClickListener {
            checkForUpdate(manual = true)
        }
        geminiKeyStatusText = findViewById(R.id.geminiKeyStatusText)
        geminiDiagText = findViewById(R.id.geminiDiagText)
        geminiKeyInput = findViewById(R.id.geminiKeyInput)
        geminiKeySaveButton = findViewById(R.id.geminiKeySaveButton)
        geminiKeySaveButton.setOnClickListener {
            onGeminiKeySaveClicked()
        }
        findViewById<Button>(R.id.geminiKeyClearButton).setOnClickListener {
            GeminiBrain.clearKey(this)
            geminiKeyInput.text.clear()
            refreshGeminiKeyUi()
            updateStatus()
            Toast.makeText(this, "Gemini key hata di gayi.", Toast.LENGTH_SHORT).show()
        }
        // v53: API Keys — saare free provideron ki key ek
        // alag screen par; Gemini wala hissa upar waisa hi
        // rehta hai aur waisa hi kaam karega।
        apiKeysButton = findViewById(R.id.apiKeysButton)
        apiKeysButton.setOnClickListener {
            startActivity(Intent(this, ApiKeysActivity::class.java))
        }
        refreshApiKeysButton()
        // v53: command history — aakhiri 5 hukm tappable
        // list; tap karte hi wohi hukm phir chalega।
        historyList = findViewById(R.id.historyList)
        historyClearButton = findViewById(R.id.historyClearButton)
        historyClearButton.setOnClickListener {
            CommandHistory.clear(this)
            refreshHistoryUi()
            Toast.makeText(this, "ইতিহাস মুছে দেওয়া হয়েছে।", Toast.LENGTH_SHORT).show()
        }
        refreshHistoryUi()
        // v37: screen-agent ke sawalon ki mezbaani is
        // screen ke naam bandho (onDestroy me hat ti hai).
        ScreenConfirm.registerActivityHost(screenConfirmHost)
        refreshGeminiKeyUi()
        refreshDiagLines()

        // v29: call_person auzaar ke liye contacts
        // permission — app khulne par ek baar maango;
        // mana ho to app aam chalta rahega, koi nagging
        // nahi (requestContactsPermissionIfNeeded dekho).
        requestContactsPermissionIfNeeded()

        // In-app update: app khulne par ek baar chup-chaap check.
        // Fail ho to koi error nahi dikhta (silent by design).
        checkForUpdate(manual = false)
    }

    override fun onResume() {
        super.onResume()
        // v37: screen saamne hai — screen-agent ke sawal
        // par khud sunna shuru karne ka jhanda.
        activityInForeground = true
        refreshDiagLines()
        refreshApiKeysButton()
        refreshHistoryUi()
        // Keep the report lines live while this screen is visible.
        mainHandler.removeCallbacks(wakeRefreshRunnable)
        mainHandler.postDelayed(wakeRefreshRunnable, WAKE_REFRESH_MS)
    }

    override fun onPause() {
        // v37: screen peeche gayi — khud sunna shuru nahi hoga.
        activityInForeground = false
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
        // v37: screen-agent wali mezbaani bhi hatao.
        ScreenConfirm.unregisterActivityHost(screenConfirmHost)
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
                // v23: toast ab key ki haalat ke hisaab se sach
                // bolta hai — key band ho to "aaya nahi hai"
                // kehna jhooth tha (header "taiyaar hai" dikha
                // raha hota tha aur toast usse ulta).
                Toast.makeText(
                    this,
                    if (GeminiBrain.isConfigured(this)) {
                        "Online AI taiyaar hai — Gemini se jawab milega."
                    } else {
                        "Online AI abhi aaya nahi hai — abhi offline commands chalenge."
                    },
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
        // v51 (item 8): "offline kaan pehle" chalu ho to
        // saamne wala engine bhi Vosk (offline) wala bane
        // — model taiyaar na ho to factory khud system
        // wala dega (createVoskOrSystem ka bandha niyam)।
        val created = if (prefs.offlineEarsFirst) {
            SpeechEngineFactory.createVoskOrSystem(this, modelManager)
        } else {
            SpeechEngineFactory.create(this, prefs.mode, modelManager)
        }
        engine = created.also { eng ->
            eng.listener = engineListener
            // v51 (item 5): engine bante hi pehli bhasha
            // spasht bandh do — pehla startListening
            // kabhi purani/pasand wali bhasha chup-chaap
            // na le; kataar ka sir aakhiri shaksiyat se
            // banta hai (resetCascade wala hi hisaab)।
            (eng as? SystemSpeechEngine)?.languageOverride =
                CascadeOrder.firstLanguage(lastForegroundPersona(), prefs.listenLanguage)
        }
    }

    /** v51 (item 5): bandhi aakhiri saamne-wali shaksiyat, ya abhi tak koi na ho to null। */
    private fun lastForegroundPersona(): Persona? {
        val name = prefs.lastForegroundPersonaName ?: return null
        return try {
            Persona.valueOf(name)
        } catch (e: IllegalArgumentException) {
            null
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
            // v51 (item 2): saamne wala gate — kachra
            // (bahut chhota + bina pehchan shabd) hukm
            // bankar processCommand tak NAHI jayega।
            // Bina kuch bole wahi kataar par phir suno
            // (seema 2 baar); seema ke baad imaandaar
            // status dikhakar turn khatm। Likha hua
            // hukm (type-and-send) is gate se NAHI
            // guzarta — woh malik ki saaf marzi hai।
            val gate = HearingGate.evaluate(
                text = text,
                persona = lastForegroundPersona(),
                scriptReliable = engine is SystemSpeechEngine,
                extraAccepted = fgGateExtraAccepted()
            )
            if (!gate.accepted) {
                saveFgHearPath(text, gateReason = gate.reason)
                if (fgGateRestarts < FG_MAX_GATE_RESTARTS) {
                    fgGateRestarts++
                    // Mic ka daava phir bandh do — sunna
                    // band hone wale callback ne use chhod
                    // diya hoga, par turn abhi chal raha
                    // hai (v51 item 6a)।
                    MicArbiter.claim(this@MainActivity, MicArbiter.OWNER_FOREGROUND)
                    statusText.text =
                        "ঠিক শোনা গেল না — আবার শুনছি, বলুন..."
                    engine?.startListening()
                } else {
                    resumeWakeService()
                    statusText.text =
                        "কিছু ঠিক শোনা গেল না — আবার ট্যাপ করে বলুন।"
                }
                return
            }
            // Session khatm ho gaya hai. Kuch flows me iske baad
            // onListeningStateChanged(false) aata hi nahi — wahi
            // v1.5 wala atkav tha — isliye mic yahin se wake
            // service ko wapas kar do.
            resumeWakeService()
            // v35: jeetne wali bhasha ke saath suna hua jarvis_diag
            // me likho — maidan ki report ke liye.
            logForegroundHeard(text)
            processCommand(text)
        }

        override fun onError(message: String) {
            // v51 (item 1): error wala turn bhi sunne ke
            // raaste ki report me bandhe — engine +
            // bhasha + code + turant-fail ka nishan।
            saveFgHearPath(null, gateReason = null)
            val sys = engine as? SystemSpeechEngine
            if (sys != null) {
                // v35: kuch samajh nahi aaya (no match / khaali
                // result / chuppi khatm) — kataar ki agli bhasha
                // khud aazmao, user ko phir tap na karna pade.
                // (Ye pehle isliye: tez aaya NO_MATCH bhi cascade
                // hi deserve karta hai, Vosk-fail wala raasta nahi.)
                if ((sys.lastErrorCode == SpeechRecognizer.ERROR_NO_MATCH ||
                        sys.lastErrorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) &&
                    advanceCascade()
                ) {
                    return
                }
                // v35: recognizer SERVICE hi kharab hai ya sunna
                // shuru hote hi fail ho gaya (OriginOS par aksar
                // hota hai) — to is session ke liye Vosk offline
                // model par gir jao, haar karke rukna nahi.
                if ((sys.lastErrorCode == SpeechRecognizer.ERROR_CLIENT ||
                        sys.lastErrorWasImmediate) &&
                    !systemStartFallbackUsed &&
                    prefs.mode != JarvisMode.OFFLINE &&
                    modelManager.isModelReady(ModelLanguage.HINDI)
                ) {
                    startVoskFallback()
                    return
                }
            }
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
                startVoskFallback()
                return
            }
            // Error ke saath session khatm — mic wake service ko
            // wapas kar do. (Fallback wale raaste par naya session
            // shuru hote hi khud pause bhej dega.)
            resumeWakeService()
            statusText.text = message
        }
    }

    /**
     * v35: system recognizer is session ke kaam ka na ho to
     * Vosk (Hindi offline) par gir kar sunna phir shuru karo.
     * Vyavhaar purana (v1.3 wala) hi hai — yahan sirf ek
     * jagah bandha hai taaki do raaste (turant-fail aur
     * start-fail) ek hi kaam karein. Ek session me ek hi
     * baar hota hai ([systemStartFallbackUsed]).
     */
    private fun startVoskFallback() {
        systemStartFallbackUsed = true
        // Don't let Jarvis hear its own voice (purana startListening wala hi dhyaan).
        speaker.stop()
        engine?.destroy()
        engine = SpeechEngineFactory.createVoskOrSystem(
            this@MainActivity, modelManager
        )
        engine?.listener = engineListener
        updateStatus()
        // Naya engine = nayi kataar; phir seedha sunna shuru.
        // (startListening() nahi bulate — woh user ke naye
        // turn wala reset hai, ye usi turn ka badlav hai.)
        resetCascade()
        politeFailureSpokenThisTurn = false
        engineStartedOk = false
        engine?.startListening()
    }

    /**
     * v35: cascade ki kataar.
     * v51 (item 5): kataar ab aakhiri saamne-wali
     * shaksiyat se shuru hoti hai — Maya se baat chal
     * rahi ho to pehla kaan hi-IN ka (v50 tak pasand
     * wali bn-IN pehle aakar Maya ki baat bhi Bangla
     * kaan se sun leti thi aur wahi text pakka maan
     * liya jata tha)। Shaksiyat bandhi hi na ho to
     * purana bartao: pasand pehle, phir bn-IN, hi-IN।
     */
    private fun resetCascade() {
        cascadeLanguages = CascadeOrder.languagesFor(
            lastForegroundPersona(), prefs.listenLanguage
        )
        cascadeIndex = 0
        (engine as? SystemSpeechEngine)?.languageOverride =
            cascadeLanguages.firstOrNull()
    }

    /**
     * v35: kataar ki agli bhasha par jaakar sunna phir shuru
     * karo. Kataar khatm ho (ya engine system wala na ho) to
     * false — bulane wala aam error wala raasta lega.
     */
    private fun advanceCascade(): Boolean {
        val sys = engine as? SystemSpeechEngine ?: return false
        if (cascadeIndex >= cascadeLanguages.lastIndex) return false
        cascadeIndex++
        // v51 (item 6a): kataar badalkar phir sunna bhi
        // isi turn ka hissa hai — mic ka daava taaza rakho।
        MicArbiter.claim(this, MicArbiter.OWNER_FOREGROUND)
        val lang = cascadeLanguages[cascadeIndex]
        sys.languageOverride = lang
        statusText.text = when (lang) {
            "bn-IN" -> "শোনা গেল না — এবার বাংলায় চেষ্টা করছি..."
            "hi-IN" -> "শোনা গেল না — এবার হিন্দিতে চেষ্টা করছি..."
            else -> "শোনা গেল না — আবার চেষ্টা করছি..."
        }
        engineStartedOk = false
        engine?.startListening()
        return true
    }

    /**
     * v35: saamne wale sunne se jo asal me suna gaya, use
     * jeetne wali bhasha ke saath jarvis_diag me likho —
     * konsi bhasha kaam aayi, maidan ki report bina iske
     * adhoori rehti thi. Screen wali "Aapne kaha" line
     * processCommand pehle ki tarah hi lagata hai.
     */
    private fun logForegroundHeard(text: String) {
        val lang = when (val e = engine) {
            is SystemSpeechEngine -> e.currentLanguage.ifBlank { "system" }
            is VoskSpeechEngine -> "vosk-hindi"
            else -> "?"
        }
        try {
            getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE).edit()
                .putString("last_foreground_heard", "$lang: $text")
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai — asal kaam isse nahi rukta.
        }
        // v51 (item 1): raaste wali report me bhi bandho
        // (fg- wala naam — background wali se alag dikhe)।
        saveFgHearPath(text, gateReason = null)
    }

    /**
     * v51 (item 1): saamne wale sunne ke raaste ki
     * report — wake service wali hi "last_hear_path"
     * chaabi, "fg-" wala naam taaki dono raaste ek hi
     * line par pehchane jayein kiski baari thi। Natija
     * ho to [text] ke saath, error ho to engine ke
     * bandhe code ke saath, gate ne roka ho to
     * [gateReason] ke saath।
     */
    private fun saveFgHearPath(text: String?, gateReason: String?) {
        try {
            val sb = StringBuilder()
            when (val e = engine) {
                is SystemSpeechEngine -> {
                    sb.append("fg-system")
                    val lang = e.currentLanguage
                    if (lang.isNotBlank()) sb.append(' ').append(lang)
                    if (text == null && gateReason == null) {
                        sb.append(" err=").append(e.lastErrorCode)
                            .append(" imm=").append(e.lastErrorWasImmediate)
                    }
                }
                is VoskSpeechEngine -> sb.append("fg-vosk hi-IN")
                else -> sb.append("fg-?")
            }
            if (gateReason != null) {
                sb.append(" gate:rejected(").append(gateReason).append(')')
            }
            if (!text.isNullOrBlank()) {
                sb.append(": ").append(text.trim())
            }
            getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE).edit()
                .putString("last_hear_path", sb.toString())
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai — asal kaam isse nahi rukta.
        }
    }

    /** v51 (item 2): saamne wale gate ke pehchan shabd — sikhaye hue shabd + seekhe kaamon ke naam। */
    private fun fgGateExtraAccepted(): List<String> {
        val out = mutableListOf<String>()
        try {
            out.addAll(TaughtCommands.readAll(this).map { it.trigger })
        } catch (t: Throwable) {
            // Na milein to gate apne niyam par chalega.
        }
        try {
            out.addAll(LearnedRoutines.readAll(this).map { it.name })
        } catch (t: Throwable) {
            // Na milein to gate apne niyam par chalega.
        }
        return out
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
        // v35: naya sunna = naya turn — bhasha cascade kataar
        // shuru se banao, aur "samajh nahi aaya" ke ek-baar
        // wale bole jawab ki ginati bhi taaza karo.
        resetCascade()
        politeFailureSpokenThisTurn = false
        // v51 (item 2): gate wala hisaab bhi har naye
        // turn par taaza।
        fgGateRestarts = 0
        // v51 (item 6a): mic ka daava pehle bandh do
        // (arbiter) aur wake service ko chal raha
        // COMMAND session bhi rokne ko kaho — sirf
        // wake sunna rokna (purana PAUSE) kaafi nahi
        // tha: beech session me tap karne par dono
        // recognizer takrakar bip chakra banta tha।
        // Sunna sach me shuru hone par PAUSE wala
        // purana bandobast bhi chalega hi
        // (onListeningStateChanged)।
        MicArbiter.claim(this, MicArbiter.OWNER_FOREGROUND)
        if (prefs.wakeServiceRunning) {
            try {
                startService(JarvisWakeService.yieldIntent(this))
            } catch (e: Exception) {
                // Service nahi mila — saamne wala sunna phir bhi try karo.
            }
        }
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
        // v51 (item 6a): mic ka daava har raaste par
        // chhodo — ye function sunne ke har ant par
        // (natija / error / screen peeche) chalta hai।
        MicArbiter.release(this, MicArbiter.OWNER_FOREGROUND)
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
        // v34: wake-start aur notification permission ke jawab
        // ab WakeSetupActivity me bante hain (wahi se maange
        // jate hain) — yahan sirf mic + contacts bache hain.
        if (requestCode == REQUEST_READ_CONTACTS) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Toast.makeText(
                    this,
                    "Contacts permission nahi mila — naam bolkar " +
                        "call karne wala kaam nahi chalega; " +
                        "baaki sab chalega.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ------------------------------------------------------------------
    // Command handling
    // ------------------------------------------------------------------

    /**
     * v52: SEND BUTTON — box me likha hua hukm bhejne
     * ka EK hi raasta: button dabao ya "সেন্ড করো"
     * bolo — dono yahi se jate hain, isliye bilkul
     * wahi hota hai jo button dabane par hota hai।
     * Box PEHLE khaali karke phir [processCommand]
     * bulaya jata hai — taaki box me "সেন্ড করো"
     * likha ho to bhi dobara send na baje (warna
     * bheja hua hukm khud hi send hukm bankar
     * phir se dabega)। Box khaali ho to false,
     * kuch bheja hi nahi।
     */
    private fun submitTypedCommand(): Boolean {
        val typed = commandInput.text.toString()
        if (typed.isBlank()) return false
        commandInput.text.clear()
        processCommand(typed)
        return true
    }

    /**
     * v53: API Keys wale button par kitni key set hain,
     * wohi dikhao — "🔑 API Keys (2/6 সেট)"। Key ka
     * text kabhi dikhta nahi।
     */
    private fun refreshApiKeysButton() {
        try {
            if (!::apiKeysButton.isInitialized) return
            val done = ProviderKeys.configuredCount(this)
            val total = ProviderKeys.Provider.values().size
            apiKeysButton.text = "🔑 API Keys ($done/$total সেট)"
        } catch (t: Throwable) {
            // Button ka label kabhi asal kaam na roke.
        }
    }

    /**
     * v53: history wali list taza karo — aakhiri 5 hukm
     * chhote button bankar; tap karte hi wohi hukm
     * [processCommand] se phir chalega। Koi gadbad ho
     * to list chup-chaap khaali — asal kaam kabhi na ruke।
     */
    private fun refreshHistoryUi() {
        try {
            if (!::historyList.isInitialized) return
            historyList.removeAllViews()
            val items = CommandHistory.list(this).take(CommandHistory.SHOWN)
            for (item in items) {
                val button = Button(this)
                button.text = item
                button.textSize = 13f
                button.isSingleLine = true
                button.ellipsize = android.text.TextUtils.TruncateAt.END
                button.setOnClickListener { processCommand(item) }
                historyList.addView(button)
            }
            historyClearButton.visibility =
                if (items.isEmpty()) View.GONE else View.VISIBLE
        } catch (t: Throwable) {
            // History ki list kabhi asal kaam na roke.
        }
    }

    private fun processCommand(text: String) {
        // v50: SHUTDOWN yahan bhi sabse pehle — background
        // wala hi niyam: "শাটডাউন" bolte/likhte hi baat
        // band, aur KUCH BOLA NAHI jayega (screen par
        // chup-chaap band wala sandesh dikhega, bas)।
        // Latka pushti-sawal ho to use saaf inkaar
        // dekar agent ko chhodo — dwaar fail-closed।
        if (SessionCommands.isShutdownCommand(text)) {
            if (ScreenConfirm.hasPending()) {
                ScreenConfirm.deliverAnswer("না")
            }
            try {
                speaker.stop()
            } catch (t: Throwable) {
                // Boli rokna bonus hai — band hona asal hai.
            }
            recognizedText.text = "Aapne kaha: $text"
            responseText.text = "Jarvis: (শাটডাউন — চুপচাপ বন্ধ)"
            return
        }
        // v37: screen-agent ka sawal latka ho (send ki
        // pushti / ask_user) to ye baat USI ka jawab hai —
        // naya command nahi. Jawab pul ko saunp do; agent
        // apni daud wahi se aage badhayega ya imaandaari
        // se rok dega. (Likhkar bheja gaya jawab bhi yahi
        // se aata hai — send button bhi processCommand hi
        // bulata hai.)
        if (ScreenConfirm.deliverAnswer(text)) {
            recognizedText.text = "Aapne kaha: $text"
            responseText.text =
                "Maya: jawab mil gaya — screen wala kaam aage badh raha hai..."
            return
        }
        // v53: COMMAND HISTORY — naya hukm yaad rakho
        // (shutdown aur latke sawal ka jawab nahi —
        // woh upar pehle hi pakde gaye)।
        CommandHistory.record(this, text)
        refreshHistoryUi()
        recognizedText.text = "Aapne kaha: $text"
        // v48: baat kisi shaksiyat ko NAAM lekar shuru
        // ho ("মায়া, ..." / "তারা, ...") to naam
        // hatakar asal hukm par chalo, aur is baar ke
        // online jawab ki bhasha usi shaksiyat ki
        // pakki bhasha rahegi (Maya = Hindi, Tara =
        // Bangla; neeche askAgentBrain tak yahi jata
        // hai). Naam na mile to sab bilkul pehle jaisa.
        val vocative = PersonaVocative.stripVocative(text)
        val commandText = vocative?.second ?: text
        val vocativeLang = vocative?.first?.spokenLang()
        // v51 (item 5): shaksiyat bandhi ho to yaad
        // rakho — agli baar saamne wala sunna usi
        // shaksiyat ki bhasha se shuru hoga (kataar
        // ka sir)। Likha hua hukm bhi yahi raasta
        // leta hai, isliye naam-pukar likhkar dene
        // par bhi bandh jayega।
        if (vocative != null) {
            prefs.lastForegroundPersonaName = vocative.first.name
        }
        // v52: SEND BUTTON WALA HUKM — "সেন্ড করো" /
        // "কমান্ড পাঠাও" bolte hi apna hi send button
        // dabega: box me jo likha hai wohi bheja jayega
        // (button dabane wala hi raasta —
        // [submitTypedCommand])। Box khaali ho to
        // imaandaar jawab, aur kuch nahi। Baat
        // mastishk (online) tak jayegi hi nahi —
        // isliye "safety rules" wala manaahi ab
        // kabhi aayega nahi। Screen-agent ka sawal
        // latka ho to woh upar pehle hi pakda gaya
        // hai — ye baat uska jawab nahi, naya hukm
        // hai। Bheja gaya hukm khud [processCommand]
        // se hokar chalega — uska jawab wahi bolega।
        if (SendButtonCommands.isSendCommand(commandText)) {
            if (!submitTypedCommand()) {
                val emptyLine = SendButtonCommands.emptyReply(
                    vocativeLang ?: PlayIntent.requestLanguage(commandText)
                )
                politeFailureSpokenThisTurn = false
                responseText.text = "Maya: $emptyLine"
                speaker.speak(emptyLine)
            }
            return
        }
        // v53: HISTORY WALE HUKM — "আবার করো" (aakhiri
        // hukm phir chalao) aur "ইতিহাস মুছে দাও"।
        // Dono offline; mastishk tak jayenge hi nahi।
        if (HistoryCommands.isRepeatCommand(commandText)) {
            val last = HistoryCommands.lastRepeatable(this)
            if (last == null) {
                val line = HistoryCommands.emptyReply(
                    vocativeLang ?: PlayIntent.requestLanguage(commandText)
                )
                politeFailureSpokenThisTurn = false
                responseText.text = "Maya: $line"
                speaker.speak(line)
            } else {
                processCommand(last)
            }
            return
        }
        if (HistoryCommands.isClearHistoryCommand(commandText)) {
            CommandHistory.clear(this)
            refreshHistoryUi()
            val line = HistoryCommands.clearedReply(
                vocativeLang ?: PlayIntent.requestLanguage(commandText)
            )
            politeFailureSpokenThisTurn = false
            responseText.text = "Maya: $line"
            speaker.speak(line)
            return
        }
        // v49: SEEKHNE WALE PAKKE HUKM — wake service
        // wala hi ek object ([LearnCommands]) yahan
        // bhi: "মনে রেখো" aakhiri SAFAL screen-kaam
        // ko bandhta hai (asafal par imaandaar
        // manaahi — asafal chaal kabhi nahi seekhi
        // jati), "শিখে নাও: …" bolkar sikhayi
        // chaal, "কী কী শিখেছ" bandhe naamon ki
        // suchi, naam wala "ভুলে যাও" ek routine
        // mitata hai। Baat seekhne wali na ho to
        // null — aam raasta bilkul pehle jaisa
        // chalega (tathya wala "মনে রেখো, <baat>"
        // brain ke remember_fact par hi jayega,
        // [LearnCommands] ke bandhe niyam se)।
        // Bhasha: naam-pukar wali shaksiyat ki,
        // warna hukm ki apni।
        // v50: SIKHANE WALE NAYE HUKM (wake service
        // wala hi ek object, [TeachCommands]) —
        // naam bandhna ("আমার নাম …") aur
        // shabd-kaam sikhana ("'লাইট' বললে …")।
        // Likha hua hukm bhi yahin se jata hai —
        // type karke sikhana/naam bandhna bhi
        // chalega। Sirf apne saaf roop pakadta
        // hai; baaki [LearnCommands] ka hai।
        TeachCommands.tryHandleAdmin(
            this, commandText,
            vocativeLang ?: PlayIntent.requestLanguage(commandText)
        )?.let { teachLine ->
            politeFailureSpokenThisTurn = false
            responseText.text = "Maya: $teachLine"
            speaker.speak(teachLine)
            return
        }
        // v52: KAAM KE NIYAM — "নিয়ম মনে রাখো: যখন
        // … তখন …" (ya "মনে রাখো, যখন … তখন …") se
        // niyam bandhna, "কী কী নিয়ম মনে আছে" se
        // suchi, "… ভুলে যাও" se ek niyam mitana।
        // Sirf saaf niyam wale roop pakadta hai;
        // baaki [LearnCommands] ka hai — isliye
        // [LearnCommands] se PEHLE bulaya jata hai,
        // par bina apne roop ke null dekar raasta
        // chhod deta hai (tathya wala "মনে রেখো,
        // <baat>" mastishk ke remember_fact par hi
        // jayega, bilkul pehle jaisa)।
        RuleCommands.tryHandleAdmin(
            this, commandText,
            vocativeLang ?: PlayIntent.requestLanguage(commandText)
        )?.let { ruleLine ->
            politeFailureSpokenThisTurn = false
            responseText.text = "Maya: $ruleLine"
            speaker.speak(ruleLine)
            return
        }
        LearnCommands.tryHandle(
            this, commandText,
            vocativeLang ?: PlayIntent.requestLanguage(commandText)
        )?.let { learnLine ->
            politeFailureSpokenThisTurn = false
            responseText.text = "Maya: $learnLine"
            speaker.speak(learnLine)
            return
        }
        // v50: SIKHAYA HUA SHABD-KAAM — baat bandhe
        // shabd ka akela roop ho ("লাইট") to kaam
        // seedha offline, mastishk se pehle (wake
        // service wala hi niyam)। Bandha kaam
        // screenshot ho to chalana background wale
        // pakke raaste se hi hoga (capture ka
        // intezaar main thread par nahi ho sakta —
        // neeche wala screenshot niyam), isliye
        // yahan sirf milan jaancha jata hai, chalana
        // wahan hota hai।
        val taughtMapping = TaughtCommands.matchTrigger(this, commandText)
        if (taughtMapping != null &&
            taughtMapping.action == TaughtCommands.ACTION_SCREENSHOT
        ) {
            runDirectScreenshot(commandText)
            return
        }
        if (taughtMapping != null) {
            val taughtExec = TaughtCommands.execute(
                this, commandEngine, taughtMapping,
                vocativeLang ?: PlayIntent.requestLanguage(commandText)
            )
            politeFailureSpokenThisTurn = false
            responseText.text = "Jarvis: ${taughtExec.result.responseText}"
            speaker.speak(taughtExec.result.responseText)
            return
        }
        // v52: KAAM KE NIYAM — baat kisi bandhe
        // niyam ki shart se mile to uska kaam pehle
        // chup-chaap chalega (screenshot wala niyam
        // saamne wali screen par nahi — woh
        // background wale raaste se chalega), phir
        // asal hukm apne aam raaste se chalega।
        // Return NAHI hota — niyam lagna asal hukm
        // ko rokta nahi, usse pehle kaam karwata hai।
        RuleCommands.tryApplyRule(
            this, commandEngine, commandText,
            vocativeLang ?: PlayIntent.requestLanguage(commandText),
            allowScreenshot = false
        )
        // v38: pakka scroll hukm — agent se pehle pakke
        // offline raaste se (wake service wala hi niyam):
        // "স্ক্রল ডাউন করো" bolte hi Accessibility service
        // swipe karegi; mastishk ke chakkar me nahi padegi,
        // aur likhkar bheja gaya hukm bhi yahi se jata hai.
        commandEngine.deterministicScrollCommand(commandText)?.let { scrollResult ->
            politeFailureSpokenThisTurn = false
            responseText.text = "Jarvis: ${scrollResult.responseText}"
            speaker.speak(scrollResult.responseText)
            return
        }
        // v40: pakka SCREENSHOT hukm bhi agent se pehle
        // pakke offline raaste se (scroll wala hi niyam).
        // Capture ka bandha intezaar MAIN thread par NAHI
        // ho sakta — tasveer ka jawab service ke
        // main-executor se aata hai, main thread usi
        // latch par atka to jawab milega hi nahi. Isliye
        // koshish background executor par chalti hai
        // (runDirectScreenshot dekho), natija mainHandler
        // se wapas aakar bola/dikhaya jata hai.
        if (commandEngine.isScreenshotCommand(commandText)) {
            runDirectScreenshot(commandText)
            return
        }
        // v36: play-iraade wala sanyojit hukm sabse pehle —
        // "ইউটিউব খুলে কিশোর কুমারের গান চালাও" ko offline
        // app-list sirf "youtube" dekhkar nigal jati thi
        // (sirf app khulta tha, gaana kabhi nahi). Play
        // kriya + media/YouTube ka zikr ho aur query bachi
        // ho to kaam play wala hi hai — neeche wala
        // handlePlayRequest dekho. Query khaali ho ("YouTube
        // chalao") to aam raasta hi chalega (sirf app
        // khulega, pehle jaisa).
        val playQuery = PlayIntent.extractPlayQuery(commandText)
        if (!playQuery.isNullOrEmpty()) {
            handlePlayRequest(commandText, playQuery, vocativeLang)
            return
        }
        // v48: LINK wala kaam (URL + kholne/browser ka
        // iraada) baatchit nahi hai — pakka screen_task
        // hai. Live test me poora GitHub URL bolne par
        // baatchit wala mastishk sirf baat banakar reh
        // gaya tha, screen chali hi nahi thi. Isliye
        // agent se pehle ye pakki jaanch (PlayIntent
        // ke baad — gaane wala iraada pehle jeetta
        // hai): kaam seedha screen_task auzaar ko,
        // user ke apne shabdon me, URL akshar-akshar
        // jaisa suna waisa hi (UrlIntent dekho).
        val urlTask = UrlIntent.extractUrlTask(commandText)
        if (urlTask != null) {
            runDirectScreenTask(commandText, urlTask, vocativeLang)
            return
        }
        // v25: online brain uplabdh ho (mode Offline na ho,
        // key set ho, net chalu ho) to pehle AGENT — app ke
        // andar EK hi mastishk (user ka pakka design):
        // Gemini poori baat samajhta hai, online kadam khud
        // leta hai aur phone wale kadam offline auzaaron se
        // karwata hai, jab tak poora kaam khatm na ho jaye.
        // Round 1 par koi model hi jawab na de to callback
        // me null aayega aur neeche wala purana offline
        // raasta bilkul pehle jaisa chalega.
        if (geminiBrainAllowed()) {
            responseText.text = "Maya soch rahi hai — kaam kar rahi hai..."
            askAgentBrain(commandText, vocativeLang)
            return
        }
        processCommandOffline(commandText, vocativeLang = vocativeLang)
    }

    /**
     * Offline raasta — v25 se pehle wala hi (adda pehle,
     * phir CommandEngine), byte-for-byte waisa hi. Mode
     * Offline ho, key na ho, net na ho, ya agent ko round 1
     * par koi model hi na mila ho — tab yahi chalega.
     * (Purana beech-wala Gemini chat ab nahi hai — agent
     * uski jagah le chuka hai; do online raaste nahi bache.)
     *
     * v35: [agentAlreadyTried] batata hai ki is baat par
     * Gemini agent pehle hi ja chuka hai (aur kuch nahi
     * mila) — taaki neeche wala agent-raasta use dobara na
     * bulaye (loop nahi bane).
     */
    private fun processCommandOffline(
        text: String,
        agentAlreadyTried: Boolean = false,
        vocativeLang: PlayIntent.SpokenLang? = null
    ) {
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
        if (result.handled) {
            // Kaam ho gaya — agle turn ke liye "samajh nahi
            // aaya" ki ek-baar-wali ginati taaza.
            politeFailureSpokenThisTurn = false
            responseText.text = "Jarvis: ${result.responseText}"
            speaker.speak(result.responseText)
            return
        }
        // v35: offline engine is baat ko kisi command se nahi
        // jod paya. Key set ho + net chalu ho aur is baat par
        // agent abhi tak na gaya ho, to kachi baat seedha
        // Gemini agent ke paas bhejo — "samajh nahi aaya" par
        // rukna hi dead end tha.
        if (!agentAlreadyTried && geminiBrainAllowed()) {
            responseText.text = "Maya soch rahi hai — kaam kar rahi hai..."
            askAgentBrain(text, vocativeLang)
            return
        }
        // v36: agent is baat par gaya tha aur saare model
        // 429 (quota) par mare — to aam "nahi kar sakta"
        // wala jawab jhooth hoga; sach ye hai ki online
        // seema khatm hai. Wahi bolo, user ki bhasha me.
        // (agentAlreadyTried ki shart isliye: kataar ka
        // natija ISI baat ki taaza daud ka hai, purani
        // band report ka nahi.)
        if (agentAlreadyTried && GeminiBrain.lastChainWasQuota(this)) {
            val line = PlayIntent.quotaReply(PlayIntent.requestLanguage(text))
            politeFailureSpokenThisTurn = true
            responseText.text = "Maya: $line"
            speaker.speak(line)
            return
        }
        // v35: offline / bina key ki vinamr haar — par ek turn
        // me awaaz se zyada se zyada EK baar; dobara ho to
        // sirf screen par dikhega. Saath me shune wali bhasha
        // jaanchne / likhkar bhejne ka ishara bhi — screen par
        // Bangla me, awaaz me usi ka Hindi roop (ek boli me ek
        // hi script saaf padhti hai).
        responseText.text = "Jarvis: ${result.responseText}\n" +
            "(শোনার ভাষা ঠিক আছে কিনা দেখুন — না হলে লিখে পাঠান।)"
        if (!politeFailureSpokenThisTurn) {
            politeFailureSpokenThisTurn = true
            speaker.speak(
                result.responseText +
                    " सुनने की भाषा जाँच लीजिए, या लिखकर भेजिए।"
            )
        }
    }

    // ------------------------------------------------------------------
    // Phase 3 (v21): Gemini online brain — key rakhna + fallback
    // ------------------------------------------------------------------

    /**
     * v22: Key save dabane par pehle JAANCH hoti hai — Gemini
     * se halki si puchh karke dekha jata hai ki likhi hui key
     * sach me chalti hai ya nahi. Jaanch pass ho tabhi key
     * band hoti hai, aur Maya bolkar + status me dikhakar
     * batati hai ki online buddhi taiyaar hai. Fail ho to
     * kuch save NAHI hota, likha hua khane me hi rehta hai
     * aur wajah (galat key / net band) status line par aur
     * awaaz se — dono se — saaf milti hai.
     */
    private fun onGeminiKeySaveClicked() {
        val typed = geminiKeyInput.text.toString().trim()
        if (typed.isEmpty()) {
            Toast.makeText(this, "Pehle khane me key likho.", Toast.LENGTH_SHORT).show()
            return
        }
        geminiKeySaveButton.isEnabled = false
        geminiKeyStatusText.text = "Key जांच हो रही है..."
        GeminiBrain.validateKey(typed) { ok, code ->
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                geminiKeySaveButton.isEnabled = true
                if (ok) {
                    GeminiBrain.setKey(this, typed)
                    geminiKeyInput.text.clear()
                    refreshGeminiKeyUi()
                    updateStatus()
                    speaker.speak(
                        "API key मिल गया है। अब मैं online सवालों के जवाब भी दे सकती हूँ।"
                    )
                } else {
                    geminiKeyStatusText.text = if (code == -1) {
                        "Key: जांच नहीं हो पाई — net check karo ✗"
                    } else {
                        "Key: galat hai ✗ (error $code)"
                    }
                    speaker.speak("यह key काम नहीं कर रही, एक बार फिर देखकर डालो।")
                }
            }
        }
    }

    /**
     * Key ki haalat sirf haan/na me dikhao — key ka text screen
     * par kabhi wapas nahi aata. Key set ho to khana khaali
     * rahega; badalna ho to naya key likhkar save karna hoga.
     */
    private fun refreshGeminiKeyUi() {
        if (!::geminiKeyStatusText.isInitialized) return
        if (GeminiBrain.isConfigured(this)) {
            geminiKeyStatusText.text = "Key: set hai ✓ — online buddhi taiyaar"
            geminiKeyInput.hint = "Key set hai — badalna hai to naya key likho"
        } else {
            geminiKeyStatusText.text = "Key: nahi hai"
            geminiKeyInput.hint = "Yahan apna Gemini API key likho"
        }
        refreshGeminiDiag()
    }

    /**
     * v24: "Online koshish (aakhiri)" line ko SharedPreferences
     * "jarvis_diag" se taza karo — kaun sa model kitni der me
     * kya bola (OK / HTTP code / timeout / net). GeminiBrain
     * report likhta hai; wake service se hui koshishen bhi
     * yahin dikhti hain kyunki prefs file ek hi hai. Ye line
     * refreshGeminiKeyUi ke saath aur refreshDiagLines
     * (onResume + har 2 s) ke saath taza hoti hai.
     */
    /** v33: "শোনার ভাষা" button ka label — AppPrefs wali pasand dikhao. */
    private fun refreshListenLangButton() {
        if (!::listenLangButton.isInitialized) return
        listenLangButton.text = when (prefs.listenLanguage) {
            "hi-IN" -> "শোনার ভাষা: हिन्दी (বদলাতে ট্যাপ করো)"
            "en-IN" -> "শোনার ভাষা: English (বদলাতে ট্যাপ করো)"
            else -> "শোনার ভাষা: বাংলা (বদলাতে ট্যাপ করো)"
        }
        // v35: wahi pasand button ke upar chhoti label me bhi —
        // galat bhasha lagi ho to bolne se pehle hi dikh jaye.
        if (::listenLangLabel.isInitialized) {
            listenLangLabel.text = when (prefs.listenLanguage) {
                "hi-IN" -> "শোনার ভাষা: हिन्दी"
                "en-IN" -> "শোনার ভাষা: English"
                else -> "শোনার ভাষা: বাংলা"
            }
        }
    }

    private fun refreshGeminiDiag() {
        if (!::geminiDiagText.isInitialized) return
        val attempt = try {
            getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                .getString("last_gemini_attempt", null)
        } catch (t: Throwable) {
            null
        }
        geminiDiagText.text = if (attempt.isNullOrBlank()) {
            "Online koshish (aakhiri): abhi tak koi koshish nahi"
        } else {
            "Online koshish (aakhiri): $attempt"
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
     * v25: sanyojit agent chalao — auzaar is screen ke
     * CommandEngine se bandhe hain (saamne wala raasta,
     * overlay bridge nahi). Agent ka aakhiri jawab hi
     * Maya ka jawab hai: dikhao + bolo + baatchit yaad me
     * jodo. Jawab null ho (round 1 par koi model hi nahi
     * mila) to purana offline raasta (processCommandOffline)
     * — bilkul pehle jaisa. Callback AgentBrain ke thread
     * par aata hai; UI ka saara kaam main thread par.
     * (v21 wala askGeminiBrain/chat raasta ab nahi hai —
     * agent uski jagah le chuka hai.)
     */
    /**
     * v36: [onAgentUnavailable] — agent ko round 1 par koi
     * model hi na mile (callback null) to aam haal me purana
     * offline raasta chalta hai; play wale hukm ke liye
     * bulane wala apna seedha youtube_play wala raasta deta
     * hai, taaki gaana Gemini ke jeene par na atke.
     */
    private fun askAgentBrain(
        text: String,
        replyLanguage: PlayIntent.SpokenLang? = null,
        onAgentUnavailable: (() -> Unit)? = null
    ) {
        val tools = AgentTools(this, commandEngine, fromBackgroundService = false)
        AgentBrain.run(
            this, geminiHistory.toList(), text, tools::execute, replyLanguage
        ) { reply ->
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (reply == null) {
                    if (onAgentUnavailable != null) {
                        onAgentUnavailable()
                    } else {
                        // v35: agent is baat par ja chuka hai — offline
                        // raasta use dobara nahi bulayega.
                        processCommandOffline(
                            text,
                            agentAlreadyTried = true,
                            vocativeLang = replyLanguage
                        )
                    }
                    return@post
                }
                // v42: prastuti wali chauki — jawab protocol
                // JSON bankar aaya ho to screen/TTS par sirf
                // uski "say" line jayegi, kaccha payload kabhi
                // nahi (v41 ke live test wala leak). Yaad me
                // bhi wahi saaf roop bandhta hai.
                // v48: naam-pukar wali baat ho to haar-line
                // ki bhasha bhi usi shaksiyat ki hogi.
                val safeReply = ReplyGuard.sanitize(
                    reply, replyLanguage ?: PlayIntent.requestLanguage(text)
                )
                geminiHistory.add("user" to text)
                geminiHistory.add("model" to safeReply)
                while (geminiHistory.size > MAX_GEMINI_HISTORY_ENTRIES) {
                    geminiHistory.removeAt(0)
                    geminiHistory.removeAt(0)
                }
                responseText.text = "Maya: $safeReply"
                speaker.speak(safeReply)
            }
        }
    }

    /**
     * v36: play-iraade wala hukm ("ইউটিউব খুলে ... গান
     * চালাও"). Mastishk uplabdh ho to pehle agent — woh
     * poori baat samajhkar youtube_play auzaar khud
     * bulayega. Agent uplabdh hi na ho (key nahi / mode
     * Offline / net nahi) ya round 1 par mar jaye (saare
     * model 429 samet), to seedha youtube_play auzaar —
     * gaana bajna Gemini ke jeene par kabhi nahi atkega.
     */
    private fun handlePlayRequest(
        text: String,
        query: String,
        replyLanguage: PlayIntent.SpokenLang? = null
    ) {
        if (geminiBrainAllowed()) {
            responseText.text = "Maya soch rahi hai — gaana chala rahi hai..."
            askAgentBrain(text, replyLanguage) { runDirectYouTubePlay(text, query) }
            return
        }
        runDirectYouTubePlay(text, query)
    }

    /**
     * v36: bina mastishk ke seedha youtube_play — v26/v33
     * wali wahi mashinari (results kholo, pehle asli video
     * par tap, watch page ki jaanch) AgentTools se, apne
     * background thread par (tap ka intezaar UI ko nahi
     * rokta). Bola jaane wala natija PlayIntent se user ki
     * bhasha me aur hamesha sachha — chal gaya / pakka
     * nahi / khula hi nahi.
     */
    private fun runDirectYouTubePlay(text: String, query: String) {
        responseText.text = "Maya: '$query' chala rahi hoon..."
        background.execute {
            val tools = AgentTools(this, commandEngine, fromBackgroundService = false)
            val result = try {
                tools.execute("youtube_play", JSONObject().put("query", query))
            } catch (t: Throwable) {
                AgentTools.ToolResult(false, "FAILED — auzaar tak pahunch nahi payi.")
            }
            PlayIntent.saveDirectPlayDiag(this, query, result)
            val line = PlayIntent.playOutcomeReply(
                result, query, PlayIntent.requestLanguage(text)
            )
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                politeFailureSpokenThisTurn = false
                responseText.text = "Maya: $line"
                speaker.speak(line)
            }
        }
    }

    /**
     * v48: bina mastishk ke seedha screen_task — LINK
     * wala kaam ([UrlIntent]) pakke taur par yahin
     * aata hai: kaam user ke apne shabdon me (URL
     * akshar-akshar jaisa suna waisa) screen_task
     * auzaar ko, apne background thread par (kadmon
     * ke bandhe intezaar UI ko nahi rokte). Bola
     * jaane wala saar [UrlIntent.spokenSummary] se —
     * screen agent ke apne shabd milein to wohi,
     * warna natije ki imaandaar bandhi line; poora
     * varnan "Screen chaal" report me bandhta hai.
     */
    private fun runDirectScreenTask(
        text: String,
        task: String,
        vocativeLang: PlayIntent.SpokenLang?
    ) {
        responseText.text = "Maya: link wala kaam screen par kar rahi hoon..."
        background.execute {
            val tools = AgentTools(this, commandEngine, fromBackgroundService = false)
            val result = try {
                tools.execute("screen_task", JSONObject().put("task", task))
            } catch (t: Throwable) {
                AgentTools.ToolResult(false, "FAILED — auzaar tak pahunch nahi payi.")
            }
            UrlIntent.saveRouteDiag(this, result.ok)
            val line = UrlIntent.spokenSummary(
                result, vocativeLang ?: PlayIntent.requestLanguage(text)
            )
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                politeFailureSpokenThisTurn = false
                responseText.text = "Maya: $line"
                speaker.speak(line)
            }
        }
    }

    /**
     * v40: bina mastishk ke seedha screenshot — hukm
     * CommandEngine.runScreenshotCommand se, apne
     * background executor par (capture ka latch UI ko
     * nahi rokta; woh raasta main thread par waise bhi
     * chalna hi nahi chahiye — jawab service ke
     * main-executor se aata hai). Natija hukm ki bhasha
     * me aur hamesha sachha — gallery me bach gaya /
     * screen surakshit / service band.
     */
    private fun runDirectScreenshot(text: String) {
        responseText.text = "Maya: screenshot le rahi hoon..."
        background.execute {
            val result = try {
                commandEngine.runScreenshotCommand(text)
            } catch (t: Throwable) {
                CommandResult("স্ক্রিনশট নিতে পারলাম না।", true)
            }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                politeFailureSpokenThisTurn = false
                responseText.text = "Maya: ${result.responseText}"
                speaker.speak(result.responseText)
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
    // v41: Bangla offline model download (Zipformer)
    // ------------------------------------------------------------------

    private fun downloadBanglaModel() {
        if (banglaModelManager.isReady()) {
            statusText.text = "বাংলা অফলাইন মডেল আগেই তৈরি আছে।"
            refreshBanglaModelUi()
            return
        }
        if (banglaModelManager.isDownloading()) {
            statusText.text = "বাংলা অফলাইন মডেল ডাউনলোড চলছে…"
            return
        }
        statusText.text = "বাংলা অফলাইন মডেল ডাউনলোড শুরু হচ্ছে…"
        background.execute {
            banglaModelManager.download(
                object : BanglaModelManager.DownloadCallback {
                    override fun onProgress(percent: Int) {
                        mainHandler.post {
                            statusText.text =
                                "বাংলা অফলাইন মডেল ডাউনলোড হচ্ছে… $percent%"
                            refreshBanglaModelUi()
                        }
                    }

                    override fun onComplete() {
                        mainHandler.post {
                            statusText.text =
                                "বাংলা অফলাইন মডেল তৈরি — এবার ব্যাকগ্রাউন্ডে " +
                                    "বাংলা কমান্ড অফলাইনেই শুনবে।"
                            refreshBanglaModelUi()
                            updateStatus()
                        }
                    }

                    override fun onError(message: String) {
                        mainHandler.post {
                            // Malik ne khud button dabakar maanga
                            // tha — nakami saaf Bangla me bolo,
                            // chup mat raho.
                            statusText.text =
                                "বাংলা অফলাইন মডেল ডাউনলোড হয়নি — নেট চেক করে " +
                                    "আবার চেষ্টা করো।"
                            refreshBanglaModelUi()
                        }
                    }
                }
            )
        }
    }

    /**
     * v41: Bangla model wali status line + button ka
     * taaza roop — model ki asal haalat (files) aur
     * download ki bandhi hui report se banta hai.
     * refreshDiagLines ke 2-second wale chakkar se bhi
     * bulta hai, taaki download ka percent bina chhue
     * aage badhta dikhe.
     */
    private fun refreshBanglaModelUi() {
        if (!::banglaModelStatusText.isInitialized) return
        when {
            banglaModelManager.isReady() -> {
                banglaModelStatusText.text =
                    "বাংলা অফলাইন মডেল: তৈরি আছে ✓ — ব্যাকগ্রাউন্ড বাংলা " +
                        "কমান্ড এবার অফলাইনেই শুনবে"
                banglaModelButton.text = "বাংলা অফলাইন মডেল ✓ তৈরি"
            }
            banglaModelManager.isDownloading() -> {
                val pct = banglaModelManager.downloadPercent()
                banglaModelStatusText.text =
                    "বাংলা অফলাইন মডেল: ডাউনলোড হচ্ছে… $pct%"
                banglaModelButton.text = "ডাউনলোড হচ্ছে… $pct%"
            }
            banglaModelManager.lastDownloadFailed() -> {
                banglaModelStatusText.text =
                    "বাংলা অফলাইন মডেল: ডাউনলোড হয়নি — নেট চেক করে আবার চেষ্টা করো"
                banglaModelButton.text = "বাংলা অফলাইন মডেল আবার ডাউনলোড করো"
            }
            else -> {
                banglaModelStatusText.text =
                    "বাংলা অফলাইন মডেল: ডাউনলোড হয়নি (৭১ MB — একবার Wi-Fi-তে " +
                        "নামালেই হবে)"
                banglaModelButton.text = "বাংলা অফলাইন মডেল ডাউনলোড করো"
            }
        }
    }

    // ------------------------------------------------------------------
    // Report lines ka refresh (v34 se wake bandobosto Mobile
    // hub me hai; yahan sirf jarvis_diag wali reporten bachi)
    // ------------------------------------------------------------------

    private val wakeRefreshRunnable = object : Runnable {
        override fun run() {
            refreshDiagLines()
            mainHandler.postDelayed(this, WAKE_REFRESH_MS)
        }
    }

    /**
     * v34: is screen par ab sirf report wali lines taza hoti
     * hain (panel scan, YouTube tap, agent chaal, shune wali
     * bhasha, online koshish). Wake / battery / accessibility /
     * overlay ki haalat ab Mobile hub ki screenon par dikhti
     * hai (WakeSetupActivity.refreshSetupUi dekho).
     */
    private fun refreshDiagLines() {
        if (!::panelScanText.isInitialized) return
        // v23: aakhiri panel-scan ki report — tile na mile to
        // is line se pata chalta hai ki panel me asal me tha
        // kya. Report service SharedPreferences "jarvis_diag"
        // me likhti hai; kabhi koshish hi na hui ho to waisa
        // saaf likho. Ye bhi refreshDiagLines ke saath hi taza
        // hoti hai (onResume + har 2 s).
        if (::panelScanText.isInitialized) {
            val report = try {
                getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                    .getString("last_panel_scan", null)
            } catch (t: Throwable) {
                null
            }
            panelScanText.text = if (report.isNullOrBlank()) {
                "Panel scan: abhi tak koi koshish nahi"
            } else {
                "Panel scan: $report"
            }
        }
        // v33: YouTube tap aur agent chaal ki reporten bhi
        // jarvis_diag se — v32 tak ye sirf file me band thi,
        // screen par dikhti hi nahi thi.
        try {
            val diag = getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
            if (::youtubeTapText.isInitialized) {
                val tap = diag.getString("last_youtube_tap", null)
                youtubeTapText.text = if (tap.isNullOrBlank()) {
                    "YouTube tap (aakhiri): abhi tak koi koshish nahi"
                } else {
                    "YouTube tap (aakhiri): $tap"
                }
            }
            if (::agentRunText.isInitialized) {
                val run = diag.getString("last_agent_run", null)
                agentRunText.text = if (run.isNullOrBlank()) {
                    "Agent chaal (aakhiri): abhi tak koi koshish nahi"
                } else {
                    "Agent chaal (aakhiri): $run"
                }
            }
            // v37: screen-agent ki report bhi yahin se —
            // ScreenAgent "last_screen_run" bandhta hai
            // (<kaam> — <natija> (<n> steps) wale roop me).
            if (::screenRunText.isInitialized) {
                val screen = diag.getString("last_screen_run", null)
                screenRunText.text = if (screen.isNullOrBlank()) {
                    "Screen chaal (aakhiri): abhi tak koi koshish nahi"
                } else {
                    "Screen chaal (aakhiri): $screen"
                }
            }
            // v46: aakhiri screen-kaam ki audit report —
            // ScreenAudit "last_screen_audit" bandhta hai
            // (<kaam> — <n> actions, <natija> wale roop me).
            if (::screenAuditText.isInitialized) {
                val audit = diag.getString("last_screen_audit", null)
                screenAuditText.text = if (audit.isNullOrBlank()) {
                    "Screen audit (aakhiri): abhi tak koi record nahi"
                } else {
                    "Screen audit (aakhiri): $audit"
                }
            }
            // v41: Bangla offline kaan ki report bhi yahin
            // se — wake service "last_bangla_asr" bandhta
            // hai ("zipformer 840ms: ..." / EMPTY / FAIL).
            if (::banglaAsrText.isInitialized) {
                val bangla = diag.getString("last_bangla_asr", null)
                banglaAsrText.text = if (bangla.isNullOrBlank()) {
                    "Bangla ASR (aakhiri): abhi tak koi koshish nahi"
                } else {
                    "Bangla ASR (aakhiri): $bangla"
                }
            }
            // v43: aaj ka kota-hisaab — QuotaManager ki
            // apni ginati (Google ka official bacha-hua
            // kota NAHI; app ne khud kitni koshish ki).
            // Mukhya model aaj 429 se khatm ho to line
            // ke aakhir me uska nishan bhi judta hai.
            if (::quotaText.isInitialized) {
                val c = QuotaManager.counts(this)
                var line = "Quota hisaab (aaj): total ${c.total}" +
                    " | main ${c.main} | lite ${c.lite}"
                if (c.mainExhausted) {
                    line += " | main aaj sesh (429)"
                } else if (c.mainCoolingDown) {
                    // v48: din-bhar khatm NAHI — sirf thand
                    // (429 ke baad 10 minute ki rok); thand
                    // beet-te hi model phir aazmaya jayega.
                    line += " | main ektu pore (cooldown)"
                }
                quotaText.text = line
            }
            // v48: aakhiri background session ki
            // shaksiyat + ant — wake service bandhta
            // hai ("maya-hindi" / "tara-bengali" aur
            // ant ki wajah, jaise completed /
            // not-understood / redirect-bn)।
            if (::personaText.isInitialized) {
                val persona = diag.getString("last_persona", null)
                val sessionEnd = diag.getString("last_session_end", null)
                personaText.text = if (persona.isNullOrBlank()) {
                    "Persona (aakhiri): abhi tak koi session nahi"
                } else if (sessionEnd.isNullOrBlank()) {
                    "Persona (aakhiri): $persona"
                } else {
                    "Persona (aakhiri): $persona · $sessionEnd"
                }
            }
            // v49: seekhe hue kaamon ki ginati —
            // godam se taaza ginati (naam yahan
            // nahi dikhte; woh bolkar milte hain)।
            if (::learnedText.isInitialized) {
                learnedText.text =
                    "শেখা কাজ (mot): " + LearnedRoutines.count(this)
            }
            // v50: sikhaye hue shabd-kaam jodon ki
            // ginati — bandha "লাইট" jod bhi isme
            // gina jata hai।
            if (::taughtText.isInitialized) {
                taughtText.text =
                    "শেখানো কমান্ড (mot): " + TaughtCommands.count(this)
            }
            // v52: kaam ke niyamon ki ginati —
            // godam se taaza ginati (niyam bolkar
            // sunne par milte hain)।
            if (::rulesText.isInitialized) {
                rulesText.text =
                    "মনে রাখা নিয়ম (mot): " + BrainRules.count(this)
            }
            // v51 (item 1): sunne ki report wali
            // linein — background ne aakhiri kya suna,
            // saamne wali screen ne aakhiri kya suna,
            // aur sunne ka raasta (engine + bhasha +
            // error)। Teeno chaabiyan service aur ye
            // screen dono bandhte hain।
            if (::backgroundHeardText.isInitialized) {
                val bg = diag.getString("last_background_heard", null)
                backgroundHeardText.text = if (bg.isNullOrBlank()) {
                    "Background (aakhiri suna): abhi tak kuch nahi"
                } else {
                    "Background (aakhiri suna): $bg"
                }
            }
            if (::foregroundHeardText.isInitialized) {
                val fg = diag.getString("last_foreground_heard", null)
                foregroundHeardText.text = if (fg.isNullOrBlank()) {
                    "Saamne (aakhiri suna): abhi tak kuch nahi"
                } else {
                    "Saamne (aakhiri suna): $fg"
                }
            }
            if (::hearPathText.isInitialized) {
                val path = diag.getString("last_hear_path", null)
                hearPathText.text = if (path.isNullOrBlank()) {
                    "শোনার পথ (aakhiri): abhi tak koi turn nahi"
                } else {
                    "শোনার পথ (aakhiri): $path"
                }
            }
        } catch (t: Throwable) {
            // Report bonus hai — asal screen isse nahi rukti.
        }
        refreshOfflinePackHint()
        // v41: Bangla model ki haalat wali line/button bhi
        // isi chakkar me taaza rahe (download ka percent).
        refreshBanglaModelUi()
        // v37: screen-agent ka sawal latka ho to use is
        // screen par rakho — user dusri app se wapas aakar
        // jawab yahin bolkar ya likhkar de sakta hai (dono
        // processCommand se hokar pul tak jate hain).
        try {
            if (::responseText.isInitialized) {
                val question = ScreenConfirm.pendingQuestion()
                if (question != null &&
                    !responseText.text.toString().contains(question)
                ) {
                    responseText.text = "Maya: $question\n" +
                        "(jawab yahin boliye ya likhiye — screen wala " +
                        "kaam intezaar kar raha hai)"
                }
            }
        } catch (t: Throwable) {
            // Sawal dikhana bonus hai — asal kaam isse nahi rukta.
        }
        refreshListenLangButton()
        // v24: online koshish ki report bhi isi ke saath taza
        // karo — ye function onResume par aur har 2 s chalta
        // hai, isliye wake service se hui koshish ki report
        // bhi screen par khud dikh jayegi.
        refreshGeminiDiag()
    }

    /**
     * v51 (item 4): Hindi offline pack wali salaah
     * line ki dikhat — tab tak dikhe jab tak (a)
     * offline engine asal me safal na ho jaye
     * (service jarvis_diag me "offline_system_ok"
     * bandhta hai), ya (b) malik use khud band na
     * kar de। refreshDiagLines ke chakkar (2 s) me
     * chalti hai, isliye safalta ke baad line khud
     * chhup jati hai।
     */
    private fun refreshOfflinePackHint() {
        if (!::offlinePackHintText.isInitialized) return
        val packOk = try {
            getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                .getString("offline_system_ok", null) == "1"
        } catch (t: Throwable) {
            false
        }
        val show = !prefs.offlinePackHintDismissed && !packOk
        offlinePackHintText.visibility = if (show) View.VISIBLE else View.GONE
        try {
            findViewById<Button>(R.id.offlinePackHintClose).visibility =
                if (show) View.VISIBLE else View.GONE
        } catch (t: Throwable) {
            // Button na mile to line ki dikhat hi kaafi hai.
        }
    }

    /**
     * v29: contacts permission sirf call_person auzaar
     * ke liye hai. Ek app-start par zyada se zyada ek
     * baar maangi jati hai ([contactsPermissionRequested]
     * wala jhanda); mana ho jaye to dobara tang nahi
     * karte — auzaar khud imaandaar FAILED deta hai aur
     * app ke baaki saare kaam aam chalte hain.
     */
    private fun requestContactsPermissionIfNeeded() {
        if (contactsPermissionRequested) return
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        contactsPermissionRequested = true
        requestPermissions(
            arrayOf(Manifest.permission.READ_CONTACTS),
            REQUEST_READ_CONTACTS
        )
    }

    // v34: notification / battery / accessibility / overlay
    // wale bandobosto yahan se Mobile hub ki screenon par
    // chale gaye hain (WakeSetupActivity dekho).

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
            // v48: BEECH wala batan (teen batan wale
            // sanvaad me neutral batan beech me hi
            // dikhta hai) — update karne se PEHLE is
            // sanskaran me naya kya hai, wohi Bangla
            // varnan jo release ke saath aaya hai.
            // Update ki baaki mashinari bilkul waisi hi.
            .setNeutralButton("এই আপডেটে কী আছে") { _, _ ->
                showReleaseNotes(info)
            }
            .setNegativeButton("Baad me", null)
            .setOnDismissListener { updateDialogShowing = false }
            .show()
    }

    /**
     * v48: "এই আপডেটে কী আছে" — latest release ka
     * varnan (GitHub release body, [UpdateChecker]
     * usi releases/latest jawab se laata hai) ek
     * scroll hone wale sanvaad me dikhao. Varnan
     * khaali ho ya fetch me na aaya ho to imaandaar
     * Bangla line: বিবরণ পাওয়া যায়নি।
     */
    private fun showReleaseNotes(info: UpdateChecker.UpdateInfo) {
        if (isFinishing || isDestroyed) return
        val notes = info.notes.trim()
        val padding = (24 * resources.displayMetrics.density).toInt()
        val notesView = TextView(this).apply {
            text = if (notes.isEmpty()) {
                "এই আপডেটের বিবরণ পাওয়া যায়নি।"
            } else {
                notes
            }
            textSize = 15f
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        val scrollView = ScrollView(this).apply { addView(notesView) }
        AlertDialog.Builder(this)
            .setTitle("এই আপডেটে কী আছে")
            .setView(scrollView)
            .setPositiveButton("ঠিক আছে", null)
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
        // v22: key band ho to header me "AI abhi aaya nahi hai"
        // ki jagah saaf "Gemini taiyaar hai" — user ko dikhe ki
        // online buddhi sach me chalu hai.
        val geminiReady = GeminiBrain.isConfigured(this)
        val modeLabel = when (prefs.mode) {
            JarvisMode.OFFLINE -> "Offline"
            JarvisMode.ONLINE ->
                if (geminiReady) {
                    "Online (Gemini taiyaar hai)"
                } else {
                    "Online (AI abhi aaya nahi hai)"
                }
            JarvisMode.AUTO ->
                if (geminiReady) "Auto (Gemini taiyaar hai)" else "Auto"
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
        private const val REQUEST_READ_CONTACTS = 1004
        private const val WAKE_REFRESH_MS = 2_000L

        /** v51 (item 2): saamne wala gate ruke to bina bole zyada se zyada itni baar phir sunna. */
        private const val FG_MAX_GATE_RESTARTS = 2

        /** v21: Gemini yaad ki gehrai — 10 turn, ek turn = user+model jodi. */
        private const val MAX_GEMINI_HISTORY_ENTRIES = 20
    }
}
