package com.aship.jarvis.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import java.util.concurrent.Executors

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
 * Saara kaam service ke apne ek background thread par hota hai;
 * bulane wala kabhi nahi rukta, natija callback se milta hai.
 * Kul intezaar ~2.5 second se bandha hai.
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

    private val worker = Executors.newSingleThreadExecutor()

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
                val result = try {
                    runToggle(turnOn)
                } catch (t: Throwable) {
                    DataResult.TILE_NOT_FOUND
                }
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
                val result = try {
                    runFlip()
                } catch (t: Throwable) {
                    DataOutcome.TILE_NOT_FOUND
                }
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
        if (!performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)) {
            return PanelSearch(panelSeen = false, tile = null, clickTarget = null)
        }
        val deadline = SystemClock.uptimeMillis() + TOTAL_BUDGET_MS
        var panelSeen = false
        var tile: AccessibilityNodeInfo? = null
        while (tile == null && SystemClock.uptimeMillis() < deadline) {
            val roots = collectRoots()
            for (root in roots) {
                if (root.packageName?.toString() == SYSTEMUI_PACKAGE) {
                    panelSeen = true
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
        val foundTile = tile ?: return PanelSearch(panelSeen, null, null)
        return PanelSearch(
            panelSeen,
            foundTile,
            findClickableAncestor(foundTile) ?: foundTile
        )
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
        return when {
            // Dabane ke baad ki haalat padh paye — wahi sach hai.
            afterState != null ->
                if (afterState) DataOutcome.TURNED_ON else DataOutcome.TURNED_OFF
            // Baad ki na mili, pehle ki mili thi — ulta hua hoga.
            beforeState != null ->
                if (beforeState) DataOutcome.TURNED_OFF else DataOutcome.TURNED_ON
            else -> DataOutcome.TOGGLED_UNKNOWN
        }
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

    /** Node ke naam ko data-tile hone ke number do (0 = nahi hai). */
    private fun dataLabelScore(node: AccessibilityNodeInfo): Int {
        val label = (
            (node.text?.toString() ?: "") + " " +
                (node.contentDescription?.toString() ?: "")
            ).trim().lowercase(Locale.ROOT)
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

        private const val SYSTEMUI_PACKAGE = "com.android.systemui"
        private const val TOTAL_BUDGET_MS = 2_500L
        private const val POLL_INTERVAL_MS = 150L
        private const val CLICK_SETTLE_MS = 350L
        private const val REREAD_ATTEMPTS = 3
        private const val REREAD_INTERVAL_MS = 200L
        private const val MAX_NODES = 800
        private const val MAX_CLICK_ANCESTORS = 6

        private val ON_WORD = Regex("""(^|[\s,:])on([\s,:.]|$)""")
        private val OFF_WORD = Regex("""(^|[\s,:])off([\s,:.]|$)""")
        private val DATA_USAGE_PATTERN = Regex("""\d+(\.\d+)?\s*(gb|mb|kb)\b""")
    }
}
