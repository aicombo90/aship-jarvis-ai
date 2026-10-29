# aship-jarvis-ai

# ASHIP JARVIS AI

A personal AI voice assistant for Android — apne hi phone ke liye.
You speak a command ("YouTube kholo", "torch jalao", "time kya hai") and
Jarvis does it on your phone and speaks the reply back to you.

- **Language:** Hindi (Latin script / Hinglish) + English keywords;
  spoken words in Devanagari / Bengali script bhi samajhta hai
- **Phase 1 status:** offline voice commands, app opening, torch, alarm,
  time/date, spoken replies (TTS), Offline / Online / Auto modes
- **Phase 2 status (done):** background listening with the "Jarvis"
  wake word — YouTube khula ho tab bhi Jarvis sunta rehta hai
- **Phone it is built for:** vivo T4x 5G, Android 16, 8 GB RAM
  (works on any Android 8.0+ phone)

---

## Roadmap

| Phase | What comes in it |
|-------|------------------|
| **Phase 1 (done)** | App shell, Offline/Online/Auto mode switch, Android system speech (Hindi), Vosk fully-offline speech after a one-time model download, rule-based offline commands (open apps, torch, alarm, time, date), Hindi TTS replies, typed-command fallback for testing |
| **Phase 2 (done)** | Background listening: foreground service + "Jarvis" wake word (Devanagari / Bengali / Latin) — dusri app khuli ho tab bhi sunta hai, wake par ek command lekar wapas wake mode. Battery-whitelist shortcut in-app. Fixed CI signing key — updates install in place |
| Phase 3 | Online AI conversation (free-tier API, key stored outside the code), natural Q&A in Hindi + English, YouTube idea/script help |
| Phase 4 | Better offline command coverage, English Vosk model option, settings screen polish |
| Phase 5 | Accessibility-based screen reading & taps ("is screen par kya hai", "download button dabao") |
| Phase 6 | Small on-device AI model for offline conversation (optional) |

Phase 1 design rule: **koi paid service nahi.** Sab kuch free/open-source —
Kotlin, Android SDK, Vosk, Android Text-to-Speech, GitHub Actions.

---

## APK kaise download karein (GitHub Actions se)

Har push ke baad GitHub khud APK bana deta hai. Koi computer/Android
Studio ki zaroorat nahi — phone se hi ho jayega.

1. Is repository ko GitHub par kholo.
2. Upar **Actions** tab par tap karo.
3. Sabse upar wali **Build APK** run kholo (uske saamne green ✓ hona chahiye).
4. Page ke neeche **Artifacts** section me **aship-jarvis-debug-apk** milega —
   us par tap karke download karo. Ye ek **.zip** file hogi.
5. Zip ko kholo (extract karo) — andar **app-debug.apk** milegi.
6. APK file par tap karo → **Install**.

> Artifact 14 din tak rehta hai. Uske baad chahiye to Actions tab me
> jaakar **Run workflow** se dobara bana sakte ho.

## Update kaise karein (Phase 2 se)

Phase 2 wale build se har CI build par **ek hi fixed signing key**
lagti hai (`keystore/keystore.b64` — khud ki banayi hui CI debug key,
Play Store wali key nahi). Isliye:

- **Naya APK purane app ke upar hi install ho jayega** — uninstall
  karne ki zaroorat nahi.
- App ka data **bana rahega**: download kiya hua Hindi model aur
  settings update ke baad bhi nahi udenge.
- **Ek last baar:** Phase 1 wala purana build is naye build se pehle
  **ek baar uninstall** karna hoga, kyunki uski signature alag thi.
  Uske baad ke saare updates seedha upar install honge.

## Pehli baar install karte samay (unknown apps permission)

Pehli baar phone puchega ki is app ko install karne ki permission dein:

1. APK par tap karo → agar **"Install blocked / For your security..."**
   aaye to **Settings** par tap karo.
2. **"Allow from this source"** (browser ya Files app ke liye) ON karo.
3. Wapas aakar **Install** dabao.

Ye ek baar hi karna padta hai. Ye permission sirf usi app (browser/Files)
ko milti hai jahan se aap APK khol rahe ho.

## App permissions — kya aur kyun

| Permission | Kyun chahiye |
|------------|--------------|
| **Microphone (Record audio)** | Aapki awaaz sunkar command samajhne ke liye. Bina iske voice command chal hi nahi sakta. Awaaz kahin upload nahi hoti — Offline mode me sab processing phone par hi hoti hai. |
| **Internet** | Sirf do kaam: (1) pehli baar offline Hindi voice model (~40 MB) download karna, (2) aane wale Phase 3 me online AI. Offline commands ke liye internet band kar sakte ho. |
| **Set alarm** | "Alarm lagao" bolne par clock app ka alarm screen kholne/prefill karne ke liye. |
| **Flashlight (hardware feature, koi permission prompt nahi)** | "Torch jalao / band karo" ke liye camera ka flash use hota hai. Camera se photo/video **nahi** liya jata. |
| **Foreground service + Foreground service microphone** (Phase 2) | Background wake-word sunne ke liye service ko zinda rakhna. Android ka niyam hai: mic wala background kaam foreground service me hi chalega, chaloo notice ke saath. Service sirf app ke **START** button se chalu hoti hai — khud se kabhi nahi. |
| **Notifications** (Phase 2, runtime par poocha jata hai) | Upar wale notice ("Jarvis sun raha hai") ke liye. **Mana karne par bhi service chalegi**, sirf notice nahi dikhega. |
| **Battery optimization se chhoot (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)** (Phase 2) | Sirf taaki app ke andar wala **"Battery bandobosto"** button aapko seedha sahi Settings page par le ja sake. Chhoot dena ya na dena poori tarah aapke haath me hai — Android khud poochega. |

