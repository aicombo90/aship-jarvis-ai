package com.aship.jarvis.ai

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.SystemClock
import com.aship.jarvis.access.JarvisAccessibilityService
import com.aship.jarvis.actions.LaunchOutcome
import com.aship.jarvis.actions.PhoneActions
import com.aship.jarvis.commands.CommandEngine
import com.aship.jarvis.commands.PlayIntent
import com.aship.jarvis.commands.SessionCommands
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
 * par hi aage badhta hai aur report me "vision n/15"
 * band hota hai (v45 se seema 10 -> 15).
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
 * v45: TAQAAT-PACK — bandhe badhe aur haath lambe.
 * Kadam-seema 15 -> [MAX_ROUNDS] (30), samay-seema
 * ~150 s -> ~[TOTAL_BUDGET_MS] ms (300 s), tasveer
 * kota 10 -> [MAX_VISION_CALLS] (15): lambe,
 * kai-app wale kaam (Chrome -> ... -> WhatsApp
 * jaise) ab beech me hi khazaana khatm karke nahi
 * rukenge — v43 wali kota-sambhaal (node kadam
 * Lite par, tasveer mukhya par) is kharche ko
 * sambhav banati hai. Naye kadam: open_app (naam/
 * package se app kholo ya badlo — AgentTools ke
 * open_app wali hi mashinari), long_press_*
 * (tap wali hi khoj + wahi pushti-dwaar, ~600 ms
 * dabaye rakhna), swipe (poori-screen ungli ki
 * chaal — gallery/story/carousel ke liye),
 * type_into (NAAM wale khane me likho), submit
 * (focus wale khane ka enter dabao — IME enter,
 * woh na mile to dikhai dene wala Search/Go/Done
 * batan, pushti-dwaar se hokar), wait_for (koi
 * likhai screen par aane tak bandha intezaar —
 * dheeme network par andaza-wale wait ki jagah)।
 * Har naya kadam v44 ki jaanch me bilkul purane
 * kadmon ki tarah judta hai; wait_for bhi wait ki
 * tarah jaancha jata hai par atke me nahi ginta —
 * uska apna FOUND/TIMEOUT hi uski report hai.
 *
 * v46: SURAKSHA-PARDA + AUDIT — taqaat ke saath
 * hifazat ki bandhi bhi. Banking/UPI/payment apps
 * ([ScreenSafety] ki kataar + launcher-naam wala
 * darja) is agent ke liye bilkul band hain: kaam ki
 * shuruat me saamne wali app waisi ho to kaam bina
 * ek bhi kadam liye mana hota hai (REFUSED); beech
 * daud me waisi app saamne aate hi kaam wahin bandh
 * hota hai (BLOCKED) — har chakkar ki pehchan
 * node-saar se hoti hai, isliye koi tasveer waisi
 * screen par kabhi banti hi nahi; aur open_app ka
 * nishana waisi app ho to kholna hi nahi padta
 * (REFUSED). Saath me har daud — safal ho ya roki
 * hui — app ke andar ek audit record chhodti hai
 * ([ScreenAudit]): kaam, har chala kadam uske
 * natije ke saath, jaanch ke faisle, pushtiyan,
 * sudhaar, aakhiri natija. Likhe hue sandeshon ka
 * asal text audit me kabhi nahi jata.
 *
 * v49: TAANA SESSION + SEEKHI CHAAL — session
 * wali daud me har kadam ke sandesh me session
 * ka sandarbh bhi judta hai (pichhle hukm/natije,
 * pehle liya gaya screenshot — [runTask] ka
 * sessionContext), aur kisi bandhe seekhe hue
 * kaam ([LearnedRoutines]) ke kadam playbook
 * bankar lagte hain ("playbook: learned:<naam>"
 * kataar me bhi band hota hai)। SEND pushti me
 * ek bandhi chhoot bhi hai — user ke apne
 * session-hukm ka saaf bhejne-aadesh
 * ([userDirectedSend] dekho); PAY/DELETE/
 * CONFIRM aur banking-rok bilkul pehle jaise।
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
 * ek kaam me ek hi baar maangi jati hai. CHHOOT (v49
 * + v66): user ne APNE hukm me saaf bhejne ko kaha
 * ho ("পাঠিয়ে দাও" / "সেন্ড করে দাও" / "কোনো
 * পারমিশন লাগবে না, একা একাই কর") to SEND tap usi
 * hukm se pehle-se-pusht hai — dobara nahi poochha
 * jata ([userDirectedSend] dekho)। PAY / DELETE /
 * CONFIRM par chhoot kabhi nahi lagti।
 *
 * v66: BHEJNE WALA BATAN AANKH SE — chat app ka send
 * batan aksar bina naam ke teer-aikon hota hai (ped
 * andha hai: naam se pehchana nahi jata)। Isliye
 * likhne ke baad agla kadam tasveer ke saath jata hai
 * ([forceVisionNextRound]) aur mastishk teer ko DEKH
 * kar dabata hai (tap_mark / tap_point); chat me
 * submit (Enter) bhejna nahi, nayi line hai।
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
 * v48: TASVEER-KADAM AB KOTA PAR NAHI MARTA. Live
 * test (2026-11-02) me pehla asal screen-kaam isiliye
 * mara tha — "FAILED (koi model nahi) (0 steps,
 * vision 1/15)" — kyunki mukhya kataar 429 se khatm
 * thi aur tasveer-kadam ke paas koi dusri sharan thi
 * hi nahi. Ab kram teen-padraav wala hai: (1) mukhya
 * kataar (VISION), woh QuotaManager ki haalat se
 * pehle se band ho to use chhoona hi nahi; (2) Lite
 * kataar par tasveer (VISION_LITE) — kataar me
 * "vision via lite (main quota sesh)" bandhta hai;
 * (3) woh bhi na mile to kadam node-raaste (TEXT)
 * par chalta hai — "vision unavailable — node-only
 * step" wala nota kataar me. Kaam model ki wajah se
 * tabhi marta hai jab node-kadam bhi bina jawab ke
 * rahe. Tasveer-kota (vision n/15) teeno haalon me
 * ek hi kadam ke liye ek hi baar ginta hai.
 *
 * Har daud ki report "jarvis_diag" me "last_screen_run"
 * par band hoti hai: user ke apne shabdon wala kaam
 * (kata hua), natija, aur kitne kadam chale. Model ne
 * khanon me kya LIKHA tha woh report me kabhi nahi
 * jata. Ye class hamesha kisi background thread se
 * bulana (AgentBrain ka executor hi woh thread hai —
 * kadmon ke bandhe intezaar UI ko nahi rokte).
 */
