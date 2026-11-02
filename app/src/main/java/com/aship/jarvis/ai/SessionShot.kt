package com.aship.jarvis.ai

import android.graphics.Bitmap

/**
 * v49: SESSION WALI SCREENSHOT KI GODAM — chalte
 * hue TAANA (continuous) screen session ke andar
 * user ne jo screenshot pakke hukm se liya tha, usi
 * tasveer ki ek in-memory nakal yahan rehti hai,
 * taaki baad me "ওই স্ক্রিনশটটা পাঠাও" bolne par
 * share wala auzaar NAII tasveer lene ke bajay
 * USI purani ko chipka sake.
 *
 * Bandobast:
 * - Tasveer sirf YAAD (memory) me hai — disk par
 *   kabhi nahi jati (gallery wali asal bachat
 *   [ScreenshotStore] apni jagah alag se hoti hai)।
 * - Wake service session ki shuruaat me ise saaf
 *   karti hai, session ke ant me bhi saaf karti
 *   hai — purane session ki tasveer naye session
 *   me kabhi nahi ghusti।
 * - Naya pakka screenshot hukm aaye to tasveer
 *   BADAL jati hai (humesha sabse taaza pakki
 *   wali hi rehti hai)।
 * - Ye [JarvisAccessibilityService] ke
 *   lastCleanScreenshot se ALAG hai: woh har
 *   capture (screen-agent ke kadam samet) par
 *   badalta rehta hai; yahan sirf user ke pakke
 *   screenshot hukm wali tasveer bandhti hai —
 *   "us samay ki screen" nahi, "uski maangi hui
 *   tasveer"।
 */
object SessionShot {

    /** Is session ki bandhi hui tasveer (nahi ho to null)। */
    @Volatile
    var bitmap: Bitmap? = null

    /** Session ki shuruaat/ant par godam khaali karo। */
    fun clear() {
        bitmap = null
    }
}
