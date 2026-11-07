package com.aship.jarvis

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.aship.jarvis.ai.GeminiBrain
import com.aship.jarvis.ai.ProviderChain
import com.aship.jarvis.ai.ProviderKeys

/**
 * v53: API KEYS wali screen — user ke fallback-chain
 * wale saare FREE provider (Gemini, Groq, OpenRouter,
 * Cloudflare, SambaNova, Cohere) ki key ek jagah।
 *
 * Har provider ko apna khana: naam + key kahan milegi,
 * status (সেট আছে / নেই), key likhne ka khana, SAVE
 * aur HATAO। Cloudflare ko key ke saath Account ID bhi
 * chahiye (Workers AI bina account ke nahi chalta)।
 *
 * Key sirf is phone par rehti hai ([ProviderKeys]) —
 * yahan se key kahin bheji nahi jati, sirf bandh kar
 * rakhi jati hai। Gemini wali key yahin se bhi badli
 * ja sakti hai — wohi [GeminiBrain] wali key hai,
 * isliye main screen wali jagah se kabhi alag nahi
 * padegi। Key ki JAANCH (validate) yahin hoti hai —
 * save karne se pehle: Gemini (v62) aur baaki paanch
 * provider (v71, [ProviderChain.validateKey] se halki
 * list wali call)। Galat key (401/403) kabhi save nahi
 * hoti; net/server ki gadbad par key save karke
 * imaandaar line boli jati hai — purani key tab bhi
 * salamat rehti hai।
 */
class ApiKeysActivity : Activity() {

    private lateinit var container: LinearLayout

    /** Har provider ki status line — save/clear par taza hoti hai। */
    private val statusViews = mutableMapOf<ProviderKeys.Provider, TextView>()

