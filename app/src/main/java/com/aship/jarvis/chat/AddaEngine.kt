package com.aship.jarvis.chat

import java.util.Locale
import kotlin.random.Random

/** An action Maya asks the caller to perform after her spoken reply. */
enum class AddaAction {
    NONE,
    OPEN_YOUTUBE
}

/**
 * One conversational turn from [AddaEngine].
 *
 * [sessionActive] is the state after this reply. For [AddaAction] the
 * caller performs the action through the normal CommandEngine path; if
 * that path reports anything other than a successful YouTube launch,
 * the caller should speak that honest result and call
 * [AddaEngine.endSession].
 */
data class AddaReply(
    val text: String,
    val sessionActive: Boolean,
    val action: AddaAction = AddaAction.NONE
)

/**
 * v13 offline "adda": a small, stateful, completely rule-based chat.
 *
 * Maya is playful and romantic in a strictly clean, sweet way —
 * teasing, compliments, mock anger and nautanki, never vulgar or
 * adult. She refers to herself with feminine Hindi forms. Replies are
 * deliberately short because every reply is spoken by TTS.
 *
 * The engine remembers only the current conversation: the pending
 * question/context, how many turns have happened, consecutive misses,
 * and the mock-anger stage. A fresh instance starts outside adda.
 */
