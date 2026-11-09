package com.aship.jarvis.commands

import android.content.Context
import com.aship.jarvis.commands.PlayIntent.SpokenLang
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * v79: LIVE KHOJ + KHOBOR — Maya ab taaza web se jawab
 * de sakti hai, sirf apni seekhi hui jaankari se nahi.
 *
 * Kaam (user, 2026-11-09): "X কী / X সম্পর্কে বলো /
 * X-এর দাম কত / X কবে / X কোথায়" bole to live web
 * se dhoondhkar pehle 1–3 natije bole; "খুলে দাও"
 * bole to pehla natija browser me khole; "আজকের
 * খবর বলো" bole to aaj ki taaza khabrein sunaye.
 *
 * Do muft srot, koi API key nahi, koi nayi
 * dependency / permission nahi (INTERNET pehle se
 * ghoshit hai):
 *  - Khoj: DuckDuckGo lite HTML
 *    (lite.duckduckgo.com/lite/?q=...) — pehla
 *    prayaas; 0 natije mile to html endpoint
 *    (html.duckduckgo.com/html/?q=...) — is VM se
 *    jaanch me lite ne HTTP 202 anomaly (bot-check)
 *    page diya aur html endpoint ne HTTP 200 par
 *    asal natije diye, isliye dono ka parser ek
 *    hi hai (class "result-link"/"result__a" aur
 *    "result-snippet"/"result__snippet" — jo mile)।
 *    Natijon ka asal URL DDG redirect ("uddg="
 *    param) se nikaala jaata hai।
 *  - Khabar: Google News RSS (Bangla feed
 *    hl=bn, fail ho to English India feed) —
 *    XmlPullParser (Android built-in) se top 5
 *    item title (+ source)।
 *
 * Imaandaari ka niyam (code me hi bandha): jo
 * mile wahi bola jata hai — snippet / headline
 * ke roop me; tareekh, daam ya khabar kabhi
 * gadhi nahi jati; fetch fail ho ya net na ho to
 * saaf "আনতে পারলাম না" — naqli natija kabhi
 * nahi. Page khud se kabhi nahi khulta — sirf
 * "খুলে দাও" par (ShowMeIntent wala hi
 * open-URL raasta)।
 *
 * Kram dispatch me: NEWS pehle ("আজকের খবর কী"
 * khoj na bane), phir OPEN (bilkul "খুলে দাও" —
 * app kholne wale hukm se takraar na ho), phir
 * SEARCH — aur teeno ShowMeIntent / ImageIntent
 * ke BAAD (v76 DETAILS "এটার দাম কত" aur v78
 * apna raasta pehle lein), agent se pehle.
 * Sab fetch apne thread par, 10 s timeout,
 * har galti pakdi hui.
 */
object WebSearchIntent {

    /** Teen kaam — dispatch inhi me se ek chunta hai. */
    enum class Kind { SEARCH, NEWS, OPEN }

    /** Ek khoj-natija: shirshak + asal URL + chhota tukda. */
    data class WebResult(val title: String, val url: String, val snippet: String)

    /** Ek khabar: shirshak + srot (akhbaar/channel ka naam). */
    data class NewsItem(val title: String, val source: String)

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_RESULTS = 5
    private const val MAX_BODY_CHARS = 1_500_000

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    // ---------------------------------------------------------------
    // Aakhiri khoj — "খুলে দাও" ke liye ("jarvis_websearch" prefs)
    // ---------------------------------------------------------------

    private const val PREFS_NAME = "jarvis_websearch"
    private const val KEY_QUERY = "last_query"
    private const val KEY_URLS = "last_urls"
    private const val KEY_TITLES = "last_titles"

