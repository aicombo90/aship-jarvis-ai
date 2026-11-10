package com.aship.jarvis.commands

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.aship.jarvis.MainActivity
import com.aship.jarvis.speaker.TtsSpeaker
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v59: reminder bajne ka receiver — AlarmManager isi ko
 * jagata hai। Kaam: (1) entry mitana (ek baar ki yaad),
 * (2) notification dikhana, (3) awaaz se bol dena।
 *
 * [goAsync] se receiver zinda rehta hai jab tak TTS bol
 * na le (zyada se zyada ~20 s) — warna system process
 * maar kar boli beech me kaat sakta hai। Notification
 * phir bhi rehta hai, taaki boli na sunayi de to bhi
 * yaad chhoote nahi।
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val done = AtomicBoolean(false)
        val finishOnce = {
            if (done.compareAndSet(false, true)) {
                try {
                    pending.finish()
                } catch (t: Throwable) {
                    // Khatm karna bonus hai।
                }
            }
        }
        try {
            val id = intent.getLongExtra(ReminderCommands.EXTRA_ID, -1L)
            val text = intent.getStringExtra(ReminderCommands.EXTRA_TEXT)
                ?.trim() ?: ""
            if (text.isEmpty()) {
                finishOnce()
                return
            }
            // Entry ab bhi hai kya — batiil ho chuki ho to
            // bajna nahi chahiye (dohri suraksha)।
            // v98: repeat wali yaad — bajne par agli baari
            // lagao (entry rakho, series chalti rahe); ek
            // baar wali purani tarah mit jati hai।
            val entry = if (id != -1L) ReminderCommands.findById(context, id) else null
            if (id != -1L && entry == null) {
                finishOnce()
                return
            }
            if (entry != null && entry.repeatKind != "NONE") {
                val next = ReminderCommands.rollForwardToFuture(
                    entry.copy(atMillis = ReminderCommands.nextOccurrence(entry))
                )
                val all = ReminderCommands.loadAll(context)
                    .map { if (it.id == id) next else it }
                ReminderCommands.saveAll(context, all)
                ReminderCommands.schedule(context, next)
            } else if (id != -1L && ReminderCommands.removeById(context, id) == null) {
                finishOnce()
                return
            }
            showNotification(context, text)
            val speaker = TtsSpeaker(context.applicationContext)
            speaker.onUtteranceFinished = {
                try {
                    speaker.shutdown()
                } catch (t: Throwable) {
                    // Band karna bonus hai।
                }
                finishOnce()
            }
            speaker.speak("মনে করিয়ে দিচ্ছি — $text")
            // TTS kabhi khatm na ho (engine na ho) to bhi
            // receiver latka nahi rahega।
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    try {
                        speaker.shutdown()
                    } catch (t: Throwable) {
                        // Band karna bonus hai।
                    }
                    finishOnce()
                },
                20_000L
            )
        } catch (t: Throwable) {
            finishOnce()
        }
    }

    private fun showNotification(context: Context, text: String) {
        try {
            val nm = context.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "মনে করানো",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                nm.createNotificationChannel(channel)
            }
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentPi = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("মনে করিয়ে দিচ্ছি")
                .setContentText(text)
                .setContentIntent(contentPi)
                .setAutoCancel(true)
                .build()
            nm.notify(
                NOTIF_ID_BASE + (System.currentTimeMillis() % 10_000L).toInt(),
                notification
            )
        } catch (t: Throwable) {
            // Notification na dikhe to bhi boli chalegi।
        }
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_reminder_channel"
        private const val NOTIF_ID_BASE = 59000
    }
}
