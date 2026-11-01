package com.aship.jarvis

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button

/**
 * v34: Mobile hub — main screen ke "Mobile (মোবাইল) —
 * Background Setup" button se khulta hai. User apna mobile
 * chunata hai (vivo / Samsung); har company ke battery
 * bandobosto alag hote hain, isliye aage ki screen bhi
 * alag hai.
 */
class MobileHubActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mobile_hub)

        findViewById<Button>(R.id.vivoButton).setOnClickListener {
            startActivity(Intent(this, VivoSetupActivity::class.java))
        }
        findViewById<Button>(R.id.samsungButton).setOnClickListener {
            startActivity(Intent(this, SamsungSetupActivity::class.java))
        }
    }
}
