package com.aship.jarvis.ai

import java.util.Locale

/**
 * v40: GALLERY KE TRASH / RECENTLY-DELETED ko khaali
 * karne wala PLAYBOOK (khel ki bandhi chaal-suchi)।
 *
 * Ye kaam app ke sabse khatarnaak kaamon me hai:
 * trash se muchi cheez PAKAPAKI (permanent) jati hai,
 * wapas nahi aati. Isliye is kaam ki suraksha sirf
 * model ki samajh par nahi chhodi gayi hai — jab bhi
 * screen_task ka kaam is jaanch se milta hai
 * ([AgentTools] screenTask me dekhta hai), ye
 * bandhi hidayat screen wale mastishk ke har kadam
 * ke sandesh me jud jati hai ([ScreenAgent] playbook
 * wala hissa): pehle folder ke andar jao, phir
 * ginati padho, phir ginati + pakapaki chetaavni ke
 * saath user se SAAF pushti lo — aur wohi pushti is
 * kaam ki delete-pushti hai ([ScreenAgent] us jawab
 * ko pehchankar delete wale tap par dobara nahi
 * poochta; bina us pushti ke delete wala tap hamesha
 * ki tarah alag se pushti maangega)।
 *
 * Pehchan jaan-boojh kar tang hai: trash/deleted
 * folder ka zikr AUR mitaane ka iraada — dono ek
 * saath chahiye. "ট্র্যাশে কী আছে দেখাও" (sirf
 * dekhna) isme nahi aata, aur aam gallery ki saari
 * photo mitaana ("সব ছবি ডিলিট করো") bhi nahi —
 * woh is playbook ka kaam hi nahi hai.
 */
object TrashPlaybook {

    /** Is kaam par playbook lagu ho to uska text, warna null. */
    fun forTask(task: String): String? =
        if (matches(task)) TEXT else null

    /** Kaam trash-khaali karne wala hai kya — folder ka zikr + mitaane ka iraada, dono. */
    fun matches(task: String): Boolean {
        val lower = task.lowercase(Locale.ROOT).trim()
        if (lower.isEmpty()) return false
        if (TRASH_REFS.none { lower.contains(it) }) return false
        return DELETE_INTENTS.any { lower.contains(it) }
    }

    /** Trash / deleted folder ke zikr (teeno script). */
    private val TRASH_REFS = listOf(
        "ট্র্যাশ", "ট্রাশ", "রিসেন্টলি ডিলিট", "ডিলিট ফোল্ডার",
        "ডিলিট করা ছবি", "ডিলিট করা ফটো",
        "trash", "recently deleted", "deleted folder", "recycle bin",
        "ट्रैश", "रिसेंटली डिलीट", "डिलीट फ़ोल्डर",
        "রিসাইকেল বিন"
    )

    /** Mitaane/khaali karne ke iraade ke sanket (teeno script). */
    private val DELETE_INTENTS = listOf(
        "খালি", "মুছে", "মুছ", "ডিলিট", "পাকাপাকি",
        "delete", "empty", "khali", "permanent",
        "डिलीट", "खाली", "मिटा", "मिटाओ"
    )

    /**
     * Screen wale mastishk ke liye bandhi hidayat —
     * har kadam ke sandesh me "PLAYBOOK" bankar jati
     * hai. Pushti wale ask_user sawal me pakapaki
     * chetaavni ke pakke shabd isliye diye gaye hain
     * ki [ScreenAgent] usi sawal-jawab ko is kaam ki
     * delete-pushti pehchan sake (chetaavni ke chinh
     * wahan bandhe hain)।
     */
    const val TEXT =
        "PLAYBOOK — EMPTYING A GALLERY TRASH / RECENTLY-DELETED FOLDER. " +
            "Follow these steps exactly; this deletion is PERMANENT.\n" +
            "1. Go into the gallery/photos app the user means and " +
            "navigate to its Trash / Recently deleted / Bin section " +
            "(often inside a menu, Library, or Albums area). If you " +
            "cannot find the trash section, scroll and look carefully " +
            "before failing.\n" +
            "2. Once inside the trash, READ how many items are there: " +
            "use a visible count on screen (e.g. \"24 items\", a number " +
            "near the title) or count the visible items. If no count " +
            "can be determined, treat the count as many (\"অনেকগুলো\" " +
            "in Bengali / \"बहुत सारे\" in Hindi / \"many\" in English).\n" +
            "3. BEFORE tapping any Delete-all / Empty-trash / Delete " +
            "button, use ask_user with ONE question in the USER'S " +
            "language that: (a) says how many items are in the trash, " +
            "(b) warns clearly that deleting from the trash is " +
            "PERMANENT and cannot be undone — in Bengali include the " +
            "exact words \"পাকাপাকি মুছে যাবে\" and \"ফেরানো যাবে না\", " +
            "in Hindi include \"हमेशा के लिए मिट जाएगा\", in English " +
            "include \"permanently deleted\", and (c) asks whether to " +
            "delete them all. THIS question and its answer ARE the " +
            "confirmation for the deletion in this task: proceed to " +
            "the delete tap ONLY if the reply is a clear yes. If " +
            "there is no answer, or the answer is not a clear yes, " +
            "use \"fail\" and say honestly that nothing was deleted.\n" +
            "4. After that yes, tap the delete-all / empty-trash " +
            "button. The app will NOT ask a second time — the " +
            "ask_user confirmation in step 3 is the one that counts " +
            "for this task. If the gallery itself then shows its own " +
            "\"Delete permanently?\" dialog, that dialog's Delete " +
            "button is part of the same confirmed flow — tap it.\n" +
            "5. If items must be selected first (a select-all " +
            "checkbox or long-press selection) or deletion happens " +
            "in batches, repeat the flow as needed, keeping count " +
            "of batches in your action history.\n" +
            "6. Finish with \"done\" ONLY when the trash screen " +
            "shows it is empty (or the deleted count matches what " +
            "was there). In \"say\", report honestly in the user's " +
            "language how many items were permanently deleted. If " +
            "you get stuck midway, use \"fail\" and say how far you " +
            "got and how many (if any) were already deleted."
}
