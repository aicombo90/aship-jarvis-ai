package com.aship.jarvis.ai

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.SystemClock
import com.aship.jarvis.access.JarvisAccessibilityService
import com.aship.jarvis.commands.PlayIntent
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v37: SCREEN-CHALANE WALA AGENT — Maya ke haath ab
 * dusri apps ki screen tak.
 *
 * Ab tak auzaar bandhe hue the: app kholo, YouTube par
 * pehla video dabao, WhatsApp chat kholo. Ye agent woh
 * kaam karta hai jo koi bandha auzaar nahi karta —
 * dusri app ke ANDAR kai kadam wala kaam: jaise
 * WhatsApp me kisi ko live location bhejna, ya CapCut
 * me gallery ka sabse naya video editor me uthana.
 * Mastishk (Gemini) faisla karta hai, ye file sirf
 * kadam chalati hai:
 *
 *   screen ka saar padho (Accessibility ped se —
 *   package + dikhai dene wale hisse, unke naam,
 *   clickable/editable hona aur beech ke nirdeshank) ->
 *   kaam + saar + ab tak ke kadam mastishk ko bhejo ->
 *   uska EK JSON kadam (tap_text / tap_at / scroll /
 *   type / back / home / wait / ask_user / done /
 *   fail) chalao -> screen thaharne ki saans (~1.2 s)
 *   -> phir saar padho...
 *
 * v39: DRISHTI (vision) jud gayi — par naap-tol ke
 * saath. Saar kaam ka lage (kaafi clickable/editable
 * hisse hon) to kadam bina tasveer ke hi chalta hai
 * (purana node-raasta, 0 extra kota). Screen tasveeri
 * lage — hisse bahut kam/khaali hon, ya saamne browser
 * (Chrome), gallery/photos ya canvas-wali app ho — to
 * us kadam par service se TASVEER aati hai, uspar
 * hisson ke chaukone par nambar bane hote hain
 * (Set-of-Mark, [ScreenVision]), aur mastishk ya to
 * nambar bolta hai (tap_mark — tap pakke beech par,
 * nirdeshank ki galti khatm) ya khaali sat-h ke liye
 * 0–1000 wale nakse par tap_point deta hai. Ek kaam
 * me zyada se zyada [MAX_VISION_CALLS] tasveer-kadam
 * — kota ki hifazat; khatm hon to kaam node-raaste
 * par hi aage badhta hai aur report me "vision n/10"
 * band hota hai.
 *
 * Surakshit screen (bank/lock — FLAG_SECURE) par
 * tasveer kaali aati hai aur node bhi khaali hote
 * hain: woh haal dikhe to agent wahin RUK jata hai
 * aur imaandaari se kehta hai ki ye screen surakshit
 * hai, dikh nahi raha — uske khilaaf zor nahi chalta.
 *
 * v40: PLAYBOOK jud sakta hai — kuchh kaamon ke
 * liye bandhi hidayat (is samay gallery-trash khaali
 * karna, [TrashPlaybook]) jo har kadam ke sandesh me
 * kaam ke neeche rehti hai. Uski ginati-pushti alag
 * pehchani jati hai: playbook wala ask_user sawal
 * pakapaki chetaavni ke chinh rakhe aur user saaf
 * haan kahe, to wohi is daud ki delete-pushti hai —
 * delete wale tap par dwaar dobara nahi khulta.
 * Bina us pushti ke pushti-dwaar bilkul pehle jaisa
 * hi sakht hai.
 *
 * v44: KADAM-JAANCH + ATKE PAR SUDHAAR — har chala
 * kadam ab andheere me nahi chalta. Agle snapshot ke
 * bandhe saar se sasta hastakshar (app + hisson ke
 * naam) bankar pichhle hastakshar se milaya jata
 * hai: screen badli / bilkul nahi badli / app badal
 * gayi — natija "↳ result:" bankar itihas me judta
 * hai, bina kisi extra model-call ya tasveer ke.
 * Ek hi nishana do baar be-asar rahe to itihas me
 * saaf chetaavni jaati hai. Lagatar teen bina-badlav
 * wale kadam = kaam atka: ek bandha khud-Back
 * (recovery) chalta hai; uske baad bhi do aur waisa
 * hi kadam rahe to kaam wahin imaandaari se ruk
 * jata hai (STUCK) — poora kadam/samaye ka khazaana
 * jhulti screen par nahi jalta. wait aur ask_user
 * wale kadam atke me nahi gine jate — woh jaanboojh
 * ka intezaar / user ka samay hai, atka nahi.
 *
 * Bandh: zyada se zyada [MAX_ROUNDS] kadam-faisle aur
 * kul ~[TOTAL_BUDGET_MS] ms; dono me se jo pehle
 * khatm, kaam wahin imaandaari se rukta hai.
 *
 * SEND-PUSHTI (sabse sakht niyam): jis tap ka nishana
 * send / pay / confirm / delete matlab wala ho, woh
 * tap PEHLE NAHI padta. Maya pehle user se poochti
 * hai ("এটা পাঠিয়ে দিই?" — kaam ki bhasha me,
 * [ScreenConfirm] wale pul se: bolkar + screen par),
 * aur saaf "haan" mile tabhi tap padta hai. Kuch aur
 * suna jaye, jawab hi na mile, ya sunna uplabdh na ho
 * — kaam wahin ruk jata hai; jo nahi bhejna tha woh
 * kabhi apne-aap nahi jata. Ek hi nishane ki pushti
 * ek kaam me ek hi baar maangi jati hai.
 *
 * Imaandari: "ho gaya" sirf tab, jab mastishk ne
 * screen dekhkar khud "done" kaha ho. Raste me ruke
 * kaam ka natija hamesha batata hai ki kaam KAHAN tak
 * pahuncha tha. Bheja gaya sandesh/location tabhi
 * "bheja" kahlayega jab pushti wala tap asal me pada
 * ho — usse pehle kabhi nahi.
 *
 * Key na ho ya saare model fail ho jayein (v36 wali
 * kataar hi chalti hai — [GeminiBrain.requestAgentRound])
 * to imaandaar FAILED; is sanskar ka koi offline
 * screen-agent nahi hai.
 *
 * Har daud ki report "jarvis_diag" me "last_screen_run"
 * par band hoti hai: user ke apne shabdon wala kaam
 * (kata hua), natija, aur kitne kadam chale. Model ne
 * khanon me kya LIKHA tha woh report me kabhi nahi
 * jata. Ye class hamesha kisi background thread se
 * bulana (AgentBrain ka executor hi woh thread hai —
 * kadmon ke bandhe intezaar UI ko nahi rokte).
 */
class ScreenAgent(private val context: Context) {

    /** Daud ka kul natija: mastishk (AgentBrain) ke liye sachha varnan. */
    data class Outcome(val ok: Boolean, val text: String)

    /**
     * v39: is daud me kitni tasveer-kadam (vision call)
     * hue — [saveDiag] report me "vision n/10" bankar
     * jata hai. ScreenAgent ek kaam ke liye ek banta
     * hai, isliye ye khet (field) daud-bhar ka hai;
     * [runTask] shuru me use saaf karta hai.
     */
    private var visionUsed = 0

    /**
     * v40: is daud ke saath juda bandha playbook (is
     * samay sirf gallery-trash wala, [TrashPlaybook]) —
     * ho to har kadam ke sandesh me judta hai. Aur
     * [deletePreConfirmed]: user ne playbook wali
     * ginati-pushti (ask_user) me pakapaki chetaavni
     * sunkar SAAF haan kahi ho to is daud ke delete
     * wale tap par [checkConfirmation] dobara nahi
     * poochti — wohi pushti is kaam ki delete-pushti
     * hai. Dono khet daud-bhar ke hain (ScreenAgent
     * ek kaam = ek object); [runTask] shuru me saaf
     * karta hai.
     */
    private var playbookText: String? = null
    private var deletePreConfirmed = false

    /**
     * v44: kadam-jaanch ki report-ginati — is daud me
     * kitne kadmon ke baad screen bilkul nahi badli
     * (nochange) aur kitni baar atke par khud-Back
     * wala sudhaar (recovery) chala. [saveDiag] inhe
     * report me jodti hai; [runTask] shuru me saaf
     * karta hai. (ScreenAgent ek kaam = ek object,
     * isliye ye khet bhi daud-bhar ke hain.)
     */
    private var verifyNoChange = 0
    private var recoveryCount = 0

