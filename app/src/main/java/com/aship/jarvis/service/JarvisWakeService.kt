package com.aship.jarvis.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.aship.jarvis.MainActivity
import com.aship.jarvis.ai.AgentBrain
import com.aship.jarvis.ai.AgentTools
import com.aship.jarvis.ai.GeminiBrain
import com.aship.jarvis.chat.AddaAction
import com.aship.jarvis.chat.AddaEngine
import com.aship.jarvis.commands.CommandEngine
import com.aship.jarvis.commands.CommandResult
import com.aship.jarvis.prefs.AppPrefs
import com.aship.jarvis.prefs.JarvisMode
import com.aship.jarvis.speaker.TtsSpeaker
import com.aship.jarvis.voice.ModelLanguage
import com.aship.jarvis.voice.ModelManager
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.concurrent.Executors

/**
 * Phase 2: background listening with a wake word.
 *
 * A microphone-type foreground service that runs the offline Vosk
 * recognizer continuously, scanning everything it hears for the wake
 * word ("Jarvis" in Devanagari / Bengali / Latin script). On a wake:
 *
 *   wake session STOPPED -> ack spoken ("हाँ, बोलिए") -> command
 *   session listens for ONE utterance -> CommandEngine runs it ->
 *   reply spoken -> wake session resumes.
 *
 * Only ONE SpeechService is ever alive at a time, and sessions are
 * fully stopped before the next one starts and before TTS speaks, so
 * Jarvis never hears its own voice. All session transitions are
 * serialized on a single-thread executor; SpeechService callbacks
 * only post work there.
 *
 * The service is started and stopped ONLY from the app UI (Android
 * does not allow background starts of microphone services), and it
 * stops itself gracefully when the Hindi model is missing.
 *
 * Phase 2 fixes: app launches cross the overlay bridge (see
 * CommandEngine's background mode), unrecognized background utterances
 * are answered with silence, and a command session is hard-capped
 * (~7 s total, ~2.5 s without a word change) so noise cannot hold it.
 *
 * v1.3 mic sharing: the phone has ONE microphone. While this service
 * holds it (Vosk AudioRecord), Android refuses the foreground system
 * recognizer ("cannot record now as ASHIP JARVIS AI is recording",
 * error 7). So MainActivity asks this service to PAUSE
 * (ACTION_PAUSE_WAKE) before tap-to-speak: the wake session stops
 * and releases the mic, but the service and its notification stay
 * alive. ACTION_RESUME_WAKE — or a 12 s safety timer, in case the
 * activity dies without resuming — restarts the wake session.
 * v1.6: the timer also force-recovers a wedged ACK/COMMAND flow,
 * but never cuts a reply that is still being spoken.
 *
 * v1.5 Siri orb: on a wake, a glossy floating orb (OrbView) appears
 * at the bottom-center of the screen above other apps — breathing
 * while it waits for the command, shimmering while the reply is
 * spoken, with the reply text in a bubble above it so the user can
 * also READ the answer. It fades out when the flow returns to wake
 * listening (1.5 s after the reply). Needs the same "Display over
 * other apps" permission as the launch bridge; without it the orb
 * is skipped silently and the voice flow is unchanged.
 *
 * v12 retry-once: command samajh na aaye (ya command session bina
 * handled command ke khatm ho jaye) to Jarvis ek baar "Phir se
 * boliye" bolkar turant ek aur command session kholta hai — dobara
 * "Jarvis" bolne ki zaroorat nahi. Dusri baar bhi na samjhe to
 * purana vyavhaar: kholne ki koshish par CANT_DO_REPLY, warna
 * chup, aur wapas wake sunne par.
 *
 * v13 offline adda: Maya naam ki persona ke saath halki, saaf-suthri
 * multi-turn baatchit. Adda chalu ho to har command-session wala
 * utterance pehle AddaEngine ko jata hai; jawab ke baad TTS khatm
 * hote hi agla command session bina wake word ke khul jata hai.
 * Adda ke andar v12 retry nahi lagta — Maya ke apne nudge/goodbye
 * wale raaste hain. Wake naam "Jarvis" ke saath "Maya" bhi hai.
 *
 * v16 screen + lock: wake hit milte hi (dono raaste — Jarvis aur
 * Maya) display ki roshni jal jati hai: ek SCREEN_BRIGHT wake lock
 * (~15 s timeout wala) liya jata hai, aur har naye session/jawab
 * par woh refresh ho jata hai, isliye poori baatchit ke dauraan
 * screen jala rehta hai aur baad me khud bujh jata hai. Aur phone
 * LOCK ho to background se koi app NAHI khulta — launch wale saare
 * raaste OverlayLauncher se guzarte hain, wahin lock ki jaanch
 * lagti hai, aur Maya "unlock kar lo" bol deti hai. Bina launch
 * wale kaam (torch, volume, gaana, battery, time) lock me bhi
 * pehle ki tarah chalte hain.
 *
 * v21 Phase 3 online brain: CommandEngine kisi baat ko na
 * sambhale, mode Offline na ho, Gemini key set ho aur net chalu
 * ho — tabhi Maya Gemini se jawab lati hai (GeminiBrain).
 * Offline command hamesha pehle jeet-ta hai; key na ho to ye
 * raasta band hi rehta hai aur purana vyavhaar nahi badalta.
 *
 * v25 sanyojit mastishk (combined brain): kram ab ulta hai —
 * online brain uplabdh ho (wahi v21 wala gate) to har baat
 * sabse pehle AGENT ko jati hai (AgentBrain): Gemini poori
 * baat samajhta hai, online kadam khud leta hai aur phone
 * wale kadam offline auzaaron se karwata hai, jab tak poora
 * kaam khatm na ho jaye. Agent ko round 1 par koi model hi
 * na mile to purana raasta (adda -> engine -> retry) bilkul
 * pehle jaisa chalega; gate band ho to agent ka naam bhi
 * nahi liya jata.
 */
class JarvisWakeService : Service() {

    private enum class State { STOPPED, LOADING, WAKE, ACK, COMMAND, REPLY }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var notificationManager: NotificationManager
    private lateinit var speaker: TtsSpeaker
    private lateinit var commandEngine: CommandEngine
    private lateinit var addaEngine: AddaEngine
    private lateinit var prefs: AppPrefs
    private lateinit var orbOverlay: OrbOverlay

    private var model: Model? = null
    private var wakeSpeechService: SpeechService? = null
    private var commandSpeechService: SpeechService? = null

    // v16: screen jalane wala wake lock. Lazily banta hai, hamesha
    // timeout ke saath acquire hota hai (khud bujh jata hai), aur
    // setReferenceCounted(false) ki wajah se dobara acquire karna
    // sirf samay badhata hai — ginati ka jhanjhat nahi.
    private var screenWakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var state: State = State.STOPPED

    @Volatile
    private var destroyed = false

    private var wakeErrorCount = 0

    // v1.3 mic sharing: true while the foreground screen has borrowed
    // the microphone. The wake session is stopped (mic released) but
    // the service + notification stay alive; no error counters move.
    @Volatile
    private var wakePaused = false

