package com.aship.jarvis

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.aship.jarvis.commands.CommandEngine
import com.aship.jarvis.prefs.AppPrefs
import com.aship.jarvis.prefs.JarvisMode
import com.aship.jarvis.speaker.TtsSpeaker
import com.aship.jarvis.voice.ModelLanguage
import com.aship.jarvis.voice.ModelManager
import com.aship.jarvis.voice.SpeechEngine
import com.aship.jarvis.voice.SpeechEngineFactory
import com.aship.jarvis.voice.SpeechEngineListener
import java.util.concurrent.Executors

/**
 * ASHIP JARVIS AI — Phase 1 main screen.
 *
 * Tap the mic button (or type a command), Jarvis runs it through the
 * offline CommandEngine and speaks the reply. Mode (Offline / Online /
 * Auto) is picked here and remembered between launches.
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var recognizedText: TextView
    private lateinit var responseText: TextView
    private lateinit var micButton: Button
    private lateinit var commandInput: EditText
    private lateinit var modeGroup: RadioGroup

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
    }
}