    fun saveLastSearch(context: Context, query: String, results: List<WebResult>) {
        try {
            val top = results.take(MAX_RESULTS)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_QUERY, query)
                .putString(KEY_URLS, top.joinToString("\n") { it.url })
                .putString(KEY_TITLES, top.joinToString("\n") { it.title.replace("\n", " ") })
                .apply()
        } catch (t: Throwable) {
            // Yaad rakhna bonus hai — khojna isse nahi rukta.
        }
    }

    /** Aakhiri khoj (sawal + natije), ya null (abhi kuch khoja hi nahi). */
    fun loadLastSearch(context: Context): Pair<String, List<WebResult>>? {
        return try {
            val prefs = context.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE
            )
            val query = prefs.getString(KEY_QUERY, null)?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return null
            val urls = prefs.getString(KEY_URLS, null)
                ?.split("\n")?.map { it.trim() }
                ?.filter { it.isNotEmpty() } ?: return null
            val titles = prefs.getString(KEY_TITLES, null)
                ?.split("\n") ?: emptyList()
            val results = urls.mapIndexed { i, url ->
                WebResult(
                    title = titles.getOrNull(i).orEmpty(),
                    url = url,
                    snippet = ""
                )
            }
            if (results.isEmpty()) null else Pair(query, results)
        } catch (t: Throwable) {
            null
        }
    }

    fun hasLastSearch(context: Context): Boolean = loadLastSearch(context) != null

    // ---------------------------------------------------------------
    // 1. NEWS — "আজকের খবর বলো / খবর শোনাও"
    // ---------------------------------------------------------------

    private val NEWS_MARKERS = listOf(
        // Bangla (script)
        "আজকের খবর", "কালকের খবর", "গতকালের খবর",
        "খবর বলো", "খবর শোনাও", "খবর শুনাও",
        "খবর দাও", "খবর কী", "খবর শুনতে চাই",
        // Bangla (roman roop)
        "ajker khobor", "khobor bolo", "khobor shonao",
        "khobor shunao", "khobor dao", "khobor ki",
        // Hindi
        "आज की खबर", "आज की खबरें", "खबर सुनाओ",
        "खबर बताओ", "खबरें बताओ",
        // English
        "today's news", "todays news", "today news",
        "tell me the news", "read the news"
    )

    /** Aaj ki khabar maangne wala hukm hai kya? */
    fun isNewsTask(rawText: String): Boolean {
        if (rawText.isBlank()) return false
        val lower = rawText.lowercase(Locale.ROOT)
        return NEWS_MARKERS.any { lower.contains(it) }
    }

    // ---------------------------------------------------------------
    // 2. OPEN — bilkul "খুলে দাও" (aakhiri khoj ka pehla natija)
    // ---------------------------------------------------------------

    /**
     * Bilkul wahi shabd — isliye "YouTube খুলে দাও"
     * (app kholo) jaisi baat yahan kabhi nahi aati:
     * usme app ka naam bhi hota hai, ye jaanch
     * barabar wala milaan hi maangti hai.
     */
    private val OPEN_MARKERS = setOf(
        // Bangla (script)
        "খুলে দাও", "খুলে দেখাও", "খুলে দাও তো",
        "প্রথমটা খুলে দাও", "ওটা খুলে দাও",
        "লিংকটা খুলে দাও", "লিংক খুলে দাও",
        "রেজাল্ট খুলে দাও", "খোলো",
        // Bangla (roman roop)
        "khule dao", "khule dekhao", "prothomta khule dao",
        // Hindi
        "खोल दो", "पहला वाला खोल दो",
        // English
        "open it", "open the first one", "open the link"
    )

    /** "খুলে দাও" — bilkul wahi, aur kuch nahi. */
    fun isOpenTask(rawText: String): Boolean {
        if (rawText.isBlank()) return false
        val norm = rawText.lowercase(Locale.ROOT)
            .trim()
            .trim('।', '.', '!', '?', ',', '"', '\'', '(', ')', ' ')
            .replace(Regex("\\s+"), " ")
        return norm in OPEN_MARKERS
    }

    // ---------------------------------------------------------------
    // 3. SEARCH — "X কী / X সম্পর্কে বলো / X-এর দাম কত..."
    // ---------------------------------------------------------------

    /**
     * Mazboot chinh — inme se koi ho AUR kaatne ke
     * baad vishay bache to hi SEARCH hai. Akela
     * "কী" jaan-boojh kar yahan nahi hai (woh har
     * baat me aata hai) — uske liye neeche wala
     * peechhe-wala "কী/কি/কে" niyam hai.
     */
    private val SEARCH_STRONG_MARKERS = listOf(
        // Bangla (script)
        "সম্পর্কে বলো", "সম্পর্কে জানাও", "সম্পর্কে বল",
        "সম্পর্কে জানতে চাই", "দাম কত", "কত টাকা",
        "কত দাম", "কবে", "কোথায়", "কখন",
        "কোথা থেকে", "কীভাবে", "কিভাবে", "কেন",
        // Bangla (roman roop)
        "somporke bolo", "somporke janao", "dam koto",
        "kobe", "kothay", "kokhon", "kivabe",
        // Hindi
        "के बारे में बताओ", "के बारे में बता",
        "कीमत कितनी", "कब", "कहाँ", "कैसे",
        // English (poora wakyaansh — akela "what" bahut dheela hai)
        "what is", "when is", "where is",
        "how much is", "how much does"
    )

    /** Kaatne wale shabd — vishay se hatane ke liye (sabse lambe pehle). */
    private val SEARCH_STRIP_MARKERS = (SEARCH_STRONG_MARKERS + listOf(
        "সম্পর্কে", "জানাও", "জানতে", "চাই", "বলো", "বল",
        "আমাকে", "আমায়", "একটু", "তো", "দয়া", "করে",
        "please", "bolo", "aamake", "ektu",
        "somporke", "the", "a", "an", "is", "does"
    )).sortedByDescending { it.length }

    /** Bacha hua vishay inme se ho to ye khoj nahi — niji / ghadi / mausam ki baat. */
    private val TOPIC_BLOCK_WORDS = setOf(
        // Sarvanaam (niji sawal — "তুমি কবে আসবে" khoj nahi)
        "আমি", "তুমি", "তোমার", "আমার", "এটা", "ওটা", "সেটা",
        "সে", "তারা", "তুই", "তোর", "আমাদের", "তোমাদের",
        "কার", "কাকে", "কাদের",
        "you", "your", "yours", "my", "mine", "i", "me",
        "this", "that", "it", "they", "he", "she", "we",
        "तुम", "तुम्हारा", "मेरा", "मैं", "यह", "वह",
        // Ghadi / tareekh — CommandEngine ka kaam (v59), khoj nahi
        "সময়", "টাইম", "time", "তারিখ", "date", "কটা", "বাজে",
        "ঘড়ি", "ঘণ্টা", "সময়",
        // Mausam — home card ka kaam (v77), khoj nahi
        "আবহাওয়া", "আবহাওয়া", "weather", "তাপমাত্রা", "বৃষ্টি", "মেঘ",
        // Khabar — NEWS ka kaam
        "খবর", "khobor"
    )

    /** Sirf ye bache to vishay nahi — "আজ কী" khoj nahi hai. */
    private val FILLER_ONLY_WORDS = setOf("আজ", "কাল", "এখন", "today", "now")

    /** Khoj wala sawal hai kya (chinh + bacha hua vishay dono)? */
    fun isSearchTask(rawText: String): Boolean {
        if (rawText.isBlank()) return false
        val lower = rawText.lowercase(Locale.ROOT)
        if (SEARCH_STRONG_MARKERS.any { lower.contains(it) }) {
            return extractSearchTopic(rawText) != null
        }
        // Peechhe wala "কী / কি / কে" — "কৃষকবন্ধু কী" khoj
        // hai, "এটা কী" nahi (vishay sarvanaam hai)।
        val trimmed = lower.trim().trimEnd('?', '।', '.', '!', ' ')
        if (trimmed.endsWith(" কী") || trimmed.endsWith(" কি") ||
            trimmed.endsWith(" কে") || trimmed.endsWith(" ki")
        ) {
            return extractSearchTopic(rawText) != null
        }
        return false
    }

    /** Vishay nikaalo ("আজ সোনার দাম কত" -> "সোনার"), na mile to null। */
    fun extractSearchTopic(rawText: String): String? {
        if (rawText.isBlank()) return null
        var s = " " + rawText.lowercase(Locale.ROOT) + " "
        s = s.replace("?", " ").replace("।", " ")
        for (marker in SEARCH_STRIP_MARKERS) {
            s = s.replace(marker, " ")
        }
        // Peechhe wala sawal-shabd bhi kaato ("কৃষকবন্ধু কী" -> "কৃষকবন্ধু")।
        s = s.replace(Regex("\\s+(কী|কি|কে|ki)\\s*$"), " ")
        val kept = s.split(Regex("\\s+"))
            .map { it.trim('.', ',', '!', '"', '\'', '(', ')') }
            .filter { it.isNotEmpty() && it !in FILLER_ONLY_WORDS }
        if (kept.isEmpty()) return null
        if (kept.any { it in TOPIC_BLOCK_WORDS }) return null
        return kept.joinToString(" ").trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Teenon me se kaunsa kaam hai — NEWS pehle, phir
     * OPEN, phir SEARCH; koi na ho to null। [hasLastSearch]
     * sirf milaan ke liye hai (OPEN par vishay na ho to
     * bhi imaandaar jawab milta hai, isliye routing
     * ke liye zaroori nahi — ShowMeIntent jaisi shakal
     * ke liye rakha hai)।
     */
    fun classify(rawText: String, hasLastSearch: Boolean): Kind? {
        // [hasLastSearch] routing ke liye zaroori nahi:
        // OPEN par aakhiri khoj na ho to bhi imaandaar
        // jawab milta hai — ShowMeIntent jaisi shakal
        // ke liye signature waisa hi rakha hai.
        if (rawText.isBlank()) return null
        if (isNewsTask(rawText)) return Kind.NEWS
        if (isOpenTask(rawText)) return Kind.OPEN
        if (isSearchTask(rawText)) return Kind.SEARCH
        return null
    }

    // ---------------------------------------------------------------
    // Live khoj — DuckDuckGo (lite, phir html endpoint)
    // ---------------------------------------------------------------

    /**
     * Live khoj — pehle lite endpoint, 0 natije mile
     * to html endpoint (is VM se jaanch me lite bot-
     * check par atka, html ne asal natije diye)।
     * Apne thread par bulao; fail ho to khaali list
     * (kabhi naqli natija nahi)।
     */
    fun searchWeb(query: String): List<WebResult> {
        return try {
            val liteUrl = "https://lite.duckduckgo.com/lite/?q=" +
                URLEncoder.encode(query, "UTF-8")
            val lite = fetchDuckResults(liteUrl)
            if (lite.isNotEmpty()) return lite
            val htmlUrl = "https://html.duckduckgo.com/html/?q=" +
                URLEncoder.encode(query, "UTF-8")
            fetchDuckResults(htmlUrl)
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun fetchDuckResults(url: String): List<WebResult> {
        val html = httpGet(url, "text/html") ?: return emptyList()
        return parseDuckResults(html)
    }

    /**
     * DuckDuckGo HTML se natije nikaalo — lite aur
     * html dono ki class ("result-link"/"result__a"
     * shirshak, "result-snippet"/"result__snippet"
     * tukda)।
     * Asal URL: html endpoint par DDG redirect hota
     * hai ("uddg=" param, URL-encoded); lite par
     * seedha URL. Dono sambhale gaye hain.
     */
    fun parseDuckResults(html: String): List<WebResult> {
        data class Found(
            val start: Int,
            val isTitle: Boolean,
            val href: String,
            val text: String
        )
        val found = mutableListOf<Found>()
        val anchorRe = Regex(
            "<a\\b[^>]*>(.*?)</a>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val hrefRe = Regex(
            "href\\s*=\\s*\"([^\"]*)\"",
            RegexOption.IGNORE_CASE
        )
        for (m in anchorRe.findAll(html)) {
            val openTag = m.value.substringBefore('>')
            val isTitle = openTag.contains("result__a") ||
                openTag.contains("result-link")
            val isSnippet = openTag.contains("result__snippet") ||
                openTag.contains("result-snippet")
            if (!isTitle && !isSnippet) continue
            val href = hrefRe.find(openTag)?.groupValues?.getOrNull(1)
                ?: continue
            val text = cleanHtmlText(m.groupValues[1])
            if (text.isEmpty()) continue
            found.add(Found(m.range.first, isTitle, href, text))
        }
        val titles = found.filter { it.isTitle }
        val snippets = found.filter { !it.isTitle }
        val out = mutableListOf<WebResult>()
        val seen = mutableSetOf<String>()
        for (t in titles) {
            val url = resolveDuckUrl(t.href) ?: continue
            if (!seen.add(url)) continue
            val snip = snippets.firstOrNull { it.start > t.start }
                ?.text.orEmpty()
            out.add(WebResult(t.text, url, snip))
            if (out.size >= MAX_RESULTS) break
        }
        return out
    }

    /** DDG redirect ("uddg=") se asal URL, ya seedha URL. */
    private fun resolveDuckUrl(hrefRaw: String): String? {
        var href = htmlUnescape(hrefRaw).trim()
        if (href.startsWith("//")) href = "https:$href"
        val uddg = Regex("[?&]uddg=([^&]*)").find(href)
            ?.groupValues?.getOrNull(1)
        if (uddg != null) {
            return try {
                URLDecoder.decode(uddg, "UTF-8").trim()
                    .takeIf { it.startsWith("http://") || it.startsWith("https://") }
            } catch (t: Throwable) {
                null
            }
        }
        return href.takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        }
    }

    // ---------------------------------------------------------------
    // Live khabar — Google News RSS (Bangla, phir English India)
    // ---------------------------------------------------------------

    /**
     * Aaj ki khabar — pehle Bangla feed, khaali/fail
     * ho to English India feed; dono fail ho to null
     * (kabhi naqli khabar nahi)। Apne thread par bulao।
     */
    fun fetchNews(): List<NewsItem>? {
        return try {
            val bn = fetchNewsFeed(
                "https://news.google.com/rss?hl=bn&gl=IN&ceid=IN:bn"
            )
            if (!bn.isNullOrEmpty()) return bn
            fetchNewsFeed(
                "https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en"
            )
        } catch (t: Throwable) {
            null
        }
    }

    private fun fetchNewsFeed(url: String): List<NewsItem>? {
        val xml = httpGet(url, "application/rss+xml") ?: return null
        return try {
            parseNewsRss(xml)
        } catch (t: Throwable) {
            null
        }
    }

    /** Google News RSS se top 5 khabar (title + source). */
    fun parseNewsRss(xml: String): List<NewsItem> {
        val out = mutableListOf<NewsItem>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())
        var inItem = false
        var curTitle: String? = null
        var curSource: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "item" -> {
                            inItem = true
                            curTitle = null
                            curSource = null
                        }
                        "title" -> if (inItem && curTitle == null) {
                            curTitle = parser.nextText()?.trim()
                        }
                        "source" -> if (inItem) {
                            curSource = parser.nextText()?.trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "item" && inItem) {
                        inItem = false
                        val raw = curTitle?.takeIf { it.isNotEmpty() }
                        if (raw != null) {
                            val cleaned = cleanHtmlText(raw)
                            if (cleaned.isNotEmpty()) {
                                val (title, src) = splitNewsTitle(
                                    cleaned, curSource
                                )
                                out.add(NewsItem(title, src))
                            }
                        }
                        if (out.size >= MAX_RESULTS) break
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    /**
     * "Shirshak - Srot" ko alag karo — <source> ho to
     * wahi, warna aakhiri " - " ke peechhe wala hissa
     * (sirf tab jab woh 40 akshar se chhota ho, warna
     * woh shirshak ka hissa hai)।
     */
    private fun splitNewsTitle(raw: String, source: String?): Pair<String, String> {
        var title = raw.trim()
        var src = source?.trim().orEmpty()
        if (src.isNotEmpty() && title.endsWith(" - $src")) {
            title = title.removeSuffix(" - $src").trim()
        }
        if (src.isEmpty()) {
            val idx = title.lastIndexOf(" - ")
            if (idx > 10) {
                val tail = title.substring(idx + 3).trim()
                if (tail.isNotEmpty() && tail.length <= 40) {
                    src = tail
                    title = title.substring(0, idx).trim()
                }
            }
        }
        return Pair(title, src)
    }

    // ---------------------------------------------------------------
    // HTTP + HTML safaai (WeatherFetcher wali hi paddhati)
    // ---------------------------------------------------------------

    private fun httpGet(urlString: String, accept: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", accept)
            conn.setRequestProperty(
                "Accept-Language", "bn-IN,bn;q=0.9,en;q=0.8"
            )
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val reader = BufferedReader(
                InputStreamReader(conn.inputStream, Charsets.UTF_8)
            )
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append('\n')
                if (sb.length > MAX_BODY_CHARS) break
            }
            reader.close()
            sb.toString()
        } catch (t: Throwable) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (t: Throwable) {
                // Chup-chaap band।
            }
        }
    }

    /** Tag hatao, entity kholo, khaali jagah ek karo. */
    fun cleanHtmlText(raw: String): String {
        val noTags = raw.replace(Regex("<[^>]*>"), " ")
        return htmlUnescape(noTags)
            .replace(Regex("\\s+"), " ")
            // Aadha kata hua tag bach gaya ho to uske
            // kone bhi hatao ("<" / ">" bolne me kaam nahi)।
            .replace("<", "").replace(">", "")
            .trim()
    }

    /** HTML entity kholo (&amp; &lt; &#123; &#x1F; ...). */
    fun htmlUnescape(input: String): String {
        var out = input.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
        out = Regex("&#(\\d+);").replace(out) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code != null && code in 1..0xFFFF) code.toChar().toString() else " "
        }
        out = Regex("&#x([0-9a-fA-F]+);").replace(out) { m ->
            val code = m.groupValues[1].toIntOrNull(16)
            if (code != null && code in 1..0xFFFF) code.toChar().toString() else " "
        }
        return out
    }

    // ---------------------------------------------------------------
    // Boli jaane wali linein (user ki bhasha me, hamesha sachhi)
    // ---------------------------------------------------------------

    private fun num(n: Int, lang: SpokenLang): String {
        if (lang != SpokenLang.BENGALI) return n.toString()
        val bn = arrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
        return n.toString().map {
            if (it.isDigit()) bn[it - '0'] else it
        }.joinToString("")
    }

    private fun stop(lang: SpokenLang): String =
        if (lang == SpokenLang.ENGLISH) ". " else "। "

    /** Khoj shuru — "খুঁজছি..." (screen / status par). */
    fun searchingLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "ইন্টারনেটে খুঁজছি..."
        SpokenLang.HINDI -> "इंटरनेट पर खोज रही हूँ..."
        SpokenLang.ENGLISH -> "Searching the internet..."
    }

    /** Khabar la rahe hain — "আনছি..." (screen / status par). */
    fun fetchingNewsLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "আজকের খবর আনছি..."
        SpokenLang.HINDI -> "आज की खबरें ला रही हूँ..."
        SpokenLang.ENGLISH -> "Fetching today's news..."
    }

    /** Khoj ke pehle 3 natije + "খুলে দাও" ki peshkash. */
    fun searchSpoken(query: String, results: List<WebResult>, lang: SpokenLang): String {
        // [query] sirf shakal ke liye hai — bola jaane
        // wala hissa natijon se hi banta hai.
        val top = results.take(3)
        val sb = StringBuilder()
        when (lang) {
            SpokenLang.BENGALI -> sb.append("ইন্টারনেটে পেলাম — ")
            SpokenLang.HINDI -> sb.append("इंटरनेट पर मिला — ")
            SpokenLang.ENGLISH -> sb.append("Found on the internet — ")
        }
        top.forEachIndexed { i, r ->
            if (i > 0) sb.append(" ")
            sb.append(num(i + 1, lang)).append(". ")
                .append(r.title.take(100).trim())
            val snip = r.snippet.take(150).trim()
            if (snip.isNotEmpty()) sb.append(": ").append(snip)
            sb.append(stop(lang))
        }
        when (lang) {
            SpokenLang.BENGALI ->
                sb.append("খুলে দাও বললে প্রথমটা খুলে দিচ্ছি।")
            SpokenLang.HINDI ->
                sb.append("खोल दो बोलो तो पहला वाला खोल दूँगी।")
            SpokenLang.ENGLISH ->
                sb.append("Say 'open it' and I'll open the first one.")
        }
        return sb.toString().take(700).trim()
    }

    /** Aaj ki top 5 khabar — "খবর অনুযায়ী" wali shakal me. */
    fun newsSpoken(items: List<NewsItem>, lang: SpokenLang): String {
        val top = items.take(5)
        val sb = StringBuilder()
        when (lang) {
            SpokenLang.BENGALI -> sb.append("খবর অনুযায়ী আজকের খবর — ")
            SpokenLang.HINDI -> sb.append("खबरों के अनुसार आज की खबरें — ")
            SpokenLang.ENGLISH -> sb.append("According to the news, today's headlines — ")
        }
        top.forEachIndexed { i, item ->
            if (i > 0) sb.append(" ")
            sb.append(num(i + 1, lang)).append(". ")
                .append(item.title.take(120).trim())
            if (item.source.isNotEmpty()) {
                sb.append(" (").append(item.source.take(30).trim()).append(")")
            }
            sb.append(stop(lang))
        }
        return sb.toString().take(800).trim()
    }

    /** Khoj me kuch na mila / net ne jawab na diya. */
    fun searchFailLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI ->
            "ইন্টারনেট থেকে খুঁজে আনতে পারলাম না — নেট আছে কি না দেখো, " +
                "আরেকবার বলো।"
        SpokenLang.HINDI ->
            "इंटरनेट से खोजकर ला नहीं पाई — नेट है कि नहीं देखो, " +
                "फिर से बोलो।"
        SpokenLang.ENGLISH ->
            "I couldn't fetch that from the internet — check the " +
                "net and ask again."
    }

    /** Khabar laane me fail. */
    fun newsFailLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI ->
            "খবর আনতে পারলাম না — নেট আছে কি না দেখো, আরেকবার বলো।"
        SpokenLang.HINDI ->
            "खबरें ला नहीं पाई — नेट है कि नहीं देखो, फिर से बोलो।"
        SpokenLang.ENGLISH ->
            "I couldn't fetch the news — check the net and ask again."
    }

    /** "খুলে দাও" par aakhiri khoj hi nahi hai. */
    fun openNoSearchLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI ->
            "আগে কিছু খুঁজে বলো — তারপর 'খুলে দাও' বললে খুলে দিচ্ছি।"
        SpokenLang.HINDI ->
            "पहले कुछ खोजकर बोलो — फिर 'खोल दो' बोलो तो खोल दूँगी।"
        SpokenLang.ENGLISH ->
            "Search for something first — then say 'open it' and " +
                "I'll open it."
    }

    /** SEARCH pakda par vishay nahi (raksha — aam taur par hota nahi). */
    fun askTopicLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "কী জানতে চাও বলো — বিষয়টা বললেই খুঁজে দিচ্ছি।"
        SpokenLang.HINDI -> "क्या जानना चाहते हो बोलो — विषय बताओ तो खोज दूँगी।"
        SpokenLang.ENGLISH -> "Tell me what you want to know — I'll search for it."
    }

    /** Pehla natija khul gaya. */
    fun openedLine(title: String, lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "প্রথমটা খুলে দিলাম — ${title.take(80)}।"
        SpokenLang.HINDI -> "पहला वाला खोल दिया — ${title.take(80)}।"
        SpokenLang.ENGLISH -> "Opened the first one — ${title.take(80)}."
    }

    /** Browser khula hi nahi (aam wajah)। */
    fun openFailedLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "ব্রাউজার খুলতে পারলাম না — আবার বলো দেখি।"
        SpokenLang.HINDI -> "ब्राउज़र खोल नहीं पाई — फिर से बोलो।"
        SpokenLang.ENGLISH -> "I couldn't open the browser — please say it again."
    }

    /** Seedhi daud ki report — "jarvis_diag" ki "last_agent_run" par। */
    fun saveRouteDiag(context: Context, kind: Kind, ok: Boolean) {
        try {
            context.getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                .edit()
                .putString(
                    "last_agent_run",
                    "steps: websearch(${kind.name.lowercase(Locale.ROOT)})=" +
                        if (ok) "OK" else "FAIL"
                )
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai — asal kaam isse nahi rukta.
        }
    }
}
