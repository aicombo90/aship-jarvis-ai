package com.aship.jarvis.ai

import android.content.Context

/**
 * v53: API KEYS — user ke fallback-chain wale saare
 * FREE provideron (Gemini, Groq, OpenRouter, Cloudflare,
 * SambaNova, Cohere) ki keyon ka EK hi bhandaar.
 *
 * Niyam (GeminiBrain wala hi):
 *  - Key SIRF is phone par rehti hai — SharedPreferences
 *    "jarvis_ai" (wahi file jisme Gemini ki key bhi hai)।
 *  - Koi key kabhi hard-code nahi, kabhi log nahi hoti,
 *    aur sirf usi provider ko bheji jati hai jise bulaya
 *    jaye — doosre provider ki key us tak kabhi nahi jati।
 *  - Gemini wali key yahin se bhi badli ja sakti hai:
 *    Gemini ka hissa seedha [GeminiBrain] ko saunp diya
 *    gaya hai, isliye main screen wali Gemini jagah aur
 *    ye API Keys screen kabhi alag nahi padenge।
 *  - Cloudflare ko key ke saath Account ID bhi chahiye
 *    (Workers AI bina account ke nahi chalta), isliye
 *    uska ek alag khana hai — baaki paanch ko sirf key।
 *  - Abhi sirf RAKHNA hai: fallback chain (ek provider
 *    fail ho to agla) is version me NAHI bana — woh
 *    alag kaam hai, key ke bina woh bhi nahi chalega।
 */
object ProviderKeys {

    /** Ek provider — naam, key kahan milegi, aur Account ID chahiye kya। */
    enum class Provider(
        val displayName: String,
        val hint: String,
        val needsAccountId: Boolean
    ) {
        GEMINI(
            "Gemini",
            "Google AI Studio (aistudio.google.com) — free",
            false
        ),
        GROQ(
            "Groq",
            "console.groq.com — free",
            false
        ),
        OPENROUTER(
            "OpenRouter",
            "openrouter.ai — free models",
            false
        ),
        CLOUDFLARE(
            "Cloudflare",
            "dash.cloudflare.com → API Tokens (Workers AI) — free",
            true
        ),
        SAMBANOVA(
            "SambaNova",
            "cloud.sambanova.ai — free",
            false
        ),
        COHERE(
            "Cohere",
            "dashboard.cohere.com — free",
            false
        )
    }

    /** Bhari hui key, ya null jab koi key set na ho। */
    fun getKey(context: Context, provider: Provider): String? {
        if (provider == Provider.GEMINI) return GeminiBrain.getKey(context)
        return prefs(context).getString(prefKey(provider), null)
            ?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun setKey(context: Context, provider: Provider, key: String) {
        if (provider == Provider.GEMINI) {
            GeminiBrain.setKey(context, key)
            return
        }
        prefs(context).edit().putString(prefKey(provider), key.trim()).apply()
    }

    fun clearKey(context: Context, provider: Provider) {
        if (provider == Provider.GEMINI) {
            GeminiBrain.clearKey(context)
            return
        }
        prefs(context).edit().remove(prefKey(provider)).apply()
    }

    /** Key set hai ya nahi — key ka text kabhi bahar nahi jata। */
    fun isConfigured(context: Context, provider: Provider): Boolean =
        getKey(context, provider) != null

    /** Kitne provideron ki key set hai (button par "2/6 সেট" dikhane ke liye)। */
    fun configuredCount(context: Context): Int =
        Provider.values().count { isConfigured(context, it) }

    // ------------------------------------------------------------------
    // Cloudflare Account ID — key se alag khana (Workers AI ko
    // account chahiye; baaki provideron ko iski zaroorat nahi)।
    // ------------------------------------------------------------------

    fun getAccountId(context: Context): String? =
        prefs(context).getString(KEY_CLOUDFLARE_ACCOUNT, null)
            ?.trim()?.takeIf { it.isNotEmpty() }

    fun setAccountId(context: Context, accountId: String) {
        prefs(context).edit()
            .putString(KEY_CLOUDFLARE_ACCOUNT, accountId.trim()).apply()
    }

    fun clearAccountId(context: Context) {
        prefs(context).edit().remove(KEY_CLOUDFLARE_ACCOUNT).apply()
    }

    private fun prefKey(provider: Provider): String = when (provider) {
        Provider.GEMINI -> "gemini_key" // kabhi use nahi — GeminiBrain sambhalta hai
        Provider.GROQ -> "key_groq"
        Provider.OPENROUTER -> "key_openrouter"
        Provider.CLOUDFLARE -> "key_cloudflare"
        Provider.SAMBANOVA -> "key_sambanova"
        Provider.COHERE -> "key_cohere"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "jarvis_ai"
        private const val KEY_CLOUDFLARE_ACCOUNT = "key_cloudflare_account"
    }
}
