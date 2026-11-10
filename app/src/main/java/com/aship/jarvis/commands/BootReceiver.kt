package com.aship.jarvis.commands

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.aship.jarvis.prefs.AppPrefs
import com.aship.jarvis.service.JarvisWakeService

/**
 * v68: phone reboot ke baad reminder dobara lagana —
 * AlarmManager ke alarm reboot par mit jate hain (v59 se
 * yahi seema likhi thi), isliye boot hote hi bachi hui
 * yaadein phir se lagayi jati hain।
 *
 * Kaam, bilkul saadah: [ReminderCommands.loadAll] se
 * SharedPreferences wali list padho (wahi godam, wahi
 * format — kuch nahi badla), jinka samay abhi aage hai
 * unhe [ReminderCommands.schedule] se dobara lagao,
 * jinka samay phone band rehte beet gaya unhe hata do
 * (chup-chaap chhooti hui yaad — "missed" bolkar bajana
 * boot ke pal me sahi nahi: phone abhi khula bhi nahi
 * hota, aur aadhi raat ko reboot par purani yaad
 * bajna user ko pareshan karega; isliye skip + saaf)।
 *
 * Suraksha: poora kaam ek try/catch me — boot par
 * kuch bhi bigde to bhi app khulna ya chalna nahi
 * rukega; receiver turant khatm hota hai (koi awaaz,
 * koi notification, koi network nahi)। Alarm lagana
 * khud bhi exception-safe hai ([ReminderCommands.
 * schedule] andar se hi false lautata hai)।
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        try {
            if (context == null) return
            if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
            val ctx = context.applicationContext
            // v80: boot par background sunna khud chalu — SIRF
            // tab jab user ne "শাটডাউন" se band na kiya ho
            // (wakeStoppedByShutdown jhanda dekho)। Ruka hua
            // ho to kuch nahi — app kholna hi dobara chalu
            // karega। Mic permission na ho to bhi kuch nahi।
            // Reminder wala kaam neeche aam chalta hai —
            // khaali reminder list wake start ko rokegi nahi
            // (pehle wala jaldi-return ab wake ke baad hai)।
            try {
                val prefs = AppPrefs(ctx)
                // v91: power kabhi chalu hua hi na ho (pehli baar
                // on karne se pehle wala reboot) to boot par khud
                // chalu nahi hogi — power hamesha malik hi chalu
                // karega; ek baar on ho chuka ho to boot par
                // wapas aayegi (24h background)।
                if (prefs.wakeEverPoweredOn && !prefs.wakeStoppedByShutdown &&
                    ContextCompat.checkSelfPermission(
                        ctx, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    ContextCompat.startForegroundService(
                        ctx, JarvisWakeService.startIntent(ctx)
                    )
                }
            } catch (t: Throwable) {
                // Boot par wake start na ho — orb tap se
                // power on karne par phir koshish hogi।
            }
            val all = ReminderCommands.loadAll(ctx)
            if (all.isEmpty()) return
            val now = System.currentTimeMillis()
            // v98: repeat wali beet chuki yaad aage badhao
            // (series na toote — agle din / agle hafte wahi
            // samay); ek baar wali beet chuki yaad hatao।
            val future = all.mapNotNull { reminder ->
                if (reminder.atMillis > now) {
                    reminder
                } else if (reminder.repeatKind != "NONE") {
                    ReminderCommands.rollForwardToFuture(reminder, now)
                } else {
                    null
                }
            }
            for (reminder in future) {
                try {
                    ReminderCommands.schedule(ctx, reminder)
                } catch (t: Throwable) {
                    // Ek alarm na lage to baaki phir bhi lagenge।
                }
            }
            if (future.size < all.size || future != all) {
                // Beet chuki yaadein hata do / repeat wali
                // aage badha do — list saaf rahe, dobara boot
                // par phir koshish na ho।
                try {
                    ReminderCommands.saveAll(ctx, future)
                } catch (t: Throwable) {
                    // Saaf na ho to bhi koi nuksan nahi —
                    // list wali jaanch pehle se hi sirf
                    // bhavishya wali yaadein dikhati hai।
                }
            }
        } catch (t: Throwable) {
            // Boot par kabhi crash nahi — app aam chalega।
        }
    }
}