    /**
     * Ek kaam chalao — bandha (sync) call, background
     * thread se hi bulana. [playbook] ho to uski
     * bandhi hidayat har kadam ke sandesh me judti
     * hai; kaam ka apna text bilkul nahi badalta.
     */
    fun runTask(taskRaw: String, playbook: String? = null): Outcome {
        val task = taskRaw.trim()
        if (task.isEmpty()) {
            return Outcome(false, "No task text was given.")
        }
        if (!GeminiBrain.isConfigured(context)) {
            saveDiag(task, "FAILED (key nahi)", 0)
            return Outcome(
                false,
                "FAILED — the Gemini key is not set on this phone, and " +
                    "the screen agent needs the online brain (there is " +
                    "no offline screen agent). Ask the user to set the " +
                    "key in the app first."
            )
        }
        if (!JarvisAccessibilityService.isEnabled(context)) {
            saveDiag(task, "FAILED (accessibility band)", 0)
            return Outcome(
                false,
                "FAILED — the accessibility service is OFF, so the " +
                    "screen agent can neither read nor touch the " +
                    "screen. Ask the user to turn on Accessibility " +
                    "for ASHIP JARVIS AI first."
            )
        }
        val lang = PlayIntent.requestLanguage(task)
        val deadline = SystemClock.uptimeMillis() + TOTAL_BUDGET_MS
        val history = mutableListOf<String>()
        val confirmedTargets = mutableSetOf<String>()
        var stepsDone = 0
        var invalidReplies = 0
        var rounds = 0
        // v39: tasveer-kadam ki ginati is daud ke liye saaf.
        visionUsed = 0
        // v40: playbook + uski delete-pushti bhi is daud ke liye saaf.
        playbookText = playbook?.trim()?.takeIf { it.isNotEmpty() }
        deletePreConfirmed = false
        // v44: jaanch ki ginati bhi har daud ke liye saaf.
        verifyNoChange = 0
        recoveryCount = 0
        var visionExhaustedNoted = false
        // v44: kadam-jaanch ki haalat. [prevSignature]
        // pichhle snapshot ka hastakshar hai; jo kadam
        // abhi chala aur agle snapshot se jaancha jayega
        // woh [pendingVerify] me rehta hai. Ek hi nishane
        // ke be-asar tap [tapStrikes] me gine jate hain;
        // [sameStreak] lagatar bina-badlav wale kadam
        // ginta hai, [sameAfterRecovery] sudhaar-Back ke
        // baad ke waisa hi kadam.
        var prevSignature: ScreenSignature? = null
        var pendingVerify: PendingVerify? = null
        val tapStrikes = mutableMapOf<String, Int>()
        var sameStreak = 0
        var sameAfterRecovery = 0
        var recoveryDone = false
        while (rounds < MAX_ROUNDS) {
            rounds++
            if (SystemClock.uptimeMillis() > deadline) {
                saveDiag(task, "FAILED (samay khatm)", stepsDone)
                return Outcome(
                    false,
                    "FAILED — the screen task ran out of time after " +
                        "$stepsDone steps. Actions taken so far: " +
                        historySummary(history) + ". Tell the user " +
                        "honestly that the task could not be finished."
                )
            }
            val capturedSnapshot = captureSync()
            if (capturedSnapshot == null) {
                saveDiag(task, "FAILED (screen nahi mila)", stepsDone)
                return Outcome(
                    false,
                    "FAILED — the screen could not be read (the " +
                        "accessibility service may have stopped). " +
                        "Actions taken so far: " +
                        historySummary(history) + "."
                )
            }
            var snapshot = capturedSnapshot
            // v44: Pichhle kadam ki JAANCH — pichhle chakkar
            // me jo kadam chala tha, uske pehle wale
            // hastakshar se is snapshot ka hastakshar milao
            // aur natija itihas me likho: screen badli,
            // bilkul nahi badli, ya app hi badal gayi.
            // Mastishk ab andheere me nahi chalta aur
            // be-asar kadam turant pakda jata hai. Ye
            // jaanch sirf bandhe hue saar par hoti hai —
            // na koi extra model-call, na koi tasveer.
            val pending = pendingVerify
            val prevSig = prevSignature
            if (pending != null && prevSig != null) {
                pendingVerify = null
                val nowSignature = signatureOf(snapshot)
                when (classifyChange(prevSig, nowSignature)) {
                    ChangeVerdict.PACKAGE_CHANGED -> {
                        sameStreak = 0
                        sameAfterRecovery = 0
                        if (pending.targetKey != null) {
                            tapStrikes.remove(pending.targetKey)
                        }
                        history.add(
                            "↳ result: foreground app changed to " +
                                snapshot.packageName
                                    .ifEmpty { "(unknown)" } +
                                " after " + pending.desc
                        )
                    }
                    ChangeVerdict.CHANGED -> {
                        sameStreak = 0
                        sameAfterRecovery = 0
                        if (pending.targetKey != null) {
                            tapStrikes.remove(pending.targetKey)
                        }
                        history.add(
                            "↳ result: screen changed after " +
                                pending.desc
                        )
                    }
                    ChangeVerdict.SAME -> {
                        val strikes = if (pending.targetKey != null) {
                            val n = (tapStrikes[pending.targetKey]
                                ?: 0) + 1
                            tapStrikes[pending.targetKey] = n
                            n
                        } else {
                            0
                        }
                        history.add(
                            if (strikes >= DEAD_TARGET_STRIKES &&
                                pending.targetLabel != null
                            ) {
                                "↳ result: SCREEN DID NOT CHANGE " +
                                    "after " + pending.desc +
                                    " — target \"" +
                                    pending.targetLabel +
                                    "\" has had NO effect " + strikes +
                                    " times in this task; choose a " +
                                    "different element or approach"
                            } else {
                                "↳ result: SCREEN DID NOT CHANGE " +
                                    "after " + pending.desc
                            }
                        )
                        // wait wale kadam (countable = false)
                        // atke me nahi gine jate — woh jaanboojh
                        // ka intezaar hai. ask_user yahan aata
                        // hi nahi (uska pending banta hi nahi).
                        if (pending.countable) {
                            verifyNoChange++
                            if (!recoveryDone) {
                                sameStreak++
                                if (sameStreak >= STUCK_SAME_LIMIT) {
                                    // ATKA — ek bandha sudhaar:
                                    // khud Back dabao, saans lo,
                                    // taaza snapshot lo aur usi
                                    // se ye chakkar aage badhe.
                                    recoveryDone = true
                                    recoveryCount++
                                    sameStreak = 0
                                    val backOk = globalActionSync(
                                        AccessibilityService
                                            .GLOBAL_ACTION_BACK
                                    )
                                    history.add(
                                        "System note: recovery — " +
                                            STUCK_SAME_LIMIT +
                                            " actions in a row changed " +
                                            "nothing on screen, so " +
                                            "Back was pressed once " +
                                            "automatically to try to " +
                                            "unstick" +
                                            if (backOk) {
                                                ""
                                            } else {
                                                " (Back itself reported " +
                                                    "failure)"
                                            } + "."
                                    )
                                    settle(deadline)
                                    val fresh = captureSync()
                                    if (fresh != null) {
                                        history.add(
                                            if (classifyChange(
                                                    nowSignature,
                                                    signatureOf(fresh)
                                                ) == ChangeVerdict.SAME
                                            ) {
                                                "↳ result: SCREEN DID " +
                                                    "NOT CHANGE after " +
                                                    "pressing Back " +
                                                    "(recovery)"
                                            } else {
                                                "↳ result: screen " +
                                                    "changed after " +
                                                    "pressing Back " +
                                                    "(recovery)"
                                            }
                                        )
                                        snapshot = fresh
                                    }
                                }
                            } else {
                                sameAfterRecovery++
                                if (sameAfterRecovery >=
                                    STUCK_POST_RECOVERY_LIMIT
                                ) {
                                    // Sudhaar ke baad bhi screen
                                    // wahi — kaam sach me atka hai.
                                    // Poora khazaana jalane ke
                                    // bajay yahin imaandaari se ruk.
                                    saveDiag(
                                        task,
                                        "STUCK (screen atki — aage " +
                                            "nahi badha)",
                                        stepsDone
                                    )
                                    return Outcome(
                                        false,
                                        "STOPPED — the screen agent " +
                                            "got STUCK after " +
                                            "$stepsDone steps: several " +
                                            "actions in a row changed " +
                                            "nothing on the screen, an " +
                                            "automatic Back did not " +
                                            "unstick it, and the screen " +
                                            "still would not move, so " +
                                            "it stopped early instead " +
                                            "of using up the whole step " +
                                            "and time budget. Actions " +
                                            "taken so far: " +
                                            historySummary(history) +
                                            ". Tell the user honestly, " +
                                            "in their own language, " +
                                            "that Maya got stuck on " +
                                            "this screen and cannot " +
                                            "proceed with this task " +
                                            "right now; say how far it " +
                                            "got from the actions above " +
                                            "and do not claim more " +
                                            "than they show."
                                    )
                                }
                            }
                        }
                    }
                }
            }
            prevSignature = signatureOf(snapshot)
            // v39: is kadam par tasveer chahiye kya? Saar
            // kaam ka ho to node-raasta (purana, kota-mukt);
            // screen tasveeri ho to Set-of-Mark tasveer ke
            // saath vision kadam — kota bandha hai
            // ([MAX_VISION_CALLS] ek kaam me).
            var stepMarked: List<JarvisAccessibilityService.ScreenElement> =
                emptyList()
            var stepImage: String? = null
            val visionWanted = wantsVision(snapshot)
            if (visionWanted && visionUsed < MAX_VISION_CALLS) {
                val shot = screenshotSync()
                if (shot != null) {
                    val usableNodes = snapshot.elements.any {
                        it.clickable || it.editable
                    }
                    if (!usableNodes &&
                        ScreenVision.looksSecureOrBlack(shot)
                    ) {
                        // Surakshit screen: tasveer kaali,
                        // node khaali — aage zor lagana
                        // bekaar aur galat hai. Wahin ruk.
                        saveDiag(
                            task,
                            "STOPPED (screen surakshit — dikh nahi raha)",
                            stepsDone
                        )
                        return Outcome(
                            false,
                            "STOPPED — the current screen is a " +
                                "protected/secure screen (the kind " +
                                "used by banking apps and lock " +
                                "screens): it cannot be seen or " +
                                "captured, and it exposes no " +
                                "readable controls. The screen " +
                                "agent stopped after $stepsDone " +
                                "steps WITHOUT touching anything. " +
                                "Actions taken so far: " +
                                historySummary(history) + ". Tell " +
                                "the user honestly, in their own " +
                                "language, that this screen is " +
                                "protected and Maya cannot see it — " +
                                "they will need to handle this " +
                                "screen themselves."
                        )
                    }
                    val marked = ScreenVision.chooseMarked(snapshot.elements)
                    val rendered = ScreenVision.renderMarkedImage(shot, marked)
                    if (rendered != null) {
                        visionUsed++
                        stepMarked = rendered.marked
                        stepImage = rendered.jpegBase64
                    }
                }
                // Tasveer na mile (dar-seema/gadbad) to ye
                // kadam bina tasveer ke hi chalega — neeche
                // stepImage null hi rehta hai.
            } else if (visionWanted && !visionExhaustedNoted &&
                visionUsed >= MAX_VISION_CALLS
            ) {
                // Kota bandobosto: tasveer ki seema khatm —
                // mastishk ko saaf bata do, taaki woh node
                // kataar se hi kaam chalaye, andaza na lagaye.
                visionExhaustedNoted = true
                history.add(
                    "System note: this task's vision budget " +
                        "($MAX_VISION_CALLS image calls) is used " +
                        "up. Continue with the element list only — " +
                        "you will NOT receive more images in this task."
                )
            }
            val raw = if (stepImage != null) {
                // v43: tasveer wala kadam VISION varg —
                // mukhya model kataar hi chalegi.
                GeminiBrain.requestAgentRoundWithImage(
                    context,
                    SCREEN_INSTRUCTION,
                    listOf(
                        "user" to buildVisionStepPrompt(
                            task, history, snapshot, stepMarked
                        )
                    ),
                    stepImage,
                    GeminiBrain.RequestClass.VISION
                )
            } else {
                // v43: node-only kadam TEXT varg — Lite
                // kataar pehle, mukhya kota bachega.
                GeminiBrain.requestAgentRound(
                    context,
                    SCREEN_INSTRUCTION,
                    listOf("user" to buildStepPrompt(task, history, snapshot)),
                    GeminiBrain.RequestClass.TEXT
                )
            }
            if (raw == null) {
                val quota = GeminiBrain.lastChainWasQuota(context)
                saveDiag(
                    task,
                    if (quota) "FAILED (online seema khatm)" else "FAILED (koi model nahi)",
                    stepsDone
                )
                return Outcome(
                    false,
                    if (quota) {
                        "FAILED — the online (Gemini) limit is exhausted " +
                            "right now, so the screen task stopped after " +
                            "$stepsDone steps. Actions taken so far: " +
                            historySummary(history) + ". Tell the user " +
                            "honestly that the online limit is over and " +
                            "to try again later."
                    } else {
                        "FAILED — no Gemini model answered, so the " +
                            "screen task stopped after $stepsDone steps. " +
                            "Actions taken so far: " +
                            historySummary(history) + "."
                    }
                )
            }
            val action = parseAction(raw)
            if (action == null) {
                invalidReplies++
                history.add(
                    "System note: your last reply was not a valid JSON " +
                        "action object. Reply with ONLY one JSON action."
                )
                if (invalidReplies >= MAX_INVALID_REPLIES) {
                    saveDiag(task, "FAILED (model ka jawab samajh nahi)", stepsDone)
                    return Outcome(
                        false,
                        "FAILED — the screen agent could not understand " +
                            "the model's replies and stopped after " +
                            "$stepsDone steps. Actions taken so far: " +
                            historySummary(history) + "."
                    )
                }
                continue
            }
            invalidReplies = 0
            when (action) {
                is ScreenAction.Done -> {
                    saveDiag(task, "SUCCESS", stepsDone)
                    return Outcome(
                        true,
                        "Screen task COMPLETED in $stepsDone steps. " +
                            "The screen agent's own summary for the " +
                            "user, already in the user's language: \"" +
                            action.say + "\" — use this as your final " +
                            "answer; do not add claims beyond it."
                    )
                }
                is ScreenAction.Fail -> {
                    saveDiag(task, "FAILED (model ne roka)", stepsDone)
                    return Outcome(
                        false,
                        "FAILED — the screen agent stopped after " +
                            "$stepsDone steps. Its own words for the " +
                            "user, already in the user's language: \"" +
                            action.say + "\" — relay this honestly; " +
                            "the task was NOT completed."
                    )
                }
                is ScreenAction.AskUser -> {
                    val waitMs = minOf(
                        ASK_WAIT_MS,
                        deadline - SystemClock.uptimeMillis()
                    )
                    val answer = if (waitMs > 0) {
                        ScreenConfirm.ask(action.question, waitMs)
                    } else {
                        null
                    }
                    history.add(
                        if (answer != null) {
                            "You asked the user: \"" + action.question +
                                "\" — the user answered: \"" + answer + "\""
                        } else {
                            "You asked the user: \"" + action.question +
                                "\" — NO answer was received (nobody " +
                                "replied in time, or listening was not " +
                                "available). Continue only if the task " +
                                "can proceed safely without the answer; " +
                                "otherwise fail honestly."
                        }
                    )
                    // v40: playbook wali delete-pushti ki
                    // pehchan — sawal me pakapaki chetaavni
                    // ke pakke chinh hon, playbook chalu
                    // ho, aur jawab SAAF haan ho, to wohi
                    // jawab is daud ki delete-pushti hai:
                    // aage ke delete wale tap par
                    // checkConfirmation dobara nahi
                    // poochegi. Chinh-bina sawal ki haan
                    // is khaas chhoot ko NAHI deti — tab
                    // aam pushti-dwaar hi chalega.
                    if (answer != null && !deletePreConfirmed &&
                        playbookText != null && isYes(answer) &&
                        PERMANENCE_MARKERS.any {
                            action.question.lowercase(Locale.ROOT).contains(it)
                        }
                    ) {
                        deletePreConfirmed = true
                        history.add(
                            "System note: the user has CONFIRMED " +
                                "permanent deletion after hearing " +
                                "the item count and the permanence " +
                                "warning (the playbook confirmation). " +
                                "Delete taps in this task are " +
                                "pre-confirmed — do NOT ask again; " +
                                "proceed with the deletion."
                        )
                    }
                }
                is ScreenAction.Wait -> {
                    val ms = action.ms.coerceIn(300, MAX_WAIT_MS)
                    sleepQuietly(ms.toLong())
                    stepsDone++
                    history.add("$stepsDone. wait ${ms}ms -> done")
                    // v44: intezaar ki jaanch hogi (screen
                    // badli to not), par atke me ginati nahi —
                    // countable = false.
                    pendingVerify = PendingVerify(
                        "waiting ${ms}ms", null, null, false
                    )
                }
                is ScreenAction.Back -> {
                    val ok = globalActionSync(AccessibilityService.GLOBAL_ACTION_BACK)
                    stepsDone++
                    history.add("$stepsDone. back -> " + if (ok) "OK" else "FAILED")
                    pendingVerify = PendingVerify(
                        "pressing Back", null, null, true
                    )
                    settle(deadline)
                }
                is ScreenAction.Home -> {
                    val ok = globalActionSync(AccessibilityService.GLOBAL_ACTION_HOME)
                    stepsDone++
                    history.add("$stepsDone. home -> " + if (ok) "OK" else "FAILED")
                    pendingVerify = PendingVerify(
                        "pressing Home", null, null, true
                    )
                    settle(deadline)
                }
                is ScreenAction.Scroll -> {
                    val ok = scrollSync(action.down)
                    stepsDone++
                    history.add(
                        "$stepsDone. scroll " +
                            (if (action.down) "down" else "up") +
                            " -> " + if (ok) "OK" else "FAILED"
                    )
                    pendingVerify = PendingVerify(
                        "scrolling " + if (action.down) "down" else "up",
                        null, null, true
                    )
                    settle(deadline)
                }
                is ScreenAction.Type -> {
                    // Likhna apne-aap me bhejna nahi hai — pushti
                    // sirf bhejne/dabane wale kadam par lagti hai.
                    val ok = typeSync(action.value)
                    stepsDone++
                    history.add(
                        "$stepsDone. type (the text you specified) -> " +
                            if (ok) "OK" else "FAILED"
                    )
                    pendingVerify = PendingVerify(
                        "typing text", null, null, true
                    )
                    settle(deadline)
                }
                is ScreenAction.TapText -> {
                    val kind = sensitiveKind(action.value)
                        ?: snapshotKindForText(snapshot, action.value)
                    val refusal = checkConfirmation(
                        kind, labelKey(action.value), lang, task,
                        stepsDone, history, confirmedTargets, deadline
                    )
                    if (refusal != null) return refusal
                    val ok = tapTextSync(action.value)
                    stepsDone++
                    history.add(
                        "$stepsDone. tap_text \"" + action.value + "\" -> " +
                            if (ok) "OK" else "FAILED (not found / not tappable)"
                    )
                    pendingVerify = PendingVerify(
                        "tapping \"" + action.value + "\"",
                        "t:" + labelKey(action.value),
                        action.value,
                        true
                    )
                    settle(deadline)
                }
                is ScreenAction.TapAt -> {
                    val label = nearestLabel(snapshot, action.x, action.y)
                    val kind = label?.let { sensitiveKind(it) }
                    val refusal = checkConfirmation(
                        kind,
                        label?.let { labelKey(it) } ?: "",
                        lang, task, stepsDone, history,
                        confirmedTargets, deadline
                    )
                    if (refusal != null) return refusal
                    val ok = tapPointSync(action.x, action.y)
                    stepsDone++
                    history.add(
                        "$stepsDone. tap_at (" + action.x + ", " + action.y +
                            ") -> " + if (ok) "OK" else "FAILED"
                    )
                    pendingVerify = PendingVerify(
                        if (label != null) {
                            "tapping \"" + label + "\""
                        } else {
                            "tapping at (" + action.x + ", " +
                                action.y + ")"
                        },
                        label?.let { "t:" + labelKey(it) }
                            ?: ("xy:" + action.x + "," + action.y),
                        label ?: ("(" + action.x + ", " + action.y + ")"),
                        true
                    )
                    settle(deadline)
                }
                is ScreenAction.TapMark -> {
                    // v39: nambar wala tap — is kadam ki
                    // tasveer par bane nishan se hissa nikalo
                    // aur uske PAKKE beech par tap karo.
                    val element = stepMarked.getOrNull(action.mark - 1)
                    stepsDone++
                    if (element == null) {
                        history.add(
                            "$stepsDone. tap_mark " + action.mark +
                                " -> FAILED (no such mark on this step — " +
                                "marks exist only on steps where an " +
                                "image was sent)"
                        )
                        pendingVerify = PendingVerify(
                            "tap_mark " + action.mark + " (no such mark)",
                            null, null, true
                        )
                    } else {
                        val kind = sensitiveKind(element.text)
                        val refusal = checkConfirmation(
                            kind, labelKey(element.text), lang, task,
                            stepsDone, history, confirmedTargets, deadline
                        )
                        if (refusal != null) return refusal
                        val ok = tapPointSync(element.centerX, element.centerY)
                        history.add(
                            "$stepsDone. tap_mark " + action.mark +
                                " (\"" + element.text + "\") -> " +
                                if (ok) "OK" else "FAILED"
                        )
                        pendingVerify = PendingVerify(
                            "tapping \"" + element.text +
                                "\" (mark " + action.mark + ")",
                            if (element.text.isNotEmpty()) {
                                "t:" + labelKey(element.text)
                            } else {
                                "xy:" + element.centerX + "," +
                                    element.centerY
                            },
                            element.text.ifEmpty {
                                "(" + element.centerX + ", " +
                                    element.centerY + ")"
                            },
                            true
                        )
                    }
                    settle(deadline)
                }
                is ScreenAction.TapPoint -> {
                    // v39: khaali sat-h ke liye 0–1000 wala
                    // naksa — asal screen px me badalkar tap.
                    val metrics = context.resources.displayMetrics
                    val realX = (action.x * metrics.widthPixels / 1000)
                        .coerceIn(0, (metrics.widthPixels - 1).coerceAtLeast(0))
                    val realY = (action.y * metrics.heightPixels / 1000)
                        .coerceIn(0, (metrics.heightPixels - 1).coerceAtLeast(0))
                    val label = nearestLabel(snapshot, realX, realY)
                    val kind = label?.let { sensitiveKind(it) }
                    val refusal = checkConfirmation(
                        kind,
                        label?.let { labelKey(it) } ?: "",
                        lang, task, stepsDone, history,
                        confirmedTargets, deadline
                    )
                    if (refusal != null) return refusal
                    val ok = tapPointSync(realX, realY)
                    stepsDone++
                    history.add(
                        "$stepsDone. tap_point (" + action.x + ", " +
                            action.y + ") -> " +
                            if (ok) "OK" else "FAILED"
                    )
                    pendingVerify = PendingVerify(
                        if (label != null) {
                            "tapping \"" + label + "\""
                        } else {
                            "tapping point (" + action.x + ", " +
                                action.y + ")"
                        },
                        label?.let { "t:" + labelKey(it) }
                            ?: ("pt:" + action.x + "," + action.y),
                        label ?: ("(" + action.x + ", " + action.y + ")"),
                        true
                    )
                    settle(deadline)
                }
            }
        }
        saveDiag(task, "FAILED (kadam khatm)", stepsDone)
        return Outcome(
            false,
            "FAILED — the screen task used all $MAX_ROUNDS steps " +
                "without finishing. Actions taken: " +
                historySummary(history) + ". Tell the user honestly " +
                "that the task could not be completed." +
                if (visionUsed >= MAX_VISION_CALLS) {
                    " Note: this task also used up its whole " +
                        "vision budget ($MAX_VISION_CALLS image " +
                        "calls) — the free online limit is the " +
                        "binding constraint, not effort."
                } else {
                    ""
                }
        )
    }

