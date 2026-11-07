package com.aship.jarvis.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.view.Display
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v17: Jarvis ki Accessibility service — EK hi kaam ke liye.
 *
 * Android aam apps ko mobile data seedha on/off karne nahi deta
 * (woh system ki permission hai). Ek hi jaayaz raasta hai:
 * quick-settings panel kholkar data wala tile khud dabana — aur
 * woh dabana Accessibility service hi kar sakti hai. Ye service
 * bas wahi karti hai: panel kholo, tile dhoondho, dabao, panel
 * band karo. Iske alawa koi screen reading, koi apne-aap tap ya
 * scroll, kuch nahi hota (manifest/config me bhi waisa kuch
 * ghoshit nahi hai).
 *
 * Service user khud Settings > Accessibility se ON karta hai.
 * Band ho to Jarvis kuch nahi karta — [isEnabled] se pata karke
 * saaf bol deta hai ki pehle app se Accessibility chalu karo.
 *
 * Tile dhoondhna andaaz (heuristic) hai: tile par likha naam har
 * company ke phone me alag hota hai (vivo OriginOS par pakka
 * koi nahi jaanta), isliye kai roop aazmaye jate hain — "Mobile
 * data" wale naam sabse pehle, phir akela "Data"/"डेटा"/"ডেটা",
 * phir "Internet" jisme data ka matlab bhi ho. "Data Saver"
 * wala tile jaan-boojh kar NAHI dabta — woh alag cheez hai.
 * Tile ka haal (on/off) padh sakte hain to pehle dekh lete hain;
 * na padh payein to ek baar dabakar koshish karte hain. Tile na
 * mile ya panel na khule to [DataResult] me sach batate hain —
 * jhoothi safalta kabhi nahi.
 *
 * v18: disha wala shabd sunai na de ("মোবাইল ডাটা করো" — Vosk
 * chhota "on" aksar kha jata hai) to [flipMobileData] tile ko
 * ek baar dabakar haalat ulta kar deta hai aur dabane ke baad
 * ki haalat padhkar [DataOutcome] me sach batata hai.
 *
 * v23: har koshish ke aakhir me ek chhoti scan report band
 * rehti hai — panel dikha tha ya nahi, kitne ped dekhe, kaun
 * se naam mile, sabse bada number kya tha. Report companion
 * ke [lastScanReport] me aur SharedPreferences "jarvis_diag"
 * me rehti hai, taaki app ki screen use dikha sake — tile na
 * mile to aakhir pata to chale ki panel me tha kya. Khoj ka
 * kul intezaar bhi 2.5 se badhakar 8 second kiya (vivo wala
 * panel sajne me der lagata hai).
 *
 * v26: dusra — aur aakhiri ghoshit — kaam:
 * [tapFirstYouTubeResult], agent ke youtube_play auzaar ke
 * liye. YouTube search results khulne ke baad pehle asal
 * video par EK tap, bas itna hi. Kabhi scroll nahi, kabhi
 * like/comment/subscribe/share nahi dabta; saamne YouTube
 * na ho to kuch hota hi nahi. Har koshish ki report
 * "jarvis_diag" me "last_youtube_tap" par band hoti hai —
 * kaun sa video daba (ya kya-kya dikha tha).
 *
 * v37: teesra ghoshit kaam — screen-agent ki mashinari
 * (agent ke screen_task auzaar ke liye): screen ka saar
 * padhna ([captureScreen]: saamne wali app + dikhai dene
 * wale hisse), naam ya nirdeshank se tap, scroll, focus
 * wale khane me likhna, aur Back/Home global action.
 * Faisle (kya dabana hai, kab rukna hai) mastishk ke
 * hain — ye service sirf wohi kadam uthati hai jo use
 * saaf taur par kaha jaye; send/pay/delete jaise kadmon
 * ki pushti user se ScreenAgent leta hai, yahan nahi.
 *
 * v39: chautha ghoshit kaam — screen-VISION ki tasveer:
 * [captureScreenshot] takeScreenshot() (Android 11+,
 * config me canTakeScreenshot) se is pal ki screen ka
 * Bitmap deta hai, taaki ScreenAgent node-khaali screen
 * (gallery, browser, canvas app) par nishan-aankit (Set-
 * of-Mark) tasveer mastishk ko bhej sake. Tasveer kahin
 * bhejni/share karni is sanskar me NAHI hai — saaf
 * (bina-nishan) tasveer sirf yaad me rehti hai
 * ([lastCleanScreenshot]) aane wale sanskaron ke liye.
 * System ki dar-seema (rate limit) ka samman: ~1.2 s ke
 * andar dusri maang par pichhli tasveer hi laut-ti hai;
 * gadbad (interval-wali bhi) par null — bulane wala us
 * kadam par bina tasveer aage badhta hai.
 *
 * v42: paanchva ghoshit sudhaar — pakke scroll hukm
 * ([scrollCommandEx]) ab DO mashinariyon wala hai: pehle
 * sakriya khidki ke sabse bade lambai-scrollable hisse par
 * ACTION_SCROLL_FORWARD/BACKWARD (kai satahen ungli-wala
 * swipe nazar-andaaz karke yahi sunti hain), woh na chale
 * to purana beech-wala swipe, woh bhi radd ho to ek baar
 * lamba-dheema swipe. Natija [ScrollReport] me bandhta
 * hai — kaun si mashinari chali, chali to kis hisse par,
 * nahi chali to kyun — taaki report andaze par na tike.
 * Screen-agent wala [scrollScreen] bilkul aparivartit hai.
 *
 * Saara kaam service ke apne ek background thread par hota hai;
 * bulane wala kabhi nahi rukta, natija callback se milta hai.
 * Kul intezaar ~8 second se bandha hai.
 */
class JarvisAccessibilityService : AccessibilityService() {

    /** Mobile-data toggle ki koshish ka sachha natija. */
    enum class DataResult {
        /** Tile mila aur dab gaya (ya pehle se waisi hi haalat thi). */
        SUCCESS,

        /** Panel khula, par data wala tile nahi mila / dab nahi paya. */
        TILE_NOT_FOUND,

        /** Quick-settings panel khula hi nahi. */
        PANEL_NOT_OPENED,

        /** Service band hai (ya beech me band ho gayi). */
        SERVICE_OFF
    }

    /**
     * v18: bina disha wala toggle ("মোবাইল ডাটা করো" — on/off
     * shabd sunai hi nahi diya) ka natija, dabane ke BAAD ki
     * asal haalat ke saath.
     */
    enum class DataOutcome {
        /** Dabane ke baad data ON hai. */
        TURNED_ON,

        /** Dabane ke baad data OFF hai. */
        TURNED_OFF,

        /** Tile dab to gaya, par baad ki haalat padh nahi paye. */
        TOGGLED_UNKNOWN,

        /** Panel khula, par data wala tile nahi mila / dab nahi paya. */
        TILE_NOT_FOUND,

        /** Quick-settings panel khula hi nahi. */
        PANEL_NOT_OPENED,

        /** Service band hai (ya beech me band ho gayi). */
        SERVICE_OFF
    }

    /**
     * v56: START button — quick-settings panel ka ek tile:
     * naam + screen par jagah (0..1000, chaudaai/lambai
     * ke hisse me). Jagah isliye bandhi jati hai taaki
     * tile kahin bhi sarke, dobara dhoondha ja sake.
     */
    data class QuickTile(
        val label: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    /** v56: panel-tile scan koshish ka sachha natija. */
    enum class TileScanOutcome {
        /** Panel khula aur tile mile. */
        FOUND,

        /** Panel khula, par koi tile padha nahi ja saka. */
        NO_TILES,

        /** Quick-settings panel khula hi nahi. */
        PANEL_NOT_OPENED,

        /** Service band hai (ya beech me band ho gayi). */
        SERVICE_OFF
    }

    /**
     * v26: YouTube search results me pehle video par tap
     * ki koshish ka sachha natija (agent ke youtube_play
     * auzaar ke liye).
     */
    enum class TapOutcome {
        /** Pehla video mila aur tap ho gaya — chal raha hona chahiye. */
        TAPPED,

        /** v33: tap gaya, par watch page khula — yeh pakka nahi ho paya. */
        UNVERIFIED,

        /** YouTube saamne aaya hi nahi, ya results me video nahi mila. */
        NOT_FOUND,

        /** Service band hai (ya beech me band ho gayi). */
        SERVICE_OFF,

        /** Ek tap-koshish pehle se chal rahi hai — dusri maang nahi li gayi. */
        BUSY
    }

    /**
     * v37: screen par dikhai dene wali ek cheez — screen
     * agent ke saar ke liye. [text] node ka text +
     * content-description milakar saaf kiya hua naam hai
     * (khaali bhi ho sakta hai — bina-naam wala button);
     * (centerX, centerY) screen par uske beech ke
     * nirdeshank hain. v39: chaukona [left], [top],
     * [right], [bottom] (screen px) bhi band hota hai —
     * Set-of-Mark tasveer par wahi chaukona nambar ke
     * saath banta hai aur tap_mark usi hisse ke beech
     * par padta hai.
     */
    data class ScreenElement(
        val text: String,
        val className: String,
        val clickable: Boolean,
        val editable: Boolean,
        val centerX: Int,
        val centerY: Int,
        val left: Int = 0,
        val top: Int = 0,
        val right: Int = 0,
        val bottom: Int = 0
    )

    /** v37: ek pal ki screen ka saar — saamne wali app aur uske hisse. */
    data class ScreenSnapshot(
        val packageName: String,
        val elements: List<ScreenElement>
    )

    /**
     * v45: poori-screen ungli-swipe ki disha — ungli ki
     * harkat ki disha hai (LEFT = ungli bayein or, samagri
     * bayein or sarkegi: gallery/carousel me aam taur par
     * AGLE page ki chaal)।
     */
    enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

    private val worker = Executors.newSingleThreadExecutor()

    // v23: panel-scan report ki kacchi jankari — sirf worker
    // thread hi chhoota hai. searchPanelTile har koshish me
    // inhe saaf karke bharta hai; report ka bandha hua roop
    // pendingScanReport me rehta hai, koshish ke aakhir me
    // publishScanReport use hamesha ke liye rakh deta hai.
    private val scanLabels = LinkedHashSet<String>()
    private var scanRootsScanned = 0
    private var scanSysuiSeen = false
    private var scanBestScore = 0
    private var scanTileLabel: String? = null
    private var pendingScanReport: String? = null

    // v26: YouTube tap ki haalat. tapBusy ek jhanda hai jo
    // kisi bhi thread se padha jata hai (@Volatile) — ek
    // samay me ek hi tap-koshish chalegi. ytLabels sirf
    // worker thread chhoota hai: khoj ke dauraan YouTube
    // khidki me mile naam, report (last_youtube_tap) ke liye.
    @Volatile
    private var tapBusy = false
    private val ytLabels = LinkedHashSet<String>()

    // v39: screen-tasveer ki cache — system ki dar-seema
    // (~1 s se jaldi dusri tasveer par ERROR_TAKE_SCREENSHOT_
    // INTERVAL_TIME_SHORT) se bachne ke liye. Maangne wale
    // ke thread aur takeScreenshot ke callback thread dono
    // chhoote hain, isliye @Volatile; ScreenAgent ki maangein
    // waise bhi ek ke baad ek aati hain.
    @Volatile
    private var lastShotBitmap: Bitmap? = null

    @Volatile
    private var lastShotAtMs: Long = 0L

    /**
     * v39: aakhiri safal SAFAL tasveer ka saaf (bina
     * nishan wala) roop — aane wale sanskaron (share
     * waghera) ke liye sirf yaad me rakha hai; is sanskar
     * me iska koi istemal nahi hota.
     */
    @Volatile
    var lastCleanScreenshot: Bitmap? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        worker.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Kuch nahi: hum ghatnayein nahi dekhte. Service sirf
        // bulaye jaane par (toggleMobileData) kaam karti hai.
    }

    override fun onInterrupt() {
        // Kuch nahi karna.
    }

