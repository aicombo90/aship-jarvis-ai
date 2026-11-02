package com.aship.jarvis.ai

import com.aship.jarvis.commands.PlayIntent
import org.json.JSONObject

/**
 * v42: JAWAB KI CHAUKI — agent ke jawab ko user tak
 * pahunchane se pehle ki aakhiri jaanch.
 *
 * v41 ke live test me pakda gaya asal bug: agent ki daud
 * bilkul sahi khatm hui (report: "steps: final"), par
 * saamne wali screen par "Maya:" wali patti me aakhiri
 * boli line ki jagah round-1 ka KACCHA PROTOCOL JSON
 * dikh gaya — {"action":"tool","tool":"torch",...}।
 * Aisa tab hota hai jab model ka jawab protocol se
 * bahar gir jata hai (JSON aadha kata ho, "action" ki
 * jagah kuch aur ho, "say" khaali ho): AgentBrain use
 * saade text wala jawab maan leta hai, aur woh saade
 * text asal me JSON hi hota hai. Protocol ya auzaar
 * badle NAHI gaye hain — ye chauki SIRF prastuti wali
 * seema (screen par dikhana + TTS se bolna) par hai:
 * dono raaste (MainActivity ki patti, wake service ki
 * boli + gole ka bubble) agent ka jawab user tak
 * bhejne se pehle yahin se guzaarte hain, aur aage koi
 * naya raasta bane to use bhi yahi aadat daalni hai.
 *
 * Niyam pakka hai: user kabhi protocol payload nahi
 * dekhega/sunega. Kaccha text protocol jaisa lage to
 * uske andar ka "say" hi asal boli line hai — woh
 * mile to wahi do; na mile (aadha kata JSON) to
 * bulane wale ki bhasha me ek chhoti imaandaar haar
 * line — jhootha "ho gaya" bhi nahi, JSON bhi nahi.
 * Aam saade jawab (sawaal ke jawab, adda ki line)
 * bilkul aparivartit guzaarte hain.
 */
object ReplyGuard {

    /**
     * [raw] ko user ke liye surakshit roop do. [lang]
     * wohi bhasha hai jisme user ne baat ki thi
     * (PlayIntent.requestLanguage se) — haar line usi
     * me milti hai.
     */
    fun sanitize(raw: String?, lang: PlayIntent.SpokenLang): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return genericLine(lang)
        if (!looksLikeProtocol(text)) return text
        val say = extractSay(text)
        return if (!say.isNullOrEmpty()) say else genericLine(lang)
    }

    /**
     * Ye text agent-protocol ka payload lagta hai kya?
     * Teen pehchan: (1) ``` fence ke andar bandha JSON,
     * (2) '{' se shuru hokar "action" ya "tool" wala
     * JSON, (3) beech me ghusa hua {"action" wala tukda
     * ya "action"+"tool" dono wala koi bhi JSON-jaisa
     * hissa. Aam boli me ye roop kabhi nahi aate.
     */
    private fun looksLikeProtocol(text: String): Boolean {
        val stripped = text
            .removePrefix("```json")
            .removePrefix("```")
            .trim()
        if (stripped.startsWith("{") &&
            (stripped.contains("\"action\"") ||
                stripped.contains("\"tool\""))
        ) {
            return true
        }
        return text.contains("{\"action\"") ||
            (text.contains("\"action\"") && text.contains("\"tool\""))
    }

    /**
     * Protocol payload ke andar ki "say" line — mil jaye
     * to wohi user ki asal boli hai. JSON aadha kata ho
     * ya "say" khaali ho to null (bulane wala haar line
     * dega, kaccha JSON kabhi nahi).
     */
    private fun extractSay(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(text.substring(start, end + 1))
                .optString("say")
                .trim()
                .takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Protocol mila par boli line nahi — imaandaar chhoti
     * haar, user ki bhasha me (AgentBrain ke PARTIAL
     * jawab wali hi sachchai: poora nahi ho paya).
     */
    private fun genericLine(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "কাজটা পুরোপুরি করে উঠতে পারলাম না।"
            PlayIntent.SpokenLang.HINDI ->
                "कुछ काम हो गया है, पर पूरा काम मैं ख़त्म नहीं कर पाई।"
            PlayIntent.SpokenLang.ENGLISH ->
                "I couldn't finish that properly."
        }
}
