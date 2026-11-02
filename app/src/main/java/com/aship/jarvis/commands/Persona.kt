package com.aship.jarvis.commands

import java.util.Locale

/**
 * v48: DO SHAKSIYAT — Maya aur Tara.
 *
 * User ka pakka hukm (2026-11-02): bhasha ka milan
 * "মাথা খারাপ করে দিচ্ছে" — isliye ab shaksiyat
 * WAKE NAAM se bandhi hai aur har shaksiyat SIRF
 * apni bhasha samjhegi/bolegi:
 *  - "Jarvis" / "Maya" bulane par MAYA — sirf Hindi
 *    (Devanagari) samajhti hai, sirf Hindi bolti hai।
 *  - "তারা" / "Tara" bulane par TARA — sirf Bangla
 *    samajhti hai, sirf Bangla bolti hai।
 *
 * Background wala asal bandobast (wake pehchan, kaan
 * ki kataar, dusri bhasha par vinamra mod) wake
 * service me hai (JarvisWakeService dekho) — Maya
 * wahan apne puraane token-niyam se, Tara naye
 * sthan-bandhe (shuruaat/akele) niyam se pehchani
 * jati hai, kyunki "তারা" aam Bangla me "ve log"
 * bhi hota hai — beech-wakya wala তারা kabhi wake
 * nahi banta।
 *
 * Ye file sirf do chhoti cheezein rakhti hai:
 * shaksiyat ki boli-bhasha ([spokenLang]) aur
 * saamne wali screen ke liye naam-pukar hatana
 * ([stripVocative]) — wahan user kabhi-kabhi
 * "মায়া, ..." / "তারা, ..." kehkar baat shuru
 * karta hai; naam hatakar asal hukm bachta hai aur
 * usi shaksiyat ki bhasha is baar ke jawab par
 * laagu hoti hai।
 */
enum class Persona { MAYA, TARA }

/** Is shaksiyat ki pakki boli-bhasha (jawab hamesha isi me). */
fun Persona.spokenLang(): PlayIntent.SpokenLang = when (this) {
    Persona.MAYA -> PlayIntent.SpokenLang.HINDI
    Persona.TARA -> PlayIntent.SpokenLang.BENGALI
}

object PersonaVocative {

    private val MAYA_NAMES = setOf(
        "মায়া", "মাযা", "মাইয়া", "माया", "maya", "maaya", "maiya"
    )

    private val TARA_NAMES = setOf(
        "তারা", "তাঁরা", "तारा", "tara", "taara"
    )

    /** Naam se pehle aane wale pukar-shabd ("হে তারা, ...")। */
    private val PARTICLES = setOf(
        "হে", "হেই", "আরে", "ও", "hey", "he", "are", "o"
    )

    /**
     * [rawText] kisi shaksiyat ko naam lekar shuru
     * hoti ho to (shaksiyat, naam-hataya-hua text)
     * lautao; warna null. Naam ke baad kuch bacha hi
     * na ho (sirf naam pukara gaya ho) to bhi null —
     * aisi baat par purana vyavhaar hi chalega।
     */
    fun stripVocative(rawText: String): Pair<Persona, String>? {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return null
        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val first = cleanToken(tokens[0])
        personaOf(first)?.let { persona ->
            val rest = tokens.drop(1).joinToString(" ").trim()
            return if (rest.isEmpty()) null else persona to rest
        }
        if (first.lowercase(Locale.ROOT) in PARTICLES && tokens.size >= 2) {
            val second = cleanToken(tokens[1])
            personaOf(second)?.let { persona ->
                val rest = tokens.drop(2).joinToString(" ").trim()
                return if (rest.isEmpty()) null else persona to rest
            }
        }
        return null
    }

    private fun personaOf(cleanedToken: String): Persona? {
        val token = cleanedToken.lowercase(Locale.ROOT)
        return when (token) {
            in MAYA_NAMES -> Persona.MAYA
            in TARA_NAMES -> Persona.TARA
            else -> null
        }
    }

    /** Token ke aas-paas ke viram-chinh hatao ("মায়া," -> "মায়া")। */
    private fun cleanToken(token: String): String =
        token.trim(' ', ',', '।', '.', '!', '?', '—', '-', ':', ';', '"', '\'')
}
