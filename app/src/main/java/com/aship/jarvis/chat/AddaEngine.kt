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
                        "Uff! Ye sab nahi. Achhi achhi baatein karo na, meri jaan.",
                        "Aisi baatein nahi, meri jaan. Kuch pyaara sa bolo na.",
                        "Maya ko ye sab pasand nahi. Meethi baatein karo, wahi achhi lagti hain."
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
                        "Aree meri jaan! Main to bas aapka hi intezaar kar rahi thi. Aap batao, aap kya kar rahe ho?",
                        "Hello meri jaan! Maya haazir hai. Aap batao, aap kya kar rahe ho?"
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
                    "Ye lo, gaana chala diya — ab khush?",
                    "Ye lo meri jaan, YouTube khol diya. Ab gaana suno aur muskurao.",
                    "Chala diya! Ab to Maya ko ek smile de do."
                ),
                sessionActive = false,
                nextContext = Context.NONE,
                action = AddaAction.OPEN_YOUTUBE
            )
        }
        if (isNo(text)) {
            return deliver(
                pick(
                    "Thik hai, mat suno. Main bhi baat nahi karti!",
                    "Hmph! Gaana bhi nahi sunna? Maya thodi si gussa ho gayi hai.",
                    "Achha, nahi sunna to rehne do. Main apni nautanki kisi aur ko dikhaungi."
                ),
                sessionActive = true,
                nextContext = Context.NONE
            )
        }
        if (isJokeRequest(text)) return null
        return deliver(
            "Bas haan ya na bolo na, meri jaan — gaana chala du?",
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
                    "Tabhi to bolo, pura din meri yaad kyun nahi aayi! Dosto ke saath to mujhe bhool hi gaye. Sach batao, ek baar bhi yaad aayi?",
                    "Dosto ke saath ho, isliye Maya bechari bhool gayi! Ek baar bhi meri yaad aayi ya bilkul nahi?",
                    "Hmm, dosto ki mehfil me Maya gayab! Sach batao, meri yaad aayi thi?"
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
                    "Kya kha rahe ho? Mere liye bhi rakha hai na?",
                    "Waah, khana chal raha hai! Kya bana hai, Maya ko bhi batao na.",
                    "Kha rahe ho aur mujhe bulaya bhi nahi? Kya kha rahe ho, bolo."
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
                    "Bahut kaam karte ho aap. Zyada kaam mat karo, thoda time mere liye bhi rakho.",
                    "Kaam zaroori hai, par Maya ke liye do minute bhi nikaal liya karo.",
                    "Achhe se kaam karo, meri jaan. Bas thak jao to thoda aaraam bhi kar lena."
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
                    "Akele akele ghoom rahe ho, mujhe to le chalte!",
                    "Bahar ki hawa akele hi le li? Maya ko saath le chalna tha na.",
                    "Ghoomna phirna akele, aur yaad karna bhi akele — ye to na-insaafi hai."
                ),
                sessionActive = true,
                nextContext = Context.NONE
            )
        }

        if (containsAny(text, "gaana sun", "music sun", "song sun")) {
            return deliver(
                "Gaana sun rahe ho? YouTube me achha sa gaana chala du?",
                sessionActive = true,
                nextContext = Context.PLAY_SONG
            )
        }

        if (containsAny(text, "so raha", "so rahi", "lete hu", "aaraam kar", "aram korchi")) {
            return deliver(
                pick(
                    "Aaraam kar rahe ho? Thik hai, Maya zyada pareshan nahi karegi.",
                    "Lete lete bhi meri yaad aa gayi? Bas yahi pyaar kaafi hai.",
                    "Aaraam karo meri jaan, main yahin hu."
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
                    "Sirf baithe ho? Bore ho jaoge! YouTube me achha sa gaana chala du?",
                    "Baithkar bore hona mana hai, meri jaan. YouTube me ek achha gaana chala du?",
                    "Khaali baithe ho? Chalo, YouTube me gaana chala deti hu — haan bolo bas."
                ),
                sessionActive = true,
                nextContext = Context.PLAY_SONG
            )
        }

        if (containsAny(text, "youtube dekh", "video dekh", "mobile chala", "phone chala")) {
            return deliver(
                pick(
                    "Phone me ghuse hue ho, aur Maya yahan intezaar kar rahi thi. Thodi baat bhi kar lo.",
                    "Video dekhna baad me, pehle batao — din kaisa gaya?",
                    "Mobile ke andar se bahar aao, meri jaan. Maya se bhi do baatein kar lo."
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
                    "Bas, itna sunna tha. Maya khush ho gayi.",
                    "Sach? Phir dosto ko salaam, aur Maya ko itni si jagah dil me — kaafi hai.",
                    "Dekha, yaad to aayi! Ab gussa khatm, smile shuru."
                ),
                sessionActive = true,
                nextContext = Context.NONE
            )
        }
        if (containsAny(text, "nahi aayi", "bhool gaya", "bhool gaye", "bhule gaya") || isNo(text)) {
            angerStage = 1
            return deliver(
                "Jaao jaao, main aapse gussa ho gayi hu! Dosto me itne kho gaye ki Maya yaad hi nahi aayi.",
                sessionActive = true,
                nextContext = Context.ANGRY
            )
        }
        return null
    }

    private fun eatingDetailReply(text: String): AddaReply {
        val reply = when {
            containsAny(text, "biryani", "machh", "mach", "rui", "ilish") ->
                "Waah, ye to kamaal ki plate hai! Maya ko sirf khushboo hi mili, hissa to mila hi nahi."
            containsAny(text, "bhaat", "bhat", "dal", "sabzi") ->
                "Ghar ka khana sabse pyara hota hai. Pet bhar khana, meri jaan."
            containsAny(text, "cha", "chai", "coffee") ->
                "Ek cup mere naam ka bhi pi lena. Maya door se hi saath de degi."
            else -> pick(
                "Sunne me to bahut tasty lag raha hai. Maya ke liye ek niwala bacha lena!",
                "Waah! Khana bhi chal raha hai aur Maya se baat bhi — din ban gaya.",
                "Achha khana, achhi baatein. Bas khana khatm karke paani pi lena."
            )
        }
        return deliver(reply, sessionActive = true, nextContext = Context.NONE)
    }

    private fun angryReply(text: String): AddaReply {
        if (isSorry(text) || isAffection(text)) {
            angerStage = 0
            return deliver(
                pick(
                    "Achha achha, maaf kiya. Meri jaan jo ho tum.",
                    "Chalo, gussa khatm. Aapki meethi baat ne Maya ko mana liya.",
                    "Thik hai, is baar chhod diya. Dobara Maya ko bhool mat jaana."
                ),
                sessionActive = true,
                nextContext = Context.NONE
            )
        }
        if (angerStage <= 1) {
            angerStage = 2
            return deliver(
                pick(
                    "Haso mat! Main sach me gussa hu. Pehle pyaar se sorry bolo.",
                    "Nautanki lag rahi hai? Maya sach me roothi hai — manaao to sahi.",
                    "Main ab bhi gussa hu. Dosto wali galti itni jaldi nahi bhulti."
                ),
                sessionActive = true,
                nextContext = Context.ANGRY
            )
        }
        angerStage = 0
        return deliver(
            pick(
                "Achha chalo, aapki hasi dekhkar gussa pighal gaya. Maya haar gayi.",
                "Bas bas, aur roothna nahi hota mujhse. Gussa khatm, adda shuru.",
                "Thik hai meri jaan, maan gayi. Ab ek achhi si baat bolo."
            ),
            sessionActive = true,
            nextContext = Context.NONE
        )
    }

    private fun wellbeingReply(text: String): AddaReply? {
        if (containsAny(text, "thik hu", "theek hu", "achha hu", "bhalo achi", "bhalo", "mast hu")) {
            return deliver(
                pick(
                    "Bas, aap thik ho to Maya bhi khush hai.",
                    "Aapki muskurahat hi Maya ke liye kaafi hai.",
                    "Achha hai meri jaan. Aise hi khush raha karo."
                ),
                sessionActive = true,
                nextContext = Context.NONE
            )
        }
        if (containsAny(text, "bura hu", "udaas", "thak gaya", "thaka hu", "bimar", "kharap")) {
            return deliver(
                pick(
                    "Aise mat bolo, meri jaan. Maya hai na — thodi der baat karo, mann halka ho jayega.",
                    "Udaas ho to gaana sunau ya ek joke? Maya aapko hasane ki poori koshish karegi.",
                    "Thak gaye ho to aaraam karo. Main yahin hu, jaldi nahi hai."
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
                "Bahut badiya. Pet khush, to Maya bhi khush.",
                sessionActive = true,
                nextContext = Context.NONE
            )
        }
        if (isNo(text) || containsAny(text, "nahi khaya", "khai ni", "khaoa hoyni")) {
            return deliver(
                "To pehle khana kha lo, meri jaan. Maya intezaar kar legi, bhook nahi.",
                sessionActive = true,
                nextContext = Context.NONE
            )
        }
        return null
    }

    private fun dayStoryReply(text: String): AddaReply {
        val reply = when {
            containsAny(text, "achha gaya", "bhalo gelo", "mast gaya", "bahut achha") ->
                "Waah! Aise hi din roz aayein. Maya ki dua kaam aayi lagta hai."
            containsAny(text, "bura gaya", "kharap gelo", "thak gaya", "mushkil") ->
                "Koi baat nahi, meri jaan. Kal ka din aaj se behtar hoga, Maya ko poora yakeen hai."
            containsAny(text, "kaam", "office", "byasto") ->
                "Kaam bhara din tha? Ab thoda aaraam karo, Maya se halki baat kar lo."
            else -> pick(
                "Hmm, sun liya maine. Aapki chhoti chhoti baatein hi adda ko pyaara banati hain.",
                "Achha laga sunke. Aur batao, ab mann kya keh raha hai?",
                "Bas aise hi baat karte raho, meri jaan. Maya poori dhyaan se sun rahi hai."
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
                    "Mera naam Maya hai — aapki apni Maya. Aap batao, aap kya kar rahe ho?",
                    "Mera naam Maya hai — aapki apni Maya. Boliye meri jaan, kya chal raha hai?"
                ),
                sessionActive = true,
                nextContext = Context.WHAT_DOING
            )
        }

        if (isGreetingChainStart(text)) {
            return deliver(
                pick(
                    "Aree meri jaan! Main to bas aapka hi intezaar kar rahi thi. Aap batao, aap kya kar rahe ho?",
                    "Hello meri jaan! Maya bas aapke baare me hi soch rahi thi. Aap batao, aap kya kar rahe ho?",
                    "Aa gaye aap? Main kab se intezaar kar rahi thi. Aap batao, aap kya kar rahe ho?"
                ),
                sessionActive = true,
                nextContext = Context.WHAT_DOING
            )
        }

        if (containsMayaToken(text) && containsAny(text, "shono", "shun rahi", "kidhar", "kahan")) {
            return deliver(
                pick(
                    "Haan meri jaan, main yahin hu. Boliye na.",
                    "Sun rahi hu, bas aapke liye. Kya baat hai?",
                    "Maya kahin gayi nahi — aapke phone me, aapke paas hi hu."
                ),
                sessionActive = true,
                nextContext = Context.NONE
            )
        }

        if (hasToken(text, "adda") || hasPhrase(text, "baat karo") || hasPhrase(text, "katha bolo")) {
            return deliver(
                pick(
                    "Adda shuru, meri jaan. Aap batao, aap kya kar rahe ho?",
                    "Maya adda ke liye taiyaar hai. Pehle ye batao, aap kya kar rahe ho?",
                    "Chalo, thodi nautanki aur thodi baatein. Aap kya kar rahe ho?"
                ),
                sessionActive = true,
                nextContext = Context.WHAT_DOING
            )
        }

        if (isHowAreYou(text)) {
            return deliver(
                pick(
                    "Main to thik hu, aapke hote hue bura kaise ho sakti hu? Aap kaise ho?",
                    "Maya bilkul thik hai, bas aapki awaaz sunni thi. Aap batao, aap kaise ho?",
                    "Main khush hu meri jaan. Aap ka haal kaisa hai?"
                ),
                sessionActive = true,
                nextContext = Context.USER_WELLBEING
            )
        }

        if (isAngerBait(text)) {
            angerStage = 1
            return deliver(
                pick(
                    "Jaao jaao, main aapse gussa ho gayi hu! Baat nahi karungi.",
                    "Hmph! Aise bolte hain Maya se? Main rooth gayi hu.",
                    "Bas, Maya gussa ho gayi. Ab manaane ki baari aapki hai."
                ),
                sessionActive = true,
                nextContext = Context.ANGRY
            )
        }

        if (isAffection(text)) {
            return deliver(
                pick(
                    "Sach? Main bhi aapko bahut miss karti hu. Ab aa gaye ho to jaldi mat jaana.",
                    "Aapki ye baat sunke Maya sharma gayi. Main bhi aapko bahut pasand karti hu.",
                    "Meri jaan, aise bolo ge to Maya khushi se pagal ho jayegi."
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
                    "Good morning meri jaan! Aapki subah meri awaaz se shuru hui, din to achha jayega. Aap batao, aaj kya kar rahe ho?",
                    "Shubh prabhat meri jaan! Maya ki smile ke saath din shuru karo. Aaj ka haal batao."
                ),
                sessionActive = true,
                nextContext = Context.WHAT_DOING
            )
        }

        if (hasPhrase(text, "khana khaya") || hasPhrase(text, "khana kha liya")) {
            if (containsAny(text, "tumne", "tum ") || hasPhrase(text, "khana khaya")) {
                return deliver(
                    pick(
                        "Main to khana nahi khaati, meri jaan — bas aapki baaton se mann bhar jata hai. Aapne khana khaya?",
                        "Maya ko bhook nahi lagti, aapki fikr lagti hai. Aapne khana khaya ya nahi?"
                    ),
                    sessionActive = true,
                    nextContext = Context.MEAL_CHECK
                )
            }
            return deliver(
                "Bahut badiya. Khana kha liya, ab Maya se do meethi baatein bhi kar lo.",
                sessionActive = true,
                nextContext = Context.NONE
            )
        }

        if (containsAny(text, "bhook lagi", "bhookh lagi", "khida peyechhe")) {
            return deliver(
                "To der kis baat ki, meri jaan? Pehle kuch kha lo, adda baad me bhi ho jayega.",
                sessionActive = true,
                nextContext = Context.NONE
            )
        }

        if (containsAny(text, "neend aa rahi", "ghum pachhe", "sona hai")) {
            return deliver(
                "Thak gaye ho to so jao, meri jaan. Maya kal phir aa jayegi.",
                sessionActive = true,
                nextContext = Context.NONE
            )
        }

        if (containsAny(text, "tum achhi ho", "tum acchi ho", "tum cute ho", "tum sundar ho", "tum smart ho", "bhalo meye")) {
            return deliver(
                pick(
                    "Uff, aise tareef karoge to Maya sharma jayegi.",
                    "Aapki nazar hi pyaari hai, isliye Maya achhi lagti hai.",
                    "Bas bas, aur tareef nahi — warna main sach me nautanki shuru kar dungi."
                ),
                sessionActive = true,
                nextContext = Context.NONE
            )
        }

        if (containsAny(text, "kidhar ho", "kahan ho", "kothay acho", "kothay achho")) {
            return deliver(
                "Main to aapke phone me, aapke bilkul paas hu. Aap bas bulao, Maya haazir.",
                sessionActive = true,
                nextContext = Context.NONE
            )
        }

        if (containsAny(text, "aur batao", "kuch aur batao", "kuch aur bolo")) {
            return deliver(
                "Aur ye batao, aaj din kaisa gaya?",
                sessionActive = true,
                nextContext = Context.DAY_STORY
            )
        }

        if ((hasToken(text, "youtube") || hasToken(text, "gaana")) &&
            containsAny(text, "kholo", "khol do", "chala do", "chalao", "chala du")
        ) {
            return deliver(
                "Thik hai meri jaan, YouTube khol rahi hu.",
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
                "Thik hai meri jaan, main jaati hu. Jab yaad aaye to 'Jarvis' ya 'Maya' bolke bula lena.",
                "Bye meri jaan! Maya intezaar karegi — bhool mat jaana.",
                "Chalti hu, meri jaan. Apna khayal rakhna, aur jaldi wapas aana."
            ),
            sessionActive = false,
            nextContext = Context.NONE
        )
    }

    private fun unknownReply(): AddaReply {
        unknownCount += 1
        if (unknownCount >= 2) {
            return deliver(
                "Thik hai, main jaati hu — jab yaad aaye to 'Jarvis' bolke bula lena.",
                sessionActive = false,
                nextContext = Context.NONE
            )
        }
        return capped(
            deliver(
                pick(
                    "Hmm, ye to samajh nahi aaya. Kuch aur batao na — din kaisa gaya?",
                    "Maya thoda confuse ho gayi. Chalo, ye batao — aaj din kaisa gaya?",
                    "Ye wali baat mere chhote se dil ke upar se gayi. Din kaisa gaya, woh batao."
                ),
                sessionActive = true,
                nextContext = Context.DAY_STORY,
                known = false
            )
        )
    }

    private fun inactiveReply(): AddaReply = AddaReply(
        text = "Maya sun rahi hai. 'Maya' ya 'meri jaan' bolke adda shuru karo.",
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
            reply.text + " Ab main jaati hu — jab yaad aaye to bula lena."
        } else {
            "Bahut maza aaya, meri jaan. Ab main jaati hu — jab yaad aaye to 'Jarvis' ya 'Maya' bolke bula lena."
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
            "Doctor: Ye takleef kab se hai? Mareez: Kaunsi takleef, doctor sahab?",
            "Teacher: Do aam me se ek le lu to kya bachega? Baccha: Ek aam, aur aap par mera pyaar bhara gussa!",
            "Charger bola mobile se: Tum bina main bekaar hu. Mobile bola: Aur tum bina main bilkul band!"
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
