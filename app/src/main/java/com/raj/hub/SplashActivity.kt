package com.raj.hub

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/** Shows the app logo with "from Raj", then goes straight into the app (no permissions needed). */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val center = findViewById<View>(R.id.center)
        val footer = findViewById<View>(R.id.footer)
        center.alpha = 0f
        footer.alpha = 0f
        center.animate().alpha(1f).setDuration(450).start()
        footer.animate().alpha(1f).setDuration(450).setStartDelay(150).start()

        Handler(Looper.getMainLooper()).postDelayed({ route() }, 900)
    }

    private fun route() {
        if (isFinishing || isDestroyed) return
        val next = Router.next(this)
        startActivity(next)
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
