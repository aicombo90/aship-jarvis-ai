package com.aship.jarvis.voice

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * v41: Bangla offline ASR model (Bangla Zipformer, sherpa-onnx)
 * ka download bandobosto — Vosk wale [ModelManager] ki hi
 * tarz par: model APK ke andar NAHI hai (int8 kul ~71 MB),
 * zaroorat par HuggingFace se app-private storage
 * (filesDir/bangla-zipformer/) me aata hai, isliye uninstall
 * ke saath khud saaf ho jata hai.
 *
 * Chaar file: encoder.onnx / decoder.onnx / joiner.onnx /
 * tokens.txt. Har file ka asal size yahin bandha hai —
 * "taiyaar" tabhi mana jata hai jab chaaron poori size par
 * maujood hon, isliye aadha download kabhi taiyaar nahi
 * kehlayega. Download resume wala hai (HTTP Range): net
 * beech me kate to agli koshish wahi se aage badhti hai.
 *
 * Haalat SharedPreferences "jarvis_bangla_model" me bhi
 * band hoti hai taaki main screen apni 2-second wali
 * refresh par downloading/percent/failed dikha sake.
 * Saare kaam blocking hain — background thread se bulao.
 */
class BanglaModelManager(context: Context) {

    private val appContext = context.applicationContext

    private val modelRoot: File =
        File(appContext.filesDir, "bangla-zipformer")

    fun modelDir(): File = modelRoot

    fun modelFile(fileName: String): File = File(modelRoot, fileName)

    /** Chaaron file poori size par hon tabhi model taiyaar. */
    fun isReady(): Boolean = FILES.all { spec ->
        val f = modelFile(spec.localName)
        f.isFile && f.length() == spec.sizeBytes
    }

    /** Ab tak kitne byte sahi roop me aa chuke hain (file prati bandhi size tak). */
    fun downloadedBytes(): Long = FILES.sumOf { spec ->
        val f = modelFile(spec.localName)
        if (f.isFile) minOf(f.length(), spec.sizeBytes) else 0L
    }

    fun downloadPercent(): Int {
        val pct = downloadedBytes() * 100L / TOTAL_BYTES
        return pct.coerceIn(0L, 100L).toInt()
    }

    /**
     * Download is pal chal raha hai kya — haalat wali
     * entry taaza (3 minute ke andar ki) ho tabhi haan:
     * app beech me band ho jaye to purani "downloading"
     * hamesha ke liye atki nahi rahegi, user dobara
     * koshish kar sakega (resume se nuqsan nahi).
     */
    fun isDownloading(): Boolean {
        val prefs = statePrefs()
        if (prefs.getString(KEY_STATE, null) != STATE_DOWNLOADING) return false
        val updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
        return System.currentTimeMillis() - updatedAt < STALE_DOWNLOAD_MS
    }

    fun lastDownloadFailed(): Boolean =
        statePrefs().getString(KEY_STATE, null) == STATE_FAILED

    interface DownloadCallback {
        fun onProgress(percent: Int)
        fun onComplete()
        fun onError(message: String)
    }

    /**
     * Chaaron file ek-ek karke download karo (jo taiyaar
     * hai use chhodo). Blocking — background thread se.
     */
    fun download(callback: DownloadCallback) {
        try {
            if (isReady()) {
                writeState(STATE_READY, 100)
                callback.onComplete()
                return
            }
            modelRoot.mkdirs()
            writeState(STATE_DOWNLOADING, downloadPercent())
            callback.onProgress(downloadPercent())
            for (spec in FILES) {
                val target = modelFile(spec.localName)
                if (target.isFile && target.length() == spec.sizeBytes) {
                    continue
                }
                if (target.isFile && target.length() > spec.sizeBytes) {
                    // Kharab (zyada lambi) file par resume kabhi
                    // nahi chalega — shuru se banao.
                    target.delete()
                }
                downloadFileWithResume(spec.url, target, spec.sizeBytes, callback)
                if (!target.isFile || target.length() != spec.sizeBytes) {
                    throw IllegalStateException(
                        "${spec.localName} poora nahi aaya " +
                            "(${target.length()} / ${spec.sizeBytes} byte)"
                    )
                }
            }
            if (isReady()) {
                writeState(STATE_READY, 100)
                callback.onProgress(100)
                callback.onComplete()
            } else {
                throw IllegalStateException("Model file poori nahi aayi.")
            }
        } catch (e: Exception) {
            writeState(STATE_FAILED, downloadPercent())
            callback.onError("Bangla model download fail: ${e.message}")
        }
    }

