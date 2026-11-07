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
 * padegi। Key ki JAANCH (validate) yahan nahi hoti —
 * sirf save/clear/status; Gemini ki jaanch main screen
 * wali jagah par pehle jaisi hi hai।
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
            val doSave = {
                ProviderKeys.setKey(this, provider, typed)
                keyInput.text.clear()
                if (provider.needsAccountId) {
                    val account = accountInput?.text.toString().trim()
                    if (account.isNotEmpty()) ProviderKeys.setAccountId(this, account)
                    accountInput?.text?.clear()
                }
                refreshStatus(provider)
                Toast.makeText(
                    this, "${provider.displayName} key সেভ হয়ে গেছে।", Toast.LENGTH_SHORT
                ).show()
            }
            // v62: Gemini key ab sirf yahin save hoti hai (main
            // screen wala save hata) — isliye v22 wali jaanch bhi
            // yahin: galat key kabhi save nahi hogi। Baaki
            // provideron ki jaanch nahi hoti (unke paas list
            // wali halki jaanch nahi hai), woh seedha save।
            if (provider == ProviderKeys.Provider.GEMINI) {
                saveButton.isEnabled = false
                GeminiBrain.validateKey(typed) { ok, code ->
                    runOnUiThread {
                        saveButton.isEnabled = true
                        if (ok) {
                            doSave()
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
            } else {
                doSave()
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
