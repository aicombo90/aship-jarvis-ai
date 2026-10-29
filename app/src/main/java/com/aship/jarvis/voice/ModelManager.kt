package com.aship.jarvis.voice

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** A downloadable Vosk model pack. */
enum class ModelLanguage(
    val folderName: String,
    val zipUrl: String,
    val label: String
) {
    HINDI(
        "vosk-model-small-hi-0.22",
        "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip",
        "Hindi"
    ),
    ENGLISH(
        "vosk-model-small-en-us-0.15",
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
        "English"
    )
}

/**
 * Downloads and unpacks Vosk models into app-private storage
 * (filesDir/vosk-models/<model folder>) the first time they are needed.
 * All functions are blocking: call them from a background thread.
 */
class ModelManager(context: Context) {

    private val modelsRoot: File = File(context.applicationContext.filesDir, "vosk-models")

    fun modelDir(language: ModelLanguage): File = File(modelsRoot, language.folderName)

    fun isModelReady(language: ModelLanguage): Boolean {
        val dir = modelDir(language)
        return dir.isDirectory && (dir.listFiles()?.isNotEmpty() == true)
    }

    interface DownloadCallback {
        fun onProgress(message: String)
        fun onComplete(language: ModelLanguage)
        fun onError(message: String)
    }

    /** Downloads + unzips the model unless it is already present. Blocking. */
    fun downloadModel(language: ModelLanguage, callback: DownloadCallback) {
        try {
            if (isModelReady(language)) {
                callback.onComplete(language)
                return
            }
            modelsRoot.mkdirs()
            val zipFile = File(modelsRoot, language.folderName + ".zip")
            callback.onProgress("${language.label} model download ho raha hai...")
            downloadFile(language.zipUrl, zipFile)
            callback.onProgress("${language.label} model taiyaar ho raha hai (unzip)...")
            unzip(zipFile, modelsRoot)
            zipFile.delete()
            if (isModelReady(language)) {
                callback.onComplete(language)
            } else {
                callback.onError("${language.label} model unzip ke baad nahi mila.")
            }
        } catch (e: Exception) {
            callback.onError("Model download fail: ${e.message}")
        }
    }

    /**
     * Downloads with resume + retries: if the connection drops mid-file
     * (common on mobile data), the next attempt continues from the bytes
     * already on disk via an HTTP Range request instead of starting over.
     */
    private fun downloadFile(urlString: String, destination: File) {
        var lastError: Exception? = null
        repeat(6) {
            try {
                downloadAttempt(urlString, destination)
                return
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(2_000)
            }
        }
        throw lastError ?: IllegalStateException("Download fail ho gaya.")
    }

    private fun downloadAttempt(urlString: String, destination: File) {
        val existing = if (destination.exists()) destination.length() else 0L
        val connection = URL(urlString).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            if (existing > 0) {
                connection.setRequestProperty("Range", "bytes=$existing-")
            }
            connection.connect()
            val code = connection.responseCode
            // 206 = resuming; 200 = server ignored Range, restart from zero.
            val append = existing > 0 && code == 206
            if (code !in 200..299) {
                throw IllegalStateException("Server response $code")
            }
            connection.inputStream.use { input ->
                FileOutputStream(destination, append).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        val canonicalTarget = targetDir.canonicalPath
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val outFile = File(targetDir, entry.name)
                // Zip-slip guard: never write outside the models folder.
                if (!outFile.canonicalPath.startsWith(canonicalTarget)) {
                    throw IllegalStateException("Invalid zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
            }
        }
    }
}
