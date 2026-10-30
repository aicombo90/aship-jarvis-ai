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
- **Phase 2 fixes + in-app update (done):** background se app ab sach
  me khulta hai (overlay bridge), background me samajh na aane par
  Jarvis chup rehta hai, command session ki samay-seema, aur app ke
  andar hi update check/download (GitHub Releases se)
- **Advanced command pack (v1.4):** kisi bhi installed app ko naam
  bolkar kholna, battery status, volume control, gaana (media)
  control, Google / YouTube search, aur timer
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

## App ke andar update (naya)

Har feature build GitHub par ek **Release** ke roop me bhi publish
hota hai (tag `v<versionCode>` — jaise `v2` — aur APK asset ka naam
hamesha `app-debug.apk`). App khud hi update bata deta hai:

1. App khulne par chup-chaap latest release check hota hai. Naya
   version ho to pop-up aayega: **"Naya version aa gaya hai!"**
2. **Download karo** dabao. Pehli baar Android **"Install unknown
   apps"** ki permission maangega — ASHIP JARVIS AI ko **Allow**
   karke wapas aao aur phir Download dabao.
3. Download poora hote hi system installer khul jayega — **Install**
   dabao. Fixed signing key ki wajah se naya version purane ke upar
   hi baithega: **download kiya Hindi model aur settings safe
   rahenge, uninstall nahi karna padega.**
4. Haath se check karna ho to neeche wala **UPDATE CHECK KARO**
   button dabao — latest ho to "Aap latest version par hain"
   dikhega.

Check fail ho (internet band, GitHub busy) to auto-check koi error
nahi dikhata — chup rehta hai. Haath wale check par "check nahi ho
paya" ka chhota sa message aayega.

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
| **Display over other apps (SYSTEM_ALERT_WINDOW)** (Phase 2 fix, Settings se diya jata hai) | Android 10+ background service se app kholna (`startActivity`) chup-chaap rok deta hai — isiliye Jarvis "YouTube khol diya" bol deta tha par YouTube khulta nahi tha. Is permission ke saath wake service launch ke pal bhar (~0.6 second) ke liye ek 1x1 pixel ka **adrishya** overlay window dikhata hai, app kholta hai, aur overlay hata deta hai. Screen par koi popup/ad kabhi nahi dikhta. Permission na ho to Jarvis jhooth nahi bolega — saaf kahega ki permission chahiye. |
| **Install packages (REQUEST_INSTALL_PACKAGES)** (in-app update) | GitHub Release se download hue naye APK ko install karne ke liye. Pehli baar Android "Install unknown apps" me is app ko Allow maangega — bina Allow ke update download shuru hi nahi hota. Sirf is app ke apne update ke liye istemal hota hai, koi dusri app install nahi hoti. |

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

### Phase 2 fixes (is build me)

- **Wake word ki fuzzy matching (v1.7):** "Jarvis" ke aam galat-sune roop bhi chalenge, aur app ke background section ki **"Mic ne aakhri suna"** line dikhayegi ki recognizer ne asal me kya suna.
- **Background se app ab sach me khulta hai.** Pehle Jarvis "YouTube
  khol diya hai" bol deta tha par Android 10+ ke background-launch
  niyam ki wajah se app khulta hi nahi tha. Ab wake service overlay
  bridge se launch karta hai (upar wali "Display over other apps"
  permission ek baar deni hogi — app ke andar **OVERLAY PERMISSION**
  wala button hai), aur permission na ho to Jarvis sach bolega ki
  permission chahiye.
- **Background me faltu bakbak band.** Aas-paas ki awaaz ya TV se
  galti se koi "command" ban jata tha to Jarvis "Ye command abhi
  available nahi hai, Phase 3 me aayega" bol padta tha. Ab
  background mode me samajh na aaye to Jarvis **chup** rehta hai aur
  wapas wake word sunne lagta hai. (App saamne khula ho to wahi
  jawab ab bhi milega — wahan jaankari kaam ki hai.)
