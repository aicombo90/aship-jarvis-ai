package com.aship.jarvis.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlin.math.sqrt

/**
 * v41: Bangla offline ASR — sherpa-onnx ka OfflineRecognizer
 * Bangla Zipformer (int8) model par. Model ki file
 * [BanglaModelManager] app-private storage me rakhta hai;
 * yahan recognizer aalsi (lazy) roop se ek baar banta hai
 * aur service ke jeevan bhar dobara istemal hota hai.
 *
 * Ye model NON-STREAMING hai: pehle poora ek vakya record
 * hota hai (zyada se zyada ~15 s; bolna rukne ke ~1.2 s
 * baad khud ruk jata hai), phir ek saath decode hota hai.
 * Model card wali bandhi hui setting hi istemal hoti hai
 * (16 kHz / feature 80 / modified_beam_search, 4 raaste) —
 * wahi setting desktop JVM par jaanch kar sahi nikli thi:
 * "টর্চ চালু করো" bilkul waisa hi suna gaya tha.
 *
 * Saare kaam blocking hain — hamesha kisi background
 * thread se bulao, main thread ya service ke mukhya
 * executor se kabhi nahi.
 */
class BanglaZipformer(context: Context) {

    private val appContext = context.applicationContext
    private val modelManager = BanglaModelManager(appContext)

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var activeRecord: AudioRecord? = null

    @Volatile
    private var captureCancelled = false

    sealed class Outcome {
        /** Kuch suna gaya — text = pehchana hua vakya, decodeMs = sirf decode ka samay. */
        data class Heard(val text: String, val decodeMs: Long) : Outcome()

        /** Recording/decode chal to gaya par koi shabd nahi mila (ya record hi nahi hua). */
        object Empty : Outcome()

        /** Asli nakami — recognizer/mic bigda; is chain me dobara koshish bekaar hai. */
        data class Failed(val reason: String) : Outcome()
    }