    // Command-session caps (Bug-2 fix): track when the session started
    // and when the heard words last CHANGED, so steady noise partials
    // cannot keep a session alive forever.
    @Volatile
    private var commandSessionStartMs = 0L

    @Volatile
    private var commandLastChangeMs = 0L

    @Volatile
    private var commandLastPartial = ""

    // v1.7 diagnostics: the longest wake partial since the last final
    // result. Kept separately so a blank final result cannot erase the
    // best evidence of what the recognizer was hearing.
    @Volatile
    private var longestWakePartialSinceFinal = ""

    // v12: is wake par command ka ek retry mil chuka hai ya nahi.
    // Har naye wake (onWakeDetected) par reset hota hai.
    @Volatile
    // v33: retry ginati — v12 ke ek-mauke ki jagah ab
    // MAX_COMMAND_RETRIES mauke, phir vida wali line.
    private var commandRetryCount = 0

    // v33: ACK wali boli khatm hone par command session ki
    // jagah wapas wake par jana hai (vida wali line chal
    // rahi ho tab).
    private var ackEndsConversation = false

    // v13: adda ke dauraan bina awaaz wala ek session maaf hai;
    // dusra lagatar khaali session adda ko chup-chaap band kar deta
    // hai. Koi asli utterance aate hi ye ginati reset ho jati hai.
    @Volatile
    private var addaNoSpeechCount = 0