    // ------------------------------------------------------------------
    // Pushti (send/pay/delete/confirm) — tap se PEHLE
    // ------------------------------------------------------------------

    /**
     * [kind] hassas (sensitive) ho aur is nishane ki pushti
     * is kaam me pehle na hui ho, to user se poochho. Saaf
     * "haan" mile to nishana yaad karke null lautao (tap
     * chalega); warna kaam wahin rokkar [Outcome] lautao —
     * woh tap kabhi nahi padega.
     */
    private fun checkConfirmation(
        kind: SensitiveKind?,
        targetKey: String,
        lang: PlayIntent.SpokenLang,
        task: String,
        stepsDone: Int,
        history: MutableList<String>,
        confirmedTargets: MutableSet<String>,
        deadline: Long
    ): Outcome? {
        if (kind == null) return null
        // v40: playbook wali ginati-pushti pehle hi mil
        // chuki ho to DELETE tap par dobara mat poochho —
        // wohi pushti is kaam ki delete-pushti thi
        // (ginati + pakapaki chetaavni ke saath li gayi).
        // Pushti na mili ho to ye raasta band rehta hai
        // aur neeche wala aam dwaar hi chalega.
        if (kind == SensitiveKind.DELETE && deletePreConfirmed) return null
        if (targetKey.isNotEmpty() && targetKey in confirmedTargets) {
            return null
        }
        val waitMs = minOf(
            ASK_WAIT_MS,
            deadline - SystemClock.uptimeMillis()
        )
        val answer = if (waitMs > 0) {
            ScreenConfirm.ask(confirmQuestion(lang, kind), waitMs)
        } else {
            null
        }
        if (!isYes(answer)) {
            saveDiag(task, "STOPPED (pushti nahi mili: $kind)", stepsDone)
            return Outcome(
                false,
                "STOPPED — the screen agent reached a " +
                    kind.name.lowercase(Locale.ROOT) + " button after " +
                    "$stepsDone steps and asked the user for " +
                    "confirmation, but no clear YES was received, so " +
                    "the tap was NOT made. " +
                    when (kind) {
                        SensitiveKind.SEND ->
                            "Nothing was sent. Tell the user honestly " +
                                "that the message/location was NOT sent " +
                                "because they did not confirm."
                        SensitiveKind.PAY ->
                            "No payment was made. Tell the user " +
                                "honestly that nothing was paid because " +
                                "they did not confirm."
                        SensitiveKind.DELETE ->
                            "Nothing was deleted. Tell the user " +
                                "honestly that nothing was deleted " +
                                "because they did not confirm."
                        SensitiveKind.CONFIRM ->
                            "Nothing was confirmed. Tell the user " +
                                "honestly that the step was not " +
                                "confirmed because they did not say yes."
                    }
            )
        }
        if (targetKey.isNotEmpty()) {
            confirmedTargets.add(targetKey)
        }
        history.add(
            "System note: the user CONFIRMED the " +
                kind.name.lowercase(Locale.ROOT) +
                " tap — you may proceed with it."
        )
        return null
    }