    /**
     * Ek vakya suno aur use pehchano. Blocking: pehle
     * recording (zyada se zyada ~15 s), phir decode.
     * Model taiyaar na ho to Failed — bulane wala is
     * sthiti ko pehle hi rokta hai (isReady wali jaanch),
     * ye sirf suraksha ki aakhiri lakeer hai.
     */
    fun listenAndTranscribe(): Outcome {
        if (!modelManager.isReady()) {
            return Outcome.Failed("model not downloaded")
        }
        val samples = captureUtterance() ?: return Outcome.Empty
        if (samples.size < MIN_DECODE_SAMPLES) return Outcome.Empty
        val rec = ensureRecognizer()
            ?: return Outcome.Failed("recognizer ban nahi paya")
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            val decodeStart = SystemClock.uptimeMillis()
            rec.decode(stream)
            val decodeMs = SystemClock.uptimeMillis() - decodeStart
            val text = rec.getResult(stream).text.trim()
            return if (text.isEmpty()) {
                Outcome.Empty
            } else {
                Outcome.Heard(text, decodeMs)
            }
        } catch (t: Throwable) {
            return Outcome.Failed("decode: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            try {
                stream.release()
            } catch (t: Throwable) {
                // Band karte huey gadbad — natija pehle mil chuka.
            }
        }
    }

    /**
     * Chal rahi recording ko beech me roko (service band
     * ho rahi ho ya session badal gaya ho). Agli
     * [listenAndTranscribe] apna jhanda khud saaf karti hai.
     */
    fun cancelCapture() {
        captureCancelled = true
        try {
            activeRecord?.stop()
        } catch (t: Throwable) {
            // Pehle hi ruka hua — kuch nahi karna.
        }
    }

    /** Recognizer ka native hissa chhodo (service teardown par). */
    fun release() {
        cancelCapture()
        synchronized(this) {
            try {
                recognizer?.release()
            } catch (t: Throwable) {
                // Native pehle hi gaya — teardown nahi rukega.
            }
            recognizer = null
        }
    }

    private fun ensureRecognizer(): OfflineRecognizer? {
        recognizer?.let { return it }
        synchronized(this) {
            recognizer?.let { return it }
            val created = try {
                val dir = modelManager.modelDir().absolutePath
                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(
                        sampleRate = SAMPLE_RATE,
                        featureDim = 80
                    ),
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = "$dir/${BanglaModelManager.FILE_ENCODER}",
                            decoder = "$dir/${BanglaModelManager.FILE_DECODER}",
                            joiner = "$dir/${BanglaModelManager.FILE_JOINER}"
                        ),
                        tokens = "$dir/${BanglaModelManager.FILE_TOKENS}",
                        numThreads = NUM_THREADS,
                        modelType = "zipformer"
                    ),
                    decodingMethod = "modified_beam_search",
                    maxActivePaths = 4
                )
                OfflineRecognizer(assetManager = null, config = config)
            } catch (t: Throwable) {
                null
            }
            recognizer = created
            return created
        }
    }

    /**
     * Mic se ek vakya record karo. Rukne ke niyam:
     *  - bolna shuru hone ke baad ~1.2 s ki khamoshi = vakya khatm;
     *  - 6 s tak kuch na suna jaye to khaali wapas (Empty wala raasta);
     *  - kul seema ~15 s (model ki ~20 s wali seema se surakshit neeche).
     * Mic na khule to null — bulane wala use Empty ki tarah
     * leta hai taaki purana recognizer wala raasta chal sake.
     */
    private fun captureUtterance(): FloatArray? {
        captureCancelled = false
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return null
        val bufferBytes = maxOf(minBuffer, CHUNK_SAMPLES * 2 * 4)
        var recorder: AudioRecord? = null
        for (source in intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )) {
            try {
                val candidate = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    recorder = candidate
                    break
                }
                candidate.release()
            } catch (t: Throwable) {
                // Agla source aazmao.
            }
        }
        val active = recorder ?: return null
        val samples = FloatArray(MAX_SAMPLES)
        var total = 0
        var speechSeen = false
        var silenceChunks = 0
        var waitedChunks = 0
        val chunk = ShortArray(CHUNK_SAMPLES)
        try {
            activeRecord = active
            active.startRecording()
            while (!captureCancelled && total < MAX_SAMPLES) {
                val toRead = minOf(chunk.size, MAX_SAMPLES - total)
                val read = active.read(chunk, 0, toRead)
                if (read <= 0) {
                    // Mic se kuch nahi aa raha — atke rehne
                    // ke bajay khaali wapas jao.
                    waitedChunks++
                    if (waitedChunks > NO_SPEECH_CHUNKS) break
                    Thread.sleep(50)
                    continue
                }
                var sumSquares = 0.0
                for (i in 0 until read) {
                    val v = chunk[i] / 32768.0f
                    samples[total + i] = v
                    sumSquares += v * v
                }
                total += read
                val rms = sqrt(sumSquares / read).toFloat()
                if (rms >= SPEECH_RMS) {
                    speechSeen = true
                    silenceChunks = 0
                } else if (speechSeen) {
                    silenceChunks++
                    if (silenceChunks >= END_SILENCE_CHUNKS) break
                } else {
                    waitedChunks++
                    if (waitedChunks >= NO_SPEECH_CHUNKS) break
                }
            }
        } catch (t: Throwable) {
            // Recording beech me bigdi — jo aaya wahi kaafi
            // ho to decode hoga, warna Empty wala raasta.
        } finally {
            try {
                active.stop()
            } catch (t: Throwable) {
                // Pehle hi ruka hua.
            }
            try {
                active.release()
            } catch (t: Throwable) {
                // Kuch nahi karna.
            }
            if (activeRecord === active) activeRecord = null
        }
        if (captureCancelled || !speechSeen) return null
        return samples.copyOf(total)
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val NUM_THREADS = 4

        /** Ek tukda = 100 ms (1600 sample @ 16 kHz). */
        private const val CHUNK_SAMPLES = 1600

        /** Kul recording ki seema ~15 s. */
        private const val MAX_SAMPLES = SAMPLE_RATE * 15

        /** Itna chhota tukda decode ke kaabil nahi (~0.3 s). */
        private const val MIN_DECODE_SAMPLES = SAMPLE_RATE * 3 / 10

        /** Bolna shuru hi na ho to 6 s me haar mano. */
        private const val NO_SPEECH_CHUNKS = 60

        /** Bolne ke baad 1.2 s khamoshi = vakya poora. */
        private const val END_SILENCE_CHUNKS = 12

        /**
         * Awaaz ki pehchan wali hadd — isse upar RMS ho to
         * "bolna" mana jata hai. Aam kamre ke shor se upar,
         * aam bol-chaal se neeche rakha hai; phone par
         * asal test ke baad badla ja sakta hai.
         */
        private const val SPEECH_RMS = 0.02f
    }
}
