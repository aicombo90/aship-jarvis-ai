package com.aship.jarvis.commands

import android.content.Context

/**
 * v96: PASAND KI WEBSITE — jaldi kholna.
 *
 * Maya ne kaha: pasand ki website par jaldi jane ka
 * feature hona chahiye. Isliye ek chhoti pasand-suchi:
 * naam -> pata (URL), phone me hi bandhti hai
 * ([PREFS_NAME], zyada se zyada [MAX_SITES] site).
 *
 * - SIKHAO: "পছন্দের সাইটে যোগ করো ইউটিউব youtube.com"
 *   (naam + pata dono bole) ya "এই সাইটটা পছন্দের
 *   তালিকায় রাখো ইউটিউব" (pata aakhiri khoj ke pehle
 *   natije se — [WebSearchIntent.loadLastSearch])।
 * - KHOLO: "[naam] সাইট খোলো" — suchi me ho to seedha
 *   browser me khulta hai, na ho to bulane wala khoj
 *   wala raasta (v79) aazmata hai aur sach bolta hai।
 * - HATAO: "পছন্দের সাইট মুছে ফেলো [naam]"।
 * - BOLO: "পছন্দের সাইটগুলো বলো" — naam gin kar sunata hai।
 *
 * Koi site pehle se nahi bhari jati — user khud sikhata
 * hai (uski apni pasand usi ki hoti hai)। Pata saaf kiya
 * jata hai: scheme na ho to https:// lagta hai, bina
 * dot ke pata mana hi nahi jata (galat-suna pata
 * kabhi suchi me nahi jata)।
 */
object FavSites {

    /** Ek user kitni pasand site rakh sakta hai. */
    const val MAX_SITES = 20

    private const val PREFS_NAME = "jarvis_fav_sites"
    private const val KEY_SITES = "sites"

    /** Bacha hua natija — sikhane par kya hua. */
    enum class SaveOutcome {
        /** Nayi site suchi me aa gayi. */
        SAVED,

        /** Naam pehle se tha — pata badal diya. */
        UPDATED,

        /** Pata pata hi nahi laga (bina dot / khaali). */
        INVALID,

        /** Suchi bhari hui hai (20) — pehle kuch hatao. */
        FULL
    }

    /** Naam saaf karo — khoj hamesha isi roop se milti hai. */
    private fun cleanName(name: String): String =
        name.trim().lowercase()

    /**
     * Pata saaf karo — khaali jagah hatao, scheme na ho
     * to https:// lagao। Bina dot ke, ya jagah wala pata
     * mana hi nahi (galat-suna "dot com" bhi yahin
     * pakda jata hai — bolne me "ডট কম" aaye to "ডট"
     * ko "." banana bulane wale ka kaam hai, yahan nahi)।
     */
    fun cleanUrl(raw: String): String? {
        var url = raw.trim().lowercase()
        if (url.isEmpty() || url.contains(" ")) return null
        if (!url.contains("://")) {
            url = "https://$url"
        }
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty() || !afterScheme.contains(".")) return null
        if (afterScheme.startsWith(".") || afterScheme.endsWith(".")) return null
        return url
    }

    /** Poori suchi — dikhane wala naam -> pata (rakhne ke kram me)। */
    fun loadAll(context: Context): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SITES, null) ?: return out
            for (line in raw.split("\n")) {
                val cut = line.trim()
                if (cut.isEmpty()) continue
                val bar = cut.indexOf('|')
                if (bar <= 0) continue
                val name = cut.substring(0, bar).trim()
                val url = cut.substring(bar + 1).trim()
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    out[name] = url
                }
            }
        } catch (t: Throwable) {
            // Suchi na padh paye to khaali mano — hukm isse nahi rukta.
        }
        return out
    }

    private fun storeAll(context: Context, sites: Map<String, String>) {
        try {
            val raw = sites.entries.joinToString("\n") { (name, url) ->
                "$name|$url"
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SITES, raw)
                .apply()
        } catch (t: Throwable) {
            // Bachana bonus hai — hukm isse nahi rukta.
        }
    }

    /** Site sikhao (naam + pata)। Pehle se naam ho to pata badalta hai। */
    fun save(context: Context, name: String, urlRaw: String): SaveOutcome {
        val clean = cleanName(name)
        if (clean.isEmpty()) return SaveOutcome.INVALID
        val url = cleanUrl(urlRaw) ?: return SaveOutcome.INVALID
        val sites = loadAll(context)
        val existingKey = sites.keys.firstOrNull { cleanName(it) == clean }
        if (existingKey == null && sites.size >= MAX_SITES) {
            return SaveOutcome.FULL
        }
        if (existingKey != null) {
            sites.remove(existingKey)
        }
        sites[name.trim()] = url
        storeAll(context, sites)
        return if (existingKey != null) SaveOutcome.UPDATED else SaveOutcome.SAVED
    }

    /**
     * Naam se site dhoondho — pehle poora naam, phir
     * hissa (na ya naam ka hissa)। Na mile to null।
     */
    fun find(context: Context, name: String): Pair<String, String>? {
        val clean = cleanName(name)
        if (clean.isEmpty()) return null
        val sites = loadAll(context)
        for ((storedName, url) in sites) {
            if (cleanName(storedName) == clean) {
                return storedName to url
            }
        }
        for ((storedName, url) in sites) {
            if (cleanName(storedName).contains(clean) || clean.contains(cleanName(storedName))) {
                return storedName to url
            }
        }
        return null
    }

    /** Naam se site hatao — mili to true। */
    fun remove(context: Context, name: String): Boolean {
        val clean = cleanName(name)
        if (clean.isEmpty()) return false
        val sites = loadAll(context)
        val key = sites.keys.firstOrNull {
            cleanName(it) == clean || cleanName(it).contains(clean)
        } ?: return false
        sites.remove(key)
        storeAll(context, sites)
        return true
    }

    /** Sirf naam — bolkar sunane ke liye, rakhne ke kram me। */
    fun listNames(context: Context): List<String> = loadAll(context).keys.toList()
}
