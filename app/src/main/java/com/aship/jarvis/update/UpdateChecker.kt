package com.aship.jarvis.update

import android.os.Handler
import android.os.Looper
import com.aship.jarvis.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * In-app update check: GitHub Releases is the source of truth.
 *
 * Every feature build is also published as a GitHub Release with
 * tag "v<versionCode>" (e.g. v2) and the APK attached as an asset
 * named "aship-jarvis-v<versionCode>.apk" (v17 se versioned naam;
 * usse pehle "app-debug.apk" tha — isiliye asset dhoondhne me
 * koi bhi ".apk" naam chalega, neeche dekho). This class fetches
 * the latest release from the public GitHub API (no auth,
 * public repo) and compares the tag number with this app's
 * VERSION_CODE.
 *
 * The asset's browser_download_url is taken from the release JSON —
 * never hardcoded — so the flow keeps working whatever the release
 * naming around it.
 *
 * Any network / HTTP / parse problem becomes [Result.Failed]; the
 * caller stays silent for the automatic check.
 */
object UpdateChecker {

    /** Everything the UI needs to offer one available update. */
    data class UpdateInfo(
        val versionCode: Int,
        val tagName: String,
        val downloadUrl: String
    )

    sealed interface Result {
        data class UpdateAvailable(val info: UpdateInfo) : Result
        object UpToDate : Result
        object Failed : Result
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Runs the check off the main thread; [callback] fires on main. */
    fun checkLatest(callback: (Result) -> Unit) {
        executor.execute {
            val result = fetchLatest()
            mainHandler.post { callback(result) }
        }
    }

    private fun fetchLatest(): Result {
        var connection: HttpURLConnection? = null
        return try {
            connection =
                (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    // GitHub API rejects requests without a User-Agent.
                    setRequestProperty("User-Agent", "ASHIP-JARVIS-AI-Android")
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Result.Failed
            } else {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                parseRelease(body)
            }
        } catch (e: Exception) {
            Result.Failed
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseRelease(body: String): Result {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            return Result.Failed
        }
        val tagName = json.optString("tag_name", "")
        val remoteCode = tagName.removePrefix("v").removePrefix("V").trim().toIntOrNull()
            ?: return Result.Failed
        if (remoteCode <= BuildConfig.VERSION_CODE) {
            return Result.UpToDate
        }
        val assets = json.optJSONArray("assets") ?: return Result.Failed
        // v17: asset ka naam versioned hai — pehle usi tag wale
        // naam ko pasand karo; na mile to koi bhi ".apk" asset
        // chalega (purane "app-debug.apk" wale release bhi isse
        // pehchan me rahenge).
        val expectedName = "aship-jarvis-v$remoteCode.apk"
        var fallbackUrl = ""
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val url = asset.optString("browser_download_url", "")
            if (url.isBlank()) continue
            if (asset.optString("name") == expectedName) {
                return Result.UpdateAvailable(UpdateInfo(remoteCode, tagName, url))
            }
            if (fallbackUrl.isEmpty() &&
                asset.optString("name").endsWith(".apk")
            ) {
                fallbackUrl = url
            }
        }
        return if (fallbackUrl.isNotEmpty()) {
            Result.UpdateAvailable(UpdateInfo(remoteCode, tagName, fallbackUrl))
        } else {
            Result.Failed
        }
    }

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/aicombo90/aship-jarvis-ai/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
}
