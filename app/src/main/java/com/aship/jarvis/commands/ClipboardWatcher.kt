package com.aship.jarvis.commands

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * v101: SMART CLIPBOARD MANAGER — copy par nazar.
 *
 * System clipboard par badlav dekhne wala sunne wala
 * ([ClipboardManager.OnPrimaryClipChangedListener]):
 * user kuch bhi copy kare to woh likhai [ClipboardStore]
 * me bachta hai (OTP / password jaisi likhai kabhi
 * nahi — store khud rokta hai)।
 *
 * - Ref-count wala start/stop: wake service (Maya chal
 *   rahi ho to) aur saamne wali screen dono yahi bulate
 *   hain — dono chahein to bhi listener ek hi lagta hai,
 *   aur tabhi hat-ta hai jab koi bhi na chahe।
 * - Maya ke APNE clip kabhi nahi bachte: paste wala
 *   raasta aur card wala "copy" dono [OWN_CLIP_LABEL]
 *   se clipboard bharte hain, aur watcher us label
 *   wale clip ko chup-chaap chhod deta hai — warna
 *   Maya apni hi paste ki hui likhai baar-baar bachaati।
 * - Sab kuch best effort: listener lagana / padhna
 *   na ho paye to chup-chaap chhodo, hukm isse nahi rukta।
 *
 * NOTE (Android 10+): background me clipboard padhna
 * band hai — isliye watcher sirf tab chalta hai jab
 * Maya ki foreground service chal rahi ho ya app
 * saamne ho। Android 12+ par system clipboard padhne
 * par apna toast dikhata hai ("X accessed your
 * clipboard") — woh OS ka vyavhaar hai, bug nahi।
 */
object ClipboardWatcher {

    /** Maya ke apne paste/copy-back wale clip ka label — watcher ise kabhi save nahi karta. */
    const val OWN_CLIP_LABEL = "jarvis screen agent"

    @Volatile
    private var refCount = 0

    @Volatile
    private var manager: ClipboardManager? = null

    @Volatile
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null

    /**
     * Nazar shuru karo — pehli baar hi listener lagta
     * hai, uske baad sirf ginati badhti hai।
     */
    @Synchronized
    fun start(context: Context) {
        if (refCount == 0) {
            try {
                val appContext = context.applicationContext
                val cm = appContext.getSystemService(
                    Context.CLIPBOARD_SERVICE
                ) as? ClipboardManager
                if (cm != null) {
                    val l = ClipboardManager.OnPrimaryClipChangedListener {
                        onClipChanged(appContext, cm)
                    }
                    cm.addPrimaryClipChangedListener(l)
                    manager = cm
                    listener = l
                }
            } catch (t: Throwable) {
                // Watcher bonus hai — hukm isse nahi rukta.
            }
        }
        refCount++
    }

    /**
     * Nazar band karo — ginati zero hote hi listener
     * hat-ta hai (kuch na chahe to)।
     */
    @Synchronized
    fun stop() {
        if (refCount > 0) refCount--
        if (refCount == 0) {
            try {
                val cm = manager
                val l = listener
                if (cm != null && l != null) {
                    cm.removePrimaryClipChangedListener(l)
                }
            } catch (t: Throwable) {
                // Hataana bonus hai — hukm isse nahi rukta.
            }
            manager = null
            listener = null
        }
    }

    /** Clipboard badla — nayi likhai bachao (Maya ki apni chhodkar). */
    private fun onClipChanged(appContext: Context, cm: ClipboardManager) {
        try {
            val clip: ClipData = cm.primaryClip ?: return
            if (clip.itemCount <= 0) return
            val label = try {
                clip.description?.label?.toString()
            } catch (t: Throwable) {
                null
            }
            // Maya ka apna clip (paste / copy-back) — kabhi save nahi।
            if (label == OWN_CLIP_LABEL) return
            val text = try {
                clip.getItemAt(0)?.text?.toString()
                    ?: clip.getItemAt(0)?.coerceToText(appContext)?.toString()
            } catch (t: Throwable) {
                null
            } ?: return
            ClipboardStore.save(appContext, text)
        } catch (t: Throwable) {
            // Watcher bonus hai — hukm isse nahi rukta.
        }
    }

    /**
     * Kisi bachi hui likhai ko ek click me wapas system
     * clipboard par rakho — label aisa ki watcher ise
     * dobara save na kare (apna hi clip)।
     */
    fun copyToClipboard(context: Context, text: String): Boolean {
        return try {
            val cm = context.getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as? ClipboardManager ?: return false
            cm.setPrimaryClip(ClipData.newPlainText(OWN_CLIP_LABEL, text))
            true
        } catch (t: Throwable) {
            false
        }
    }
}
