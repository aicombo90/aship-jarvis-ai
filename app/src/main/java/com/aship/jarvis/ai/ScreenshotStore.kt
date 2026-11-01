package com.aship.jarvis.ai

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.aship.jarvis.access.JarvisAccessibilityService
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v40: SCREENSHOT ka disk-wala bandobosto — v39 me
 * tasveer sirf yaad (memory) me rehti thi
 * ([JarvisAccessibilityService] ka lastCleanScreenshot)
 * aur kahin bhejni/share karni NAHI thi. Ab do kaam:
 *
 * 1. Taazi tasveer lena — [captureFresh]: service ke
 *    capture wale raaste ka bandha (sync) intezaar,
 *    bilkul [ScreenAgent] ke screenshotSync jaisa
 *    (wahi latch, wahi ~6 s seema). Hamesha kisi
 *    background thread se bulana — tasveer ka jawab
 *    service ke main-executor se aata hai, isliye
 *    bulane wala thread main thread NAHI hona chahiye.
 *
 * 2. Tasveer ko rakhna — gallery me [saveToGallery]
 *    (MediaStore, "ASHIP JARVIS" wala album) aur
 *    share ke liye cache me JPEG [writeShareJpeg]
 *    (FileProvider usi file ko content:// bankar
 *    deta hai — file_paths.xml me cache-path pehle
 *    se hai).
 *
 * "Screen surakshit hai kya" wali jaanch
 * ([screenLooksSecure]) v39 wala hi mila-jula niyam
 * hai: tasveer lagbhag kaali ([ScreenVision]) AUR
 * node-saar me koi kaam ka hissa nahi — dono ek
 * saath hon tabhi FLAG_SECURE mana jata hai. Sirf
 * kaali tasveer (andhere me chal raha video, kaali
 * photo) surakshit NAHI mani jati, bilkul ScreenAgent
 * ki tarah.
 */
object ScreenshotStore {

    /** Gallery me Jarvis ke screenshoton wala album (Pictures ke andar). */
    const val GALLERY_ALBUM = "ASHIP JARVIS"

    /**
     * Is pal ki taazi screen tasveer — service band ho,
     * dar-seema lage, ya jawab hi na aaye to null.
     * Bandha call hai: background thread se hi bulana.
     */
    fun captureFresh(context: Context): Bitmap? {
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        var delivered = false
        try {
            JarvisAccessibilityService.captureScreenshot(context) { result ->
                bitmap = result
                delivered = true
                latch.countDown()
            }
        } catch (t: Throwable) {
            return null
        }
        val completed = try {
            latch.await(CAPTURE_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        return if (completed && delivered) bitmap else null
    }

    /**
     * Ye tasveer surakshit (FLAG_SECURE) screen ki hai
     * kya — v39 wala mila-jula niyam: kaali tasveer +
     * node-saar me kaam ka hissa na hona. Saar hi na
     * mile to kaali tasveer akeli hi kaafi mani jati
     * hai (surakshit screen par ped bhi khaali hota hai).
     * Bandha call hai: background thread se hi bulana.
     */
    fun screenLooksSecure(context: Context, bitmap: Bitmap): Boolean {
        if (!ScreenVision.looksSecureOrBlack(bitmap)) return false
        val latch = CountDownLatch(1)
        var snapshot: JarvisAccessibilityService.ScreenSnapshot? = null
        var delivered = false
        try {
            JarvisAccessibilityService.captureScreen(context) { result ->
                snapshot = result
                delivered = true
                latch.countDown()
            }
        } catch (t: Throwable) {
            return true
        }
        val completed = try {
            latch.await(SNAPSHOT_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        if (!completed || !delivered) return true
        val elements = snapshot?.elements ?: return true
        return elements.none { it.clickable || it.editable }
    }

    /**
     * Tasveer gallery me rakho — file naam
     * screenshot_<samay>.jpg, album [GALLERY_ALBUM]।
     *
     * Android 10+ (API 29+): MediaStore me RELATIVE_PATH
     * "Pictures/ASHIP JARVIS" ke saath IS_PENDING wala
     * pakka raasta — koi storage permission nahi chahiye,
     * tasveer seedha aam gallery me dikhti hai.
     * Usse purane phone (API 26–28) par woh raasta hai
     * hi nahi (aur WRITE_EXTERNAL_STORAGE humne liya
     * nahi hai), isliye wahan app ke apne Pictures hisse
     * me file likhkar media-scanner ko de dete hain —
     * gallery me phir bhi dikh jati hai. Ye us purane
     * raaste ki sabse kareebi barabar (equivalent) hai.
     */
    fun saveToGallery(context: Context, bitmap: Bitmap): Boolean {
        val name = "screenshot_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
            ".jpg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, bitmap, name)
        } else {
            saveViaAppDir(context, bitmap, name)
        }
    }

    /** API 29+: MediaStore wala asal raasta (IS_PENDING ke saath). */
    private fun saveViaMediaStore(
        context: Context,
        bitmap: Bitmap,
        name: String
    ): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + GALLERY_ALBUM
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            )
        } catch (t: Throwable) {
            null
        } ?: return false
        val written = try {
            resolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            } ?: false
        } catch (t: Throwable) {
            false
        }
        if (!written) {
            try {
                resolver.delete(uri, null, null)
            } catch (t: Throwable) {
                // Aadhi bandhi entry saaf na ho paye to bhi
                // natija "nahi bacha" hi rahega.
            }
            return false
        }
        return try {
            val done = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** API 26–28: app ke apne Pictures hisse + media scan (gallery me dikhega). */
    private fun saveViaAppDir(
        context: Context,
        bitmap: Bitmap,
        name: String
    ): Boolean {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: return false
        val dir = File(base, GALLERY_ALBUM)
        return try {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, name)
            val written = FileOutputStream(file).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            }
            if (written) {
                try {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(file.absolutePath),
                        arrayOf("image/jpeg"),
                        null
                    )
                } catch (t: Throwable) {
                    // Scan na ho to file phir bhi disk par hai —
                    // gallery me der se dikhegi, natija jhootha nahi.
                }
            }
            written
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Share ke liye tasveer cache me JPEG bankar rakho —
     * hamesha wahi ek file (share_screenshot.jpg), har
     * share par taazi tasveer usi par likhi jati hai.
     * FileProvider isi file ka content:// URI deta hai
     * (file_paths.xml ki cache-path "." poora cache
     * hissa gher-ti hai, is up-folder samet)।
     */
    fun writeShareJpeg(context: Context, bitmap: Bitmap): File? {
        return try {
            val dir = File(context.cacheDir, SHARE_DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, SHARE_FILE_NAME)
            val written = FileOutputStream(file).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            }
            if (written) file else null
        } catch (t: Throwable) {
            null
        }
    }

    /** Tasveer ke jawab ka adhiktam intezaar — ScreenAgent ke OP_WAIT_MS jitna hi. */
    private const val CAPTURE_WAIT_MS = 6_000L

    /** Node-saar ke jawab ka adhiktam intezaar. */
    private const val SNAPSHOT_WAIT_MS = 4_000L

    /** Gallery/share wali JPEG gunvatta — saaf, par aakar bandha. */
    private const val JPEG_QUALITY = 92

    private const val SHARE_DIR_NAME = "screenshots"
    private const val SHARE_FILE_NAME = "share_screenshot.jpg"
}