    /** Hassas matlab wale naam ki shreni, ya null (aam tap). */
    private fun sensitiveKind(label: String): SensitiveKind? {
        val lower = label.lowercase(Locale.ROOT).trim()
        if (lower.isEmpty()) return null
        return when {
            DELETE_WORDS.any { lower.contains(it) } -> SensitiveKind.DELETE
            PAY_WORDS.any { lower.contains(it) } ||
                PAY_WORD.containsMatchIn(lower) -> SensitiveKind.PAY
            SEND_WORDS.any { lower.contains(it) } -> SensitiveKind.SEND
            CONFIRM_WORDS.any { lower.contains(it) } -> SensitiveKind.CONFIRM
            else -> null
        }
    }

    /**
     * tap_text ke liye: saar me woh hissa jiska naam model
     * ke mange hue naam se milta ho — uske asal naam se
     * shreni dekho (model ka manga naam khud saaf ho par
     * asal button hassas ho sakta hai).
     */
    private fun snapshotKindForText(
        snapshot: JarvisAccessibilityService.ScreenSnapshot,
        wanted: String
    ): SensitiveKind? {
        val needle = wanted.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return null
        for (element in snapshot.elements) {
            val label = element.text.lowercase(Locale.ROOT)
            if (label.isEmpty()) continue
            if (label.contains(needle) || needle.contains(label)) {
                sensitiveKind(element.text)?.let { return it }
            }
        }
        return null
    }

