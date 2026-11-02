package com.aship.jarvis.ai

import android.content.Context
import java.util.Locale

/**
 * v46: SCREEN-AGENT KA SURAKSHA-PARDA (safety layer) —
 * banking / UPI / payment apps ke andar agent KABHI
 * kaam nahi karta.
 *
 * Kyun: aisi apps me khud-chaal (automation) user ke
 * khaaton ke liye asal khatra hai — galat tap seedha
 * paisa hila sakta hai, aur ye apps khud Accessibility
 * wali khud-chaal ko pehchankar khaata band tak kar
 * sakti hain. Isliye niyam sakht aur saada hai: aisi
 * app saamne aate hi screen-kaam wahin rukta hai —
 * na model bulta hai, na tasveer banti hai, na koi
 * tap/type/scroll chalta hai (rukawat node-saar wali
 * pehchan par hoti hai, tasveer se pehle)।
 *
 * Pehchan do darjon par:
 * 1. [DENIED_PACKAGES] — Bharat ki badi banking/UPI/
 *    payment apps ki bandhi kataar (neeche). Ek hi
 *    jagah bandhi hai taaki sambhalna aasan rahe; koi
 *    galat entry kisi na-maujood package par ho to woh
 *    be-asar hai (woh package kabhi saamne aayegi hi
 *    nahi), aur kisi aam app ka package is kataar me
 *    jaanboojh kar NAHI rakha gaya hai.
 * 2. Naam wala dusra darja — jo banking app kataar me
 *    na ho, uska launcher-naam (PackageManager se —
 *    wahi naam jo launcher dikhata hai) Bank / Banking /
 *    UPI / ব্যাংক / ব্যাংকিং rakhe to bhi mana. Isse
 *    aage koi andaaz (heuristic) nahi lagta — screen ke
 *    andar ki likhai par faisla kabhi nahi hota.
 *
 * Kabhi mana NAHI: khud Jarvis app aur SystemUI —
 * unke bina agent chal hi nahi sakta.
 *
 * Janboojh kar kataar se BAHAR:
 * - Amazon shopping app (com.amazon.mShop.android.
 *   shopping): Amazon Pay usi ke andar rehta hai, par
 *   poori app band karna matlab aam khareed-bikri wale
 *   kaam bhi band — naap se zyada rok hoti.
 * - WhatsApp: WhatsApp Pay usi ke andar hai, par
 *   WhatsApp agent ke aam kaamon ki sabse badi app
 *   hai; use rokna matlab agent hi aadha band.
 * - slice / Freo ke package-naam pakke bharose ke
 *   nahi the — andaze par entry nahi daali.
 */
object ScreenSafety {

    /** Kataar me bandhe hue banking/UPI/payment packages. */
    private val DENIED_PACKAGES = setOf(
        // UPI / bhugtan (payments) apps
        "com.google.android.apps.nbu.paisa.user", // Google Pay
        "com.phonepe.app", // PhonePe
        "net.one97.paytm", // Paytm
        "org.npci.upiapp", // BHIM
        "com.hdfcbank.payzapp", // PayZapp
        "com.freecharge.android", // Freecharge
        "com.mobikwik_new", // Mobikwik
        "com.dreamplug.androidapp", // CRED
        // Bank apps
        "com.sbi.lotus", // SBI YONO
        "com.sbi.SBIFreedomPlus", // SBI YONO Lite / Anywhere
        "com.snapwork.hdfc", // HDFC Bank MobileBanking
        "com.csam.icici.bank.imobile", // ICICI iMobile Pay
        "com.icicibank.pockets", // ICICI Pockets
        "com.axis.mobile", // Axis Mobile
        "com.kotak811.kotak811", // Kotak 811
        "com.bankofbaroda.mconnect", // Bank of Baroda M-Connect
        "com.infosys.fintech.pnb", // PNB ONE
        "com.canarabank.mobility", // Canara Bank (ai1 / mobility)
        "com.idfcfirstbank.mybankingapp", // IDFC First Bank
        "com.jupiter.money" // Jupiter (neo-banking)
    )

    /**
     * open_app ke bole gaye naamon ke liye bandhi
     * pehchan — model aksar naam bolta hai, package
     * nahi. Lambey/pakke naam substring se; chhote
     * aam shabd (axis, sbi, cred...) shabd-seema par,
     * taaki kisi aur naam ke beech me ghuskar galat
     * mana na ho. Ye sirf open_app wale kadam ke liye
     * hai — asal package mil jaye to wahi faisla hai
     * ([denyReason]), ye naam-jaanch uski jagah nahi.
     */
    private val DENIED_NAME_PARTS = listOf(
        "google pay", "phonepe", "paytm", "bhim", "yono",
        "payzapp", "freecharge", "mobikwik", "hdfc",
        "icici", "imobile", "kotak", "canara", "idfc",
        "jupiter"
    )
    private val DENIED_NAME_WORDS = listOf(
        "gpay", "axis", "sbi", "pnb", "cred", "upi", "bank"
    )

    /** Naam me banking shabd — Latin shabd-seema + Bangla substring. */
    private val BANKING_WORD_REGEX =
        Regex("""\b(bank|banking|upi)\b""", RegexOption.IGNORE_CASE)

    /**
     * [packageName] mana hai to wajah ("package" ya
     * launcher-naam), warna null. Pehchan kram: apni
     * app / SystemUI chhoot -> bandhi kataar -> naam.
     */
    fun denyReason(context: Context, packageName: String): String? {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return null
        if (pkg == context.packageName) return null
        if (pkg == "com.android.systemui") return null
        if (pkg in DENIED_PACKAGES) return "package"
        val label = appLabel(context, pkg)
        if (label != null && labelLooksBanking(label)) {
            return "app label \"" + label + "\""
        }
        return null
    }

    /** [packageName] mana hai kya — [denyReason] ka saada roop. */
    fun isDenied(context: Context, packageName: String): Boolean =
        denyReason(context, packageName) != null

    /** Launcher wala asal naam (PackageManager) — na mile to null. */
    fun appLabel(context: Context, packageName: String): String? =
        try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info)?.toString()
        } catch (t: Throwable) {
            null
        }

    /**
     * Naam banking-wala lagta hai kya: Bangla me ব্যাংক
     * (ব্যাংকিং usi ke andar aa jata hai), Latin me
     * bank/banking/upi poora shabd bankar.
     */
    fun labelLooksBanking(label: String): Boolean {
        if (label.contains("ব্যাংক")) return true
        return BANKING_WORD_REGEX.containsMatchIn(label)
    }

    /**
     * open_app ke bole gaye naam ki jaanch — asal
     * package na mil paye tab ka aakhiri darja. Naam me
     * bandha banking-naam ya banking shabd ho to mana.
     */
    fun nameLooksDenied(nameRaw: String): Boolean {
        val name = nameRaw.trim().lowercase(Locale.ROOT)
        if (name.isEmpty()) return false
        if (labelLooksBanking(name)) return true
        if (DENIED_NAME_PARTS.any { name.contains(it) }) return true
        return DENIED_NAME_WORDS.any { word ->
            Regex("""\b""" + Regex.escape(word) + """\b""")
                .containsMatchIn(name)
        }
    }
}
