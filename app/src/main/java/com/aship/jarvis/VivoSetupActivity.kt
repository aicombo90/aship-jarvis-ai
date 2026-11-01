package com.aship.jarvis

import android.os.Bundle

/**
 * v34: vivo setup screen — v33 tak main screen par bikhre
 * hue saare background controls (wake word, vivo battery
 * bandobosto, accessibility, overlay) ab yahan hain, bilkul
 * wohi vyavhaar ke saath (saajha aadhaar: WakeSetupActivity).
 */
class VivoSetupActivity : WakeSetupActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vivo_setup)
        initSetup()
        bindSetupControls()
        refreshSetupUi()
    }
}
