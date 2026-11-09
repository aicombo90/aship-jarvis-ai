package com.aship.jarvis.ai

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * v77: home screen ka weather card — Open-Meteo (muft,
 * koi API key nahi, koi nayi permission nahi — INTERNET
 * pehle se ghoshit hai)।
 *
 * - Jagah: "Baduria, West Bengal" — Open-Meteo ki muft
 *   geocoding API se ek baar lat/lon nikalo, phir
 *   SharedPreferences "jarvis_weather" me bandh do
 *   (dobara net kharch nahi)। Geocode na mile to
 *   Baduria ka lagbhag wala nirdeshank (22.74, 88.78)
 *   — jagah ka sarvajanik nirdeshank hai, koi niji
 *   sthan nahi।
 * - Mausam: Open-Meteo forecast API — aaj ka tapman,
 *   feels-like, zyada/kam, nami, weather code; timezone
 *   Asia/Kolkata। Cache 3 ghante tak (puraana cache
 *   dikhaya jata hai, naya na mile to bhi)।
 * - Offline ya fetch fail: kabhi jhootha mausam nahi —
 *   cache ho to wahi (puraana), warna card me "—"
 *   rehta hai। Sab kuch apne thread par, har galti
 *   pakdi hui — fetch ki gadbad app ko kabhi nahi
 *   rokegi, na crash karegi।
 */
object WeatherFetcher {

    data class Weather(
        val tempC: Int,
        val feelsC: Int,
        val highC: Int,
        val lowC: Int,
        val humidity: Int,
        val code: Int
    )

    private const val PREFS_NAME = "jarvis_weather"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_CACHE = "cache_json"
    private const val KEY_CACHE_MS = "cache_ms"
    private const val CACHE_MAX_MS = 3L * 60 * 60 * 1000 // 3 ghante
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    // Baduria, West Bengal ka lagbhag nirdeshank — sirf
    // tab jab geocoding API se kuch na mile।
    private const val FALLBACK_LAT = 22.74
    private const val FALLBACK_LON = 88.78

    /** 3 ghante se taaza cache ho to wahi, warna null। */
    fun cached(context: Context): Weather? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val age = System.currentTimeMillis() - prefs.getLong(KEY_CACHE_MS, 0L)
            if (age < 0L || age > CACHE_MAX_MS) return null
            val raw = prefs.getString(KEY_CACHE, null) ?: return null
            parseWeather(JSONObject(raw))
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Taaza mausam lao — apne thread par chalta hai,
     * [onResult] usi thread par bulaaya jata hai (UI
     * wala apne mainHandler se le)। Fail ho to taaza
     * cache (chaahe 3 ghante se puraana ho) ya null।
     */
    fun fetch(context: Context, onResult: (Weather?) -> Unit) {
        Thread {
            val result = try {
                val (lat, lon) = resolveLatLon(context)
                val url =
                    "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,relative_humidity_2m," +
                        "apparent_temperature,weather_code" +
                        "&daily=temperature_2m_max,temperature_2m_min" +
                        "&timezone=" + URLEncoder.encode("Asia/Kolkata", "UTF-8") +
                        "&forecast_days=1"
                val body = httpGet(url) ?: throw IllegalStateException("no body")
                val weather = parseWeather(JSONObject(body))
                saveCache(context, body)
                weather
            } catch (t: Throwable) {
                cachedStale(context)
            }
            try {
                onResult(result)
            } catch (t: Throwable) {
                // Callback ki gadbad bhi yahin khatm।
            }
        }.apply { isDaemon = true }.start()
    }

