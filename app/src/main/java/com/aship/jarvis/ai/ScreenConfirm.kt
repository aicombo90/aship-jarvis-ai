package com.aship.jarvis.ai

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v37: screen-agent ke sawal-jawab wala pul (bridge).
 *
 * ScreenAgent kisi background thread par chalta hai
 * (AgentBrain ke executor par, ek auzaar ke andar).
 * Send/pay/delete wala kadam aane par — ya model khud
 * kuch poochna chahe (ask_user) — use user se ATKE hue
 * intezaar ke saath sawal poochna hota hai: sawal bolo,
 * ek jawab suno/lo, phir aage badho ya ruk jao. Bolna
 * aur sunna app ke do mezbaano (hosts) ke paas hai —
 * saamne wali screen (MainActivity: apna speaker +
 * mic + likhne wala khana) aur background wake service
 * (apna speaker + wake wala raasta). Ye pul bas itna
 * karta hai: ek samay me EK latka hua sawal rakhta hai,
 * use taaza mezbaan tak pahunchata hai, aur jawab —
 * jahan se bhi aaye (mic se suna hua ya likha hua) —
 * intezaar kar rahe agent tak saunp deta hai.
 *
 * Niyam sakht hain:
 * - Ek samay me ek hi sawal; dusra sawal aaye to woh
 *   turant "uplabdh nahi" (null) paata hai.
 * - Samay khatm ho ya koi mezbaan hi na ho to jawab
 *   null — bulane wala use "pushti NAHI mili" hi
 *   samjhega. Chuppi kabhi haan nahi banti.
 * - Jawab ka text reporton (jarvis_diag) me kabhi
 *   nahi jata — ye file kuch band hi nahi karti.
 */
object ScreenConfirm {

    /**
     * Sawal ka mezbaan — jiske paas awaaz/screen hai.
     * [onQuestion] kisi bhi thread se bulaaya ja sakta
     * hai; mezbaan apna kaam apne thread par le jaye
     * (bolna + screen par dikhana). Sunna mezbaan ke
     * aam raaste se hi hota hai: suna/likha gaya agli
     * baat [deliverAnswer] se is pul tak aati hai.
     */
    fun interface Host {
        fun onQuestion(question: String)
    }

    /** Latka hua ek sawal: uska text, jawab aur bandha intezaar. */
    private class Pending(val question: String) {
        val latch = CountDownLatch(1)

        @Volatile
        var answer: String? = null
    }

    private val lock = Any()

    @Volatile
    private var activityHost: Host? = null

    @Volatile
    private var serviceHost: Host? = null

    @Volatile
    private var pending: Pending? = null

    /** Saamne wali screen apna mezbaan bandhe (onCreate) — purana ho to badal jata hai. */
    fun registerActivityHost(host: Host) {
        activityHost = host
    }

    /** Band hoti screen apna mezbaan hataye (onDestroy) — wahi wala ho tabhi. */
    fun unregisterActivityHost(host: Host) {
        if (activityHost === host) {
            activityHost = null
        }
    }

    /** Wake service apna mezbaan bandhe (onCreate) — purana ho to badal jata hai. */
    fun registerServiceHost(host: Host) {
        serviceHost = host
    }

    /** Band hoti service apna mezbaan hataye (teardown) — wahi wala ho tabhi. */
    fun unregisterServiceHost(host: Host) {
        if (serviceHost === host) {
            serviceHost = null
        }
    }

    /** Is pal koi sawal latka hai kya. */
    fun hasPending(): Boolean = pending != null

    /** Latke hue sawal ka text (screen par dikhane ke liye), ya null. */
    fun pendingQuestion(): String? = pending?.question

    /**
     * Agent wale thread se: [question] poochho aur jawab
     * ka bandha intezaar karo ([timeoutMs] tak). Jawab ka
     * kachha text laut-ta hai; mezbaan na ho, pehle se
     * koi sawal latka ho, ya samay khatm ho jaye to null.
     * Null ka matlab hamesha "jawab/pushti nahi mili" hai.
     */
    fun ask(question: String, timeoutMs: Long): String? {
        val host = activityHost ?: serviceHost ?: return null
        val mine = Pending(question)
        synchronized(lock) {
            if (pending != null) return null
            pending = mine
        }
        try {
            host.onQuestion(question)
        } catch (t: Throwable) {
            synchronized(lock) {
                if (pending === mine) pending = null
            }
            return null
        }
        val answered = try {
            mine.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        synchronized(lock) {
            if (pending === mine) pending = null
        }
        return if (answered) mine.answer else null
    }

    /**
     * Mezbaan ke aam sunne/likhne wale raaste se: ye baat
     * latke hue sawal ka jawab hai to use lo aur true
     * lautao (bulane wala use naya command NA samjhe)।
     * Koi sawal latka hi na ho to false — aam vyavhaar.
     */
    fun deliverAnswer(text: String): Boolean {
        val mine: Pending
        synchronized(lock) {
            mine = pending ?: return false
            pending = null
        }
        mine.answer = text
        mine.latch.countDown()
        return true
    }
}
