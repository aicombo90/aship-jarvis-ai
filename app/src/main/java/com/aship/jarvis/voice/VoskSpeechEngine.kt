package com.aship.jarvis.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.concurrent.Executors

/**
 * Speech input path B: Vosk, fully offline once a model is downloaded
 * (see [ModelManager]). The model + recognizer are created lazily on a
 * background thread the first time listening is requested; if the model
 * is missing or anything fails, the error is reported via the listener
 * and [SpeechEngineFactory] users can fall back to the system engine.
 */
class VoskSpeechEngine(
    context: Context,
    private val modelManager: ModelManager,
    private val language: ModelLanguage = ModelLanguage.HINDI
) : SpeechEngine {

    override var listener: SpeechEngineListener? = null
    override val displayName: String = "Vosk offline (${language.label})"

    @Suppress("unused")
    private val appContext: Context = context.applicationContext

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var model: Model? = null
    private var speechService: SpeechService? = null

    @Volatile
    private var initStarted = false

    @Volatile
    private var initFailed = false

    @Volatile
    private var pendingListen = false

    override fun startListening() {
        val service = speechService
        if (service != null) {
            startServiceListening(service)
            return
        }
        if (initFailed) {
            listener?.onError("Offline model load nahi ho paya.")
            return
        }
        pendingListen = true
        if (!initStarted) {
            initStarted = true
            initAsync()
        }
    }

    private fun initAsync() {
        executor.execute {
            try {
                if (!modelManager.isModelReady(language)) {
                    initFailed = true
                    pendingListen = false
                    mainHandler.post {
                        listener?.onError(
                            "Offline ${language.label} model abhi download nahi hua. " +
                                "Neeche wala button dabakar download kijiye."
                        )
                    }
                    return@execute
                }
                val loadedModel = Model(modelManager.modelDir(language).absolutePath)
                val recognizer = Recognizer(loadedModel, SAMPLE_RATE)
                val service = SpeechService(recognizer, SAMPLE_RATE)
                model = loadedModel
                speechService = service
                if (pendingListen) {
                    pendingListen = false
                    mainHandler.post { startServiceListening(service) }
                }
            } catch (t: Throwable) {
                initFailed = true
                pendingListen = false
                mainHandler.post { listener?.onError("Vosk start nahi ho paya: ${t.message}") }
            }
        }
    }

    private fun startServiceListening(service: SpeechService) {
        try {
            service.startListening(voskListener)
            listener?.onListeningStateChanged(true)
        } catch (t: Throwable) {
            listener?.onError("Mic start nahi ho paya: ${t.message}")
        }
    }

    override fun stopListening() {
        try {
            speechService?.stop()
        } catch (t: Throwable) {
            // Already stopped: nothing to do.
        }
        listener?.onListeningStateChanged(false)
    }

    override fun destroy() {
        try {
            speechService?.stop()
        } catch (t: Throwable) {
            // Ignore while tearing down.
        }
        try {
            speechService?.shutdown()
        } catch (t: Throwable) {
            // Ignore while tearing down.
        }
        speechService = null
        try {
            model?.close()
        } catch (t: Throwable) {
            // Ignore while tearing down.
        }
        model = null
        executor.shutdown()
    }

    private val voskListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val text = parseJsonField(hypothesis, "partial")
            if (text.isNotBlank()) {
                listener?.onPartialResult(text)
            }
        }

        override fun onResult(hypothesis: String?) {
            val text = parseJsonField(hypothesis, "text")
            if (text.isNotBlank()) {
                listener?.onFinalResult(text)
            }
        }

        override fun onFinalResult(hypothesis: String?) {
            val text = parseJsonField(hypothesis, "text")
            if (text.isNotBlank()) {
                listener?.onFinalResult(text)
            }
            listener?.onListeningStateChanged(false)
        }

        override fun onError(exception: Exception?) {
            listener?.onListeningStateChanged(false)
            listener?.onError("Vosk error: ${exception?.message}")
        }

        override fun onTimeout() {
            listener?.onListeningStateChanged(false)
        }
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
        private const val SAMPLE_RATE = 16000.0f
    }
}
