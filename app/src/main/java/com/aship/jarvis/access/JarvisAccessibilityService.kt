package com.aship.jarvis.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.content.ComponentName
import android.content.Context
import android.os.Build
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

        private const val SYSTEMUI_PACKAGE = "com.android.systemui"
        // v23: 2.5s se 8s — vivo ka panel sajne me der lagata
        // hai; tile ped par chadhne se pehle khoj khatm ho jati
        // thi, ye ek pakka shak hai.
        private const val TOTAL_BUDGET_MS = 8_000L
        private const val POLL_INTERVAL_MS = 150L
        private const val MAX_SCAN_LABELS = 18
        private const val MAX_SCAN_LABEL_CHARS = 40
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
