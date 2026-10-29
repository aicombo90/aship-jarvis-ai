package com.aship.jarvis.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.aship.jarvis.MainActivity
import com.aship.jarvis.commands.CommandEngine
import com.aship.jarvis.prefs.AppPrefs
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
 *   wake session STOPPED -> ack spoken ("Haan, boliye") -> command
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
 */
class JarvisWakeService : Service() {

    private enum class State { STOPPED, LOADING, WAKE, ACK, COMMAND, REPLY }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var notificationManager: NotificationManager
    private lateinit var speaker: TtsSpeaker
    private lateinit var commandEngine: CommandEngine
    private lateinit var prefs: AppPrefs

    private var model: Model? = null
    private var wakeSpeechService: SpeechService? = null
    private var commandSpeechService: SpeechService? = null

    @Volatile
    private var state: State = State.STOPPED

    @Volatile
    private var destroyed = false

    private var wakeErrorCount = 0

    // Command-session caps (Bug-2 fix): track when the session started
    // and when the heard words last CHANGED, so steady noise partials
    // cannot keep a session alive forever.
    @Volatile
    private var commandSessionStartMs = 0L

    @Volatile
    private var commandLastChangeMs = 0L

    @Volatile
    private var commandLastPartial = ""

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
        prefs = AppPrefs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (state != State.STOPPED) {
            // Already running: a second START changes nothing.
            return START_NOT_STICKY
        }
        isRunning = true
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
        setStatus("Band hai")
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        stopWakeSession()
        stopCommandSession()
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
        override fun onPartialResult(hypothesis: String?) {
            if (state != State.WAKE) return
            if (containsWakeWord(parseJsonField(hypothesis, "partial"))) {
                executor.execute { onWakeDetected() }
            }
        }

        override fun onResult(hypothesis: String?) {
            if (state != State.WAKE) return
            if (containsWakeWord(parseJsonField(hypothesis, "text"))) {
                executor.execute { onWakeDetected() }
            }
        }

        override fun onFinalResult(hypothesis: String?) {
            if (state != State.WAKE) return
            if (containsWakeWord(parseJsonField(hypothesis, "text"))) {
                executor.execute { onWakeDetected() }
            }
        }

        override fun onError(exception: Exception?) {
            if (state == State.WAKE) {
                executor.execute { handleWakeError() }
            }
        }

        override fun onTimeout() {
            // No timeout is set on the wake session: nothing to do.
        }
    }

    // ------------------------------------------------------------------
    // Wake -> ack -> command (executor thread)
    // ------------------------------------------------------------------

    private fun onWakeDetected() {
        if (destroyed || state != State.WAKE) return
        state = State.ACK
        stopWakeSession()
        // A real wake proves listening works: clear the error counter.
        wakeErrorCount = 0
        prefs.wakeHitCount = prefs.wakeHitCount + 1
        setStatus("'Jarvis' suna gaya — command boliye")
        updateNotification("Command boliye...")
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
                    disarmAckFallback()
                    startCommandSession()
                }
                State.REPLY -> {
                    disarmReplyFallback()
                    startWakeSession()
                }
                else -> {
                    // Speech finished in some other state: nothing waits on it.
                }
            }
        }
    }

    private fun startCommandSession() {
        if (destroyed) return
        val loadedModel = model ?: return
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
            // Mic busy etc.: fall back to wake listening.
            startWakeSession()
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
                    stopCommandSession()
                    startWakeSession()
                }
            }
        }

        override fun onTimeout() {
            executor.execute {
                if (!destroyed && state == State.COMMAND) {
                    stopCommandSession()
                    startWakeSession()
                }
            }
        }

        private fun acceptCommand(text: String) {
            if (state != State.COMMAND || text.isBlank()) return
            executor.execute { onCommandHeard(text) }
        }
    }

    private fun onCommandHeard(text: String) {
        if (destroyed || state != State.COMMAND) return
        // Flip state BEFORE stopping the session, so the final-result
        // callback fired by stop() is ignored.
        state = State.REPLY
        stopCommandSession()
        val result = commandEngine.handle(text)
        if (!result.handled) {
            // Bug-2 fix: in background mode an unrecognized utterance
            // is almost always room noise, TV, or someone else talking.
            // Stay silent and resume wake listening instead of
            // announcing the "Phase 3" fallback to nobody.
            setStatus("Samajh nahi aaya — chup rehkar wapas 'Jarvis' sun raha hoon")
            updateNotification("Jarvis sun raha hai — 'Jarvis' boliye")
            startWakeSession()
            return
        }
        setStatus("Command mila — jawab de raha hoon")
        updateNotification("Jarvis jawab de raha hai...")
        speaker.speak(result.responseText)
        armReplyFallback(result.responseText)
    }

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
                    stopCommandSession()
                    startWakeSession()
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
                startCommandSession()
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
                startWakeSession()
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

    private fun containsWakeWord(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        return WAKE_WORDS.any { lower.contains(it) }
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

        private const val NOTIFICATION_ID = 4201
        private const val CHANNEL_ID = "jarvis_wake_channel"
        private const val SAMPLE_RATE = 16000.0f

        private const val ACK_TEXT = "Haan, boliye"
        private const val COMMAND_SILENCE_TIMEOUT_MS = 2_500L
        private const val COMMAND_HARD_CAP_MS = 7_000L
        private const val COMMAND_WATCHDOG_TICK_MS = 400L
        private const val ACK_FALLBACK_MS = 3_500L
        private const val REPLY_FALLBACK_BASE_MS = 4_000L
        private const val REPLY_FALLBACK_PER_CHAR_MS = 110L
        private const val REPLY_FALLBACK_MAX_MS = 20_000L
        private const val WAKE_RESTART_DELAY_MS = 1_500L
        private const val MAX_WAKE_ERRORS = 5

        /** Wake-word spellings the Hindi model / recognizers produce. */
        private val WAKE_WORDS = listOf(
            "जार्विस", "जारविस", "জার্ভিস", "জারবিস", "jarvis"
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
    }
}