- **Command session ki samay-seema.** "Jarvis" ke baad command
  sunne ka session zyada se zyada **~7 second** chalega, aur aakhiri
  badli hui awaaz ke **~2.5 second** ki chuppi par khud band ho
  jayega — lagatar shor session ko latka nahi sakta.

### Chhoti tuning (v1.2)

- **Saamne sunne me system recognizer pehle.** Chhota Vosk Hindi
  model owner ke Bengali lehje wali Hindi ko aksar galat sunta tha
  (aur galat sunne par wahi purana fallback jawab aa jata tha),
  jabki Android ka system recognizer use sahi samajhta hai. Isliye
  ab AUTO/ONLINE mode me tap-to-speak **system recognizer** se
  chalega; Vosk sirf OFFLINE mode me chalega, ya tab jab system
  recognizer phone par uplabdh na ho / start hi na ho paye.
  (Background wake-word sunna pehle ki tarah Vosk se hi hai.)
- **Facebook/WhatsApp ke dusre package naam.** "Facebook kholo"
  par pehle main app (`com.facebook.katana`), na mile to Facebook
  Lite (`com.facebook.lite`) khulta hai; WhatsApp par pehle
  `com.whatsapp`, na mile to WhatsApp Business
  (`com.whatsapp.w4b`). Ye kram saamne aur background — dono
  raaston par lagta hai, aur koi bhi na mile to wahi sachha
  "nahi mila" wala jawab milega.

### Mic sharing (v1.3)

- **Tap-to-speak aur background sunna ab mic baant lete hain.**
  Phone me mic ek hi hota hai: background wake service (Vosk) mic
  pakde ho to Android saamne wale Google recognizer ko mana kar
  deta tha ("Speech Recognition and Synthesis from Google cannot
  record now as ASHIP JARVIS AI is recording") aur woh chalu hote
  hi band ho jata tha. Ab 🎤 Tap-to-speak dabate hi app wake
  service ko kuch der ke liye **rok** deta hai — wake session band,
  mic khali — par service aur uska notification chalu rehte hain.
  Saamne wala sunna khatm hote hi app use **khud wapas chalu** kar
  deta hai; activity kabhi resume bhejna bhool jaye to service
  **12 second** me khud hi wapas sunne lagta hai. (v1.6: resume ab
  result/error aane par aur app se bahar jaate hi bhi turant bhej
  diya jata hai; atka hua command flow ho to timer use tod kar
  wapas sunna shuru kar deta hai — bolta hua jawab kabhi beech me
  nahi katata.)

### Advanced command pack (v1.4)

Naye commands — ye sab saamne (🎤 Tap to speak) bhi chalte hain aur
background wake word se bhi:

- **Koi bhi app naam bolkar kholo** — "Telegram kholo", "MX Player
  kholo", "… open karo". Jarvis launcher ki saari apps ke naam
  (label) se mila kar dhoondhta hai; YouTube / Facebook / WhatsApp /
  Camera / Chrome / Settings wale pakke naam pehle ki tarah pehle
  khulenge. App na mile to Jarvis saaf kahega — "… naam ki app is
  phone me nahi mili". (Android 11+ ke package-visibility niyam ki
  wajah se manifest me MAIN/LAUNCHER wala `<queries>` block hai —
  `QUERY_ALL_PACKAGES` jaisi badi permission **nahi** li gayi.)
- **Battery** — "Battery kitna hai" / "Battery dekho" → percent aur
  charging ka haal awaaz me. Koi permission nahi chahiye.
- **Volume** — "Volume badhao" / "Awaaz tez karo" (ek step upar),
  "Volume kam karo", "Volume full karo", "Mute karo" / "Volume
  band". Sirf "Volume" bolne par haal bata dega. Ye AudioManager se
  seedha hota hai — activity nahi chahiye, isliye background se bhi
  sach me chalta hai.
