package com.aship.jarvis.commands

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import com.aship.jarvis.actions.LaunchOutcome
import com.aship.jarvis.actions.PhoneActions
import com.aship.jarvis.service.JarvisNotificationListener

/**
 * v98: notification se jawab bhejne ka asal kaam —
 * RemoteInput wala standard Android raasta।
 *
 * Kaise: zinda notification ke reply action me
 * [RemoteInput.addResultsToIntent] se user ka bola
 * jawab bharo, phir actionIntent.send() — notification
 * wala app khud jawab bhej deta hai (Maya kabhi kisi
 * app ke andar password / token nahi chhooti)।
 *
 * Imaandaari: jawab tabhi "bhej diya" jab send() bina
 * galti ke ho; notification ja chuki ho (live map me
 * na ho) ya reply wala button hi na ho to sach bolo
 * aur app kholne ki peshkash karo — jhootha "bhej
 * diya" kabhi nahi। Khud se kabhi jawab nahi bhejta —
 * sirf user ke bole hukm par।
 */
object NotificationCommands {

    enum class ReplyOutcome { SENT, NO_LIVE, NO_ACTION, FAILED }

    /** Notification access mila hua hai kya (system settings wala)। */
    fun isAccessGranted(context: Context): Boolean =
        JarvisNotificationListener.isAccessGranted(context)

    /**
     * Notification-access wala Settings screen kholo —
     * user wahan khud anumati deta hai (koi chhupa
     * tareeka nahi)। Khula to true, warna false।
     */
    fun openAccessSettings(
        context: Context,
        fromBackgroundService: Boolean
    ): Boolean {
        return if (fromBackgroundService) {
            PhoneActions.openPanelFromBackground(
                context, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            ) == LaunchOutcome.LAUNCHED
        } else {
            PhoneActions.openPanel(
                context, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            )
        }
    }

    /**
     * [msg] par [replyText] bhejo — zinda notification
     * chahiye (live map) + reply wala action chahiye।
     */
    fun sendReply(
        context: Context,
        msg: NotificationStore.StoredMessage,
        replyText: String
    ): ReplyOutcome {
        val sbn = JarvisNotificationListener.liveNotification(msg.key)
            ?: return ReplyOutcome.NO_LIVE
        val action = sbn.notification?.actions?.firstOrNull { a ->
            a.remoteInputs?.isNotEmpty() == true
        } ?: return ReplyOutcome.NO_ACTION
        val remoteInputs = action.remoteInputs ?: return ReplyOutcome.NO_ACTION
        return try {
            val intent = Intent()
            val results = Bundle()
            for (ri in remoteInputs) {
                results.putCharSequence(ri.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(remoteInputs, intent, results)
            action.actionIntent.send(context, 0, intent)
            NotificationStore.markReplied(context, msg.key)
            ReplyOutcome.SENT
        } catch (t: Throwable) {
            ReplyOutcome.FAILED
        }
    }

    /** Message wala app kholo (jawab ka button na ho / message ja chuka ho to)। */
    fun openMessageApp(
        context: Context,
        msg: NotificationStore.StoredMessage,
        fromBackgroundService: Boolean
    ): Boolean {
        if (msg.pkg.isEmpty()) return false
        return if (fromBackgroundService) {
            PhoneActions.openAppFromBackground(context, msg.pkg) ==
                LaunchOutcome.LAUNCHED
        } else {
            PhoneActions.openApp(context, msg.pkg)
        }
    }

    /**
     * "উত্তর দাও [text]" / "reply [text]" me se jawab
     * wala hissa nikaalo — KACHHE text se (normalize
     * nahi), taaki jawab user ke bole shabdon me hi jaye।
     * Na mile to null।
     */
    fun extractReplyText(rawText: String): String? {
        val lower = rawText.lowercase(java.util.Locale.ROOT)
        val phrases = listOf(
            "উত্তর দাও", "জবাব দাও", "উত্তর পাঠাও", "জবাব পাঠাও",
            "উত্তর লিখে দাও", "जवाब दो", "उत्तर दो",
            "जवाब भेजो", "उत्तर भेजो"
        )
        for (phrase in phrases) {
            val idx = lower.indexOf(phrase)
            if (idx >= 0) {
                val after = rawText.substring(idx + phrase.length)
                    .trim().trim(',', '.', '।', '!', '?', '"', '\'')
                if (after.isNotEmpty()) return after
            }
        }
        // "reply" poora shabd ho to (kisi shabd ke andar nahi)।
        val tokens = rawText.split(Regex("""\s+"""))
        val replyIdx = tokens.indexOfFirst {
            it.lowercase(java.util.Locale.ROOT).trim(',', '.', '।', '!', '?') == "reply"
        }
        if (replyIdx >= 0 && replyIdx + 1 < tokens.size) {
            val after = tokens.drop(replyIdx + 1).joinToString(" ")
                .trim().trim(',', '.', '।', '!', '?', '"', '\'')
            if (after.isNotEmpty()) return after
        }
        return null
    }
}
