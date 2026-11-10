package com.aship.jarvis.commands

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.aship.jarvis.ai.PlayIntent
import com.aship.jarvis.ai.ProviderChain
import com.aship.jarvis.prefs.AppPrefs
import com.aship.jarvis.prefs.JarvisMode

/**
 * v97: OFFLINE MODE — Maya ke mastishk
 * (online jawab) ke liye imaandaar saaf-suthri
 * jhaank (sach bolna, chupchaap asafal nahi):
 *
 *  - OFFLINE MODE chalu ho (mode = OFFLINE),
 *  - koi provider key hi nahi bandhi ho,
 *  - ya internet na ho —
 *  to [reason] wajah batata hai ("mode" /
 *  "key" / "net") aur [reply] wohi wajah
 *  user ki bhasha me bolta hai, offline kaam
 *  chalte rehne ka bharosa dekar।
 *
 * Asal offline kaam (app kholna, torch, data,
 * scroll, tap, back, call, reminder, toggle,
 * pasandeeda site, screen padhna) dono
 * dispatch raaston me pehle se hi online call
 * se PEHLE aazmaye jate hain — ye file sirf
 * wajah bataati hai, koi naya raasta nahi
 * banati। Ye poori tarah offline local LLM
 * NAHI hai (user ne 2026-11-10 ko saaf mana
 * kiya — bahut mushkil) — sirf offline
 * fallback + imaandaar jawab hai।
 */
object OfflineMode {

    // ---------------------------------------------------------------
    // Jhaank
    // ---------------------------------------------------------------

    /**
     * Online jawab kyun nahi mil sakta — "mode"
     * (Offline mode chalu), "key" (koi key nahi),
     * "net" (internet nahi) — ya online kaam kar
     * sakta hai to null। [CommandEngine.handle]
     * jaisi koshishon se koi lena-dena nahi: sirf
     * mastishk ki uplabdhata dekhna hai।
     */
    fun reason(context: Context): String? {
        return try {
            val prefs = AppPrefs(context)
            if (prefs.mode == JarvisMode.OFFLINE) return "mode"
            if (!ProviderChain.anyProviderConfigured(context)) return "key"
            if (!isNetworkUp(context)) return "net"
            null
        } catch (t: Throwable) {
            null
        }
    }

    /** Online jawab milega ya nahi — indicator ke liye ek hi sawaal। */
    fun isOffline(context: Context): Boolean = reason(context) != null

    /**
     * Internet hai ya nahi — [JarvisWakeService]
     * ke `isNetworkUp()` wahi niyam (active
     * network par koi transport chahiye)।
     */
    fun isNetworkUp(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (t: Throwable) {
            false
        }
    }

    // ---------------------------------------------------------------
    // Jawab
    // ---------------------------------------------------------------

    /**
     * Wajah ke hisaab se imaandaar jawab (user ki
     * bhasha me): kya nahi mil sakta + offline
     * kaam chalte rahenge। "বুঝতে পারিনি" wala
     * bandha-vakya kabhi nahi।
     */
    fun reply(
        reason: String,
        lang: PlayIntent.SpokenLang
    ): String {
        return when (reason) {
            "mode" -> when (lang) {
                PlayIntent.SpokenLang.HINDI ->
                    "ऑफ़लाइन मोड चालू है — ऑनलाइन जवाब बंद है। ऑफ़लाइन काम (ऐप खोलना, टॉर्च, डेटा, स्क्रॉल, टैप) चलते रहेंगे।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "Offline mode is on — online answers are off. Offline work (opening apps, torch, data, scroll, tap) still works."
                else ->
                    "অফলাইন মোডে আছি — অনলাইন উত্তর বন্ধ আছে। অফলাইন কাজগুলো (অ্যাপ খোলা, টর্চ, ডেটা, স্ক্রল, ট্যাপ) চলবে।"
            }
            "key" -> when (lang) {
                PlayIntent.SpokenLang.HINDI ->
                    "कोई API key नहीं डाली है — ऑनलाइन जवाब नहीं दे सकती। API Keys से key डालने पर ऑनलाइन काम करेगा; ऑफ़लाइन काम चलते रहेंगे।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "No API key is set — I can't give online answers. Add a key in API Keys and online work will start; offline work still works."
                else ->
                    "কোনো API key বসানো নেই — অনলাইন উত্তর দিতে পারছি না। API Keys থেকে key বসালে অনলাইন কাজ করবে; অফলাইন কাজগুলো চলবে।"
            }
            else -> when (lang) {
                PlayIntent.SpokenLang.HINDI ->
                    "इंटरनेट नहीं है — ऑनलाइन जवाब नहीं दे सकती। ऑफ़लाइन काम चलते रहेंगे।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "No internet — I can't give online answers. Offline work still works."
                else ->
                    "ইন্টারনেট নেই, তাই অনলাইন উত্তর দিতে পারছি না — অফলাইন কাজগুলো চলবে।"
            }
        }
    }
}
