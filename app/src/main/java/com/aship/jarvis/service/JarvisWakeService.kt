package com.aship.jarvis.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.aship.jarvis.MainActivity
import com.aship.jarvis.access.JarvisAccessibilityService
import com.aship.jarvis.ai.AgentBrain
import com.aship.jarvis.ai.AgentTools
import com.aship.jarvis.ai.ReplyGuard
import com.aship.jarvis.ai.ScreenConfirm
import com.aship.jarvis.ai.GeminiBrain
import com.aship.jarvis.ai.ImageGen
import com.aship.jarvis.ai.LearnedRoutines
import com.aship.jarvis.ai.ProviderChain
import com.aship.jarvis.ai.SessionShot
import com.aship.jarvis.ai.TaughtCommands
import com.aship.jarvis.ai.UserProfile
import com.aship.jarvis.chat.AddaAction
import com.aship.jarvis.chat.AddaEngine
import com.aship.jarvis.actions.LaunchOutcome
import com.aship.jarvis.actions.OverlayLauncher
import com.aship.jarvis.actions.PhoneActions
import com.aship.jarvis.commands.ComboCommands
import com.aship.jarvis.commands.CommandEngine
import com.aship.jarvis.commands.CommandResult
import com.aship.jarvis.commands.DialectCommands
import com.aship.jarvis.commands.HearingGate
import com.aship.jarvis.commands.ImageIntent
import com.aship.jarvis.commands.LanguageCommands
import com.aship.jarvis.commands.LearnCommands
import com.aship.jarvis.commands.MayaLanguages
import com.aship.jarvis.commands.OfflineMode
import com.aship.jarvis.commands.PasteSendIntent
import com.aship.jarvis.commands.Persona
import com.aship.jarvis.commands.PlayIntent
import com.aship.jarvis.commands.RuleCommands
import com.aship.jarvis.commands.SendButtonCommands
import com.aship.jarvis.commands.SessionCommands
import com.aship.jarvis.commands.ShowMeIntent
import com.aship.jarvis.commands.TeachCommands
import com.aship.jarvis.commands.UrlIntent
import com.aship.jarvis.commands.WebSearchIntent
import com.aship.jarvis.commands.spokenLang
import com.aship.jarvis.prefs.AppPrefs
import com.aship.jarvis.prefs.JarvisMode
import com.aship.jarvis.speaker.TtsSpeaker
import com.aship.jarvis.voice.BanglaModelManager
import com.aship.jarvis.voice.BanglaZipformer
import com.aship.jarvis.voice.MicArbiter
import com.aship.jarvis.voice.ModelLanguage
import com.aship.jarvis.voice.ModelManager
import com.aship.jarvis.voice.SpeechEngineListener
import com.aship.jarvis.voice.SystemSpeechEngine
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Phase 2: background listening with a wake word.
 *
 * A microphone-type foreground service that runs the offline Vosk
 * recognizer continuously, scanning everything it hears for the wake
 * word ("Jarvis" in Devanagari / Bengali / Latin script). On a wake:
 *
 *   wake session STOPPED -> ack spoken ("जी बॉस, बोलिए") -> command
 *   session listens for ONE utterance -> CommandEngine runs it ->
 *   reply spoken -> wake session resumes.
 *
 * Only ONE SpeechService is ever alive at a time, and sessions are
 * fully stopped before the next one starts and before TTS speaks, so
 * Jarvis never hears its own voice. All session transitions are
 * serialized on a single-thread executor; SpeechService callbacks
 * only post work there.
 *
 * The service is started and stopped ONLY from the app UI (Android
 * does not allow background starts of microphone services), and it
 * stops itself gracefully when the Hindi model is missing.
 *
 * Phase 2 fixes: app launches cross the overlay bridge (see
 * CommandEngine's background mode), unrecognized background utterances
 * are answered with silence, and a command session is hard-capped
 * (~7 s total, ~2.5 s without a word change) so noise cannot hold it.
 *
 * v1.3 mic sharing: the phone has ONE microphone. While this service
 * holds it (Vosk AudioRecord), Android refuses the foreground system
 * recognizer ("cannot record now as ASHIP JARVIS AI is recording",
 * error 7). So MainActivity asks this service to PAUSE
 * (ACTION_PAUSE_WAKE) before tap-to-speak: the wake session stops
 * and releases the mic, but the service and its notification stay
 * alive. ACTION_RESUME_WAKE — or a 12 s safety timer, in case the
 * activity dies without resuming — restarts the wake session.
 * v1.6: the timer also force-recovers a wedged ACK/COMMAND flow,
 * but never cuts a reply that is still being spoken.
 *
 * v1.5 Siri orb: on a wake, a glossy floating orb (OrbView) appears
 * at the bottom-center of the screen above other apps — breathing
 * while it waits for the command, shimmering while the reply is
 * spoken, with the reply text in a bubble above it so the user can
 * also READ the answer. It fades out when the flow returns to wake
 * listening (1.5 s after the reply). Needs the same "Display over
 * other apps" permission as the launch bridge; without it the orb
 * is skipped silently and the voice flow is unchanged.
 *
 * v12 retry-once: command samajh na aaye (ya command session bina
 * handled command ke khatm ho jaye) to Jarvis ek baar "Phir se
 * boliye" bolkar turant ek aur command session kholta hai — dobara
 * "Jarvis" bolne ki zaroorat nahi. Dusri baar bhi na samjhe to
 * purana vyavhaar: kholne ki koshish par CANT_DO_REPLY, warna
 * chup, aur wapas wake sunne par.
 *
 * v13 offline adda: Maya naam ki persona ke saath halki, saaf-suthri
 * multi-turn baatchit. Adda chalu ho to har command-session wala
 * utterance pehle AddaEngine ko jata hai; jawab ke baad TTS khatm
 * hote hi agla command session bina wake word ke khul jata hai.
 * Adda ke andar v12 retry nahi lagta — Maya ke apne nudge/goodbye
 * wale raaste hain. Wake naam "Jarvis" ke saath "Maya" bhi hai.
 *
 * v16 screen + lock: wake hit milte hi (dono raaste — Jarvis aur
 * Maya) display ki roshni jal jati hai: ek SCREEN_BRIGHT wake lock
 * (~15 s timeout wala) liya jata hai, aur har naye session/jawab
 * par woh refresh ho jata hai, isliye poori baatchit ke dauraan
 * screen jala rehta hai aur baad me khud bujh jata hai. Aur phone
 * LOCK ho to background se koi app NAHI khulta — launch wale saare
 * raaste OverlayLauncher se guzarte hain, wahin lock ki jaanch
 * lagti hai, aur Maya "unlock kar lo" bol deti hai. Bina launch
 * wale kaam (torch, volume, gaana, battery, time) lock me bhi
 * pehle ki tarah chalte hain.
 *
 * v21 Phase 3 online brain: CommandEngine kisi baat ko na
 * sambhale, mode Offline na ho, Gemini key set ho aur net chalu
 * ho — tabhi Maya Gemini se jawab lati hai (GeminiBrain).
 * Offline command hamesha pehle jeet-ta hai; key na ho to ye
 * raasta band hi rehta hai aur purana vyavhaar nahi badalta.
 *
 * v25 sanyojit mastishk (combined brain): kram ab ulta hai —
 * online brain uplabdh ho (wahi v21 wala gate) to har baat
 * sabse pehle AGENT ko jati hai (AgentBrain): Gemini poori
 * baat samajhta hai, online kadam khud leta hai aur phone
 * wale kadam offline auzaaron se karwata hai, jab tak poora
 * kaam khatm na ho jaye. Agent ko round 1 par koi model hi
 * na mile to purana raasta (adda -> engine -> retry) bilkul
 * pehle jaisa chalega; gate band ho to agent ka naam bhi
 * nahi liya jata.
 *
 * v36 teen background sudhaar: (1) play-iraade wala hukm
 * (PlayIntent) agent ke saath-saath seedhe youtube_play
 * par bhi ja sakta hai — agent mare to gaana phir bhi
 * bajega; (2) spasht OFF wala device hukm (torch/data)
 * agent se pehle pakke offline raaste se chalta hai —
 * "বন্ধ" kabhi "চালু" nahi banta, aur device hukm ka
 * kachha suna hua text jarvis_diag me band hota hai;
 * (3) khud-ki-awaaz wala jaal — TTS ke dauraan aur uske
 * 1.5 s baad tak suna hua sab goonj hai, hukm nahi.
 *
 * v38 do sudhaar: (1) pakka scroll hukm (CommandEngine
 * .deterministicScrollCommand) OFF wale raaste ke theek
 * baad, agent se pehle chalta hai — "স্ক্রল ডাউন করো"
 * bolte hi swipe. (2) BACKGROUND BANGLA SUNNA: Vosk
 * Hindi Bangla sun hi nahi sakta tha — post-wake hukm
 * khaali/garble aata tha aur do-retry wala jaap band
 * kar deta tha. Ab Vosk sirf "Jarvis" wala wake-spotting
 * rakhta hai; wake milte hi hukm SYSTEM recognizer
 * (SystemSpeechEngine, main thread par bandha) sunta
 * hai — wahi bhasha kataar jo saamne wali screen ki hai
 * (pasand -> bn-IN -> hi-IN). System recognizer uplabdh
 * na ho ya turant fail ho jaye to usi turn ke liye
 * purana Vosk wala raasta. System raaste par miss ka
 * niyam naya hai: ek chhota prompt ("বলুন, শুনছি") +
 * ek aur khidki, phir imaandaar vida — do-retry wala
 * jaap is raaste par nahi hai.
 *
 * v41 Bangla offline ASR: Bangla Zipformer model
 * (sherpa-onnx, int8) user ne app se download kiya ho
 * to post-wake hukm sabse pehle USI se suna jata hai —
 * poori tarah offline, system recognizer ki bhi
 * zaroorat nahi. Model taiyaar na ho to v38 wala kram
 * bilkul pehle jaisa (system -> Vosk); Zipformer khaali
 * aaye ya bigde to turn wahi kram par gir jata hai,
 * jaise woh tha hi nahi. Wake-word spotting Vosk Hindi
 * par hi rehta hai — Zipformer keyword-spotter nahi hai.
 *
 * v48 DO SHAKSIYAT + CHUP MAUT KA ILAAJ. (1) Shaksiyat
 * ab WAKE NAAM se bandhi hai: "Jarvis"/"Maya" -> MAYA
 * (sirf Hindi: kaan hi-IN system recognizer + Vosk,
 * Zipformer band, boli hamesha Hindi, agent ko Hindi
 * wala bhasha-bandhan); "তারা"/"Tara" -> TARA (sirf
 * Bangla: Zipformer -> bn-IN system -> Vosk, boli
 * hamesha Bangla). Dusri bhasha ki script wali baat
 * par kaam NAHI hota — ek vinamra mod (redirect)
 * bolkar session vinamrata se band hota hai. Tara ki
 * wake-pehchan sthan-bandhi hai (baat ki shuruaat ya
 * akela naam), kyunki তারা aam Bangla shabd hai.
 * (2) Session ka ant ab kabhi CHUP nahi hota: jo
 * raasta bina poora jawab diye band ho, woh shaksiyat
 * ki apni bhasha me ek vida-line bolkar band hota hai
 * (adda ke khaali-session wale do chup raaste yahi
 * sudhre hain); wake sunna har ant par phir chalu
 * hota hai; aur har ant ki wajah "last_session_end"
 * report me bandhti hai ("last_persona" ke saath).
 * (3) Link wala kaam (URL + kholne ka iraada) yahan
 * bhi pakke taur par screen_task ko jata hai
 * ([UrlIntent]) — baatchit wala mastishk use baat
 * banakar nahi taal sakta.
 *
 * v49 TAANA (CONTINUOUS) SCREEN SESSION + SEEKHE
 * KAAM. (1) Background session me SCREEN-KAAM
 * (pakka screenshot hukm, share_screenshot, ya
 * koi screen_task — seedha/URL wala/agent wala)
 * chale to baatchit wahin khatm NAHI hoti: natija
 * bolkar Maya/Tara poochti hai "আর কিছু করতে
 * হবে?" / "और कुछ करना है?" aur bina wake naam ke
 * agla hukm sunti hai — kadam-kadam wala poora
 * kram (screenshot -> WhatsApp -> teesra sampark
 * -> "সেন্ড করে দাও") ek hi session me chalta
 * hai. Session ka sandarbh (pichhle hukm/natije,
 * pehle liya gaya screenshot) har agle hukm ke
 * saath mastishk tak jata hai. Samajh na aaye to
 * woh POOCHHTI hai par session kabhi nahi marti —
 * na galti se, na kota se, na asafal kaam se.
 * Ant sirf teen raaston se: "সিস্টেম শাটডাউন"
 * (gola gayab, wake sunna chaloo — poori band
 * NAHI), prompt par saaf "না", ya 15 minute ki
 * poori khamoshi (chetaavni bolkar band). Apne
 * hukm me saaf bhejne ka aadesh ho to us kaam ka
 * Send tap pehle-se-pusht hai ([SessionCommands]
 * + ScreenAgent dekho)। (2) Session ke bahar bhi
 * seekhne wale pakke hukm yahin sambhalte hain
 * ([LearnCommands]): "মনে রেখো" aakhiri SAFAL
 * screen-kaam ko bandhta hai — asafal kabhi nahi
 * seekha jata; "শিখে নাও: …" bolkar sikhayi
 * chaal bandhti hai. Adda session ke andar jaan-
 * boojh kar NAHI chalta — session kaam ki haalat
 * hai, adda uska apna alag ek-baar wala raasta
 * rakhta hai (session ke bahar bilkul pehle jaisa)।
 */
class JarvisWakeService : Service() {

    private enum class State { STOPPED, LOADING, WAKE, ACK, COMMAND, REPLY }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var notificationManager: NotificationManager
    private lateinit var speaker: TtsSpeaker
    private lateinit var commandEngine: CommandEngine
    private lateinit var addaEngine: AddaEngine
    private lateinit var prefs: AppPrefs
    private lateinit var orbOverlay: OrbOverlay

    /**
     * v37: screen-agent ke sawalon ki background mezbaani
     * ([ScreenConfirm.Host]) — sawal aane par use bolo aur
     * gole par dikhao. Jawab wake wale raaste se aata hai:
     * user "Jarvis" bolkar jawab bolta hai aur
     * onCommandHeard use pul ko saunp deta hai (naya
     * command nahi banta). Sawal service ke apne executor
     * par chalaya jata hai — bolna/status badalna wahi se
     * hota hai jaise baaki jawabon ka.
     */
    private val screenConfirmHost = ScreenConfirm.Host { question ->
        try {
            executor.execute {
                if (destroyed) return@execute
                setStatus("Screen wala kaam sawal pooch raha hai")
                updateNotification(question)
                orbOverlay.setReply(question)
                speaker.speak(question)
            }
        } catch (t: Throwable) {
            // Service band ho rahi hai: sawal aage nahi
            // badhega — agent intezaar ke baad imaandaari
            // se ruk jayega.
        }
    }

    private var model: Model? = null
    private var wakeSpeechService: SpeechService? = null
    private var commandSpeechService: SpeechService? = null

    // v16: screen jalane wala wake lock. Lazily banta hai, hamesha
    // timeout ke saath acquire hota hai (khud bujh jata hai), aur
    // setReferenceCounted(false) ki wajah se dobara acquire karna
    // sirf samay badhata hai — ginati ka jhanjhat nahi.
    private var screenWakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var state: State = State.STOPPED

    @Volatile
    private var destroyed = false

    // v75: FULL SHUTDOWN — "শাটডাউন / ফুল শাটডাউন"
    // sunte hi "কমপ্লিট" bolkar poori service +
    // process band। [fullShutdownPending] "কমপ্লিট"
    // bolne ke dauraan sach hai; [fullShutdownKilling]
    // marne wala kadam ek hi baar chale iska jaal hai
    // (boli-khatm wala callback aur safety timer dono
    // use bula sakte hain); [fullShutdownStartedMs]
    // safety timer ki kul intezaar-seema ke liye।
    @Volatile
    private var fullShutdownPending = false

    @Volatile
    private var fullShutdownKilling = false

    @Volatile
    private var fullShutdownStartedMs = 0L

    private var wakeErrorCount = 0

    // v1.3 mic sharing: true while the foreground screen has borrowed
    // the microphone. The wake session is stopped (mic released) but
    // the service + notification stay alive; no error counters move.
    @Volatile
    private var wakePaused = false

    // Command-session caps (Bug-2 fix): track when the session started
    // and when the heard words last CHANGED, so steady noise partials
    // cannot keep a session alive forever.
    @Volatile
    private var commandSessionStartMs = 0L

    @Volatile
    private var commandLastChangeMs = 0L

    @Volatile
    private var commandLastPartial = ""

    // v1.7 diagnostics: the longest wake partial since the last final
    // result. Kept separately so a blank final result cannot erase the
    // best evidence of what the recognizer was hearing.
    @Volatile
    private var longestWakePartialSinceFinal = ""

    // v12: is wake par command ka ek retry mil chuka hai ya nahi.
    // Har naye wake (onWakeDetected) par reset hota hai.
    @Volatile
    // v33: retry ginati — v12 ke ek-mauke ki jagah ab
    // MAX_COMMAND_RETRIES mauke, phir vida wali line.
    private var commandRetryCount = 0

    // v33: ACK wali boli khatm hone par command session ki
    // jagah wapas wake par jana hai (vida wali line chal
    // rahi ho tab).
    private var ackEndsConversation = false

    // v13: adda ke dauraan bina awaaz wala ek session maaf hai;
    // dusra lagatar khaali session adda ko chup-chaap band kar deta
    // hai. Koi asli utterance aate hi ye ginati reset ho jati hai.
    @Volatile
    private var addaNoSpeechCount = 0

    // v36: khud-ki-awaaz wala jaal (self-hearing guard) —
    // Maya ki boli kab khatm hui (uptime millis). TTS bol
    // raha ho ya khatm hue SELF_ECHO_GUARD_MS ke andar ho,
    // to suna hua kuch bhi hukm nahi banta: uski apni boli
    // ("টর্চ চালু করে দিয়েছি") ke shabd dobara kaam
    // chalu kar dete the. onTtsFinished asal samay likhta
    // hai; armReplyFallback andaz wala samay pehle se likh
    // deta hai, taaki finish-callback kho jaye to bhi
    // khidki band rahe.
    @Volatile
    private var lastSpeechEndMs = 0L

    // ------------------------------------------------------------------
    // v38: post-wake hukm ke liye SYSTEM recognizer wala
    // raasta. Vosk Hindi Bangla sun hi nahi sakta — isliye
    // wake ke baad wala hukm ab phone ke system recognizer
    // se suna jayega (wahi jo saamne wali screen par Bangla
    // theek sunta hai). SystemSpeechEngine sirf MAIN thread
    // par banta/chalta hai — service use applicationContext
    // ke saath wahi banati hai, shuru/rok mainHandler se
    // hoti hai, aur uske callbacks (main thread) asal kaam
    // executor ko saunp dete hain. System uplabdh na ho /
    // turant fail ho jaye to usi turn ke liye Vosk wala
    // purana raasta (startVoskCommandSession).
    // ------------------------------------------------------------------

    /** Is pal ke command session ka kaan kaun sa hai. */
    private enum class CommandKind { NONE, VOSK, SYSTEM, ZIPFORMER }

    /** System recognizer engine — sirf main thread par chhoota hai. */
    private var systemEngine: SystemSpeechEngine? = null

    @Volatile
    private var commandKind: CommandKind = CommandKind.NONE

    /** System session ki bhasha kataar (v48: shaksiyat se bandhi — Maya: sirf hi-IN, Tara: sirf bn-IN). */
    @Volatile
    private var systemCascadeLanguages: List<String> = emptyList()

    @Volatile
    private var systemCascadeIndex: Int = 0

    /** System raaste par ek prompt + ek khidki ka mauka mil chuka hai kya. */
    @Volatile
    private var systemRetryUsed: Boolean = false

    /** Is pal jo hukm chal raha hai, woh system recognizer se aaya tha kya. */
    @Volatile
    private var currentTurnViaSystem: Boolean = false

    /**
     * System recognizer is phone par uplabdh hi nahi (ya
     * turant fail hua) — is wake-chain me dobara koshish
     * mat karo. Har naye wake par reset hota hai.
     */
    @Volatile
    private var systemUnavailableThisChain: Boolean = false

    /** Maya ki boli ki goonj system session me aayi ho to bina retry kharch kiye kitni baar phir suna. */
    @Volatile
    private var systemEchoRestarts: Int = 0

    // ------------------------------------------------------------------
    // v51: sunne ki marammat wale khet.
    //
    // [systemOfflineEngine]: net-fail par ek baar
    // aazmaya jaane wala preferOffline=true wala
    // system engine (item 4) — aam [systemEngine] se
    // alag isliye, taaki online wala apni haalat me
    // rahe aur agla turn phir online se hi shuru ho।
    // [activeSystemEngine]: is pal jo engine chal
    // raha hai (aam ya offline) — listener error ki
    // pehchan isi se padhta hai।
    // [systemOfflineAttemptUsed]: is chain me offline
    // wala mauka kharch ho chuka hai kya (har turn
    // par ek hi baar)।
    // [systemOfflineActive]: is pal chal raha prayas
    // offline wala hai kya (report + safalta ke
    // nishan ke liye)।
    // [systemGateRestarts] / [zipformerGateRestarts]:
    // gate se ruka natija bina boli-retry kharch
    // kiye kitni baar phir suna gaya (echo wala hi
    // hisaab, seema MAX_GATE_RESTARTS)।
    // [sessionEmptyReopenCount]: taana session ki
    // lagatar khaali khidkiyan — peeche-hatne
    // (backoff) wale antar ke liye (item 6b)।
    // [micYielded] / [micYieldedMidSession]: mic
    // saamne wali screen ko udhaar diya gaya hai,
    // aur dete samay koi taana session beech me
    // ruka tha kya (item 6a)।
    // ------------------------------------------------------------------
    private var systemOfflineEngine: SystemSpeechEngine? = null

    @Volatile
    private var activeSystemEngine: SystemSpeechEngine? = null

    @Volatile
    private var systemOfflineAttemptUsed: Boolean = false

    @Volatile
    private var systemOfflineActive: Boolean = false

    @Volatile
    private var systemGateRestarts: Int = 0

    @Volatile
    private var zipformerGateRestarts: Int = 0

    @Volatile
    private var sessionEmptyReopenCount: Int = 0

    @Volatile
    private var micYielded: Boolean = false

    @Volatile
    private var micYieldedMidSession: Boolean = false

    // ------------------------------------------------------------------
    // v41: Bangla offline ASR (Zipformer) wala raasta.
    // Model [BanglaModelManager] se sirf taiyaari jaanchi
    // jati hai; asal sunna/decode [BanglaZipformer] apne
    // thread par karta hai (recording ~15 s tak ki ho
    // sakti hai — service ka mukhya executor us dauraan
    // band nahi reh sakta). Har session ka ek number
    // (seq) hota hai: purane thread ka der se aaya natija
    // naye session ko kabhi nahi chhedega.
    // ------------------------------------------------------------------

    private var banglaModelManager: BanglaModelManager? = null
    private var banglaAsr: BanglaZipformer? = null

    @Volatile
    private var zipformerSessionSeq: Int = 0

    /** Goonj wala natija aaye to bina retry kharch kiye kitni baar phir suna (system wala hi hisaab). */
    @Volatile
    private var zipformerEchoRestarts: Int = 0

    /**
     * Zipformer is wake-chain me bigad chuka hai (recognizer
     * na bana / decode fail) — is chain me use phir mat
     * aazmao; agle wake par taaza mauka milega. System wale
     * [systemUnavailableThisChain] ki hi tarz.
     */
    @Volatile
    private var zipformerBrokenThisChain: Boolean = false

    // ------------------------------------------------------------------
    // v48: shaksiyat + session ke ant ki report.
    //
    // [sessionPersona] har wake par naam se bandhta hai
    // (onWakeDetected) aur poori chain me wahi rehta hai —
    // kaan ki bhasha kataar, boli ki bhasha, agent ka
    // bhasha-bandhan, mod (redirect) wali jaanch, sab
    // faisle isi ek khet se hote hain.
    //
    // [pendingEndReason]: jo aakhiri jawab bola gaya,
    // uske saath session kis wajah se band mana jayega —
    // jawab bolne wale raaste ise bolne se pehle bandh
    // dete hain, aur boli khatm hone par (onTtsFinished /
    // reply fallback) wahi wajah "last_session_end" me
    // likhi jati hai. Vida-line (closing) wale ant ke
    // liye alag [closingReason] hai, kyunki woh ACK
    // raaste se band hota hai.
    // ------------------------------------------------------------------
    @Volatile
    private var sessionPersona: Persona = Persona.MAYA

    @Volatile
    private var pendingEndReason: String = SESSION_END_COMPLETED

    @Volatile
    private var closingReason: String = SESSION_END_NOT_UNDERSTOOD

    // ------------------------------------------------------------------
    // v49: TAANA (continuous) SCREEN SESSION ki haalat.
    //
    // [sessionActive]: is chain me koi screen-kaam
    // chal chuka hai aur ab hum "jaage rehne" wali
    // haalat me hain — har hukm ke jawab ke baad
    // "আর কিছু করতে হবে?" wala prompt aayega aur
    // bina wake naam ke agli khidki khulegi। Ant
    // sirf shutdown / prompt-par-na / 15-minute
    // khamoshi se hoga; har ant [clearContinuousSession]
    // se guzarta hai (endSession wale bandhe nishan
    // se — koi ant bina safaai ke nahi)।
    //
    // [sessionAwaitingMore]: is pal khuli sunne wali
    // khidki "আর কিছু?" wale prompt ke turant baad
    // wali hai kya — sirf USI khidki ki baat par
    // saaf "না" session band karta hai; beech kaam
    // me "না" ek aam hukm hi rehta hai।
    //
    // [sessionContextLines]: is session ke hukm +
    // boli hui saar-linein (aakhiri 10) — mastishk
    // (AgentBrain/ScreenAgent) ko sandarbh bankar
    // jati hain, taaki "তিন নম্বরটা" / "ওই
    // স্ক্রিনশটটা" jaise ishare peeche jud sakein।
    //
    // [sessionMissCount]: lagatar kitni baar suna
    // par kuch juda nahi — pehli baar par sawal
    // wala prompt, dusri par imaandaar line aur
    // wapas "আর কিছু?" par (session phir bhi
    // zinda)।
    //
    // [lastBrainTools]: aakhiri agent-daud ki
    // auzaar-tukdi — uske [AgentTools.screenWorkUsed]
    // nishan se pata chalta hai ki mastishk ne
    // screen-kaam chalaya tha (session yahin se
    // banta hai, jawab aane par)।
    // Saare khet sirf executor thread par chhoote
    // hain (sessionActive @Volatile sirf isliye hai
    // ki idle wala timer bhi use padh sake)।
    // ------------------------------------------------------------------
    @Volatile
    private var sessionActive: Boolean = false

    private var sessionAwaitingMore: Boolean = false
    private var sessionShotTaken: Boolean = false
    private val sessionContextLines = mutableListOf<String>()
    private var sessionMissCount: Int = 0

    @Volatile
    private var lastBrainTools: AgentTools? = null

    /**
     * v97: DIALECT sudhaar ("না, আমি বলেছি 'X'")
     * ke liye pichli baar suni hui aakhri baat —
     * [rememberCommandFinal] me har naye hukm se
     * pehle purani baat yahan utaar li jati hai।
     * Sirf pukaar-naam (মায়া/তারা) ho to sudhaar
     * wala haath use nahi manta (DialectCommands)।
     */
    private var previousCommandFinalText: String? = null

    // v21 Phase 3: Gemini baatchit ki yaad — is wake chain ke
    // andar wale aakhiri 10 user/model turn (jodi = 2 entry).
    // Sirf executor thread par chhoota hai. Har naye wake par
    // saaf ho jati hai (onWakeDetected), taaki har chain taaza
    // shuru ho; lambi padaav par khud kat-ti rehti hai.
    private val geminiHistory = mutableListOf<Pair<String, String>>()

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        speaker = TtsSpeaker(this)
        speaker.onUtteranceFinished = { onTtsFinished() }
        // v93: TTS voice na mile (nayi bhasha ka voice
        // phone par nahi) to fallback imaandaari se diag
        // me likho — background se toast nahi banta, aur
        // bolna kabhi nahi rukta (graceful fallback)।
        speaker.onLanguageFallback = { requested, applied ->
            try {
                applicationContext.getSharedPreferences(
                    "jarvis_diag", Context.MODE_PRIVATE
                ).edit()
                    .putString("last_tts_fallback", "$requested -> $applied")
                    .apply()
            } catch (t: Throwable) {
                // Diag bonus hai — bolna isse nahi rukta।
            }
        }
        // Background flag: app launches cross the overlay bridge and
        // report a missing overlay permission honestly (Bug-1 fix).
        commandEngine = CommandEngine(applicationContext, fromBackgroundService = true)
        addaEngine = AddaEngine()
        prefs = AppPrefs(this)
        // v41: Bangla offline ASR ke do hisse — model ki
        // taiyaari jaanchne wala aur asal sunne/decode
        // wala. Model download na ho to dono soye rehte
        // hain; koi laagat nahi.
        banglaModelManager = BanglaModelManager(applicationContext)
        banglaAsr = BanglaZipformer(applicationContext)
        orbOverlay = OrbOverlay()
        // v37: screen-agent ke sawalon ki mezbaani is
        // service ke naam bandho (teardown me hat ti hai).
        ScreenConfirm.registerServiceHost(screenConfirmHost)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_WAKE -> {
                // Service chal hi nahi raha (state STOPPED) to kuch
                // mat karo. stopSelf() yahan jaan-boojh kar NAHI hai:
                // purana prefs-jhanda galat ho to RESUME ek sahi
                // START se pehle line me lag sakta hai, aur stop
                // us start ko kharab kar dega. Khaali instance na
                // mic pakadta hai, na notice dikhata hai — agla
                // START/STOP use sambhal lega.
                if (state != State.STOPPED) {
                    executor.execute { pauseWakeListening() }
                }
                return START_NOT_STICKY
            }
            ACTION_RESUME_WAKE -> {
                if (state != State.STOPPED) {
                    executor.execute { resumeWakeListening() }
                }
                return START_NOT_STICKY
            }
            ACTION_YIELD_MIC -> {
                // v51 (item 6a): saamne wali screen mic
                // lene ja rahi hai — chal raha COMMAND
                // session bhi poori tarah roko (sirf wake
                // sunna nahi), warna dono recognizer
                // takrakar bip chakra banayenge।
                if (state != State.STOPPED) {
                    executor.execute { yieldMicToForeground() }
                }
                return START_NOT_STICKY
            }
            ACTION_OVERLAY_HIDE -> {
                // v89: MainActivity saamne aayi — chhota overlay
                // gola hatao। Service band ho to kuch nahi
                // (overlay hai hi nahi)।
                if (state != State.STOPPED && ::orbOverlay.isInitialized) {
                    orbOverlay.hideNow()
                }
                return START_NOT_STICKY
            }
            ACTION_OVERLAY_SHOW -> {
                // v89: MainActivity peeche gayi — chhota overlay
                // gola mini app ki tarah dikhao (sirf service
                // chal rahi ho to)।
                // v91: SIRF tab jab sakriya session chal raha ho
                // (wake word ke baad — ACK/COMMAND/REPLY)।
                // Bas background me chalu hona (WAKE — wake word
                // ka intezaar) gola dikhane ki wajah nahi; aise
                // me laga hua ho to hata bhi do।
                if (state != State.STOPPED && ::orbOverlay.isInitialized) {
                    if (isActivelyListening()) orbOverlay.show() else orbOverlay.hideNow()
                }
                return START_NOT_STICKY
            }
        }
        if (state != State.STOPPED) {
            // Already running: a second START changes nothing —
            // v91: START_STICKY hi rahe (neeche dekho), taaki
            // system isko maar de to dobara khada kare (24h)।
            return START_STICKY
        }
        isRunning = true
        prefs.wakeServiceRunning = true
        state = State.LOADING
        setStatus("Model load ho raha hai...")
        startForegroundWith(buildNotification("Model load ho raha hai..."))
        executor.execute { loadModelAndStartWake() }
        // v91: 24h background — START_STICKY: Android service
        // ko maar de (memory pressure) to null intent se
        // dobara khada karega; onCreate/onStartCommand phir
        // model load karke wake sunna shuru kar denge। Pehle
        // START_NOT_STICKY tha — marne par wapas nahi aati
        // thi। ACTION_STOP wala raasta ab bhi NOT_STICKY hai
        // (band ki hui service dobara khadi nahi honi chahiye),
        // aur PAUSE/RESUME/YIELD/OVERLAY wale raaste bhi
        // NOT_STICKY hi hain (woh chalti service ko hukm hain;
        // unse null-intent restart hokar band ki hui service
        // dobara khul jaye, woh galat hoga)।
        return START_STICKY
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Idempotent full cleanup: recognizers, TTS, model, executor. */
    private fun teardown() {
        if (destroyed) return
        destroyed = true
        // v92: lagatar scroll (chal raha ho to) yahin
        // roko — service band hone ke baad screen peeche
        // se scroll hoti na rahe।
        CommandEngine.stopContinuousScroll()
        // v37: screen-agent wali mezbaani sabse pehle hatao.
        ScreenConfirm.unregisterServiceHost(screenConfirmHost)
        state = State.STOPPED
        isRunning = false
        wakePaused = false
        // v49: taana session bhi saath saaf — idle
        // wali ghadi aur bandhi tasveer sabse pehle
        // (neeche wala removeCallbacksAndMessages
        // uske posted kaam ko bhi uda dega)।
        disarmSessionIdle()
        clearContinuousSession()
        prefs.wakeServiceRunning = false
        setStatus("Band hai")
        // Gola sabse pehle hatao — neeche wala removeCallbacksAndMessages
        // uske posted kaam ko bhi uda dega.
        if (::orbOverlay.isInitialized) {
            orbOverlay.removeImmediately()
        }
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        stopWakeSession()
        stopCommandSession()
        // v41: Zipformer ki chal rahi recording roko aur
        // recognizer ka native hissa chhodo.
        stopZipformerCommandSession()
        try {
            banglaAsr?.release()
        } catch (t: Throwable) {
            // Band karte huey gadbad — teardown nahi rukega.
        }
        banglaAsr = null
        // v38: system recognizer bhi band. onDestroy main
        // thread par chalta hai — engine wahi banta/chalta
        // hai, isliye yahan seedha destroy theek hai.
        try {
            systemEngine?.destroy()
        } catch (t: Throwable) {
            // Band karte huey gadbad — teardown nahi rukega.
        }
        systemEngine = null
        // v51: offline wala system engine bhi saath band.
        try {
            systemOfflineEngine?.destroy()
        } catch (t: Throwable) {
            // Band karte huey gadbad — teardown nahi rukega.
        }
        systemOfflineEngine = null
        activeSystemEngine = null
        // v16: screen wala wake lock pada ho to chhod do — waise
        // bhi uska timeout khud use bujha deta, ye bas safaai hai.
        try {
            screenWakeLock?.let { lock ->
                if (lock.isHeld) lock.release()
            }
        } catch (t: Throwable) {
            // Release fail ho jaye to bhi teardown nahi rukega.
        }
        screenWakeLock = null
        speaker.shutdown()
        try {
            model?.close()
        } catch (t: Throwable) {
            // Native side already gone: nothing to do.
        }
        model = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ------------------------------------------------------------------
    // Model loading (executor thread)
    // ------------------------------------------------------------------

    private fun loadModelAndStartWake() {
        if (destroyed) return
        val modelManager = ModelManager(applicationContext)
        if (!modelManager.isModelReady(ModelLanguage.HINDI)) {
            setStatus(
                "Hindi offline model download nahi hai — background sunna band ho gaya. " +
                    "Pehle app kholkar model download kijiye."
            )
            updateNotification("Model nahi hai — app kholkar pehle download kijiye")
            // v85: model bina boot par bhi har baar chalu hokar
            // turant band hone ka chakra na bane — jhanda lagao
            // taaki boot wapas na laye; user model download karke
            // power dabayega to powerOn jhanda saaf kar dega।
            prefs.wakeStoppedByShutdown = true
            mainHandler.post { stopSelf() }
            return
        }
        try {
            model = Model(modelManager.modelDir(ModelLanguage.HINDI).absolutePath)
        } catch (t: Throwable) {
            setStatus("Model load fail ho gaya: ${t.message}")
            mainHandler.post { stopSelf() }
            return
        }
        startWakeSession()
    }

    // ------------------------------------------------------------------
    // Wake session (executor thread)
    // ------------------------------------------------------------------

    private fun startWakeSession() {
        if (destroyed) return
        val loadedModel = model ?: return
        stopCommandSession()
        stopWakeSession()
        state = State.WAKE
        if (wakePaused) {
            // Mic foreground screen ko diya hua hai: state WAKE hi
            // rahega, par koi session mic nahi pakdega. Resume (ya
            // 12 s wala safety timer) aane par yahi function dobara
            // chalega aur session sach me shuru hoga.
            setStatus("Background sunna roka hai — mic app ke paas hai")
            return
        }
        if (MicArbiter.isForegroundOwned(applicationContext)) {
            // v51 (item 6a): arbiter bhi wahi kehta hai —
            // mic is pal saamne wali screen ka hai; wake
            // sunna mic nahi pakdega. (Aam taur par
            // wakePaused upar hi rok deta hai — ye dusra
            // jaal hai.)
            setStatus("Background sunna roka hai — mic app ke paas hai")
            return
        }
        try {
            val recognizer = Recognizer(loadedModel, SAMPLE_RATE)
            val service = SpeechService(recognizer, SAMPLE_RATE)
            wakeSpeechService = service
            service.startListening(wakeListener)
            // NOTE: wakeErrorCount is NOT reset here — Vosk errors
            // arrive async, after an apparently fine start. The
            // counter only resets on a real wake (proof the mic
            // pipeline works), so a broken mic gives up eventually
            // instead of restarting forever.
            setStatus("Sun raha hoon — 'Jarvis' boliye")
            updateNotification("Jarvis sun raha hai — 'Jarvis' boliye")
        } catch (t: Throwable) {
            handleWakeError()
        }
    }

    private fun stopWakeSession() {
        wakeSpeechService?.let { service ->
            try {
                service.stop()
            } catch (t: Throwable) {
                // Already stopped: nothing to do.
            }
            try {
                service.shutdown()
            } catch (t: Throwable) {
                // Ignore while tearing down.
            }
        }
        wakeSpeechService = null
    }

    // ------------------------------------------------------------------
    // Mic sharing (v1.3, executor thread)
    //
    // Foreground tap-to-speak (Google system recognizer) aur ye
    // service ek hi mic ke do daavedar hain — ek saath chal hi nahi
    // sakte. Isliye activity sunna shuru karne se pehle PAUSE bhejti
    // hai: wake session band (mic khali), service + notification
    // chalu. Sunna khatm hone par RESUME aata hai; na aaye to 12 s
    // ka safety timer khud resume kar deta hai — aur beech ka
    // command flow kahin phas gaya ho to use tod kar bhi (bolta
    // hua jawab kabhi nahi katata). Pause na to error counter
    // badhata hai, na service band karta hai.
    // ------------------------------------------------------------------

    private fun pauseWakeListening() {
        if (destroyed) return
        if (wakePaused) {
            // Pehle se roka hai: sirf safety timer aage badhao.
            armAutoResume()
            return
        }
        wakePaused = true
        if (state == State.WAKE) {
            stopWakeSession()
            setStatus("Background sunna roka hai — mic app ke paas hai")
        }
        // ACK/COMMAND/REPLY chal raha ho to flow ko haath mat lagao:
        // uske ant me startWakeSession() wakePaused dekh kar khud
        // ruka rahega.
        armAutoResume()
    }

    private fun resumeWakeListening() {
        if (destroyed || !wakePaused) return
        wakePaused = false
        disarmAutoResume()
        // v51 (item 6a): mic udhaar se wapas aaya. Beech
        // me ruka taana session ho to wake sunne ki
        // jagah usi ki khidki phir kholo — session
        // udhaar se nahi marta (v49 wala niyam)।
        micYielded = false
        if (micYieldedMidSession && sessionActive) {
            micYieldedMidSession = false
            state = State.ACK
            reopenSessionWindow()
            return
        }
        micYieldedMidSession = false
        // Sirf WAKE state me session dobara chalao. LOADING ho to
        // model load ke baad wala startWakeSession() khud chalega;
        // ACK/COMMAND/REPLY ho to flow ke ant me chalega — dono ab
        // wakePaused ki rukawat nahi dekhenge.
        if (state == State.WAKE) {
            startWakeSession()
        } else if (state == State.ACK) {
            // v51: ACK wali boli ke baad khulne wali
            // khidki arbiter par atki ho sakti hai —
            // mic ab khaali hai, khidki yahin kholo।
            startCommandSession()
        }
    }

    /**
     * v51 (item 6a): mic saamne wali screen ko udhaar
     * do — sirf wake sunna nahi, chal raha COMMAND
     * session (system / Vosk / Zipformer, jo bhi ho)
     * bhi poori tarah roko, warna dono recognizer ek
     * hi mic par takrakar recognizer-busy wale bip
     * chakra banate hain. Taana session ho to woh
     * MARTA nahi — sirf rukta hai; mic wapas milte hi
     * (resumeWakeListening) uski khidki phir khulti
     * hai. Boli (ACK/REPLY) chal rahi ho to use haath
     * mat lagao — arbiter wala jaal aage ke
     * startCommandSession ko rokega।
     * Sirf executor thread se bulao।
     */
    private fun yieldMicToForeground() {
        if (destroyed) return
        micYielded = true
        // wakePaused wala purana bandobast (auto-resume
        // ghadi, startWakeSession ki rukawat) udhaar ke
        // liye bhi wahi kaam karega।
        wakePaused = true
        armAutoResume()
        when (state) {
            State.COMMAND -> {
                micYieldedMidSession = sessionActive
                stopSystemCommandSession()
                stopCommandSession()
                stopZipformerCommandSession()
                commandKind = CommandKind.NONE
                state = State.WAKE
                setStatus("Mic saamne wali screen ko diya — sunna ruka hai")
            }
            State.WAKE -> {
                stopWakeSession()
                setStatus("Mic saamne wali screen ko diya — sunna ruka hai")
            }
            else -> {
                // ACK / REPLY / LOADING: flow ko haath
                // mat lagao (v1.3 wala hi niyam)।
            }
        }
    }

    private val autoResumeRunnable = Runnable {
        executor.execute {
            if (destroyed || !wakePaused) return@execute
            when (state) {
                State.REPLY -> {
                    // Jawab abhi bola ja raha hai — use beech me
                    // kaatna nahi hai. Thodi der baad phir dekho;
                    // jawab khatm hote hi flow WAKE me jayega aur
                    // resume wahin se ho jayega.
                    armAutoResume(REPLY_RESUME_RETRY_MS)
                }
                State.ACK, State.COMMAND -> {
                    // 12 s beet gaye aur flow ab bhi yahin atka hai
                    // to woh healthy nahi hai: ack fallback 3.5 s ka
                    // hai aur command session ka hard cap 7 s ka —
                    // dono beet chuke hain, matlab flow kahin phas
                    // gaya hai (khoya hua callback). Atki hui haalat
                    // tod kar wake sunna saaf phir se shuru karo.
                    disarmAckFallback()
                    stopCommandSession()
                    // v38: phasa hua flow system session bhi
                    // ho sakta hai — use bhi saath roko.
                    stopSystemCommandSession()
                    commandKind = CommandKind.NONE
                    // v13: phas gaya flow tod rahe hain — adda ka
                    // state bhi saath saaf karo, warna agle wake ke
                    // baad bhi purani baatchit chipki rahegi.
                    addaEngine.endSession()
                    addaNoSpeechCount = 0
                    // v48: ye sudhaar bolta NAHI hai — is pal
                    // mic saamne wali screen ke paas udhaar
                    // hai (wakePaused), wahan boli ghusana
                    // galat hoga; par ant ki wajah report me
                    // zaroor bandhegi (koi ant bina hisaab ke
                    // nahi).
                    endSession(SESSION_END_WEDGED)
                    orbOverlay.hideNow()
                    wakePaused = false
                    disarmAutoResume()
                    startWakeSession()
                }
                else -> resumeWakeListening()
            }
        }
    }

    private fun armAutoResume(delayMs: Long = WAKE_AUTO_RESUME_MS) {
        mainHandler.removeCallbacks(autoResumeRunnable)
        mainHandler.postDelayed(autoResumeRunnable, delayMs)
    }

    private fun disarmAutoResume() {
        mainHandler.removeCallbacks(autoResumeRunnable)
    }

    private fun handleWakeError() {
        if (destroyed || state != State.WAKE) return
        stopWakeSession()
        wakeErrorCount++
        if (wakeErrorCount > MAX_WAKE_ERRORS) {
            setStatus("Mic me baar-baar dikkat aa rahi hai — background sunna band ho gaya.")
            updateNotification("Mic error — background sunna band ho gaya")
            mainHandler.post { stopSelf() }
            return
        }
        // v85: flap dheela karo — har galti ke baad der badhti
        // jaye (4 s, 8 s, phir 12 s), taaki chalu/band ki
        // jhankaar na lage; 5 galti ke baad pehle jaisa band।
        val restartDelayMs =
            WAKE_RESTART_DELAY_MS * minOf(wakeErrorCount.coerceAtLeast(1), 3)
        mainHandler.postDelayed({
            executor.execute {
                if (!destroyed && state == State.WAKE) {
                    startWakeSession()
                }
            }
        }, restartDelayMs)
    }

    private val wakeListener = object : RecognitionListener {
        // wakePaused bhi dekho: pause ke liye stop() karne par Vosk
        // ek aakhiri final result bhej sakta hai — usse "wake" nahi
        // manna hai, aur ruke hue samay ki error error-counter me
        // nahi judni chahiye.
        override fun onPartialResult(hypothesis: String?) {
            if (state != State.WAKE || wakePaused) return
            // v36: Maya khud bol rahi ho (ya abhi-abhi chup
            // hui ho) to ye uski apni goonj hai — wake mat mano.
            if (selfEchoGuardActive()) return
            val partial = parseJsonField(hypothesis, "partial")
            rememberWakePartial(partial)
            // v48: wake naam se shaksiyat bhi tay hoti
            // hai — Jarvis/Maya -> MAYA, Tara -> TARA.
            detectWakePersona(partial)?.let { persona ->
                executor.execute { onWakeDetected(persona) }
            }
        }

        override fun onResult(hypothesis: String?) {
            if (state != State.WAKE || wakePaused) return
            if (selfEchoGuardActive()) return
            val text = parseJsonField(hypothesis, "text")
            rememberWakeFinal(text)
            detectWakePersona(text)?.let { persona ->
                executor.execute { onWakeDetected(persona) }
            }
        }

        override fun onFinalResult(hypothesis: String?) {
            if (state != State.WAKE || wakePaused) return
            if (selfEchoGuardActive()) return
            val text = parseJsonField(hypothesis, "text")
            rememberWakeFinal(text)
            detectWakePersona(text)?.let { persona ->
                executor.execute { onWakeDetected(persona) }
            }
        }

        override fun onError(exception: Exception?) {
            if (state == State.WAKE && !wakePaused) {
                executor.execute { handleWakeError() }
            }
        }

        override fun onTimeout() {
            // No timeout is set on the wake session: nothing to do.
        }
    }

    // ------------------------------------------------------------------
    // Screen wake (v16)
    //
    // Wake hit milte hi display jal jaye — screen band ho ya lock
    // screen dikh rahi ho. Ek SCREEN_BRIGHT wake lock
    // (ACQUIRE_CAUSES_WAKEUP = band screen ko bhi jalaye,
    // ON_AFTER_RELEASE = chhodne ke baad pal bhar aur roshni)
    // timeout ke saath liya jata hai; har session/jawab par
    // dobara bulane se samay aage badh jata hai. Ye lock screen
    // ko JALATA hai, lock KHOLTA nahi — unlock user hi karega.
    // Poora kaam try/catch me hai: wake lock kabhi fail ho jaye
    // to bhi wake flow nahi rukna chahiye.
    // ------------------------------------------------------------------

    private fun lightScreenOnWake() {
        try {
            val powerManager =
                getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lock = screenWakeLock ?: powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "jarvis:wake"
            ).also { created ->
                created.setReferenceCounted(false)
                screenWakeLock = created
            }
            lock.acquire(WAKE_SCREEN_TIMEOUT_MS)
        } catch (t: Throwable) {
            // Screen na jale to bhi awaaz wala flow pehle jaisa
            // chalta rahega — yahan rukna kabhi nahi hai.
        }
    }

    // ------------------------------------------------------------------
    // Wake -> ack -> command (executor thread)
    // ------------------------------------------------------------------

    private fun onWakeDetected(persona: Persona) {
        if (destroyed || state != State.WAKE || wakePaused) return
        // v48: is chain ki shaksiyat wake NAAM se bandhi
        // — "Jarvis"/"Maya" -> MAYA (Hindi), "Tara" ->
        // TARA (Bangla) — aur report me bhi bandh gayi.
        sessionPersona = persona
        savePersonaDiag()
        pendingEndReason = SESSION_END_COMPLETED
        closingReason = SESSION_END_NOT_UNDERSTOOD
        // v16: wake hit milte hi sabse pehle screen ki roshni —
        // screen band ho to bhi user ko dikhe ki Maya jaag gayi.
        lightScreenOnWake()
        state = State.ACK
        stopWakeSession()
        // A real wake proves listening works: clear the error counter.
        wakeErrorCount = 0
        // v12: har naye wake ke saath command ko ek taaza retry mile.
        commandRetryCount = 0
        ackEndsConversation = false
        // v38: nayi wake-chain — system recognizer ko taaza
        // mauka (is phone par uplabdh hai ya nahi, phir
        // aazmayenge) aur naya ek-prompt wala hisaab.
        systemRetryUsed = false
        currentTurnViaSystem = false
        systemUnavailableThisChain = false
        // v51: net-fail wala offline mauka aur gate
        // wala hisaab bhi har nayi chain me taaza —
        // pichhli chain ki nakami agli ko nahi maregi।
        systemOfflineAttemptUsed = false
        systemGateRestarts = 0
        zipformerGateRestarts = 0
        micYieldedMidSession = false
        // v41: Zipformer ko bhi har nayi chain me taaza
        // mauka — pichhli chain me bigda ho to bhi agle
        // wake par phir aazmayenge, goonj wala hisaab
        // bhi shuru se.
        zipformerBrokenThisChain = false
        zipformerEchoRestarts = 0
        // v13: naya wake hamesha taaza interaction hai — koi atka
        // hua purana adda state saath nahi ghisdega.
        addaEngine.endSession()
        addaNoSpeechCount = 0
        // v21: pichhli chain ki Gemini baatchit bhi saath saaf —
        // naya wake matlab nayi baatchit.
        geminiHistory.clear()
        // v49: naya wake = nayi kataar — koi atka hua
        // taana session saath nahi ghisdega (session
        // apni chain ke andar hi jeeta/marta hai;
        // uske ant report me pehle bandh chuke hote
        // hain, isliye yahan sirf safaai hai)।
        clearContinuousSession()
        prefs.wakeHitCount = prefs.wakeHitCount + 1
        setStatus("'Jarvis' suna gaya — command boliye")
        updateNotification("Command boliye...")
        // v1.5: Siri gola ubhar aaye — wake ki haan wale pal se
        // lekar poore command flow ke ant tak dikhega.
        orbOverlay.show()
        // Mic is fully released before Jarvis speaks, so it cannot
        // hear its own acknowledgement.
        // v48+v50: wake ki haan bhi shaksiyat ki apni bandhi line me.
        speaker.speak(personaAckText())
        armAckFallback()
    }

    // ------------------------------------------------------------------
    // v48: shaksiyat wale bandhe jawab + ant ki report
    // ------------------------------------------------------------------

    /** Shaksiyat ki boli-bhasha (jawab hamesha isi me). */
    private fun personaSpokenLang(): PlayIntent.SpokenLang =
        sessionPersona.spokenLang()

    /** Wake ke baad ki haan — Maya ki Hindi, Tara ki Bangla. */
    private fun personaAckText(): String = when (sessionPersona) {
        Persona.MAYA -> ACK_TEXT
        Persona.TARA -> TARA_ACK_TEXT
    }

    /** Session ke ant ki vida line — shaksiyat ki apni bhasha me. */
    private fun personaCloseText(): String = when (sessionPersona) {
        Persona.MAYA -> MAYA_CLOSE_TEXT
        Persona.TARA -> TARA_CLOSE_TEXT
    }

    /** "last_persona" report — kaun si shaksiyat ki chain chal rahi hai. */
    private fun savePersonaDiag() {
        try {
            getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                .edit()
                .putString(
                    "last_persona",
                    when (sessionPersona) {
                        Persona.MAYA -> "maya-hindi"
                        Persona.TARA -> "tara-bengali"
                    }
                )
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai — asal flow isse nahi rukta.
        }
    }

    /**
     * v48: session ke ant ki wajah "last_session_end"
     * me band karo (completed / not-understood /
     * redirect-bn / redirect-hi / recognizer-error /
     * quota / screen-confirm / wedged-recovered /
     * adda-silent). Har woh raasta jo session ko
     * wake sunne par wapas bhejta hai, yahan se
     * guzarta hai — koi ant bina report ke nahi.
     */
    private fun endSession(reason: String) {
        try {
            getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                .edit()
                .putString("last_session_end", reason)
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai — asal flow isse nahi rukta.
        }
        // v49: ye function har session-ant ka bandha
        // darwaza hai (v48 wala niyam) — taana session
        // chal raha ho to uski safaai bhi yahin se
        // hogi, taaki koi bhi ant (shutdown / done /
        // idle / phasne par sudhaar / aam vida) aadha
        // session peeche na chhode।
        if (sessionActive) {
            clearContinuousSession()
        }
    }

    // ------------------------------------------------------------------
    // v49: TAANA SESSION ka bandobast — pravesh, prompt,
    // na-samajh wala sawal, ant, aur khamoshi-ghadi।
    // Saare function sirf executor thread se bulaye
    // jate hain (timer wale runnable apna kaam
    // executor ko saunp dete hain)।
    // ------------------------------------------------------------------

    /**
     * Taana session shuru karo — pehla screen-kaam
     * chalte hi bulaya jata hai। Pehle se chal raha
     * ho to kuch nahi hota (sandarbh/tasveer bane
     * rehte hain)। Gemini wali baatchit-yaad
     * (geminiHistory) yahan NAHI saaf hoti — woh
     * isi chain ka sandarbh hai aur session use
     * aage badhata hai।
     */
    private fun enterContinuousSession() {
        if (sessionActive) return
        sessionActive = true
        sessionAwaitingMore = false
        sessionShotTaken = false
        sessionContextLines.clear()
        sessionMissCount = 0
        // v51 (item 6b): khaali-khidki wala peeche-
        // hatne ka hisaab bhi taaza।
        sessionEmptyReopenCount = 0
        SessionShot.clear()
    }

    /** Session ki poori safaai — har ant yahin se guzarta hai। */
    private fun clearContinuousSession() {
        sessionActive = false
        sessionAwaitingMore = false
        sessionShotTaken = false
        sessionContextLines.clear()
        sessionMissCount = 0
        sessionEmptyReopenCount = 0
        micYieldedMidSession = false
        SessionShot.clear()
        disarmSessionIdle()
        mainHandler.removeCallbacks(sessionReopenRunnable)
    }

    /**
     * Ek session-hukm ka jawab bola gaya — ab
     * session ka bandha prompt bolo ("আর কিছু
     * করতে হবে?" / "और कुछ करना है?") aur ACK
     * wale raaste se agli sunne wali khidki kholo
     * (boli khatm hone par onAckSpeechDone seedha
     * command session kholega — wake naam ki
     * zaroorat NAHI)। Gola dikhta rehta hai।
     */
    private fun promptSessionMore() {
        if (destroyed) return
        sessionAwaitingMore = true
        state = State.ACK
        ackEndsConversation = false
        setStatus("Session chal raha hai — 'আর কিছু?' pooch rahi hoon")
        updateNotification(
            "Session chal raha hai — boliye, band karne ke liye " +
                "'সিস্টেম শাটডাউন' boliye"
        )
        lightScreenOnWake()
        val prompt = sessionMoreText()
        speaker.speak(prompt)
        armAckFallback()
        orbOverlay.setReply(prompt)
        armSessionIdle()
    }

    /**
     * Session ke andar suna-par-na-juda hukm:
     * pehli baar EK sawal wala prompt ("কোনটা
     * বলতে চাইছো?") bolkar phir suno; dusri
     * lagatar baar imaandaar line bolkar wapas
     * "আর কিছু?" par jao। Dono haal me session
     * ZINDA rehta hai — na-samajh kabhi maut nahi
     * (user ka pakka hukm)।
     */
    private fun handleSessionNotUnderstood() {
        if (destroyed) return
        sessionMissCount++
        if (sessionMissCount <= 1) {
            state = State.ACK
            ackEndsConversation = false
            sessionAwaitingMore = false
            setStatus("Samajh nahi aaya — sawal pooch rahi hoon")
            updateNotification("Phir se boliye...")
            lightScreenOnWake()
            speaker.speak(sessionClarifyText())
            armAckFallback()
        } else {
            sessionMissCount = 0
            val line = sessionNotUnderstoodText()
            setStatus("Samajh nahi aaya — session chal raha hai")
            updateNotification("Jarvis jawab de raha hai...")
            lightScreenOnWake()
            speaker.speak(line)
            armReplyFallback(line)
            orbOverlay.setReply(line)
        }
    }

    /**
     * v50: SHUTDOWN — BINA BOLE band। User ka
     * pakka hukm: "শাটডাউন" sunte hi gola turant
     * gayab aur koi vida-line NAHI boli jayegi।
     * Isliye ye raasta boli wale saare bandobast
     * (ACK fallback, reply fallback, chalti hui
     * boli) ko pehle rokta hai, ant ki wajah
     * "shutdown-silent" bandhta hai, gola abhi
     * hatata hai aur wake sunna phir chalu karta
     * hai — service zinda rehti hai। Adda chalu
     * ho to woh bhi yahin saaf hota hai।
     * Sirf executor thread se bulao (onCommandHeard
     * wala raasta — state wahan REPLY ho chuka
     * hota hai aur command session band)। 
     */
    private fun shutdownSilently() {
        disarmSessionIdle()
        disarmAckFallback()
        disarmReplyFallback()
        try {
            speaker.stop()
        } catch (t: Throwable) {
            // Boli rokna bonus hai — band hona asal hai.
        }
        addaEngine.endSession()
        addaNoSpeechCount = 0
        sessionAwaitingMore = false
        // endSession taana session ki safaai bhi
        // yahin se karta hai (uska bandha niyam)।
        endSession(SESSION_END_SHUTDOWN_SILENT)
        pendingEndReason = SESSION_END_COMPLETED
        closingReason = SESSION_END_NOT_UNDERSTOOD
        ackEndsConversation = false
        setStatus("Shutdown — gola band, wake sunna chal raha hai")
        updateNotification("Jarvis sun raha hai — 'Jarvis' boliye")
        orbOverlay.hideNow()
        startWakeSession()
    }

    /**
     * v75: FULL SHUTDOWN — "কমপ্লিট" bolkar poori
     * service + process band। Kram: pehle saare
     * fallback/timer roko (koi cheez beech me wake
     * sunna phir chalu na kare), report me wajah
     * bandho, "কমপ্লিট" bolo, aur boli khatm hote
     * hi [fullShutdownKill] chalao ([onTtsFinished]
     * wala bandobast)। Callback kho jaye to safety
     * timer ([fullShutdownKillRunnable]) khud maar
     * dega — shutdown kabhi atakta nahi। Sirf
     * executor thread se bulao (onCommandHeard wala
     * raasta — state wahan REPLY ho chuka hota hai
     * aur command session band)।
     */
    private fun startFullShutdown() {
        disarmSessionIdle()
        disarmAckFallback()
        disarmReplyFallback()
        disarmAutoResume()
        fullShutdownPending = true
        fullShutdownStartedMs = SystemClock.uptimeMillis()
        // Report: ant ki wajah pehle bandho — boli ya
        // timer, jo bhi maare, wajah yahi rahegi।
        endSession(SESSION_END_FULL_SHUTDOWN)
        pendingEndReason = SESSION_END_COMPLETED
        closingReason = SESSION_END_NOT_UNDERSTOOD
        ackEndsConversation = false
        setStatus("কমপ্লিট — পুরো বন্ধ হচ্ছে...")
        updateNotification("কমপ্লিট — পুরো বন্ধ হচ্ছে...")
        orbOverlay.setReply(FULL_SHUTDOWN_SPEAK_TEXT)
        speaker.speak(FULL_SHUTDOWN_SPEAK_TEXT)
        mainHandler.removeCallbacks(fullShutdownKillRunnable)
        mainHandler.postDelayed(
            fullShutdownKillRunnable,
            FULL_SHUTDOWN_KILL_TIMEOUT_MS
        )
    }

    /**
     * v75: safety timer — "কমপ্লিট" ki boli khatm
     * hone ka callback na aaye to khud maar do। Boli
     * abhi chal rahi ho aur kul intezaar 15 s se kam
     * ho to 3 s aage badho, warna maar do। Main
     * thread par chalta hai; [fullShutdownKill] kisi
     * bhi thread se surakshit hai।
     */
    private val fullShutdownKillRunnable: Runnable = Runnable {
        if (fullShutdownKilling || !fullShutdownPending) return@Runnable
        val elapsed = SystemClock.uptimeMillis() - fullShutdownStartedMs
        if (speaker.isSpeakingNow && elapsed < FULL_SHUTDOWN_MAX_WAIT_MS) {
            mainHandler.postDelayed(
                fullShutdownKillRunnable,
                FULL_SHUTDOWN_KILL_REARM_MS
            )
            return@Runnable
        }
        fullShutdownKill()
    }

    /**
     * v75: ASAL MAAR — ek hi baar chalega
     * ([fullShutdownKilling] wala jaal)। Kram:
     * timer roko, gola turant hatao, prefs me
     * turant (sync) likho ki wake service band
     * hai, [teardown] se sab kuch saaf karo
     * (wake/command/Zipformer session, engine,
     * wake lock, TTS, foreground notice), service
     * roko, aur aakhir me khud ka process maar
     * do — taaki koi bhi executor/timer/activity
     * peeche zinda na bache। Accessibility wala
     * setting chhoota NAHI hai: wohi process me
     * chalne wali service process marte hi band
     * ho jati hai, aur setting ON rehti hai —
     * dobara app kholte hi system use phir bandh
     * deta hai, kuch dobara chalu karna nahi
     * padta। Reminder wale alarm bhi nahi chhede
     * gaye — woh ek-shot system alarm hain, koi
     * chalti hui sunna nahi, battery nahi khate।
     */
    private fun fullShutdownKill() {
        if (fullShutdownKilling) return
        fullShutdownKilling = true
        fullShutdownPending = false
        mainHandler.removeCallbacks(fullShutdownKillRunnable)
        try {
            orbOverlay.removeImmediately()
        } catch (t: Throwable) {
            // Gola hatana bonus hai — marna asal hai.
        }
        // Prefs turant (sync) likho — apply() wala
        // aam setter killProcess se pehle disk tak
        // nahi pahunchta।
        try {
            prefs.markWakeServiceStoppedSync()
            prefs.markWakeStoppedByShutdownSync()
        } catch (t: Throwable) {
            // Flag bonus hai — marna asal hai.
        }
        teardown()
        try {
            stopSelf()
        } catch (t: Throwable) {
            // Service rokna bonus hai — marna asal hai.
        }
        Process.killProcess(Process.myPid())
    }

    /**
     * Vida-line bolkar session band karo — ACK
     * wale band raaste se: boli khatm hote hi
     * onAckSpeechDone ant ki wajah ([reason])
     * bandhega, gola HATAYEGA aur wake sunna phir
     * chalu karega। Khamoshi (idle) wala ant
     * yahi raasta leta hai (gola gayab, service
     * zinda — poori band NAHI)। Shutdown is
     * raaste par ab NAHI aata — woh v50 se
     * bilkul chup hai ([shutdownSilently])।
     */
    private fun endSessionWithClosingLine(reason: String, line: String) {
        disarmSessionIdle()
        closingReason = reason
        state = State.ACK
        ackEndsConversation = true
        setStatus("Session band ho raha hai")
        updateNotification("Jarvis sun raha hai — 'Jarvis' boliye")
        lightScreenOnWake()
        speaker.speak(line)
        armAckFallback()
        orbOverlay.setReply(line)
    }

    /**
     * "আর কিছু?" par saaf "না" — session ka
     * vinamra aam ant: chhoti vida line REPLY
     * wale aam raaste se boli jati hai (gola
     * thaharkar gayab, wake sunna chaloo — bilkul
     * v48 ke aam ant jaisa, sirf wajah
     * "session-done" bandhti hai)। Session ka
     * jhanda bolne se PEHLE hataya jata hai taaki
     * boli-khatm wala bandobast aam ant hi dekhe;
     * baaki safaai yahin pakki ho jati hai।
     */
    private fun endSessionDone() {
        val line = sessionDoneText()
        clearContinuousSession()
        pendingEndReason = SESSION_END_DONE
        setStatus("Session khatm — jawab de raha hoon")
        updateNotification("Jarvis jawab de raha hai...")
        lightScreenOnWake()
        speaker.speak(line)
        armReplyFallback(line)
        orbOverlay.setReply(line)
    }

    /**
     * Mastishk (AgentBrain) aur screen-kaam
     * (ScreenAgent) ke liye is session ka compact
     * sandarbh — session na chal raha ho to null
     * (bulane wale ka bartav bilkul pehle jaisa)।
     */
    private fun sessionContextText(): String? {
        if (!sessionActive) return null
        val out = StringBuilder()
        out.append(
            "You are inside a CONTINUOUS hands-free screen " +
                "session: the user is giving step-by-step spoken " +
                "commands, and after each command the app asks " +
                "\"anything else?\" and listens again. Resolve " +
                "references like \"the third one\", \"that page\" " +
                "or \"send it\" against this session and the " +
                "current screen."
        )
        if (sessionContextLines.isNotEmpty()) {
            out.append("\nSession so far:")
            sessionContextLines.forEachIndexed { index, line ->
                out.append("\n").append(index + 1)
                    .append(") ").append(line)
            }
        }
        if (sessionShotTaken) {
            out.append(
                "\nA screenshot was taken earlier in this " +
                    "session and is stored. If the user refers " +
                    "to THAT screenshot, call share_screenshot " +
                    "— the app reuses the stored capture " +
                    "instead of taking a new one."
            )
        }
        return out.toString()
    }

    /** Ek poora hua session len-den sandarbh me bandho (aakhiri [SESSION_CONTEXT_MAX_LINES])। */
    private fun noteSessionOutcome(userText: String, outcomeLine: String) {
        if (!sessionActive) return
        val entry = userText.trim().take(SESSION_CONTEXT_CMD_CHARS) +
            " → " + outcomeLine.trim().take(SESSION_CONTEXT_OUTCOME_CHARS)
        sessionContextLines.add(entry)
        while (sessionContextLines.size > SESSION_CONTEXT_MAX_LINES) {
            sessionContextLines.removeAt(0)
        }
    }

    // ------------------------------------------------------------------
    // v49: khamoshi-ghadi (idle valve) + khidki phir kholna
    // ------------------------------------------------------------------

    /**
     * 15 minute ki poori khamoshi: session sunne
     * wali haalat (COMMAND/ACK) me ho to chetaavni
     * line bolkar session band (gola gayab, wajah
     * "session-idle")। Kaam chal raha ho (REPLY)
     * to ghadi sirf aage badhti hai — lamba
     * screen-kaam beech me kabhi nahi katega।
     */
    private val sessionIdleRunnable = Runnable {
        executor.execute {
            if (destroyed || !sessionActive) return@execute
            when (state) {
                State.COMMAND, State.ACK -> {
                    endSessionWithClosingLine(
                        SESSION_END_IDLE, sessionIdleText()
                    )
                }
                State.REPLY -> armSessionIdle()
                else -> Unit
            }
        }
    }

    private fun armSessionIdle() {
        mainHandler.removeCallbacks(sessionIdleRunnable)
        mainHandler.postDelayed(sessionIdleRunnable, SESSION_IDLE_MS)
    }

    private fun disarmSessionIdle() {
        mainHandler.removeCallbacks(sessionIdleRunnable)
    }

    /**
     * Khaali/error wali khidki ke baad session ki
     * khidki phir kholne wala bandha intezaar —
     * chhota sa antar isliye, taaki recognizer ke
     * turant-fail par band-bina-soch wala chakkar
     * (spin) na bane; har asal khidki apna samay
     * leti hai aur beech me idle ghadi chalti
     * rehti hai।
     */
    private val sessionReopenRunnable = Runnable {
        executor.execute {
            if (destroyed || !sessionActive) return@execute
            if (state == State.COMMAND || state == State.ACK) {
                startCommandSession()
            }
        }
    }

    private fun reopenSessionWindow() {
        // v51 (item 6b): lagatar khaali khidkiyon par
        // antar peeche hat-ta hai — pehli 3 par 600 ms,
        // phir 3 s, phir 8 s। Har khaali khidki ek
        // recognizer band+shuru (aur system kaan par
        // ek bip jodi) hoti hai; bina peeche-hatne ke
        // chup kamre me bhi bip chakra banta rehta
        // tha। Asal baat milte hi hisaab taaza
        // (onCommandHeard), aur 15-minute wali idle
        // ghadi aakhiri rok pehle ki tarah hai —
        // "miss session ko nahi marta" wala v49
        // niyam bilkul aparivartit।
        sessionEmptyReopenCount++
        val delayMs = when {
            sessionEmptyReopenCount <= 3 -> SESSION_REOPEN_DELAY_MS
            sessionEmptyReopenCount <= 5 -> SESSION_REOPEN_DELAY_MID_MS
            else -> SESSION_REOPEN_DELAY_LATE_MS
        }
        mainHandler.removeCallbacks(sessionReopenRunnable)
        mainHandler.postDelayed(sessionReopenRunnable, delayMs)
    }

    // ------------------------------------------------------------------
    // v49: session wali boli linein — shaksiyat ki apni bhasha me
    // ------------------------------------------------------------------

    /** "আর কিছু?" wala prompt — session ki pehchan। */
    private fun sessionMoreText(): String = when (sessionPersona) {
        Persona.MAYA -> SESSION_MORE_TEXT_HI
        Persona.TARA -> SESSION_MORE_TEXT_BN
    }

    /** Na-samajh par ek sawal — poochhna, band karna nahi। */
    private fun sessionClarifyText(): String = when (sessionPersona) {
        Persona.MAYA -> SESSION_CLARIFY_TEXT_HI
        Persona.TARA -> SESSION_CLARIFY_TEXT_BN
    }

    /** Doosri na-samajh par imaandaar line — phir prompt aayega। */
    private fun sessionNotUnderstoodText(): String = when (sessionPersona) {
        Persona.MAYA -> SESSION_NOT_UNDERSTOOD_TEXT_HI
        Persona.TARA -> SESSION_NOT_UNDERSTOOD_TEXT_BN
    }

    /**
     * Prompt par "না" ki vinamra vida। v50:
     * malik ka naam bandha ho ([UserProfile])
     * to vida me naam judta hai — "ঠিক আছে,
     * <নাম>"। Naam na ho to bandhi line hi।
     * (Wake wali haan-line me naam jaan-boojh
     * kar nahi judta — woh bandhi hai।)
     */
    private fun sessionDoneText(): String {
        val name = UserProfile.getName(applicationContext)
        return when (sessionPersona) {
            Persona.MAYA ->
                if (name != null) "ठीक है, $name।"
                else SESSION_DONE_TEXT_HI
            Persona.TARA ->
                if (name != null) "ঠিক আছে, $name।"
                else SESSION_DONE_TEXT_BN
        }
    }

    /** Khamoshi wale ant ki chetaavni line। */
    private fun sessionIdleText(): String = when (sessionPersona) {
        Persona.MAYA -> SESSION_IDLE_TEXT_HI
        Persona.TARA -> SESSION_IDLE_TEXT_BN
    }

    /**
     * v48: dusri bhasha ki script wali baat par vinamra
     * mod — MAYA session me Bangla-mukhi baat, TARA
     * session me Devanagari-mukhi baat. Baat par kaam
     * NAHI hoga; bulane wala ye line bolkar session
     * vinamrata se band kar dega.
     *
     * Jaanch sirf SYSTEM/Zipformer wale kaan ke text
     * par lagti hai ([currentTurnViaSystem]) — Vosk
     * hamesha Devanagari lipyantaran deta hai, isliye
     * uske text me Bangla boli bhi Devanagari hi
     * dikhegi; wahan script se bhasha pehchanna jhooth
     * hoga aur sahi Bangla hukm bhi mod kha jayega.
     * (Ek jaani hui seema: system recognizer kabhi
     * Bangla boli ko Devanagari me lipyantarit kar de
     * to Maya use Hindi hi manegi — script hi ekmatra
     * sanket hai.)
     */
    private fun personaRedirectLine(text: String): String? {
        if (!currentTurnViaSystem) return null
        val lang = PlayIntent.requestLanguage(text)
        return when {
            sessionPersona == Persona.MAYA &&
                lang == PlayIntent.SpokenLang.BENGALI -> MAYA_REDIRECT_TEXT
            sessionPersona == Persona.TARA &&
                lang == PlayIntent.SpokenLang.HINDI -> TARA_REDIRECT_TEXT
            else -> null
        }
    }

    /**
     * v36: khud-ki-awaaz wala jaal abhi band hai kya — TTS
     * bol raha ho, ya boli khatm hue SELF_ECHO_GUARD_MS
     * (1.5 s) bhi na beete hon. Is khidki me recognizer jo
     * bhi "sune" (partial ya final, wake ya command), woh
     * Maya ki apni boli ki goonj hi mani jayegi aur hukm
     * kabhi nahi banegi — uske jawabon ke "চালু" jaise
     * shabd dobara wahi kaam chalu kar dete the.
     */
    private fun selfEchoGuardActive(): Boolean {
        if (speaker.isSpeakingNow) return true
        val end = lastSpeechEndMs
        return end > 0L &&
            SystemClock.uptimeMillis() - end < SELF_ECHO_GUARD_MS
    }

    private fun onTtsFinished() {
        executor.execute {
            // v75: FULL SHUTDOWN ki "কমপ্লিট" wali boli
            // khatm — ab wake sunna phir chalu NAHI hoga,
            // seedha maar (fullShutdownKill)। Boli khatm
            // hone se pehle wala destroyed-check iske
            // baad aata hai taaki shutdown ka raasta
            // kabhi na chhoote।
            if (fullShutdownPending) {
                fullShutdownKill()
                return@execute
            }
            if (destroyed) return@execute
            // v36: boli asal me yahan khatm hui — goonj wali
            // khidki (self-hearing guard) yahin se gini jayegi.
            lastSpeechEndMs = SystemClock.uptimeMillis()
            when (state) {
                State.ACK -> {
                    onAckSpeechDone()
                }
                State.REPLY -> {
                    disarmReplyFallback()
                    if (addaEngine.isActive) {
                        // v13: adda abhi chal raha hai — gola bana
                        // rahega aur agla turn bina "Jarvis" bole
                        // seedha command session se shuru hoga.
                        setStatus("Adda chal raha hai — Maya sun rahi hai")
                        updateNotification("Maya adda sun rahi hai — boliye...")
                        startCommandSession()
                    } else if (sessionActive && ScreenConfirm.hasPending()) {
                        // v49: taana session ke andar screen
                        // wale kaam ka sawal abhi latka hai
                        // (uski boli abhi khatm hui hai) —
                        // jawab wake naam se NAHI, session
                        // ki apni sunne wali khidki se aayega:
                        // seedha command session kholo।
                        // (Session ke bahar ye pal purana
                        // v48 wala hi hai: neeche wala aam
                        // ant + wake sunna।)
                        setStatus("Screen wala kaam sawal pooch raha hai — jawab boliye")
                        updateNotification("Jawab boliye...")
                        startCommandSession()
                    } else if (sessionActive) {
                        // v49: TAANA SESSION chal raha hai —
                        // jawab bola gaya, par baatchit
                        // khatm NAHI: "আর কিছু করতে হবে?"
                        // poochho aur bina wake naam ke
                        // agli khidki kholo। Session ka
                        // ant yahan NAHI band hota (woh
                        // sirf shutdown / na / khamoshi par
                        // bandhega)।
                        promptSessionMore()
                    } else {
                        // v48: jawab bola gaya — session ka ant
                        // yahin band hota hai (wajah jawab bolne
                        // wale raaste ne pehle bandh di thi).
                        endSession(pendingEndReason)
                        pendingEndReason = SESSION_END_COMPLETED
                        // v1.5: jawab khatm — gola 1.5 s aur dikh kar
                        // dheere se gayab ho jayega.
                        orbOverlay.hideAfter(ORB_REPLY_LINGER_MS)
                        startWakeSession()
                    }
                }
                else -> {
                    // Speech finished in some other state: nothing waits on it.
                }
            }
        }
    }

    /**
     * v33: ACK wali boli (wake ki haan, retry prompt, ya vida
     * wali line) khatm — aam haal me command session kholo;
     * vida wali line chal rahi thi to gola hata kar wapas
     * wake sunne par jao. TTS-finish aur ack-fallback dono
     * yahi se guzarte hain.
     */
    private fun onAckSpeechDone() {
        disarmAckFallback()
        if (ackEndsConversation) {
            ackEndsConversation = false
            // v48: vida-line wala ant — wajah band karo,
            // phir wake sunna phir chalu (har ant par).
            endSession(closingReason)
            orbOverlay.hideNow()
            startWakeSession()
        } else {
            startCommandSession()
        }
    }

    /**
     * Command session ki shuruaat — kaanon ka kram:
     *  1. v41 ZIPFORMER (Bangla offline): model download
     *     karke taiyaar ho aur is chain me bigda na ho to
     *     sabse pehle wahi. Khaali/bigda natija aane par
     *     turn neeche wale kram par gir jata hai.
     *  2. v38 SYSTEM recognizer: uplabdh na ho ya turant
     *     fail ho jaye to usi turn ke liye Vosk.
     *  3. Vosk wala purana raasta — Hindi background
     *     pehle ki tarah chalta rahega.
     * Model na ho to kram bilkul v40 wala hi hai.
     */
    private fun startCommandSession() {
        if (destroyed) return
        // v51 (item 6a): mic saamne wali screen ke paas
        // ho to khidki MAT kholo — dono recognizer
        // takrayenge। Taana session ho to khidki
        // peeche-hatne wale antar se phir aazmayenge
        // (mic wapas milte hi resume wala raasta bhi
        // wahi kholega); aam turn ho to woh turn yahin
        // chup-chaap khatm — screen wala sunna malik
        // ki apni marzi hai, usse ladna nahi hai।
        if (MicArbiter.isForegroundOwned(applicationContext)) {
            if (sessionActive) {
                reopenSessionWindow()
            }
            return
        }
        MicArbiter.claim(applicationContext, MicArbiter.OWNER_SERVICE)
        // v49: taana session ki khidki khul rahi hai —
        // 15-minute wali khamoshi-ghadi yahin se chalegi
        // (koi baat mile to onCommandHeard use band
        // kar dega; kaam chal raha ho to ghadi aage
        // badh jayegi, session beech kaam me nahi
        // marega — sessionIdleRunnable dekho)।
        if (sessionActive) {
            armSessionIdle()
        }
        // v48: Zipformer SIRF Tara ke liye hai — Maya
        // (Hindi) ke session me Bangla kaan kabhi nahi
        // khulega; uska kram seedha system (hi-IN) ->
        // Vosk hai, bilkul hukm ke hisaab se.
        if (sessionPersona == Persona.TARA &&
            !zipformerBrokenThisChain && isBanglaModelReady()
        ) {
            startZipformerCommandSession()
            return
        }
        if (prefs.offlineEarsFirst && model != null) {
            // v51 (item 8): "offline kaan pehle" wala
            // vaikalpik badlav chalu hai — system
            // recognizer (bip wala kaan) se pehle Vosk
            // wala offline kaan। Tara ke liye Zipformer
            // upar pehle hi hai; ye Maya ke kram ko
            // badalta hai। Badlav band ho to ye shaakha
            // kabhi nahi chalti — purana kram hi aam hai।
            startVoskCommandSession()
            return
        }
        if (!systemUnavailableThisChain && tryStartSystemCommandSession()) {
            return
        }
        startVoskCommandSession()
    }

    /** v41: Bangla Zipformer model taiyaar hai kya (chaaron file poori). */
    private fun isBanglaModelReady(): Boolean = try {
        banglaModelManager?.isReady() == true
    } catch (t: Throwable) {
        false
    }

    private fun startVoskCommandSession() {
        if (destroyed) return
        val loadedModel = model ?: return
        // v16: har naya session (aam command, retry ke baad wala,
        // ya adda ka agla turn) screen-wake ka samay aage badhata
        // hai — lambi baatchit me screen beech me nahi bujhega.
        lightScreenOnWake()
        stopWakeSession()
        stopCommandSession()
        stopSystemCommandSession()
        stopZipformerCommandSession()
        state = State.COMMAND
        commandKind = CommandKind.VOSK
        try {
            val recognizer = Recognizer(loadedModel, SAMPLE_RATE)
            val service = SpeechService(recognizer, SAMPLE_RATE)
            commandSpeechService = service
            service.startListening(commandListener)
            setStatus("Command sun raha hoon — boliye")
            updateNotification("Command boliye...")
            val now = SystemClock.uptimeMillis()
            commandSessionStartMs = now
            commandLastChangeMs = now
            commandLastPartial = ""
            armCommandWatchdog()
        } catch (t: Throwable) {
            // Mic busy etc.: session shuru hi nahi ho paya — pehli
            // koshish par v12 retry ka mauka, warna wapas wake par.
            // v48: wajah recognizer-error bandhegi.
            handleCommandMiss(fromRecognizerError = true)
        }
    }

    private fun stopCommandSession() {
        disarmCommandWatchdog()
        commandSpeechService?.let { service ->
            try {
                service.stop()
            } catch (t: Throwable) {
                // Already stopped: nothing to do.
            }
            try {
                service.shutdown()
            } catch (t: Throwable) {
                // Ignore while tearing down.
            }
        }
        commandSpeechService = null
    }

    // ------------------------------------------------------------------
    // v38: system recognizer wala command session
    //
    // Pul (bridge) is prakar hai: engine MAIN thread par
    // applicationContext ke saath banta hai (SystemSpeechEngine
    // khud appContext rakhta hai); shuru/rok ke saare kaam
    // mainHandler se hote hain, aur uske callbacks (jo main
    // thread par aate hain) faisle executor ko saunp dete
    // hain — state badlav hamesha executor par hi hota hai,
    // bilkul Vosk wale raaste ki tarah. Wake wala Vosk
    // session onWakeDetected me pehle hi band ho chuka
    // hota hai aur ACK ki boli khatm hone par hi session
    // khulta hai, isliye mic ka koi takraav nahi hota.
    // Khidki ka samay engine ke apne input-niyam (saamne
    // wali screen wale hi) tay karte hain.
    // ------------------------------------------------------------------

    /**
     * System session kholne ki koshish. Recognizer uplabdh
     * hi na ho to false (bulane wala seedha Vosk par
     * jayega); koshish shuru ho gayi ho to true — turant
     * fail hone par listener wala raasta Vosk par girayega.
     * Sirf executor thread se bulao.
     */
    private fun tryStartSystemCommandSession(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(applicationContext)) {
            systemUnavailableThisChain = true
            return false
        }
        // v16 wala screen-wake refresh, Vosk session ki tarah.
        lightScreenOnWake()
        stopWakeSession()
        stopCommandSession()
        stopSystemCommandSession()
        stopZipformerCommandSession()
        state = State.COMMAND
        commandKind = CommandKind.SYSTEM
        systemEchoRestarts = 0
        // v51 (item 2): gate wala hisaab bhi har naye
        // system prayas par taaza।
        systemGateRestarts = 0
        resetSystemCascade()
        setStatus("Command sun raha hoon — boliye")
        updateNotification("Command boliye...")
        // v51 (item 6c): rok + shuru EK hi main-thread
        // block me — upar wala stopSystemCommandSession
        // apna post alag bhejta hai; woh post isse pehle
        // chale ya baad me, asal band+shuru yahin ek
        // saath hoga, isliye aadha-band recognizer par
        // naya start padkar busy-error nahi banega।
        mainHandler.post {
            try {
                systemEngine?.stopListening()
            } catch (t: Throwable) {
                // Band karte huey gadbad — shuru phir bhi hoga.
            }
            try {
                systemOfflineEngine?.stopListening()
            } catch (t: Throwable) {
                // Band karte huey gadbad — shuru phir bhi hoga.
            }
            startSystemListeningOnMain()
        }
        return true
    }

    /**
     * v38: bhasha kataar taaza karo.
     * v48: kataar ab pasand se NAHI, SHAKSIYAT se
     * banti hai — Maya ke liye SIRF hi-IN (koi bn-IN
     * wala dusra mauka nahi) aur Tara ke liye SIRF
     * bn-IN (koi hi-IN wala dusra mauka nahi)। Ek
     * bhasha ki kataar khatm ho to session apne
     * miss-niyam par jata hai, dusri bhasha par
     * kabhi nahi girta.
     * v93: ye ek-bhasha wali kataar SIRF tab, jab
     * chuni hui bhasha Hindi/Bangla ho (Maya/Tara
     * wala bandhan — bilkul pehle jaisa)। Chuni hui
     * bhasha koi AUR ho (Tamil, English, Spanish...)
     * to kataar usi se shuru hoti hai, phir shaksiyat
     * ki bhasha, phir bn/hi/en — taaki nayi bhasha
     * me boli sunai de, aur wapas Hindi/Bangla me
     * badalne wala hukm bhi pakda ja sake।
     */
    private fun resetSystemCascade() {
        val selected = prefs.listenLanguage
        val personaTag = when (sessionPersona) {
            Persona.MAYA -> "hi-IN"
            Persona.TARA -> "bn-IN"
        }
        systemCascadeLanguages = if (MayaLanguages.isPersonaLanguage(selected)) {
            listOf(personaTag)
        } else {
            listOf(selected, personaTag, "bn-IN", "hi-IN", "en-IN").distinct()
        }
        systemCascadeIndex = 0
    }

    /** Sirf main thread se — SystemSpeechEngine wahi banta/chalta hai. */
    private fun startSystemListeningOnMain() {
        if (destroyed || state != State.COMMAND ||
            commandKind != CommandKind.SYSTEM
        ) {
            return
        }
        val engine = systemEngine ?: SystemSpeechEngine(
            applicationContext, preferOffline = false
        ).also { created ->
            created.listener = systemCommandListener
            systemEngine = created
        }
        activeSystemEngine = engine
        systemOfflineActive = false
        engine.languageOverride =
            systemCascadeLanguages.getOrNull(systemCascadeIndex)
        try {
            // v51 (item 6c): restartListening = purana
            // recognizer destroy + taaza shuru, ek hi
            // main-thread saans me — ruk-kar-shuru wala
            // purana takraav (busy error -> bip chakra)
            // yahin khatm hota hai।
            engine.restartListening()
        } catch (t: Throwable) {
            // Engine ne apna onError na diya ho to bhi
            // is turn ke liye Vosk par giro.
            executor.execute { onSystemSessionFailed() }
        }
    }

    /**
     * v51 (item 4): net-fail ke baad ka EK offline
     * prayas — preferOffline=true wala alag engine।
     * Phone me Google ka Hindi offline pack ho to ye
     * bina net ke bhi achha sunega; pack na ho to ye
     * turant fail hoga aur listener wala raasta Vosk
     * par girayega (factory wali chetaavni yahi hai —
     * isliye koshish ek hi baar, [systemOfflineAttemptUsed]
     * se bandhi)। Sirf executor thread se bulao;
     * engine main thread par banta/chalta hai।
     */
    private fun startOfflineSystemAttempt() {
        if (destroyed || state != State.COMMAND ||
            commandKind != CommandKind.SYSTEM
        ) {
            return
        }
        setStatus("Net wala kaan fail — offline kaan aazma rahi hoon")
        mainHandler.post { restartOfflineListeningOnMain() }
    }

    /** Sirf main thread se — offline engine ko (phir) chalao। */
    private fun restartOfflineListeningOnMain() {
        if (destroyed || state != State.COMMAND ||
            commandKind != CommandKind.SYSTEM
        ) {
            return
        }
        val engine = systemOfflineEngine ?: SystemSpeechEngine(
            applicationContext, preferOffline = true
        ).also { created ->
            created.listener = systemCommandListener
            systemOfflineEngine = created
        }
        activeSystemEngine = engine
        systemOfflineActive = true
        engine.languageOverride =
            systemCascadeLanguages.getOrNull(systemCascadeIndex)
        try {
            engine.restartListening()
        } catch (t: Throwable) {
            executor.execute { onSystemSessionFailed() }
        }
    }

    /** System session roko — engine main thread par hai, isliye post karke. */
    private fun stopSystemCommandSession() {
        mainHandler.post {
            try {
                systemEngine?.stopListening()
            } catch (t: Throwable) {
                // Band karte huey gadbad — session waise bhi khatm.
            }
            try {
                // v51: offline wala engine bhi saath roko —
                // jo chal raha ho wahi asal mic rakhta hai।
                systemOfflineEngine?.stopListening()
            } catch (t: Throwable) {
                // Band karte huey gadbad — session waise bhi khatm.
            }
        }
    }

    /** v51 (item 3): net-warga ke error code — dheela net / server timeout wali pehchan। */
    private fun isNetworkErrorCode(code: Int): Boolean =
        code == SpeechRecognizer.ERROR_NETWORK ||
            code == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
            code == SpeechRecognizer.ERROR_SERVER ||
            code == SpeechRecognizer.ERROR_SERVER_DISCONNECTED

    private val systemCommandListener = object : SpeechEngineListener {
        override fun onListeningStateChanged(listening: Boolean) {
            // Service ko isse kuch nahi lena — session ki
            // haalat state/commandKind se bandhi hai.
        }

        override fun onPartialResult(text: String) {
            // Post-wake hukm ke liye partial ka kaam nahi —
            // sirf pakka final natija hukm banta hai.
        }

        override fun onFinalResult(text: String) {
            // Callback main thread par hai; asal kaam executor par.
            executor.execute { acceptSystemCommand(text) }
        }

        override fun onError(message: String) {
            // Error ki pehchan engine ke bandhe hue code se —
            // main thread par hi padh lo, phir faisla executor par.
            // v51: jo engine is pal chal raha tha (aam ya
            // offline wala) usi ke code padho — pehle hamesha
            // aam engine padha jata tha।
            val engine = activeSystemEngine ?: systemEngine ?: return
            val code = engine.lastErrorCode
            val immediate = engine.lastErrorWasImmediate
            val wasOfflineAttempt = systemOfflineActive
            executor.execute {
                if (destroyed || state != State.COMMAND ||
                    commandKind != CommandKind.SYSTEM
                ) {
                    return@execute
                }
                // v51 (item 1): har error wala turn bhi
                // sunne-raaste ki report me bandhe —
                // engine + bhasha + code + turant-fail
                // ka nishan।
                saveHearPathDiag(
                    engine = if (wasOfflineAttempt) {
                        "system-offline"
                    } else {
                        "system"
                    },
                    locale = systemCascadeLanguages
                        .getOrNull(systemCascadeIndex),
                    errCode = code,
                    immediate = immediate,
                    text = null
                )
                when {
                    isNetworkErrorCode(code) && !wasOfflineAttempt &&
                        !systemOfflineAttemptUsed -> {
                        // v51 (item 3+4): net wali nakami
                        // boli-retry wala chakra NAHI
                        // chalegi — pehle EK offline
                        // prayas (pack ho to bina net
                        // sunega), woh bhi fail ho to
                        // seedha Vosk. Dheele net par yahi
                        // chakra bip chakra ki jad tha।
                        systemOfflineAttemptUsed = true
                        startOfflineSystemAttempt()
                    }
                    code == SpeechRecognizer.ERROR_CLIENT || immediate -> {
                        // Recognizer service hi kharab /
                        // missing hai (OriginOS par aam) —
                        // is chain me phir koshish nahi;
                        // is turn ke liye Vosk wala raasta.
                        // v51: offline prayas pack na hone
                        // par yahin turant fail hokar aata
                        // hai — uska bhi yahi ant hai।
                        systemUnavailableThisChain = true
                        onSystemSessionFailed()
                    }
                    isNetworkErrorCode(code) -> {
                        // v51: offline wala bhi net par
                        // fail, ya dusri baar net-fail —
                        // bina boli-retry ke Vosk par giro।
                        onSystemSessionFailed()
                    }
                    (code == SpeechRecognizer.ERROR_NO_MATCH ||
                        code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) &&
                        advanceSystemCascade() -> {
                        // Kuch samajh nahi aaya — kataar ki
                        // agli bhasha par wahi session phir
                        // khula hai (v35 wala hi vyavhaar).
                    }
                    else -> handleSystemCommandMiss(
                        SESSION_END_RECOGNIZER_ERROR
                    )
                }
            }
        }
    }

    /** Kataar ki agli bhasha par system session phir kholo. Kataar khatm ho to false. */
    private fun advanceSystemCascade(): Boolean {
        if (systemCascadeIndex >= systemCascadeLanguages.lastIndex) return false
        systemCascadeIndex++
        mainHandler.post { startSystemListeningOnMain() }
        return true
    }

    /**
     * System session turant-fail / uplabdh-nahi wala ant —
     * is turn ke liye purana Vosk wala command session
     * chalao. Is chain me system dobara nahi aazmayenge
     * (turant-fail har wake par dohrana bekaar hai).
     * Sirf executor thread se bulao.
     */
    private fun onSystemSessionFailed() {
        if (destroyed || state != State.COMMAND ||
            commandKind != CommandKind.SYSTEM
        ) {
            return
        }
        systemUnavailableThisChain = true
        startVoskCommandSession()
    }

    /**
     * System recognizer ne pakka hukm suna — wahi
     * onCommandHeard wala raasta jo Vosk wala text leta
     * tha (pakke hukm -> agent -> adda), neeche kuch
     * nahi badla. Sirf executor thread se bulao.
     */
    private fun acceptSystemCommand(text: String) {
        if (destroyed || state != State.COMMAND ||
            commandKind != CommandKind.SYSTEM
        ) {
            return
        }
        if (text.isBlank()) {
            handleSystemCommandMiss()
            return
        }
        // v51 (item 1): sunne ke raaste ki report —
        // engine + bhasha + suna text, har turn par।
        val offlineNow = systemOfflineActive
        saveHearPathDiag(
            engine = if (offlineNow) "system-offline" else "system",
            locale = systemCascadeLanguages
                .getOrNull(systemCascadeIndex),
            errCode = -1,
            immediate = false,
            text = text
        )
        // v51 (item 2): plausibility gate — kachra
        // (bahut chhota + bina pehchan shabd) hukm
        // bankar aage NAHI jayega; miss mano par
        // boli-retry kharch kiye bina phir suno
        // (echo-restart wala hi raasta, seema bandhi)।
        val gate = HearingGate.evaluate(
            text = text,
            persona = sessionPersona,
            scriptReliable = true,
            extraAccepted = gateExtraAccepted()
        )
        if (!gate.accepted) {
            saveHearPathDiag(
                engine = if (offlineNow) "system-offline" else "system",
                locale = systemCascadeLanguages
                    .getOrNull(systemCascadeIndex),
                errCode = -1,
                immediate = false,
                text = text,
                gateReason = gate.reason
            )
            if (systemGateRestarts < MAX_GATE_RESTARTS) {
                systemGateRestarts++
                resetSystemCascade()
                // Jis kaan ne suna tha usi ko phir
                // chalao — offline wala natija online
                // par phir net-fail ka chakra na bane।
                if (offlineNow) {
                    mainHandler.post { restartOfflineListeningOnMain() }
                } else {
                    mainHandler.post { startSystemListeningOnMain() }
                }
            } else {
                handleSystemCommandMiss()
            }
            return
        }
        if (selfEchoGuardActive()) {
            // Maya ki apni boli (prompt) goonj bankar aayi
            // hai — retry kharch kiye bina wahi khidki phir
            // kholo; baar-baar ho to miss mano.
            if (systemEchoRestarts < MAX_SYSTEM_ECHO_RESTARTS) {
                systemEchoRestarts++
                resetSystemCascade()
                mainHandler.post { startSystemListeningOnMain() }
            } else {
                handleSystemCommandMiss()
            }
            return
        }
        currentTurnViaSystem = true
        if (offlineNow) {
            // v51 (item 4): offline engine ne asal hukm
            // suna — pack maujood hai aur kaam karta
            // hai; main screen ki salaah-line ab khud
            // chhup jayegi।
            saveOfflineSystemOkDiag()
        }
        // v12 diagnostics wala hi "Mic ne aakhri suna",
        // aur v38 ki engine-wali report jarvis_diag me.
        rememberCommandFinal(text)
        saveBackgroundHeardDiag(fromSystem = true, text = text)
        stopSystemCommandSession()
        commandKind = CommandKind.NONE
        onCommandHeard(dialectTranslated(text))
    }

    /**
     * v38: system raaste par miss (kuch suna nahi /
     * cascade khatm / recognizer ki aam galti) ka NAYA
     * niyam — purana do-retry wala "বুঝতে পারিনি" jaap
     * is raaste par NAHI hai:
     *  - pehli baar: ek chhota prompt user ki pasandeeda
     *    bhasha me ("বলুন, শুনছি") + ek aur sunne ki
     *    khidki (ACK wale raaste se);
     *  - dusri baar: ek imaandaar vida line bolkar wapas
     *    wake sunne par (startClosingLine).
     * Adda chalu ho to adda ke apne niyam (handleCommandMiss
     * wala hi vyavhaar) — Maya wahan khud sambhalti hai.
     * Sirf executor thread se bulao.
     */
    private fun handleSystemCommandMiss(
        endReason: String = SESSION_END_NOT_UNDERSTOOD
    ) {
        if (destroyed) return
        stopSystemCommandSession()
        commandKind = CommandKind.NONE
        if (sessionActive) {
            // v49: taana session me khaali khidki
            // (kuch suna nahi / recognizer chup) maut
            // NAHI hai — khidki chup-chaap phir kholo।
            // Asli band sirf 15-minute wali poori
            // khamoshi (idle ghadi) karegi; suna-par-
            // na-juda wala hukm yahan aata hi nahi
            // (woh finishCommandResult ke session
            // wale sawal-raaste par jata hai)।
            reopenSessionWindow()
            return
        }
        if (addaEngine.isActive) {
            if (addaNoSpeechCount == 0) {
                addaNoSpeechCount = 1
                startCommandSession()
            } else {
                // v48: adda ka dusra khaali session pehle
                // CHUP-CHAAP band hota tha — gola hatao
                // aur seedha wake par, bina ek shabd bole
                // (user ki shikayat wali "chup maut")। Ab
                // vida-line bolkar band hoga, shaksiyat ki
                // apni bhasha me, wajah ke saath.
                addaEngine.endSession()
                addaNoSpeechCount = 0
                startClosingLine(SESSION_END_ADDA_SILENT)
            }
            return
        }
        if (!systemRetryUsed) {
            systemRetryUsed = true
            state = State.ACK
            ackEndsConversation = false
            setStatus("Samajh nahi aaya — boliye, sun rahi hoon")
            updateNotification("Boliye, sun rahi hoon...")
            lightScreenOnWake()
            speaker.speak(systemRetryPrompt())
            armAckFallback()
        } else {
            startClosingLine(endReason)
        }
    }

    /** v38 wala ek-mauka prompt — v48: pasand se nahi, shaksiyat ki bhasha me. */
    private fun systemRetryPrompt(): String =
        when (sessionPersona) {
            Persona.MAYA -> "बोलिए, सुन रही हूँ।"
            Persona.TARA -> "বলুন, শুনছি।"
        }

    /**
     * v38: post-wake hukm KIS kaan se suna gaya, woh
     * "jarvis_diag" me "last_background_heard" par band
     * karo ("system bn-IN: ..." / "vosk: ...") — agle
     * test me bina andaze ke dikhega ki Bangla asal me
     * system se suna gaya ya Vosk par gir gaya tha.
     * Setup page wala "Mic ne aakhri suna" (AppPrefs)
     * dono raaston par pehle ki tarah hi banta hai
     * (rememberCommandFinal).
     */
    private fun saveBackgroundHeardDiag(fromSystem: Boolean, text: String) {
        val tag = if (fromSystem) {
            val lang = systemCascadeLanguages
                .getOrNull(systemCascadeIndex)
                ?.takeIf { it.isNotBlank() }
            if (lang != null) "system $lang" else "system"
        } else {
            "vosk"
        }
        saveBackgroundHeardDiagTag(tag, text)
    }

    /**
     * v51 (item 1): sunne ke RAASTE ki report —
     * "jarvis_diag" ki "last_hear_path" par har
     * command turn ka hisaab: kaun sa kaan (system /
     * system-offline / vosk / zipformer / fg-*), kis
     * bhasha me, error code + turant-fail ka nishan
     * (natija ho to err=-1), aur suna text। Gate ne
     * roka ho to [gateReason] ke saath "gate:rejected"
     * bankar bandhta hai (item 2)। Main screen par
     * "শোনার পথ (aakhiri)" line me dikhta hai —
     * bip chakra ya galat-sunne ki shikayat par ab
     * andaza nahi, bandha hisaab milega।
     */
    private fun saveHearPathDiag(
        engine: String,
        locale: String?,
        errCode: Int,
        immediate: Boolean,
        text: String?,
        gateReason: String? = null
    ) {
        try {
            val sb = StringBuilder(engine)
            if (!locale.isNullOrBlank()) {
                sb.append(' ').append(locale)
            }
            if (gateReason != null) {
                sb.append(" gate:rejected(").append(gateReason).append(')')
            } else if (errCode >= 0) {
                sb.append(" err=").append(errCode)
                    .append(" imm=").append(immediate)
            }
            if (!text.isNullOrBlank()) {
                sb.append(": ").append(text.trim())
            }
            getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                .edit()
                .putString("last_hear_path", sb.toString())
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai — asal hukm isse nahi rukta.
        }
    }

    /**
     * v51 (item 4): offline system engine ne pehli
     * baar asal hukm suna — "offline_system_ok" ka
     * nishan, jisse main screen ki Hindi-pack wali
     * salaah-line khud chhup jati hai।
     */
    private fun saveOfflineSystemOkDiag() {
        try {
            getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                .edit()
                .putString("offline_system_ok", "1")
                .apply()
        } catch (t: Throwable) {
            // Nishan bonus hai — asal hukm isse nahi rukta.
        }
    }

    /**
     * v51 (item 2): gate ke liye sikhaye/seekhe hue
     * shabd — malik ke khud bandhe shabd (taught
     * triggers) aur seekhe kaamon ke naam hamesha
     * pehchan-shabd maane jayein, warna ek-shabd
     * wala sikha hukm gate par ruk jayega।
     */
    private fun gateExtraAccepted(): List<String> {
        val out = mutableListOf<String>()
        try {
            out.addAll(
                TaughtCommands.readAll(applicationContext).map { it.trigger }
            )
        } catch (t: Throwable) {
            // Sikhaye shabd na milein to gate apne niyam par chalega.
        }
        try {
            out.addAll(
                LearnedRoutines.readAll(applicationContext).map { it.name }
            )
        } catch (t: Throwable) {
            // Seekhe naam na milein to bhi gate rukega nahi.
        }
        return out
    }

    /** v41: "last_background_heard" me kaan ka naam dekar band karo. */
    private fun saveBackgroundHeardDiagTag(tag: String, text: String) {
        try {
            getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                .edit()
                .putString("last_background_heard", "$tag: ${text.trim()}")
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai — asal hukm isse nahi rukta.
        }
    }

    /**
     * v41: Zipformer kaan ki apni report — "jarvis_diag"
     * ki "last_bangla_asr" par: natija + decode ka samay
     * + suna hua text ("zipformer 840ms: ..."), ya
     * "zipformer EMPTY" / "zipformer FAIL <kaaran>".
     * Main screen par "Bangla ASR (aakhiri)" line me
     * dikhti hai — agle test me bina andaze ke pata
     * chalega ki offline kaan ne asal me kya suna aur
     * kitni der me.
     */
    private fun saveBanglaAsrDiag(value: String) {
        try {
            getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                .edit()
                .putString("last_bangla_asr", value)
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai — asal hukm isse nahi rukta.
        }
    }

    // ------------------------------------------------------------------
    // v41: Zipformer (Bangla offline) wala command session
    //
    // Recording + decode [BanglaZipformer] ke apne thread
    // par hote hain: recording ~15 s tak ja sakti hai aur
    // service ka mukhya executor (jo TTS-finish, watchdog
    // aur baaki saari haalat sambhalta hai) itni der band
    // nahi reh sakta. Thread sirf natija laata hai; faisla
    // hamesha executor par hota hai, aur session number
    // (seq) purane thread ke der-wale natije ko naye
    // session se alag rakhta hai.
    //
    // Rukawat ke niyam v38 wale system raaste jaise hi:
    //  - suna gaya text seedha onCommandHeard ko — neeche
    //    ka dispatch (ScreenConfirm -> OFF -> scroll ->
    //    screenshot -> PlayIntent -> agent -> adda)
    //    bilkul aparivartit;
    //  - suna-par-nahi-juda text par v38 wala ek-prompt
    //    niyam (currentTurnViaSystem = true wala raasta);
    //  - khaali/fail natija = jaise ye kaan tha hi nahi:
    //    turn v38 wale kram (system -> Vosk) par girta
    //    hai, wahan ke apne miss-niyam laagu honge;
    //  - nakami par is chain ke liye ye kaan band
    //    (zipformerBrokenThisChain) — har wake par
    //    ek hi kharab koshish ka jaap nahi lagega.
    // ------------------------------------------------------------------

    /** Sirf executor thread se bulao. */
    private fun startZipformerCommandSession() {
        if (destroyed) return
        val asr = banglaAsr
        if (asr == null) {
            fallThroughToSystemOrVosk()
            return
        }
        lightScreenOnWake()
        stopWakeSession()
        stopCommandSession()
        stopSystemCommandSession()
        state = State.COMMAND
        commandKind = CommandKind.ZIPFORMER
        setStatus("Command sun raha hoon — boliye")
        updateNotification("Command boliye...")
        val seq = ++zipformerSessionSeq
        Thread {
            // v36 wala khud-ki-awaaz wala jaal (self-hearing
            // guard): Maya ki boli chal rahi ho ya abhi-abhi
            // khatm hui ho to mic MAT kholo — pehle goonj
            // wali khidki nikal jaane do, warna recording me
            // uski apni boli hi band ho jayegi. Zyada se
            // zyada ZIPFORMER_GUARD_WAIT_MS tak intezaar.
            var waitedMs = 0L
            while (!destroyed && seq == zipformerSessionSeq &&
                selfEchoGuardActive() &&
                waitedMs < ZIPFORMER_GUARD_WAIT_MS
            ) {
                try {
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    break
                }
                waitedMs += 100
            }
            if (destroyed || seq != zipformerSessionSeq) {
                return@Thread
            }
            val outcome = try {
                asr.listenAndTranscribe()
            } catch (t: Throwable) {
                BanglaZipformer.Outcome.Failed(
                    t.message ?: t.javaClass.simpleName
                )
            }
            executor.execute { onZipformerOutcome(seq, outcome) }
        }.start()
    }

    /**
     * Chal raha Zipformer session roko: session number
     * badhakar purane thread ka natija bekaar karo aur
     * recording chal rahi ho to use bhi roko. Har jagah
     * se surakshit (executor/main/teardown).
     */
    private fun stopZipformerCommandSession() {
        zipformerSessionSeq++
        try {
            banglaAsr?.cancelCapture()
        } catch (t: Throwable) {
            // Rokte huey gadbad — session waise bhi khatm.
        }
    }

    /** Zipformer thread ka natija — faisla executor par. */
    private fun onZipformerOutcome(
        seq: Int,
        outcome: BanglaZipformer.Outcome
    ) {
        if (destroyed || state != State.COMMAND ||
            commandKind != CommandKind.ZIPFORMER ||
            seq != zipformerSessionSeq
        ) {
            return
        }
        when (outcome) {
            is BanglaZipformer.Outcome.Heard -> {
                saveBanglaAsrDiag(
                    "zipformer ${outcome.decodeMs}ms: ${outcome.text}"
                )
                // v51 (item 1+2): raaste ki report + gate।
                // Zipformer chhoti awaaz par bhi badi
                // bani hui baat de sakta hai — tez decode
                // (<600 ms) + bina pehchan shabd wali
                // baat kachra maankar khidki phir kholo
                // (seema bandhi), phir purane kram par।
                val gate = HearingGate.evaluate(
                    text = outcome.text,
                    persona = sessionPersona,
                    scriptReliable = true,
                    decodeMs = outcome.decodeMs,
                    extraAccepted = gateExtraAccepted()
                )
                saveHearPathDiag(
                    engine = "zipformer",
                    locale = "bn-IN",
                    errCode = -1,
                    immediate = false,
                    text = outcome.text,
                    gateReason = if (gate.accepted) null else gate.reason
                )
                if (!gate.accepted) {
                    if (zipformerGateRestarts < MAX_GATE_RESTARTS) {
                        zipformerGateRestarts++
                        startZipformerCommandSession()
                    } else {
                        fallThroughToSystemOrVosk()
                    }
                    return
                }
                if (selfEchoGuardActive()) {
                    // Recording ke baad bhi goonj wali
                    // khidki band hai — natija Maya ki apni
                    // boli ka hissa ho sakta hai. System
                    // wale niyam ki tarah bina retry kharch
                    // kiye ek aur khidki; baar-baar ho to
                    // purane kram par giro.
                    if (zipformerEchoRestarts < MAX_SYSTEM_ECHO_RESTARTS) {
                        zipformerEchoRestarts++
                        startZipformerCommandSession()
                    } else {
                        fallThroughToSystemOrVosk()
                    }
                    return
                }
                // Suna-par-nahi-juda text par v38 wala
                // ek-prompt niyam laagu ho — isliye ye
                // turn bhi "system wala" mana jayega.
                currentTurnViaSystem = true
                rememberCommandFinal(outcome.text)
                saveBackgroundHeardDiagTag("zipformer", outcome.text)
                commandKind = CommandKind.NONE
                onCommandHeard(dialectTranslated(outcome.text))
            }
            BanglaZipformer.Outcome.Empty -> {
                saveBanglaAsrDiag("zipformer EMPTY")
                fallThroughToSystemOrVosk()
            }
            is BanglaZipformer.Outcome.Failed -> {
                saveBanglaAsrDiag("zipformer FAIL ${outcome.reason}")
                // Is chain me ye kaan phir nahi aazmayenge —
                // kharab recognizer har turn par atke, ye
                // system wale turant-fail niyam jaisa hi hai.
                zipformerBrokenThisChain = true
                fallThroughToSystemOrVosk()
            }
        }
    }

    /**
     * Zipformer ke baad ka purana kram — system uplabdh
     * ho to wahi, warna Vosk. Jaise v41 se pehle hota
     * tha, bilkul waisa hi.
     */
    private fun fallThroughToSystemOrVosk() {
        if (destroyed) return
        commandKind = CommandKind.NONE
        if (prefs.offlineEarsFirst && model != null) {
            // v51 (item 8): offline kaan pehle wala
            // badlav — Zipformer ke baad bhi system se
            // pehle Vosk hi aayega।
            startVoskCommandSession()
            return
        }
        if (!systemUnavailableThisChain && tryStartSystemCommandSession()) {
            return
        }
        startVoskCommandSession()
    }

    private val commandListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            if (state != State.COMMAND) return
            // v36: boli ki goonj wala partial session ki umar
            // nahi badhayega — asal awaaz ke shabd hi badhayein.
            if (selfEchoGuardActive()) return
            val partial = parseJsonField(hypothesis, "partial").trim()
            if (partial.isNotEmpty() && partial != commandLastPartial) {
                // The words actually changed — the user is mid-command,
                // so the silence deadline moves. Repeats of the same
                // partial (steady room noise) do NOT extend anything.
                commandLastPartial = partial
                commandLastChangeMs = SystemClock.uptimeMillis()
            }
        }

        override fun onResult(hypothesis: String?) {
            acceptCommand(parseJsonField(hypothesis, "text"))
        }

        override fun onFinalResult(hypothesis: String?) {
            acceptCommand(parseJsonField(hypothesis, "text"))
        }

        override fun onError(exception: Exception?) {
            executor.execute {
                if (!destroyed && state == State.COMMAND) {
                    // v12: bina command ke khatm — retry ya vida.
                    // v48: wajah recognizer-error bandhegi.
                    handleCommandMiss(fromRecognizerError = true)
                }
            }
        }

        override fun onTimeout() {
            executor.execute {
                if (!destroyed && state == State.COMMAND) {
                    // v12: bina command ke khatm — retry ya vida.
                    // v48: wajah recognizer-error bandhegi.
                    handleCommandMiss(fromRecognizerError = true)
                }
            }
        }

        private fun acceptCommand(text: String) {
            if (state != State.COMMAND || text.isBlank()) return
            // v36: Maya ki apni boli (ya uski goonj) hukm
            // bankar phir se kaam chalu na kar de — boli ke
            // dauraan aur uske pal bhar baad tak ka suna hua
            // sab yahin chhod do.
            if (selfEchoGuardActive()) return
            // v51 (item 1+2): raaste ki report + gate।
            // Vosk hamesha Devanagari lipyantaran deta
            // hai, isliye script-jaanch yahan lagu nahi
            // (scriptReliable = false)। Kachra ruke to
            // khidki yun hi chalti rehti hai — Vosk bip
            // nahi bajata, aur asal baat aane par wahi
            // khidki use pakad legi; khaali khidki
            // watchdog sambhalega।
            val gate = HearingGate.evaluate(
                text = text,
                persona = sessionPersona,
                scriptReliable = false,
                extraAccepted = gateExtraAccepted()
            )
            saveHearPathDiag(
                engine = "vosk",
                locale = "hi-IN",
                errCode = -1,
                immediate = false,
                text = text,
                gateReason = if (gate.accepted) null else gate.reason
            )
            if (!gate.accepted) return
            // v12 diagnostics: command recognizer ne jo suna, woh
            // bhi "Mic ne aakhri suna" me dikhe.
            rememberCommandFinal(text)
            // v38: ye turn Vosk se aaya hai — system wale
            // ek-prompt niyam is par lagu nahi honge, aur
            // report me kaan ka naam "vosk" bandhega.
            currentTurnViaSystem = false
            saveBackgroundHeardDiag(fromSystem = false, text = text)
            executor.execute { onCommandHeard(dialectTranslated(text)) }
        }
    }

    private fun onCommandHeard(text: String) {
        if (destroyed || state != State.COMMAND) return
        // Flip state BEFORE stopping the session, so the final-result
        // callback fired by stop() is ignored.
        state = State.REPLY
        stopCommandSession()
        // v49: koi bhi asal baat mili — khamoshi wali
        // ghadi band (session ho ya na ho, ghadi sirf
        // session me hi chalti hai)।
        disarmSessionIdle()
        // v51 (item 6b): asal baat mili — khaali
        // khidkiyon wala peeche-hatne ka hisaab taaza
        // (agli khaali khidki phir 600 ms se shuru)।
        sessionEmptyReopenCount = 0
        // v49: ye khidki "আর কিছু?" wale prompt ke
        // turant baad wali thi kya — sirf usi haal me
        // saaf "না" session band karega (neeche
        // wala bandobast)। Nishan yahin pakdo, phir
        // saaf kar do — agli khidki apna nishan khud
        // layegi।
        val wasAwaitingMore = sessionActive && sessionAwaitingMore
        sessionAwaitingMore = false
        // v75: FULL SHUTDOWN — "শাটডাউন / ফুল শাটডাউন"
        // sunte hi pehle "কমপ্লিট" bolkar poori service
        // + process band (neeche wala startFullShutdown)।
        // Ye jaanch chup wale shutdown se PEHLE lagti hai:
        // "সিস্টেম শাটডাউন" isme nahi aata, woh purana
        // chup session-ant hi rehta hai।
        if (SessionCommands.isFullShutdownCommand(text)) {
            if (ScreenConfirm.hasPending()) {
                ScreenConfirm.deliverAnswer(SESSION_DENY_ANSWER)
            }
            startFullShutdown()
            return
        }
        // v50: SHUTDOWN HAR HAAL ME SABSE PEHLE —
        // session ho ya na ho, adda ho ya aam
        // hukm, "শাটডাউন / shutdown" sunte hi
        // baat yahin khatm: gola turant gayab aur
        // EK SHABD BHI NAHI bola jayega (v49 ki
        // boli jaane wali vida-line is hukm ke
        // liye khatm — user ka pakka hukm:
        // "কিছু না বলে" band)। Latka hua
        // pushti-sawal ho to use saaf "না" dekar
        // agent ko turant chhodo (dwaar
        // fail-closed hi rahega)। Background wake
        // sunna chaloo rehta hai — poori service
        // band NAHI hoti।
        if (SessionCommands.isShutdownCommand(text)) {
            if (ScreenConfirm.hasPending()) {
                ScreenConfirm.deliverAnswer(SESSION_DENY_ANSWER)
            }
            shutdownSilently()
            return
        }
        // v37: screen-agent ka sawal latka ho (send ki
        // pushti / ask_user) to ye baat USI ka jawab hai —
        // naya command nahi. Jawab pul ko saunp kar wapas
        // wake sunne par jao; agent apni daud wahi se aage
        // badhayega ya imaandaari se rok dega.
        if (ScreenConfirm.deliverAnswer(text)) {
            if (sessionActive) {
                // v49: taana session ke andar ka jawab
                // hai — chain yahan NAHI tootti: service
                // REPLY me hi intezaar karegi, agent
                // apni daud aage badhayega, uska jawab
                // bola jayega aur phir "আর কিছু?" wala
                // prompt aayega (onTtsFinished wala
                // bandobast)। Wake sunna yahan NAHI
                // chalega — session ki khidki hi iska
                // raasta hai।
                setStatus("Screen wale kaam ka jawab mil gaya — kaam aage badh raha hai")
                return
            }
            setStatus("Screen wale kaam ka jawab mil gaya")
            // v48: ye baat ek mukammal len-den thi —
            // ant yahin band karo; kuch bolna NAHI hai
            // (bolna pushti wale pul ko bigaad dega)।
            endSession(SESSION_END_SCREEN_CONFIRM)
            startWakeSession()
            return
        }
        // v52: SEND BUTTON — background me koi box hota
        // hi nahi, isliye yahan sirf imaandaar jawab:
        // screen kholo, likho, phir "সেন্ড করো" bolo।
        // Kuch invent karke bheja nahi jata — box ka
        // kaam sirf saamne wali screen par hota hai।
        if (SendButtonCommands.isSendCommand(text)) {
            val sendLine = SendButtonCommands.backgroundReply(
                personaSpokenLang()
            )
            setStatus("Send button — screen khuli nahi hai")
            updateNotification("Jarvis jawab de raha hai...")
            lightScreenOnWake()
            speaker.speak(sendLine)
            armReplyFallback(sendLine)
            orbOverlay.setReply(sendLine)
            return
        }
        // v49: "আর কিছু?" ke jawab me saaf NA / bas —
        // session vinamrata se band (aam boli wali
        // vida, gola thaharkar gayab, wake sunna
        // chaloo)। Sirf prompt ke turant baad wali
        // baat par hai — beech kaam me "না" aam
        // hukm hi rehta hai।
        if (wasAwaitingMore && SessionCommands.isNoMoreAnswer(text)) {
            endSessionDone()
            return
        }
        // v97: DIALECT — bol-chaal sikhana
        // ("'X' শুনলে 'Y' বুঝবে" / "'X' বললে
        // 'Y' বুঝবে"), sudhaar ("না, আমি বলেছি
        // 'X'"), suchi/bhulna। TeachCommands se
        // PEHLE chalta hai — "বুঝবে" wala roop
        // sirf iska hai, "বললে" wala TeachCommands
        // ka (bina "বুঝবে" ke)। Pichli suni baat
        // [previousCommandFinalText] se aati hai।
        DialectCommands.tryHandleAdmin(
            applicationContext, text, personaSpokenLang(), previousCommandFinalText
        )?.let { dialectLine ->
            setStatus("Dialect wala hukm — jawab de raha hoon")
            updateNotification("Jarvis jawab de raha hai...")
            lightScreenOnWake()
            speaker.speak(dialectLine)
            armReplyFallback(dialectLine)
            orbOverlay.setReply(dialectLine)
            return
        }

        // v50: SIKHANE WALE NAYE HUKM — naam
        // bandhna ("আমার নাম …") aur shabd-kaam
        // sikhana ("'লাইট' বললে টর্চ জ্বালাবে")।
        // [TeachCommands] sirf apne saaf roop
        // pakadta hai; baaki seekhne wali baat
        // neeche [LearnCommands] ke paas hi
        // jayegi, bilkul pehle jaisa।
        TeachCommands.tryHandleAdmin(
            applicationContext, text, personaSpokenLang()
        )?.let { teachLine ->
            setStatus("Sikhane wala hukm — jawab de raha hoon")
            updateNotification("Jarvis jawab de raha hai...")
            lightScreenOnWake()
            speaker.speak(teachLine)
            armReplyFallback(teachLine)
            orbOverlay.setReply(teachLine)
            return
        }
        // v69: COMBO — "কম্বো মনে রাখো: যখন … তখন
        // <kaam1>, <kaam2>" se combo bandhna, "কী কী
        // কম্বো মনে আছে" se suchi, "… কম্বোটা ভুলে
        // যাও" se ek combo mitana। Sirf saaf combo
        // wale roop pakadta hai — "মনে রাখো, যখন…"
        // wala roop bhi tabhi jab "তখন" ke baad 2+
        // sahi dhap hon (ek dhap ho to niyam hi
        // rehta hai); isliye [RuleCommands] se PEHLE
        // bulaya jata hai, par bina apne roop ke null
        // dekar raasta chhod deta hai।
        ComboCommands.tryHandleAdmin(
            applicationContext, text, personaSpokenLang()
        )?.let { comboLine ->
            setStatus("Combo wala hukm — jawab de raha hoon")
            updateNotification("Jarvis jawab de raha hai...")
            lightScreenOnWake()
            speaker.speak(comboLine)
            armReplyFallback(comboLine)
            orbOverlay.setReply(comboLine)
            return
        }
        // v52: KAAM KE NIYAM — "নিয়ম মনে রাখো: যখন
        // … তখন …" (ya "মনে রাখো, যখন … তখন …") se
        // niyam bandhna, "কী কী নিয়ম মনে আছে" se
        // suchi, "… ভুলে যাও" se ek niyam mitana।
        // Sirf saaf niyam wale roop pakadta hai;
        // baaki [LearnCommands] ka hai — isliye
        // [LearnCommands] se PEHLE bulaya jata hai,
        // par bina apne roop ke null dekar raasta
        // chhod deta hai।
        RuleCommands.tryHandleAdmin(
            applicationContext, text, personaSpokenLang()
        )?.let { ruleLine ->
            setStatus("Niyam wala hukm — jawab de raha hoon")
            updateNotification("Jarvis jawab de raha hai...")
            lightScreenOnWake()
            speaker.speak(ruleLine)
            armReplyFallback(ruleLine)
            orbOverlay.setReply(ruleLine)
            return
        }
        // v49: SEEKHNE WALE PAKKE HUKM — session ke
        // andar bhi, bahar bhi (saamne wali screen
        // wala hi ek object, [LearnCommands])।
        // "মনে রেখো" (aakhiri safal kaam bandho —
        // asafal par imaandaar manaahi), "শিখে
        // নাও: …", "কী কী শিখেছ", naam wala
        // "ভুলে যাও"। Jawab shaksiyat ki pakki
        // bhasha me bola jata hai; session chal
        // raha ho to jawab ke baad prompt phir
        // aayega (aam boli wala bandobast), na ho
        // to chain aam taur par band hogi।
        LearnCommands.tryHandle(
            applicationContext, text, personaSpokenLang()
        )?.let { learnLine ->
            setStatus("Seekhne wala hukm — jawab de raha hoon")
            updateNotification("Jarvis jawab de raha hai...")
            lightScreenOnWake()
            speaker.speak(learnLine)
            armReplyFallback(learnLine)
            orbOverlay.setReply(learnLine)
            return
        }
        // v48: DUSRI BHASHA PAR VINAMRA MOD — Maya ke
        // session me Bangla-mukhi baat, Tara ke session
        // me Devanagari-mukhi baat: uspar koi kaam NAHI
        // hoga. Ek line bolkar (shaksiyat ki apni
        // bhasha me) session vinamrata se band — boli
        // khatm hote hi wake sunna phir chalu hoga
        // (onTtsFinished wala REPLY raasta)।
        personaRedirectLine(text)?.let { redirectLine ->
            pendingEndReason = if (sessionPersona == Persona.MAYA) {
                SESSION_END_REDIRECT_BN
            } else {
                SESSION_END_REDIRECT_HI
            }
            setStatus("Dusri bhasha ki baat — vinamra mod de raha hoon")
            updateNotification("Jarvis jawab de raha hai...")
            lightScreenOnWake()
            speaker.speak(redirectLine)
            armReplyFallback(redirectLine)
            orbOverlay.setReply(redirectLine)
            return
        }
        // v50: SIKHAYA HUA SHABD-KAAM — baat kisi
        // bandhe shabd ka akela roop ho ("লাইট")
        // to uska kaam seedha offline, mastishk
        // se pehle (shutdown ke baad ki sabse
        // pakki pehchan yahi hai)। Bandha
        // screenshot kaam chale to woh bhi
        // screen-kaam hai — taana session yahin
        // se shuru/aage (neeche wale pakke
        // screenshot raaste wala hi niyam)।
        TeachCommands.tryExecuteTaught(
            applicationContext, commandEngine, text, personaSpokenLang()
        )?.let { taughtExec ->
            if (taughtExec.actionId == TaughtCommands.ACTION_SCREENSHOT &&
                taughtExec.result.handled
            ) {
                enterContinuousSession()
                val shotDiag = try {
                    getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                        .getString("last_screenshot_command", null)
                } catch (t: Throwable) {
                    null
                }
                if (shotDiag != null &&
                    shotDiag.trimEnd().endsWith("OK")
                ) {
                    val freshShot = try {
                        JarvisAccessibilityService.instance
                            ?.lastCleanScreenshot
                    } catch (t: Throwable) {
                        null
                    }
                    if (freshShot != null) {
                        SessionShot.bitmap = freshShot
                        sessionShotTaken = true
                    }
                }
            }
            finishCommandResult(text, taughtExec.result)
            return
        }
        // v69: COMBO — baat kisi bandhe combo ka bol
        // ho ("গুড নাইট") ya "… কম্বোটা চালাও" ho to
        // dhap ek ke baad ek chalte hain aur hisaab
        // bola jata hai। Combo khud ASAL hukm hai —
        // chala to aam raasta aage nahi badhta
        // (sikhaye hue shabd wala hi niyam)।
        ComboCommands.tryRunCombo(
            applicationContext, commandEngine, text,
            personaSpokenLang(), allowScreenshot = true
        )?.let { comboLine ->
            finishCommandResult(text, CommandResult(comboLine, true))
            return
        }
        // v52: KAAM KE NIYAM — baat kisi bandhe
        // niyam ki shart se mile to uska kaam pehle
        // chup-chaap chalega (screenshot wala niyam
        // bhi yahan chalta hai — background wala
        // raasta), phir asal hukm apne aam raaste se
        // chalega। Return NAHI hota — niyam lagna
        // asal hukm ko rokta nahi।
        RuleCommands.tryApplyRule(
            applicationContext, commandEngine, text,
            personaSpokenLang(), allowScreenshot = true
        )
        addaNoSpeechCount = 0
        // v49: asal hukm ki koshish shuru — lagatar
        // na-samajhne ki ginati yahan se taaza (ye
        // hukm jud jaye to ginati zero hi rahegi;
        // na jude to handleSessionNotUnderstood use
        // yahin se aage badhayega)।
        sessionMissCount = 0
        // v36 (C): device wala hukm (torch / mobile data)
        // suna gaya ho to uska KACHHA text jarvis_diag me
        // band karo — Vosk ne asal me suna kya tha, woh
        // agle test me bina andaze ke dikhega.
        logDeviceCommandHeard(text)
        // v93: VOICE LANGUAGE SWITCH — "English-এ কথা
        // বলো" / "বাংলায় কথা বলো" / "language change
        // করো"। Naam ke bina bhashaon ki suchi wali
        // boli (background me dialog nahi khulta);
        // naam ke saath turant badal kar nayi bhasha
        // me pushti — agle turn se kataar (resetSystem
        // Cascade) aur Gemini dono nayi bhasha me।
        if (LanguageCommands.isBareChangeRequest(text)) {
            finishCommandResult(
                text, CommandResult(LanguageCommands.askWhichReply(), true)
            )
            return
        }
        LanguageCommands.extractLanguageSwitch(text)?.let { lang ->
            prefs.listenLanguage = lang.tag
            finishCommandResult(
                text, CommandResult(lang.confirmLine, true),
                languageTag = MayaLanguages.ttsTagFor(lang.tag)
            )
            return
        }
        // v92: lagatar scroll roko ("থামো" / "stop") —
        // baaki sabse PEHLE, taaki roko wala hukm kabhi
        // kisi aur kaam (gaana roko waghera) me na badle
        // aur scroll turant ruke। Scroll chal hi nahi
        // raha ho to chhota roko-vakya bhi yahin
        // handled hai; gaana/timer wala stop yahan
        // nahi phansta (CommandEngine ka guard)।
        commandEngine.stopContinuousScrollCommand(text)?.let { stopResult ->
            finishCommandResult(text, stopResult)
            return
        }
        // v36 (A): spasht OFF wala device hukm agent ke
        // paas kabhi nahi jayega — pakka offline raasta
        // yahin chalega. "বন্ধ করো" ka garble agent se
        // "chalu" bankar laut aata tha; spasht band kabhi
        // chalu nahi banna chahiye.
        commandEngine.deterministicOffCommand(text)?.let { offResult ->
            finishCommandResult(text, offResult)
            return
        }
        // v92: LAGATAR scroll — "স্ক্রল ডাউন করো" bolte
        // hi dheema scroll shuru hota hai aur "থামো"
        // bolne tak chalta rehta hai (ek baar wala
        // deterministicScrollCommand neeche fallback ki
        // tarah rehta hai — sikhaye hue kaam / combo
        // use seedha bulate hain, voice yahan se jati hai)।
        commandEngine.startContinuousScrollCommand(text)?.let { scrollResult ->
            finishCommandResult(text, scrollResult)
            return
        }
        // v92: VOICE TAP / CLICK — "এই ভিডিওটা ক্লিক
        // করো" / "[naam] ট্যাপ করো" — screen-agent ke
        // tap raaste se (naam se tap, warna sabse upar
        // wala bada video-jaisa hissa); na mile to
        // imaandaar "পেলাম না"।
        commandEngine.deterministicTapCommand(text)?.let { tapResult ->
            finishCommandResult(text, tapResult)
            return
        }
        // v92: VOICE BACK — "ব্যাক করো" / "পেছনে যাও"
        // — system back (GLOBAL_ACTION_BACK) saamne wali
        // screen par।
        commandEngine.deterministicBackCommand(text)?.let { backResult ->
            finishCommandResult(text, backResult)
            return
        }
        // v95: MAYA'S EYES — screen dekhna (describe / find /
        // read) — agent se pehle pakke raaste se। Bandhi
        // koshish (capture + Gemini vision) isi executor
        // thread par chalti hai, main thread par nahi —
        // screenshot wala hi niyam। Kram: read -> find ->
        // describe, taaki "read what's on the screen"
        // describe me na ghuse। Tap-by-description yahan
        // alag se nahi — woh deterministicTapCommand ke
        // andar vision fallback hai (naam se na mile to)।
        commandEngine.readScreenCommand(text)?.let { eyesResult ->
            finishCommandResult(text, eyesResult)
            return
        }
        commandEngine.findOnScreenCommand(text)?.let { eyesResult ->
            finishCommandResult(text, eyesResult)
            return
        }
        commandEngine.describeScreenCommand(text)?.let { eyesResult ->
            finishCommandResult(text, eyesResult)
            return
        }
        // v96: TOGGLE (WiFi / Bluetooth / brightness) + PASAND
        // KI SITE — agent se pehle pakke raaste se। Bandhi
        // koshish (QS tile latch / khoj) isi executor thread
        // par chalti hai, main thread par nahi — screenshot
        // wala hi niyam। Tile wala raasta hi sachha raasta
        // hai (seedha system API Android 10+ par band hai);
        // tile na mile to Settings panel kholo aur sach bolo।
        if (commandEngine.isToggleCommand(text)) {
            val toggleResult = commandEngine.runToggleCommand(text)
            finishCommandResult(text, toggleResult)
            return
        }
        if (commandEngine.isFavSiteCommand(text)) {
            val favResult = commandEngine.runFavSiteCommand(text)
            finishCommandResult(text, favResult)
            return
        }
        // v98: NOTIFICATION SE UTTAR — agent se pehle pakke
        // raaste se। Reply bhejna tez IPC hai (RemoteInput),
        // par niyam wahi — isi executor thread par, main
        // thread par nahi। Padhna / jawab / anumati — teeno
        // yahin; anumati na ho to sach bolkar Settings kholo।
        if (commandEngine.isNotificationCommand(text)) {
            val notifResult = commandEngine.runNotificationCommand(text)
            finishCommandResult(text, notifResult)
            return
        }
        // v38: pakka scroll hukm bhi agent se pehle pakke
        // offline raaste se — "স্ক্রল ডাউন করো" bolte hi
        // Accessibility swipe karegi, mastishk ke chakkar
        // me nahi padegi (saamne wali screen wala hi niyam).
        commandEngine.deterministicScrollCommand(text)?.let { scrollResult ->
            finishCommandResult(text, scrollResult)
            return
        }
        // v40: pakka SCREENSHOT hukm bhi agent se pehle
        // pakke offline raaste se — tasveer lekar gallery
        // me bachayi jati hai, jawab hukm ki bhasha me.
        // Bandhi koshish isi executor thread par chalti
        // hai (main thread par nahi — capture ka jawab
        // service ke main-executor se aata hai, isliye
        // yahan latch par intezaar surakshit hai)।
        if (commandEngine.isScreenshotCommand(text)) {
            val shotResult = commandEngine.runScreenshotCommand(text)
            // v49: SCREEN-KAAM chala — taana session
            // yahin se shuru (ya chal raha ho to aage
            // badha)। Tasveer asal me bach gayi ho
            // (runScreenshotCommand ki apni report
            // "-> OK" ho) to usi pal ki taaza tasveer
            // session ki godam me bhi bandh jaye —
            // baad me "ওই স্ক্রিনশটটা পাঠাও" par
            // share wala auzaar NAII tasveer lene ke
            // bajay yahi lega। Naya screenshot purane
            // ko badal deta hai (humesha sabse taaza
            // pakki wali rehti hai)।
            enterContinuousSession()
            val shotDiag = try {
                getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                    .getString("last_screenshot_command", null)
            } catch (t: Throwable) {
                null
            }
            if (shotDiag != null && shotDiag.trimEnd().endsWith("OK")) {
                val freshShot = try {
                    JarvisAccessibilityService.instance?.lastCleanScreenshot
                } catch (t: Throwable) {
                    null
                }
                if (freshShot != null) {
                    SessionShot.bitmap = freshShot
                    sessionShotTaken = true
                }
            }
            finishCommandResult(text, shotResult)
            return
        }
        // v36: play-iraade wala sanyojit hukm ("YouTube
        // khulo aur gaana chalao") — app-list use sirf
        // kholkar nigal na le. Agent uplabdh ho to agent
        // (uska null jawab seedhe youtube_play par girega),
        // warna seedha auzaar: gaana Gemini par nahi atka.
        val playQuery = PlayIntent.extractPlayQuery(text)
        if (!playQuery.isNullOrEmpty()) {
            if (geminiBrainAllowed()) {
                askAgentBrain(text, playQuery)
            } else {
                runDirectYouTubePlay(
                    text, playQuery, commandEngine.isBackgroundPlayRequest(text)
                )
            }
            return
        }
        // v48: LINK wala kaam (URL + kholne/browser ka
        // iraada) — agent se pehle pakka screen_task
        // raasta (saamne wali screen wala hi niyam,
        // [UrlIntent])। Kaam ka text user ki baat
        // bilkul waisi ki waisi — URL akshar-akshar
        // jaisa suna waisa hi jayega.
        val urlTask = UrlIntent.extractUrlTask(text)
        if (urlTask != null) {
            // v49: ye bhi screen-kaam hai — taana
            // session yahin se shuru/aage।
            enterContinuousSession()
            runDirectScreenTask(text, urlTask)
            return
        }
        // v67: PASTE / SEND-TEXT wala kaam (app + paste/
        // send verb) — agent se pehle pakka screen_task
        // raasta (saamne wali screen wala hi niyam,
        // [PasteSendIntent])। "পেস্ট করো" par mastishk
        // mana kar deta tha — ab woh raasta aata hi nahi।
        val pasteTask = PasteSendIntent.extractPasteSendTask(text)
        if (pasteTask != null) {
            // v49: ye bhi screen-kaam hai — taana
            // session yahin se shuru/aage।
            enterContinuousSession()
            runDirectPasteSendTask(text, pasteTask)
            return
        }
        // v74: sawal wali shakal me bhejne ka hukm ("তুমি
        // সেন্ড করে দেবে" / "আমি কেন সেন্ড বাটন প্রেস
        // করব") — app ka naam/text nahi hai, isliye
        // paste-route nahi pakadta; baatchit wala
        // mastishk is par bahana banata tha ("system
        // security")। Ab ye bhi pakka screen_task hai:
        // jo app khula hai usi me type hua text bhejna।
        val questionSendTask = PasteSendIntent.extractQuestionSendTask(text)
        if (questionSendTask != null) {
            // v49: ye bhi screen-kaam hai — taana
            // session yahin se shuru/aage।
            enterContinuousSession()
            runDirectPasteSendTask(text, questionSendTask)
            return
        }
        // v76: DEKHAO — "শাড়ি দেখতে চাই" / "কোরিয়ান শার্ট
        // দেখাও" (Google Images kholkar dikhana), "পছন্দ
        // হচ্ছে না" (dobara dhoondhna), "দাম কত / ডিটেলস
        // বলো" (tafseel bolna) — pakka deterministic raasta,
        // baatchit wala mastishk is par aata hi nahi
        // (ShowMeIntent dekho)। Ye bhi screen-kaam hai —
        // taana session yahin se shuru/aage।
        val showKind = ShowMeIntent.classify(
            text, ShowMeIntent.hasLastShown(applicationContext)
        )
        if (showKind != null) {
            enterContinuousSession()
            runDirectShowMe(text, showKind)
            return
        }
        // v78: CHHOBI BANAO — "X-এর ছবি বানাও" (Gemini se
        // AI tasveer banana), "আরেকটা বানাও" (dobara),
        // "শেয়ার করো" (aakhiri tasveer share) — pakka
        // deterministic raasta, baatchit wala mastishk is
        // par aata hi nahi (ImageIntent dekho)। Ye bhi
        // screen-kaam hai — taana session yahin se
        // shuru/aage।
        val imageKind = ImageIntent.classify(text)
        if (imageKind != null) {
            enterContinuousSession()
            runDirectImageGen(text, imageKind)
            return
        }
        // v79: LIVE KHOJ + KHOBOR — "X কী / X সম্পর্কে বলো /
        // X-এর দাম কত / X কবে / X কোথায়" (taaza web se
        // dhoondhkar pehle natije bolna), "আজকের খবর বলো"
        // (taaza khabrein sunana), "খুলে দাও" (aakhiri khoj
        // ka pehla natija kholna) — pakka deterministic
        // raasta, baatchit wala mastishk is par aata hi nahi
        // (WebSearchIntent dekho)। ShowMeIntent / ImageIntent
        // ke BAAD (v76 DETAILS "এটার দাম কত" aur v78 apna
        // raasta pehle lein), agent se pehle। Ye bhi
        // screen-kaam hai — taana session yahin se shuru/aage।
        val webKind = WebSearchIntent.classify(
            text, WebSearchIntent.hasLastSearch(applicationContext)
        )
        if (webKind != null) {
            enterContinuousSession()
            runDirectWebSearch(text, webKind)
            return
        }
        // v25: online brain uplabdh ho to sabse pehle AGENT —
        // app ke andar EK hi mastishk (user ka pakka design):
        // Gemini poori baat samajhta hai, online kadam khud
        // leta hai aur phone wale kadam offline auzaaron se
        // karwata hai, jab tak poora kaam khatm na ho jaye.
        // Adda tab hi pehle aayega jab agent uplabdh NA ho
        // (Offline mode / key nahi / net nahi) — ya agent ko
        // round 1 par koi model hi na mile (onAgentBrainAnswer
        // wala purana raasta, retry ke saath).
        if (geminiBrainAllowed()) {
            askAgentBrain(text)
            return
        }
        // v13: adda trigger ya chalu adda ho to utterance pehle
        // Maya ke AddaEngine ko jata hai — CommandEngine use beech
        // me nigal nahi sakta. Adda ke andar v12 retry nahi lagta.
        // v48: adda MAYA ki apni (Hindi) baatchit hai —
        // Tara ke Bangla session me woh kabhi nahi
        // chalegi (uski har line Hindi hai; Tara ke
        // session me Hindi bolna sakht-bhasha wale
        // hukm ke khilaaf hoga)।
        // v49: taana session ke andar bhi adda NAHI
        // chalega — session kaam ki haalat hai, uske
        // andar har baat hukm hi hai (mastishk uplabdh
        // ho to wahi baatchit bhi sambhalta hai)।
        // Session ke bahar adda bilkul pehle jaisa।
        if (sessionPersona == Persona.MAYA && !sessionActive &&
            addaEngine.shouldHandle(text)
        ) {
            handleAddaHeard(text)
            return
        }
        val rawResult = commandEngine.handle(text)
        if (!rawResult.handled) {
            // v97: OFFLINE MODE — mastishk uplabdh
            // hi nahi tha (Offline mode / key nahi /
            // net nahi) aur offline engine bhi na jod
            // paya to imaandaar wajah bolo — "বুঝতে
            // পারিনি" wala retry/veda yahan nahi।
            OfflineMode.reason(applicationContext)?.let { reason ->
                finishCommandResult(
                    text,
                    CommandResult(
                        OfflineMode.reply(reason, personaSpokenLang()), true
                    )
                )
                return
            }
        }
        finishCommandResult(text, rawResult)
    }

    /**
     * Engine ke result ka aakhiri faisla — v21 se pehle ye saara
     * kaam onCommandHeard ke andar hota tha; ab wahi code yahan
     * hai taaki agent/Gemini ke null jawab par bhi bilkul waisa
     * hi vyavhaar mile. Sirf executor thread se bulao; state
     * REPLY ho aur command session band ho chuka ho.
     */
    private fun finishCommandResult(
        text: String,
        rawResult: CommandResult,
        languageTag: String? = null
    ) {
        // v49: taana session ke andar suna-par-na-juda
        // hukm — v48 wala retry/vida jaap yahan NAHI
        // chalega: session apne sawal wale raaste par
        // jayega (pehle sawal, phir imaandaar line,
        // phir wapas "আর কিছু?" — session zinda)।
        if (sessionActive && !rawResult.handled) {
            handleSessionNotUnderstood()
            return
        }
        // v38: system recognizer se aaya hukm suna to gaya
        // par kisi kaam se nahi juda — purana do-retry
        // wala "বুঝতে পারিনি" jaap is raaste par nahi
        // hai: ek chhota prompt + ek khidki, phir vida
        // (handleSystemCommandMiss wala naya niyam).
        if (!rawResult.handled && currentTurnViaSystem) {
            handleSystemCommandMiss()
            return
        }
        // v12: "handled" ka faisla RAW result par hota hai — neeche
        // wale v1.10 CANT_DO conversion se PEHLE. Isliye galat-suna
        // kholne ka prayas ("pesbuk kholo" jaisa) bhi pehle apna ek
        // retry payega; can't-do wala bola jawab sirf tab aayega
        // jab retry wala prayas bhi kholne ka hi nikle aur asafal
        // rahe.
        if (!rawResult.handled && commandRetryCount < MAX_COMMAND_RETRIES) {
            startCommandRetry()
            return
        }
        // v1.10: kholne ki koshish thi par system me nahi hai —
        // chup band hone ke bajay saaf bol do ki nahi kar sakte.
        // Bina verb wali aawazein (shor/TV) pehle ki tarah chup
        // rahengi, taaki background bak-bak na kare.
        val result = if (!rawResult.handled && looksLikeOpenAttempt(text)) {
            CommandResult(CANT_DO_REPLY, true)
        } else {
            rawResult
        }
        if (!result.handled) {
            // Bug-2 fix: in background mode an unrecognized utterance
            // is almost always room noise, TV, or someone else talking.
            // Stay silent and resume wake listening instead of
            // announcing the "Phase 3" fallback to nobody.
            // v33: chup vida nahi — vida wali line bolkar jao.
            startClosingLine()
            return
        }
        setStatus("Command mila — jawab de raha hoon")
        updateNotification("Jarvis jawab de raha hai...")
        // v48: jawab bola ja raha hai — session ka ant
        // "completed" band karo (quota wale khaas
        // raaste ise bolne ke baad apni wajah se
        // badal sakte hain — onAgentBrainAnswer dekho)।
        pendingEndReason = SESSION_END_COMPLETED
        // v49: taana session chal raha ho to ye hukm
        // uske sandarbh me bandh jaye (agle hukm ke
        // ishare isiliye judte hain)।
        noteSessionOutcome(text, result.responseText)
        // v16: jawab bolna shuru — screen-wake ka samay badhao,
        // taaki jawab ke dauraan aur uske baad wale intezaar me
        // screen jala rahe.
        lightScreenOnWake()
        // v93: [languageTag] mile (nayi bhasha) to usi
        // ki TTS awaaz me — warna purana script wala
        // raasta (Bangla/Hindi/English pehle jaisa)।
        speaker.speak(result.responseText, languageTag)
        armReplyFallback(result.responseText)
        // v1.5: jawab gole ke upar wale bubble me text me bhi dikhe,
        // aur gola jawab ke dauraan chamak (shimmer) mode me rahe.
        orbOverlay.setReply(result.responseText)
    }

    /**
     * v21 Phase 3 gate: online brain kab bulaya jaye. Teenon
     * shartein — mode pure-Offline na ho (prefs wala mode hi
     * aakhiri shabd hai), user ne Gemini key di ho, aur is pal
     * net sach me chalu ho. Ek bhi na mile to Gemini ka naam
     * bhi nahi liya jata — purana offline raasta hi chalega.
     * v58: key kisi bhi ek provider ki ho — chain Gemini se
     * shuru hokar baaki provideron par jati hai।
     */
    private fun geminiBrainAllowed(): Boolean {
        if (prefs.mode == JarvisMode.OFFLINE) return false
        if (!ProviderChain.anyProviderConfigured(applicationContext)) return false
        return isNetworkUp()
    }

    /** Is pal internet uplabdh hai ya nahi (ConnectivityManager se). */
    private fun isNetworkUp(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * v25: sanyojit agent chalao. Auzaar background wale
     * CommandEngine se bandhe hain (overlay bridge wala
     * raasta) — agent ke phone-kadam bilkul waise hi
     * chalte hain jaise seedhe bole hue command chalte
     * the. State pehle se REPLY hai aur command session
     * band ho chuka hai; agent ki daud (net round + auzaar)
     * AgentBrain ke apne thread par chalti hai — is flow
     * ko koi timer nahi chhedta: reply fallback sirf kuch
     * BOLNE par arm hota hai, auto-resume sirf wakePaused
     * par chalta hai. Aakhiri jawab AgentBrain ke thread
     * se wapas executor par aata hai (onAgentBrainAnswer).
     * (v21 wala askGeminiBrain/chat raasta ab nahi hai —
     * agent uski jagah le chuka hai.)
     */
    /**
     * v36: [playQueryFallback] — play-iraade wale hukm ke
     * liye: agent ko round 1 par koi model na mile to
     * offline engine ki jagah seedha youtube_play chalega
     * (gaana Gemini ke jeene par nahi atkega).
     */
    private fun askAgentBrain(text: String, playQueryFallback: String? = null) {
        setStatus("Maya soch rahi hai — kaam kar rahi hai...")
        updateNotification("Maya soch rahi hai — kaam kar rahi hai...")
        val tools = AgentTools(
            applicationContext, commandEngine, fromBackgroundService = true
        )
        // v49: session wale bandhan auzaaron tak —
        // (a) hukm me saaf bhejne ka aadesh ho AUR
        // hum session ke andar hon to woh aadesh
        // screen-kaam tak jayega (Send pehle-se-
        // pusht); session ke bahar kabhi nahi।
        // (b) hukm purani session-tasveer ki taraf
        // ishara kare to share wala auzaar wahi
        // lega। (c) session sandarbh mastishk +
        // screen-kaam dono ko milega।
        // Auzaar-tukdi bandh rakho — jawab aane par
        // uske screenWorkUsed nishan se session
        // banega (onAgentBrainAnswer dekho)।
        tools.userDirectedSend = sessionActive &&
            SessionCommands.isSendInstruction(text)
        tools.reuseSessionScreenshot = sessionActive && sessionShotTaken &&
            SessionCommands.refersToEarlierScreenshot(text)
        val contextForRun = sessionContextText()
        tools.sessionContext = contextForRun
        lastBrainTools = tools
        // v48: is chain ki shaksiyat ka bhasha-bandhan
        // agent tak jata hai — Maya ke session me har
        // boli Hindi, Tara ke session me har boli
        // Bangla, user kisi bhi bhasha me bole.
        // v93: chuni hui bhasha Hindi/Bangla ho to wahi
        // bandhan (bilkul pehle jaisa); koi AUR bhasha
        // (Tamil, English, Spanish...) chuni ho to
        // usi ka tag jata hai — AgentBrain ka LANGUAGE
        // OVERRIDE use karta hai, aur shaksiyat wala
        // Hindi/Bangla override nahi lagta (MayaLanguages
        // .effectiveSessionTag dekho)।
        val effectiveTag =
            MayaLanguages.effectiveSessionTag(sessionPersona, prefs.listenLanguage)
        AgentBrain.run(
            applicationContext, geminiHistory.toList(), text, tools::execute,
            replyLanguage = if (MayaLanguages.isPersonaLanguage(effectiveTag)) {
                personaSpokenLang()
            } else {
                null
            },
            replyLanguageTag = effectiveTag,
            sessionContext = contextForRun
        ) { reply ->
            executor.execute { onAgentBrainAnswer(text, reply, playQueryFallback) }
        }
    }

    /**
     * Agent ka aakhiri jawab (ya uski anupasthiti). Jawab
     * mila to use ek aam handled jawab ki tarah bolo —
     * gola bubble, reply fallback, sab waisa hi; aur
     * baatchit yaad me jud jati hai. Jawab null ho (round
     * 1 par koi model hi nahi mila) to purana offline
     * raasta bilkul pehle jaisa: adda, phir engine, phir
     * finishCommandResult — retry ab bhi waisa hi bacha hai.
     */
    private fun onAgentBrainAnswer(
        text: String,
        reply: String?,
        playQueryFallback: String? = null
    ) {
        // Beech me service band ho gayi ho ya flow aage badh
        // gaya ho to der se aaya jawab chup-chaap chhod do.
        if (destroyed || state != State.REPLY) return
        // v49: is daud me mastishk ne screen-kaam
        // (screen_task / share_screenshot) chalaya
        // tha to ye SCREEN-KAAM wali baat thi — taana
        // session yahin se shuru hoga: jawab bola
        // jayega, phir aam ant ki jagah "আর কিছু?"
        // wala prompt aayega (onTtsFinished wala
        // bandobast)। Daud asafal model-haar par
        // giri ho (reply null, koi auzaar chala hi
        // nahi) to nishan false hi rahega — session
        // nahi banega, purana raasta chalega।
        if (lastBrainTools?.screenWorkUsed == true) {
            enterContinuousSession()
        }
        if (reply == null) {
            // v36: play wala hukm tha aur mastishk nahi mila —
            // gaana seedhe auzaar se bajega, offline engine
            // use sirf kholkar nahi chhodega.
            if (playQueryFallback != null) {
                runDirectYouTubePlay(
                    text, playQueryFallback, commandEngine.isBackgroundPlayRequest(text)
                )
                return
            }
            // v48: adda sirf MAYA ke session me (Hindi
            // baatchit) — Tara me kabhi nahi.
            // v49: taana session ke andar bhi nahi
            // (onCommandHeard wala hi niyam)।
            if (sessionPersona == Persona.MAYA && !sessionActive &&
                addaEngine.shouldHandle(text)
            ) {
                handleAddaHeard(text)
                return
            }
            val engineResult = commandEngine.handle(text)
            // v36: engine bhi na sambhal paye aur agent ki
            // haar saare modelon ke 429 (quota) se hui ho,
            // to retry/veda wale aam raaste ki jagah
            // imaandaar "online seema khatm" wala jawab —
            // user ki bhasha me, handled bankar (retry nahi).
            if (!engineResult.handled &&
                (GeminiBrain.lastChainWasQuota(applicationContext) ||
                    ProviderChain.lastChainWasQuota(applicationContext))
            ) {
                finishCommandResult(
                    text,
                    CommandResult(
                        // v48: kota line bhi shaksiyat
                        // ki pakki bhasha me hogi.
                        PlayIntent.quotaReply(personaSpokenLang()),
                        true
                    )
                )
                // v48: ant ki wajah "quota" — aam
                // "completed" se alag band karo.
                pendingEndReason = SESSION_END_QUOTA
                return
            }
            finishCommandResult(text, engineResult)
            return
        }
        // v42: prastuti wali chauki — jawab protocol JSON
        // bankar aaya ho to boli/bubble me sirf uski "say"
        // line jayegi, kaccha payload kabhi nahi (saamne
        // wale raaste wala hi niyam, MainActivity dekho)।
        // Yaad me bhi wahi saaf roop bandhta hai.
        // v48: chauki ki haar-line bhi shaksiyat ki
        // pakki bhasha me — bhasha-milan yahan bhi nahi.
        val safeReply = ReplyGuard.sanitize(
            reply, personaSpokenLang()
        )
        // Baatchit ki yaad: aakhiri 10 turn rakho, jodi todna mat.
        geminiHistory.add("user" to text)
        geminiHistory.add("model" to safeReply)
        while (geminiHistory.size > MAX_GEMINI_HISTORY_ENTRIES) {
            geminiHistory.removeAt(0)
            geminiHistory.removeAt(0)
        }
        setStatus("Online jawab mila — bol rahi hoon")
        updateNotification("Jarvis jawab de raha hai...")
        // v48: agent ka jawab bola ja raha hai — ant
        // "completed" band karo.
        pendingEndReason = SESSION_END_COMPLETED
        // v49: session chal raha ho to ye len-den
        // uske sandarbh me bhi bandh jaye।
        noteSessionOutcome(text, safeReply)
        // v16 wala screen-wake refresh, aam jawab ki tarah.
        // v93: nayi bhasha ka jawab usi ki TTS awaaz me
        // (Latin-lipi wale jawab script se nahi pehchane
        // jate — Bangla/Hindi par null = purana raasta)।
        lightScreenOnWake()
        speaker.speak(
            safeReply,
            MayaLanguages.ttsTagFor(
                MayaLanguages.effectiveSessionTag(sessionPersona, prefs.listenLanguage)
            )
        )
        armReplyFallback(safeReply)
        orbOverlay.setReply(safeReply)
    }

    /**
     * v36: bina mastishk ke seedha youtube_play — v26/v33
     * wali wahi mashinari (results kholo, pehle asli video
     * par gesture tap, watch page ki jaanch) AgentTools se.
     * Auzaar bandha (sync) hai aur tap ka intezaar lamba ho
     * sakta hai, isliye apne alag thread par chalega;
     * natija wapas executor par aakar aam jawab ki tarah
     * bola jayega (gola, reply fallback — sab waisa hi).
     * State REPLY pehle se hai aur command session band
     * hai, bilkul agent wale jawab jaisa mahaul.
     * Bola jaane wala natija PlayIntent se user ki bhasha
     * me aur hamesha sachha.
     */
    private fun runDirectYouTubePlay(
        text: String,
        query: String,
        backgroundNote: Boolean = false
    ) {
        setStatus("Gaana chala rahi hoon...")
        updateNotification("Gaana chala rahi hoon...")
        Thread {
            val tools = AgentTools(
                applicationContext, commandEngine, fromBackgroundService = true
            )
            val result = try {
                tools.execute("youtube_play", JSONObject().put("query", query))
            } catch (t: Throwable) {
                AgentTools.ToolResult(false, "FAILED — auzaar tak pahunch nahi payi.")
            }
            PlayIntent.saveDirectPlayDiag(applicationContext, query, result)
            // v48: natije ki boli bhi shaksiyat ki pakki
            // bhasha me — suna hua text kisi bhi script
            // me raha ho, Maya Hindi hi bolegi, Tara
            // Bangla hi.
            // v96: background me bajane ka hukm tha aur
            // gaana chal gaya ho to imaandaar Premium wali
            // line bhi jodo — bina Premium ke background
            // playback hota hi nahi, ye baat chhupani nahi।
            var line = PlayIntent.playOutcomeReply(
                result, query, personaSpokenLang()
            )
            if (backgroundNote && result.ok) {
                line += " " + commandEngine.backgroundPlayNote(personaSpokenLang())
            }
            executor.execute {
                if (destroyed || state != State.REPLY) return@execute
                setStatus("Seedha jawab mila — bol rahi hoon")
                updateNotification("Jarvis jawab de raha hai...")
                pendingEndReason = SESSION_END_COMPLETED
                // v49: session chal raha ho to gaane
                // wala len-den bhi sandarbh me bandhe
                // (prompt iske baad aam taur par aayega)।
                noteSessionOutcome(text, line)
                lightScreenOnWake()
                speaker.speak(line)
                armReplyFallback(line)
                orbOverlay.setReply(line)
            }
        }.start()
    }

    /**
     * v76: DEKHAO wala kaam ([ShowMeIntent]) — background
     * wala raasta (runDirectYouTubePlay wala hi bandobast):
     *  - SHOW: vishay nikaalo, online ho to mastishk se
     *    khoj-shabd behtar karwao (na mile to kachcha
     *    vishay hi), phir Google Images kholo;
     *  - REFINE: aakhiri vishay par agla khoj-roop kholo;
     *  - DETAILS: aakhiri vishay par online mastishk se
     *    tafseel bolkar sunao (offline ho to imaandaar
     *    manaahi)।
     * Browser background se [PhoneActions.openUrlFromBackground]
     * se khulta hai (OverlayLauncher wala pul — Android 10+
     * ki background-launch rok iske bina chup-chaap khulta
     * hi nahi)। Boli runDirectYouTubePlay jaisi hi:
     * executor par, state REPLY me, session ke sandarbh
     * ke saath। Mastishk wale jawab (GeminiBrain.chat)
     * apne executor thread par aate hain — boli hamesha
     * is service ke executor se।
     */
    private fun runDirectShowMe(text: String, kind: ShowMeIntent.Kind) {
        val lang = personaSpokenLang()
        when (kind) {
            ShowMeIntent.Kind.REFINE -> {
                val last = ShowMeIntent.loadLastShown(applicationContext)
                if (last == null) {
                    speakShowLine(ShowMeIntent.noTopicLine(kind, lang), text)
                    return
                }
                val (topic, variant) = last
                val nextVariant = (variant + 1) % ShowMeIntent.variantCount
                ShowMeIntent.saveLastShown(applicationContext, topic, nextVariant)
                openShowImagesBackground(
                    ShowMeIntent.buildVariantQuery(topic, nextVariant),
                    topic, kind, lang, text
                )
            }
            ShowMeIntent.Kind.DETAILS -> {
                val last = ShowMeIntent.loadLastShown(applicationContext)
                if (last == null) {
                    speakShowLine(ShowMeIntent.noTopicLine(kind, lang), text)
                    return
                }
                val (topic) = last
                if (!geminiBrainAllowed()) {
                    speakShowLine(ShowMeIntent.offlineDetailsLine(lang), text)
                    return
                }
                setStatus("Details bol rahi hoon...")
                updateNotification("Details bol rahi hoon...")
                GeminiBrain.chat(
                    applicationContext, emptyList(),
                    ShowMeIntent.detailsPrompt(topic, lang)
                ) { reply ->
                    val clean = reply
                        ?.let { ShowMeIntent.cleanDetailsReply(it) }
                        ?.takeIf { it.isNotEmpty() }
                    val line = clean ?: ShowMeIntent.offlineDetailsLine(lang)
                    speakShowLine(line, text)
                }
            }
            ShowMeIntent.Kind.SHOW -> {
                val item = ShowMeIntent.extractShowItem(text)
                if (item == null) {
                    speakShowLine(ShowMeIntent.askWhatLine(lang), text)
                    return
                }
                ShowMeIntent.saveLastShown(applicationContext, item, 0)
                if (geminiBrainAllowed()) {
                    setStatus("Soch rahi hoon — achhi tasveerein dhoondh rahi hoon...")
                    updateNotification("Soch rahi hoon...")
                    GeminiBrain.chat(
                        applicationContext, emptyList(),
                        ShowMeIntent.refineQueryPrompt(item)
                    ) { refined ->
                        val query = ShowMeIntent.sanitizeRefinedQuery(refined)
                            ?: item
                        openShowImagesBackground(
                            query, item, kind, lang, text
                        )
                    }
                } else {
                    openShowImagesBackground(item, item, kind, lang, text)
                }
            }
        }
    }

    /** v76: show-me wali boli — runDirectYouTubePlay wala hi bandobast. */
    private fun speakShowLine(line: String, userText: String) {
        executor.execute {
            if (destroyed || state != State.REPLY) return@execute
            setStatus("Seedha jawab mila — bol rahi hoon")
            updateNotification("Jarvis jawab de raha hai...")
            pendingEndReason = SESSION_END_COMPLETED
            noteSessionOutcome(userText, line)
            lightScreenOnWake()
            // v93: nayi bhasha ka jawab usi ki TTS awaaz me.
            speaker.speak(
                line,
                MayaLanguages.ttsTagFor(
                    MayaLanguages.effectiveSessionTag(sessionPersona, prefs.listenLanguage)
                )
            )
            armReplyFallback(line)
            orbOverlay.setReply(line)
        }
    }

    /** v76: Google Images background se kholo + hamesha sachha jawab bolo. */
    private fun openShowImagesBackground(
        query: String,
        topic: String,
        kind: ShowMeIntent.Kind,
        lang: PlayIntent.SpokenLang,
        userText: String
    ) {
        setStatus("Tasveerein khol rahi hoon...")
        updateNotification("Tasveerein khol rahi hoon...")
        Thread {
            val outcome = try {
                PhoneActions.openUrlFromBackground(
                    applicationContext, ShowMeIntent.imagesUrl(query)
                )
            } catch (t: Throwable) {
                LaunchOutcome.FAILED
            }
            val ok = outcome == LaunchOutcome.LAUNCHED
            ShowMeIntent.saveRouteDiag(applicationContext, kind, ok)
            val line = when (outcome) {
                LaunchOutcome.LAUNCHED ->
                    ShowMeIntent.showingLine(topic, lang)
                LaunchOutcome.LOCKED -> ShowMeIntent.lockedLine(lang)
                LaunchOutcome.PERMISSION_NEEDED ->
                    ShowMeIntent.permissionLine(lang)
                else -> ShowMeIntent.openFailedLine(lang)
            }
            speakShowLine(line, userText)
        }.start()
    }

    /**
     * v79: LIVE KHOJ + KHOBOR wala kaam ([WebSearchIntent]) —
     * background wala raasta (runDirectShowMe wala hi bandobast):
     *  - SEARCH: vishay nikaalo, DuckDuckGo se live dhoondho
     *    (apne thread par), pehle 3 natije bolkar sunao;
     *  - NEWS: Google News RSS se aaj ki top 5 khabar sunao;
     *  - OPEN: aakhiri khoj ka pehla natija browser me kholo.
     * Browser background se [PhoneActions.openUrlFromBackground]
     * se khulta hai (OverlayLauncher wala pul)। Boli
     * runDirectYouTubePlay jaisi hi: executor par, state
     * REPLY me, session ke sandarbh ke saath।
     */
    private fun runDirectWebSearch(text: String, kind: WebSearchIntent.Kind) {
        val lang = personaSpokenLang()
        when (kind) {
            WebSearchIntent.Kind.NEWS -> {
                setStatus("Aaj ki khabar la rahi hoon...")
                updateNotification("Aaj ki khabar la rahi hoon...")
                Thread {
                    val items = WebSearchIntent.fetchNews()
                    val line = if (items.isNullOrEmpty()) {
                        WebSearchIntent.newsFailLine(lang)
                    } else {
                        WebSearchIntent.newsSpoken(items, lang)
                    }
                    WebSearchIntent.saveRouteDiag(
                        applicationContext, kind, !items.isNullOrEmpty()
                    )
                    speakWebLine(line, text)
                }.apply { isDaemon = true }.start()
            }
            WebSearchIntent.Kind.SEARCH -> {
                val query = WebSearchIntent.extractSearchTopic(text)
                if (query == null) {
                    speakWebLine(WebSearchIntent.askTopicLine(lang), text)
                    return
                }
                setStatus("Internet me dhoondh rahi hoon...")
                updateNotification("Internet me dhoondh rahi hoon...")
                Thread {
                    val results = WebSearchIntent.searchWeb(query)
                    if (results.isNotEmpty()) {
                        WebSearchIntent.saveLastSearch(
                            applicationContext, query, results
                        )
                    }
                    val line = if (results.isEmpty()) {
                        WebSearchIntent.searchFailLine(lang)
                    } else {
                        WebSearchIntent.searchSpoken(query, results, lang)
                    }
                    WebSearchIntent.saveRouteDiag(
                        applicationContext, kind, results.isNotEmpty()
                    )
                    speakWebLine(line, text)
                }.apply { isDaemon = true }.start()
            }
            WebSearchIntent.Kind.OPEN -> {
                val last = WebSearchIntent.loadLastSearch(applicationContext)
                val first = last?.second?.firstOrNull()
                if (first == null) {
                    speakWebLine(WebSearchIntent.openNoSearchLine(lang), text)
                    return
                }
                openWebResultBackground(first, kind, lang, text)
            }
        }
    }

    /** v79: live khoj/khabar wali boli — runDirectYouTubePlay wala hi bandobast. */
    private fun speakWebLine(line: String, userText: String) {
        executor.execute {
            if (destroyed || state != State.REPLY) return@execute
            setStatus("Seedha jawab mila — bol rahi hoon")
            updateNotification("Jarvis jawab de raha hai...")
            pendingEndReason = SESSION_END_COMPLETED
            noteSessionOutcome(userText, line)
            lightScreenOnWake()
            // v93: nayi bhasha ka jawab usi ki TTS awaaz me.
            speaker.speak(
                line,
                MayaLanguages.ttsTagFor(
                    MayaLanguages.effectiveSessionTag(sessionPersona, prefs.listenLanguage)
                )
            )
            armReplyFallback(line)
            orbOverlay.setReply(line)
        }
    }

    /** v79: aakhiri khoj ka pehla natija background se kholo + hamesha sachha jawab bolo. */
    private fun openWebResultBackground(
        result: WebSearchIntent.WebResult,
        kind: WebSearchIntent.Kind,
        lang: PlayIntent.SpokenLang,
        userText: String
    ) {
        setStatus("Pehla natija khol rahi hoon...")
        updateNotification("Pehla natija khol rahi hoon...")
        Thread {
            val outcome = try {
                PhoneActions.openUrlFromBackground(
                    applicationContext, result.url
                )
            } catch (t: Throwable) {
                LaunchOutcome.FAILED
            }
            val ok = outcome == LaunchOutcome.LAUNCHED
            WebSearchIntent.saveRouteDiag(applicationContext, kind, ok)
            val line = when (outcome) {
                LaunchOutcome.LAUNCHED ->
                    WebSearchIntent.openedLine(result.title, lang)
                LaunchOutcome.LOCKED -> ShowMeIntent.lockedLine(lang)
                LaunchOutcome.PERMISSION_NEEDED ->
                    ShowMeIntent.permissionLine(lang)
                else -> WebSearchIntent.openFailedLine(lang)
            }
            speakWebLine(line, userText)
        }.start()
    }

    /**
     * v78: CHHOBI BANAO wala kaam ([ImageIntent]) — background
     * wala raasta (runDirectShowMe wala hi bandobast):
     *  - GENERATE: vishay nikaalo (na ho to "কিসের ছবি
     *    বানাবো?"), gate (Offline mode / Gemini key /
     *    net) paar karo, phir [ImageGen] se banao;
     *  - REGENERATE: aakhiri vishay par dobara, alag
     *    variation ke saath;
     *  - SHARE: aakhiri tasveer ka share screen
     *    OverlayLauncher wale pul se kholo.
     * Tasveer banti hai [ImageGen] ke executor thread
     * par — file likhna / gallery bachana usi callback
     * me; viewer OverlayLauncher se (Thread par, bilkul
     * openShowImagesBackground jaisa); boli hamesha
     * [speakImageLine] se (speakShowLine wala hi dhang)।
     *
     * EK FARQ (jaan-boojh kar): banate hue "বানাচ্ছি,
     * একটু দাঁড়াও" wali line yahan BOLI nahi jati —
     * sirf status / notification / orb me dikhti hai.
     * Wajah: background me har boli ke khatm hone par
     * onTtsFinished reply turn band karke "আর কিছু?"
     * wala prompt chalu kar deta hai — beech wali boli
     * se generation chalte hue hi turn khatm ho jata
     * aur aakhiri boli + viewer dono chhoot jate. Saamne
     * wali screen (MainActivity) me ye samasya nahi hai,
     * isliye wahan "বানাচ্ছি" turant boli jati hai.
     */
    private fun runDirectImageGen(text: String, kind: ImageIntent.Kind) {
        val lang = personaSpokenLang()
        when (kind) {
            ImageIntent.Kind.SHARE -> {
                val last = ImageIntent.loadLastImage(applicationContext)
                if (last == null) {
                    speakImageLine(ImageIntent.noImageLine(kind, lang), text)
                    return
                }
                val (path) = last
                val file = File(path)
                if (!file.exists()) {
                    speakImageLine(ImageIntent.noImageLine(kind, lang), text)
                    return
                }
                setStatus("শেয়ার করছি...")
                updateNotification("শেয়ার করছি...")
                Thread {
                    val chooser = ImageIntent.buildShareChooserIntent(
                        applicationContext, file, lang
                    )
                    val outcome = if (chooser == null) {
                        LaunchOutcome.FAILED
                    } else {
                        try {
                            OverlayLauncher.launch(applicationContext, chooser)
                        } catch (t: Throwable) {
                            LaunchOutcome.FAILED
                        }
                    }
                    val line = when (outcome) {
                        LaunchOutcome.LAUNCHED ->
                            ImageIntent.shareOpenedLine(lang)
                        LaunchOutcome.LOCKED -> ImageIntent.lockedLine(lang)
                        LaunchOutcome.PERMISSION_NEEDED ->
                            ImageIntent.permissionLine(lang)
                        else -> ImageIntent.shareFailedLine(lang)
                    }
                    speakImageLine(line, text)
                }.start()
            }
            ImageIntent.Kind.REGENERATE -> {
                val last = ImageIntent.loadLastImage(applicationContext)
                if (last == null) {
                    speakImageLine(ImageIntent.noImageLine(kind, lang), text)
                    return
                }
                val (_, prompt) = last
                startImageGenerationBackground(
                    prompt, variation = true, kind, lang, text
                )
            }
            ImageIntent.Kind.GENERATE -> {
                val prompt = ImageIntent.extractImagePrompt(text)
                if (prompt == null) {
                    speakImageLine(ImageIntent.askWhatLine(lang), text)
                    return
                }
                startImageGenerationBackground(
                    prompt, variation = false, kind, lang, text
                )
            }
        }
    }

    /** v78: tasveer wali boli — runDirectYouTubePlay wala hi bandobast. */
    private fun speakImageLine(line: String, userText: String) {
        executor.execute {
            if (destroyed || state != State.REPLY) return@execute
            setStatus("Seedha jawab mila — bol rahi hoon")
            updateNotification("Jarvis jawab de raha hai...")
            pendingEndReason = SESSION_END_COMPLETED
            noteSessionOutcome(userText, line)
            lightScreenOnWake()
            speaker.speak(line)
            armReplyFallback(line)
            orbOverlay.setReply(line)
        }
    }

    /** v78: gate paar karo, phir [ImageGen] se banao; natija bolkar + kholkar dikhao. */
    private fun startImageGenerationBackground(
        prompt: String,
        variation: Boolean,
        kind: ImageIntent.Kind,
        lang: PlayIntent.SpokenLang,
        userText: String
    ) {
        // Gate — saamne wale raaste wala hi (Offline mode /
        // Gemini key khaas taur par / net)। Har manaahi
        // imaandaar, alag line.
        if (prefs.mode == JarvisMode.OFFLINE) {
            speakImageLine(ImageIntent.offlineLine(lang), userText)
            ImageIntent.saveRouteDiag(applicationContext, kind, false)
            return
        }
        if (!GeminiBrain.isConfigured(applicationContext)) {
            speakImageLine(ImageIntent.noKeyLine(lang), userText)
            ImageIntent.saveRouteDiag(applicationContext, kind, false)
            return
        }
        if (!isNetworkUp()) {
            speakImageLine(ImageIntent.noNetLine(lang), userText)
            ImageIntent.saveRouteDiag(applicationContext, kind, false)
            return
        }
        val genLine = ImageIntent.generatingLine(lang)
        setStatus(genLine)
        updateNotification(genLine)
        try {
            orbOverlay.setReply(genLine)
        } catch (t: Throwable) {
            // Orb bonus hai — asal kaam isse nahi rukta.
        }
        ImageGen.generate(applicationContext, prompt, variation) { result ->
            when (result) {
                ImageGen.ImageGenResult.NoKey -> {
                    ImageIntent.saveRouteDiag(applicationContext, kind, false)
                    speakImageLine(ImageIntent.noKeyLine(lang), userText)
                }
                ImageGen.ImageGenResult.Quota -> {
                    ImageIntent.saveRouteDiag(applicationContext, kind, false)
                    speakImageLine(ImageIntent.quotaLine(lang), userText)
                }
                is ImageGen.ImageGenResult.Failed -> {
                    ImageIntent.saveRouteDiag(applicationContext, kind, false)
                    speakImageLine(ImageIntent.failedLine(lang), userText)
                }
                is ImageGen.ImageGenResult.Success -> {
                    // File IO yahin (callback thread) — viewer
                    // Thread par, boli speakImageLine se.
                    val file = ImageIntent.writeImageFile(
                        applicationContext, result.bytes, result.mimeType
                    )
                    if (file == null) {
                        ImageIntent.saveRouteDiag(applicationContext, kind, false)
                        speakImageLine(ImageIntent.failedLine(lang), userText)
                        return@generate
                    }
                    ImageIntent.saveLastImage(
                        applicationContext, file.absolutePath, prompt
                    )
                    ImageIntent.saveImageToGallery(
                        applicationContext, result.bytes
                    )
                    setStatus("ছবি খুলছি...")
                    updateNotification("ছবি খুলছি...")
                    Thread {
                        val viewIntent = ImageIntent.buildViewIntent(
                            applicationContext, file, result.mimeType
                        )
                        val outcome = if (viewIntent == null) {
                            LaunchOutcome.FAILED
                        } else {
                            try {
                                OverlayLauncher.launch(
                                    applicationContext, viewIntent
                                )
                            } catch (t: Throwable) {
                                LaunchOutcome.FAILED
                            }
                        }
                        val ok = outcome == LaunchOutcome.LAUNCHED
                        ImageIntent.saveRouteDiag(applicationContext, kind, ok)
                        val line = when (outcome) {
                            LaunchOutcome.LAUNCHED ->
                                ImageIntent.readyLine(lang)
                            LaunchOutcome.LOCKED -> ImageIntent.lockedLine(lang)
                            LaunchOutcome.PERMISSION_NEEDED ->
                                ImageIntent.permissionLine(lang)
                            else -> ImageIntent.viewFailedLine(lang)
                        }
                        speakImageLine(line, userText)
                    }.start()
                }
            }
        }
    }

    /**
     * v48: bina mastishk ke seedha screen_task — LINK
     * wala kaam ([UrlIntent]) background me bhi pakke
     * taur par yahin aata hai (saamne wali screen wala
     * hi niyam). Auzaar bandha (sync) hai aur kadam
     * lambe ho sakte hain, isliye apne thread par;
     * natija wapas executor par aakar aam jawab ki
     * tarah bola jayega. Bola jaane wala saar
     * [UrlIntent.spokenSummary] se — screen agent ke
     * apne shabd milein to wohi, warna shaksiyat ki
     * bhasha me natije ki imaandaar bandhi line.
     * State REPLY pehle se hai aur command session
     * band hai, bilkul agent wale jawab jaisa mahaul.
     */
    private fun runDirectScreenTask(text: String, task: String) {
        setStatus("Link wala kaam screen par kar rahi hoon...")
        updateNotification("Screen wala kaam chal raha hai...")
        Thread {
            val tools = AgentTools(
                applicationContext, commandEngine, fromBackgroundService = true
            )
            // v49: bulane wale ne session pehle hi
            // shuru kar diya hai (URL wala screen-kaam
            // session banata hai) — hukm ka bhejne
            // wala aadesh aur session sandarbh auzaar
            // tak jayein (askAgentBrain wala hi niyam)।
            tools.userDirectedSend = sessionActive &&
                SessionCommands.isSendInstruction(text)
            tools.sessionContext = sessionContextText()
            val result = try {
                tools.execute("screen_task", JSONObject().put("task", task))
            } catch (t: Throwable) {
                AgentTools.ToolResult(false, "FAILED — auzaar tak pahunch nahi payi.")
            }
            UrlIntent.saveRouteDiag(applicationContext, result.ok)
            val line = UrlIntent.spokenSummary(result, personaSpokenLang())
            executor.execute {
                if (destroyed || state != State.REPLY) return@execute
                setStatus("Screen wale kaam ka jawab mila — bol rahi hoon")
                updateNotification("Jarvis jawab de raha hai...")
                pendingEndReason = SESSION_END_COMPLETED
                // v49: session ke sandarbh me ye
                // len-den bhi bandh jaye।
                noteSessionOutcome(text, line)
                lightScreenOnWake()
                speaker.speak(line)
                armReplyFallback(line)
                orbOverlay.setReply(line)
            }
        }.start()
    }

    /**
     * v67: bina mastishk ke seedha screen_task — PASTE /
     * SEND-TEXT wala kaam ([PasteSendIntent]) background
     * me bhi pakke taur par yahin aata hai (saamne wali
     * screen wala hi niyam). Auzaar bandha (sync) hai
     * aur kadam lambe ho sakte hain, isliye apne thread
     * par; natija wapas executor par aakar aam jawab ki
     * tarah bola jayega. Bola jaane wala saar
     * [PasteSendIntent.spokenSummary] se — screen agent
     * ke apne shabd milein to wohi, warna shaksiyat ki
     * bhasha me natije ki imaandaar bandhi line.
     */
    private fun runDirectPasteSendTask(text: String, task: String) {
        setStatus("Lekha paste karke bhej rahi hoon...")
        updateNotification("Screen wala kaam chal raha hai...")
        Thread {
            val tools = AgentTools(
                applicationContext, commandEngine, fromBackgroundService = true
            )
            // v49: bulane wale ne session pehle hi
            // shuru kar diya hai (paste wala screen-kaam
            // session banata hai) — hukm ka bhejne
            // wala aadesh aur session sandarbh auzaar
            // tak jayein (askAgentBrain wala hi niyam)।
            // v66: sirf "পেস্ট করো" ho to jhanda nahi
            // lagta — paste hoga, bhejna tabhi jab
            // screen agent ko saaf aadesh mile।
            tools.userDirectedSend = sessionActive &&
                SessionCommands.isSendInstruction(text)
            // v74: pakka bhejna bhi usi aadesh se — send
            // wala kadam ab code chalata hai, model ka
            // mana use rok nahi sakta।
            tools.deterministicSend = sessionActive &&
                SessionCommands.isSendInstruction(text)
            tools.sessionContext = sessionContextText()
            val result = try {
                tools.execute("screen_task", JSONObject().put("task", task))
            } catch (t: Throwable) {
                AgentTools.ToolResult(false, "FAILED — auzaar tak pahunch nahi payi.")
            }
            PasteSendIntent.saveRouteDiag(applicationContext, result.ok)
            val line = PasteSendIntent.spokenSummary(result, personaSpokenLang())
            executor.execute {
                if (destroyed || state != State.REPLY) return@execute
                setStatus("Screen wale kaam ka jawab mila — bol rahi hoon")
                updateNotification("Jarvis jawab de raha hai...")
                pendingEndReason = SESSION_END_COMPLETED
                // v49: session ke sandarbh me ye
                // len-den bhi bandh jaye।
                noteSessionOutcome(text, line)
                lightScreenOnWake()
                speaker.speak(line)
                armReplyFallback(line)
                orbOverlay.setReply(line)
            }
        }.start()
    }

    /**
     * v36 (C): device hukm (torch / flashlight / mobile
     * data) ke liye recognizer ne KACHHA kya suna, woh
     * "jarvis_diag" me "last_device_heard" par band karo.
     * "Mic ne aakhri suna" (AppPrefs) har command ka text
     * rakhta hai; ye device-wali alag report isliye hai
     * taaki galat-suna OFF/ON wala garble agle test me
     * bina dhundhe dikh jaye. Sirf device wale shabd hon
     * tabhi likhte hain — aam baatchit isme nahi aati.
     */
    private fun logDeviceCommandHeard(text: String) {
        val lower = text.lowercase(Locale.ROOT)
        if (DEVICE_HEARD_WORDS.none { lower.contains(it) }) return
        try {
            getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                .edit()
                .putString("last_device_heard", text.trim())
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai — asal hukm isse nahi rukta.
        }
    }

    /**
     * v13 adda turn: state pehle hi REPLY hai aur command session
     * band ho chuka hai. Maya ka jawab bolo; uske baad wala raasta
     * onTtsFinished / reply fallback dekh lega — adda chalu ho to
     * agla command session, warna wapas wake.
     *
     * YouTube wala action koi shortcut se nahi hota: wahi purana
     * CommandEngine ("youtube kholo") background overlay bridge se
     * kholti hai. Uska jawab safal wala pakka jawab na ho to Maya ki
     * bani-banaai line ki jagah wahi sachha jawab bolo aur adda band
     * kar do — jhootha "khol diya" kabhi nahi.
     */
    private fun handleAddaHeard(text: String) {
        val step = addaEngine.respond(text)
        val spokenText = if (step.action == AddaAction.OPEN_YOUTUBE) {
            val launchResult = commandEngine.handle("youtube kholo")
            if (launchResult.responseText == "YouTube खोल दिया है।") {
                step.text
            } else {
                addaEngine.endSession()
                launchResult.responseText
            }
        } else {
            step.text
        }
        if (addaEngine.isActive) {
            setStatus("Adda chal raha hai — Maya sun rahi hai")
            updateNotification("Maya adda me hai — boliye...")
        } else {
            setStatus("Adda khatm — jawab de raha hoon")
            updateNotification("Jarvis jawab de raha hai...")
            // v48: adda is jawab ke saath band ho raha
            // hai — jawab hi uski vida hai, ant
            // "completed" band karo.
            pendingEndReason = SESSION_END_COMPLETED
        }
        // v16: adda ka jawab bolna shuru — screen-wake refresh.
        lightScreenOnWake()
        speaker.speak(spokenText)
        armReplyFallback(spokenText)
        orbOverlay.setReply(spokenText)
    }

    /**
     * v12 retry-once: "फिर से बोलिए" bolkar ACK wale raaste se
     * ek aur command session kholo (TTS khatm hone par, ya ack
     * fallback par). Gola dikhta rehta hai — dobara "Jarvis"
     * bolne ki zaroorat nahi. Sirf executor thread se bulao;
     * command session pehle hi band ho chuka ho.
     */
    private fun startCommandRetry() {
        commandRetryCount++
        state = State.ACK
        ackEndsConversation = false
        setStatus("Samajh nahi aaya — phir se boliye, dobara sun raha hoon")
        updateNotification("Phir se boliye...")
        // v16: retry prompt bhi ek bola hua jawab hai — screen jala rahe.
        lightScreenOnWake()
        // v33: prompt Bangla me tha — v48: ab prompt
        // SHAKSIYAT ki bhasha me hai (Maya = Hindi,
        // Tara = v33 wali Bangla line hi), kyunki
        // bhasha-milan hi is sanskaran ki jad hai.
        speaker.speak(personaRetryPrompt())
        armAckFallback()
    }

    /** Retry wala prompt — shaksiyat ki apni bhasha me, koshish ki ginati ke hisaab se. */
    private fun personaRetryPrompt(): String = when (sessionPersona) {
        Persona.MAYA -> if (commandRetryCount <= 1) {
            MAYA_RETRY_PROMPT_FIRST
        } else {
            MAYA_RETRY_PROMPT_SECOND
        }
        Persona.TARA -> if (commandRetryCount <= 1) {
            RETRY_PROMPT_FIRST
        } else {
            RETRY_PROMPT_SECOND
        }
    }

    /**
     * v33: saari koshishon ke baad chup vida nahi — ek
     * vinamra line bolkar wapas wake par jao (ACK wale
     * raaste se, taaki boli khatm hone par hi mic khule).
     * Sirf executor thread se bulao.
     *
     * v48: vida line ab SHAKSIYAT ki bhasha me hai
     * (purani bandhi Bangla line Tara ki hi hai; Maya
     * apni Hindi line bolegi) aur ant ki wajah
     * [reason] yahin bandhti hai — boli khatm hone par
     * onAckSpeechDone use "last_session_end" me
     * likhega. Koi bhi ant bina boli + bina wajah ke
     * nahi hoga.
     */
    private fun startClosingLine(
        reason: String = SESSION_END_NOT_UNDERSTOOD
    ) {
        closingReason = reason
        state = State.ACK
        ackEndsConversation = true
        setStatus("Samajh nahi aaya — wapas 'Jarvis' sun raha hoon")
        updateNotification("Jarvis sun raha hai — 'Jarvis' boliye")
        lightScreenOnWake()
        speaker.speak(personaCloseText())
        armAckFallback()
    }

    /**
     * v12: command session bina handled command ke khatm ho gaya
     * (recognizer error/timeout, timeout par khaali salvage, ya
     * session shuru hi nahi ho paya). Pehli baar par retry ka
     * mauka; retry ke baad purana chup vida — gola hatao aur
     * wapas wake sunne par jao. Sirf executor thread se bulao.
     */
    private fun handleCommandMiss(fromRecognizerError: Boolean = false) {
        if (destroyed) return
        stopCommandSession()
        if (sessionActive) {
            // v49: taana session me khaali/error wali
            // khidki maut NAHI hai — khidki phir kholo
            // (handleSystemCommandMiss wala hi niyam)।
            // Band sirf idle ghadi (15 minute ki
            // khamoshi) ya user ke apne hukm se hoga।
            reopenSessionWindow()
            return
        }
        if (addaEngine.isActive) {
            // v13: adda me "फिर से बोलिए" wala v12 retry nahi
            // chalega. Pehla khaali/error session chup-chaap ek aur
            // mauka; dusra lagatar khaali session adda band karke
            // wapas wake par. Asli utterance aate hi ginati reset
            // ho jati hai (onCommandHeard).
            // v48: dusra khaali session ab CHUP nahi marega —
            // vida-line bolkar band hoga (startClosingLine),
            // shaksiyat ki bhasha me, wajah ke saath.
            if (addaNoSpeechCount == 0) {
                addaNoSpeechCount = 1
                startCommandSession()
            } else {
                addaEngine.endSession()
                addaNoSpeechCount = 0
                startClosingLine(SESSION_END_ADDA_SILENT)
            }
            return
        }
        if (commandRetryCount < MAX_COMMAND_RETRIES) {
            startCommandRetry()
        } else {
            startClosingLine(
                if (fromRecognizerError) {
                    SESSION_END_RECOGNIZER_ERROR
                } else {
                    SESSION_END_NOT_UNDERSTOOD
                }
            )
        }
    }

    /** "X kholo" jaisi koshish — kisi bhi script me open-verb ho. */
    private fun looksLikeOpenAttempt(text: String): Boolean =
        text.contains("khol", ignoreCase = true) ||
            text.contains("open", ignoreCase = true) ||
            text.contains("खोल") || text.contains("খোল")

    // ------------------------------------------------------------------
    // Timers (main looper; each re-checks state on the executor)
    // ------------------------------------------------------------------

    /**
     * Command-session watchdog (Bug-2 fix). Ends the session when
     * EITHER the hard cap (~7 s total) is reached — no matter how many
     * partials keep arriving — OR ~2.5 s pass with no CHANGE in the
     * heard words. Continuous noise can no longer stretch a session.
     */
    private val commandWatchdogRunnable = object : Runnable {
        override fun run() {
            executor.execute {
                if (destroyed || state != State.COMMAND) return@execute
                val now = SystemClock.uptimeMillis()
                val totalMs = now - commandSessionStartMs
                val silenceMs = now - commandLastChangeMs
                if (totalMs >= COMMAND_HARD_CAP_MS ||
                    silenceMs >= COMMAND_SILENCE_TIMEOUT_MS
                ) {
                    // v1.9: session khatm hone par aakhiri suna hua
                    // tukda (partial) bekaar mat jane do — use bhi
                    // command ki tarah aazmao. Na mile to
                    // onCommandHeard khud pehle ki tarah chup
                    // wapas wake par chala jayega; mile to kaam
                    // hoga — dheere bolne par bhi command bachega.
                    val lastPartial = commandLastPartial
                    if (lastPartial.isNotBlank()) {
                        // v12 diagnostics: bachaya gaya partial bhi
                        // "Mic ne aakhri suna" me likho. Dhyaan rahe:
                        // ye salvage ISI prayas ka hissa hai — retry
                        // nahi khaata; partial asafal nikle to
                        // onCommandHeard ke andar retry ab bhi milega.
                        rememberCommandFinal(lastPartial)
                        onCommandHeard(dialectTranslated(lastPartial))
                    } else {
                        // v12: khaali gaya session bhi pehle ek
                        // retry payega, phir hi wapas wake par.
                        handleCommandMiss()
                    }
                } else {
                    mainHandler.postDelayed(this, COMMAND_WATCHDOG_TICK_MS)
                }
            }
        }
    }

    private fun armCommandWatchdog() {
        mainHandler.removeCallbacks(commandWatchdogRunnable)
        mainHandler.postDelayed(commandWatchdogRunnable, COMMAND_WATCHDOG_TICK_MS)
    }

    private fun disarmCommandWatchdog() {
        mainHandler.removeCallbacks(commandWatchdogRunnable)
    }

    private val ackFallbackRunnable = Runnable {
        executor.execute {
            if (!destroyed && state == State.ACK) {
                onAckSpeechDone()
            }
        }
    }

    private fun armAckFallback() {
        mainHandler.removeCallbacks(ackFallbackRunnable)
        mainHandler.postDelayed(ackFallbackRunnable, ACK_FALLBACK_MS)
    }

    private fun disarmAckFallback() {
        mainHandler.removeCallbacks(ackFallbackRunnable)
    }

    private val replyFallbackRunnable = Runnable {
        executor.execute {
            if (!destroyed && state == State.REPLY) {
                if (addaEngine.isActive) {
                    // v13: TTS completion callback kabhi kho jaye to
                    // bhi adda wahi se aage badhe, wake par na gire.
                    startCommandSession()
                } else if (sessionActive && ScreenConfirm.hasPending()) {
                    // v49: onTtsFinished wala hi session
                    // niyam — latke sawal ka jawab session
                    // ki apni khidki se aayega।
                    startCommandSession()
                } else if (sessionActive) {
                    // v49: onTtsFinished wala hi session
                    // niyam — jawab bola mana gaya, ab
                    // "আর কিছু?" wala prompt chalega।
                    promptSessionMore()
                } else {
                    // v48: jawab bola mana gaya (TTS-finish
                    // callback kho gaya tha) — ant yahin
                    // band karo, bilkul onTtsFinished jaise.
                    endSession(pendingEndReason)
                    pendingEndReason = SESSION_END_COMPLETED
                    orbOverlay.hideAfter(ORB_REPLY_LINGER_MS)
                    startWakeSession()
                }
            }
        }
    }

    private fun armReplyFallback(spokenText: String) {
        mainHandler.removeCallbacks(replyFallbackRunnable)
        val delay = (REPLY_FALLBACK_BASE_MS + spokenText.length * REPLY_FALLBACK_PER_CHAR_MS)
            .coerceAtMost(REPLY_FALLBACK_MAX_MS)
        // v36: boli ke khatm hone ka andaz wala samay pehle
        // se likh do — finish-callback (onTtsFinished) aaye
        // to woh asal samay se use badal dega; callback kho
        // jaye aur fallback wala raasta chale to bhi goonj
        // wali khidki (self-hearing guard) is andaz tak
        // band rahegi.
        val estimatedEnd = SystemClock.uptimeMillis() + delay
        if (estimatedEnd > lastSpeechEndMs) {
            lastSpeechEndMs = estimatedEnd
        }
        mainHandler.postDelayed(replyFallbackRunnable, delay)
    }


    private fun disarmReplyFallback() {
        mainHandler.removeCallbacks(replyFallbackRunnable)
    }

    // ------------------------------------------------------------------
    // Siri orb overlay (v1.5)
    //
    // Ek chhoti si floating khidki (TYPE_APPLICATION_OVERLAY):
    // neeche beech me OrbView ka gola, uske upar ek gola-kaand
    // (rounded) kala bubble jisme jawab text dikhta hai. Window
    // NOT_FOCUSABLE + NOT_TOUCHABLE hai — neeche wali app ke taps
    // kabhi nahi rukte. "Display over other apps" permission na
    // ho to show() chup-chaap kuch nahi karta; awaaz wala flow
    // pehle jaisa hi chalta hai. Ye khidki OverlayLauncher ki
    // 1x1 launch-khidki se alag hai — dono saath reh sakte hain.
    //
    // Saare window kaam main thread par hote hain; bahari calls
    // (executor thread se) mainHandler par post ho jate hain.
    // ------------------------------------------------------------------

    /**
     * v91: overlay gola SIRF tab jab Maya sakriya roop se sun/
     * bol rahi ho — wake word ke baad ka active session
     * (ACK = haan bol rahi, COMMAND = hukm sun rahi, REPLY =
     * jawab bol rahi)। WAKE (wake word ka intezaar — bas
     * background me chalu) ya LOADING/STOPPED me nahi।
     * Overlay dikhane ke dono raaste (ACTION_OVERLAY_SHOW
     * aur showOnMain) isi se gate hote hain।
     */
    private fun isActivelyListening(): Boolean =
        state == State.ACK || state == State.COMMAND || state == State.REPLY

    private inner class OrbOverlay {

        private var windowManager: WindowManager? = null
        private var container: LinearLayout? = null
        private var orbView: OrbView? = null
        private var bubble: TextView? = null
        private var attached = false

        // Har show/hide par badhta hai, taaki kisi purane fade-out
        // ka end-action naye show ko galti se na uda de.
        private var generation = 0

        private val hideRunnable = Runnable { fadeOutOnMain() }

        /** Gola dikhao — command ka intezaar (breathing) mode me. */
        fun show() {
            mainHandler.post { showOnMain() }
        }

        /** Jawab bubble me dikhao aur gole ko shimmer mode me dalo. */
        fun setReply(text: String) {
            mainHandler.post {
                // v89: app ke ANDAR jawab ka bubble nahi — chhota
                // gola + bubble sirf app ke BAHAR mini app।
                // Jawab boli (TTS) se sunai deta hi hai।
                if (MainActivity.isMainForeground) {
                    removeWindowOnMain()
                    return@post
                }
                if (!attached) showOnMain()
                if (!attached) return@post
                bubble?.text = text
                bubble?.visibility = View.VISIBLE
                orbView?.setMode(OrbView.Mode.SPEAKING)
            }
        }

        /** Kuch der baad gola hatao (jawab ke baad wala thahraav). */
        fun hideAfter(delayMs: Long) {
            mainHandler.removeCallbacks(hideRunnable)
            mainHandler.postDelayed(hideRunnable, delayMs)
        }

        /** Gola abhi hatao (fade-out ke saath). */
        fun hideNow() {
            mainHandler.post { fadeOutOnMain() }
        }

        /** Teardown ke liye: bina fade ke, isi pal hatao. */
        fun removeImmediately() {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                removeWindowOnMain()
            } else {
                mainHandler.post { removeWindowOnMain() }
            }
        }

        private fun showOnMain() {
            // v89: MainActivity saamne ho to chhota gola mat
            // dikhao — woh sirf app ke BAHAR mini app ki tarah
            // dikhega। Laga hua ho to hata bhi do (user app
            // kholkar aa gaya)।
            if (MainActivity.isMainForeground) {
                removeWindowOnMain()
                return
            }
            // v91: sakriya session nahi (bas background me wake
            // word ka intezaar) — gola mat dikhao; laga hua ho
            // to hata bhi do। onWakeDetected (ACK) aur setReply
            // (COMMAND/REPLY) wale raaste is jaanch se guzarte
            // hain, isliye sakriya session ka gola chalta hai।
            if (!isActivelyListening()) {
                removeWindowOnMain()
                return
            }
            if (!Settings.canDrawOverlays(this@JarvisWakeService)) {
                // Permission nahi: gola nahi, par kuch tootta nahi.
                return
            }
            generation++
            mainHandler.removeCallbacks(hideRunnable)
            if (!attached) {
                buildViews()
                val wm = windowManager ?: return
                val root = container ?: return
                try {
                    wm.addView(root, overlayLayoutParams())
                } catch (t: Throwable) {
                    // Permission beech me wapas li gayi ho, aadi.
                    return
                }
                attached = true
            }
            val root = container ?: return
            root.animate().cancel()
            bubble?.visibility = View.GONE
            orbView?.setMode(OrbView.Mode.LISTENING)
            orbView?.startAnimations()
            root.alpha = 0f
            root.animate().alpha(1f).setDuration(ORB_FADE_IN_MS).start()
        }

        private fun fadeOutOnMain() {
            if (!attached) return
            val gen = ++generation
            val root = container ?: return
            root.animate().cancel()
            root.animate().alpha(0f).setDuration(ORB_FADE_OUT_MS)
                .withEndAction {
                    if (gen == generation) removeWindowOnMain()
                }
                .start()
        }

        private fun removeWindowOnMain() {
            generation++
            mainHandler.removeCallbacks(hideRunnable)
            container?.animate()?.cancel()
            orbView?.stopAnimations()
            if (attached) {
                try {
                    windowManager?.removeView(container)
                } catch (t: Throwable) {
                    // Pehle se hata hua: kuch nahi karna.
                }
                attached = false
            }
        }

        private fun buildViews() {
            if (container != null) return
            val service = this@JarvisWakeService
            windowManager =
                service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

            val bubbleView = TextView(service).apply {
                textSize = 14f
                setTextColor(Color.WHITE)
                maxWidth = dp(300)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = GradientDrawable().apply {
                    setColor(0xD91C1C28.toInt())
                    cornerRadius = dp(18).toFloat()
                    setStroke(1, 0x40FFFFFF)
                }
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            }

            // v1.11: gola thoda bada (104 -> 148dp) — pehle wala
            // bahut chhota lagta tha.
            val orb = OrbView(service).apply {
                layoutParams = LinearLayout.LayoutParams(dp(148), dp(148))
            }

            container = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(10), dp(10), dp(10), dp(4))
                addView(bubbleView)
                addView(orb)
            }
            bubble = bubbleView
            orbView = orb
        }

        private fun overlayLayoutParams(): WindowManager.LayoutParams =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(48)
            }

        private fun dp(value: Int): Int =
            (value * resources.displayMetrics.density).toInt()
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Jarvis background sunna",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Jab Jarvis background me wake word sun raha hota hai"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, JarvisWakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("ASHIP JARVIS AI")
            .setContentText(text)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Band karo", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundWith(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
                return
            } catch (t: Throwable) {
                // Fall through to the untyped call below.
            }
        }
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(text: String) {
        if (destroyed) return
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun setStatus(message: String) {
        statusMessage = message
    }

    private fun rememberWakePartial(text: String) {
        val clean = text.trim().replace('\n', ' ')
        if (clean.length > longestWakePartialSinceFinal.length) {
            longestWakePartialSinceFinal = clean
            prefs.lastHeardPartialText = clean
            prefs.lastHeardPartialAtMs = System.currentTimeMillis()
        }
    }

    private fun rememberWakeFinal(text: String) {
        val clean = text.trim().replace('\n', ' ')
        if (clean.isEmpty()) return
        prefs.lastHeardText = clean
        prefs.lastHeardAtMs = System.currentTimeMillis()
        longestWakePartialSinceFinal = ""
        prefs.lastHeardPartialText = ""
        prefs.lastHeardPartialAtMs = 0L
    }

    /**
     * v12 diagnostics: wake listener ki tarah, command session ne
     * jo suna (final result, ya timeout par bachaya gaya partial)
     * wahi last-heard fields me likho — taaki "Mic ne aakhri suna"
     * line command ki pehchan bhi dikhaye, sirf wake wali nahi.
     */
    private fun rememberCommandFinal(text: String) {
        val clean = text.trim().replace('\n', ' ')
        if (clean.isEmpty()) return
        // v97: DIALECT sudhaar ke liye pichli baat
        // bacha lo — nayi baat likhne se pehle।
        previousCommandFinalText = prefs.lastHeardText.takeIf { it.isNotBlank() }
        prefs.lastHeardText = clean
        prefs.lastHeardAtMs = System.currentTimeMillis()
    }

    /**
     * v97: DIALECT anuvaad — suni hui baat kisi
     * bandhe bol-chaal roop se mile to uska asal
     * hukm; warna baat jaise ki taise। [DialectMap]
     * wali jhaank [DialectCommands] ki surakshit
     * khidki se hoti hai — kuch galat ho to null
     * hi milega, asal raasta kabhi nahi rukega।
     */
    private fun dialectTranslated(text: String): String {
        return DialectCommands.translateOrNull(applicationContext, text) ?: text
    }

    private fun containsWakeWord(text: String): Boolean {
        if (text.isBlank()) return false
        val squashedText = squashWakeText(text)
        if (squashedText.isEmpty()) return false
        val variants = WAKE_WORDS.map { squashWakeText(it) }
        if (variants.any { squashedText.contains(it) }) return true

        // Fuzzy word match: one small spelling/recognition slip in a
        // word is enough, but unrelated short words never qualify.
        val words = text.split(Regex("\\s+"))
            .map { squashWakeText(it) }
            .filter { it.length >= 5 }
        for (word in words) {
            for (variant in variants) {
                if (variant.length >= 5 && levenshteinDistance(word, variant) <= 1) {
                    return true
                }
            }
        }
        return containsMayaCall(text)
    }

    /**
     * v13: dusra wake naam "Maya". Jarvis wala purana substring
     * raasta is par lagu NAHI hai — naam hamesha ek alag shabd
     * (token) ki tarah mile tabhi wake hoga, taaki "মায়াবী" /
     * "mayabi" jaise lambe shabd galti se na jaga dein. Latin roop
     * par wahi distance-1 wali fuzzy chhoot hai; script roop
     * squash ke baad chhote ho jate hain, isliye unpar pakka token
     * match hi kaafi hai.
     */
    private fun containsMayaCall(text: String): Boolean {
        val variants = MAYA_WAKE_WORDS.map { squashWakeText(it) }.toSet()
        val tokens = text.split(Regex("\\s+"))
            .flatMap { it.split('-') }
            .map { squashWakeText(it) }
            .filter { it.isNotEmpty() }
        return tokens.any { token ->
            token in variants ||
                (token.length >= 4 && variants.any { variant ->
                    variant.all { ch -> ch.code < 128 } &&
                        levenshteinDistance(token, variant) <= 1
                })
        }
    }

    /**
     * v48: teesra wake naam "Tara" (তারা) — par Maya
     * wale token-niyam se SAKHT sthan-niyam ke saath:
     * "তারা" aam Bangla me "ve log" bhi hota hai,
     * isliye naam SIRF baat ki SHURUAAT me (ya akela)
     * mile tabhi wake hoga; beech-wakya wala তারা
     * kabhi nahi jagayega. Shuruaat me "হে"/"hey"/"আরে"
     * jaisa pukar-shabd ho to uske theek baad wala
     * token bhi chalega. Latin roop par distance-1
     * wali fuzzy chhoot Maya wali hi hai; script
     * roopon par pakka token match hi hai (squash ke
     * baad ve chhote ho jate hain — Maya wala nota).
     * Vosk Hindi use "तारा" sunta hai — Devanagari,
     * Bangla aur Latin, teeno roop kataar me hain.
     */
    private fun containsTaraCall(text: String): Boolean {
        val variants = TARA_WAKE_WORDS.map { squashWakeText(it) }.toSet()
        val tokens = text.split(Regex("\\s+"))
            .flatMap { it.split('-') }
            .map { squashWakeText(it) }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        fun isTara(token: String): Boolean =
            token in variants ||
                (token.length >= 4 && variants.any { variant ->
                    variant.all { ch -> ch.code < 128 } &&
                        levenshteinDistance(token, variant) <= 1
                })
        if (isTara(tokens[0])) return true
        // "হে তারা ..." — pukar-shabd ke baad wala naam.
        val particles = WAKE_PARTICLES.map { squashWakeText(it) }.toSet()
        return tokens.size >= 2 && tokens[0] in particles &&
            isTara(tokens[1])
    }

    /**
     * v48: suna hua text kis shaksiyat ko jaga raha
     * hai — Tara ki sthan-bandhi pehchan PEHLE (jo
     * baat ki shuruaat me naam lekar bulaye, usi ki
     * shaksiyat), phir purana Jarvis/Maya wala raasta
     * -> MAYA. Kuch na mile to null (wake nahi).
     */
    private fun detectWakePersona(text: String): Persona? {
        if (text.isBlank()) return null
        if (containsTaraCall(text)) return Persona.TARA
        if (containsWakeWord(text)) return Persona.MAYA
        return null
    }

    private fun squashWakeText(text: String): String {
        val result = StringBuilder()
        for (ch in text.lowercase()) {
            if (ch.isLetterOrDigit()) result.append(ch)
        }
        return result.toString()
    }

    private fun levenshteinDistance(first: String, second: String): Int {
        if (kotlin.math.abs(first.length - second.length) > 1) return 2
        var previous = IntArray(second.length + 1) { it }
        var current = IntArray(second.length + 1)
        for (i in 1..first.length) {
            current[0] = i
            for (j in 1..second.length) {
                val substitutionCost = if (first[i - 1] == second[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + substitutionCost
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[second.length]
    }

    /** Vosk hypotheses are JSON strings like {"text" : "youtube kholo"}. */
    private fun parseJsonField(json: String?, field: String): String {
        if (json.isNullOrBlank()) return ""
        return try {
            JSONObject(json).optString(field, "")
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        const val ACTION_START = "com.aship.jarvis.action.WAKE_START"
        const val ACTION_STOP = "com.aship.jarvis.action.WAKE_STOP"
        const val ACTION_PAUSE_WAKE = "com.aship.jarvis.action.WAKE_PAUSE"
        const val ACTION_RESUME_WAKE = "com.aship.jarvis.action.WAKE_RESUME"

        /** v51 (item 6a): saamne wali screen mic lene se pehle bheja jaane wala "mic chhodo" sanket। */
        const val ACTION_YIELD_MIC = "com.aship.jarvis.action.WAKE_YIELD_MIC"

        private const val NOTIFICATION_ID = 4201
        private const val CHANNEL_ID = "jarvis_wake_channel"
        private const val SAMPLE_RATE = 16000.0f

        /**
         * v50: wake wali haan — user ki bandhi
         * line (2026-11-03): Maya Hindi me "জি
         * বস, বলিয়ে" — Devanagari roop hi bola
         * jata hai, taaki hi-IN awaaz sahi bole।
         * Naam bandha ho tab bhi ye line NAHI
         * badalti (bandhi hai)।
         */
        private const val ACK_TEXT = "जी बॉस, बोलिए"

        /** v50: Tara ki haan — user ki bandhi line: "জি বস, বলো"। */
        private const val TARA_ACK_TEXT = "জি বস, বলো"

        /** v12: command na samajhne par ek baar dobara mauka dene ka prompt. */
        // v33: retry prompts Bangla me (malik ki bhasha);
        // TtsSpeaker script dekhkar Bangla awaaz lagayega.
        // v48: ye dono ab TARA ke prompt hain; Maya ke
        // Hindi prompt neeche alag hain — har shaksiyat
        // sirf apni bhasha bolegi.
        private const val RETRY_PROMPT_FIRST = "বুঝতে পারিনি — আরেকবার বলো তো।"
        private const val RETRY_PROMPT_SECOND = "আরেকবার একটু আস্তে বলো তো — শুনছি।"
        private const val MAYA_RETRY_PROMPT_FIRST = "समझ नहीं आया — एक बार फिर बोलिए।"
        private const val MAYA_RETRY_PROMPT_SECOND = "एक बार फिर, थोड़ा धीरे बोलिए — सुन रही हूँ।"

        /** v48: vida (closing) lines — har shaksiyat ki apni bhasha me. */
        private const val MAYA_CLOSE_TEXT =
            "ठीक है, मैं जाती हूँ — जब याद आए तो 'माया' या 'जार्विस' बोलकर बुला लेना।"
        private const val TARA_CLOSE_TEXT =
            "ঠিক আছে, আমি যাচ্ছি — মনে পড়লে 'তারা' বলে ডেকো।"

        /**
         * v48: dusri bhasha par vinamra mod (redirect)
         * wali pakki line — Maya ke session me Bangla
         * baat aaye to Hindi me ye, Tara ke session me
         * Hindi baat aaye to Bangla me woh.
         */
        private const val MAYA_REDIRECT_TEXT =
            "मैं सिर्फ़ हिंदी समझती हूँ। बांग्ला के लिए 'तारा' कहकर बुलाइए।"
        private const val TARA_REDIRECT_TEXT =
            "আমি শুধু বাংলা বুঝি — হিন্দির জন্য 'মায়া' বলে ডাকো।"

        /** v48: "last_session_end" report ki wajahen. */
        private const val SESSION_END_COMPLETED = "completed"
        private const val SESSION_END_NOT_UNDERSTOOD = "not-understood"
        private const val SESSION_END_REDIRECT_BN = "redirect-bn"
        private const val SESSION_END_REDIRECT_HI = "redirect-hi"
        private const val SESSION_END_RECOGNIZER_ERROR = "recognizer-error"
        private const val SESSION_END_QUOTA = "quota"
        private const val SESSION_END_SCREEN_CONFIRM = "screen-confirm"
        private const val SESSION_END_ADDA_SILENT = "adda-silent"
        private const val SESSION_END_WEDGED = "wedged-recovered"

        /** v49: taana session ke ant ki wajahen (usi report me). */
        private const val SESSION_END_DONE = "session-done"
        private const val SESSION_END_IDLE = "session-idle"

        /**
         * v50: shutdown wala ant — session ho ya
         * na ho, wajah hamesha yahi bandhti hai
         * ("shutdown-silent": gola gayab, boli
         * bilkul nahi)। v49 ki "session-shutdown"
         * isse badal gayi।
         */
        private const val SESSION_END_SHUTDOWN_SILENT = "shutdown-silent"

        /**
         * v75: FULL SHUTDOWN wala ant — "কমপ্লিট" bolkar
         * background sunna + poora process band। Report
         * me yahi wajah bandhti hai।
         */
        private const val SESSION_END_FULL_SHUTDOWN = "full-shutdown"

        /** v75: band hone se pehle bola jaane wala ek shabd। */
        private const val FULL_SHUTDOWN_SPEAK_TEXT = "কমপ্লিট"

        /**
         * v75: "কমপ্লিট" ki boli khatm hone ka intezaar
         * — itni der me callback na aaye to safety timer
         * khud maar dega (boli atakne par bhi shutdown
         * rukega nahi)। Boli abhi chal rahi ho to timer
         * 3 s aage badhta hai, kul 15 s tak।
         */
        private const val FULL_SHUTDOWN_KILL_TIMEOUT_MS = 6_000L
        private const val FULL_SHUTDOWN_KILL_REARM_MS = 3_000L
        private const val FULL_SHUTDOWN_MAX_WAIT_MS = 15_000L

        /**
         * v49: shutdown ke pal latke pushti-sawal ko
         * diya jaane wala saaf inkaar — dwaar hamesha
         * fail-closed (haan kabhi nahi banta)।
         */
        private const val SESSION_DENY_ANSWER = "না"

        /** v49: session me itni poori khamoshi (15 min) par chetaavni + band। */
        private const val SESSION_IDLE_MS = 15 * 60 * 1000L

        /** v49: khaali khidki phir kholne ka chhota antar (spin-rok). */
        private const val SESSION_REOPEN_DELAY_MS = 600L

        /** v51 (item 6b): lagatar khaali khidkiyon par peeche-hatne wale antar — 3 ke baad 3 s, 5 ke baad 8 s. */
        private const val SESSION_REOPEN_DELAY_MID_MS = 3_000L
        private const val SESSION_REOPEN_DELAY_LATE_MS = 8_000L

        /** v51 (item 2): gate se ruka natija bina boli-retry kharch kiye zyada se zyada itni baar phir suna jayega. */
        private const val MAX_GATE_RESTARTS = 2

        /** v49: session sandarbh me aakhiri itne len-den rehte hain। */
        private const val SESSION_CONTEXT_MAX_LINES = 10

        /** v49: sandarbh ki ek line me hukm ka kata hua aakar। */
        private const val SESSION_CONTEXT_CMD_CHARS = 50

        /** v49: sandarbh ki ek line me natije ka kata hua aakar। */
        private const val SESSION_CONTEXT_OUTCOME_CHARS = 70

        /** v49: session wali boli linein — har shaksiyat ki apni bhasha me। */
        private const val SESSION_MORE_TEXT_BN = "আর কিছু করতে হবে?"
        private const val SESSION_MORE_TEXT_HI = "और कुछ करना है?"
        private const val SESSION_CLARIFY_TEXT_BN =
            "ঠিক বুঝলাম না — কোনটা বলতে চাইছো?"
        private const val SESSION_CLARIFY_TEXT_HI =
            "ठीक समझ नहीं आया — आप क्या कहना चाह रहे हैं?"
        private const val SESSION_NOT_UNDERSTOOD_TEXT_BN =
            "এটা আমি ঠিক বুঝতে পারলাম না।"
        private const val SESSION_NOT_UNDERSTOOD_TEXT_HI =
            "यह मैं ठीक से समझ नहीं पाई।"
        private const val SESSION_DONE_TEXT_BN = "ঠিক আছে।"
        private const val SESSION_DONE_TEXT_HI = "ठीक है।"
        private const val SESSION_IDLE_TEXT_BN =
            "অনেকক্ষণ কোনো কথা হলো না — সেশন বন্ধ করছি।"
        private const val SESSION_IDLE_TEXT_HI =
            "काफ़ी देर से कोई बात नहीं हुई — सेशन बंद कर रही हूँ।"

        /** v33: ek command ko kul itne retry milenge. */
        private const val MAX_COMMAND_RETRIES = 2

        /** v38: system session me goonj wala final aaye to bina retry kharch kiye itni baar phir sunna. */
        private const val MAX_SYSTEM_ECHO_RESTARTS = 2

        /** v41: Zipformer session mic kholne se pehle goonj wali khidki (self-hearing guard) nikalne ka zyada se zyada intezaar. */
        private const val ZIPFORMER_GUARD_WAIT_MS = 4_000L

        /** v1.10: unsupported kholne ki koshish par bola jaane wala jawab. */
        const val CANT_DO_REPLY = "माफ़ कीजिए, अभी मैं यह काम नहीं कर सकता हूँ।"
        private const val COMMAND_SILENCE_TIMEOUT_MS = 2_500L
        private const val COMMAND_HARD_CAP_MS = 7_000L
        private const val COMMAND_WATCHDOG_TICK_MS = 400L
        private const val ACK_FALLBACK_MS = 3_500L
        private const val REPLY_FALLBACK_BASE_MS = 4_000L
        private const val REPLY_FALLBACK_PER_CHAR_MS = 110L
        private const val REPLY_FALLBACK_MAX_MS = 20_000L
        private const val WAKE_RESTART_DELAY_MS = 4_000L
        private const val MAX_WAKE_ERRORS = 5
        private const val WAKE_AUTO_RESUME_MS = 12_000L
        private const val REPLY_RESUME_RETRY_MS = 2_000L
        private const val ORB_REPLY_LINGER_MS = 1_500L
        private const val ORB_FADE_IN_MS = 250L
        private const val ORB_FADE_OUT_MS = 220L

        /** v16: ek baar ke screen-wake ki umar; har session/jawab use badha deta hai. */
        private const val WAKE_SCREEN_TIMEOUT_MS = 15_000L

        /**
         * v36: boli khatm hone ke baad itni der tak suna hua
         * sab goonj mana jayega (self-hearing guard) — Maya
         * ke apne jawab ke shabd dobara hukm na ban jayein.
         */
        private const val SELF_ECHO_GUARD_MS = 1_500L

        /**
         * v36 (C): device hukm ki pehchan wale shabd — inme
         * se koi suna jaye to kachha text jarvis_diag ki
         * "last_device_heard" report me band hota hai.
         */
        private val DEVICE_HEARD_WORDS = listOf(
            "torch", "টর্চ", "टॉर्च", "টার্চ", "टार्च",
            "flashlight", "ফ্ল্যাশলাইট", "फ्लैशलाइट",
            "mobile data", "মোবাইল ডাটা", "মোবাইল ডেটা",
            "मोबाइल डेटा", "मोबाइल डाटा", "ডাটা", "ডেটা",
            "डेटा", "डाटा"
        )

        /** v21: Gemini yaad ki gehrai — 10 turn, ek turn = user+model jodi. */
        private const val MAX_GEMINI_HISTORY_ENTRIES = 20

        /** Wake-word spellings and close mishearings from the recognizers. */
        private val WAKE_WORDS = listOf(
            "जार्विस", "जारविस", "जर्भिस", "जारभिस", "जार्बिस",
            "जार्भिस", "जार्बीस", "जारवीस", "जारिस", "जाविस",
            "জার্ভিস", "জারবিস", "জারভিস", "জার্বিস", "জার্ভীস",
            "jarvis", "jarbis", "jarwis", "jarvish"
        )

        /**
         * v13: dusre wake naam "Maya" ke roop. Ye WAKE_WORDS me
         * jaan-boojh kar nahi milaye gaye — unpar token-level wala
         * alag match lagta hai (containsMayaCall dekho).
         */
        private val MAYA_WAKE_WORDS = listOf(
            "माया", "মায়া", "মাযা", "মাইয়া",
            "maya", "maaya", "maiya"
        )

        /**
         * v48: teesre wake naam "Tara" ke roop —
         * Devanagari (Vosk Hindi wahi sunta hai), Bangla
         * aur Latin. Ye MAYA_WAKE_WORDS se alag isliye
         * hain kyunki inpar sthan-bandha niyam lagta hai
         * (containsTaraCall dekho): "তারা" aam shabd
         * hai, beech-wakya me kabhi wake nahi.
         */
        private val TARA_WAKE_WORDS = listOf(
            "तारा", "তারা", "তাঁরা",
            "tara", "taara"
        )

        /** v48: naam se pehle aane wale pukar-shabd ("হে তারা"). */
        private val WAKE_PARTICLES = listOf(
            "হে", "হেই", "আরে", "ও", "हे", "अरे",
            "hey", "he", "are", "o"
        )

        /** True while the wake service is alive (same process as the UI). */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Last human-readable state line, shown on the main screen. */
        @Volatile
        var statusMessage: String = "Band hai"
            private set

        fun startIntent(context: Context): Intent =
            Intent(context, JarvisWakeService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, JarvisWakeService::class.java).setAction(ACTION_STOP)

        fun pauseIntent(context: Context): Intent =
            Intent(context, JarvisWakeService::class.java).setAction(ACTION_PAUSE_WAKE)

        fun resumeIntent(context: Context): Intent =
            Intent(context, JarvisWakeService::class.java).setAction(ACTION_RESUME_WAKE)

        /** v89: overlay chhupao/dikhao — MainActivity ke saamne/peeche hone par। */
        const val ACTION_OVERLAY_HIDE = "com.aship.jarvis.action.OVERLAY_HIDE"
        const val ACTION_OVERLAY_SHOW = "com.aship.jarvis.action.OVERLAY_SHOW"

        fun overlayHideIntent(context: Context): Intent =
            Intent(context, JarvisWakeService::class.java).setAction(ACTION_OVERLAY_HIDE)

        fun overlayShowIntent(context: Context): Intent =
            Intent(context, JarvisWakeService::class.java).setAction(ACTION_OVERLAY_SHOW)

        /** v51 (item 6a): [ACTION_YIELD_MIC] wala intent — MainActivity sunna shuru karne se pehle bhejti hai। */
        fun yieldIntent(context: Context): Intent =
            Intent(context, JarvisWakeService::class.java).setAction(ACTION_YIELD_MIC)
    }
}
