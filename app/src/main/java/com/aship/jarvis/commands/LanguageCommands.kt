package com.aship.jarvis.commands

import java.util.Locale

/**
 * v93: AWAAZ SE BHASHA BADLO.
 *
 * "English-এ কথা বলো" / "বাংলায় কথা বলো" / "language
 * change করো" / "speak in Tamil" — bhasha ka naam +
 * badalne wali kriya, dono milen to bhasha badlo।
 * Sirf bhasha ka naam bolna kaafi NAHI ("English
 * গান চালাও" me English gaane ka zikr hai, bhasha
 * badalne ka hukm nahi — wahan ye kabhi nahi
 * phansega)।
 *
 * Naam ke bina ("language change করো") saamne wali
 * screen par bhasha-chunao dialog khulta hai, aur
 * background me bhashaon ki suchi wali boli — dono
 * jagah bulane wala khud faisla karta hai, isliye
 * yahan sirf pehchan hai, UI nahi।
 */
object LanguageCommands {

    /** Bhasha badalne wali kriyaen (chhote akshar, contains). */
    private val SWITCH_VERBS = listOf(
        "কথা বলো", "কথা বল", "কথা বলুন",
        "বলো", "বলুন", "bolo",
        "बोलो", "बात करो", "में बोलो",
        "speak", "talk",
        "language", "ভাষা", "bhasha",
        "switch", "change", "বদল", "পরিবর্তন"
    )

    /** Bina naam wala badlav — "language change করো" jaisi baat. */
    private val BARE_CHANGE_PHRASES = listOf(
        "language change", "change language", "switch language",
        "ভাষা বদল", "ভাষা পরিবর্তন",
        "language বদল", "ভাষা change", "ভাষা switch",
        "change the language", "switch the language"
    )

    /**
     * Bhasha-badalne wala hukm ho to nayi bhasha
     * lautao; warna null। Bhasha ka naam + badalne
     * wali kriya, dono chahiye।
     */
    fun extractLanguageSwitch(rawText: String): MayaLanguage? {
        val lower = rawText.lowercase(Locale.ROOT)
        if (lower.isBlank()) return null
        if (SWITCH_VERBS.none { lower.contains(it) }) return null
        return MayaLanguages.ALL.firstOrNull { lang ->
            lang.matchWords.any { word -> lower.contains(word) }
        }
    }

    /** Naam ke bina sirf "bhasha badlo" — chunao mangta hai. */
    fun isBareChangeRequest(rawText: String): Boolean {
        val lower = rawText.lowercase(Locale.ROOT)
        if (lower.isBlank()) return false
        if (extractLanguageSwitch(rawText) != null) return false
        return BARE_CHANGE_PHRASES.any { lower.contains(it) }
    }

    /** Background me: kaun si bhasha — suchi ke saath puchho. */
    fun askWhichReply(): String {
        val names = MayaLanguages.ALL.joinToString(", ") { it.englishName }
        return "কোন ভাষায় কথা বলবো? $names — যেকোনো একটার নাম বলে দাও, " +
            "যেমন 'English-এ কথা বলো'।"
    }
}
