package com.raj.hub

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/** Shows the app logo with "from Raj", then goes to the Permission Center (first launch only) and then straight into the app. */
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
        val next = if (!Prefs.permsShown(this)) {
            // First launch: show the Permission Center once.
            Intent(this, PermissionsActivity::class.java).putExtra(PermissionsActivity.EXTRA_FIRST, true)
        } else {
            Router.next(this)
        }
        startActivity(next)
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