class ScreenAgent(
    private val context: Context,
    /**
     * v45: ye daud peeche wali service (wake/orb) se aayi
     * hai kya — sirf [ScreenAction.OpenApp] ke launch
     * raaste ke liye: saamne se aam startActivity,
     * peeche se wahi OverlayLauncher wala pul jo
     * [AgentTools] ka open_app leta hai. Bulane wala
     * (AgentTools) apna jhanda deta hai; na de to
     * saamne wala roop (purana bartao)।
     */
    private val fromBackgroundService: Boolean = false,
    /**
     * v49 + v66: user ke APNE hukm ne bhejne ka
     * saaf aadesh diya tha kya ("পাঠিয়ে দাও" /
     * "সেন্ড করে দাও" / "কোনো পারমিশন লাগবে না,
     * একা একাই কর" — pehchan [SessionCommands] ki
     * hai)। Bulane wala ye jhanda deta hai: wake
     * service ka session-hukm ho ya MainActivity
     * ka saamne wala hukm (TAP TO SPEAK / likha) —
     * hukm aur daud ek hi baari ke hon to session
     * wala bandhan nahi lagta। Sirf tabhi is daud
     * ka SEND tap usi hukm se pehle-se-pusht mana
     * jata hai ([checkConfirmation] dekho)। PAY /
     * DELETE / CONFIRM par iska koi asar NAHI hai,
     * aur banking/payment wali rok (denylist) isse
     * upar hi rehti hai — woh kabhi nahi hat-ti।
     */
    private val userDirectedSend: Boolean = false,
    /**
     * v74: PAKKA BHEJNA (deterministic send) — user ke
     * apne hukm ne bhejne ka saaf aadesh diya ho to
     * bhejne wala kadam CODE chalata hai, model nahi:
     * khana bhara milte hi agent khud send batan
     * dhoondhkar dabata hai aur khana khaali hokar
     * jaanchta hai. Model ka "fail" / mana is kadam
     * par aakhiri faisla nahi rehta (live test
     * 2026-11-08: paste-route chala — 13 kadam, vision
     * 11/15 — par model ne bhejne par "fail" kar diya,
     * "model ne roka")। Bulane wala ([AgentTools])
     * ye jhanda deta hai; na de to purana bartao।
     * Sirf SEND tap par asar: PAY / DELETE / CONFIRM
     * aur banking-rok bilkul pehle jaise।
     */
    private val deterministicSend: Boolean = false
) {

    /** Daud ka kul natija: mastishk (AgentBrain) ke liye sachha varnan. */
    data class Outcome(val ok: Boolean, val text: String)

    /**
     * v39: is daud me kitni tasveer-kadam (vision call)
     * hue — [saveDiag] report me "vision n/15" bankar
     * jata hai (v45 se kota 15). ScreenAgent ek kaam
     * ke liye ek banta hai, isliye ye khet (field)
     * daud-bhar ka hai; [runTask] shuru me use saaf
     * karta hai.
     */
    private var visionUsed = 0

    /**
     * v40: is daud ke saath juda bandha playbook
     * (gallery-trash wala, [TrashPlaybook]; v47 se
     * app-wise pack wala bhi, [Playbooks]) —
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
     * v66: agle chakkar me tasveer jabardasti — likhne
     * (type/type_into) ke baad bhejne wala batan hi agla
     * kadam hota hai, aur woh batan aksar bina naam ke
     * teer-aikon hota hai (ped se pehchana nahi jata) —
     * isliye likhne ke baad agla kadam tasveer ke saath
     * jayega taaki mastishk use DEKH kar dabaye। Daud
     * shuru me saaf hota hai ([runTaskLoop] dekho)।
     */
    private var forceVisionNextRound = false

    /**
     * v74: pakke-bhejne ki ek-baar-wali koshish ho chuki
     * kya — ek daud me deterministic send sirf ek baar
     * chalta hai; na hua to model ko wapas chakkar
     * milta hai (imaandaar Fail ke saath), dobara-dabai
     * nahi hoti। Daud shuru me saaf hota hai
     * ([runTaskLoop] dekho)।
     */
    private var detSendAttempted = false

    /**
     * v49: taana (continuous) session ke andar
     * chali daud ka sandarbh — session ke pichhle
     * hukm + unke natije + "pehle screenshot liya
     * gaya tha" wala sanket। Har kadam ke sandesh
     * me playbook ke theek baad judta hai, taaki
     * "তিন নম্বরটা" / "ওই পেজটা" jaise ishare
     * mastishk session ke hisaab se samjhe, andaze
     * se nahi। Session ke bahar null — sandesh
     * bilkul pehle jaisa।
     */
    private var sessionContextText: String? = null

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
     * v46: is daud ka aakhiri [saveDiag] natija-naam —
     * audit record ka "outcome" yahi banta hai (wahan
     * saveDiag har bade rukhne par bulaya jata hai)।
     * [runTask] shuru me saaf karta hai.
     */
    private var auditOutcomeLabel: String? = null

    /**
     * Ek kaam chalao — bandha (sync) call, background
     * thread se hi bulana. [playbook] ho to uski
     * bandhi hidayat har kadam ke sandesh me judti
     * hai; kaam ka apna text bilkul nahi badalta.
     *
     * v46: ye sirf oopar wala ghaira hai — asal daud
     * [runTaskLoop] me chalti hai, aur kaam jis bhi
     * haal me khatm ho (safal, asafal, ruka, atka,
     * suraksha se mana), aakhir me [ScreenAudit] me
     * ek record band hota hai. Kataar (history) isiliye
     * yahan bankar andar jaati hai.
     */
    fun runTask(
        taskRaw: String,
        playbook: String? = null,
        playbookName: String? = null,
        sessionContext: String? = null
    ): Outcome {
        val history = mutableListOf<String>()
        auditOutcomeLabel = null
        // v49: session sandarbh is daud ke liye
        // bandh gaya (session ke bahar null)।
        sessionContextText = sessionContext
            ?.trim()?.takeIf { it.isNotEmpty() }
        val outcome = try {
            runTaskLoop(taskRaw, playbook, playbookName, history)
        } catch (t: Throwable) {
            // Daud beech me toot jaye tab bhi record bane;
            // bulane wale ke liye imaandaar FAILED hi
            // sahi jawab hai.
            Outcome(
                false,
                "FAILED — the screen task stopped because of an " +
                    "unexpected internal problem. Tell the user " +
                    "honestly that the task could not be done."
            )
        }
        ScreenAudit.append(
            context, taskRaw.trim(), history, auditOutcomeLabel
        )
        return outcome
    }

    /** Asal daud — [runTask] se bulta hai, kataar bahar se aati hai. */
    private fun runTaskLoop(
        taskRaw: String,
        playbook: String?,
        playbookName: String?,
        history: MutableList<String>
    ): Outcome {
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
        val confirmedTargets = mutableSetOf<String>()
        var stepsDone = 0
        var invalidReplies = 0
        var rounds = 0
        // v39: tasveer-kadam ki ginati is daud ke liye saaf.
        visionUsed = 0
        // v40: playbook + uski delete-pushti bhi is daud ke liye saaf.
        playbookText = playbook?.trim()?.takeIf { it.isNotEmpty() }
        deletePreConfirmed = false
        // v66: jabardasti-tasveer wala jhanda bhi saaf.
        forceVisionNextRound = false
        // v74: pakka-bhejne wali ek-baar koshish bhi is daud ke liye saaf.
        detSendAttempted = false
        // v47: pack wala playbook (WhatsApp/Chrome/
        // Gallery — [Playbooks]) laga ho to kataar ki
        // sabse pehli line me uska naam band karo,
        // taaki v46 wala audit record bhi dikhaye ki
        // is daud me kaun si bandhi chaal chali thi
        // ([ScreenAudit] "playbook:" wali lines ko
        // ghatna bankar rakhta hai)। Trash wale
        // playbook ka koi naam nahi aata — uski daud
        // pehle jaisi hi rehti hai।
        if (playbookName != null) {
            history.add("playbook: $playbookName")
        }
        // v66: user ke apne hukm wala bhejna — is daud ka
        // SEND tap pehle-se-pusht hai (bulane wale ne hukm
        // me saaf aadesh pehchana: "পাঠিয়ে দাও" / "সেন্ড
        // করে দাও" / "কোনো পারমিশন লাগবে না, একা একাই
        // কর")। Mastishk ko pehle hi kadam se pata rahe
        // taaki woh bhejne wale kadam par dobara poochne
        // ki koshish na kare — ye note audit me bhi jayega।
        if (userDirectedSend) {
            history.add(
                "System note: user-directed send — the user's " +
                    "own instruction for this task explicitly " +
                    "ordered the send and said no permission is " +
                    "needed, so the send tap is pre-confirmed. " +
                    "Tap the send button directly when the " +
                    "message is typed and ready; do NOT ask the " +
                    "user again."
            )
        }
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
            // v46: SURAKSHA-JAANCH — is chakkar ka sabse
            // pehla kaam: saamne wali app banking/payment
            // wali ho to kaam yahin rukta hai. Pehchan
            // node-saar (package + launcher naam) par hai,
            // isliye na model bulta hai, na tasveer banti
            // hai, na koi kadam chalta hai. Pehle chakkar
            // par mana (REFUSED), beech daud me bandh
            // (BLOCKED) — faisla [blockedOutcomeIfDenied] me.
            blockedOutcomeIfDenied(
                snapshot, rounds == 1, task, stepsDone, history
            )?.let { return it }
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
                        // v45: khamosh kadam (wait_for) ka apna
                        // natija hi uski report hai — dusri
                        // "↳ result" line nahi judti.
                        if (!pending.silentVerdict) {
                            history.add(
                                "↳ result: foreground app changed to " +
                                    snapshot.packageName
                                        .ifEmpty { "(unknown)" } +
                                    " after " + pending.desc
                            )
                        }
                    }
                    ChangeVerdict.CHANGED -> {
                        sameStreak = 0
                        sameAfterRecovery = 0
                        if (pending.targetKey != null) {
                            tapStrikes.remove(pending.targetKey)
                        }
                        if (!pending.silentVerdict) {
                            history.add(
                                "↳ result: screen changed after " +
                                    pending.desc
                            )
                        }
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
                        if (!pending.silentVerdict) {
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
                        }
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
            // v46: sudhaar-Back ne taaza snapshot badla ho
            // to suraksha-jaanch dobara — tasveer/model se
            // pehle. Aam haal me snapshot wahi hai aur ye
            // jaanch pehli wali ki dohraav-matr hai.
            blockedOutcomeIfDenied(
                snapshot, rounds == 1, task, stepsDone, history
            )?.let { return it }
            prevSignature = signatureOf(snapshot)
            // v74: PAKKA BHEJNA — hukm pakka bhejne wala ho
            // ([deterministicSend]) aur khana bhara ho to
            // mastishk se poochhe BINA seedha bhejo: ye
            // chakkar model ka faisla nahi maangta — model
            // ke "fail" se pehle hi kaam ho jana chahiye।
            // Khana khaali ho to kuch nahi hota (null) aur
            // chakkar aam raaste hi chalta hai; ek daud me
            // sirf ek koshish ([detSendAttempted])।
            if (deterministicSend && !detSendAttempted) {
                val det = tryDeterministicSend(
                    snapshot, history, deadline
                )
                if (det != null) {
                    stepsDone += det.stepsUsed
                    detSendAttempted = true
                    if (det.sent) {
                        saveDiag(task, "SUCCESS (det-send)", stepsDone)
                        return Outcome(
                            true,
                            "Screen task COMPLETED in $stepsDone steps. " +
                                "The message was put in the input box and " +
                                "the send button was pressed by the " +
                                "deterministic send step (the model did " +
                                "not decide the send); the input box is " +
                                "empty, so the message went through."
                        )
                    }
                }
            }
            // v39: is kadam par tasveer chahiye kya? Saar
            // kaam ka ho to node-raasta (purana, kota-mukt);
            // screen tasveeri ho to Set-of-Mark tasveer ke
            // saath vision kadam — kota bandha hai
            // ([MAX_VISION_CALLS] ek kaam me).
            var stepMarked: List<JarvisAccessibilityService.ScreenElement> =
                emptyList()
            var stepImage: String? = null
            // v66: likhne ke turant baad wala kadam tasveer
            // ke saath — bhejne wala teer-aikon ped se
            // pehchana nahi jata, use DEKHNA padta hai।
            // Jhanda is chakkar me kharch ho jata hai,
            // kota khatm ho tab bhi (dobara nahi jalega)।
            val visionWanted = wantsVision(snapshot) || forceVisionNextRound
            forceVisionNextRound = false
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
            var raw: String?
            if (stepImage != null) {
                // v43: tasveer wala kadam VISION varg —
                // mukhya model kataar hi chalegi.
                val visionContents = listOf(
                    "user" to buildVisionStepPrompt(
                        task, history, snapshot, stepMarked
                    )
                )
                // v48: mukhya kataar is pal uplabdh hi
                // nahi (QuotaManager: har mukhya model
                // exhausted / thand / uplabdh-nahi) to
                // uspar koshish jalana bekaar hai — seedha
                // Lite wali dusri sharan par jao.
                val mainChainDown = GeminiBrain.MAIN_MODELS.all {
                    QuotaManager.isSkippedToday(context, it)
                }
                var visionRaw: String? = null
                var visionViaLite = false
                if (!mainChainDown) {
                    visionRaw = GeminiBrain.requestAgentRoundWithImage(
                        context,
                        SCREEN_INSTRUCTION,
                        visionContents,
                        stepImage,
                        GeminiBrain.RequestClass.VISION
                    )
                }
                if (visionRaw == null) {
                    // v48: mukhya kataar kota/thand se
                    // mari (ya shuru se hi band thi) —
                    // tasveer Lite modelon ko do. Lite
                    // bhi tasveer samajh sakte hain aur
                    // Set-of-Mark nishan tap ko bandhe
                    // hisse ke pakke beech par hi rakhte
                    // hain, isliye is sharan ki laagat
                    // bandhi hui hai. Ye bhi ek tasveer-
                    // kadam hi hai — [visionUsed] upar
                    // pehle hi is kadam ke liye gina ja
                    // chuka hai, kota waisa hi rehta hai.
                    val liteRaw = GeminiBrain.requestAgentRoundWithImage(
                        context,
                        SCREEN_INSTRUCTION,
                        visionContents,
                        stepImage,
                        GeminiBrain.RequestClass.VISION_LITE
                    )
                    if (liteRaw != null) {
                        visionRaw = liteRaw
                        visionViaLite = true
                    }
                }
                if (visionViaLite) {
                    history.add(
                        "System note: vision via lite (main quota " +
                            "sesh) — the main model chain could not " +
                            "serve this image step, so a Lite model " +
                            "read the image instead. The numbered " +
                            "marks are unchanged."
                    )
                }
                if (visionRaw != null) {
                    raw = visionRaw
                } else {
                    // v48: koi model tasveer nahi le paya —
                    // kaam YAHIN nahi marta (v47 tak marta
                    // tha: "FAILED (koi model nahi)"). Saar
                    // uplabdh ho to ye kadam NODE-raaste par
                    // chalega; kaam model ki wajah se tabhi
                    // marega jab ye node-kadam bhi bina
                    // jawab ke rahe (neeche wala purana
                    // FAILED raasta).
                    history.add(
                        "System note: vision unavailable — " +
                            "node-only step. No model could serve " +
                            "the image this step; decide from the " +
                            "element list only."
                    )
                    raw = GeminiBrain.requestAgentRound(
                        context,
                        SCREEN_INSTRUCTION,
                        listOf(
                            "user" to buildStepPrompt(
                                task, history, snapshot
                            )
                        ),
                        GeminiBrain.RequestClass.TEXT
                    )
                    // Is kadam par model ne nishan dekhe
                    // hi nahi — tap_mark/long_press_mark
                    // ke nambar bekaar hain; khaali kataar
                    // unhe imaandaari se "no such mark"
                    // banayegi.
                    stepMarked = emptyList()
                }
            } else {
                // v43: node-only kadam TEXT varg — Lite
                // kataar pehle, mukhya kota bachega.
                raw = GeminiBrain.requestAgentRound(
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
                    // v74: model ne bhejne se mana kiya — par
                    // hukm pakka bhejne wala ho to uska mana
                    // aakhiri faisla NAHI: deterministic send
                    // abhi ek baar khud koshish karega (khana
                    // bhara ho to)। Safal ho to kaam poora;
                    // na ho to model ka Fail hi imaandaar
                    // jawab rehta hai (neeche waisa hi)।
                    if (deterministicSend && !detSendAttempted) {
                        val det = tryDeterministicSend(
                            snapshot, history, deadline
                        )
                        if (det != null) {
                            stepsDone += det.stepsUsed
                            detSendAttempted = true
                            if (det.sent) {
                                saveDiag(task, "SUCCESS (det-send)", stepsDone)
                                return Outcome(
                                    true,
                                    "Screen task COMPLETED in $stepsDone steps. " +
                                        "The message was put in the input box and " +
                                        "the send button was pressed by the " +
                                        "deterministic send step after the model " +
                                        "refused the send; the input box is " +
                                        "empty, so the message went through."
                                )
                            }
                        }
                    }
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
                    // v66: likh diya — agla kadam aam taur par
                    // bhejne wala batan hi hai; use tasveer ke
                    // saath DEKHA jayega (teer-aikon bina naam ke)।
                    if (ok) forceVisionNextRound = true
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
                is ScreenAction.Paste -> {
                    // Chipkana apne-aap me bhejna nahi hai —
                    // pushti sirf bhejne/dabane wale kadam par
                    // lagti hai (type wala hi niyam)।
                    val result = pasteSync(action.value)
                    val ok = result !=
                        JarvisAccessibilityService.PasteResult.FAILED
                    // v66: chipka diya — agla kadam aam taur par
                    // bhejne wala batan hi hai; use tasveer ke
                    // saath DEKHA jayega (teer-aikon bina naam ke)।
                    if (ok) forceVisionNextRound = true
                    stepsDone++
                    history.add(
                        "$stepsDone. paste -> " + when (result) {
                            JarvisAccessibilityService.PasteResult.PASTED ->
                                "PASTED (clipboard)"
                            JarvisAccessibilityService.PasteResult.TYPED ->
                                "TYPED (paste did not take, typed instead)"
                            JarvisAccessibilityService.PasteResult.FAILED ->
                                "FAILED (could not put the text in the field)"
                        }
                    )
                    pendingVerify = PendingVerify(
                        "pasting text", null, null, true
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
                    noteSendTapMade(kind, ok, history)
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
                    noteSendTapMade(kind, ok, history)
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
                        noteSendTapMade(kind, ok, history)
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
                    noteSendTapMade(kind, ok, history)
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
                is ScreenAction.OpenApp -> {
                    // v46: SURAKSHA — kholne se PEHLE nishana
                    // jaancho: manga naam/package asal me
                    // banking/payment wali app par utre to
                    // kholna hi mana hai aur kaam yahin
                    // bandh. Model home-screen ke aikon se
                    // ghumkar bhi wahan nahi pahunchega —
                    // agli pehchan (chakkar ki shuruat wali
                    // jaanch) use pehle hi kadam par rokegi.
                    val deniedTarget = deniedOpenTarget(action.value)
                    if (deniedTarget != null) {
                        stepsDone++
                        history.add(
                            "$stepsDone. open_app \"" + action.value +
                                "\" -> REFUSED (banking/payment app)"
                        )
                        saveDiag(
                            task,
                            "BLOCKED (open_app refused: " +
                                "banking/payment app)",
                            stepsDone
                        )
                        return Outcome(
                            false,
                            "BLOCKED — the screen agent tried to " +
                                "open a banking/payment app (" +
                                deniedTarget + ") and the safety " +
                                "layer refused: Maya never opens or " +
                                "operates banking/payment apps, for " +
                                "the user's own safety. The app was " +
                                "NOT opened by the agent. Actions " +
                                "taken so far: " +
                                historySummary(history) + ". Tell " +
                                "the user plainly, in their own " +
                                "language, that Maya cannot open or " +
                                "work inside a banking/payment app — " +
                                "for example in Bengali: \"এটা " +
                                "ব্যাংকিং/পেমেন্ট অ্যাপ — এর ভিতরে " +
                                "আমি কাজ করতে পারি না, তোমার " +
                                "সুরক্ষার জন্য।\""
                        )
                    }
                    // v45: app kholna/badlo — mashinari wahi
                    // jo AgentTools ka open_app leta hai
                    // (openAppSync): kai-app wale kaam ab
                    // home-screen par aikon dhoondhne me
                    // kadam nahi jalate.
                    val ok = openAppSync(action.value)
                    stepsDone++
                    history.add(
                        "$stepsDone. open_app \"" + action.value +
                            "\" -> " + if (ok) "OK" else "FAILED"
                    )
                    pendingVerify = PendingVerify(
                        "opening app \"" + action.value + "\"",
                        null, null, true
                    )
                    settle(deadline)
                }
                is ScreenAction.LongPressText -> {
                    // v45: der-dabana — tap_text wala hi
                    // pushti-dwaar (hassas naam ho to pehle
                    // pushti), wahi be-asar ginati.
                    val kind = sensitiveKind(action.value)
                        ?: snapshotKindForText(snapshot, action.value)
                    val refusal = checkConfirmation(
                        kind, labelKey(action.value), lang, task,
                        stepsDone, history, confirmedTargets, deadline
                    )
                    if (refusal != null) return refusal
                    val ok = longPressTextSync(action.value)
                    stepsDone++
                    history.add(
                        "$stepsDone. long_press_text \"" +
                            action.value + "\" -> " +
                            if (ok) {
                                "OK"
                            } else {
                                "FAILED (not found / not pressable)"
                            }
                    )
                    pendingVerify = PendingVerify(
                        "long-pressing \"" + action.value + "\"",
                        "t:" + labelKey(action.value),
                        action.value,
                        true
                    )
                    settle(deadline)
                }
                is ScreenAction.LongPressMark -> {
                    // v45: nambar wala der-dabana — tap_mark
                    // wala hi hisaab, wahi pushti-dwaar.
                    val element = stepMarked.getOrNull(action.mark - 1)
                    stepsDone++
                    if (element == null) {
                        history.add(
                            "$stepsDone. long_press_mark " +
                                action.mark +
                                " -> FAILED (no such mark on this " +
                                "step — marks exist only on steps " +
                                "where an image was sent)"
                        )
                        pendingVerify = PendingVerify(
                            "long_press_mark " + action.mark +
                                " (no such mark)",
                            null, null, true
                        )
                    } else {
                        val kind = sensitiveKind(element.text)
                        val refusal = checkConfirmation(
                            kind, labelKey(element.text), lang, task,
                            stepsDone, history, confirmedTargets, deadline
                        )
                        if (refusal != null) return refusal
                        val ok = longPressPointSync(
                            element.centerX, element.centerY
                        )
                        history.add(
                            "$stepsDone. long_press_mark " +
                                action.mark + " (\"" + element.text +
                                "\") -> " +
                                if (ok) "OK" else "FAILED"
                        )
                        pendingVerify = PendingVerify(
                            "long-pressing \"" + element.text +
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
                is ScreenAction.LongPressPoint -> {
                    // v45: khaake wala der-dabana — tap_point
                    // wala hi badlaav (0–1000 -> asal px),
                    // wahi paas-wale naam ki pushti-jaanch.
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
                    val ok = longPressPointSync(realX, realY)
                    stepsDone++
                    history.add(
                        "$stepsDone. long_press_point (" + action.x +
                            ", " + action.y + ") -> " +
                            if (ok) "OK" else "FAILED"
                    )
                    pendingVerify = PendingVerify(
                        if (label != null) {
                            "long-pressing \"" + label + "\""
                        } else {
                            "long-pressing point (" + action.x +
                                ", " + action.y + ")"
                        },
                        label?.let { "t:" + labelKey(it) }
                            ?: ("pt:" + action.x + "," + action.y),
                        label ?: ("(" + action.x + ", " + action.y + ")"),
                        true
                    )
                    settle(deadline)
                }
                is ScreenAction.Swipe -> {
                    // v45: poori-screen ungli chaal — scroll
                    // (node-wali chaal) se alag; gallery,
                    // story, carousel aur aade liston ke liye.
                    val ok = swipeSync(action.direction)
                    stepsDone++
                    val dirName = action.direction.name
                        .lowercase(Locale.ROOT)
                    history.add(
                        "$stepsDone. swipe $dirName -> " +
                            if (ok) "OK" else "FAILED"
                    )
                    pendingVerify = PendingVerify(
                        "swiping $dirName", null, null, true
                    )
                    settle(deadline)
                }
                is ScreenAction.TypeInto -> {
                    // Likhna (type ki tarah) apne-aap me
                    // bhejna nahi hai — pushti yahan nahi
                    // lagti; bhejne wala kadam (submit/tap)
                    // apni jaanch khud rakhta hai.
                    val ok = typeIntoSync(action.label, action.text)
                    // v66: type wala hi niyam — agla kadam
                    // tasveer ke saath, bhejne wala batan DEKHNE ke liye।
                    if (ok) forceVisionNextRound = true
                    stepsDone++
                    history.add(
                        "$stepsDone. type_into \"" + action.label +
                            "\" -> " +
                            if (ok) {
                                "OK"
                            } else {
                                "FAILED (no matching editable field)"
                            }
                    )
                    pendingVerify = PendingVerify(
                        "typing into \"" + action.label + "\"",
                        null, null, true
                    )
                    settle(deadline)
                }
                is ScreenAction.PasteInto -> {
                    // Chipkana (paste ki tarah) apne-aap me
                    // bhejna nahi hai — pushti yahan nahi
                    // lagti; bhejne wala kadam (submit/tap)
                    // apni jaanch khud rakhta hai.
                    val result = pasteIntoSync(action.label, action.text)
                    val ok = result !=
                        JarvisAccessibilityService.PasteResult.FAILED
                    // v66: type wala hi niyam — agla kadam
                    // tasveer ke saath, bhejne wala batan DEKHNE ke liye।
                    if (ok) forceVisionNextRound = true
                    stepsDone++
                    history.add(
                        "$stepsDone. paste_into \"" + action.label +
                            "\" -> " + when (result) {
                            JarvisAccessibilityService.PasteResult.PASTED ->
                                "PASTED (clipboard)"
                            JarvisAccessibilityService.PasteResult.TYPED ->
                                "TYPED (paste did not take, typed instead)"
                            JarvisAccessibilityService.PasteResult.FAILED ->
                                "FAILED (no matching editable field)"
                        }
                    )
                    pendingVerify = PendingVerify(
                        "pasting into \"" + action.label + "\"",
                        null, null, true
                    )
                    settle(deadline)
                }
                is ScreenAction.Submit -> {
                    // v45: submit — bandha kram, teesra koi
                    // raasta nahi: (1) focus wale khane ka
                    // IME enter; (2) woh na mile to dikhai
                    // dene wala Search/Go/Done batan —
                    // pushti-dwaar se hokar (uska naam
                    // hassas ho to pehle pushti); (3) dono
                    // na hon to imaandaar FAILED.
                    var ok = submitImeSync()
                    var how = "enter key"
                    if (!ok) {
                        val buttonLabel = findSubmitButtonSync()
                        if (buttonLabel != null) {
                            val kind = sensitiveKind(buttonLabel)
                            val refusal = checkConfirmation(
                                kind, labelKey(buttonLabel), lang,
                                task, stepsDone, history,
                                confirmedTargets, deadline
                            )
                            if (refusal != null) return refusal
                            ok = tapTextSync(buttonLabel)
                            how = "button \"" + buttonLabel + "\""
                        } else {
                            how = "no enter key, no search/go button"
                        }
                    }
                    stepsDone++
                    history.add(
                        "$stepsDone. submit -> " +
                            if (ok) {
                                "OK ($how)"
                            } else {
                                "FAILED ($how)"
                            }
                    )
                    pendingVerify = PendingVerify(
                        "submitting",
                        null, null, true
                    )
                    settle(deadline)
                }
                is ScreenAction.WaitFor -> {
                    // v45: likhai aane ka bandha intezaar —
                    // wait wala hi bartao (jaanch hogi, atke
                    // me ginati nahi), bas iska apna
                    // FOUND/TIMEOUT hi iski report hai,
                    // isliye jaanch khamosh (silentVerdict)।
                    // Kul samay-seema (deadline) ka samman:
                    // intezaar use paar nahi jata.
                    val timeoutMs = action.timeoutMs.coerceIn(
                        MIN_WAIT_FOR_MS, MAX_WAIT_FOR_MS
                    )
                    val startedAt = SystemClock.uptimeMillis()
                    var found = false
                    while (true) {
                        val snap = captureSync()
                        if (snap != null &&
                            snapshotHasText(snap, action.text)
                        ) {
                            found = true
                            break
                        }
                        val elapsed =
                            SystemClock.uptimeMillis() - startedAt
                        if (elapsed >= timeoutMs) break
                        if (SystemClock.uptimeMillis() > deadline) break
                        sleepQuietly(
                            minOf(
                                WAIT_FOR_POLL_MS,
                                (timeoutMs - elapsed).toLong()
                            )
                        )
                    }
                    val waitedMs =
                        SystemClock.uptimeMillis() - startedAt
                    stepsDone++
                    history.add(
                        "$stepsDone. wait_for \"" + action.text +
                            "\" -> " +
                            if (found) {
                                "FOUND (" +
                                    formatSeconds(waitedMs) + ")"
                            } else {
                                "TIMEOUT (" +
                                    formatSeconds(waitedMs) + ")"
                            }
                    )
                    pendingVerify = PendingVerify(
                        "waiting for \"" + action.text + "\"",
                        null, null, false,
                        silentVerdict = true
                    )
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
    // v46: Suraksha-parda — banking/payment apps par rok
    // ------------------------------------------------------------------

    /**
     * Is pal saamne wali app mana ho to kaam rokne wala
     * [Outcome], warna null. [atTaskStart] = pehla
     * chakkar: wahan rok ka naam REFUSED hai (ek bhi
     * kadam nahi chala); beech daud me BLOCKED. Dono
     * haal me: koi model-call nahi, koi tasveer nahi,
     * koi kadam nahi — pehchan sirf node-saar ki
     * [JarvisAccessibilityService.ScreenSnapshot.packageName]
     * aur launcher naam par hai ([ScreenSafety])।
     * Rok audit ke liye kataar me "System note" bankar
     * bhi rehti hai.
     */
    private fun blockedOutcomeIfDenied(
        snapshot: JarvisAccessibilityService.ScreenSnapshot,
        atTaskStart: Boolean,
        task: String,
        stepsDone: Int,
        history: MutableList<String>
    ): Outcome? {
        val reason = ScreenSafety.denyReason(context, snapshot.packageName)
            ?: return null
        val pkg = snapshot.packageName.ifEmpty { "(unknown)" }
        history.add(
            "System note: task stopped by the safety layer — " +
                "banking/payment app in foreground (" + pkg +
                ", matched by " + reason + "); no action was " +
                "taken inside it."
        )
        return if (atTaskStart) {
            saveDiag(task, "REFUSED (banking/payment app)", stepsDone)
            Outcome(
                false,
                "REFUSED — this screen task starts inside a " +
                    "banking/payment app (" + pkg + "), and Maya " +
                    "never operates inside banking or payment " +
                    "apps, for the user's own safety. NOTHING " +
                    "was done: no step was taken and the app " +
                    "was not touched. Tell the user plainly, in " +
                    "their own language, that Maya cannot work " +
                    "inside a banking/payment app — for example " +
                    "in Bengali: \"এটা ব্যাংকিং/পেমেন্ট অ্যাপ — " +
                    "এর ভিতরে আমি কাজ করতে পারি না, তোমার " +
                    "সুরক্ষার জন্য।\""
            )
        } else {
            saveDiag(task, "BLOCKED (banking/payment app)", stepsDone)
            Outcome(
                false,
                "BLOCKED — after " + stepsDone + " steps a " +
                    "banking/payment app (" + pkg + ") came to " +
                    "the foreground, so the screen task stopped " +
                    "immediately: Maya never operates inside " +
                    "banking or payment apps, for the user's own " +
                    "safety. Nothing was tapped, typed or " +
                    "captured inside it. Actions taken so far: " +
                    historySummary(history) + ". Tell the user " +
                    "plainly, in their own language, that Maya " +
                    "stopped because a banking/payment app is " +
                    "open and she cannot work inside it."
            )
        }
    }

    /**
     * open_app ka manga nishana mana hai kya — ho to
     * asal package (ya package na milne par manga naam
     * khud), warna null. Kram:
     * - manga shabda package-naam ke nakse par utre to
     *   wahi pehchan hai — seedha [ScreenSafety] jaanch.
     * - warna wahi pakki khoj jo kholne wali mashinari
     *   leti hai ([CommandEngine.resolveAppPackage] —
     *   tryOpenApp ka sirf-padhne wala roop): usse mila
     *   package hi asal nishana hai, usi ki jaanch.
     * - package na mile (app is phone me mile hi nahi)
     *   to aakhiri darja: naam ka apna roop
     *   ([ScreenSafety.nameLooksDenied])।
     */
    private fun deniedOpenTarget(nameRaw: String): String? {
        val value = nameRaw.trim()
        if (value.isEmpty()) return null
        if (PACKAGE_NAME_PATTERN.matches(value)) {
            return if (ScreenSafety.isDenied(context, value)) {
                value
            } else {
                null
            }
        }
        val resolved = try {
            CommandEngine(context, fromBackgroundService)
                .resolveAppPackage(value)
        } catch (t: Throwable) {
            null
        }
        if (resolved != null) {
            return if (ScreenSafety.isDenied(context, resolved)) {
                resolved
            } else {
                null
            }
        }
        return if (ScreenSafety.nameLooksDenied(value)) value else null
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
        // v49: USER KE APNE HUKM WALA BHEJNA — taana
        // session ke andar user ne is kaam ke apne
        // hukm me saaf bhejne ko kaha tha ("পাঠিয়ে
        // দাও") aur bulane wale ne woh pehchan is
        // daud ko di hai ([userDirectedSend])। Us
        // haal me SEND tap par alag se pushti NAHI
        // maangi jati — hukm khud hi pushti hai।
        // Kataar me iska saaf nishan band hota hai
        // (audit record me bhi yahi jayega)।
        // Sirf SEND ke liye hai: PAY / DELETE /
        // CONFIRM hamesha pehle wala dwaar hi
        // dekhenge, aur banking/payment wali rok
        // isse pehle hi (capture jaanch me) lag
        // chuki hoti hai — woh kabhi nahi hat-ti।
        if (kind == SensitiveKind.SEND && userDirectedSend) {
            history.add(
                "System note: user-directed send — the " +
                    "user's own command in this session " +
                    "explicitly instructed this send, so " +
                    "the send tap is treated as " +
                    "pre-confirmed by that instruction " +
                    "(no separate confirmation was asked)."
            )
            return null
        }
        if (targetKey.isNotEmpty() && targetKey in confirmedTargets) {
            return null
        }
        // v46: pushti MAANGI gayi — audit ke liye kataar
        // me nishan rakho. Sirf shreni ka naam jata hai;
        // user ke jawab ka asal text kataar me kabhi
        // nahi aata (mili/na-mili hi kaafi hai)।
        history.add(
            "System note: a " +
                kind.name.lowercase(Locale.ROOT) +
                " confirmation was asked."
        )
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
            // v46: pushti NAHI mili — audit me ye bhi
            // band ho (tap nahi pada, yehi is record ki
            // sabse zaroori baat hai)।
            history.add(
                "System note: the " +
                    kind.name.lowercase(Locale.ROOT) +
                    " confirmation was NOT given — the tap " +
                    "was NOT made."
            )
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

    /**
     * v66: SEND tap padne ke baad jaanch-note — tap
     * padna hi bhejna nahi hai: agle snapshot me
     * mastishk ko dekhna hoga ki likhai khane se
     * gayi aur sandesh chat me dikha ya nahi। Ye
     * note usi ki yaad dilata hai (aur audit me
     * bhi rehta hai)। Sirf safal SEND tap par।
     */
    private fun noteSendTapMade(
        kind: SensitiveKind?,
        ok: Boolean,
        history: MutableList<String>
    ) {
        if (kind != SensitiveKind.SEND || !ok) return
        history.add(
            "System note: the send tap was made — on the " +
                "next screen, verify the message left the " +
                "input box and appears in the chat before " +
                "saying done. If the text is still sitting " +
                "in the input box, the send did NOT go " +
                "through — tap the send button again once, " +
                "then check again."
        )
    }

    // ------------------------------------------------------------------
    // v74: PAKKA BHEJNA (deterministic send) — model ka veto nahi
    // ------------------------------------------------------------------

    /**
     * Ek pakke-bhejne ki koshish ka natija: [sent] = khana
     * khaali hokar sandesh gaya (jaancha hua); [stepsUsed]
     * = dabane wale kadmon ki ginati (report ke liye)।
     */
    private data class DetSendResult(val sent: Boolean, val stepsUsed: Int)

    /**
     * v74: DETERMINISTIC SEND — mastishk se poochhe BINA
     * bhejo. Live test (2026-11-08) me paste-route chala
     * (13 kadam, vision 11/15) par model ne bhejne wale
     * kadam par khud "fail" kar diya ("model ne roka"):
     * v67 wala raasta sirf ROUTING se model ko hatata
     * tha, kadam ab bhi uske faisle par chalte the.
     * Isliye bhejna ab code karta hai — model ka mana
     * is kadam par aakhiri faisla nahi:
     *
     *  1. bhara hua khana (editable + text) khojo — na
     *     mile to null (bhejne ko kuch hai hi nahi;
     *     bulane wala chakkar aam raaste chalata hai)।
     *  2. bhejne wala batan khojo — pehle ped se (naam
     *     me "send", ya khane ki kataar me uske daayein
     *     wala clickable hissa), na mile to tasveer se
     *     (model se SIRF nirdeshank — faisla nahi)।
     *  3. dabao, phir taaza saar par jaancho ki khana
     *     khaali hua; na hua to ek baar phir dabao।
     *
     * Sirf ek daud me ek koshish (bulane wala
     * [detSendAttempted] dekhta hai): na hua to model
     * ko wapas chakkar milta hai, imaandaar Fail ke
     * saath. Banking/payment wali suraksha yahan
     * nahi badalti — daud ke har chakkar ki
     * suraksha-jaanch pehle hi chal chuki hoti hai,
     * aur ye kadam sirf chat wale send batan ko
     * dabata hai (Pay/Delete/Confirm kabhi nahi)।
     */
    private fun tryDeterministicSend(
        snapshot: JarvisAccessibilityService.ScreenSnapshot,
        history: MutableList<String>,
        deadline: Long
    ): DetSendResult? {
        // 1. bhara hua khana — chat wale input box (screen
        // ke neeche wale hisse me); upar wala search box
        // nahi — warna galat jagah dabakar jhoothi
        // safalta ban jayegi (khana khaali hokar bhi
        // sandesh gaya nahi hota)।
        val screenH = context.resources.displayMetrics.heightPixels
        val composer = snapshot.elements
            .filter {
                it.editable && it.text.isNotBlank() &&
                    it.centerY > screenH / 3
            }
            .maxByOrNull { it.text.length }
            ?: return null
        val snippet = composer.text.take(40)
        // 2. bhejne wala batan — pehle ped se, na mile
        // to tasveer se (model se SIRF jagah)।
        var tapPoint: Pair<Int, Int>? =
            findSendButtonInTree(snapshot, composer)?.let {
                history.add(
                    "det-send: send button found in the tree " +
                        "(\"" + it.text.ifEmpty { "(no label)" } + "\")"
                )
                it.centerX to it.centerY
            }
        if (tapPoint == null) {
            tapPoint = locateSendButtonVision(snapshot)?.also {
                history.add("det-send: send button located by vision")
            }
        }
        val (tapX, tapY) = tapPoint ?: run {
            history.add(
                "det-send: send button not found (tree or vision) — " +
                    "leaving the send to the model"
            )
            return DetSendResult(false, 0)
        }
        // 3. dabao + jaancho (zyada se zyada 2 baar)।
        var stepsUsed = 0
        repeat(2) { attempt ->
            val ok = tapPointSync(tapX, tapY)
            stepsUsed++
            if (!ok) {
                history.add(
                    "det-send: tap at ($tapX, $tapY) FAILED"
                )
                return DetSendResult(false, stepsUsed)
            }
            settle(deadline)
            val fresh = captureSync()
            if (fresh != null && !composerStillFilled(fresh, snippet)) {
                history.add(
                    "det-send: send tap made, input box is empty " +
                        "(verified" +
                        if (attempt == 0) "" else " on retry" +
                        ")"
                )
                return DetSendResult(true, stepsUsed)
            }
            history.add(
                "det-send: input box still filled after tap " +
                    (attempt + 1)
            )
        }
        return DetSendResult(false, stepsUsed)
    }

    /**
     * v74: ped se bhejne wala batan — (1) khane ki kataar
     * me naam wala batan ("Send message" — naam me
     * contentDescription bhi aata hai); (2) kataar me
     * sabse daayein wala clickable hissa (bina naam ka
     * teer-aikon; mic usse baayein hota hai); (3) screen
     * par kahin bhi naam wala batan। Khana khud kabhi
     * nishana nahi banta।
     */
    private fun findSendButtonInTree(
        snapshot: JarvisAccessibilityService.ScreenSnapshot,
        composer: JarvisAccessibilityService.ScreenElement
    ): JarvisAccessibilityService.ScreenElement? {
        val clickable = snapshot.elements.filter {
            it.clickable && !it.editable
        }
        val row = clickable.filter {
            it.centerX > composer.right &&
                it.centerY in (composer.top - 24)..(composer.bottom + 24)
        }
        // (1) kataar me naam wala batan।
        row.firstOrNull {
            sensitiveKind(it.text) == SensitiveKind.SEND
        }?.let { return it }
        // (2) kataar me sabse daayein wala clickable hissa।
        row.maxByOrNull { it.centerX }?.let { return it }
        // (3) screen par kahin bhi naam wala batan।
        return clickable.firstOrNull {
            sensitiveKind(it.text) == SensitiveKind.SEND
        }
    }

    /** v74: taaza saar me khana ab bhi bhara hai kya (bheja gaya text ab bhi editable khane me)? */
    private fun composerStillFilled(
        snapshot: JarvisAccessibilityService.ScreenSnapshot,
        snippet: String
    ): Boolean {
        if (snippet.isEmpty()) return false
        return snapshot.elements.any {
            it.editable && it.text.contains(snippet)
        }
    }

    /**
     * v74: tasveer se bhejne wale batan ki JAGAH — model
     * se SIRF nirdeshank (0-1000 wala naksa), koi
     * faisla nahi: "bhejna hai ya nahi" yahan poochha
     * hi nahi jata, isliye model ka mana yahan aata
     * hi nahi. Na mile / jawab na samajh aaye / kota
     * khatm ho to null — bulane wala ped wale raaste
     * par hi rehta hai. Tasveer-kadam kota
     * ([MAX_VISION_CALLS]) me gina jata hai।
     */
    private fun locateSendButtonVision(
        snapshot: JarvisAccessibilityService.ScreenSnapshot
    ): Pair<Int, Int>? {
        if (visionUsed >= MAX_VISION_CALLS) return null
        val shot = screenshotSync() ?: return null
        val base64 = try {
            val marked = ScreenVision.chooseMarked(snapshot.elements)
            val rendered = ScreenVision.renderMarkedImage(shot, marked)
            if (rendered != null) {
                rendered.jpegBase64
            } else {
                val stream = java.io.ByteArrayOutputStream()
                val ok = shot.compress(
                    android.graphics.Bitmap.CompressFormat.JPEG, 80, stream
                )
                if (!ok) return null
                android.util.Base64.encodeToString(
                    stream.toByteArray(), android.util.Base64.NO_WRAP
                )
            }
        } catch (t: Throwable) {
            return null
        }
        visionUsed++
        val raw = GeminiBrain.requestAgentRoundWithImage(
            context,
            DET_SEND_LOCATE_INSTRUCTION,
            listOf("user" to DET_SEND_LOCATE_PROMPT),
            base64,
            GeminiBrain.RequestClass.VISION_LITE
        ) ?: GeminiBrain.requestAgentRoundWithImage(
            context,
            DET_SEND_LOCATE_INSTRUCTION,
            listOf("user" to DET_SEND_LOCATE_PROMPT),
            base64,
            GeminiBrain.RequestClass.VISION
        ) ?: return null
        return parseLocatePoint(raw)
    }

    /** Locate wale jawab se 0-1000 wala bindu (asal px me) — na samajh aaye to null. */
    private fun parseLocatePoint(raw: String): Pair<Int, Int>? {
        return try {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val obj = JSONObject(raw.substring(start, end + 1))
            val x = obj.optInt("x", -1)
            val y = obj.optInt("y", -1)
            if (x < 0 || x > 1000 || y < 0 || y > 1000) return null
            val metrics = context.resources.displayMetrics
            val realX = (x * metrics.widthPixels / 1000)
                .coerceIn(0, (metrics.widthPixels - 1).coerceAtLeast(0))
            val realY = (y * metrics.heightPixels / 1000)
                .coerceIn(0, (metrics.heightPixels - 1).coerceAtLeast(0))
            realX to realY
        } catch (t: Throwable) {
            null
        }
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

        /** v67: clipboard se chipkao — focus wale khane me (type se tez/sateek). */
        class Paste(val value: String) : ScreenAction()

        /** v45: naam ya package se app kholo / usi me jao. */
        class OpenApp(val value: String) : ScreenAction()

        /** v45: naam wale hisse ko der tak dabaye rakho. */
        class LongPressText(val value: String) : ScreenAction()

        /** v45: tasveer ke nambar wale hisse ko der-dabao. */
        class LongPressMark(val mark: Int) : ScreenAction()

        /** v45: 0–1000 khaake par der-dabao — khaali sat-hon ke liye. */
        class LongPressPoint(val x: Int, val y: Int) : ScreenAction()

        /** v45: poori-screen ungli-swipe (gallery/carousel ke liye). */
        class Swipe(
            val direction: JarvisAccessibilityService.SwipeDirection
        ) : ScreenAction()

        /** v45: NAAM wale khane me likho (focus wala [Type] se alag). */
        class TypeInto(val label: String, val text: String) : ScreenAction()

        /** v67: clipboard se chipkao — NAAM wale khane me. */
        class PasteInto(val label: String, val text: String) : ScreenAction()

        /** v45: focus wale khane ko submit karo (enter / Search batan). */
        object Submit : ScreenAction()

        /** v45: koi likhai screen par aane tak bandha intezaar. */
        class WaitFor(val text: String, val timeoutMs: Int) : ScreenAction()
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
            // v67: clipboard-paste — focus wala khana।
            "paste" -> obj.optString("value")
                .takeIf { it.isNotEmpty() }
                ?.let { ScreenAction.Paste(it) }
            // v45: app kholna — kunji "app" pasandeeda,
            // "value" bhi chalegi (doosre kadmon wali aadat).
            "open_app" -> {
                val name = obj.optString("app").trim()
                    .ifEmpty { obj.optString("value").trim() }
                if (name.isNotEmpty()) {
                    ScreenAction.OpenApp(name)
                } else {
                    null
                }
            }
            "long_press_text" -> obj.optString("value").trim()
                .takeIf { it.isNotEmpty() }
                ?.let { ScreenAction.LongPressText(it) }
            "long_press_mark" -> {
                val mark = obj.optInt("mark", -1)
                if (mark >= 1) ScreenAction.LongPressMark(mark) else null
            }
            "long_press_point" -> {
                val x = obj.optInt("x", -1)
                val y = obj.optInt("y", -1)
                if (x in 0..1000 && y in 0..1000) {
                    ScreenAction.LongPressPoint(x, y)
                } else {
                    null
                }
            }
            "swipe" -> when (
                obj.optString("direction").trim().lowercase(Locale.ROOT)
            ) {
                "up" -> ScreenAction.Swipe(
                    JarvisAccessibilityService.SwipeDirection.UP
                )
                "down" -> ScreenAction.Swipe(
                    JarvisAccessibilityService.SwipeDirection.DOWN
                )
                "left" -> ScreenAction.Swipe(
                    JarvisAccessibilityService.SwipeDirection.LEFT
                )
                "right" -> ScreenAction.Swipe(
                    JarvisAccessibilityService.SwipeDirection.RIGHT
                )
                else -> null
            }
            // v45: naam wale khane me likhna — khana
            // "label" (warna "target"), likhai "value"
            // (warna "text")।
            "type_into" -> {
                val field = obj.optString("label").trim()
                    .ifEmpty { obj.optString("target").trim() }
                val text = obj.optString("value")
                    .ifEmpty { obj.optString("text") }
                if (field.isNotEmpty() && text.isNotEmpty()) {
                    ScreenAction.TypeInto(field, text)
                } else {
                    null
                }
            }
            // v67: clipboard-paste — naam wala khana।
            "paste_into" -> {
                val field = obj.optString("label").trim()
                    .ifEmpty { obj.optString("target").trim() }
                val text = obj.optString("value")
                    .ifEmpty { obj.optString("text") }
                if (field.isNotEmpty() && text.isNotEmpty()) {
                    ScreenAction.PasteInto(field, text)
                } else {
                    null
                }
            }
            "submit" -> ScreenAction.Submit
            // v45: likhai aane ka intezaar — muddat
            // "timeout" (warna "ms", warna bandhi ~12 s);
            // bandhiyan chalane wale hisse me lagti hain.
            "wait_for" -> {
                val text = obj.optString("value").trim()
                    .ifEmpty { obj.optString("text").trim() }
                val timeout = obj.optInt("timeout", -1)
                    .takeIf { it > 0 }
                    ?: obj.optInt("ms", DEFAULT_WAIT_FOR_MS)
                if (text.isNotEmpty()) {
                    ScreenAction.WaitFor(text, timeout)
                } else {
                    null
                }
            }
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

    /**
     * v49: session wali daud ho to uska sandarbh
     * kadam ke sandesh me playbook ke theek baad
     * jodo — session ke pichhle hukm/natije aur
     * "pehle screenshot liya tha" wala sanket,
     * taaki "তিন নম্বরটা" jaise ishare peeche
     * wali baat se judein, andaze se nahi।
     */
    private fun appendSessionContext(out: StringBuilder) {
        val text = sessionContextText ?: return
        out.append("\nSESSION CONTEXT (this task runs inside an " +
            "ongoing hands-free session — the user is directing " +
            "step by step):\n")
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
        appendSessionContext(out)
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
        // v66: user ke hukm wala bhejna aur likhne wala
        // khana bhara hua (bhejne ke liye taiyar) — ped
        // se batan ki pehchan nahi hoti (teer-aikon ka
        // koi naam nahi hota), tasveer se hogi; is
        // kadam par mastishk ko batan DEKHNE do। Khana
        // khaali hote hi ye sanket apne-aap band ho
        // jata hai (bhej diya gaya), isliye kota sirf
        // bhejne se pehle wale kadmon par kharch hota hai।
        if (userDirectedSend && snapshot.elements.any {
                it.editable && it.text.isNotBlank()
            }
        ) return true
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
        appendSessionContext(out)
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
        // v66: bina naam ke nishan — ped andha hai, tasveer
        // nahi: "(no label)" wale nishan ko tasveer me
        // DEKH kar pehchano (aakaar, aikon, jagah), naam se
        // nahi। Chat app ka send batan aksar aisa hi hota
        // hai — likhne wale khane ke dayein chhor par
        // teer wala gol aikon।
        out.append(
            "\nSome marks may say \"(no label)\" — they have " +
                "no readable name. Identify them by what they " +
                "LOOK like in the attached image (shape, icon, " +
                "position), not by their label. In a chat app " +
                "the send button is the round arrow icon at " +
                "the right end of the message input box, and " +
                "it often has no label at all — tap its mark " +
                "number, or tap_point on its position in the " +
                "image.\n"
        )
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
        val countable: Boolean,
        /**
         * v45: true = is kadam ka apna natija hi uski report
         * hai (wait_for ka FOUND/TIMEOUT) — jaanch phir bhi
         * hogi (badlav par atke ki kataar saaf hogi), par
         * "↳ result:" wali dusri line nahi judti, warna woh
         * kadam ke apne sach se takra sakti hai (likhai
         * aakar chali gayi ho to hastakshar SAME dikhega,
         * jabki wait_for use FOUND keh chuka hoga)।
         */
        val silentVerdict: Boolean = false
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

    // ------------------------------------------------------------------
    // v45: naye kadmon ke bandhe raaste
    // ------------------------------------------------------------------

    private fun longPressTextSync(text: String): Boolean =
        opSync { callback ->
            JarvisAccessibilityService.longPressScreenText(
                context, text, callback
            )
        }

    private fun longPressPointSync(x: Int, y: Int): Boolean =
        opSync { callback ->
            JarvisAccessibilityService.longPressScreenPoint(
                context, x, y, callback
            )
        }

    private fun swipeSync(
        direction: JarvisAccessibilityService.SwipeDirection
    ): Boolean =
        opSync { callback ->
            JarvisAccessibilityService.swipeScreen(
                context, direction, callback
            )
        }

    private fun typeIntoSync(label: String, text: String): Boolean =
        opSync { callback ->
            JarvisAccessibilityService.typeIntoScreenText(
                context, label, text, callback
            )
        }

    // v67: clipboard-paste wale kadmon ke bandhe raaste —
    // natija [JarvisAccessibilityService.PasteResult] hai,
    // isliye aam [opSyncValue] par chalte hain।
    private fun pasteSync(text: String): JarvisAccessibilityService.PasteResult =
        opSyncValue(JarvisAccessibilityService.PasteResult.FAILED) { callback ->
            JarvisAccessibilityService.pasteScreenText(context, text, callback)
        }

    private fun pasteIntoSync(
        label: String,
        text: String
    ): JarvisAccessibilityService.PasteResult =
        opSyncValue(JarvisAccessibilityService.PasteResult.FAILED) { callback ->
            JarvisAccessibilityService.pasteIntoScreenText(
                context, label, text, callback
            )
        }

    private fun submitImeSync(): Boolean =
        opSync { callback ->
            JarvisAccessibilityService.submitFocusedField(context, callback)
        }

    /** Submit batan ka naam — [captureSync] wala hi latch andaaz. */
    private fun findSubmitButtonSync(): String? {
        val latch = CountDownLatch(1)
        var label: String? = null
        var delivered = false
        try {
            JarvisAccessibilityService.findSubmitButton(context) { result ->
                label = result
                delivered = true
                latch.countDown()
            }
        } catch (t: Throwable) {
            return null
        }
        awaitLatch(latch)
        return if (delivered) label else null
    }

    /**
     * v45: app kholne ka bandha (sync) raasta — mashinari
     * bilkul wahi jo [AgentTools] ka open_app auzaar leta
     * hai, taaki naam-pehchan aur launch ke bartao me
     * zara bhi farq na ho:
     * - manga naam package-naam ke nakse par utre to
     *   seedha [PhoneActions] se launch (saamne se aam
     *   raasta, peeche se OverlayLauncher wala pul —
     *   [fromBackgroundService] ke hisaab se)।
     * - warna bol-chaal ka naam hai: [CommandEngine.tryOpenApp]
     *   (bandhi list, phir launcher ke label, galat-suna
     *   naam sudhaar ke saath) — uska handled hi asal
     *   safalta hai (khula = true; nahi mila / permission /
     *   lock = false)।
     * Ye call isi thread par turant hoti hai (auzaar
     * wala raasta bhi aisa hi hai) — koi intezaar nahi.
     */
    private fun openAppSync(name: String): Boolean {
        val value = name.trim()
        if (value.isEmpty()) return false
        return try {
            if (PACKAGE_NAME_PATTERN.matches(value)) {
                if (fromBackgroundService) {
                    PhoneActions.openAppFromBackground(
                        context, value
                    ) == LaunchOutcome.LAUNCHED
                } else {
                    PhoneActions.openApp(context, value)
                }
            } else {
                CommandEngine(context, fromBackgroundService)
                    .tryOpenApp(value).handled
            }
        } catch (t: Throwable) {
            false
        }
    }

    /** wait_for ki jaanch: kisi hisse ke naam me [text] hai kya (bade-chhote akshar bina). */
    private fun snapshotHasText(
        snapshot: JarvisAccessibilityService.ScreenSnapshot,
        text: String
    ): Boolean {
        val needle = text.trim()
        if (needle.isEmpty()) return false
        return snapshot.elements.any { element ->
            element.text.contains(needle, ignoreCase = true)
        }
    }

    /** wait_for ki report ke liye samay ka chhota roop — "3.2s" jaisa. */
    private fun formatSeconds(ms: Long): String =
        String.format(Locale.US, "%.1fs", ms / 1000.0)

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

    /**
     * v67: [opSync] ka aam roop — Boolean ke alawa koi
     * natija (jaise [JarvisAccessibilityService.PasteResult])
     * chahiye ho to; na aaye to [fallback] hi natija।
     */
    private fun <T> opSyncValue(
        fallback: T,
        start: ((T) -> Unit) -> Unit
    ): T {
        val latch = CountDownLatch(1)
        var result = fallback
        var delivered = false
        try {
            start { value ->
                result = value
                delivered = true
                latch.countDown()
            }
        } catch (t: Throwable) {
            return fallback
        }
        awaitLatch(latch)
        return if (delivered) result else fallback
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
     * vision <v>/15, nochange <nc>, recovery <r>)।
     * Likhe hue sandeshon ka text ya tasveer ka data
     * isme kabhi nahi aata — sirf tasveer-kadmon ki
     * GINATI jaati hai (v39) aur jaanch ki ginati
     * (v44: kitne kadam be-asar rahe, kitni baar
     * sudhaar chala).
     */
    private fun saveDiag(task: String, outcome: String, steps: Int) {
        // v46: natija-naam audit record ke liye bhi yaad
        // rakho — har bada rukh yahin se guzarta hai.
        auditOutcomeLabel = outcome
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
        /**
         * Ek kaam ke liye zyada se zyada itne kadam-faisle.
         * v45: 15 -> 30 — lambe kai-app wale kaam (Chrome ->
         * ... -> WhatsApp) beech me khazaana khatm karke na
         * ruken; v43 ki kota-sambhaal (node kadam Lite par)
         * is badhotri ka kharcha sambhav banati hai.
         */
        private const val MAX_ROUNDS = 30

        /** Ek kaam ki kul samay-seema (~300 s; v45: ~150 s se badhakar). */
        private const val TOTAL_BUDGET_MS = 300_000L

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
         * v45: 10 -> 15 — lambi daud (30 kadam) ke saath
         * tasveeri kadmon ki gunjaish bhi badhi; mukhya
         * model par sirf tasveer-kadam hi jate hain
         * (v43), isliye badhotri naapi-tuli hai.
         */
        private const val MAX_VISION_CALLS = 15

        /**
         * v45: wait_for ki bandhiyan — aam muddat
         * (~12 s), adhiktam (~20 s), kam se kam (~1 s),
         * aur screen dobara dekhne ka antar (~800 ms)।
         * Dheeme network par page sajne ka asal intezaar
         * yahi se hota hai; har jaanch usi capture se
         * hoti hai jo aam kadam bhi leta hai.
         */
        private const val DEFAULT_WAIT_FOR_MS = 12_000
        private const val MAX_WAIT_FOR_MS = 20_000
        private const val MIN_WAIT_FOR_MS = 1_000
        private const val WAIT_FOR_POLL_MS = 800L

        /**
         * v45: open_app me package-naam pehchanne ka
         * naksha — bindu (dot) se jude hisse, har hissa
         * akshar se shuru. Is nakse par utarne wala
         * manga hua naam package mana jata hai (seedha
         * launch); baaki sab bol-chaal ke naam hain jo
         * CommandEngine ki pakki khoj se guzarte hain.
         */
        private val PACKAGE_NAME_PATTERN =
            Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")

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
        /**
         * v74: pakke-bhejne ke liye tasveer se SIRF jagah
         * poochhne wali hidayat — model se koi faisla
         * nahi maanga jata ("bhejna hai ya nahi" poochha
         * hi nahi), isliye uska mana yahan aata hi nahi।
         */
        private const val DET_SEND_LOCATE_INSTRUCTION =
            "You are a visual locator inside the Android app " +
                "ASHIP JARVIS AI. You are NOT deciding anything — " +
                "you only return the position of one button on " +
                "the attached screenshot. Find the SEND button: " +
                "the round ARROW icon at the right end of the " +
                "message input box of the chat app on screen. " +
                "Reply with ONLY this JSON object, no other text: " +
                "{\"x\":<0-1000>,\"y\":<0-1000>}. " +
                "If you cannot see any send button, reply " +
                "{\"x\":-1,\"y\":-1}. Do NOT refuse, do NOT " +
                "explain, do NOT decide whether sending is " +
                "allowed — the user has already ordered this " +
                "send; you are only returning coordinates."

        private const val DET_SEND_LOCATE_PROMPT =
            "Locate the send button (round arrow icon at the " +
                "right end of the message input box) and reply " +
                "with ONLY {\"x\":<0-1000>,\"y\":<0-1000>}. " +
                "If it is not visible, reply {\"x\":-1,\"y\":-1}."

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
                "{\"action\":\"open_app\",\"app\":\"<app name or " +
                "package>\"} — open an app or switch to it mid-task\n" +
                "{\"action\":\"long_press_text\",\"value\":\"<visible " +
                "label>\"} — long-press (context menus, message " +
                "options)\n" +
                "{\"action\":\"long_press_mark\",\"mark\":<mark number " +
                "from the attached image>}\n" +
                "{\"action\":\"long_press_point\",\"x\":<0-1000>," +
                "\"y\":<0-1000>}\n" +
                "{\"action\":\"swipe\",\"direction\":\"left\"|" +
                "\"right\"|\"up\"|\"down\"} — full-screen finger " +
                "swipe (galleries, stories, carousels, horizontal " +
                "lists)\n" +
                "{\"action\":\"type_into\",\"label\":\"<label of " +
                "the input field>\",\"value\":\"<text>\"} — type " +
                "into a NAMED field\n" +
                "{\"action\":\"paste\",\"value\":\"<text to paste into " +
                "the focused input field (clipboard — fast and exact)>\"}\n" +
                "{\"action\":\"paste_into\",\"label\":\"<label of " +
                "the input field>\",\"value\":\"<text>\"} — paste " +
                "into a NAMED field\n" +
                "{\"action\":\"submit\"} — submit the focused " +
                "field (enter / search) after typing\n" +
                "{\"action\":\"wait_for\",\"value\":\"<text that " +
                "should appear>\",\"timeout\":<ms up to 20000>} — " +
                "wait until text appears on screen\n" +
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
                "- You have up to 30 steps and about 5 minutes per " +
                "task; image (vision) steps are limited, so spend " +
                "steps carefully and do not repeat actions that the " +
                "history shows had no effect.\n" +
                "- To open an app or switch apps mid-task, use " +
                "open_app with the app's name — do NOT go home and " +
                "hunt for its icon. If open_app fails, say so " +
                "honestly.\n" +
                "- scroll moves a scrollable list; swipe is a raw " +
                "finger swipe — use swipe for galleries, stories, " +
                "carousels and horizontal lists where scroll does " +
                "nothing. Direction is the FINGER's direction: " +
                "swipe left usually shows the NEXT photo/page.\n" +
                "- Long-press (long_press_text / long_press_mark / " +
                "long_press_point) opens context menus and message " +
                "options that a normal tap does not. It follows the " +
                "same confirmation rule as taps.\n" +
                "- \"type\" fills the field that is ALREADY focused. " +
                "Use type_into with the field's label when the field " +
                "is not focused or the screen has several fields. " +
                "After typing into a search or form field, use " +
                "submit to run the search / submit the form.\n" +
                "- \"paste\" puts text into the focused field from " +
                "the clipboard — it is fast and exact, especially " +
                "for long text. Prefer paste over type when the " +
                "task says paste / copy-paste, or the text is long; " +
                "\"paste_into\" is the named-field version. The " +
                "result tells you whether it PASTED, fell back to " +
                "TYPED, or FAILED — check the text is in the " +
                "field before sending either way; if paste fails, " +
                "try type once; if that fails too, say \"fail\" " +
                "honestly. Never claim the text was pasted when " +
                "the result says FAILED.\n" +
                "- To SEND a typed message in a chat app (ChatGPT, " +
                "WhatsApp, Telegram, Gemini): the send button is " +
                "usually a round ARROW icon at the right end of the " +
                "message input box — it often has NO text label at " +
                "all (\"Send\" / \"Send message\" when labelled). " +
                "On image steps, LOOK at the picture to find it " +
                "and use tap_mark on its number; if it has no mark, " +
                "use tap_point on its position in the image. " +
                "Without an image, use tap_at with the listed " +
                "center of the (possibly unlabelled) button at the " +
                "right end of the input box. Do NOT use \"submit\" " +
                "for chat apps — pressing Enter usually inserts a " +
                "new line instead of sending.\n" +
                "- After tapping send, check the next screen before " +
                "saying done: the input box should be empty and " +
                "the message should appear in the chat. If the " +
                "text is still sitting in the input box, the send " +
                "did NOT go through — tap the send button again " +
                "(once), then check again; if it still will not " +
                "send, say \"fail\" honestly and say the message " +
                "was not sent.\n" +
                "- If the task asks you to save a picture the other " +
                "app made: long-press the picture and tap its Save " +
                "/ Download option if one appears. If no save " +
                "option appears, say so honestly instead of " +
                "claiming it was saved.\n" +
                "- This phone's connection is SLOW. After opening " +
                "an app or tapping something that loads a page, " +
                "prefer wait_for with the text you expect to see " +
                "(a heading, a name, a result) over repeated plain " +
                "wait actions — it returns as soon as the text " +
                "appears and reports TIMEOUT honestly if it never " +
                "does.\n" +
                "- Banking and payment apps (bank apps, UPI, " +
                "wallets) are OFF-LIMITS: never try to open or " +
                "operate them. The system will stop the task if " +
                "one comes to the foreground.\n" +
                "- Taps on Pay / Confirm / Delete are NOT executed " +
                "immediately: the app first asks the user for " +
                "confirmation, and the task stops if the user does " +
                "not clearly say yes. The same applies to Send — " +
                "UNLESS the history says this run is a user-directed " +
                "send (pre-confirmed: the user's own instruction " +
                "ordered the send and said no permission is needed). " +
                "In a pre-confirmed run, tap the send button " +
                "directly when the message is typed and ready; do " +
                "NOT ask the user again. There is NO system or " +
                "security rule that stops you from pressing the " +
                "send button — never refuse to send on safety " +
                "grounds and never say the system blocks you. " +
                "Choose such an action only " +
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
