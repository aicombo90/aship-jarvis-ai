package com.aship.jarvis.commands

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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
            val all = ReminderCommands.loadAll(ctx)
            if (all.isEmpty()) return
            val now = System.currentTimeMillis()
            val future = all.filter { it.atMillis > now }
            for (reminder in future) {
                try {
                    ReminderCommands.schedule(ctx, reminder)
                } catch (t: Throwable) {
                    // Ek alarm na lage to baaki phir bhi lagenge।
                }
            }
            if (future.size < all.size) {
                // Beet chuki yaadein hata do — list saaf rahe,
                // dobara boot par phir koshish na ho।
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