    /** Cloudflare ki Account ID wali status line (token ki line se alag)। */
    private var cloudflareAccountStatus: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_api_keys)
        container = findViewById(R.id.apiKeysContainer)
        findViewById<Button>(R.id.apiKeysBackButton).setOnClickListener { finish() }
        for (provider in ProviderKeys.Provider.values()) {
            container.addView(buildProviderSection(provider))
        }
        refreshAllStatuses()
    }

    override fun onResume() {
        super.onResume()
        refreshAllStatuses()
    }

    private fun refreshAllStatuses() {
        for (provider in ProviderKeys.Provider.values()) refreshStatus(provider)
    }

    private fun refreshStatus(provider: ProviderKeys.Provider) {
        statusViews[provider]?.text = statusLine(provider)
        if (provider == ProviderKeys.Provider.CLOUDFLARE) {
            cloudflareAccountStatus?.text = accountStatusLine()
        }
    }

    private fun statusLine(provider: ProviderKeys.Provider): String =
        if (ProviderKeys.isConfigured(this, provider)) "Status: সেট আছে ✓"
        else "Status: নেই"

    private fun accountStatusLine(): String {
        val id = ProviderKeys.getAccountId(this)
        return if (id.isNullOrBlank()) "Account ID: নেই" else "Account ID: সেট আছে ✓"
    }

    /**
     * Ek provider ka poora hissa: naam + hint, status,
     * key likhne ka khana, SAVE/HATAO — Cloudflare ko
     * Account ID ka ek aur khana।
     */
    private fun buildProviderSection(provider: ProviderKeys.Provider): LinearLayout {
        val section = LinearLayout(this)
        section.orientation = LinearLayout.VERTICAL
        section.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) }

        val title = TextView(this)
        title.text = provider.displayName
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        title.setTypeface(null, Typeface.BOLD)
        section.addView(title)

        val hint = TextView(this)
        hint.text = provider.hint
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        section.addView(hint)

        val status = TextView(this)
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        status.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) }
        section.addView(status)
        statusViews[provider] = status

        val keyInput = EditText(this)
        keyInput.hint = "API key লিখো"
        keyInput.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyInput.maxLines = 1
        keyInput.minHeight = dp(52)
        keyInput.setPadding(dp(12), dp(12), dp(12), dp(12))
        keyInput.setBackgroundResource(R.drawable.input_box_bg)
        keyInput.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        keyInput.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        section.addView(keyInput)

        // Cloudflare: Account ID ka alag khana + uski status line।
        var accountInput: EditText? = null
        if (provider.needsAccountId) {
            val accountStatus = TextView(this)
            accountStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            accountStatus.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            section.addView(accountStatus)
            cloudflareAccountStatus = accountStatus

            accountInput = EditText(this)
            accountInput.hint = "Account ID লিখো"
            accountInput.inputType = InputType.TYPE_CLASS_TEXT
            accountInput.maxLines = 1
            accountInput.minHeight = dp(52)
            accountInput.setPadding(dp(12), dp(12), dp(12), dp(12))
            accountInput.setBackgroundResource(R.drawable.input_box_bg)
            accountInput.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            accountInput.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            section.addView(accountInput)
        }

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) }

        val saveButton = Button(this)
        saveButton.text = "SAVE"
        saveButton.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        saveButton.setOnClickListener {
            val typed = keyInput.text.toString().trim()
            if (typed.isEmpty()) {
                Toast.makeText(this, "Key খালি — আগে key লিখো।", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Save karna: key (+ Cloudflare ka Account ID,
            // agar likha ho) band karo, khane khaali karo,
            // status taza karo. [note] ho to wahi line bolo
            // (lambi wali), warna aam "সেভ হয়ে গেছে" line।
            val doSave = { note: String?, longToast: Boolean ->
                ProviderKeys.setKey(this, provider, typed)
                keyInput.text.clear()
                if (provider.needsAccountId) {
                    val account = accountInput?.text.toString().trim()
                    if (account.isNotEmpty()) ProviderKeys.setAccountId(this, account)
                    accountInput?.text?.clear()
                }
                refreshStatus(provider)
                Toast.makeText(
                    this,
                    note ?: "${provider.displayName} key সেভ হয়ে গেছে।",
                    if (longToast) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                ).show()
            }
            // v62: Gemini key ab sirf yahin save hoti hai (main
            // screen wala save hata) — isliye v22 wali jaanch bhi
            // yahin: galat key kabhi save nahi hogi। v71: baaki
            // paanch provideron ki jaanch bhi yahin — halki list
            // wali call ([ProviderChain.validateKey]): sirf
            // 401/403 = galat key (save mat karo, purani key
            // salamat); 200 = sahi (save); baaki sab (net band,
            // timeout, 429/5xx) = jaanch adhoori — key save
            // karke imaandaar line bolo, kabhi rokna nahi।
            if (provider == ProviderKeys.Provider.GEMINI) {
                saveButton.isEnabled = false
                GeminiBrain.validateKey(typed) { ok, code ->
                    runOnUiThread {
                        saveButton.isEnabled = true
                        if (ok) {
                            doSave(null, false)
                        } else {
                            Toast.makeText(
                                this,
                                if (code == -1) "Key যাচাই হলো না — নেট দেখো, সেভ হয়নি"
                                else "Key ভুল (error $code) — সেভ হয়নি",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            } else if (provider.needsAccountId) {
                // Cloudflare: key + Account ID ek saath jaanche
                // jate hain. Account ID likha ho to wahi, warna
                // pehle se band wala; ek bhi na ho to jaanch ho
                // hi nahi sakti — key save karke wahi bolo।
                val typedAccount = accountInput?.text.toString().trim()
                val effectiveAccount = typedAccount.takeIf { it.isNotEmpty() }
                    ?: ProviderKeys.getAccountId(this)
                if (effectiveAccount == null) {
                    doSave(
                        "Account ID ছাড়া যাচাই করা যায়নি — Account ID দিলে key যাচাই হবে।",
                        true
                    )
                } else {
                    validateAndSave(
                        provider, typed, effectiveAccount, saveButton, doSave
                    )
                }
            } else {
                validateAndSave(provider, typed, null, saveButton, doSave)
            }
        }
        row.addView(saveButton)

        val clearButton = Button(this)
        clearButton.text = "HATAO"
        clearButton.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        clearButton.setOnClickListener {
            ProviderKeys.clearKey(this, provider)
            if (provider.needsAccountId) ProviderKeys.clearAccountId(this)
            keyInput.text.clear()
            accountInput?.text?.clear()
            refreshStatus(provider)
            Toast.makeText(
                this, "${provider.displayName} key সরিয়ে দেওয়া হয়েছে।",
                Toast.LENGTH_SHORT
            ).show()
        }
        row.addView(clearButton)

        section.addView(row)
        return section
    }

    /**
     * v71: Gemini ke alawa kisi provider ki key save karne
     * se pehle halki jaanch ([ProviderChain.validateKey])।
     * Button jaanch bhar band + "যাচাই হচ্ছে…" dikhta hai।
     * VALID (200) = save; INVALID (401/403) = save MAT
     * karo — purani key salamat, saaf line bolo; baaki sab
     * = jaanch adhoori — key save karke imaandaar line
     * bolo. Callback jaanch wale thread par aata hai,
     * isliye UI kaam [runOnUiThread] me।
     */
    private fun validateAndSave(
        provider: ProviderKeys.Provider,
        typed: String,
        accountId: String?,
        saveButton: Button,
        doSave: (String?, Boolean) -> Unit
    ) {
        saveButton.isEnabled = false
        saveButton.text = "যাচাই হচ্ছে…"
        ProviderChain.validateKey(provider, typed, accountId) { result, _ ->
            runOnUiThread {
                saveButton.isEnabled = true
                saveButton.text = "SAVE"
                when (result) {
                    ProviderChain.ValidationResult.VALID -> doSave(null, false)
                    ProviderChain.ValidationResult.INVALID -> {
                        val msg = if (provider.needsAccountId) {
                            "key বা Account ID ভুল মনে হচ্ছে, আবার দেখে নাও — সেভ হয়নি"
                        } else {
                            "এই key টা ভুল মনে হচ্ছে, আবার দেখে নাও — সেভ হয়নি"
                        }
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                    ProviderChain.ValidationResult.INCONCLUSIVE -> {
                        doSave(
                            "ইন্টারনেট/সার্ভারের জন্য যাচাই করা যায়নি, key টা সেভ করে রাখলাম — ভুল হলে পরে জানাবে",
                            true
                        )
                    }
                }
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