    /** Cache chahe kitna bhi puraana ho — fail hone par aakhiri sahara। */
    private fun cachedStale(context: Context): Weather? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_CACHE, null) ?: return null
            parseWeather(JSONObject(raw))
        } catch (t: Throwable) {
            null
        }
    }

    private fun resolveLatLon(context: Context): Pair<Double, Double> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_LAT) && prefs.contains(KEY_LON)) {
            return Pair(
                prefs.getFloat(KEY_LAT, 0f).toDouble(),
                prefs.getFloat(KEY_LON, 0f).toDouble()
            )
        }
        val found = try {
            val q = URLEncoder.encode("Baduria, West Bengal", "UTF-8")
            val body = httpGet(
                "https://geocoding-api.open-meteo.com/v1/search" +
                    "?name=$q&count=1&language=en&format=json"
            ) ?: return Pair(FALLBACK_LAT, FALLBACK_LON)
            val results = JSONObject(body).optJSONArray("results") ?: return Pair(
                FALLBACK_LAT,
                FALLBACK_LON
            )
            if (results.length() == 0) return Pair(FALLBACK_LAT, FALLBACK_LON)
            val first = results.getJSONObject(0)
            Pair(first.optDouble("latitude", FALLBACK_LAT), first.optDouble("longitude", FALLBACK_LON))
        } catch (t: Throwable) {
            Pair(FALLBACK_LAT, FALLBACK_LON)
        }
        try {
            prefs.edit()
                .putFloat(KEY_LAT, found.first.toFloat())
                .putFloat(KEY_LON, found.second.toFloat())
                .apply()
        } catch (t: Throwable) {
            // Cache na bandhe to bhi mausam aayega।
        }
        return found
    }

    private fun parseWeather(root: JSONObject): Weather {
        // Zaroori khaana na mile to getDouble/getInt khud
        // JSONException phenkta hai — fetch use pakad kar
        // cache/null par girta hai; kabhi "0°" jhooth nahi।
        val current = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")
        val maxArr = daily.getJSONArray("temperature_2m_max")
        val minArr = daily.getJSONArray("temperature_2m_min")
        return Weather(
            tempC = current.getDouble("temperature_2m").toInt(),
            feelsC = current.getDouble("apparent_temperature").toInt(),
            highC = maxArr.getDouble(0).toInt(),
            lowC = minArr.getDouble(0).toInt(),
            humidity = current.getInt("relative_humidity_2m"),
            code = current.getInt("weather_code")
        )
    }

    private fun saveCache(context: Context, body: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CACHE, body)
                .putLong(KEY_CACHE_MS, System.currentTimeMillis())
                .apply()
        } catch (t: Throwable) {
            // Cache bonus hai।
        }
    }

    private fun httpGet(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) sb.append(line)
            reader.close()
            sb.toString()
        } catch (t: Throwable) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (t: Throwable) {
                // Chup-chaap band।
            }
        }
    }

    /** WMO weather code -> Bangla haalat (Open-Meteo ke niyam se)। */
    fun conditionBangla(code: Int): String {
        return when (code) {
            0 -> "পরিষ্কার আকাশ"
            1 -> "প্রায় পরিষ্কার"
            2 -> "আংশিক মেঘলা"
            3 -> "মেঘলা"
            45, 48 -> "কুয়াশা"
            51, 53, 55 -> "হালকা গুঁড়ি বৃষ্টি"
            56, 57 -> "জমাট গুঁড়ি বৃষ্টি"
            61 -> "হালকা বৃষ্টি"
            63 -> "বৃষ্টি"
            65 -> "ভারী বৃষ্টি"
            66, 67 -> "জমাট বৃষ্টি"
            71 -> "হালকা তুষার"
            73 -> "তুষার"
            75 -> "ভারী তুষার"
            77 -> "তুষার-কণা"
            80 -> "হালকা ঝোড়ো বৃষ্টি"
            81 -> "ঝোড়ো বৃষ্টি"
            82 -> "প্রবল ঝোড়ো বৃষ্টি"
            85, 86 -> "তুষার-ঝড়"
            95 -> "বজ্রবৃষ্টি"
            96, 99 -> "শিলাসহ বজ্রবৃষ্টি"
            else -> "আবহাওয়া"
        }
    }

    /** WMO weather code -> emoji chinh (koi naya asset nahi)। */
    fun iconFor(code: Int): String {
        return when (code) {
            0, 1 -> "☀️"
            2, 3 -> "⛅"
            45, 48 -> "🌫️"
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
            71, 73, 75, 77, 85, 86 -> "❄️"
            95, 96, 99 -> "⛈️"
            else -> "🌤️"
        }
    }
}