    /**
     * Mobile data [turnOn] wali haalat me karne ki koshish.
     * Turant laut aata hai; asal kaam background thread par hota
     * hai aur natija [callback] se (usi thread par) milta hai.
     */
    fun toggleMobileData(turnOn: Boolean, callback: (DataResult) -> Unit) {
        try {
            worker.execute {
                pendingScanReport = null
                val result = try {
                    runToggle(turnOn)
                } catch (t: Throwable) {
                    DataResult.TILE_NOT_FOUND
                }
                publishScanReport()
                try {
                    callback(result)
                } catch (t: Throwable) {
                    // Bulane wala ja chuka hai: natija chhod do.
                }
            }
        } catch (t: Throwable) {
            // Service band ho rahi hai aur worker band ho chuka:
            // kaam nahi ho payega — sach bata do.
            try {
                callback(DataResult.SERVICE_OFF)
            } catch (t2: Throwable) {
                // Bulane wala ja chuka hai: natija chhod do.
            }
        }
    }

    /**
     * v18: bina disha ka toggle — tile ko ek baar dabakar
     * haalat ulta kar do (on hai to off, off hai to on), phir
     * dabane ke baad ki haalat padhkar batao. Turant laut aata
     * hai; asal kaam background thread par hota hai aur natija
     * [callback] se (usi thread par) milta hai.
     */
    fun flipMobileData(callback: (DataOutcome) -> Unit) {
        try {
            worker.execute {
                pendingScanReport = null
                val result = try {
                    runFlip()
                } catch (t: Throwable) {
                    DataOutcome.TILE_NOT_FOUND
                }
                publishScanReport()
                try {
                    callback(result)
                } catch (t: Throwable) {
                    // Bulane wala ja chuka hai: natija chhod do.
                }
            }
        } catch (t: Throwable) {
            // Service band ho rahi hai aur worker band ho chuka:
            // kaam nahi ho payega — sach bata do.
            try {
                callback(DataOutcome.SERVICE_OFF)
            } catch (t2: Throwable) {
                // Bulane wala ja chuka hai: natija chhod do.
            }
        }
    }