    // v21 Phase 3: Gemini baatchit ki yaad — is wake chain ke
    // andar wale aakhiri 10 user/model turn (jodi = 2 entry).
    // Sirf executor thread par chhoota hai. Har naye wake par
    // saaf ho jati hai (onWakeDetected), taaki har chain taaza
    // shuru ho; lambi padaav par khud kat-ti rehti hai.
    private val geminiHistory = mutableListOf<Pair<String, String>>()

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        speaker = TtsSpeaker(this)
        speaker.onUtteranceFinished = { onTtsFinished() }
        // Background flag: app launches cross the overlay bridge and
        // report a missing overlay permission honestly (Bug-1 fix).
        commandEngine = CommandEngine(applicationContext, fromBackgroundService = true)
        addaEngine = AddaEngine()
        prefs = AppPrefs(this)
        orbOverlay = OrbOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_WAKE -> {
                // Service chal hi nahi raha (state STOPPED) to kuch
                // mat karo. stopSelf() yahan jaan-boojh kar NAHI hai:
                // purana prefs-jhanda galat ho to RESUME ek sahi
                // START se pehle line me lag sakta hai, aur stop
                // us start ko kharab kar dega. Khaali instance na
                // mic pakadta hai, na notice dikhata hai — agla
                // START/STOP use sambhal lega.
                if (state != State.STOPPED) {
                    executor.execute { pauseWakeListening() }
                }
                return START_NOT_STICKY
            }
            ACTION_RESUME_WAKE -> {
                if (state != State.STOPPED) {
                    executor.execute { resumeWakeListening() }
                }
                return START_NOT_STICKY
            }
        }
        if (state != State.STOPPED) {
            // Already running: a second START changes nothing.
            return START_NOT_STICKY
        }
        isRunning = true
        prefs.wakeServiceRunning = true
        state = State.LOADING
        setStatus("Model load ho raha hai...")
        startForegroundWith(buildNotification("Model load ho raha hai..."))
        executor.execute { loadModelAndStartWake() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Idempotent full cleanup: recognizers, TTS, model, executor. */
    private fun teardown() {
        if (destroyed) return
        destroyed = true
        state = State.STOPPED
        isRunning = false
        wakePaused = false
        prefs.wakeServiceRunning = false
        setStatus("Band hai")
        // Gola sabse pehle hatao — neeche wala removeCallbacksAndMessages
        // uske posted kaam ko bhi uda dega.
        if (::orbOverlay.isInitialized) {
            orbOverlay.removeImmediately()
        }
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        stopWakeSession()
        stopCommandSession()
        // v16: screen wala wake lock pada ho to chhod do — waise
        // bhi uska timeout khud use bujha deta, ye bas safaai hai.
        try {
            screenWakeLock?.let { lock ->
                if (lock.isHeld) lock.release()
            }
        } catch (t: Throwable) {
            // Release fail ho jaye to bhi teardown nahi rukega.
        }
        screenWakeLock = null
        speaker.shutdown()
        try {
            model?.close()
        } catch (t: Throwable) {
            // Native side already gone: nothing to do.
        }
        model = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ------------------------------------------------------------------
    // Model loading (executor thread)
    // ------------------------------------------------------------------

    private fun loadModelAndStartWake() {
        if (destroyed) return
        val modelManager = ModelManager(applicationContext)
        if (!modelManager.isModelReady(ModelLanguage.HINDI)) {
            setStatus(
                "Hindi offline model download nahi hai — background sunna band ho gaya. " +
                    "Pehle app kholkar model download kijiye."
            )
            updateNotification("Model nahi hai — app kholkar pehle download kijiye")
            mainHandler.post { stopSelf() }
            return
        }
        try {
            model = Model(modelManager.modelDir(ModelLanguage.HINDI).absolutePath)
        } catch (t: Throwable) {
            setStatus("Model load fail ho gaya: ${t.message}")
            mainHandler.post { stopSelf() }
            return
        }
        startWakeSession()
    }

    // ------------------------------------------------------------------
    // Wake session (executor thread)
    // ------------------------------------------------------------------

    private fun startWakeSession() {
        if (destroyed) return
        val loadedModel = model ?: return
        stopCommandSession()
        stopWakeSession()
        state = State.WAKE
        if (wakePaused) {
            // Mic foreground screen ko diya hua hai: state WAKE hi
            // rahega, par koi session mic nahi pakdega. Resume (ya
            // 12 s wala safety timer) aane par yahi function dobara
            // chalega aur session sach me shuru hoga.
            setStatus("Background sunna roka hai — mic app ke paas hai")
            return
        }
        try {
            val recognizer = Recognizer(loadedModel, SAMPLE_RATE)
            val service = SpeechService(recognizer, SAMPLE_RATE)
            wakeSpeechService = service
            service.startListening(wakeListener)
            // NOTE: wakeErrorCount is NOT reset here — Vosk errors
            // arrive async, after an apparently fine start. The
            // counter only resets on a real wake (proof the mic
            // pipeline works), so a broken mic gives up eventually
            // instead of restarting forever.
            setStatus("Sun raha hoon — 'Jarvis' boliye")
            updateNotification("Jarvis sun raha hai — 'Jarvis' boliye")
        } catch (t: Throwable) {
            handleWakeError()
        }
    }

    private fun stopWakeSession() {
        wakeSpeechService?.let { service ->
            try {
                service.stop()
            } catch (t: Throwable) {
                // Already stopped: nothing to do.
            }
            try {
                service.shutdown()
            } catch (t: Throwable) {
                // Ignore while tearing down.
            }
        }
        wakeSpeechService = null
    }

    // ------------------------------------------------------------------
    // Mic sharing (v1.3, executor thread)
    //
    // Foreground tap-to-speak (Google system recognizer) aur ye
    // service ek hi mic ke do daavedar hain — ek saath chal hi nahi
    // sakte. Isliye activity sunna shuru karne se pehle PAUSE bhejti
    // hai: wake session band (mic khali), service + notification
    // chalu. Sunna khatm hone par RESUME aata hai; na aaye to 12 s
    // ka safety timer khud resume kar deta hai — aur beech ka
    // command flow kahin phas gaya ho to use tod kar bhi (bolta
    // hua jawab kabhi nahi katata). Pause na to error counter
    // badhata hai, na service band karta hai.
    // ------------------------------------------------------------------

    private fun pauseWakeListening() {
        if (destroyed) return
        if (wakePaused) {
            // Pehle se roka hai: sirf safety timer aage badhao.
            armAutoResume()
            return
        }
        wakePaused = true
        if (state == State.WAKE) {
            stopWakeSession()
            setStatus("Background sunna roka hai — mic app ke paas hai")
        }
        // ACK/COMMAND/REPLY chal raha ho to flow ko haath mat lagao:
        // uske ant me startWakeSession() wakePaused dekh kar khud
        // ruka rahega.
        armAutoResume()
    }

    private fun resumeWakeListening() {
        if (destroyed || !wakePaused) return
        wakePaused = false
        disarmAutoResume()
        // Sirf WAKE state me session dobara chalao. LOADING ho to
        // model load ke baad wala startWakeSession() khud chalega;
        // ACK/COMMAND/REPLY ho to flow ke ant me chalega — dono ab
        // wakePaused ki rukawat nahi dekhenge.
        if (state == State.WAKE) {
            startWakeSession()
        }
    }

    private val autoResumeRunnable = Runnable {
        executor.execute {
            if (destroyed || !wakePaused) return@execute
            when (state) {
                State.REPLY -> {
                    // Jawab abhi bola ja raha hai — use beech me
                    // kaatna nahi hai. Thodi der baad phir dekho;
                    // jawab khatm hote hi flow WAKE me jayega aur
                    // resume wahin se ho jayega.
                    armAutoResume(REPLY_RESUME_RETRY_MS)
                }
                State.ACK, State.COMMAND -> {
                    // 12 s beet gaye aur flow ab bhi yahin atka hai
                    // to woh healthy nahi hai: ack fallback 3.5 s ka
                    // hai aur command session ka hard cap 7 s ka —
                    // dono beet chuke hain, matlab flow kahin phas
                    // gaya hai (khoya hua callback). Atki hui haalat
                    // tod kar wake sunna saaf phir se shuru karo.
                    disarmAckFallback()
                    stopCommandSession()
                    // v13: phas gaya flow tod rahe hain — adda ka
                    // state bhi saath saaf karo, warna agle wake ke
                    // baad bhi purani baatchit chipki rahegi.
                    addaEngine.endSession()
                    addaNoSpeechCount = 0
                    orbOverlay.hideNow()
                    wakePaused = false
                    disarmAutoResume()
                    startWakeSession()
                }
                else -> resumeWakeListening()
            }
        }
    }

    private fun armAutoResume(delayMs: Long = WAKE_AUTO_RESUME_MS) {
        mainHandler.removeCallbacks(autoResumeRunnable)
        mainHandler.postDelayed(autoResumeRunnable, delayMs)
    }

    private fun disarmAutoResume() {
        mainHandler.removeCallbacks(autoResumeRunnable)
    }

    private fun handleWakeError() {
        if (destroyed || state != State.WAKE) return
        stopWakeSession()
        wakeErrorCount++
        if (wakeErrorCount > MAX_WAKE_ERRORS) {
            setStatus("Mic me baar-baar dikkat aa rahi hai — background sunna band ho gaya.")
            updateNotification("Mic error — background sunna band ho gaya")
            mainHandler.post { stopSelf() }
            return
        }
        mainHandler.postDelayed({
            executor.execute {
                if (!destroyed && state == State.WAKE) {
                    startWakeSession()
                }
            }
        }, WAKE_RESTART_DELAY_MS)
    }

    private val wakeListener = object : RecognitionListener {
        // wakePaused bhi dekho: pause ke liye stop() karne par Vosk
        // ek aakhiri final result bhej sakta hai — usse "wake" nahi
        // manna hai, aur ruke hue samay ki error error-counter me
        // nahi judni chahiye.
        override fun onPartialResult(hypothesis: String?) {
            if (state != State.WAKE || wakePaused) return
            val partial = parseJsonField(hypothesis, "partial")
            rememberWakePartial(partial)
            if (containsWakeWord(partial)) {
                executor.execute { onWakeDetected() }
            }
        }

        override fun onResult(hypothesis: String?) {
            if (state != State.WAKE || wakePaused) return
            val text = parseJsonField(hypothesis, "text")
            rememberWakeFinal(text)
            if (containsWakeWord(text)) {
                executor.execute { onWakeDetected() }
            }
        }

        override fun onFinalResult(hypothesis: String?) {
            if (state != State.WAKE || wakePaused) return
            val text = parseJsonField(hypothesis, "text")
            rememberWakeFinal(text)
            if (containsWakeWord(text)) {
                executor.execute { onWakeDetected() }
            }
        }

        override fun onError(exception: Exception?) {
            if (state == State.WAKE && !wakePaused) {
                executor.execute { handleWakeError() }
            }
        }

        override fun onTimeout() {
            // No timeout is set on the wake session: nothing to do.
        }
    }

    // ------------------------------------------------------------------
    // Screen wake (v16)
    //
    // Wake hit milte hi display jal jaye — screen band ho ya lock
    // screen dikh rahi ho. Ek SCREEN_BRIGHT wake lock
    // (ACQUIRE_CAUSES_WAKEUP = band screen ko bhi jalaye,
    // ON_AFTER_RELEASE = chhodne ke baad pal bhar aur roshni)
    // timeout ke saath liya jata hai; har session/jawab par
    // dobara bulane se samay aage badh jata hai. Ye lock screen
    // ko JALATA hai, lock KHOLTA nahi — unlock user hi karega.
    // Poora kaam try/catch me hai: wake lock kabhi fail ho jaye
    // to bhi wake flow nahi rukna chahiye.
    // ------------------------------------------------------------------

    private fun lightScreenOnWake() {
        try {
            val powerManager =
                getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lock = screenWakeLock ?: powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "jarvis:wake"
            ).also { created ->
                created.setReferenceCounted(false)
                screenWakeLock = created
            }
            lock.acquire(WAKE_SCREEN_TIMEOUT_MS)
        } catch (t: Throwable) {
            // Screen na jale to bhi awaaz wala flow pehle jaisa
            // chalta rahega — yahan rukna kabhi nahi hai.
        }
    }

    // ------------------------------------------------------------------
    // Wake -> ack -> command (executor thread)
    // ------------------------------------------------------------------

    private fun onWakeDetected() {
        if (destroyed || state != State.WAKE || wakePaused) return
        // v16: wake hit milte hi sabse pehle screen ki roshni —
        // screen band ho to bhi user ko dikhe ki Maya jaag gayi.
        lightScreenOnWake()
        state = State.ACK
        stopWakeSession()
        // A real wake proves listening works: clear the error counter.
        wakeErrorCount = 0
        // v12: har naye wake ke saath command ko ek taaza retry mile.
        commandRetryCount = 0
        ackEndsConversation = false
        // v13: naya wake hamesha taaza interaction hai — koi atka
        // hua purana adda state saath nahi ghisdega.
        addaEngine.endSession()
        addaNoSpeechCount = 0
        // v21: pichhli chain ki Gemini baatchit bhi saath saaf —
        // naya wake matlab nayi baatchit.
        geminiHistory.clear()
        prefs.wakeHitCount = prefs.wakeHitCount + 1
        setStatus("'Jarvis' suna gaya — command boliye")
        updateNotification("Command boliye...")
        // v1.5: Siri gola ubhar aaye — "हाँ, बोलिए" wale pal se
        // lekar poore command flow ke ant tak dikhega.
        orbOverlay.show()
        // Mic is fully released before Jarvis speaks, so it cannot
        // hear its own acknowledgement.
        speaker.speak(ACK_TEXT)
        armAckFallback()
    }

    private fun onTtsFinished() {
        executor.execute {
            if (destroyed) return@execute
            when (state) {
                State.ACK -> {
                    onAckSpeechDone()
                }
                State.REPLY -> {
                    disarmReplyFallback()
                    if (addaEngine.isActive) {
                        // v13: adda abhi chal raha hai — gola bana
                        // rahega aur agla turn bina "Jarvis" bole
                        // seedha command session se shuru hoga.
                        setStatus("Adda chal raha hai — Maya sun rahi hai")
                        updateNotification("Maya adda sun rahi hai — boliye...")
                        startCommandSession()
                    } else {
                        // v1.5: jawab khatm — gola 1.5 s aur dikh kar
                        // dheere se gayab ho jayega.
                        orbOverlay.hideAfter(ORB_REPLY_LINGER_MS)
                        startWakeSession()
                    }
                }
                else -> {
                    // Speech finished in some other state: nothing waits on it.
                }
            }
        }
    }

    /**
     * v33: ACK wali boli (wake ki haan, retry prompt, ya vida
     * wali line) khatm — aam haal me command session kholo;
     * vida wali line chal rahi thi to gola hata kar wapas
     * wake sunne par jao. TTS-finish aur ack-fallback dono
     * yahi se guzarte hain.
     */
    private fun onAckSpeechDone() {
        disarmAckFallback()
        if (ackEndsConversation) {
            ackEndsConversation = false
            orbOverlay.hideNow()
            startWakeSession()
        } else {
            startCommandSession()
        }
    }

    private fun startCommandSession() {
        if (destroyed) return
        val loadedModel = model ?: return
        // v16: har naya session (aam command, retry ke baad wala,
        // ya adda ka agla turn) screen-wake ka samay aage badhata
        // hai — lambi baatchit me screen beech me nahi bujhega.
        lightScreenOnWake()
        stopWakeSession()
        stopCommandSession()
        state = State.COMMAND
        try {
            val recognizer = Recognizer(loadedModel, SAMPLE_RATE)
            val service = SpeechService(recognizer, SAMPLE_RATE)
            commandSpeechService = service
            service.startListening(commandListener)
            setStatus("Command sun raha hoon — boliye")
            updateNotification("Command boliye...")
            val now = SystemClock.uptimeMillis()
            commandSessionStartMs = now
            commandLastChangeMs = now
            commandLastPartial = ""
            armCommandWatchdog()
        } catch (t: Throwable) {
            // Mic busy etc.: session shuru hi nahi ho paya — pehli
            // koshish par v12 retry ka mauka, warna wapas wake par.
            handleCommandMiss()
        }
    }

    private fun stopCommandSession() {
        disarmCommandWatchdog()
        commandSpeechService?.let { service ->
            try {
                service.stop()
            } catch (t: Throwable) {
                // Already stopped: nothing to do.
            }
            try {
                service.shutdown()
            } catch (t: Throwable) {
                // Ignore while tearing down.
            }
        }
        commandSpeechService = null
    }

    private val commandListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            if (state != State.COMMAND) return
            val partial = parseJsonField(hypothesis, "partial").trim()
            if (partial.isNotEmpty() && partial != commandLastPartial) {
                // The words actually changed — the user is mid-command,
                // so the silence deadline moves. Repeats of the same
                // partial (steady room noise) do NOT extend anything.
                commandLastPartial = partial
                commandLastChangeMs = SystemClock.uptimeMillis()
            }
        }

        override fun onResult(hypothesis: String?) {
            acceptCommand(parseJsonField(hypothesis, "text"))
        }

        override fun onFinalResult(hypothesis: String?) {
            acceptCommand(parseJsonField(hypothesis, "text"))
        }

        override fun onError(exception: Exception?) {
            executor.execute {
                if (!destroyed && state == State.COMMAND) {
                    // v12: bina command ke khatm — retry ya vida.
                    handleCommandMiss()
                }
            }
        }

        override fun onTimeout() {
            executor.execute {
                if (!destroyed && state == State.COMMAND) {
                    // v12: bina command ke khatm — retry ya vida.
                    handleCommandMiss()
                }
            }
        }

        private fun acceptCommand(text: String) {
            if (state != State.COMMAND || text.isBlank()) return
            // v12 diagnostics: command recognizer ne jo suna, woh
            // bhi "Mic ne aakhri suna" me dikhe.
            rememberCommandFinal(text)
            executor.execute { onCommandHeard(text) }
        }
    }

    private fun onCommandHeard(text: String) {
        if (destroyed || state != State.COMMAND) return
        // Flip state BEFORE stopping the session, so the final-result
        // callback fired by stop() is ignored.
        state = State.REPLY
        stopCommandSession()
        addaNoSpeechCount = 0
        // v25: online brain uplabdh ho to sabse pehle AGENT —
        // app ke andar EK hi mastishk (user ka pakka design):
        // Gemini poori baat samajhta hai, online kadam khud
        // leta hai aur phone wale kadam offline auzaaron se
        // karwata hai, jab tak poora kaam khatm na ho jaye.
        // Adda tab hi pehle aayega jab agent uplabdh NA ho
        // (Offline mode / key nahi / net nahi) — ya agent ko
        // round 1 par koi model hi na mile (onAgentBrainAnswer
        // wala purana raasta, retry ke saath).
        if (geminiBrainAllowed()) {
            askAgentBrain(text)
            return
        }
        // v13: adda trigger ya chalu adda ho to utterance pehle
        // Maya ke AddaEngine ko jata hai — CommandEngine use beech
        // me nigal nahi sakta. Adda ke andar v12 retry nahi lagta.
        if (addaEngine.shouldHandle(text)) {
            handleAddaHeard(text)
            return
        }
        val rawResult = commandEngine.handle(text)
        finishCommandResult(text, rawResult)
    }

    /**
     * Engine ke result ka aakhiri faisla — v21 se pehle ye saara
     * kaam onCommandHeard ke andar hota tha; ab wahi code yahan
     * hai taaki agent/Gemini ke null jawab par bhi bilkul waisa
     * hi vyavhaar mile. Sirf executor thread se bulao; state
     * REPLY ho aur command session band ho chuka ho.
     */
    private fun finishCommandResult(text: String, rawResult: CommandResult) {
        // v12: "handled" ka faisla RAW result par hota hai — neeche
        // wale v1.10 CANT_DO conversion se PEHLE. Isliye galat-suna
        // kholne ka prayas ("pesbuk kholo" jaisa) bhi pehle apna ek
        // retry payega; can't-do wala bola jawab sirf tab aayega
        // jab retry wala prayas bhi kholne ka hi nikle aur asafal
        // rahe.
        if (!rawResult.handled && commandRetryCount < MAX_COMMAND_RETRIES) {
            startCommandRetry()
            return
        }
        // v1.10: kholne ki koshish thi par system me nahi hai —
        // chup band hone ke bajay saaf bol do ki nahi kar sakte.
        // Bina verb wali aawazein (shor/TV) pehle ki tarah chup
        // rahengi, taaki background bak-bak na kare.
        val result = if (!rawResult.handled && looksLikeOpenAttempt(text)) {
            CommandResult(CANT_DO_REPLY, true)
        } else {
            rawResult
        }
        if (!result.handled) {
            // Bug-2 fix: in background mode an unrecognized utterance
            // is almost always room noise, TV, or someone else talking.
            // Stay silent and resume wake listening instead of
            // announcing the "Phase 3" fallback to nobody.
            // v33: chup vida nahi — vida wali line bolkar jao.
            startClosingLine()
            return
        }
        setStatus("Command mila — jawab de raha hoon")
        updateNotification("Jarvis jawab de raha hai...")
        // v16: jawab bolna shuru — screen-wake ka samay badhao,
        // taaki jawab ke dauraan aur uske baad wale intezaar me
        // screen jala rahe.
        lightScreenOnWake()
        speaker.speak(result.responseText)
        armReplyFallback(result.responseText)
        // v1.5: jawab gole ke upar wale bubble me text me bhi dikhe,
        // aur gola jawab ke dauraan chamak (shimmer) mode me rahe.
        orbOverlay.setReply(result.responseText)
    }

    /**
     * v21 Phase 3 gate: online brain kab bulaya jaye. Teenon
     * shartein — mode pure-Offline na ho (prefs wala mode hi
     * aakhiri shabd hai), user ne Gemini key di ho, aur is pal
     * net sach me chalu ho. Ek bhi na mile to Gemini ka naam
     * bhi nahi liya jata — purana offline raasta hi chalega.
     */
    private fun geminiBrainAllowed(): Boolean {
        if (prefs.mode == JarvisMode.OFFLINE) return false
        if (!GeminiBrain.isConfigured(applicationContext)) return false
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
     * v25: sanyojit agent chalao. Auzaar background wale
     * CommandEngine se bandhe hain (overlay bridge wala
     * raasta) — agent ke phone-kadam bilkul waise hi
     * chalte hain jaise seedhe bole hue command chalte
     * the. State pehle se REPLY hai aur command session
     * band ho chuka hai; agent ki daud (net round + auzaar)
     * AgentBrain ke apne thread par chalti hai — is flow
     * ko koi timer nahi chhedta: reply fallback sirf kuch
     * BOLNE par arm hota hai, auto-resume sirf wakePaused
     * par chalta hai. Aakhiri jawab AgentBrain ke thread
     * se wapas executor par aata hai (onAgentBrainAnswer).
     * (v21 wala askGeminiBrain/chat raasta ab nahi hai —
     * agent uski jagah le chuka hai.)
     */
    private fun askAgentBrain(text: String) {
        setStatus("Maya soch rahi hai — kaam kar rahi hai...")
        updateNotification("Maya soch rahi hai — kaam kar rahi hai...")
        val tools = AgentTools(
            applicationContext, commandEngine, fromBackgroundService = true
        )
        AgentBrain.run(
            applicationContext, geminiHistory.toList(), text, tools::execute
        ) { reply ->
            executor.execute { onAgentBrainAnswer(text, reply) }
        }
    }

    /**
     * Agent ka aakhiri jawab (ya uski anupasthiti). Jawab
     * mila to use ek aam handled jawab ki tarah bolo —
     * gola bubble, reply fallback, sab waisa hi; aur
     * baatchit yaad me jud jati hai. Jawab null ho (round
     * 1 par koi model hi nahi mila) to purana offline
     * raasta bilkul pehle jaisa: adda, phir engine, phir
     * finishCommandResult — retry ab bhi waisa hi bacha hai.
     */
    private fun onAgentBrainAnswer(text: String, reply: String?) {
        // Beech me service band ho gayi ho ya flow aage badh
        // gaya ho to der se aaya jawab chup-chaap chhod do.
        if (destroyed || state != State.REPLY) return
        if (reply == null) {
            if (addaEngine.shouldHandle(text)) {
                handleAddaHeard(text)
                return
            }
            finishCommandResult(text, commandEngine.handle(text))
            return
        }
        // Baatchit ki yaad: aakhiri 10 turn rakho, jodi todna mat.
        geminiHistory.add("user" to text)
        geminiHistory.add("model" to reply)
        while (geminiHistory.size > MAX_GEMINI_HISTORY_ENTRIES) {
            geminiHistory.removeAt(0)
            geminiHistory.removeAt(0)
        }
        setStatus("Online jawab mila — bol rahi hoon")
        updateNotification("Jarvis jawab de raha hai...")
        // v16 wala screen-wake refresh, aam jawab ki tarah.
        lightScreenOnWake()
        speaker.speak(reply)
        armReplyFallback(reply)
        orbOverlay.setReply(reply)
    }

    /**
     * v13 adda turn: state pehle hi REPLY hai aur command session
     * band ho chuka hai. Maya ka jawab bolo; uske baad wala raasta
     * onTtsFinished / reply fallback dekh lega — adda chalu ho to
     * agla command session, warna wapas wake.
     *
     * YouTube wala action koi shortcut se nahi hota: wahi purana
     * CommandEngine ("youtube kholo") background overlay bridge se
     * kholti hai. Uska jawab safal wala pakka jawab na ho to Maya ki
     * bani-banaai line ki jagah wahi sachha jawab bolo aur adda band
     * kar do — jhootha "khol diya" kabhi nahi.
     */
    private fun handleAddaHeard(text: String) {
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
        if (addaEngine.isActive) {
            setStatus("Adda chal raha hai — Maya sun rahi hai")
            updateNotification("Maya adda me hai — boliye...")
        } else {
            setStatus("Adda khatm — jawab de raha hoon")
            updateNotification("Jarvis jawab de raha hai...")
        }
        // v16: adda ka jawab bolna shuru — screen-wake refresh.
        lightScreenOnWake()
        speaker.speak(spokenText)
        armReplyFallback(spokenText)
        orbOverlay.setReply(spokenText)
    }

    /**
     * v12 retry-once: "फिर से बोलिए" bolkar ACK wale raaste se
     * ek aur command session kholo (TTS khatm hone par, ya ack
     * fallback par). Gola dikhta rehta hai — dobara "Jarvis"
     * bolne ki zaroorat nahi. Sirf executor thread se bulao;
     * command session pehle hi band ho chuka ho.
     */
    private fun startCommandRetry() {
        commandRetryCount++
        state = State.ACK
        ackEndsConversation = false
        setStatus("Samajh nahi aaya — phir se boliye, dobara sun raha hoon")
        updateNotification("Phir se boliye...")
        // v16: retry prompt bhi ek bola hua jawab hai — screen jala rahe.
        lightScreenOnWake()
        // v33: prompt Bangla me — malik Bangla bolte hain, aur
        // TtsSpeaker ab script dekhkar awaaz khud badalta hai.
        speaker.speak(
            if (commandRetryCount <= 1) {
                RETRY_PROMPT_FIRST
            } else {
                RETRY_PROMPT_SECOND
            }
        )
        armAckFallback()
    }

    /**
     * v33: saari koshishon ke baad chup vida nahi — ek
     * vinamra line bolkar wapas wake par jao (ACK wale
     * raaste se, taaki boli khatm hone par hi mic khule).
     * Sirf executor thread se bulao.
     */
    private fun startClosingLine() {
        state = State.ACK
        ackEndsConversation = true
        setStatus("Samajh nahi aaya — wapas 'Jarvis' sun raha hoon")
        updateNotification("Jarvis sun raha hai — 'Jarvis' boliye")
        lightScreenOnWake()
        speaker.speak(CLOSE_PROMPT_TEXT)
        armAckFallback()
    }

    /**
     * v12: command session bina handled command ke khatm ho gaya
     * (recognizer error/timeout, timeout par khaali salvage, ya
     * session shuru hi nahi ho paya). Pehli baar par retry ka
     * mauka; retry ke baad purana chup vida — gola hatao aur
     * wapas wake sunne par jao. Sirf executor thread se bulao.
     */
    private fun handleCommandMiss() {
        if (destroyed) return
        stopCommandSession()
        if (addaEngine.isActive) {
            // v13: adda me "फिर से बोलिए" wala v12 retry nahi
            // chalega. Pehla khaali/error session chup-chaap ek aur
            // mauka; dusra lagatar khaali session adda band karke
            // wapas wake par. Asli utterance aate hi ginati reset
            // ho jati hai (onCommandHeard).
            if (addaNoSpeechCount == 0) {
                addaNoSpeechCount = 1
                startCommandSession()
            } else {
                addaEngine.endSession()
                addaNoSpeechCount = 0
                orbOverlay.hideNow()
                startWakeSession()
            }
            return
        }
        if (commandRetryCount < MAX_COMMAND_RETRIES) {
            startCommandRetry()
        } else {
            startClosingLine()
        }
    }

    /** "X kholo" jaisi koshish — kisi bhi script me open-verb ho. */
    private fun looksLikeOpenAttempt(text: String): Boolean =
        text.contains("khol", ignoreCase = true) ||
            text.contains("open", ignoreCase = true) ||
            text.contains("खोल") || text.contains("খোল")

    // ------------------------------------------------------------------
    // Timers (main looper; each re-checks state on the executor)
    // ------------------------------------------------------------------

    /**
     * Command-session watchdog (Bug-2 fix). Ends the session when
     * EITHER the hard cap (~7 s total) is reached — no matter how many
     * partials keep arriving — OR ~2.5 s pass with no CHANGE in the
     * heard words. Continuous noise can no longer stretch a session.
     */
    private val commandWatchdogRunnable = object : Runnable {
        override fun run() {
            executor.execute {
                if (destroyed || state != State.COMMAND) return@execute
                val now = SystemClock.uptimeMillis()
                val totalMs = now - commandSessionStartMs
                val silenceMs = now - commandLastChangeMs
                if (totalMs >= COMMAND_HARD_CAP_MS ||
                    silenceMs >= COMMAND_SILENCE_TIMEOUT_MS
                ) {
                    // v1.9: session khatm hone par aakhiri suna hua
                    // tukda (partial) bekaar mat jane do — use bhi
                    // command ki tarah aazmao. Na mile to
                    // onCommandHeard khud pehle ki tarah chup
                    // wapas wake par chala jayega; mile to kaam
                    // hoga — dheere bolne par bhi command bachega.
                    val lastPartial = commandLastPartial
                    if (lastPartial.isNotBlank()) {
                        // v12 diagnostics: bachaya gaya partial bhi
                        // "Mic ne aakhri suna" me likho. Dhyaan rahe:
                        // ye salvage ISI prayas ka hissa hai — retry
                        // nahi khaata; partial asafal nikle to
                        // onCommandHeard ke andar retry ab bhi milega.
                        rememberCommandFinal(lastPartial)
                        onCommandHeard(lastPartial)
                    } else {
                        // v12: khaali gaya session bhi pehle ek
                        // retry payega, phir hi wapas wake par.
                        handleCommandMiss()
                    }
                } else {
                    mainHandler.postDelayed(this, COMMAND_WATCHDOG_TICK_MS)
                }
            }
        }
    }

    private fun armCommandWatchdog() {
        mainHandler.removeCallbacks(commandWatchdogRunnable)
        mainHandler.postDelayed(commandWatchdogRunnable, COMMAND_WATCHDOG_TICK_MS)
    }

    private fun disarmCommandWatchdog() {
        mainHandler.removeCallbacks(commandWatchdogRunnable)
    }

    private val ackFallbackRunnable = Runnable {
        executor.execute {
            if (!destroyed && state == State.ACK) {
                onAckSpeechDone()
            }
        }
    }

    private fun armAckFallback() {
        mainHandler.removeCallbacks(ackFallbackRunnable)
        mainHandler.postDelayed(ackFallbackRunnable, ACK_FALLBACK_MS)
    }

    private fun disarmAckFallback() {
        mainHandler.removeCallbacks(ackFallbackRunnable)
    }

    private val replyFallbackRunnable = Runnable {
        executor.execute {
            if (!destroyed && state == State.REPLY) {
                if (addaEngine.isActive) {
                    // v13: TTS completion callback kabhi kho jaye to
                    // bhi adda wahi se aage badhe, wake par na gire.
                    startCommandSession()
                } else {
                    orbOverlay.hideAfter(ORB_REPLY_LINGER_MS)
                    startWakeSession()
                }
            }
        }
    }

    private fun armReplyFallback(spokenText: String) {
        mainHandler.removeCallbacks(replyFallbackRunnable)
        val delay = (REPLY_FALLBACK_BASE_MS + spokenText.length * REPLY_FALLBACK_PER_CHAR_MS)
            .coerceAtMost(REPLY_FALLBACK_MAX_MS)
        mainHandler.postDelayed(replyFallbackRunnable, delay)
    }


    private fun disarmReplyFallback() {
        mainHandler.removeCallbacks(replyFallbackRunnable)
    }

    // ------------------------------------------------------------------
    // Siri orb overlay (v1.5)
    //
    // Ek chhoti si floating khidki (TYPE_APPLICATION_OVERLAY):
    // neeche beech me OrbView ka gola, uske upar ek gola-kaand
    // (rounded) kala bubble jisme jawab text dikhta hai. Window
    // NOT_FOCUSABLE + NOT_TOUCHABLE hai — neeche wali app ke taps
    // kabhi nahi rukte. "Display over other apps" permission na
    // ho to show() chup-chaap kuch nahi karta; awaaz wala flow
    // pehle jaisa hi chalta hai. Ye khidki OverlayLauncher ki
    // 1x1 launch-khidki se alag hai — dono saath reh sakte hain.
    //
    // Saare window kaam main thread par hote hain; bahari calls
    // (executor thread se) mainHandler par post ho jate hain.
    // ------------------------------------------------------------------

    private inner class OrbOverlay {

        private var windowManager: WindowManager? = null
        private var container: LinearLayout? = null
        private var orbView: OrbView? = null
        private var bubble: TextView? = null
        private var attached = false

        // Har show/hide par badhta hai, taaki kisi purane fade-out
        // ka end-action naye show ko galti se na uda de.
        private var generation = 0

        private val hideRunnable = Runnable { fadeOutOnMain() }

        /** Gola dikhao — command ka intezaar (breathing) mode me. */
        fun show() {
            mainHandler.post { showOnMain() }
        }

        /** Jawab bubble me dikhao aur gole ko shimmer mode me dalo. */
        fun setReply(text: String) {
            mainHandler.post {
                if (!attached) showOnMain()
                if (!attached) return@post
                bubble?.text = text
                bubble?.visibility = View.VISIBLE
                orbView?.setMode(OrbView.Mode.SPEAKING)
            }
        }

        /** Kuch der baad gola hatao (jawab ke baad wala thahraav). */
        fun hideAfter(delayMs: Long) {
            mainHandler.removeCallbacks(hideRunnable)
            mainHandler.postDelayed(hideRunnable, delayMs)
        }

        /** Gola abhi hatao (fade-out ke saath). */
        fun hideNow() {
            mainHandler.post { fadeOutOnMain() }
        }

        /** Teardown ke liye: bina fade ke, isi pal hatao. */
        fun removeImmediately() {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                removeWindowOnMain()
            } else {
                mainHandler.post { removeWindowOnMain() }
            }
        }

        private fun showOnMain() {
            if (!Settings.canDrawOverlays(this@JarvisWakeService)) {
                // Permission nahi: gola nahi, par kuch tootta nahi.
                return
            }
            generation++
            mainHandler.removeCallbacks(hideRunnable)
            if (!attached) {
                buildViews()
                val wm = windowManager ?: return
                val root = container ?: return
                try {
                    wm.addView(root, overlayLayoutParams())
                } catch (t: Throwable) {
                    // Permission beech me wapas li gayi ho, aadi.
                    return
                }
                attached = true
            }
            val root = container ?: return
            root.animate().cancel()
            bubble?.visibility = View.GONE
            orbView?.setMode(OrbView.Mode.LISTENING)
            orbView?.startAnimations()
            root.alpha = 0f
            root.animate().alpha(1f).setDuration(ORB_FADE_IN_MS).start()
        }

        private fun fadeOutOnMain() {
            if (!attached) return
            val gen = ++generation
            val root = container ?: return
            root.animate().cancel()
            root.animate().alpha(0f).setDuration(ORB_FADE_OUT_MS)
                .withEndAction {
                    if (gen == generation) removeWindowOnMain()
                }
                .start()
        }

        private fun removeWindowOnMain() {
            generation++
            mainHandler.removeCallbacks(hideRunnable)
            container?.animate()?.cancel()
            orbView?.stopAnimations()
            if (attached) {
                try {
                    windowManager?.removeView(container)
                } catch (t: Throwable) {
                    // Pehle se hata hua: kuch nahi karna.
                }
                attached = false
            }
        }

        private fun buildViews() {
            if (container != null) return
            val service = this@JarvisWakeService
            windowManager =
                service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

            val bubbleView = TextView(service).apply {
                textSize = 14f
                setTextColor(Color.WHITE)
                maxWidth = dp(300)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = GradientDrawable().apply {
                    setColor(0xD91C1C28.toInt())
                    cornerRadius = dp(18).toFloat()
                    setStroke(1, 0x40FFFFFF)
                }
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            }

            // v1.11: gola thoda bada (104 -> 148dp) — pehle wala
            // bahut chhota lagta tha.
            val orb = OrbView(service).apply {
                layoutParams = LinearLayout.LayoutParams(dp(148), dp(148))
            }

            container = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(10), dp(10), dp(10), dp(4))
                addView(bubbleView)
                addView(orb)
            }
            bubble = bubbleView
            orbView = orb
        }

        private fun overlayLayoutParams(): WindowManager.LayoutParams =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(48)
            }

        private fun dp(value: Int): Int =
            (value * resources.displayMetrics.density).toInt()
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Jarvis background sunna",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Jab Jarvis background me wake word sun raha hota hai"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, JarvisWakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("ASHIP JARVIS AI")
            .setContentText(text)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Band karo", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundWith(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
                return
            } catch (t: Throwable) {
                // Fall through to the untyped call below.
            }
        }
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(text: String) {
        if (destroyed) return
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun setStatus(message: String) {
        statusMessage = message
    }

    private fun rememberWakePartial(text: String) {
        val clean = text.trim().replace('\n', ' ')
        if (clean.length > longestWakePartialSinceFinal.length) {
            longestWakePartialSinceFinal = clean
            prefs.lastHeardPartialText = clean
            prefs.lastHeardPartialAtMs = System.currentTimeMillis()
        }
    }

    private fun rememberWakeFinal(text: String) {
        val clean = text.trim().replace('\n', ' ')
        if (clean.isEmpty()) return
        prefs.lastHeardText = clean
        prefs.lastHeardAtMs = System.currentTimeMillis()
        longestWakePartialSinceFinal = ""
        prefs.lastHeardPartialText = ""
        prefs.lastHeardPartialAtMs = 0L
    }

    /**
     * v12 diagnostics: wake listener ki tarah, command session ne
     * jo suna (final result, ya timeout par bachaya gaya partial)
     * wahi last-heard fields me likho — taaki "Mic ne aakhri suna"
     * line command ki pehchan bhi dikhaye, sirf wake wali nahi.
     */
    private fun rememberCommandFinal(text: String) {
        val clean = text.trim().replace('\n', ' ')
        if (clean.isEmpty()) return
        prefs.lastHeardText = clean
        prefs.lastHeardAtMs = System.currentTimeMillis()
    }

    private fun containsWakeWord(text: String): Boolean {
        if (text.isBlank()) return false
        val squashedText = squashWakeText(text)
        if (squashedText.isEmpty()) return false
        val variants = WAKE_WORDS.map { squashWakeText(it) }
        if (variants.any { squashedText.contains(it) }) return true

        // Fuzzy word match: one small spelling/recognition slip in a
        // word is enough, but unrelated short words never qualify.
        val words = text.split(Regex("\\s+"))
            .map { squashWakeText(it) }
            .filter { it.length >= 5 }
        for (word in words) {
            for (variant in variants) {
                if (variant.length >= 5 && levenshteinDistance(word, variant) <= 1) {
                    return true
                }
            }
        }
        return containsMayaCall(text)
    }

    /**
     * v13: dusra wake naam "Maya". Jarvis wala purana substring
     * raasta is par lagu NAHI hai — naam hamesha ek alag shabd
     * (token) ki tarah mile tabhi wake hoga, taaki "মায়াবী" /
     * "mayabi" jaise lambe shabd galti se na jaga dein. Latin roop
     * par wahi distance-1 wali fuzzy chhoot hai; script roop
     * squash ke baad chhote ho jate hain, isliye unpar pakka token
     * match hi kaafi hai.
     */
    private fun containsMayaCall(text: String): Boolean {
        val variants = MAYA_WAKE_WORDS.map { squashWakeText(it) }.toSet()
        val tokens = text.split(Regex("\\s+"))
            .flatMap { it.split('-') }
            .map { squashWakeText(it) }
            .filter { it.isNotEmpty() }
        return tokens.any { token ->
            token in variants ||
                (token.length >= 4 && variants.any { variant ->
                    variant.all { ch -> ch.code < 128 } &&
                        levenshteinDistance(token, variant) <= 1
                })
        }
    }

    private fun squashWakeText(text: String): String {
        val result = StringBuilder()
        for (ch in text.lowercase()) {
            if (ch.isLetterOrDigit()) result.append(ch)
        }
        return result.toString()
    }

    private fun levenshteinDistance(first: String, second: String): Int {
        if (kotlin.math.abs(first.length - second.length) > 1) return 2
        var previous = IntArray(second.length + 1) { it }
        var current = IntArray(second.length + 1)
        for (i in 1..first.length) {
            current[0] = i
            for (j in 1..second.length) {
                val substitutionCost = if (first[i - 1] == second[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + substitutionCost
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[second.length]
    }

    /** Vosk hypotheses are JSON strings like {"text" : "youtube kholo"}. */
    private fun parseJsonField(json: String?, field: String): String {
        if (json.isNullOrBlank()) return ""
        return try {
            JSONObject(json).optString(field, "")
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        const val ACTION_START = "com.aship.jarvis.action.WAKE_START"
        const val ACTION_STOP = "com.aship.jarvis.action.WAKE_STOP"
        const val ACTION_PAUSE_WAKE = "com.aship.jarvis.action.WAKE_PAUSE"
        const val ACTION_RESUME_WAKE = "com.aship.jarvis.action.WAKE_RESUME"

        private const val NOTIFICATION_ID = 4201
        private const val CHANNEL_ID = "jarvis_wake_channel"
        private const val SAMPLE_RATE = 16000.0f

        private const val ACK_TEXT = "हाँ, बोलिए"

        /** v12: command na samajhne par ek baar dobara mauka dene ka prompt. */
        // v33: retry prompts Bangla me (malik ki bhasha);
        // TtsSpeaker script dekhkar Bangla awaaz lagayega.
        private const val RETRY_PROMPT_FIRST = "বুঝতে পারিনি — আরেকবার বলো তো।"
        private const val RETRY_PROMPT_SECOND = "আরেকবার একটু আস্তে বলো তো — শুনছি।"
        private const val CLOSE_PROMPT_TEXT = "ঠিক আছে — পরে আবার 'জার্ভিস' বলে ডেকো।"

        /** v33: ek command ko kul itne retry milenge. */
        private const val MAX_COMMAND_RETRIES = 2

        /** v1.10: unsupported kholne ki koshish par bola jaane wala jawab. */
        const val CANT_DO_REPLY = "माफ़ कीजिए, अभी मैं यह काम नहीं कर सकता हूँ।"
        private const val COMMAND_SILENCE_TIMEOUT_MS = 2_500L
        private const val COMMAND_HARD_CAP_MS = 7_000L
        private const val COMMAND_WATCHDOG_TICK_MS = 400L
        private const val ACK_FALLBACK_MS = 3_500L
        private const val REPLY_FALLBACK_BASE_MS = 4_000L
        private const val REPLY_FALLBACK_PER_CHAR_MS = 110L
        private const val REPLY_FALLBACK_MAX_MS = 20_000L
        private const val WAKE_RESTART_DELAY_MS = 1_500L
        private const val MAX_WAKE_ERRORS = 5
        private const val WAKE_AUTO_RESUME_MS = 12_000L
        private const val REPLY_RESUME_RETRY_MS = 2_000L
        private const val ORB_REPLY_LINGER_MS = 1_500L
        private const val ORB_FADE_IN_MS = 250L
        private const val ORB_FADE_OUT_MS = 220L

        /** v16: ek baar ke screen-wake ki umar; har session/jawab use badha deta hai. */
        private const val WAKE_SCREEN_TIMEOUT_MS = 15_000L

        /** v21: Gemini yaad ki gehrai — 10 turn, ek turn = user+model jodi. */
        private const val MAX_GEMINI_HISTORY_ENTRIES = 20

        /** Wake-word spellings and close mishearings from the recognizers. */
        private val WAKE_WORDS = listOf(
            "जार्विस", "जारविस", "जर्भिस", "जारभिस", "जार्बिस",
            "जार्भिस", "जार्बीस", "जारवीस", "जारिस", "जाविस",
            "জার্ভিস", "জারবিস", "জারভিস", "জার্বিস", "জার্ভীস",
            "jarvis", "jarbis", "jarwis", "jarvish"
        )

        /**
         * v13: dusre wake naam "Maya" ke roop. Ye WAKE_WORDS me
         * jaan-boojh kar nahi milaye gaye — unpar token-level wala
         * alag match lagta hai (containsMayaCall dekho).
         */
        private val MAYA_WAKE_WORDS = listOf(
            "माया", "মায়া", "মাযা", "মাইয়া",
            "maya", "maaya", "maiya"
        )

        /** True while the wake service is alive (same process as the UI). */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Last human-readable state line, shown on the main screen. */
        @Volatile
        var statusMessage: String = "Band hai"
            private set

        fun startIntent(context: Context): Intent =
            Intent(context, JarvisWakeService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, JarvisWakeService::class.java).setAction(ACTION_STOP)

        fun pauseIntent(context: Context): Intent =
            Intent(context, JarvisWakeService::class.java).setAction(ACTION_PAUSE_WAKE)

        fun resumeIntent(context: Context): Intent =
            Intent(context, JarvisWakeService::class.java).setAction(ACTION_RESUME_WAKE)
    }
}