Abhi bhi **koi Accessibility Service nahi hai** aur koi API key code me
nahi hai. Jo permission lagegi, Android khud poochega — bina aapke haan
kiye kuch nahi hota.

## Background sunna (Phase 2) — kaise kaam karta hai

1. App kholo, Hindi offline model download ho (pehli baar).
2. Neeche **"▶ Background sunna START karo"** dabao. Mic aur
   notification ki permission Android poochega — Allow karo.
3. Upar ek notice aa jayega: **"Jarvis sun raha hai — 'Jarvis' boliye"**.
   Ab chahe YouTube kholo ya koi aur app — Jarvis background me
   wake word sunta rahega.
4. **"Jarvis"** bolo (जर्विस / জার্ভিস bhi chalega) → Jarvis bolega
   **"Haan, boliye"** → ek command bolo ("YouTube kholo", "torch
   jalao", "time kya hai") → kaam hoga, jawab milega → Jarvis khud
   wapas wake-sunne par chala jayega.
5. Band karna ho to notice me **"Band karo"** dabao, ya app me aakar
   **"⏹ Background sunna BAND karo"**.

**Sunna poori tarah offline hai** — Vosk model phone par hi chalta hai,
aapki awaaz kahin upload nahi hoti.

### Imandari wali seemayein (honest limits)

- **Screen band ho to pakka nahi.** Screen ON ho ya dusri app khuli
  ho — tab sabse bharosemand chalta hai. Screen band/lock ki haalat
  me har phone par wake word pakadna guarantee nahi hai — ye Android
  ki suraksha seema hai, koi jugaad nahi lagaya gaya.
- **vivo ka battery bandobosto zaroori hai.** vivo (OriginOS) peeche
  chal rahi apps ko band karne me tez hai. App ke andar wale
  **"Battery bandobosto karo"** button se Jarvis ko battery
  whitelist me daal do, warna service kuch der me band ho sakti hai.
- **Battery lagegi.** Lagatar mic chalu matlab lagatar bijli kharch.
  Din-bhar chalu rakhna hai to battery zyada giregi — jab zaroorat
  na ho, band kar do.
- **Ek samay par ek hi sunna.** Background sunna chalu ho to app ke
  andar wala 🎤 Tap-to-speak ek saath mat chalao — do mic ek saath
  takra sakte hain.
- Aas-paas ke log "Jarvis" shabd bol dein to galti se bhi jaag sakta
  hai — ye wake-word takneek ki fitrat hai.

## Pehli baar use kaise karein

1. App kholo — pehla screen hi Jarvis hai.
2. **Mode** chuno: shuruaat me **Auto** theek hai.
3. **"Download Hindi offline voice model"** button dabao (ek baar,
   internet ON rakhkar). Model phone me save ho jayega — uske baad
   Offline mode me bina internet ke Jarvis sunega.
4. **🎤 Tap to speak** dabao → mic permission **Allow** karo → boliye:
   - "YouTube kholo"
   - "Torch jalao" / "Torch band karo"
   - "Time kya hai"
   - "Kal 7 baje alarm lagao" (alarm screen khulega, time bhara milega)
   - "Tum kaun ho"
5. Mic na ho to neeche box me command **type** karke bhi bhej sakte ho —
   testing ke liye yehi sabse asaan hai.
6. **Background sunna** ke liye: model ready hone ke baad neeche
   **"▶ Background sunna START karo"** dabao, aur ek baar
   **"Battery bandobosto karo"** wala button dabakar Jarvis ko
   battery whitelist me daal do. Uske baad YouTube kholkar bhi
   "Jarvis" bolkar bula sakte ho (upar wala Phase 2 section dekho).

Jarvis jawab awaaz me dega (phone ka Hindi TTS voice; na ho to English).
Phone ki Settings → Text-to-speech me chaaho to Hindi voice data
alag se download kar sakte ho.

## Developers ke liye

- Build: GitHub Actions (`.github/workflows/build-apk.yml`) —
  JDK 17, Android SDK 35, Gradle 8.9, `./gradlew assembleDebug`.
- Package: `com.aship.jarvis` — modules:
  `voice/` (speech engines + model download), `commands/` (offline
  command rules), `actions/` (phone actions), `speaker/` (TTS),
  `prefs/` (settings), `service/` (Phase 2 wake-word foreground
  service).
- Offline speech: [Vosk](https://alphacephei.com/vosk/) small Hindi model
  (`vosk-model-small-hi-0.22`), Apache-2.0.
- Signing: `keystore/keystore.b64` (repo root) holds a base64 PKCS12
  CI keystore (alias `jarvis`). `app/build.gradle` decodes it at
  configuration time into `app/build/` and signs debug + release
  with it, so every CI build has the same signature and installs
  over the previous one. Without the .b64, builds fall back to the
  default debug key. Public CI key by design — not a store key.