    /** (x, y) ke sabse paas wale hisse ka naam — pushti ki jaanch ke liye. */
    private fun nearestLabel(
        snapshot: JarvisAccessibilityService.ScreenSnapshot,
        x: Int,
        y: Int
    ): String? {
        var best: String? = null
        var bestDistance = Long.MAX_VALUE
        for (element in snapshot.elements) {
            val dx = (element.centerX - x).toLong()
            val dy = (element.centerY - y).toLong()
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = element.text
            }
        }
        // Bahut door ka hissa is tap ka nishana nahi mana jayega.
        return if (bestDistance <= MAX_NEAREST_DISTANCE_PX.toLong() *
            MAX_NEAREST_DISTANCE_PX
        ) {
            best
        } else {
            null
        }
    }

    /** Pushti ke nishane ki pehchan — ek kaam me ek baar hi pushti. */
    private fun labelKey(label: String): String =
        label.trim().lowercase(Locale.ROOT)

    /** Pushti wala sawal — kaam ki bhasha me, shreni ke hisaab se. */
    private fun confirmQuestion(
        lang: PlayIntent.SpokenLang,
        kind: SensitiveKind
    ): String = when (kind) {
        SensitiveKind.SEND -> when (lang) {
            PlayIntent.SpokenLang.BENGALI -> "এটা পাঠিয়ে দিই?"
            PlayIntent.SpokenLang.HINDI -> "क्या मैं इसे भेज दूँ?"
            PlayIntent.SpokenLang.ENGLISH -> "Shall I send this?"
        }
        SensitiveKind.DELETE -> when (lang) {
            PlayIntent.SpokenLang.BENGALI -> "এটা মুছে দিই?"
            PlayIntent.SpokenLang.HINDI -> "क्या मैं इसे मिटा दूँ?"
            PlayIntent.SpokenLang.ENGLISH -> "Shall I delete this?"
        }
        SensitiveKind.PAY -> when (lang) {
            PlayIntent.SpokenLang.BENGALI -> "টাকাটা পাঠিয়ে দিই?"
            PlayIntent.SpokenLang.HINDI -> "क्या मैं पैसे भेज दूँ?"
            PlayIntent.SpokenLang.ENGLISH -> "Shall I pay now?"
        }
        SensitiveKind.CONFIRM -> when (lang) {
            PlayIntent.SpokenLang.BENGALI -> "এটা নিশ্চিত করে দিই?"
            PlayIntent.SpokenLang.HINDI -> "क्या मैं इसे कन्फ़र्म कर दूँ?"
            PlayIntent.SpokenLang.ENGLISH -> "Shall I confirm this?"
        }
    }

    /**
     * Jawab saaf "haan" hai kya. Niyam suraksha ki or
     * jhuka hai: mana wale tukde pehle dekhe jate hain
     * ("করো না" me "করো" hone se woh haan nahi banta),
     * aur haan ke liye pakka shabd chahiye — chuppi,
     * samay-khatm aur dhundhla jawab sab "nahi" hai.
     */
    private fun isYes(answerRaw: String?): Boolean {
        val answer = answerRaw?.trim()?.lowercase(Locale.ROOT)
            ?: return false
        if (answer.isEmpty()) return false
        if (NO_PHRASES.any { answer.contains(it) }) return false
        if (answer in NO_EXACT) return false
        return YES_WORDS.any { answer.contains(it) }
    }

    // ------------------------------------------------------------------
    // Model ke kadam — protocol
    // ------------------------------------------------------------------

    private sealed class ScreenAction {
        class TapText(val value: String) : ScreenAction()
        class TapAt(val x: Int, val y: Int) : ScreenAction()

        /** v39: tasveer par bane nambar wala tap (1 se shuru). */
        class TapMark(val mark: Int) : ScreenAction()

        /** v39: 0–1000 khaake wala tap — khaali sat-hon ke liye. */
        class TapPoint(val x: Int, val y: Int) : ScreenAction()
        class Scroll(val down: Boolean) : ScreenAction()
        class Type(val value: String) : ScreenAction()
        object Back : ScreenAction()
        object Home : ScreenAction()
        class Wait(val ms: Int) : ScreenAction()
        class AskUser(val question: String) : ScreenAction()
        class Done(val say: String) : ScreenAction()
        class Fail(val say: String) : ScreenAction()
    }

    private enum class SensitiveKind { SEND, PAY, DELETE, CONFIRM }

    /**
     * Model ke kacche jawab se ek kadam nikalo —
     * [AgentBrain] wala hi andaaz: pehle '{' se aakhiri
     * '}' tak ka JSON. Na bane / anjaan action ho to
     * null (bulane wala use kharab jawab gin lega).
     */
    private fun parseAction(raw: String): ScreenAction? {
        val text = raw.trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = try {
            JSONObject(text.substring(start, end + 1))
        } catch (t: Throwable) {
            return null
        }
        return when (obj.optString("action").trim().lowercase(Locale.ROOT)) {
            "tap_text" -> obj.optString("value").trim()
                .takeIf { it.isNotEmpty() }
                ?.let { ScreenAction.TapText(it) }
            "tap_at" -> {
                val x = obj.optInt("x", -1)
                val y = obj.optInt("y", -1)
                if (x >= 0 && y >= 0) ScreenAction.TapAt(x, y) else null
            }
            "tap_mark" -> {
                val mark = obj.optInt("mark", -1)
                if (mark >= 1) ScreenAction.TapMark(mark) else null
            }
            "tap_point" -> {
                val x = obj.optInt("x", -1)
                val y = obj.optInt("y", -1)
                if (x in 0..1000 && y in 0..1000) {
                    ScreenAction.TapPoint(x, y)
                } else {
                    null
                }
            }
            "scroll" -> ScreenAction.Scroll(
                !obj.optString("direction").trim()
                    .equals("up", ignoreCase = true)
            )
            "type" -> obj.optString("value")
                .takeIf { it.isNotEmpty() }
                ?.let { ScreenAction.Type(it) }
            "back" -> ScreenAction.Back
            "home" -> ScreenAction.Home
            "wait" -> ScreenAction.Wait(obj.optInt("ms", 1000))
            "ask_user" -> obj.optString("question").trim()
                .takeIf { it.isNotEmpty() }
                ?.let { ScreenAction.AskUser(it) }
            "done" -> ScreenAction.Done(obj.optString("say").trim())
            "fail" -> ScreenAction.Fail(obj.optString("say").trim())
            else -> null
        }
    }

    /** Ek kadam ke liye mastishk ko bheja jaane wala poora sandesh. */
    /**
     * v40: is daud ka playbook ho to use kadam wale
     * sandesh me kaam ke theek neeche jodo — mastishk
     * use har kadam par dobara padhta hai, isliye
     * suraksha wali chaal (ginati, chetaavni, pushti)
     * lambe kaam me beech me nahi ghulti.
     */
    private fun appendPlaybook(out: StringBuilder) {
        val text = playbookText ?: return
        out.append("\nPLAYBOOK for this task (follow it exactly):\n")
        out.append(text).append("\n")
    }

    private fun buildStepPrompt(
        task: String,
        history: List<String>,
        snapshot: JarvisAccessibilityService.ScreenSnapshot
    ): String {
        val out = StringBuilder()
        out.append("Task (in the user's own words): ").append(task).append("\n")
        appendPlaybook(out)
        out.append("\n")
        out.append("Actions already taken:\n")
        if (history.isEmpty()) {
            out.append("(none yet)\n")
        } else {
            for (line in history) {
                out.append("- ").append(line).append("\n")
            }
        }
        out.append("\nCurrent screen:\n")
        out.append("App package: ")
            .append(snapshot.packageName.ifEmpty { "(unknown)" })
            .append("\n")
        if (snapshot.elements.isEmpty()) {
            out.append("Visible elements: (none readable)\n")
        } else {
            out.append("Visible elements:\n")
            snapshot.elements.forEachIndexed { index, element ->
                out.append(index + 1).append(". \"")
                    .append(element.text.ifEmpty { "(no label)" })
                    .append("\" | ").append(element.className)
                if (element.clickable) out.append(" | clickable")
                if (element.editable) out.append(" | editable")
                out.append(" @ (")
                    .append(element.centerX).append(", ")
                    .append(element.centerY).append(")\n")
            }
        }
        out.append(
            "\nReply with ONLY one JSON action object for the NEXT step."
        )
        return out.toString()
    }

    /**
     * v39: ye kadam tasveeri hai kya — yani node-saar
     * akela kaafi nahi lagta? Teen sanket: (1) saamne
     * wali app woh hai jahan ped aksar khaali/tasveeri
     * hota hai ([VISION_FIRST_PACKAGES]: browser,
     * gallery/photos, canvas app); (2) kaam ke hisse
     * (clickable/editable) bahut kam hain; (3) hisse
     * hain hi nahi. Koi bhi sanket mile to kadam
     * tasveer ke saath jayega (kota raha to) — warna
     * node-raasta hi kaafi hai.
     */
    private fun wantsVision(
        snapshot: JarvisAccessibilityService.ScreenSnapshot
    ): Boolean {
        if (snapshot.packageName in VISION_FIRST_PACKAGES) return true
        val actionable = snapshot.elements.count {
            it.clickable || it.editable
        }
        return actionable < MIN_ACTIONABLE_FOR_NODE_STEP
    }

    /**
     * v39: vision kadam ke liye sandesh — kaam + itihas
     * + NISHAN wale hisson ki kataar (nambar, naam,
     * pakka beech). Tasveer isi sandesh ke saath
     * inline_data bankar jaati hai; nambar tasveer par
     * bane nambar hi hain, isliye model ka tap_mark
     * seedha usi hisse par padta hai.
     */
    private fun buildVisionStepPrompt(
        task: String,
        history: List<String>,
        snapshot: JarvisAccessibilityService.ScreenSnapshot,
        marked: List<JarvisAccessibilityService.ScreenElement>
    ): String {
        val out = StringBuilder()
        out.append("Task (in the user's own words): ").append(task).append("\n")
        appendPlaybook(out)
        out.append("\n")
        out.append("Actions already taken:\n")
        if (history.isEmpty()) {
            out.append("(none yet)\n")
        } else {
            for (line in history) {
                out.append("- ").append(line).append("\n")
            }
        }
        out.append("\nCurrent screen (an image is attached):\n")
        out.append("App package: ")
            .append(snapshot.packageName.ifEmpty { "(unknown)" })
            .append("\n")
        out.append(
            "The attached image is the actual screen with " +
                "numbered marks drawn on elements. Marked elements:\n"
        )
        if (marked.isEmpty()) {
            out.append("(no elements could be marked — use " +
                "tap_point on the 0-1000 grid only if you can " +
                "clearly see the target in the image)\n")
        } else {
            marked.forEachIndexed { index, element ->
                out.append("Mark ").append(index + 1).append(": \"")
                    .append(element.text.ifEmpty { "(no label)" })
                    .append("\" | ").append(element.className)
                if (element.clickable) out.append(" | clickable")
                if (element.editable) out.append(" | editable")
                out.append(" @ (")
                    .append(element.centerX).append(", ")
                    .append(element.centerY).append(")\n")
            }
        }
        out.append(
            "\nReply with ONLY one JSON action object for the NEXT step."
        )
        return out.toString()
    }

    // ------------------------------------------------------------------
    // v44: Kadam-jaanch — hastakshar, badlav ki shreni, pending kadam
    // ------------------------------------------------------------------

    /**
     * Ek snapshot ka sasta hastakshar — sirf pehle se bandhe
     * hue saar se banta hai: saamne wali app + saare hisson
     * ke naam (kram me bandhe, dohraav ke saath) aur unme se
     * kaam ke (clickable/editable) hisson ke naam alag se.
     * Nirdeshank isme nahi — halki hil-dul par hastakshar
     * nahi badalna chahiye.
     */
    private data class ScreenSignature(
        val packageName: String,
        val labels: List<String>,
        val actionableLabels: List<String>
    )

    /** Jo kadam abhi chala aur agle snapshot se jaancha jayega. */
    private data class PendingVerify(
        /** Itihas ki note ke liye kadam ka varnan ("tapping X"). */
        val desc: String,
        /** Tap wale nishane ki pehchan (be-asar ginati ke liye); tap na ho to null. */
        val targetKey: String?,
        /** Chetaavni note me dikhane wala nishane ka naam. */
        val targetLabel: String?,
        /** false = jaanboojh ka intezaar (wait) — jaanch hogi, atke me ginati nahi. */
        val countable: Boolean
    )

    private enum class ChangeVerdict { SAME, CHANGED, PACKAGE_CHANGED }

    private fun signatureOf(
        snapshot: JarvisAccessibilityService.ScreenSnapshot
    ): ScreenSignature {
        val labels = snapshot.elements.map {
            it.text.ifEmpty { it.className }
        }
        val actionable = snapshot.elements
            .filter { it.clickable || it.editable }
            .map { it.text.ifEmpty { it.className } }
        return ScreenSignature(
            snapshot.packageName,
            labels.sorted(),
            actionable.sorted()
        )
    }

    /**
     * Pichhle hastakshar ke saamne naye ki shreni:
     * - app badli -> PACKAGE_CHANGED.
     * - naam bilkul waisa ke waisa -> SAME.
     * - naam badle par KAAM ke hisson ke naam phir bhi
     *   waisa ke waisa -> SAME hi mano: badlav sirf
     *   ghadi/samaye/typing-sanket jaisi chalti text ka
     *   hai, asal screen wahi ki wahi hai.
     * - warna CHANGED.
     * Jhootha SAME khatarnaak hai (uska bharosa karke
     * galat sudhaar chal jayega ya kaam galat ruk
     * jayega); jhootha CHANGED sirf jaanch ko kamzor
     * karta hai. Isliye niyam jaanboojh kar is or
     * jhuka hai: kaam ke hisson se bahar ki halchal ko
     * badlav mat mano, uske andar ki har halchal ko
     * badlav mano.
     */
    private fun classifyChange(
        prev: ScreenSignature,
        now: ScreenSignature
    ): ChangeVerdict {
        if (prev.packageName != now.packageName) {
            return ChangeVerdict.PACKAGE_CHANGED
        }
        if (prev.labels == now.labels) return ChangeVerdict.SAME
        return if (prev.actionableLabels == now.actionableLabels) {
            ChangeVerdict.SAME
        } else {
            ChangeVerdict.CHANGED
        }
    }

    /** Kadmon ka chhota saar — natije ke imaandaar varnan ke liye. */
    private fun historySummary(history: List<String>): String {
        val actions = history.filter { line ->
            line.firstOrNull()?.isDigit() == true
        }
        return if (actions.isEmpty()) {
            "no actions were taken"
        } else {
            actions.joinToString("; ")
        }
    }

    // ------------------------------------------------------------------
    // Service wale bandhe (sync) raaste — youtube_play wala hi latch
    // ------------------------------------------------------------------

    private fun captureSync(): JarvisAccessibilityService.ScreenSnapshot? {
        val latch = CountDownLatch(1)
        var snapshot: JarvisAccessibilityService.ScreenSnapshot? = null
        var delivered = false
        try {
            JarvisAccessibilityService.captureScreen(context) { result ->
                snapshot = result
                delivered = true
                latch.countDown()
            }
        } catch (t: Throwable) {
            return null
        }
        awaitLatch(latch)
        return if (delivered) snapshot else null
    }

    /**
     * v39: tasveer ka bandha intezaar — [captureSync]
     * wala hi latch. Tasveer na mile (service band,
     * dar-seema, purana Android) to null; bulane wala
     * us kadam par node-raaste se aage badhta hai.
     */
    private fun screenshotSync(): android.graphics.Bitmap? {
        val latch = CountDownLatch(1)
        var bitmap: android.graphics.Bitmap? = null
        var delivered = false
        try {
            JarvisAccessibilityService.captureScreenshot(context) { result ->
                bitmap = result
                delivered = true
                latch.countDown()
            }
        } catch (t: Throwable) {
            return null
        }
        awaitLatch(latch)
        return if (delivered) bitmap else null
    }

    private fun tapTextSync(text: String): Boolean =
        opSync { callback ->
            JarvisAccessibilityService.tapScreenText(context, text, callback)
        }

    private fun tapPointSync(x: Int, y: Int): Boolean =
        opSync { callback ->
            JarvisAccessibilityService.tapScreenPoint(context, x, y, callback)
        }

    private fun scrollSync(down: Boolean): Boolean =
        opSync { callback ->
            JarvisAccessibilityService.scrollScreen(context, down, callback)
        }

    private fun typeSync(text: String): Boolean =
        opSync { callback ->
            JarvisAccessibilityService.typeScreenText(context, text, callback)
        }

    private fun globalActionSync(action: Int): Boolean =
        opSync { callback ->
            JarvisAccessibilityService.screenGlobalAction(context, action, callback)
        }

    /** Ek Boolean wale service-kaam ka bandha intezaar. */
    private fun opSync(
        start: ((Boolean) -> Unit) -> Unit
    ): Boolean {
        val latch = CountDownLatch(1)
        var result = false
        var delivered = false
        try {
            start { value ->
                result = value
                delivered = true
                latch.countDown()
            }
        } catch (t: Throwable) {
            return false
        }
        awaitLatch(latch)
        return delivered && result
    }

    private fun awaitLatch(latch: CountDownLatch) {
        try {
            latch.await(OP_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // Kata hua intezaar — natija "nahi aaya" hi rahega.
        }
    }

    /** Kadam ke baad screen thaharne ki saans (kul seema ke andar hi). */
    private fun settle(deadline: Long) {
        val remaining = deadline - SystemClock.uptimeMillis()
        if (remaining <= 0) return
        sleepQuietly(minOf(SETTLE_MS, remaining))
    }

    private fun sleepQuietly(ms: Long) {
        if (ms <= 0) return
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            // Neend kati — kaam aage badhega.
        }
    }

    // ------------------------------------------------------------------
    // Report
    // ------------------------------------------------------------------

    /**
     * Daud ki report "jarvis_diag" me "last_screen_run"
     * par band karo — AgentBrain wali report wala hi
     * andaaz: <kaam (kata hua)> — <natija> (<n> steps,
     * vision <v>/10, nochange <nc>, recovery <r>)।
     * Likhe hue sandeshon ka text ya tasveer ka data
     * isme kabhi nahi aata — sirf tasveer-kadmon ki
     * GINATI jaati hai (v39) aur jaanch ki ginati
     * (v44: kitne kadam be-asar rahe, kitni baar
     * sudhaar chala).
     */
    private fun saveDiag(task: String, outcome: String, steps: Int) {
        try {
            val value = task.take(MAX_DIAG_TASK_CHARS) +
                " — " + outcome + " (" + steps + " steps, vision " +
                visionUsed + "/" + MAX_VISION_CALLS + ", nochange " +
                verifyNoChange + ", recovery " + recoveryCount + ")"
            context.getSharedPreferences(DIAG_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_SCREEN_RUN, value)
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai; asal kaam isse nahi rukta.
        }
    }

    companion object {
        /** Ek kaam ke liye zyada se zyada itne kadam-faisle. */
        private const val MAX_ROUNDS = 15

        /** Ek kaam ki kul samay-seema (~150 s). */
        private const val TOTAL_BUDGET_MS = 150_000L

        /** Kadam ke baad screen thaharne ki saans. */
        private const val SETTLE_MS = 1_200L

        /** Service ke ek kaam ke natije ka adhiktam intezaar. */
        private const val OP_WAIT_MS = 6_000L

        /** User ke jawab ka adhiktam intezaar (pushti / ask_user). */
        private const val ASK_WAIT_MS = 60_000L

        /** Model ke ek wait kadam ki adhiktam muddat. */
        private const val MAX_WAIT_MS = 5_000

        /** Lagatar itne kharab jawabon par daud rukti hai. */
        private const val MAX_INVALID_REPLIES = 3

        /**
         * v44: lagatar itne bina-badlav wale kadmon par
         * kaam atka mana jata hai aur ek bandha
         * sudhaar-Back (recovery) chalta hai.
         */
        private const val STUCK_SAME_LIMIT = 3

        /**
         * v44: sudhaar-Back ke baad bhi lagatar itne aur
         * bina-badlav wale kadam -> kaam wahin
         * imaandaari se ruk jata hai (STUCK) — poora
         * kadam/samaye ka khazaana nahi jalta.
         */
        private const val STUCK_POST_RECOVERY_LIMIT = 2

        /**
         * v44: ek hi nishana itni baar be-asar rahe to
         * itihas me mastishk ko saaf chetaavni jaati
         * hai — dusra nishana ya dusra raasta chun.
         */
        private const val DEAD_TARGET_STRIKES = 2

        /** tap_at ki pushti ke liye nishane ki adhiktam doori (px). */
        private const val MAX_NEAREST_DISTANCE_PX = 90

        /**
         * v39: ek kaam me zyada se zyada itne tasveer-kadam
         * — Gemini ki muft seema hi asal bandhan hai; isse
         * zyada tasveerein bhejna ek kaam ko poora kota
         * kha jayega. Seema khatm hone par kaam node-raaste
         * par aage badhta hai.
         */
        private const val MAX_VISION_CALLS = 10

        /**
         * v39: node-saar me kaam ke hisse isse kam hon to
         * screen tasveeri mani jati hai (vision kadam).
         */
        private const val MIN_ACTIONABLE_FOR_NODE_STEP = 3

        /**
         * v39: woh package jahan ped aksar khaali ya
         * tasveeri hota hai — browser, gallery/photos,
         * canvas-wali apps (CapCut, Maps). In par kadam
         * pehle tasveer se jayega. Ye ek andaaz (heuristic)
         * kataar hai; kisi app ka naam yahan na bhi ho to
         * kam-hisson wala niyam use phir bhi vision par
         * le jata hai.
         */
        private val VISION_FIRST_PACKAGES = setOf(
            "com.android.chrome",
            "com.google.android.apps.photos",
            "com.android.gallery3d",
            "com.google.android.gallery3d",
            "com.vivo.gallery",
            "com.miui.gallery",
            "com.lemon.lvoverseas",
            "com.lemon.lv",
            "com.google.android.apps.maps"
        )

        private const val DIAG_PREFS_NAME = "jarvis_diag"
        private const val KEY_LAST_SCREEN_RUN = "last_screen_run"
        private const val MAX_DIAG_TASK_CHARS = 40

        private val SEND_WORDS = listOf(
            "send", "সেন্ড", "পাঠাও", "পাঠান", "পাঠিয়ে",
            "भेजें", "भेजो", "भेज"
        )
        private val DELETE_WORDS = listOf(
            "delete", "মুছুন", "মুছে", "ডিলিট",
            "हटाएं", "मिटाएं", "मिटा"
        )
        private val PAY_WORDS = listOf("পেমেন্ট", "भुगतान", "पे करें")
        private val PAY_WORD = Regex("""(^|[^a-z])pay([^a-z]|$)""")
        private val CONFIRM_WORDS = listOf(
            "confirm", "কনফার্ম", "নিশ্চিত", "पुष्टि", "कन्फ़र्म"
        )

        /**
         * v40: pakapaki (permanent) chetaavni ke pakke
         * chinh — playbook wala ask_user sawal inme se
         * koi chinh rakhe aur jawab saaf haan ho, tabhi
         * woh jawab is daud ki delete-pushti mani jati
         * hai. [TrashPlaybook] apne sawal ke shabdon me
         * yehi chinh rakhne ko kehta hai.
         */
        private val PERMANENCE_MARKERS = listOf(
            "পাকাপাকি", "ফেরানো যাবে না",
            "permanent",
            "हमेशा के लिए"
        )

        private val NO_PHRASES = listOf(
            "করো না", "দিও না", "দেবে না", "পাঠিও না", "পাঠাবে না",
            "না পাঠ", "পাঠাতে হবে না", "বাদ দাও", "থাম",
            "don't", "do not", "stop", "cancel",
            "मत कर", "मत भेज", "नहीं", "रुको"
        )
        private val NO_EXACT = setOf("না", "নো", "no", "nah", "न")
        private val YES_WORDS = listOf(
            "হ্যাঁ", "হুম", "হাঁ", "পাঠাও", "পাঠিয়ে", "আচ্ছা",
            "ঠিক আছে", "করে দাও", "করো",
            "yes", "yeah", "ok", "okay",
            "हाँ", "हां", "भेज", "करो"
        )

        /**
         * Screen wale mastishk ke liye nirdesh — wahi
         * Gemini kataar, par is kaam ke liye alag prompt:
         * har jawab SIRF ek JSON kadam, screen par jo
         * asal me dikh raha hai usi par chalo, aur "done"
         * tabhi jab kaam screen par sach me poora dikhe.
         */
        private const val SCREEN_INSTRUCTION =
            "You are the screen-driving agent inside the Android app " +
                "ASHIP JARVIS AI. The user has given you a task to " +
                "complete INSIDE another app by driving its screen. " +
                "At each step you receive: the task in the user's own " +
                "words, the actions already taken, and a compact list " +
                "of the elements currently visible on screen (label, " +
                "type, clickable/editable, center coordinates). On " +
                "some steps an IMAGE of the screen is attached as " +
                "well, with numbered marks drawn on the elements; " +
                "on those steps the element list gives each mark's " +
                "number, label and exact center.\n" +
                "\n" +
                "Reply with ONLY a JSON object, no other text, no " +
                "markdown. Exactly one of these shapes:\n" +
                "{\"action\":\"tap_text\",\"value\":\"<visible label " +
                "of the element to tap>\"}\n" +
                "{\"action\":\"tap_at\",\"x\":<int>,\"y\":<int>}\n" +
                "{\"action\":\"tap_mark\",\"mark\":<mark number from " +
                "the attached image>\"}\n" +
                "{\"action\":\"tap_point\",\"x\":<0-1000>,\"y\":<0-1000>}\n" +
                "{\"action\":\"scroll\",\"direction\":\"down\"|\"up\"}\n" +
                "{\"action\":\"type\",\"value\":\"<text to type into " +
                "the focused input field>\"}\n" +
                "{\"action\":\"back\"}\n" +
                "{\"action\":\"home\"}\n" +
                "{\"action\":\"wait\",\"ms\":<milliseconds up to 5000>}\n" +
                "{\"action\":\"ask_user\",\"question\":\"<one short " +
                "question in the user's language>\"}\n" +
                "{\"action\":\"done\",\"say\":\"<short line in the " +
                "user's language confirming what was actually " +
                "completed>\"}\n" +
                "{\"action\":\"fail\",\"say\":\"<short honest line in " +
                "the user's language saying the task could not be " +
                "completed and why>\"}\n" +
                "\n" +
                "RULES:\n" +
                "- Prefer tap_text with the element's exact visible " +
                "label; use tap_at only when the element has no usable " +
                "label (use its listed center coordinates).\n" +
                "- On steps WITH an attached image: prefer tap_mark " +
                "with the number drawn on the target element — its " +
                "tap lands exactly on that element's known center. " +
                "Use tap_point (x and y on a 0-1000 grid over the " +
                "image) ONLY for targets that have no mark and no " +
                "element entry (photos, icons, canvas areas). " +
                "tap_mark and tap_point work only on steps where an " +
                "image was attached.\n" +
                "- Work ONLY from what the screen list actually shows. " +
                "If the target is not visible, scroll to look for it. " +
                "Never invent elements, labels or results.\n" +
                "- If the needed app is not open yet, use home and tap " +
                "the app's name on the home screen; if you cannot get " +
                "into the app, fail honestly.\n" +
                "- Taps on Send / Pay / Confirm / Delete are NOT " +
                "executed immediately: the app first asks the user " +
                "for confirmation, and the task stops if the user " +
                "does not clearly say yes. Choose such a tap only " +
                "when the task is truly ready for it.\n" +
                "- Use ask_user only when a real choice belongs to " +
                "the user (e.g. several matching contacts). If no " +
                "answer comes back, do not guess — fail honestly.\n" +
                "- Say \"done\" ONLY when the screen shows the task " +
                "is truly complete (e.g. the message appears in the " +
                "chat, the video is loaded in the editor). If you are " +
                "stuck after trying, say \"fail\" honestly.\n" +
                "- Reply in the user's language in \"say\" and " +
                "\"question\" (Bengali task -> Bengali in Bengali " +
                "script; Hindi -> Devanagari; English -> English). " +
                "Keep them to one short spoken sentence."
    }
}