    private fun downloadFileWithResume(
        urlString: String,
        destination: File,
        expectedBytes: Long,
        callback: DownloadCallback
    ) {
        var lastError: Exception? = null
        repeat(6) {
            if (destination.isFile && destination.length() == expectedBytes) return
            try {
                downloadAttempt(urlString, destination, callback)
                if (destination.length() == expectedBytes) return
                // Server ne kam byte diye aur stream band ho
                // gaya — resume wali koshish phir chalegi.
                lastError = IllegalStateException(
                    "Adhoora download (${destination.length()} / $expectedBytes byte)"
                )
            } catch (e: Exception) {
                lastError = e
            }
            Thread.sleep(2_000)
        }
        throw lastError ?: IllegalStateException("Download fail ho gaya.")
    }

    private fun downloadAttempt(
        urlString: String,
        destination: File,
        callback: DownloadCallback
    ) {
        val existing = if (destination.exists()) destination.length() else 0L
        val connection = URL(urlString).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            // HuggingFace ka resolve URL ek CDN par redirect
            // karta hai — woh apne aap follow hota hai.
            connection.instanceFollowRedirects = true
            if (existing > 0) {
                connection.setRequestProperty("Range", "bytes=$existing-")
            }
            connection.connect()
            val code = connection.responseCode
            // 206 = resume ho raha hai; 200 = server ne Range
            // nahi mana, file shuru se likho.
            val append = existing > 0 && code == 206
            if (code !in 200..299) {
                throw IllegalStateException("Server response $code")
            }
            connection.inputStream.use { input ->
                FileOutputStream(destination, append).use { output ->
                    val buffer = ByteArray(16_384)
                    var lastReportedPercent = downloadPercent()
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        val pct = downloadPercent()
                        if (pct != lastReportedPercent) {
                            lastReportedPercent = pct
                            writeState(STATE_DOWNLOADING, pct)
                            callback.onProgress(pct)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun statePrefs() =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun writeState(state: String, percent: Int) {
        try {
            statePrefs().edit()
                .putString(KEY_STATE, state)
                .putInt(KEY_PERCENT, percent)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply()
        } catch (t: Throwable) {
            // Haalat likhna bonus hai — download isse nahi rukega.
        }
    }

    private data class FileSpec(
        val localName: String,
        val url: String,
        val sizeBytes: Long
    )

    companion object {
        const val FILE_ENCODER = "encoder.onnx"
        const val FILE_DECODER = "decoder.onnx"
        const val FILE_JOINER = "joiner.onnx"
        const val FILE_TOKENS = "tokens.txt"

        private const val PREFS_NAME = "jarvis_bangla_model"
        private const val KEY_STATE = "state"
        private const val KEY_PERCENT = "percent"
        private const val KEY_UPDATED_AT = "updated_at"

        private const val STATE_DOWNLOADING = "downloading"
        private const val STATE_READY = "ready"
        private const val STATE_FAILED = "failed"

        private const val STALE_DOWNLOAD_MS = 180_000L

        private const val HF_BASE =
            "https://huggingface.co/SayedShaun/bangla-zipformer-onnx/resolve/main"

        /** Model card (sherpa-onnx 1.13.8 par jaancha hua) ke asal size. */
        private val FILES = listOf(
            FileSpec(FILE_ENCODER, "$HF_BASE/int8/encoder.onnx", 72_828_389L),
            FileSpec(FILE_DECODER, "$HF_BASE/int8/decoder.onnx", 540_689L),
            FileSpec(FILE_JOINER, "$HF_BASE/int8/joiner.onnx", 259_417L),
            FileSpec(FILE_TOKENS, "$HF_BASE/tokens.txt", 6_578L)
        )

        val TOTAL_BYTES: Long = FILES.sumOf { it.sizeBytes }
    }
}