    /**
     * v56: START button — quick-settings panel kholkar
     * saare tile padho (naam + jagah) aur [callback] se
     * lautao. Turant laut aata hai; asal kaam worker
     * thread par hota hai. Panel kholne ke baad use BACK
     * se band bhi kar deta hai, taaki user apni screen
     * par wapas aaye.
     */
    fun scanQuickSettingsTiles(callback: (TileScanOutcome, List<QuickTile>) -> Unit) {
        try {
            worker.execute {
                var outcome: TileScanOutcome
                var tiles: List<QuickTile> = emptyList()
                try {
                    if (!performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)) {
                        outcome = TileScanOutcome.PANEL_NOT_OPENED
                    } else {
                        tiles = collectPanelTiles()
                        outcome = if (tiles.isEmpty()) {
                            TileScanOutcome.NO_TILES
                        } else {
                            TileScanOutcome.FOUND
                        }
                    }
                } catch (t: Throwable) {
                    outcome = TileScanOutcome.NO_TILES
                    tiles = emptyList()
                }
                try {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                } catch (t: Throwable) {
                    // Panel band na ho to bhi natija de do.
                }
                try {
                    callback(outcome, tiles)
                } catch (t: Throwable) {
                    // Bulane wala ja chuka hai: natija chhod do.
                }
            }
        } catch (t: Throwable) {
            try {
                callback(TileScanOutcome.SERVICE_OFF, emptyList())
            } catch (t2: Throwable) {
                // Bulane wala ja chuka hai.
            }
        }
    }

    /**
     * v56: panel khulne ke baad SystemUI ke ped se saare
     * tile padho — har node ka naam (text + description)
     * aur screen par jagah (0..1000). Naam se ek hi baar
     * (pehla wala), zyada se zyada [MAX_PANEL_TILES].
     * Panel der se khule to [TOTAL_BUDGET_MS] tak intezaar.
     */
    private fun collectPanelTiles(): List<QuickTile> {
        val found = LinkedHashMap<String, QuickTile>()
        val deadline = SystemClock.uptimeMillis() + TOTAL_BUDGET_MS
        val metrics = try {
            resources.displayMetrics
        } catch (t: Throwable) {
            null
        }
        val width = metrics?.widthPixels ?: 0
        val height = metrics?.heightPixels ?: 0
        while (SystemClock.uptimeMillis() < deadline &&
            found.size < MAX_PANEL_TILES
        ) {
            val roots = collectRoots()
            var sysuiSeen = false
            for (root in roots) {
                if (root.packageName?.toString() != SYSTEMUI_PACKAGE) continue
                sysuiSeen = true
                collectTileNodes(root, found, width, height)
            }
            if (sysuiSeen && found.isNotEmpty()) break
            roots.forEach { root ->
                try {
                    root.recycle()
                } catch (t: Throwable) {
                    // Pehle se saaf: kuch nahi karna.
                }
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return found.values.toList()
    }

    /**
     * v56: ek ped ke har node se tile padho — naam khaali
     * ho ya jagah na ho to chhodo; naam se ek hi baar.
     */
    private fun collectTileNodes(
        root: AccessibilityNodeInfo,
        out: LinkedHashMap<String, QuickTile>,
        width: Int,
        height: Int
    ) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        val bounds = Rect()
        while (!stack.isEmpty() && visited < MAX_NODES &&
            out.size < MAX_PANEL_TILES
        ) {
            val node = stack.removeLast()
            visited++
            try {
                val label = nodeLabel(node).take(40).trim()
                if (label.isNotEmpty()) {
                    val key = label.lowercase(Locale.ROOT)
                    if (!out.containsKey(key)) {
                        try {
                            node.getBoundsInScreen(bounds)
                        } catch (t: Throwable) {
                            bounds.setEmpty()
                        }
                        if (!bounds.isEmpty && width > 0 && height > 0) {
                            out[key] = QuickTile(
                                label,
                                (bounds.left * 1000 / width).coerceIn(0, 1000),
                                (bounds.top * 1000 / height).coerceIn(0, 1000),
                                (bounds.right * 1000 / width).coerceIn(0, 1000),
                                (bounds.bottom * 1000 / height).coerceIn(0, 1000)
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                // Ek node bigda to baaki phir bhi padho.
            }
            for (i in 0 until node.childCount) {
                try {
                    node.getChild(i)?.let { child -> stack.addLast(child) }
                } catch (t: Throwable) {
                    // Ped beech me badal gaya: jo mila wahi kaafi.
                }
            }
        }
    }

    /**
     * v26: YouTube search results me pehle asal video par
     * EK tap ki koshish (agent ke youtube_play auzaar se
     * bulaayi jati hai). Turant laut aata hai; asal kaam
     * worker thread par hota hai aur natija [callback] se
     * (usi thread par) milta hai. Ek samay me ek hi
     * koshish: dusri maang aaye to kataar me khada karne
     * ki jagah sachha [TapOutcome.BUSY] milta hai.
     */
    fun tapFirstYouTubeResult(callback: (TapOutcome) -> Unit) {
        if (tapBusy) {
            saveYouTubeTapDiag("BUSY")
            deliverTapResult(callback, TapOutcome.BUSY)
            return
        }
        tapBusy = true
        try {
            worker.execute {
                val outcome = try {
                    runYouTubeTap()
                } catch (t: Throwable) {
                    // Ped beech me bigad gaya ho: report ke
                    // saath imaandaar NOT_FOUND.
                    saveYouTubeTapDiag(
                        "NOT_FOUND seen: [" +
                            ytLabels.joinToString(" | ") + "]"
                    )
                    TapOutcome.NOT_FOUND
                }
                tapBusy = false
                deliverTapResult(callback, outcome)
            }
        } catch (t: Throwable) {
            // Service band ho rahi hai aur worker band ho
            // chuka: kaam nahi ho payega — sach bata do.
            tapBusy = false
            saveYouTubeTapDiag("SERVICE_OFF")
            deliverTapResult(callback, TapOutcome.SERVICE_OFF)
        }
    }

    /** v26: tap ka natija surakshit roop se bulane wale tak pahunchayo. */
    private fun deliverTapResult(
        callback: (TapOutcome) -> Unit,
        outcome: TapOutcome
    ) {
        try {
            callback(outcome)
        } catch (t: Throwable) {
            // Bulane wala ja chuka hai: natija chhod do.
        }
    }

    // ------------------------------------------------------------------
    // v37: screen-agent wali mashinari — screen padhna aur chalana
    // ------------------------------------------------------------------

    /**
     * v37: is pal ki screen ka saar band karo — saamne wali
     * app ka package aur uske dikhai dene wale hisse
     * ([ScreenElement] ki kataar, zyada se zyada
     * [MAX_SCREEN_ELEMENTS])। Turant laut aata hai; asal
     * kaam worker thread par hota hai aur natija [callback]
     * se (usi thread par) milta hai. Koi ped hi na mile to
     * null.
     */
    fun captureScreen(callback: (ScreenSnapshot?) -> Unit) {
        runOnWorker(callback, null) { runCaptureScreen() }
    }

    /**
     * v39: is pal ki screen ki TASVEER — takeScreenshot()
     * (Android 11+; usse purane phone par turant null)।
     * Callback kisi bhi thread par aa sakta hai.
     *
     * Dar-seema ka samman: pichhli safal tasveer
     * [SHOT_MIN_INTERVAL_MS] se taazi ho to wahi laut-ti
     * hai — system ka interval-wala error (aur har doosri
     * gadbad) null bankar aata hai, bulane wala us kadam
     * par bina tasveer ke aage badhta hai; kaam kabhi
     * tasveer ki wajah se nahi rukta. Safal tasveer
     * [lastCleanScreenshot] me bhi band hoti hai.
     *
     * FLAG_SECURE wali screen par tasveer kaali aati hai —
     * use pehchanna ScreenAgent/ScreenVision ka kaam hai,
     * yahan tasveer jaisi mili waisi hi jaati hai.
     */
    fun captureScreenshot(callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            deliverShot(callback, null)
            return
        }
        val cached = lastShotBitmap
        val age = SystemClock.uptimeMillis() - lastShotAtMs
        if (cached != null && age in 0 until SHOT_MIN_INTERVAL_MS) {
            deliverShot(callback, cached)
            return
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        deliverShot(callback, unwrapShot(screenshot))
                    }

                    override fun onFailure(errorCode: Int) {
                        // Interval-wali gadbad bhi yahin aati
                        // hai — is kadam par tasveer nahi.
                        deliverShot(callback, null)
                    }
                }
            )
        } catch (t: Throwable) {
            deliverShot(callback, null)
        }
    }

    /**
     * v39: ScreenshotResult ke hardware buffer ko aam
     * (software, padhne-aankne layak) Bitmap me badlo —
     * hardware bitmap par na pixel padhe jate hain na
     * Canvas se nishan bante hain. Buffer har haal me
     * band hota hai (system ki seema bandhi hai).
     */
    private fun unwrapShot(screenshot: ScreenshotResult): Bitmap? {
        var hardware: Bitmap? = null
        val copy = try {
            hardware = Bitmap.wrapHardwareBuffer(
                screenshot.hardwareBuffer,
                screenshot.colorSpace
            )
            hardware?.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            null
        }
        try {
            hardware?.recycle()
        } catch (t: Throwable) {
            // Pehle se saaf: kuch nahi karna.
        }
        try {
            screenshot.hardwareBuffer.close()
        } catch (t: Throwable) {
            // Band karne ki gadbad tasveer ko nahi rokti.
        }
        if (copy != null) {
            lastShotBitmap = copy
            lastShotAtMs = SystemClock.uptimeMillis()
            lastCleanScreenshot = copy
        }
        return copy
    }

    /** v39: tasveer ka natija surakshit roop se bulane wale tak pahunchayo. */
    private fun deliverShot(callback: (Bitmap?) -> Unit, bitmap: Bitmap?) {
        try {
            callback(bitmap)
        } catch (t: Throwable) {
            // Bulane wala ja chuka hai: natija chhod do.
        }
    }

    /**
     * v37: screen par [text] naam wali cheez dabao — naam
     * mila to ungli-jaisa gesture tap (v33 wala hi raasta),
     * woh na chale to ACTION_CLICK। Natija: dab paya ya
     * nahi.
     */
    fun tapScreenText(text: String, callback: (Boolean) -> Unit) {
        runOnWorker(callback, false) { runTapText(text) }
    }

    /** v37: screen par seedha (x, y) par gesture tap. */
    fun tapScreenPoint(x: Int, y: Int, callback: (Boolean) -> Unit) {
        runOnWorker(callback, false) { tapAtPoint(x.toFloat(), y.toFloat()) }
    }

    /** v37: screen ke beech me swipe — [down] par samagri upar ki or sarkegi. */
    fun scrollScreen(down: Boolean, callback: (Boolean) -> Unit) {
        runOnWorker(callback, false) { runScroll(down) }
    }

    /**
     * v38: pakke scroll hukm wala swipe — screen-agent wale
     * [scrollScreen] se alag, ungli screen ki ~60% unchai
     * tak chalti hai (hukm wala scroll ek baar me zyada
     * door jaye). Mashinari wahi (dispatchSwipe) hai.
     */
    fun scrollCommand(down: Boolean, callback: (Boolean) -> Unit) {
        runOnWorker(callback, false) { runScrollCommand(down) }
    }

    /**
     * v42: pakke scroll hukm ki poori report wala roop —
     * [ScrollReport] (mashinari + tafseel ke saath)। Mashinari
     * [runScrollCommandEx] me hai: node wala action pehle,
     * phir swipe, phir lamba-dheema swipe dobara.
     */
    fun scrollCommandEx(down: Boolean, callback: (ScrollReport) -> Unit) {
        runOnWorker(
            callback,
            ScrollReport(false, MECHANISM_NONE, "worker band/gadbad")
        ) { runScrollCommandEx(down) }
    }

    /**
     * v42: ek scroll-hukm koshish ka sachha hisaab —
     * [ok] asal me scroll hua ya nahi (node action manzoor
     * hua, ya swipe poora hua), [mechanism] kaun si
     * mashinari chali ([MECHANISM_NODE] / [MECHANISM_GESTURE]
     * / [MECHANISM_GESTURE_RETRY] / [MECHANISM_NONE]), aur
     * [detail] chhoti tafseel (node wala class + aakaar, ya
     * haar ki wajah) — bolne ke liye NAHI, sirf report ke liye.
     */
    data class ScrollReport(
        val ok: Boolean,
        val mechanism: String,
        val detail: String
    )

    /**
     * v37: is pal focus wale (ya pehle dikhai dene wale
     * editable) khane me [text] likho — ACTION_SET_TEXT,
     * woh na chale to clipboard + paste wala raasta.
     */
    fun typeScreenText(text: String, callback: (Boolean) -> Unit) {
        runOnWorker(callback, false) { runTypeText(text) }
    }

    /** v37: Back / Home jaise global action (GLOBAL_ACTION_*). */
    fun screenGlobalAction(action: Int, callback: (Boolean) -> Unit) {
        runOnWorker(callback, false) {
            try {
                performGlobalAction(action)
            } catch (t: Throwable) {
                false
            }
        }
    }

    /**
     * v45: screen par [text] naam wali cheez ko DER tak
     * dabaye rakho (long-press, ~600 ms ungli-jaisa
     * gesture; woh na chale to ACTION_LONG_CLICK) —
     * context menu / message-options jaisi cheezon ke
     * liye jo aam tap se nahi khulti. Khoj [tapScreenText]
     * wali hi hai (hubahu pehle, warna naam rakhne wala
     * sabse chhota label, clickable purvaj ke saath)।
     */
    fun longPressScreenText(text: String, callback: (Boolean) -> Unit) {
        runOnWorker(callback, false) { runLongPressText(text) }
    }

    /** v45: screen par seedha (x, y) par long-press gesture. */
    fun longPressScreenPoint(x: Int, y: Int, callback: (Boolean) -> Unit) {
        runOnWorker(callback, false) {
            longPressAtPoint(x.toFloat(), y.toFloat())
        }
    }

    /**
     * v45: poori screen par ungli-swipe — screen ki ~65%
     * chaudi/unchai ka safar, ~300 ms. Ye [scrollScreen]
     * se alag ASAL gesture hai: gallery, story, carousel
     * aur aade (horizontal) liston par kaam karta hai,
     * jahan node-scroll kuch nahi karta. Disha ungli ki
     * harkat ki hai — LEFT matlab ungli bayein or gayi
     * (aam taur par agli tasveer/page)।
     */
    fun swipeScreen(direction: SwipeDirection, callback: (Boolean) -> Unit) {
        runOnWorker(callback, false) { runSwipeScreen(direction) }
    }

    /**
     * v45: NAAM wale khane me likho — [label] se milta
     * editable khana dhoondho (uske apne naam/hint se,
     * warna sabse paas wale label se), use focus karo aur
     * [typeScreenText] wali hi mashinari (ACTION_SET_TEXT,
     * woh na chale to clipboard + paste) se [text] bharo.
     * Milta-julta khana hi na mile to imaandaar false.
     */
    fun typeIntoScreenText(
        label: String,
        text: String,
        callback: (Boolean) -> Unit
    ) {
        runOnWorker(callback, false) { runTypeIntoText(label, text) }
    }

    /**
     * v45: is pal focus wale khane par IME ka enter
     * dabao (ACTION_IME_ENTER) — search/form wale khane
     * me likhne ke baad "chalao" wala kadam. Focus wala
     * khana na mile, ya woh khana ye action deta hi na
     * ho (purana Android / khane ki marzi), to
     * imaandaar false — bulane wala tab dikhai dene wale
     * Search/Go/Done batan ki or jata hai.
     */
    fun submitFocusedField(callback: (Boolean) -> Unit) {
        runOnWorker(callback, false) { runSubmitFocusedField() }
    }

    /**
     * v45: screen par dikhai dene wala Search / Go / Done
     * matlab ka sabse achha batan (bn/hi/en naam) — mile
     * to uska ASAL dikhai dene wala naam, warna null.
     * Ye sirf DHOONDHTA hai, dabata nahi: dabane se pehle
     * pushti-dwaar (ScreenAgent) apni jaanch chalata hai,
     * isliye hassas naam (Send waghera) bina pushti ke
     * kabhi nahi dabta.
     */
    fun findSubmitButton(callback: (String?) -> Unit) {
        runOnWorker(callback, null) { runFindSubmitButton() }
    }

    /**
     * v37: screen wale saare kaam ek hi bandobosto se —
     * worker thread par chalao, gadbad ho to [fallback]
     * wala sachha natija, callback kabhi atakta nahi.
     * (toggleMobileData wala hi purana andaaz, bas yahan
     * paanch kaamon ke liye ek jagah bandha hai.)
     */
    private fun <T> runOnWorker(
        callback: (T) -> Unit,
        fallback: T,
        work: () -> T
    ) {
        try {
            worker.execute {
                val result = try {
                    work()
                } catch (t: Throwable) {
                    fallback
                }
                try {
                    callback(result)
                } catch (t: Throwable) {
                    // Bulane wala ja chuka hai: natija chhod do.
                }
            }
        } catch (t: Throwable) {
            // Service band ho rahi hai aur worker band ho
            // chuka: kaam nahi ho payega — sach bata do.
            try {
                callback(fallback)
            } catch (t2: Throwable) {
                // Bulane wala ja chuka hai: natija chhod do.
            }
        }
    }

    /**
     * v37: saamne wali app ka ped — rootInActiveWindow
     * sabse bharosemand hai; woh na mile to khidkiyon me
     * pehla gair-SystemUI ped. Panel wale [collectRoots]
     * se alag isliye: wahan SystemUI jaan-boojh kar aage
     * rehta hai, yahan saamne wali asal app chahiye.
     */
    private fun foregroundRoot(): AccessibilityNodeInfo? {
        try {
            rootInActiveWindow?.let { return it }
        } catch (t: Throwable) {
            // Is pal koi sakriya khidki nahi: neeche wali koshish.
        }
        try {
            windows?.forEach { window ->
                try {
                    val root = window.root ?: return@forEach
                    if (root.packageName?.toString() != SYSTEMUI_PACKAGE) {
                        return root
                    }
                } catch (t: Throwable) {
                    // Ek khidki ka ped na mile to baaki phir bhi.
                }
            }
        } catch (t: Throwable) {
            // Khidkiyon ki list hi na mile: kuch nahi hai.
        }
        return null
    }

    /** v37: asal capture — sirf worker thread se bulana. */
    private fun runCaptureScreen(): ScreenSnapshot? {
        val root = foregroundRoot() ?: return null
        val packageName = try {
            root.packageName?.toString() ?: ""
        } catch (t: Throwable) {
            ""
        }
        val elements = ArrayList<ScreenElement>()
        val bounds = Rect()
        for (node in collectInDocumentOrder(root)) {
            if (elements.size >= MAX_SCREEN_ELEMENTS) break
            try {
                if (!node.isVisibleToUser) continue
                node.getBoundsInScreen(bounds)
                if (bounds.width() <= 0 || bounds.height() <= 0) continue
                val label = nodeLabel(node)
                    .replace(WHITESPACE_RUN, " ")
                    .trim()
                    .take(MAX_SCREEN_LABEL_CHARS)
                val clickable = node.isClickable
                val editable = node.isEditable
                if (label.isEmpty() && !clickable && !editable) continue
                val className = node.className?.toString()
                    ?.substringAfterLast('.') ?: "View"
                elements.add(
                    ScreenElement(
                        text = label,
                        className = className,
                        clickable = clickable,
                        editable = editable,
                        centerX = bounds.centerX(),
                        centerY = bounds.centerY(),
                        left = bounds.left,
                        top = bounds.top,
                        right = bounds.right,
                        bottom = bounds.bottom
                    )
                )
            } catch (t: Throwable) {
                // Ped beech me badal raha hai: ye node chhodo.
            }
        }
        return ScreenSnapshot(packageName, elements)
    }

    /**
     * v37: naam se dabana — sirf worker thread se.
     * Document order me khoj: hubahu (exact) milan sabse
     * pehle, warna naam me woh shabd rakhne wala sabse
     * chhota label. Dabana khud node par, woh clickable
     * na ho to uske clickable purvaj par ([findClickableAncestor]
     * khud se shuru hota hai, isliye dono haal sambhalta hai)।
     */
    /**
     * v45: [runTapText] ki khoj alag bandhi hui — naam se
     * sabse achha dabane-layak nishana (clickable purvaj
     * ke saath)। Document order me: hubahu (exact) milan
     * sabse pehle, warna naam me woh shabd rakhne wala
     * sabse chhota label. Tap aur long-press dono isiko
     * istemal karte hain, taaki dono ki khoj bilkul ek ho.
     */
    private fun findLabeledTarget(
        wantedRaw: String
    ): AccessibilityNodeInfo? {
        val wanted = wantedRaw.trim().lowercase(Locale.ROOT)
        if (wanted.isEmpty()) return null
        val root = foregroundRoot() ?: return null
        var best: AccessibilityNodeInfo? = null
        var bestExact = false
        var bestLabelLength = Int.MAX_VALUE
        for (node in collectInDocumentOrder(root)) {
            val label = try {
                nodeLabel(node).trim()
            } catch (t: Throwable) {
                continue
            }
            if (label.isEmpty()) continue
            val lower = label.lowercase(Locale.ROOT)
            val exact = lower == wanted
            if (!exact && !lower.contains(wanted)) continue
            val target = try {
                findClickableAncestor(node)
            } catch (t: Throwable) {
                null
            } ?: continue
            val better = when {
                exact && !bestExact -> true
                exact == bestExact && label.length < bestLabelLength -> true
                else -> false
            }
            if (better) {
                best = target
                bestExact = exact
                bestLabelLength = label.length
            }
        }
        return best
    }

    private fun runTapText(wantedRaw: String): Boolean {
        val target = findLabeledTarget(wantedRaw) ?: return false
        val bounds = Rect()
        try {
            target.getBoundsInScreen(bounds)
        } catch (t: Throwable) {
            // Ped badal gaya: bounds khaali rahenge, click wala
            // raasta phir bhi aazmaya jayega.
        }
        if (bounds.width() > 0 && bounds.height() > 0) {
            if (tapAtPoint(bounds.centerX().toFloat(), bounds.centerY().toFloat())) {
                return true
            }
        }
        return try {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * v45: naam se der-dabana — khoj [runTapText] wali
     * hi ([findLabeledTarget]); dabana: nishane ke beech
     * par ~600 ms ka ungli-gesture, woh na chale to
     * node par ACTION_LONG_CLICK.
     */
    private fun runLongPressText(wantedRaw: String): Boolean {
        val target = findLabeledTarget(wantedRaw) ?: return false
        val bounds = Rect()
        try {
            target.getBoundsInScreen(bounds)
        } catch (t: Throwable) {
            // Ped badal gaya: bounds khaali rahenge, node wala
            // raasta phir bhi aazmaya jayega.
        }
        if (bounds.width() > 0 && bounds.height() > 0) {
            if (longPressAtPoint(
                    bounds.centerX().toFloat(),
                    bounds.centerY().toFloat()
                )
            ) {
                return true
            }
        }
        return try {
            target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * v45: (x, y) par der tak dabaye rakhne wala gesture —
     * [tapAtPoint] wala hi bandha intezaar, bas stroke
     * [LONG_PRESS_DURATION_MS] tak ek hi jagah thaharta hai.
     */
    private fun longPressAtPoint(x: Float, y: Float): Boolean {
        return try {
            val path = Path()
            path.moveTo(x, y)
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path, 0, LONG_PRESS_DURATION_MS
                    )
                )
                .build()
            val latch = CountDownLatch(1)
            val accepted = AtomicBoolean(false)
            val ok = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(
                        gestureDescription: GestureDescription?
                    ) {
                        accepted.set(true)
                        latch.countDown()
                    }

                    override fun onCancelled(
                        gestureDescription: GestureDescription?
                    ) {
                        latch.countDown()
                    }
                },
                null
            )
            if (ok) {
                latch.await(1600, TimeUnit.MILLISECONDS)
                accepted.get()
            } else {
                false
            }
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * v45: poori-screen swipe ki asal chaal — sirf worker
     * thread se. Ungli screen ke beech se us disha me
     * ~65% safar karti hai ([SWIPE_TRAVEL_FRACTION]),
     * ~300 ms me: gallery/carousel ki tez palat ke liye
     * kaafi, itna dheema bhi nahi ki drag ban jaye.
     */
    private fun runSwipeScreen(direction: SwipeDirection): Boolean {
        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels / 2f
        val cy = metrics.heightPixels / 2f
        val halfX = metrics.widthPixels * SWIPE_TRAVEL_FRACTION / 2f
        val halfY = metrics.heightPixels * SWIPE_TRAVEL_FRACTION / 2f
        return when (direction) {
            SwipeDirection.UP ->
                dispatchSwipe(cx, cy + halfY, cx, cy - halfY, SWIPE_DURATION_MS)
            SwipeDirection.DOWN ->
                dispatchSwipe(cx, cy - halfY, cx, cy + halfY, SWIPE_DURATION_MS)
            SwipeDirection.LEFT ->
                dispatchSwipe(cx + halfX, cy, cx - halfX, cy, SWIPE_DURATION_MS)
            SwipeDirection.RIGHT ->
                dispatchSwipe(cx - halfX, cy, cx + halfX, cy, SWIPE_DURATION_MS)
        }
    }

    /** v37: screen ke beech me ek swipe — sirf worker thread se. */
    private fun runScroll(down: Boolean): Boolean {
        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels / 2f
        val yFrom = metrics.heightPixels * if (down) 0.72f else 0.28f
        val yTo = metrics.heightPixels * if (down) 0.28f else 0.72f
        return dispatchSwipe(cx, yFrom, cx, yTo, SCROLL_DURATION_MS)
    }

    /** v38: hukm wale scroll ka swipe — 0.80 <-> 0.20, yani screen ki ~60% unchai ka safar. */
    private fun runScrollCommand(down: Boolean): Boolean {
        return runScrollCommandEx(down).ok
    }

    /**
     * v42: hukm wale scroll ki asal mashinari — sirf worker
     * thread se. Kram bandha hua hai:
     * 1. NODE: sakriya khidki ke sabse bade lambai-scrollable
     *    hisse par ACTION_SCROLL_FORWARD (down) /
     *    ACTION_SCROLL_BACKWARD (up). Kai satahen (khaas taur
     *    par asal list/scroll wale hisse) ungli-swipe ko
     *    nazar-andaaz kar deti hain par ye action turant
     *    sunti hain; aur ye swipe ki "poora hua" wali jhoothi
     *    kamyabi se bhi bacha hai — action mana ho to sach
     *    me FAIL hai.
     * 2. GESTURE: v38 wala beech-beech swipe (0.80 <-> 0.20,
     *    ~60% unchai, [SCROLL_COMMAND_DURATION_MS]) — node
     *    wala hissa na mile ya action na chale to.
     * 3. GESTURE-RETRY: wahi swipe ek baar aur, lamba aur
     *    dheema ([SCROLL_COMMAND_RETRY_DURATION_MS]) — kuch
     *    satahen tez-chhota swipe beech me kaat deti hain.
     * Teeno ka natija [ScrollReport] me sach bandhta hai.
     */
    private fun runScrollCommandEx(down: Boolean): ScrollReport {
        val nodeDetail = performNodeScroll(down)
        if (nodeDetail != null) {
            return ScrollReport(true, MECHANISM_NODE, nodeDetail)
        }
        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels / 2f
        val yFrom = metrics.heightPixels * if (down) 0.80f else 0.20f
        val yTo = metrics.heightPixels * if (down) 0.20f else 0.80f
        if (dispatchSwipe(cx, yFrom, cx, yTo, SCROLL_COMMAND_DURATION_MS)) {
            return ScrollReport(
                true, MECHANISM_GESTURE,
                "swipe ${SCROLL_COMMAND_DURATION_MS}ms (node nahi mila/chala nahi)"
            )
        }
        if (dispatchSwipe(cx, yFrom, cx, yTo, SCROLL_COMMAND_RETRY_DURATION_MS)) {
            return ScrollReport(
                true, MECHANISM_GESTURE_RETRY,
                "swipe ${SCROLL_COMMAND_RETRY_DURATION_MS}ms dobara (pehla radd hua tha)"
            )
        }
        return ScrollReport(
            false, MECHANISM_NONE,
            "node-scroll nahi hua aur swipe do baar radd"
        )
    }

    /**
     * v42: sabse achhe lambai-scrollable hisse par node wala
     * scroll action dabao — chal jaye to us hisse ka chhota
     * varnan (class + aakaar), warna null. Chuna hua hissa
     * khud mana kar de to uske scrollable purvajon par bhi
     * ek-ek karke koshish hoti hai (kabhi-kabhi scrollable
     * jhande wala hissa andar ka hota hai, asal ghumne wala
     * uska baap)। Sirf worker thread se.
     */
    private fun performNodeScroll(down: Boolean): String? {
        val action = if (down) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        var node: AccessibilityNodeInfo? = bestScrollableNode() ?: return null
        var hops = 0
        while (node != null && hops <= MAX_CLICK_ANCESTORS) {
            val current = node
            val acted = try {
                current.isScrollable && current.performAction(action)
            } catch (t: Throwable) {
                false
            }
            if (acted) {
                val bounds = Rect()
                try {
                    current.getBoundsInScreen(bounds)
                } catch (t: Throwable) {
                    // Ped badal gaya: aakaar bina naam kaafi hai.
                }
                val name = try {
                    current.className?.toString()
                } catch (t: Throwable) {
                    null
                } ?: "scrollable"
                return name + " " + bounds.width() + "x" + bounds.height()
            }
            node = try {
                current.parent
            } catch (t: Throwable) {
                null
            }
            hops++
        }
        return null
    }

    /**
     * v42: scroll hukm ke liye sabse achha scrollable hissa
     * chuno. Khidkiyon ka kram: pehle saamne wali asal app
     * ([foregroundRoot]), phir baaki gair-SystemUI khidkiyan,
     * aakhir me SystemUI (panel khula ho to wohi saamne hai —
     * ye usi ka aakhiri sahara hai, pehla nahi)। Har khidki
     * me sabse BADA woh hissa jo sach me scrollable hai,
     * dikhai de raha hai, aur ya to FORWARD/BACKWARD action
     * deta hai ya aakaar se lambai-scrollable lagta hai
     * (unchai chaudi ki kam se kam dedh-guni)। Pehli khidki
     * ka ummeedwaar screen ke [SCROLL_NODE_MIN_AREA_FRACTION]
     * hisse se bada ho tabhi wahi pakka — Jarvis khud saamne
     * ho to uski apni ScrollView is jaanch me pehle aati
     * hai (saamne wali khidki usi ki hai), isliye app ke
     * andar wala scroll bhi node wale raaste se hota hai.
     * Kuch na mile to null — bulane wala swipe par girega.
     */
    private fun bestScrollableNode(): AccessibilityNodeInfo? {
        val metrics = resources.displayMetrics
        val screenArea =
            metrics.widthPixels.toLong() * metrics.heightPixels.toLong()
        val minArea = (screenArea * SCROLL_NODE_MIN_AREA_FRACTION).toLong()
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            foregroundRoot()?.let { roots.add(it) }
        } catch (t: Throwable) {
            // Saamne wali khidki is pal nahi: neeche wali kataar.
        }
        val allRoots = try {
            collectRoots()
        } catch (t: Throwable) {
            emptyList()
        }
        for (root in allRoots) {
            val pkg = try {
                root.packageName?.toString()
            } catch (t: Throwable) {
                null
            }
            if (pkg != SYSTEMUI_PACKAGE && roots.none { it === root }) {
                roots.add(root)
            }
        }
        for (root in allRoots) {
            if (roots.none { it === root }) roots.add(root)
        }
        for (root in roots) {
            var best: AccessibilityNodeInfo? = null
            var bestArea = 0L
            for (node in collectInDocumentOrder(root)) {
                try {
                    if (!node.isScrollable || !node.isVisibleToUser) continue
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (bounds.width() <= 0 || bounds.height() <= 0) continue
                    val hasVerticalAction = node.actionList.any { info ->
                        info.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                            info.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    }
                    if (!hasVerticalAction &&
                        bounds.height() * 3 < bounds.width() * 2
                    ) {
                        continue
                    }
                    val area =
                        bounds.width().toLong() * bounds.height().toLong()
                    if (area > bestArea) {
                        best = node
                        bestArea = area
                    }
                } catch (t: Throwable) {
                    // Ped badal raha hai: aage badho.
                }
            }
            if (best != null && bestArea >= minArea) return best
        }
        return null
    }

    /** v37: (x1, y1) se (x2, y2) tak ungli-jaisa swipe — [tapAtPoint] wala hi bandha intezaar. */
    private fun dispatchSwipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long
    ): Boolean {
        return try {
            val path = Path()
            path.moveTo(x1, y1)
            path.lineTo(x2, y2)
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(path, 0, durationMs)
                )
                .build()
            val latch = CountDownLatch(1)
            val accepted = AtomicBoolean(false)
            val ok = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(
                        gestureDescription: GestureDescription?
                    ) {
                        accepted.set(true)
                        latch.countDown()
                    }

                    override fun onCancelled(
                        gestureDescription: GestureDescription?
                    ) {
                        latch.countDown()
                    }
                },
                null
            )
            if (ok) {
                latch.await(1500, TimeUnit.MILLISECONDS)
                accepted.get()
            } else {
                false
            }
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * v37: likhna — sirf worker thread se. Pehle is pal
     * focus wala input khana (findFocus), woh na mile to
     * saamne wali app ka pehla dikhai dene wala editable
     * khana. ACTION_SET_TEXT asal raasta hai; woh mana
     * kar de to clipboard par rakhkar ACTION_PASTE —
     * dono ka natija jo sach hai wahi laut-ta hai.
     */
    private fun runTypeText(text: String): Boolean {
        var target: AccessibilityNodeInfo? = null
        for (root in collectRoots()) {
            try {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?.let { target = it }
            } catch (t: Throwable) {
                // Is ped me focus nahi: agli khidki dekho.
            }
            if (target != null) break
        }
        if (target == null) {
            val root = foregroundRoot()
            if (root != null) {
                for (node in collectInDocumentOrder(root)) {
                    try {
                        if (node.isEditable && node.isVisibleToUser) {
                            target = node
                            break
                        }
                    } catch (t: Throwable) {
                        // Ped badal raha hai: aage badho.
                    }
                }
            }
        }
        val node = target ?: return false
        return setTextOnNode(node, text)
    }

    /**
     * v45: ek chune hue khane par text bharne ki asal
     * mashinari — [runTypeText] se alag bandhi hui, taaki
     * naam-wala likhna ([runTypeIntoText]) bhi bilkul
     * wahi raasta le. Focus ki koshish, phir
     * ACTION_SET_TEXT; woh mana kar de to clipboard par
     * rakhkar ACTION_PASTE. Dono ka natija sach.
     */
    private fun setTextOnNode(
        node: AccessibilityNodeInfo,
        text: String
    ): Boolean {
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        } catch (t: Throwable) {
            // Focus ki koshish bonus hai — set-text phir bhi chalega.
        }
        val setOk = try {
            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (t: Throwable) {
            false
        }
        if (setOk) return true
        return try {
            val clipboard = getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as? ClipboardManager ?: return false
            clipboard.setPrimaryClip(
                ClipData.newPlainText("jarvis screen agent", text)
            )
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * v45: kisi khane ka pehchan-naam — dikhne wala text,
     * varnan (contentDescription) aur hint, ek saath.
     * Khaali khane par aam taur par hint hi uska naam
     * hota hai ("Search...", "নাম লিখুন")।
     */
    private fun fieldLabel(node: AccessibilityNodeInfo): String =
        ((nodeLabel(node)) + " " +
            (try {
                node.hintText?.toString() ?: ""
            } catch (t: Throwable) {
                ""
            })).trim()

    /**
     * v45: naam se khana dhoondhkar likhna — sirf worker
     * thread se. Do chaal:
     * 1. APNA NAAM: koi dikhai dene wala editable khana
     *    jiska apna naam/hint mange hue naam se mile
     *    (hubahu pehle, warna naam rakhne wala sabse
     *    chhota) — aam taur par yahi kaafi hai.
     * 2. PAAS WALA NAAM: khane ka apna naam na ho to
     *    woh label jo mange hue naam se milta ho aur
     *    uske thik neeche/paas koi editable khana ho
     *    (formon me label khane ke upar likha rehta hai)।
     * Dono na mile to imaandaar false — andaze se kisi
     * bhi khane me nahi likhenge.
     */
    private fun runTypeIntoText(labelRaw: String, text: String): Boolean {
        val wanted = labelRaw.trim().lowercase(Locale.ROOT)
        if (wanted.isEmpty()) return false
        val root = foregroundRoot() ?: return false
        val nodes = collectInDocumentOrder(root)
        var best: AccessibilityNodeInfo? = null
        var bestExact = false
        var bestLabelLength = Int.MAX_VALUE
        for (node in nodes) {
            try {
                if (!node.isEditable || !node.isVisibleToUser) continue
                val label = fieldLabel(node)
                if (label.isEmpty()) continue
                val lower = label.lowercase(Locale.ROOT)
                val exact = lower == wanted
                if (!exact && !lower.contains(wanted) &&
                    !wanted.contains(lower)
                ) {
                    continue
                }
                val better = when {
                    exact && !bestExact -> true
                    exact == bestExact && label.length < bestLabelLength ->
                        true
                    else -> false
                }
                if (better) {
                    best = node
                    bestExact = exact
                    bestLabelLength = label.length
                }
            } catch (t: Throwable) {
                // Ped badal raha hai: aage badho.
            }
        }
        if (best != null) {
            return setTextOnNode(best!!, text)
        }
        // Chaal 2: paas wala label — uske neeche sabse
        // paas wala editable khana (bandhi doori ke andar)।
        val labelBounds = Rect()
        val fieldBounds = Rect()
        var bestField: AccessibilityNodeInfo? = null
        var bestDistance = Long.MAX_VALUE
        for (node in nodes) {
            try {
                if (node.isEditable || !node.isVisibleToUser) continue
                val label = nodeLabel(node).trim()
                if (label.isEmpty()) continue
                val lower = label.lowercase(Locale.ROOT)
                if (lower != wanted && !lower.contains(wanted)) continue
                node.getBoundsInScreen(labelBounds)
                if (labelBounds.width() <= 0) continue
                for (field in nodes) {
                    try {
                        if (!field.isEditable || !field.isVisibleToUser) {
                            continue
                        }
                        field.getBoundsInScreen(fieldBounds)
                        if (fieldBounds.width() <= 0) continue
                        // Khana label ke neeche ho (form ki
                        // aam banawat) ya ek hi kataar me
                        // dayein — upar/bayein wala khana is
                        // label ka nahi mana jayega.
                        val belowGap = fieldBounds.top - labelBounds.bottom
                        val sameRow = fieldBounds.top < labelBounds.bottom &&
                            fieldBounds.bottom > labelBounds.top
                        if (!(belowGap in 0..NEAREST_LABEL_MAX_GAP_PX ||
                                (sameRow && fieldBounds.left >= labelBounds.left))
                        ) {
                            continue
                        }
                        val dx = (fieldBounds.centerX() -
                            labelBounds.centerX()).toLong()
                        val dy = (fieldBounds.centerY() -
                            labelBounds.centerY()).toLong()
                        val distance = dx * dx + dy * dy
                        if (distance < bestDistance) {
                            bestDistance = distance
                            bestField = field
                        }
                    } catch (t: Throwable) {
                        // Ped badal raha hai: aage badho.
                    }
                }
            } catch (t: Throwable) {
                // Ped badal raha hai: aage badho.
            }
        }
        val field = bestField ?: return false
        return setTextOnNode(field, text)
    }

    /**
     * v45: focus wale khane par IME enter — sirf worker
     * thread se. Focus wala khana pehle ([runTypeText]
     * wala hi dhoondhna); phir Android 11+ par hi
     * ACTION_IME_ENTER aazmana — ye action usse pehle
     * ke Android me hai hi nahi, aur khana khud use
     * deta ho tabhi dabana. Na mile / na de to false:
     * bulane wala dikhai dene wale Search/Go/Done
     * batan ki or jata hai, teesra koi raasta nahi banta.
     */
    private fun runSubmitFocusedField(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false
        }
        var target: AccessibilityNodeInfo? = null
        for (root in collectRoots()) {
            try {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?.let { target = it }
            } catch (t: Throwable) {
                // Is ped me focus nahi: agli khidki dekho.
            }
            if (target != null) break
        }
        val node = target ?: return false
        return performImeEnter(node)
    }

    /**
     * v45: ACTION_IME_ENTER asal me dabana — ye chinh
     * (constant) Android 11 (API 30) se pehle ke
     * framework me hai hi nahi, isliye iska zikr sirf
     * is bandhe hue hisse me hai, aur bulane wala
     * ([runSubmitFocusedField]) SDK jaanch ke baad hi
     * ise bulata hai. Khane ki action-list me ye
     * action ho tabhi dabega; natija sach.
     */
    private fun performImeEnter(node: AccessibilityNodeInfo): Boolean {
        val imeAction = try {
            node.actionList.firstOrNull { info ->
                info.id ==
                    AccessibilityNodeInfo.AccessibilityAction
                        .ACTION_IME_ENTER.id
            }
        } catch (t: Throwable) {
            null
        } ?: return false
        return try {
            node.performAction(imeAction.id)
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * v45: Search/Go/Done matlab wala naam hai kya —
     * gair-Latin (Bangla/Devanagari) shabdon par
     * "naam me hai" kaafi hai; Latin shabdon par
     * shabd-seema (word boundary) chahiye, warna
     * "Google" me "go" aur "abandoned" me "done"
     * galti se mil jayega.
     */
    private fun isSubmitLabel(label: String): Boolean {
        val lower = label.lowercase(Locale.ROOT).trim()
        if (lower.isEmpty()) return false
        if (SUBMIT_EXACT_WORDS.any { lower == it }) return true
        if (SUBMIT_CONTAIN_WORDS.any { lower.contains(it) }) return true
        return SUBMIT_LATIN_PATTERN.containsMatchIn(lower)
    }

    /**
     * v45: dikhai dene wala sabse achha submit batan
     * dhoondho — sirf worker thread se. Dikhai dene
     * wala, naam wala, dabane-layak (khud clickable ya
     * clickable purvaj wala) hissa jiska naam
     * [isSubmitLabel] se mile: hubahu naam pehle,
     * warna sabse chhota naam. Mile to uska ASAL naam;
     * dabana bulane wale (ScreenAgent) ka kaam hai —
     * pushti-dwaar wahin lagta hai.
     */
    private fun runFindSubmitButton(): String? {
        val root = foregroundRoot() ?: return null
        var best: String? = null
        var bestExact = false
        var bestLabelLength = Int.MAX_VALUE
        for (node in collectInDocumentOrder(root)) {
            val label = try {
                if (!node.isVisibleToUser) continue
                nodeLabel(node).trim()
            } catch (t: Throwable) {
                continue
            }
            if (label.isEmpty()) continue
            if (!isSubmitLabel(label)) continue
            val target = try {
                findClickableAncestor(node)
            } catch (t: Throwable) {
                null
            } ?: continue
            val exact = SUBMIT_EXACT_WORDS.any {
                label.lowercase(Locale.ROOT) == it
            }
            val better = when {
                exact && !bestExact -> true
                exact == bestExact && label.length < bestLabelLength -> true
                else -> false
            }
            if (better) {
                best = label
                bestExact = exact
                bestLabelLength = label.length
            }
        }
        return best
    }

    /** Panel khoj ka natija: panel dikha tha ya nahi, tile, aur dabane wala node. */
    private class PanelSearch(
        val panelSeen: Boolean,
        val tile: AccessibilityNodeInfo?,
        val clickTarget: AccessibilityNodeInfo?
    )

    /**
     * Quick-settings panel kholkar data tile dhoondho. Panel
     * khul hi na paye to tile null aur panelSeen false milega.
     */
    private fun searchPanelTile(): PanelSearch {
        // v23: report ki jankari har koshish me nayi — panel
        // khule hi na, tab bhi report bandhegi (sysui=no ...).
        resetScanCollectors()
        if (!performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)) {
            snapshotScanReport()
            return PanelSearch(panelSeen = false, tile = null, clickTarget = null)
        }
        val deadline = SystemClock.uptimeMillis() + TOTAL_BUDGET_MS
        var panelSeen = false
        var tile: AccessibilityNodeInfo? = null
        while (tile == null && SystemClock.uptimeMillis() < deadline) {
            val roots = collectRoots()
            scanRootsScanned += roots.size
            for (root in roots) {
                if (root.packageName?.toString() == SYSTEMUI_PACKAGE) {
                    panelSeen = true
                    scanSysuiSeen = true
                }
                val found = findDataTile(root)
                if (found != null) {
                    tile = found
                    break
                }
            }
            // Jo ped kaam nahi aaya use saaf kar do; agle chakkar
            // me taaza ped milega. (Tile mil gaya ho to uska ped
            // nahi chhedte — tile usi ka hissa hai.)
            if (tile == null) {
                roots.forEach { root ->
                    try {
                        root.recycle()
                    } catch (t: Throwable) {
                        // Pehle se saaf: kuch nahi karna.
                    }
                }
                SystemClock.sleep(POLL_INTERVAL_MS)
            }
        }
        if (tile == null) {
            snapshotScanReport()
            return PanelSearch(panelSeen, null, null)
        }
        val foundTile = tile
        scanTileLabel = nodeLabel(foundTile)
        snapshotScanReport()
        return PanelSearch(
            panelSeen,
            foundTile,
            findClickableAncestor(foundTile) ?: foundTile
        )
    }

    /** v23: scan jankari saaf karo — har nayi khoj ki shuruaat. */
    private fun resetScanCollectors() {
        scanLabels.clear()
        scanRootsScanned = 0
        scanSysuiSeen = false
        scanBestScore = 0
        scanTileLabel = null
    }

    /** v23: ab tak ki scan jankari ko report ke ek-bandhe roop me badlo. */
    private fun snapshotScanReport() {
        pendingScanReport =
            "sysui=" + (if (scanSysuiSeen) "yes" else "no") +
                " roots=" + scanRootsScanned +
                " best=" + scanBestScore +
                " labels: [" + scanLabels.joinToString(" | ") + "]"
    }

    /**
     * v23: report ko hamesha ke liye rakh do — companion ke
     * [lastScanReport] me (isi process me turant mile) aur
     * SharedPreferences "jarvis_diag" me (service alag process
     * me ho to bhi app ki screen use padh sake). Report na
     * band paye to bhi asal natija kabhi nahi rukta.
     */
    private fun publishScanReport() {
        val report = pendingScanReport ?: return
        lastScanReport = report
        try {
            getSharedPreferences(DIAG_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(DIAG_KEY_LAST_PANEL_SCAN, report)
                .apply()
        } catch (t: Throwable) {
            // Report likhna gauṇ hai: toggle ka sach isse bada hai.
        }
    }

    /** Asal kaam — sirf worker thread se bulana. */
    private fun runToggle(turnOn: Boolean): DataResult {
        val search = searchPanelTile()
        val foundTile = search.tile ?: run {
            // Panel khula tha par tile nahi mila to panel band
            // karke batao; panel khula hi nahi to BACK mat bhejo —
            // woh neeche wali app ko peeche dhakel dega.
            if (search.panelSeen) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return DataResult.TILE_NOT_FOUND
            }
            return DataResult.PANEL_NOT_OPENED
        }

        val clickTarget = search.clickTarget ?: foundTile
        // Haal padh sakte hain aur pehle se waisi hi hai to
        // dabane ki zaroorat hi nahi — ulta ho jayega.
        val currentState = readTileState(clickTarget) ?: readTileState(foundTile)
        if (currentState == null || currentState != turnOn) {
            val clicked = try {
                clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (t: Throwable) {
                false
            }
            if (!clicked) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return DataResult.TILE_NOT_FOUND
            }
            SystemClock.sleep(CLICK_SETTLE_MS)
        }
        // v23: safal koshish ki report scan wali ki jagah legi —
        // kaun sa tile daba aur kaun si haalat chahiye thi.
        pendingScanReport =
            "OK tile=" + (scanTileLabel ?: "?") +
                " state=" + (if (turnOn) "on" else "off")
        performGlobalAction(GLOBAL_ACTION_BACK)
        return DataResult.SUCCESS
    }

    /** v18: asal ulta-karne wala kaam — sirf worker thread se bulana. */
    private fun runFlip(): DataOutcome {
        val search = searchPanelTile()
        val foundTile = search.tile ?: run {
            if (search.panelSeen) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return DataOutcome.TILE_NOT_FOUND
            }
            return DataOutcome.PANEL_NOT_OPENED
        }

        val clickTarget = search.clickTarget ?: foundTile
        val beforeState = readTileState(clickTarget) ?: readTileState(foundTile)
        // Toggle me dabana hamesha hai — haalat jo bhi ho, ek
        // dabav use ulta hi karega.
        val clicked = try {
            clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (t: Throwable) {
            false
        }
        if (!clicked) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return DataOutcome.TILE_NOT_FOUND
        }
        SystemClock.sleep(CLICK_SETTLE_MS)
        val afterState = rereadTileState(foundTile, clickTarget)
        performGlobalAction(GLOBAL_ACTION_BACK)
        val outcome = when {
            // Dabane ke baad ki haalat padh paye — wahi sach hai.
            afterState != null ->
                if (afterState) DataOutcome.TURNED_ON else DataOutcome.TURNED_OFF
            // Baad ki na mili, pehle ki mili thi — ulta hua hoga.
            beforeState != null ->
                if (beforeState) DataOutcome.TURNED_OFF else DataOutcome.TURNED_ON
            else -> DataOutcome.TOGGLED_UNKNOWN
        }
        // v23: safal koshish ki report scan wali ki jagah legi —
        // kaun sa tile daba aur dabane ke baad data kis haalat
        // me hai (pakka padh paye to wahi, warna unknown).
        pendingScanReport =
            "OK tile=" + (scanTileLabel ?: "?") +
                " state=" + when (outcome) {
                    DataOutcome.TURNED_ON -> "on"
                    DataOutcome.TURNED_OFF -> "off"
                    else -> "unknown"
                }
        return outcome
    }

    /**
     * v18: tile dabane ke baad ki haalat dobara padhne ki
     * koshish. Purana node taaza (refresh) karke dekho; woh na
     * bane to panel ke taaza ped se tile phir dhoondhkar padho.
     * Kuch koshishon me na mile to null.
     */
    private fun rereadTileState(
        tile: AccessibilityNodeInfo,
        clickTarget: AccessibilityNodeInfo
    ): Boolean? {
        var attempts = 0
        while (attempts < REREAD_ATTEMPTS) {
            try {
                tile.refresh()
            } catch (t: Throwable) {
                // Ped badal gaya hoga: neeche taaza khoj hai.
            }
            try {
                clickTarget.refresh()
            } catch (t: Throwable) {
                // Ped badal gaya hoga: neeche taaza khoj hai.
            }
            readTileState(clickTarget)?.let { return it }
            readTileState(tile)?.let { return it }
            val roots = collectRoots()
            for (root in roots) {
                val fresh = findDataTile(root) ?: continue
                val freshTarget = findClickableAncestor(fresh) ?: fresh
                readTileState(freshTarget)?.let { return it }
                readTileState(fresh)?.let { return it }
            }
            attempts++
            if (attempts < REREAD_ATTEMPTS) {
                SystemClock.sleep(REREAD_INTERVAL_MS)
            }
        }
        return null
    }

    /** v26: YouTube khoj ka ek ummeedwaar — dabane wala node aur uska naam. */
    private class YouTubeHit(
        val clickTarget: AccessibilityNodeInfo,
        val label: String
    )

    /**
     * v33 (v26 ki jagah): asal tap wali khoj — sirf worker
     * thread se bulana. ~10 second tak sakriya khidkiyon ke
     * ped baar-baar dekhe jate hain; kuch bhi dabta hai SIRF
     * tab, jab saamne wala ped YouTube (package
     * [YOUTUBE_PACKAGE]) ka ho. Pehla video-umeedwaar mila
     * to pehle ungli-jaisa GESTURE tap (v32 tak ka
     * ACTION_CLICK YouTube asal chhoona nahi maanta tha —
     * "TAPPED" hokar bhi video nahi khulta tha), phir
     * ACTION_CLICK; har koshish ke baad watch page ke
     * nishan se asal jaanch. Nishan mile tabhi TAPPED;
     * tap gaya par nishan na mile to imaandaar UNVERIFIED.
     * Kabhi scroll nahi hota.
     */
    private fun runYouTubeTap(): TapOutcome {
        ytLabels.clear()
        val deadline = SystemClock.uptimeMillis() + YOUTUBE_TAP_BUDGET_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val roots = collectRoots()
            var hit: YouTubeHit? = null
            for (root in roots) {
                if (root.packageName?.toString() != YOUTUBE_PACKAGE) {
                    continue
                }
                hit = findFirstVideoResult(root)
                if (hit != null) break
            }
            if (hit == null) {
                roots.forEach { root ->
                    try {
                        root.recycle()
                    } catch (t: Throwable) {
                        // Pehle se saaf: kuch nahi karna.
                    }
                }
                SystemClock.sleep(POLL_INTERVAL_MS)
                continue
            }
            // Umeedwaar mila — ped abhi zinda hai, tap abhi karo.
            return tapAndVerify(hit)
        }
        saveYouTubeTapDiag(
            "NOT_FOUND seen: [" + ytLabels.joinToString(" | ") + "]"
        )
        return TapOutcome.NOT_FOUND
    }

    /** Mile hue ummeedwaar par tap + asal jaanch. */
    private fun tapAndVerify(hit: YouTubeHit): TapOutcome {
        var method = "none"
        var dispatched = false
        val bounds = Rect()
        try {
            hit.clickTarget.getBoundsInScreen(bounds)
        } catch (t: Throwable) {
            // Ped badal gaya: bounds khaali rahenge, click wala
            // raasta phir bhi aazmaya jayega.
        }
        if (bounds.width() > 0 && bounds.height() > 0) {
            method = "gesture"
            dispatched = tapAtPoint(
                bounds.centerX().toFloat(),
                bounds.centerY().toFloat()
            )
        }
        if (dispatched && verifyWatchPage()) {
            saveYouTubeTapDiag(
                "TAPPED method=$method verified label=\"" +
                    hit.label.take(MAX_SCAN_LABEL_CHARS) + "\""
            )
            return TapOutcome.TAPPED
        }
        val clicked = try {
            hit.clickTarget.performAction(
                AccessibilityNodeInfo.ACTION_CLICK
            )
        } catch (t: Throwable) {
            false
        }
        if (clicked) {
            method = if (dispatched) "gesture+click" else "click"
            if (verifyWatchPage()) {
                saveYouTubeTapDiag(
                    "TAPPED method=$method verified label=\"" +
                        hit.label.take(MAX_SCAN_LABEL_CHARS) + "\""
                )
                return TapOutcome.TAPPED
            }
        }
        saveYouTubeTapDiag(
            "UNVERIFIED method=$method label=\"" +
                hit.label.take(MAX_SCAN_LABEL_CHARS) + "\""
        )
        return TapOutcome.UNVERIFIED
    }

    /** v33: screen par (x, y) par ungli-jaisa ek tap. */
    private fun tapAtPoint(x: Float, y: Float): Boolean {
        return try {
            val path = Path()
            path.moveTo(x, y)
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(path, 0, 70)
                )
                .build()
            val latch = CountDownLatch(1)
            val accepted = AtomicBoolean(false)
            val ok = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(
                        gestureDescription: GestureDescription?
                    ) {
                        accepted.set(true)
                        latch.countDown()
                    }

                    override fun onCancelled(
                        gestureDescription: GestureDescription?
                    ) {
                        latch.countDown()
                    }
                },
                null
            )
            if (ok) {
                latch.await(900, TimeUnit.MILLISECONDS)
                accepted.get()
            } else {
                false
            }
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * v33: tap ke baad ~2.8 s tak dekho — YouTube ka watch
     * page khula ya nahi. Nishan: player wale label ("Full
     * screen", "Pause", "Minimize", "Watch later"...).
     * Nishan na mile to sach UNVERIFIED rahega — jhootha
     * TAPPED kabhi nahi.
     */
    private fun verifyWatchPage(): Boolean {
        val deadline = SystemClock.uptimeMillis() + VERIFY_WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val roots = collectRoots()
            var watchSign = false
            for (root in roots) {
                if (root.packageName?.toString() != YOUTUBE_PACKAGE) {
                    continue
                }
                if (treeHasWatchSign(root)) {
                    watchSign = true
                    break
                }
            }
            roots.forEach { root ->
                try {
                    root.recycle()
                } catch (t: Throwable) {
                    // Pehle se saaf: kuch nahi karna.
                }
            }
            if (watchSign) return true
            SystemClock.sleep(VERIFY_POLL_MS)
        }
        return false
    }

    /** Ek ped me watch page ke nishan hai ya nahi. */
    private fun treeHasWatchSign(root: AccessibilityNodeInfo): Boolean {
        for (node in collectInDocumentOrder(root)) {
            val label = nodeLabel(node).lowercase(Locale.ROOT)
            if (label.contains("full screen") ||
                label.contains("ফুল স্ক্রিন") ||
                label == "pause" ||
                label.contains("minimize") ||
                label.contains("watch later") ||
                label.contains("add to queue")
            ) {
                return true
            }
        }
        return false
    }

    /**
     * v26: ek YouTube ped me pehla asal video-umeedwaar.
     * Khoj results wali list (pehla scrollable node) ke andar
     * bandhi hai — upar wale filter-chip aur doosre hisse
     * ummeedwaar nahi bante; scrollable hissa na mile to
     * poora ped hi khoj ka maidhan hai. Document order (jaise
     * screen par dikhta hai) me pehla woh node chunta hai jo
     * naam wala ho, khud clickable ho ya clickable purvaj
     * rakhta ho, aur jiski santaan me avadhi (duration) jaisa
     * "12:34" pattern mile. Woh na mile to dusri chaal: pehla
     * naam-wala clickable ummeedwaar (uska naam report me
     * rahega — galat chuna gaya to wahan dikhega).
     */
    private fun findFirstVideoResult(
        root: AccessibilityNodeInfo
    ): YouTubeHit? {
        val container = findScrollableContainer(root) ?: root
        var firstLoose: YouTubeHit? = null
        for (node in collectInDocumentOrder(container)) {
            if (node === container) continue
            val label = nodeLabel(node)
            if (label.isNotEmpty() && ytLabels.size < MAX_YOUTUBE_LABELS) {
                ytLabels.add(label.take(MAX_SCAN_LABEL_CHARS))
            }
            if (label.isEmpty()) continue
            if (isNonVideoLabel(label)) continue
            val target = findClickableAncestor(node) ?: continue
            val hit = YouTubeHit(target, label)
            if (subtreeHasDuration(node)) {
                return hit
            }
            if (firstLoose == null) {
                firstLoose = hit
            }
        }
        return firstLoose
    }

    /**
     * v26: ped me pehla scrollable hissa (YouTube par results
     * wali RecyclerView) — document order me. Na mile to null.
     */
    private fun findScrollableContainer(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        for (node in collectInDocumentOrder(root)) {
            try {
                if (node.isScrollable) return node
            } catch (t: Throwable) {
                // Ped badal raha hai: aage badho.
            }
        }
        return null
    }

    /**
     * v26: ped ko document order (upar se neeche, jaise
     * screen par dikhta hai) me ikattha karo — [MAX_NODES]
     * par bandha. findDataTile wali LIFO chaal yahan
     * jaan-boojh kar nahi li gayi: "pehla result" ke liye
     * asal dikhai wala kram hi chahiye.
     */
    private fun collectInDocumentOrder(
        root: AccessibilityNodeInfo
    ): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (!stack.isEmpty() && out.size < MAX_NODES) {
            val node = stack.removeLast()
            out.add(node)
            val children = ArrayList<AccessibilityNodeInfo>()
            for (i in 0 until node.childCount) {
                try {
                    node.getChild(i)?.let { child -> children.add(child) }
                } catch (t: Throwable) {
                    // Ped beech me badal gaya: jo mila wahi kaafi.
                }
            }
            // Bachcho ko ulte kram me rakho, taaki nikaalne
            // par asal (screen wala) kram mile.
            for (child in children.asReversed()) {
                stack.addLast(child)
            }
        }
        return out
    }

    /**
     * v26: node ki santaan (khud samet) me kahin avadhi
     * jaisa "12:34" / "1:02:03" pattern hai kya — asli
     * video ki sabse pakki pehchaan. Bandhi ginati par rukti hai.
     */
    private fun subtreeHasDuration(node: AccessibilityNodeInfo): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(node)
        var visited = 0
        while (!stack.isEmpty() && visited < MAX_DURATION_NODES) {
            val current = stack.removeLast()
            visited++
            if (DURATION_PATTERN.containsMatchIn(nodeLabel(current))) {
                return true
            }
            for (i in 0 until current.childCount) {
                try {
                    current.getChild(i)?.let { child -> stack.addLast(child) }
                } catch (t: Throwable) {
                    // Ped beech me badal gaya: jo mila wahi kaafi.
                }
            }
        }
        return false
    }

    /** v26: shelf ke naam / vigyapan wale hisse video nahi hote. */
    private fun isNonVideoLabel(label: String): Boolean {
        val lower = label.lowercase(Locale.ROOT)
        return lower == "shorts" ||
            lower.startsWith("ad ") || lower.startsWith("ad·") ||
            lower.startsWith("ad ·") || lower.contains("sponsored")
    }

    /**
     * v26: YouTube tap ki report band karo — companion ke
     * [lastYouTubeTapReport] me aur SharedPreferences
     * "jarvis_diag" me, bilkul panel-scan report ki tarah.
     * Report likhne ki gadbad asal natije ko kabhi nahi rokti.
     */
    private fun saveYouTubeTapDiag(report: String) {
        lastYouTubeTapReport = report
        try {
            getSharedPreferences(DIAG_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(DIAG_KEY_LAST_YOUTUBE_TAP, report)
                .apply()
        } catch (t: Throwable) {
            // Report gauṇ hai: tap ka sach isse bada hai.
        }
    }

    /**
     * Panel ke ped (tree) me data tile dhoondho. Har node ke
     * text + content-description ko milakar naam banate hain aur
     * [dataLabelScore] se number dete hain; sabse zyada number
     * wala node hi tile hai. "Data Saver"/"Data roaming" jaise
     * naam jaan-boojh kar 0 number par hain — woh mobile data
     * ka switch nahi hai.
     */
    private fun findDataTile(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (!stack.isEmpty() && visited < MAX_NODES) {
            val node = stack.removeLast()
            visited++
            val score = dataLabelScore(node)
            if (score > bestScore) {
                bestScore = score
                best = node
            }
            // v23: report ke liye sabse bada number yaad rakho.
            if (score > scanBestScore) {
                scanBestScore = score
            }
            for (i in 0 until node.childCount) {
                try {
                    node.getChild(i)?.let { child -> stack.addLast(child) }
                } catch (t: Throwable) {
                    // Ped beech me badal gaya: jo mila wahi kaafi.
                }
            }
        }
        return best
    }

    /** Node ka dikhai dene wala naam (text + varnan), saaf kata hua roop. */
    private fun nodeLabel(node: AccessibilityNodeInfo): String =
        ((node.text?.toString() ?: "") + " " +
            (node.contentDescription?.toString() ?: "")).trim()

    /** Node ke naam ko data-tile hone ke number do (0 = nahi hai). */
    private fun dataLabelScore(node: AccessibilityNodeInfo): Int {
        val shown = nodeLabel(node)
        // v23: scan report ke liye mile hue naam yaad rakho —
        // pehle 18 alag naam, har naam kata hua (40 akshar).
        if (shown.isNotEmpty() && scanLabels.size < MAX_SCAN_LABELS) {
            scanLabels.add(shown.take(MAX_SCAN_LABEL_CHARS))
        }
        val label = shown.lowercase(Locale.ROOT)
        if (label.isEmpty()) return 0
        if (label.contains("saver") || label.contains("roaming")) return 0
        return when {
            // v20: OriginOS jaisi skins par data tile par "Mobile data"
                // nahi, upyog likha hota hai ("62.25 GB") - wahi pehchan hai.
                DATA_USAGE_PATTERN.containsMatchIn(label) ||
                (label.contains("mobile") && label.contains("data")) ||
                label.contains("मोबाइल डेटा") ||
                label.contains("মোবাইল ডেটা") || label.contains("মোবাইল ডাটা") -> 3
            label == "data" || label == "डेटा" ||
                label == "ডেটা" || label == "ডাটা" -> 2
            label.contains("cellular") -> 2
            label.contains("internet") && label.contains("data") -> 1
            else -> 0
        }
    }

    /**
     * Tile ki vartamaan haalat (true = on, false = off), jahan
     * se padh sakte hain: checkable node ka checked, state
     * description (Android 11+), ya naam/description me likha
     * on/off. Kuch na mile to null — bulane wala ek baar dabakar
     * koshish karega.
     */
    private fun readTileState(node: AccessibilityNodeInfo): Boolean? {
        try {
            if (node.isCheckable) {
                return node.isChecked
            }
        } catch (t: Throwable) {
            // Aage ke raaste aazmao.
        }
        val hay = (
            (node.text?.toString() ?: "") + " " +
                (node.contentDescription?.toString() ?: "") + " " +
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    node.stateDescription?.toString() ?: ""
                } else {
                    ""
                }
            ).lowercase(Locale.ROOT)
        return when {
            hay.contains("चालू") || hay.contains("চালু") ||
                ON_WORD.containsMatchIn(hay) -> true
            hay.contains("बंद") || hay.contains("বন্ধ") ||
                OFF_WORD.containsMatchIn(hay) -> false
            else -> null
        }
    }

    /** Tile node se upar chadhkar pehla clickable node dhoondho. */
    private fun findClickableAncestor(
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var steps = 0
        while (current != null && steps < MAX_CLICK_ANCESTORS) {
            try {
                if (current.isClickable) {
                    return current
                }
                current = current.parent
            } catch (t: Throwable) {
                return null
            }
            steps++
        }
        return null
    }

    /**
     * Panel ke mumkin ped: sabse pehle SystemUI ke (panel wahin
     * rehta hai), phir jo bhi sakriya khidki mile. rootInActiveWindow
     * aur (config me retrieve-interactive-windows jhanda hai isliye)
     * saari khidkiyon ke ped — vivo jaise phones par panel alag
     * khidki me bhi aa sakta hai.
     */
    private fun collectRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            rootInActiveWindow?.let { roots.add(it) }
        } catch (t: Throwable) {
            // Is pal koi sakriya khidki nahi: neeche wali koshish.
        }
        try {
            windows?.forEach { window ->
                try {
                    window.root?.let { root -> roots.add(root) }
                } catch (t: Throwable) {
                    // Ek khidki ka ped na mile to baaki phir bhi.
                }
            }
        } catch (t: Throwable) {
            // Khidkiyon ki list hi na mile: jo hai wahi kaafi.
        }
        return roots.sortedBy { root ->
            if (root.packageName?.toString() == SYSTEMUI_PACKAGE) 0 else 1
        }
    }

    companion object {
        /** Chalti hui service — onServiceConnected/onDestroy sambhalte hain. */
        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        /**
         * v23: aakhiri panel-scan ki report — har data koshish
         * ke aakhir me service bhar deti hai. App ki screen
         * SharedPreferences "jarvis_diag" se padhti hai (alag
         * process ka raasta); ye roop isi process me turant
         * chahne walon ke liye hai.
         */
        @Volatile
        var lastScanReport: String? = null
            private set

        /**
         * v26: aakhiri YouTube-tap koshish ki report — har
         * koshish ke aakhir me service bhar deti hai
         * (TAPPED label="..." / NOT_FOUND seen: [...] /
         * BUSY / SERVICE_OFF). App ki screen SharedPreferences
         * "jarvis_diag" se padh sakti hai.
         */
        @Volatile
        var lastYouTubeTapReport: String? = null
            private set

        /**
         * Service chalu hai ya nahi. Pehle chalti hui instance
         * dekho; woh na ho to AccessibilityManager ki chalu
         * services wali list me apna naam dhoondho (service
         * Settings me ON ho par abhi bandhi na ho, to bhi sach
         * ke kareeb jawab mile).
         */
        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            val manager = context.getSystemService(
                Context.ACCESSIBILITY_SERVICE
            ) as? AccessibilityManager ?: return false
            val enabledServices = try {
                manager.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                )
            } catch (t: Throwable) {
                return false
            }
            val mine = ComponentName(
                context, JarvisAccessibilityService::class.java
            )
            return enabledServices.any { info ->
                val component = try {
                    ComponentName.unflattenFromString(info.id)
                } catch (t: Throwable) {
                    null
                }
                component == mine ||
                    info.id.startsWith(context.packageName + "/")
            }
        }

        /**
         * Kahin se bhi mobile-data toggle ki koshish: service
         * band ho to turant [DataResult.SERVICE_OFF], warna
         * chalti hui service ko kaam saunp do (natija callback
         * se, background thread par).
         */
        fun toggleMobileData(
            context: Context,
            turnOn: Boolean,
            callback: (DataResult) -> Unit
        ) {
            val service = instance
            if (service == null) {
                if (!isEnabled(context)) {
                    callback(DataResult.SERVICE_OFF)
                    return
                }
                // Settings me ON hai par instance abhi bandha
                // nahi — is pal kaam nahi ho payega.
                callback(DataResult.SERVICE_OFF)
                return
            }
            service.toggleMobileData(turnOn, callback)
        }

        /**
         * v18: kahin se bhi bina-disha toggle ki koshish:
         * service band ho to turant [DataOutcome.SERVICE_OFF],
         * warna chalti hui service ko kaam saunp do (natija
         * callback se, background thread par).
         */
        fun flipMobileData(
            context: Context,
            callback: (DataOutcome) -> Unit
        ) {
            val service = instance
            if (service == null) {
                // Settings me ON ho tab bhi instance abhi bandha
                // na ho to is pal kaam nahi ho payega.
                callback(DataOutcome.SERVICE_OFF)
                return
            }
            service.flipMobileData(callback)
        }

        /**
         * v56: kahin se bhi panel-tile scan ki koshish:
         * service band ho to turant [TileScanOutcome.SERVICE_OFF],
         * warna chalti hui service ko kaam saunp do (natija
         * callback se, worker thread par).
         */
        fun scanQuickSettingsTiles(
            context: Context,
            callback: (TileScanOutcome, List<QuickTile>) -> Unit
        ) {
            val service = instance
            if (service == null) {
                callback(TileScanOutcome.SERVICE_OFF, emptyList())
                return
            }
            service.scanQuickSettingsTiles(callback)
        }

        /**
         * v26: kahin se bhi YouTube ke pehle-result par tap
         * ki koshish: service band ho to turant
         * [TapOutcome.SERVICE_OFF] (report bhi band karke),
         * warna chalti hui service ko kaam saunp do (natija
         * callback se, background thread par).
         */
        fun tapFirstYouTubeResult(
            context: Context,
            callback: (TapOutcome) -> Unit
        ) {
            val service = instance
            if (service == null) {
                lastYouTubeTapReport = "SERVICE_OFF"
                try {
                    context.getSharedPreferences(
                        DIAG_PREFS_NAME, Context.MODE_PRIVATE
                    ).edit()
                        .putString(DIAG_KEY_LAST_YOUTUBE_TAP, "SERVICE_OFF")
                        .apply()
                } catch (t: Throwable) {
                    // Report gauṇ hai: sachha natija callback se jayega.
                }
                callback(TapOutcome.SERVICE_OFF)
                return
            }
            service.tapFirstYouTubeResult(callback)
        }

        /**
         * v37: kahin se bhi screen ka saar maango: service
         * band ho (ya instance abhi bandha na ho) to turant
         * null, warna chalti hui service ko kaam saunp do
         * (natija callback se, background thread par).
         */
        fun captureScreen(
            context: Context,
            callback: (ScreenSnapshot?) -> Unit
        ) {
            val service = instance
            if (service == null) {
                callback(null)
                return
            }
            service.captureScreen(callback)
        }

        /**
         * v39: kahin se bhi screen ki tasveer maango:
         * service band ho (ya instance abhi bandha na ho)
         * to turant null, warna chalti hui service ko kaam
         * saunp do (natija callback se).
         */
        fun captureScreenshot(
            context: Context,
            callback: (Bitmap?) -> Unit
        ) {
            val service = instance
            if (service == null) {
                callback(null)
                return
            }
            service.captureScreenshot(callback)
        }

        /** v37: kahin se bhi naam wali cheez dabao — service band ho to turant false. */
        fun tapScreenText(
            context: Context,
            text: String,
            callback: (Boolean) -> Unit
        ) {
            val service = instance
            if (service == null) {
                callback(false)
                return
            }
            service.tapScreenText(text, callback)
        }

        /** v37: kahin se bhi (x, y) par tap — service band ho to turant false. */
        fun tapScreenPoint(
            context: Context,
            x: Int,
            y: Int,
            callback: (Boolean) -> Unit
        ) {
            val service = instance
            if (service == null) {
                callback(false)
                return
            }
            service.tapScreenPoint(x, y, callback)
        }

        /** v37: kahin se bhi screen scroll — service band ho to turant false. */
        fun scrollScreen(
            context: Context,
            down: Boolean,
            callback: (Boolean) -> Unit
        ) {
            val service = instance
            if (service == null) {
                callback(false)
                return
            }
            service.scrollScreen(down, callback)
        }

        /** v38: kahin se bhi pakka scroll hukm (~60% unchai wala swipe) — service band ho to turant false. */
        fun scrollCommand(
            context: Context,
            down: Boolean,
            callback: (Boolean) -> Unit
        ) {
            val service = instance
            if (service == null) {
                callback(false)
                return
            }
            service.scrollCommand(down, callback)
        }

        /**
         * v42: kahin se bhi pakka scroll hukm report ke saath
         * (node pehle, phir swipe) — service band ho to turant
         * [MECHANISM_NONE] wali haar report.
         */
        fun scrollCommandEx(
            context: Context,
            down: Boolean,
            callback: (ScrollReport) -> Unit
        ) {
            val service = instance
            if (service == null) {
                callback(
                    ScrollReport(false, MECHANISM_NONE, "service band hai")
                )
                return
            }
            service.scrollCommandEx(down, callback)
        }

        /** v42: scroll report ki mashinari ke naam — report me yahi bandhte hain. */
        const val MECHANISM_NODE = "node"
        const val MECHANISM_GESTURE = "gesture"
        const val MECHANISM_GESTURE_RETRY = "gesture-retry"
        const val MECHANISM_NONE = "none"

        /** v37: kahin se bhi focus wale khane me likho — service band ho to turant false. */
        fun typeScreenText(
            context: Context,
            text: String,
            callback: (Boolean) -> Unit
        ) {
            val service = instance
            if (service == null) {
                callback(false)
                return
            }
            service.typeScreenText(text, callback)
        }

        /** v37: kahin se bhi Back/Home global action — service band ho to turant false. */
        fun screenGlobalAction(
            context: Context,
            action: Int,
            callback: (Boolean) -> Unit
        ) {
            val service = instance
            if (service == null) {
                callback(false)
                return
            }
            service.screenGlobalAction(action, callback)
        }

        /** v45: kahin se bhi naam wali cheez der-dabao — service band ho to turant false. */
        fun longPressScreenText(
            context: Context,
            text: String,
            callback: (Boolean) -> Unit
        ) {
            val service = instance
            if (service == null) {
                callback(false)
                return
            }
            service.longPressScreenText(text, callback)
        }

        /** v45: kahin se bhi (x, y) par long-press — service band ho to turant false. */
        fun longPressScreenPoint(
            context: Context,
            x: Int,
            y: Int,
            callback: (Boolean) -> Unit
        ) {
            val service = instance
            if (service == null) {
                callback(false)
                return
            }
            service.longPressScreenPoint(x, y, callback)
        }

        /** v45: kahin se bhi poori-screen swipe — service band ho to turant false. */
        fun swipeScreen(
            context: Context,
            direction: SwipeDirection,
            callback: (Boolean) -> Unit
        ) {
            val service = instance
            if (service == null) {
                callback(false)
                return
            }
            service.swipeScreen(direction, callback)
        }

        /** v45: kahin se bhi naam wale khane me likho — service band ho to turant false. */
        fun typeIntoScreenText(
            context: Context,
            label: String,
            text: String,
            callback: (Boolean) -> Unit
        ) {
            val service = instance
            if (service == null) {
                callback(false)
                return
            }
            service.typeIntoScreenText(label, text, callback)
        }

        /** v45: kahin se bhi focus wale khane par IME enter — service band ho to turant false. */
        fun submitFocusedField(
            context: Context,
            callback: (Boolean) -> Unit
        ) {
            val service = instance
            if (service == null) {
                callback(false)
                return
            }
            service.submitFocusedField(callback)
        }

        /** v45: kahin se bhi submit batan ka naam dhoondho — service band ho to turant null. */
        fun findSubmitButton(
            context: Context,
            callback: (String?) -> Unit
        ) {
            val service = instance
            if (service == null) {
                callback(null)
                return
            }
            service.findSubmitButton(callback)
        }

        private const val SYSTEMUI_PACKAGE = "com.android.systemui"
        // v23: 2.5s se 8s — vivo ka panel sajne me der lagata
        // hai; tile ped par chadhne se pehle khoj khatm ho jati
        // thi, ye ek pakka shak hai.
        private const val TOTAL_BUDGET_MS = 8_000L
        private const val POLL_INTERVAL_MS = 150L
        private const val MAX_SCAN_LABELS = 18
        private const val MAX_SCAN_LABEL_CHARS = 40
        // v56: START button — panel se zyada se zyada kitne tile yaad rakhein.
        private const val MAX_PANEL_TILES = 80
        private const val DIAG_PREFS_NAME = "jarvis_diag"
        private const val DIAG_KEY_LAST_PANEL_SCAN = "last_panel_scan"
        // v26: YouTube tap ke bandobosto.
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val YOUTUBE_TAP_BUDGET_MS = 10_000L
        private const val VERIFY_WAIT_MS = 2_800L
        private const val VERIFY_POLL_MS = 300L
        private const val MAX_YOUTUBE_LABELS = 10
        private const val MAX_DURATION_NODES = 120
        private const val DIAG_KEY_LAST_YOUTUBE_TAP = "last_youtube_tap"
        private const val CLICK_SETTLE_MS = 350L
        // v37: screen-agent wale bandobosto — saar me zyada
        // se zyada 60 hisse, har naam 90 akshar tak kata
        // hua; scroll wala swipe ~320 ms ka.
        private const val MAX_SCREEN_ELEMENTS = 60
        private const val MAX_SCREEN_LABEL_CHARS = 90
        private const val SCROLL_DURATION_MS = 320L
        // v38: hukm wale scroll ka swipe samay (~60% unchai ka safar).
        private const val SCROLL_COMMAND_DURATION_MS = 350L
        // v42: pehla swipe radd ho to dobara wala lamba-dheema
        // swipe; aur node wale ummeedwaar ka kam se kam aakaar
        // (poori screen ka hissa) — chhote tukde (chip kataar,
        // seek bar) scroll hukm ke nishaane nahi hain.
        private const val SCROLL_COMMAND_RETRY_DURATION_MS = 550L
        private const val SCROLL_NODE_MIN_AREA_FRACTION = 0.08
        // v39: tasveer ki dar-seema — isse jaldi dusri maang
        // par pichhli tasveer hi laut-ti hai (system ka
        // interval error ~1 s ke aas-paas aata hai).
        private const val SHOT_MIN_INTERVAL_MS = 1_200L
        // v45: long-press ka ungli-thahrna (~600 ms — aam
        // Android long-press seema ke aas-paas), poori-screen
        // swipe ka safar (screen ka 65%) aur samay (~300 ms),
        // aur naam-wale likhne me label se khane ki adhiktam
        // doori (label ke neeche wala khana, px).
        private const val LONG_PRESS_DURATION_MS = 600L
        private const val SWIPE_DURATION_MS = 300L
        private const val SWIPE_TRAVEL_FRACTION = 0.65f
        private const val NEAREST_LABEL_MAX_GAP_PX = 600
        // v45: submit (Search/Go/Done) wale naam — hubahu
        // walon ko pehchan me aage rakha jata hai; gair-Latin
        // shabd "naam me hai" se, Latin shabd seema-bandh
        // pattern se milte hain ("Google" ka "go" galat
        // milan na bane)।
        private val SUBMIT_EXACT_WORDS = setOf(
            "search", "go", "done",
            "সার্চ", "খুঁজুন", "অনুসন্ধান", "সম্পন্ন",
            "खोजें", "खोजो", "सर्च"
        )
        private val SUBMIT_CONTAIN_WORDS = listOf(
            "সার্চ", "খুঁজুন", "অনুসন্ধান", "সম্পন্ন",
            "खोजें", "खोजो", "सर्च", "खोज"
        )
        private val SUBMIT_LATIN_PATTERN =
            Regex("""(^|[^a-z])(search|go|done)([^a-z]|$)""")
        private val WHITESPACE_RUN = Regex("""\s+""")
        private const val REREAD_ATTEMPTS = 3
        private const val REREAD_INTERVAL_MS = 200L
        private const val MAX_NODES = 800
        private const val MAX_CLICK_ANCESTORS = 6

        private val ON_WORD = Regex("""(^|[\s,:])on([\s,:.]|$)""")
        private val OFF_WORD = Regex("""(^|[\s,:])off([\s,:.]|$)""")
        private val DATA_USAGE_PATTERN = Regex("""\d+(\.\d+)?\s*(gb|mb|kb)\b""")
        // v26: video ki avadhi jaisa pattern — "12:34", "1:02:03".
        private val DURATION_PATTERN = Regex("""\d{1,2}:\d{2}""")
    }
}
