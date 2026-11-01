package com.aship.jarvis

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast

/**
 * v34: Samsung setup screen — upar wohi saajhe controls jo
 * vivo screen par hain (wake word, accessibility, overlay;
 * saajha aadhaar: WakeSetupActivity), neeche Samsung (One UI)
 * ke khaas battery bandobosto ke dhap: Battery Unrestricted,
 * Never sleeping apps, optimisation se chhutti, aur
 * Recents me app atka kar rakhna (sirf likha hua dhap).
 */
class SamsungSetupActivity : WakeSetupActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_samsung_setup)
        initSetup()
        bindSetupControls()

        findViewById<Button>(R.id.samsungBatteryButton).setOnClickListener {
            openAppInfo()
        }
        findViewById<Button>(R.id.samsungNeverSleepingButton).setOnClickListener {
            openPowerUsageSummary()
        }
        findViewById<Button>(R.id.samsungOptimizeButton).setOnClickListener {
            openBatteryOptimization()
        }
        refreshSetupUi()
    }

    /** Dhap 1: is app ki App info — wahan Battery → Unrestricted. */
    private fun openAppInfo() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(
                    this,
                    "Settings > Apps > ASHIP JARVIS AI > Battery > Unrestricted chunein.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Dhap 2: battery wali mukhya screen kholo — wahan se
     * Background usage limits → Never sleeping apps tak jaana
     * hai. Kuch One UI par ye seedha na khule to aam Settings.
     * (Note: is screen ka action Settings class me public
     * constant nahi hai — isliye seedha action string;
     * na mile to catch me aam Settings khulega.)
     */
    private fun openPowerUsageSummary() {
        try {
            startActivity(Intent("android.settings.POWER_USAGE_SUMMARY"))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(
                    this,
                    "Settings > Battery > Background usage limits > " +
                        "Never sleeping apps > ASHIP JARVIS AI jodein.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Dhap 3: optimisation se chhutti — pehle seedha is app
     * ke liye Allow wala raasta (vivo wale button jaisa hi);
     * woh na khule to saari apps ki list kholo.
     */
    private fun openBatteryOptimization() {
        if (isBatteryOptimizationIgnored()) {
            Toast.makeText(
                this,
                "Battery bandobosto pehle se ho gaya hai — Jarvis whitelist me hai.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(
                    this,
                    "Settings > Apps > ASHIP JARVIS AI > Battery > Unrestricted chunein.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