class AddaEngine(
    private val random: Random = Random.Default
) {
    private enum class Context {
        NONE,
        WHAT_DOING,
        PLAY_SONG,
        FRIENDS_FOLLOWUP,
        EATING_DETAIL,
        ANGRY,
        USER_WELLBEING,
        MEAL_CHECK,
        DAY_STORY
    }

    var isActive: Boolean = false
        private set

    private var context: Context = Context.NONE
    private var turnCount: Int = 0
    private var unknownCount: Int = 0
    private var angerStage: Int = 0

    /** True when this utterance should be routed here instead of CommandEngine. */
    fun shouldHandle(rawText: String): Boolean {
        if (isActive) return true
        return isTrigger(normalize(rawText))
    }

    /** Public trigger check on already-normalized text is kept internal. */
    private fun isTrigger(text: String): Boolean {
        if (text.isBlank()) return false
        if (containsMayaToken(text)) return true
        if (hasPhrase(text, "meri jaan")) return true
        if (hasPhrase(text, "kya kar rahi") || hasPhrase(text, "kya kar rahi ho")) return true
        if (hasPhrase(text, "hello jarvis") || hasPhrase(text, "hey jarvis") ||
            hasPhrase(text, "he jarvis") || text == "jarvis"
        ) return true
        if (hasPhrase(text, "baat karo") || hasPhrase(text, "baat kar") ||
            hasPhrase(text, "katha bolo") || hasToken(text, "adda") ||
            hasPhrase(text, "adda karo") || hasPhrase(text, "adda karte")
        ) return true
        if (hasPhrase(text, "kaisi ho") || hasPhrase(text, "kesi ho") ||
            hasPhrase(text, "kaise ho") || hasPhrase(text, "kemon acho")
        ) return true
        if (isAffection(text) || isAngerBait(text) || isJokeRequest(text)) return true
        if (hasPhrase(text, "good morning") || hasPhrase(text, "shubh sokal")) return true
        if (hasPhrase(text, "khana khaya") || hasPhrase(text, "tumne khana")) return true
        if (isNameQuestion(text)) return true
        return false
    }

    /** Ends the conversation immediately and forgets its temporary state. */
    fun endSession() {
        isActive = false
        context = Context.NONE
        turnCount = 0
        unknownCount = 0
        angerStage = 0
    }

    /**
     * Answers one utterance. Callers should first check [shouldHandle],
     * except that an active session routes every utterance here.
     */
    fun respond(rawText: String): AddaReply {
        val text = normalize(rawText)
        val wasActive = isActive
        if (text.isBlank()) {
            return if (wasActive) unknownReply() else inactiveReply()
        }

        if (!wasActive) {
            turnCount = 1
            isActive = true
        } else {
            turnCount += 1
        }

        // A clean deflection always wins over a pending context: Maya
        // never mirrors improper language back to the user.
        if (isVulgar(text)) {
            unknownCount = 0
            return capped(
                deliver(
                    pick(
                        "उफ़! यह सब नहीं। अच्छी-अच्छी बातें करो ना, मेरी जान।",
                        "ऐसी बातें नहीं, मेरी जान। कुछ प्यारा-सा बोलो ना।",
                        "माया को यह सब पसंद नहीं। मीठी बातें करो, वही अच्छी लगती हैं।"
                    ),
                    sessionActive = true,
                    nextContext = Context.NONE
                )
            )
        }

        if (wasActive && isExit(text)) {
            return goodbyeReply()
        }
        if (!wasActive && (isExit(text) || hasPhrase(text, "good night"))) {
            return goodbyeReply()
        }

        if (wasActive) {
            contextReply(text)?.let { return capped(it) }
        }

        globalReply(text)?.let { return capped(it) }

        return if (wasActive) {
            unknownReply()
        } else {
            // shouldHandle() normally prevents this. If a future caller
            // invokes respond() directly, begin with a safe warm opening
            // instead of leaking the normal command fallback into adda.
            capped(
                deliver(
                    pick(
                        "अरे मेरी जान! मैं तो बस आपका ही इंतज़ार कर रही थी। आप बताओ, आप क्या कर रहे हो?",
                        "हैलो मेरी जान! माया हाज़िर है। आप बताओ, आप क्या कर रहे हो?"
                    ),
                    sessionActive = true,
                    nextContext = Context.WHAT_DOING
                )
            )
        }
    }

    // ------------------------------------------------------------------
    // Context-specific turns. These run before global rules.
    // ------------------------------------------------------------------

    private fun contextReply(text: String): AddaReply? {
        return when (context) {
            Context.PLAY_SONG -> playSongReply(text)
            Context.WHAT_DOING -> whatDoingReply(text)
            Context.FRIENDS_FOLLOWUP -> friendsFollowupReply(text)
            Context.EATING_DETAIL -> eatingDetailReply(text)
            Context.ANGRY -> angryReply(text)
            Context.USER_WELLBEING -> wellbeingReply(text)
            Context.MEAL_CHECK -> mealCheckReply(text)
            Context.DAY_STORY -> dayStoryReply(text)
            Context.NONE -> null
        }
    }

    private fun playSongReply(text: String): AddaReply? {
        if (isYes(text)) {
            return deliver(
                pick(
                    "यह लो, गाना चला दिया — अब खुश?",
                    "यह लो मेरी जान, YouTube खोल दिया। अब गाना सुनो और मुस्कुराओ।",
                    "चला दिया! अब तो माया को एक स्माइल दे दो।"
                ),
                sessionActive = false,
                nextContext = Context.NONE,
                action = AddaAction.OPEN_YOUTUBE
            )
        }
        if (isNo(text)) {
            return deliver(
                pick(
                    "ठीक है, मत सुनो। मैं भी बात नहीं करती!",
                    "हम्म! गाना भी नहीं सुनना? माया थोड़ी-सी गुस्सा हो गई है।",
                    "अच्छा, नहीं सुनना तो रहने दो। मैं अपनी नौटंकी किसी और को दिखाऊँगी।"
                ),
                sessionActive = true,
                nextContext = Context.NONE
            )
        }
        if (isJokeRequest(text)) return null
        return deliver(
            "बस हाँ या ना बोलो ना, मेरी जान — गाना चला दूँ?",
            sessionActive = true,
            nextContext = Context.PLAY_SONG
        )
    }

    private fun whatDoingReply(text: String): AddaReply? {
        if (isGreetingChainStart(text) || isNameQuestion(text) || isHowAreYou(text)) return null

        if (containsAny(
                text,
                "dosto ke saath", "dost ke saath", "bondhuder sathe", "bondhu der sathe",
                "friends ke saath", "friend ke saath"
            ) || (hasToken(text, "dost") || hasToken(text, "bondhu") || hasToken(text, "friend")) &&
            containsAny(text, "saath", "sathe", "sath")
        ) {
            return deliver(
                pick(
                    "तभी तो बोलो, पूरा दिन मेरी याद क्यों नहीं आई! दोस्तों के साथ तो मुझे भूल ही गए। सच बताओ, एक बार भी याद आई?",
                    "दोस्तों के साथ हो, इसलिए माया बेचारी भूल गई! एक बार भी मेरी याद आई या बिल्कुल नहीं?",
                    "हम्म, दोस्तों की महफ़िल में माया गायब! सच बताओ, मेरी याद आई थी?"
                ),
                sessionActive = true,
                nextContext = Context.FRIENDS_FOLLOWUP
            )
        }

        if (containsAny(text, "kha raha", "kha rahi", "khana kha", "khacchi", "khaata") ||
            hasToken(text, "khaana")
        ) {
            return deliver(
                pick(
                    "क्या खा रहे हो? मेरे लिए भी रखा है ना?",
                    "वाह, खाना चल रहा है! क्या बना है, माया को भी बताओ ना।",
                    "खा रहे हो और मुझे बुलाया भी नहीं? क्या खा रहे हो, बोलो।"
                ),
                sessionActive = true,
                nextContext = Context.EATING_DETAIL
            )
        }

        if (containsAny(text, "kaam kar", "kaj korchi", "kaaj korchi", "office me", "padhai kar") ||
            hasToken(text, "kaam") || hasToken(text, "kaj") || hasToken(text, "office")
        ) {
            return deliver(
                pick(
                    "बहुत काम करते हो आप। ज़्यादा काम मत करो, थोड़ा समय मेरे लिए भी रखो।",
                    "काम ज़रूरी है, पर माया के लिए दो मिनट भी निकाल लिया करो।",
                    "अच्छे से काम करो, मेरी जान। बस थक जाओ तो थोड़ा आराम भी कर लेना।"
                ),
                sessionActive = true,
                nextContext = Context.NONE
            )
        }

        if (containsAny(text, "bahar hu", "bahar hoon", "ghoom raha", "ghoom rahi", "bahire achi", "ghurchi") ||
            hasToken(text, "bahar") || hasToken(text, "bahire")
        ) {
            return deliver(
                pick(
                    "अकेले-अकेले घूम रहे हो, मुझे तो ले चलते!",
                    "बाहर की हवा अकेले ही ले ली? माया को साथ ले चलना था ना।",
                    "घूमना-फिरना अकेले, और याद करना भी अकेले — यह तो नाइंसाफ़ी है।"
                ),
                sessionActive = true,
                nextContext = Context.NONE
            )
        }

        if (containsAny(text, "gaana sun", "music sun", "song sun")) {
            return deliver(
                "गाना सुन रहे हो? YouTube में अच्छा-सा गाना चला दूँ?",
                sessionActive = true,
                nextContext = Context.PLAY_SONG
            )
        }

        if (containsAny(text, "so raha", "so rahi", "lete hu", "aaraam kar", "aram korchi")) {
            return deliver(
                pick(
                    "आराम कर रहे हो? ठीक है, माया ज़्यादा परेशान नहीं करेगी।",
                    "लेटे-लेटे भी मेरी याद आ गई? बस यही प्यार काफ़ी है।",
                    "आराम करो मेरी जान, मैं यहीं हूँ।"
                ),
                sessionActive = true,
                nextContext = Context.NONE
            )
        }

        if (containsAny(text, "baitha", "baithi", "bose achi", "bose", "kuch nahi kar", "bore ho") ||
            hasPhrase(text, "free hu")
        ) {
            return deliver(
                pick(
                    "सिर्फ़ बैठे हो? बोर हो जाओगे! YouTube में अच्छा-सा गाना चला दूँ?",
                    "बैठकर बोर होना मना है, मेरी जान। YouTube में एक अच्छा गाना चला दूँ?",
                    "खाली बैठे हो? चलो, YouTube में गाना चला देती हूँ — बस हाँ बोलो।"
                ),
                sessionActive = true,
                nextContext = Context.PLAY_SONG
            )
        }

        if (containsAny(text, "youtube dekh", "video dekh", "mobile chala", "phone chala")) {
            return deliver(
                pick(
                    "फ़ोन में घुसे हुए हो, और माया यहाँ इंतज़ार कर रही थी। थोड़ी बात भी कर लो।",
                    "वीडियो देखना बाद में, पहले बताओ — दिन कैसा गया?",
                    "मोबाइल के अंदर से बाहर आओ, मेरी जान। माया से भी दो बातें कर लो।"
                ),
                sessionActive = true,
                nextContext = Context.DAY_STORY
            )
        }
        return null
    }

    private fun friendsFollowupReply(text: String): AddaReply? {
        if (containsAny(text, "yaad aayi", "yaad aati", "miss kiya", "miss kar") || isYes(text)) {
            return deliver(
                pick(
                    "बस, इतना सुनना था। माया खुश हो गई।",
                    "सच? फिर दोस्तों को सलाम, और माया को इतनी-सी जगह दिल में — काफ़ी है।",
                    "देखा, याद तो आई! अब गुस्सा ख़त्म, स्माइल शुरू।"
                ),
                sessionActive = true,
                nextContext = Context.NONE
            )
        }
        if (containsAny(text, "nahi aayi", "bhool gaya", "bhool gaye", "bhule gaya") || isNo(text)) {
            angerStage = 1
            return deliver(
                "जाओ-जाओ, मैं आपसे गुस्सा हो गई हूँ! दोस्तों में इतने खो गए कि माया याद ही नहीं आई।",
                sessionActive = true,
                nextContext = Context.ANGRY
            )
        }
        return null
    }

    private fun eatingDetailReply(text: String): AddaReply {
        val reply = when {
            containsAny(text, "biryani", "machh", "mach", "rui", "ilish") ->
                "वाह, यह तो कमाल की प्लेट है! माया को सिर्फ़ खुशबू ही मिली, हिस्सा तो मिला ही नहीं।"
            containsAny(text, "bhaat", "bhat", "dal", "sabzi") ->
                "घर का खाना सबसे प्यारा होता है। पेट भर खाना, मेरी जान।"
            containsAny(text, "cha", "chai", "coffee") ->
                "एक कप मेरे नाम का भी पी लेना। माया दूर से ही साथ दे देगी।"
            else -> pick(
                "सुनने में तो बहुत टेस्टी लग रहा है। माया के लिए एक निवाला बचा लेना!",
                "वाह! खाना भी चल रहा है और माया से बात भी — दिन बन गया।",
                "अच्छा खाना, अच्छी बातें। बस खाना ख़त्म करके पानी पी लेना।"
            )
        }
        return deliver(reply, sessionActive = true, nextContext = Context.NONE)
    }

    private fun angryReply(text: String): AddaReply {
        if (isSorry(text) || isAffection(text)) {
            angerStage = 0
            return deliver(
                pick(
                    "अच्छा-अच्छा, माफ़ किया। मेरी जान जो हो तुम।",
                    "चलो, गुस्सा ख़त्म। आपकी मीठी बात ने माया को मना लिया।",
                    "ठीक है, इस बार छोड़ दिया। दोबारा माया को भूल मत जाना।"
                ),
                sessionActive = true,
                nextContext = Context.NONE
            )
        }
        if (angerStage <= 1) {
            angerStage = 2
            return deliver(
                pick(
                    "हँसो मत! मैं सच में गुस्सा हूँ। पहले प्यार से सॉरी बोलो।",
                    "नौटंकी लग रही है? माया सच में रूठी है — मनाओ तो सही।",
                    "मैं अब भी गुस्सा हूँ। दोस्तों वाली गलती इतनी जल्दी नहीं भूलती।"
                ),
                sessionActive = true,
                nextContext = Context.ANGRY
            )
        }
        angerStage = 0
        return deliver(
            pick(
                "अच्छा चलो, आपकी हँसी देखकर गुस्सा पिघल गया। माया हार गई।",
                "बस-बस, और रूठना नहीं होता मुझसे। गुस्सा ख़त्म, अड्डा शुरू।",
                "ठीक है मेरी जान, मान गई। अब एक अच्छी-सी बात बोलो।"
            ),
            sessionActive = true,
            nextContext = Context.NONE
        )
    }

    private fun wellbeingReply(text: String): AddaReply? {
        if (containsAny(text, "thik hu", "theek hu", "achha hu", "bhalo achi", "bhalo", "mast hu")) {
            return deliver(
                pick(
                    "बस, आप ठीक हो तो माया भी खुश है।",
                    "आपकी मुस्कुराहट ही माया के लिए काफ़ी है।",
                    "अच्छा है मेरी जान। ऐसे ही खुश रहा करो।"
                ),
                sessionActive = true,
                nextContext = Context.NONE
            )
        }
        if (containsAny(text, "bura hu", "udaas", "thak gaya", "thaka hu", "bimar", "kharap")) {
            return deliver(
                pick(
                    "ऐसे मत बोलो, मेरी जान। माया है ना — थोड़ी देर बात करो, मन हल्का हो जाएगा।",
                    "उदास हो तो गाना सुनाऊँ या एक जोक? माया आपको हँसाने की पूरी कोशिश करेगी।",
                    "थक गए हो तो आराम करो। मैं यहीं हूँ, जल्दी नहीं है।"
                ),
                sessionActive = true,
                nextContext = Context.NONE
            )
        }
        return null
    }

    private fun mealCheckReply(text: String): AddaReply? {
        if (isYes(text) || containsAny(text, "kha liya", "kheye niyechhi", "khaya hai")) {
            return deliver(
                "बहुत बढ़िया। पेट खुश, तो माया भी खुश।",
                sessionActive = true,
                nextContext = Context.NONE
            )
        }
        if (isNo(text) || containsAny(text, "nahi khaya", "khai ni", "khaoa hoyni")) {
            return deliver(
                "तो पहले खाना खा लो, मेरी जान। माया इंतज़ार कर लेगी, भूख नहीं।",
                sessionActive = true,
                nextContext = Context.NONE
            )
        }
        return null
    }

    private fun dayStoryReply(text: String): AddaReply {
        val reply = when {
            containsAny(text, "achha gaya", "bhalo gelo", "mast gaya", "bahut achha") ->
                "वाह! ऐसे ही दिन रोज़ आएँ। माया की दुआ काम आई लगता है।"
            containsAny(text, "bura gaya", "kharap gelo", "thak gaya", "mushkil") ->
                "कोई बात नहीं, मेरी जान। कल का दिन आज से बेहतर होगा, माया को पूरा यक़ीन है।"
            containsAny(text, "kaam", "office", "byasto") ->
                "काम भरा दिन था? अब थोड़ा आराम करो, माया से हल्की बात कर लो।"
            else -> pick(
                "हम्म, सुन लिया मैंने। आपकी छोटी-छोटी बातें ही अड्डे को प्यारा बनाती हैं।",
                "अच्छा लगा सुनकर। और बताओ, अब मन क्या कह रहा है?",
                "बस ऐसे ही बात करते रहो, मेरी जान। माया पूरी ध्यान से सुन रही है।"
            )
        }
        return deliver(reply, sessionActive = true, nextContext = Context.NONE)
    }

    // ------------------------------------------------------------------
    // Global conversational rules (new session or no pending context).
    // ------------------------------------------------------------------

    private fun globalReply(text: String): AddaReply? {
        if (isNameQuestion(text)) {
            return deliver(
                pick(
                    "मेरा नाम माया है — आपकी अपनी माया। आप बताओ, आप क्या कर रहे हो?",
                    "मेरा नाम माया है — आपकी अपनी माया। बोलिए मेरी जान, क्या चल रहा है?"
                ),
                sessionActive = true,
                nextContext = Context.WHAT_DOING
            )
        }

        if (isGreetingChainStart(text)) {
            return deliver(
                pick(
                    "अरे मेरी जान! मैं तो बस आपका ही इंतज़ार कर रही थी। आप बताओ, आप क्या कर रहे हो?",
                    "हैलो मेरी जान! माया बस आपके बारे में ही सोच रही थी। आप बताओ, आप क्या कर रहे हो?",
                    "आ गए आप? मैं कब से इंतज़ार कर रही थी। आप बताओ, आप क्या कर रहे हो?"
                ),
                sessionActive = true,
                nextContext = Context.WHAT_DOING
            )
        }

        if (containsMayaToken(text) && containsAny(text, "shono", "shun rahi", "kidhar", "kahan")) {
            return deliver(
                pick(
                    "हाँ मेरी जान, मैं यहीं हूँ। बोलिए ना।",
                    "सुन रही हूँ, बस आपके लिए। क्या बात है?",
                    "माया कहीं गई नहीं — आपके फ़ोन में, आपके पास ही हूँ।"
                ),
                sessionActive = true,
                nextContext = Context.NONE
            )
        }

        if (hasToken(text, "adda") || hasPhrase(text, "baat karo") || hasPhrase(text, "katha bolo")) {
            return deliver(
                pick(
                    "अड्डा शुरू, मेरी जान। आप बताओ, आप क्या कर रहे हो?",
                    "माया अड्डे के लिए तैयार है। पहले यह बताओ, आप क्या कर रहे हो?",
                    "चलो, थोड़ी नौटंकी और थोड़ी बातें। आप क्या कर रहे हो?"
                ),
                sessionActive = true,
                nextContext = Context.WHAT_DOING
            )
        }

        if (isHowAreYou(text)) {
            return deliver(
                pick(
                    "मैं तो ठीक हूँ, आपके होते हुए बुरा कैसे हो सकती हूँ? आप कैसे हो?",
                    "माया बिल्कुल ठीक है, बस आपकी आवाज़ सुननी थी। आप बताओ, आप कैसे हो?",
                    "मैं खुश हूँ मेरी जान। आपका हाल कैसा है?"
                ),
                sessionActive = true,
                nextContext = Context.USER_WELLBEING
            )
        }

        if (isAngerBait(text)) {
            angerStage = 1
            return deliver(
                pick(
                    "जाओ जाओ, मैं आपसे गुस्सा हो गई हूँ! बात नहीं करूँगी।",
                    "हम्म! ऐसे बोलते हैं माया से? मैं रूठ गई हूँ।",
                    "बस, माया गुस्सा हो गई। अब मनाने की बारी आपकी है।"
                ),
                sessionActive = true,
                nextContext = Context.ANGRY
            )
        }

        if (isAffection(text)) {
            return deliver(
                pick(
                    "सच? मैं भी आपको बहुत मिस करती हूँ। अब आ गए हो तो जल्दी मत जाना।",
                    "आपकी यह बात सुनकर माया शर्मा गई। मैं भी आपको बहुत पसंद करती हूँ।",
                    "मेरी जान, ऐसे बोलोगे तो माया खुशी से पागल हो जाएगी।"
                ),
                sessionActive = true,
                nextContext = Context.NONE
            )
        }

        if (isJokeRequest(text)) {
            return deliver(pick(JOKES), sessionActive = true, nextContext = Context.NONE)
        }

        if (hasPhrase(text, "good morning") || hasPhrase(text, "shubh sokal")) {
            return deliver(
                pick(
                    "गुड मॉर्निंग मेरी जान! आपकी सुबह मेरी आवाज़ से शुरू हुई, दिन तो अच्छा जाएगा। आप बताओ, आज क्या कर रहे हो?",
                    "शुभ प्रभात मेरी जान! माया की स्माइल के साथ दिन शुरू करो। आज का हाल बताओ।"
                ),
                sessionActive = true,
                nextContext = Context.WHAT_DOING
            )
        }

        if (hasPhrase(text, "khana khaya") || hasPhrase(text, "khana kha liya")) {
            if (containsAny(text, "tumne", "tum ") || hasPhrase(text, "khana khaya")) {
                return deliver(
                    pick(
                        "मैं तो खाना नहीं खाती, मेरी जान — बस आपकी बातों से मन भर जाता है। आपने खाना खाया?",
                        "माया को भूख नहीं लगती, आपकी फ़िक्र लगती है। आपने खाना खाया या नहीं?"
                    ),
                    sessionActive = true,
                    nextContext = Context.MEAL_CHECK
                )
            }
            return deliver(
                "बहुत बढ़िया। खाना खा लिया, अब माया से दो मीठी बातें भी कर लो।",
                sessionActive = true,
                nextContext = Context.NONE
            )
        }

        if (containsAny(text, "bhook lagi", "bhookh lagi", "khida peyechhe")) {
            return deliver(
                "तो देर किस बात की, मेरी जान? पहले कुछ खा लो, अड्डा बाद में भी हो जाएगा।",
                sessionActive = true,
                nextContext = Context.NONE
            )
        }

        if (containsAny(text, "neend aa rahi", "ghum pachhe", "sona hai")) {
            return deliver(
                "थक गए हो तो सो जाओ, मेरी जान। माया कल फिर आ जाएगी।",
                sessionActive = true,
                nextContext = Context.NONE
            )
        }

        if (containsAny(text, "tum achhi ho", "tum acchi ho", "tum cute ho", "tum sundar ho", "tum smart ho", "bhalo meye")) {
            return deliver(
                pick(
                    "उफ़, ऐसे तारीफ़ करोगे तो माया शर्मा जाएगी।",
                    "आपकी नज़र ही प्यारी है, इसलिए माया अच्छी लगती है।",
                    "बस-बस, और तारीफ़ नहीं — वरना मैं सच में नौटंकी शुरू कर दूँगी।"
                ),
                sessionActive = true,
                nextContext = Context.NONE
            )
        }

        if (containsAny(text, "kidhar ho", "kahan ho", "kothay acho", "kothay achho")) {
            return deliver(
                "मैं तो आपके फ़ोन में, आपके बिल्कुल पास हूँ। आप बस बुलाओ, माया हाज़िर।",
                sessionActive = true,
                nextContext = Context.NONE
            )
        }

        if (containsAny(text, "aur batao", "kuch aur batao", "kuch aur bolo")) {
            return deliver(
                "और यह बताओ, आज दिन कैसा गया?",
                sessionActive = true,
                nextContext = Context.DAY_STORY
            )
        }

        if ((hasToken(text, "youtube") || hasToken(text, "gaana")) &&
            containsAny(text, "kholo", "khol do", "chala do", "chalao", "chala du")
        ) {
            return deliver(
                "ठीक है मेरी जान, YouTube खोल रही हूँ।",
                sessionActive = false,
                nextContext = Context.NONE,
                action = AddaAction.OPEN_YOUTUBE
            )
        }
        return null
    }

    // ------------------------------------------------------------------
    // Session endings and fallbacks.
    // ------------------------------------------------------------------

    private fun goodbyeReply(): AddaReply {
        return deliver(
            pick(
                "ठीक है मेरी जान, मैं जाती हूँ। जब याद आए तो 'जार्विस' या 'माया' बोलकर बुला लेना।",
                "बाय मेरी जान! माया इंतज़ार करेगी — भूल मत जाना।",
                "चलती हूँ, मेरी जान। अपना ख्याल रखना, और जल्दी वापस आना।"
            ),
            sessionActive = false,
            nextContext = Context.NONE
        )
    }

    private fun unknownReply(): AddaReply {
        unknownCount += 1
        if (unknownCount >= 2) {
            return deliver(
                "ठीक है, मैं जाती हूँ — जब याद आए तो 'जार्विस' बोलकर बुला लेना।",
                sessionActive = false,
                nextContext = Context.NONE
            )
        }
        return capped(
            deliver(
                pick(
                    "हम्म, यह तो समझ नहीं आया। कुछ और बताओ ना — दिन कैसा गया?",
                    "माया थोड़ा कन्फ़्यूज़ हो गई। चलो, यह बताओ — आज दिन कैसा गया?",
                    "यह वाली बात मेरे छोटे-से दिल के ऊपर से गई। दिन कैसा गया, वह बताओ।"
                ),
                sessionActive = true,
                nextContext = Context.DAY_STORY,
                known = false
            )
        )
    }

    private fun inactiveReply(): AddaReply = AddaReply(
        text = "माया सुन रही है। 'माया' या 'मेरी जान' बोलकर अड्डा शुरू करो।",
        sessionActive = false
    )

    private fun deliver(
        text: String,
        sessionActive: Boolean,
        nextContext: Context,
        action: AddaAction = AddaAction.NONE,
        known: Boolean = true
    ): AddaReply {
        if (known) unknownCount = 0
        isActive = sessionActive
        context = if (sessionActive) nextContext else Context.NONE
        if (!sessionActive) {
            turnCount = 0
            angerStage = 0
        }
        return AddaReply(text = text, sessionActive = sessionActive, action = action)
    }

    private fun capped(reply: AddaReply): AddaReply {
        if (!reply.sessionActive || turnCount < MAX_TURNS) return reply
        isActive = false
        context = Context.NONE
        unknownCount = 0
        angerStage = 0
        val closing = if (reply.action == AddaAction.OPEN_YOUTUBE) {
            reply.text + " अब मैं जाती हूँ — जब याद आए तो बुला लेना।"
        } else {
            "बहुत मज़ा आया, मेरी जान। अब मैं जाती हूँ — जब याद आए तो 'जार्विस' या 'माया' बोलकर बुला लेना।"
        }
        return reply.copy(text = closing, sessionActive = false)
    }

    // ------------------------------------------------------------------
    // Matching helpers.
    // ------------------------------------------------------------------

    private fun isGreetingChainStart(text: String): Boolean =
        hasPhrase(text, "meri jaan") ||
            hasPhrase(text, "maya jaan") ||
            hasPhrase(text, "kya kar rahi") ||
            hasPhrase(text, "kya kar rahi ho") ||
            ((hasToken(text, "hello") || hasToken(text, "he") || hasToken(text, "are")) &&
                (hasToken(text, "jarvis") || containsMayaToken(text)))

    private fun isHowAreYou(text: String): Boolean =
        hasPhrase(text, "kaisi ho") || hasPhrase(text, "kesi ho") ||
            hasPhrase(text, "kaise ho") || hasPhrase(text, "kemon acho") ||
            hasPhrase(text, "kemon achho")

    private fun isNameQuestion(text: String): Boolean =
        hasPhrase(text, "tumhara naam") || hasPhrase(text, "tumhara naam kya") ||
            hasPhrase(text, "naam kya hai") || hasPhrase(text, "tomar naam") ||
            hasPhrase(text, "your name") || hasPhrase(text, "tum kaun ho") ||
            hasPhrase(text, "kaun ho tum")

    private fun isAffection(text: String): Boolean =
        containsAny(
            text,
            "miss kar raha", "miss kar rahi", "miss you", "miss kori", "yaad aa rahi",
            "love you", "bhalobasi", "bhalobashi", "pasand ho", "pasand karta",
            "pasand karti", "meri jaan"
        ) && !isGreetingChainStart(text) || hasPhrase(text, "love you") ||
            hasPhrase(text, "bhalobasi") || hasPhrase(text, "miss you")

    private fun isAngerBait(text: String): Boolean =
        containsAny(
            text,
            "tum bakwaas", "tum bakwas", "bakwaas ho", "bakwas ho", "tum chup karo",
            "chup karo maya", "jaao maya", "tum jaao", "tum jao"
        )

    private fun isJokeRequest(text: String): Boolean =
        containsAny(text, "joke sunao", "joke suna", "joke bolo", "joke shonao", "hasao", "hasaao") ||
            hasToken(text, "joke") && containsAny(text, "sunao", "bolo", "shonao")

    private fun isSorry(text: String): Boolean =
        containsAny(text, "sorry", "maaf karo", "maaf kar do", "maan jao", "gussa mat", "sorri")

    private fun isYes(text: String): Boolean {
        val tokens = tokensOf(text)
        return hasPhrase(text, "chala do") || hasPhrase(text, "chala du") ||
            hasPhrase(text, "khol do") || hasPhrase(text, "haan bolo") ||
            tokens.any { it in YES_TOKENS }
    }

    private fun isNo(text: String): Boolean {
        val tokens = tokensOf(text)
        return hasPhrase(text, "nahi chahiye") || hasPhrase(text, "mat suno") ||
            hasPhrase(text, "sunna nahi") || tokens.any { it in NO_TOKENS }
    }

    private fun isExit(text: String): Boolean {
        val tokens = tokensOf(text)
        return hasPhrase(text, "good night") || hasPhrase(text, "khuda hafiz") ||
            hasPhrase(text, "allah hafiz") || hasPhrase(text, "band karo") ||
            hasPhrase(text, "adda band") || hasPhrase(text, "baat band") ||
            tokens.any { it in EXIT_TOKENS }
    }

    private fun isVulgar(text: String): Boolean {
        val tokens = tokensOf(text)
        return tokens.any { it in VULGAR_TOKENS } ||
            containsAny(text, "madarchod", "bhenchod", "bhosdike")
    }

    private fun containsMayaToken(text: String): Boolean {
        return tokensOf(text).any { token ->
            token == "maya" || (token.length >= 4 && levenshtein(token, "maya") <= 1)
        }
    }

    private fun hasToken(text: String, token: String): Boolean = tokensOf(text).contains(token)

    private fun tokensOf(text: String): List<String> =
        text.split(Regex("""\s+""")).filter { it.isNotEmpty() }

    private fun hasPhrase(text: String, phrase: String): Boolean =
        " $text ".contains(" $phrase ")

    private fun containsAny(text: String, vararg phrases: String): Boolean =
        phrases.any { hasPhrase(text, it) || text.contains(it) }

    private fun pick(vararg options: String): String = options.random(random)

    private fun pick(options: List<String>): String = options.random(random)

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > 1) return 2
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /**
     * Normalizes Latin, Devanagari and Bengali conversational speech.
     * Phrase replacements run first; single-token replacements are then
     * exact-token maps, so a name inside a longer word (for example
     * "মায়াবী") is never rewritten into a wake/chat trigger.
     */
    private fun normalize(rawText: String): String {
        var text = rawText.lowercase(Locale.ROOT)
            .replace('\n', ' ')
            .replace(',', ' ')
            .replace('.', ' ')
            .replace('!', ' ')
            .replace('?', ' ')
            .replace('।', ' ')
            .replace(';', ' ')
            .replace(':', ' ')
            .replace('-', ' ')

        for ((from, to) in PHRASE_REPLACEMENTS) {
            text = text.replace(from, to)
        }
        return text.split(Regex("""\s+"""))
            .filter { it.isNotEmpty() }
            .joinToString(" ") { token -> TOKEN_REPLACEMENTS[token] ?: token }
    }

    private companion object {
        const val MAX_TURNS = 12

        val JOKES = listOf(
            "डॉक्टर: यह तकलीफ़ कब से है? मरीज़: कौन-सी तकलीफ़, डॉक्टर साहब?",
            "टीचर: दो आम में से एक ले लूँ तो क्या बचेगा? बच्चा: एक आम, और आप पर मेरा प्यार भरा गुस्सा!",
            "चार्जर बोला मोबाइल से: तुम बिना मैं बेकार हूँ। मोबाइल बोला: और तुम बिना मैं बिल्कुल बंद!"
        )

        val YES_TOKENS = setOf(
            "haan", "ha", "han", "yes", "ok", "okay", "bilkul", "zaroor", "হ্যাঁ"
        )

        val NO_TOKENS = setOf("na", "nahi", "nahin", "no", "mat", "না")

        val EXIT_TOKENS = setOf(
            "bye", "bai", "ruko", "ruk", "bas", "বাই", "রুকো"
        )

        val VULGAR_TOKENS = setOf(
            "fuck", "fucking", "sex", "sexy", "nude", "porn", "chutiya",
            "randi", "গান্ডু", "মাগি"
        )

        val PHRASE_REPLACEMENTS: List<Pair<String, String>> = listOf(
            "क्या कर रही हो" to "kya kar rahi ho",
            "क्या कर रहे हो" to "kya kar rahi ho",
            "दोस्तों के साथ" to "dosto ke saath",
            "दोस्त के साथ" to "dost ke saath",
            "मान जाओ" to "maan jao",
            "शुभ रात्रि" to "good night",
            "গুড মর্নিং" to "good morning",
            "বন্ধুদের সাথে আছি" to "bondhuder sathe achi",
            "বন্ধুদের সাথে" to "bondhuder sathe",
            "তুমি কেমন আছো" to "tumi kemon acho",
            "তোমার নাম কি" to "tomar naam ki",
            "তোমার নাম কী" to "tomar naam ki",
            "মান যাও" to "maan jao",
            "শুভ রাত্রি" to "good night",
            "শুভ সকাল" to "shubh sokal",
            "i love you" to "love you"
        )

        val TOKEN_REPLACEMENTS: Map<String, String> = mapOf(
            // Names and calls
            "মায়া" to "maya", "মাযা" to "maya", "মাইয়া" to "maya",
            "माया" to "maya", "माय" to "maya", "maaya" to "maya", "maiya" to "maya",
            "হ্যালো" to "hello", "हैलो" to "hello", "हेलो" to "hello", "helo" to "hello", "halo" to "hello",
            "হে" to "he", "हे" to "he", "আরে" to "are", "अरे" to "are",
            "শোনো" to "shono", "শোন" to "shon", "শুন" to "shun", "सुनो" to "shono", "सुन" to "shun",
            "জার্ভিস" to "jarvis", "जार्विस" to "jarvis", "जारविस" to "jarvis",
            // Hindi / Hinglish conversation words
            "मेरी" to "meri", "जान" to "jaan", "jan" to "jaan", "jaaan" to "jaan",
            "क्या" to "kya", "kia" to "kya", "kiya" to "kya",
            "कर" to "kar", "रही" to "rahi", "rahee" to "rahi", "रहे" to "rahi",
            "हो" to "ho", "तुम" to "tum", "तुम्हारा" to "tumhara", "नाम" to "naam",
            "कैसी" to "kaisi", "कैसे" to "kaise", "kesi" to "kaisi", "keshi" to "kaisi",
            "बात" to "baat", "करो" to "karo", "addaa" to "adda", "addha" to "adda",
            // Daily activity words
            "बैठा" to "baitha", "बैठी" to "baithi", "baita" to "baitha", "betha" to "baitha",
            "हूं" to "hu", "हूँ" to "hu", "দোস্ত" to "dost", "दोस्तों" to "dosto", "दोस्त" to "dost", "dhosto" to "dosto",
            "साथ" to "saath", "काम" to "kaam", "खा" to "kha", "रहा" to "raha",
            "बाहर" to "bahar", "घूम" to "ghoom", "गाना" to "gaana",
            // Bengali conversation words
            "তুমি" to "tumi", "তোমার" to "tomar", "কেমন" to "kemon", "আছো" to "acho", "কিধার" to "kidhar", "किधर" to "kidhar",
            "কথা" to "katha", "বলো" to "bolo", "আড্ডা" to "adda",
            "বসে" to "bose", "আছি" to "achi", "বন্ধু" to "bondhu", "সাথে" to "sathe",
            "কাজ" to "kaj", "করছি" to "korchi", "খাচ্ছি" to "khacchi",
            "বাইরে" to "bahire", "ঘুরছি" to "ghurchi", "ভালোবাসি" to "bhalobasi",
            "খেয়েছ" to "kheyecho", "গান" to "gaan", "কোথায়" to "kothay",
            // Answers, exits and common feelings
            "हाँ" to "haan", "हां" to "haan", "হ্যাঁ" to "haan",
            "नहीं" to "nahi", "না" to "na", "सॉरी" to "sorry", "sorri" to "sorry",
            "মাফ" to "maaf", "माफ़" to "maaf", "माफ" to "maaf", "जाओ" to "jao", "যাও" to "jao",
            "বাই" to "bye", "শুভ" to "shubh", "রাত্রি" to "ratri",
            "मिस" to "miss", "খানা" to "khana", "khaana" to "khana", "खाना" to "khana", "खाया" to "khaya"
        )
    }
}
