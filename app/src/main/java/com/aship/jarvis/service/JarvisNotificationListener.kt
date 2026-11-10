package com.aship.jarvis.service

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.aship.jarvis.commands.NotificationStore
import com.aship.jarvis.commands.ReminderCommands

/**
 * v98: notification se jawab — message notification
 * pakadna (WhatsApp / Messenger / SMS / Telegram ...),
 * taaki Maya use padhkar suna sake aur awaaz se jawab
 * bhej sake।
 *
 * Kaam, bilkul saadah: message jaisi notification aate
 * hi (1) [NotificationStore] me uska hisaab (bhejne
 * wala + text + samay) likh do, (2) asal notification
 * ko live map me bandh do (jawab bhejne ke liye
 * RemoteInput wala action baad me yahin se milega),
 * (3) kisi shaks par nazar (person watch) ho to
 * [ReminderCommands.onMessageReceived] se use jagao।
 *
 * Kya NAHI hota: Maya khud se kuch padhkar NAHI
 * sunati, khud se jawab NAHI bhejti — sirf tab jab
 * user bolkar kahe। Notification hata di jaye to
 * godam se bhi mit jati hai।
 *
 * Anumati: user Settings > Notifications > Notification
 * access se khud ON karta hai; bina uske service band
 * rehti hai aur Maya saaf mana kar deti hai।
 * BIND_NOTIFICATION_LISTENER_SERVICE ki wajah se sirf
 * Android system hi ise bandh sakta hai (manifest dekho)।
 */
class JarvisNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            val msg = extractMessage(applicationContext, sbn) ?: return
            synchronized(liveLock) {
                liveMap[msg.key] = sbn!!
                while (liveMap.size > MAX_LIVE) {
                    val eldest = liveMap.keys.firstOrNull() ?: break
                    liveMap.remove(eldest)
                }
            }
            NotificationStore.add(
                applicationContext,
                NotificationStore.StoredMessage(
                    key = msg.key,
                    pkg = msg.pkg,
                    sender = msg.sender,
                    text = msg.text,
                    time = msg.time,
                    hasReply = msg.hasReply,
                    replied = false
                )
            )
            ReminderCommands.onMessageReceived(
                applicationContext, msg.sender, msg.text
            )
        } catch (t: Throwable) {
            // Notification sunna kabhi crash nahi karega।
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        try {
            val key = sbn?.key ?: return
            synchronized(liveLock) { liveMap.remove(key) }
            NotificationStore.remove(applicationContext, key)
        } catch (t: Throwable) {
            // Hatana bonus hai — godam saaf rakhna asal hai।
        }
    }

    companion object {
        private const val MAX_LIVE = 10
        private val liveLock = Any()
        private val liveMap = LinkedHashMap<String, StatusBarNotification>()

        /** Jawab bhejne ke liye zinda notification — na mile to null। */
        fun liveNotification(key: String): StatusBarNotification? =
            synchronized(liveLock) { liveMap[key] }

        /** Notification access mila hua hai kya (system settings wala)। */
        fun isAccessGranted(context: Context): Boolean {
            return try {
                NotificationManagerCompat.getEnabledListenerPackages(context)
                    .contains(context.packageName)
            } catch (t: Throwable) {
                false
            }
        }

        /** Pakda hua message — nikaalne layak na ho to null। */
        data class ExtractedMessage(
            val key: String,
            val pkg: String,
            val sender: String,
            val text: String,
            val time: Long,
            val hasReply: Boolean
        )

        /**
         * Notification me se message nikaalo — message jaisi
         * na ho to null। Apni khud ki notification, chalu
         * (ongoing) notification, aur bina text wali kabhi nahi।
         */
        fun extractMessage(
            context: Context,
            sbn: StatusBarNotification?
        ): ExtractedMessage? {
            if (sbn == null) return null
            if (sbn.packageName == context.packageName) return null
            val n = sbn.notification ?: return null
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return null
            val extras = n.extras ?: return null
            val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
                ?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return null
            val sender = extras.getCharSequence(Notification.EXTRA_TITLE)
                ?.toString()?.trim().orEmpty()
                .ifEmpty { sbn.packageName }
            val hasReply = n.actions?.any { action ->
                action.remoteInputs?.isNotEmpty() == true
            } == true
            val isMessageCategory = n.category == Notification.CATEGORY_MESSAGE
            if (!isMessageCategory && !hasReply &&
                sbn.packageName !in MESSAGE_PACKAGES
            ) {
                return null
            }
            return ExtractedMessage(
                key = sbn.key,
                pkg = sbn.packageName,
                sender = sender,
                text = text,
                time = sbn.postTime,
                hasReply = hasReply
            )
        }

        /** Message wale aam apps — category/reply na ho to bhi inki khabar rakho। */
        private val MESSAGE_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.facebook.orca",
            "com.facebook.lite",
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging",
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.thoughtcrime.securesms",
            "com.instagram.android",
            "com.snapchat.android",
            "com.discord",
            "com.skype.raider",
            "com.viber.voip",
            "com.tencent.mm"
        )
    }
}
