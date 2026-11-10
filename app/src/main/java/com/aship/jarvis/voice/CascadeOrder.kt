package com.aship.jarvis.voice

import com.aship.jarvis.commands.Persona

/**
 * v51 (item 5): saamne wali screen ki bhasha-kataar ka
 * kram — shaksiyat-aware.
 *
 * v50 tak kataar hamesha [saved pasand, bn-IN, hi-IN]
 * banti thi; malik ki pasand bn-IN hone se har saamne
 * wala sunna Bangla se shuru hota tha aur bn-IN ka koi
 * bhi text pakka maan liya jata tha — Maya (Hindi)
 * baatchit me bhi pehla kaan galat bhasha ka khulta
 * tha. Ab kataar aakhiri saamne-wali shaksiyat se
 * shuru hoti hai (Maya -> hi-IN pehle, Tara -> bn-IN
 * pehle); shaksiyat abhi tak bandhi hi na ho to purana
 * bartao (saved pasand pehle).
 *
 * v93: chuni hui bhasha Hindi/Bangla ho to wahi kram
 * (shaksiyat pehle — v51 ka fix bilkul pehle jaisa);
 * chuni hui bhasha koi AUR ho (Tamil, English,
 * Spanish...) to CHUNI HUI pehle — warna us bhasha
 * ki boli pehle kaan par hi galat bhasha me pakdi
 * jati aur kataar kabhi wahan tak pahunchti hi nahi।
 *
 * Shudh Kotlin — JVM test ke liye.
 */
object CascadeOrder {

    fun languagesFor(lastPersona: Persona?, savedLanguage: String): List<String> {
        val personaLanguage = when (lastPersona) {
            Persona.MAYA -> "hi-IN"
            Persona.TARA -> "bn-IN"
            null -> null
        }
        return if (com.aship.jarvis.commands.MayaLanguages.isPersonaLanguage(savedLanguage)) {
            listOfNotNull(personaLanguage, savedLanguage, "bn-IN", "hi-IN", "en-IN")
                .distinct()
        } else {
            listOfNotNull(savedLanguage, personaLanguage, "bn-IN", "hi-IN", "en-IN")
                .distinct()
        }
    }

    /** Engine bante hi lagne wali pehli bhasha (kataar ka sir). */
    fun firstLanguage(lastPersona: Persona?, savedLanguage: String): String =
        languagesFor(lastPersona, savedLanguage).first()
}