- **Gaana (media)** — "Gaana chalao" / "Play karo", "Gaana roko" /
  "Pause karo", "Agla gaana", "Pichla gaana" — jo music app chal
  rahi hai use media button ka signal jata hai.
- **Google search** — "Google par \<sawaal\> search karo" ya
  "\<sawaal\> google karo" → browser me search khul jayega.
  Background se overlay bridge wala raasta lagega.
- **YouTube search** — "YouTube par \<cheez\> search karo" /
  "YouTube me \<cheez\> dikhao" → YouTube ke search results
  khulenge (sirf "YouTube kholo" bolne par pehle ki tarah app
  khulega, search nahi).
- **Timer** — "5 minute ka timer lagao", "30 second timer",
  "1 ghante ka timer" → clock app me timer lag jayega (jahan
  support hai wahan bina screen dikhaye, `EXTRA_SKIP_UI` se).
  `ACTION_SET_TIMER` ke liye koi permission nahi chahiye.

Jo naye kaam activity kholte hain (search, timer, naam wala app),
woh background me pehle ki tarah overlay bridge se hi jaate hain —
permission na ho to Jarvis sach bolega, jhootha "khol diya" nahi.

### Siri orb + casual speech (v1.5)

- **Siri jaisa gola (orb)** — background me "Jarvis" sunte hi
  screen ke neeche beech me ek chamakta hua gola (gehra neela →
  baingani → gulabi) Siri ke andaaz me ubhar aata hai. Command ka
  intezaar karte samay woh dheere-dheere "saans" leta hai (pulse),
  aur jawab bolte samay uske andar ki chamak ghoomti hai
  (shimmer). **Jawab text me bhi** gole ke upar ek chhote kaale
  bubble me dikhta hai — bina app khole padh sakte ho. Jawab
  khatm hone ke ~1.5 second baad gola khud fade hokar gayab ho
  jata hai. Ye sirf tab dikhega jab "Display over other apps"
  permission mili ho; na ho to awaaz wala flow pehle jaisa hi
  chalega, kuch atakta nahi. Gola sirf dikhawa hai — us par tap
  karne se kuch nahi hota, aur neeche wali app ke taps bhi nahi
  rukte.
- **Halki-phulki boli bhi samajhta hai** — beech ke bina-matlab
  shabd ("zara", "bhai", "please", "ek", "toh" ...) khud hat
  jate hain, aur command kisi lambe vakya ke beech me chhupa ho
  ("accha suno torch band kar do") to bhi Jarvis use pakad leta
  hai. Do suraksha niyam: Google / YouTube search ki query kabhi
  nahi kat-ti (search hamesha pehle chalti hai), aur bina
  "kholo / open" bole sirf app ka naam lene se app NAHI khulta.
- Torch / alarm / battery / volume ke kuch aur galat-sune roop
  (टार्च, अलारम, ব্যাট্রি, वॉल्युम ...) bhi ab pehchane jate
  hain.

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
- **Ek samay par ek hi sunna.** Mic ek hi hai, isliye background
  sunna aur 🎤 Tap-to-speak ek hi pal me saath nahi chalte — v1.3
  se app khud pehle background sunna rok kar phir saamne sunta
  hai, aur baad me use wapas chalu kar deta hai (upar "Mic
  sharing" dekho).
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
  command rules), `actions/` (phone actions + overlay launch bridge),
  `speaker/` (TTS), `prefs/` (settings), `service/` (Phase 2
  wake-word foreground service), `update/` (GitHub Releases update
  check + DownloadManager download/install).
- Offline speech: [Vosk](https://alphacephei.com/vosk/) small Hindi model
  (`vosk-model-small-hi-0.22`), Apache-2.0.
- Signing: `keystore/keystore.b64` (repo root) holds a base64 PKCS12
  CI keystore (alias `jarvis`). `app/build.gradle` decodes it at
  configuration time into `app/build/` and signs debug + release
  with it, so every CI build has the same signature and installs
  over the previous one. Without the .b64, builds fall back to the
  default debug key. Public CI key by design — not a store key.
