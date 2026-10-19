package com.raj.hub

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * First launch only: downloads the app's files from GitHub and saves them on the phone.
 * After that the app opens instantly and also works offline.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var store: AppStore
    private lateinit var app: AppInfo
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var retry: TextView
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        store = AppStore(this)
        app = AppRegistry.load(this).first()

        status = findViewById(R.id.status)
        bar = findViewById(R.id.progress)
        retry = findViewById(R.id.retry)
        bar.progressTintList = ColorStateList.valueOf(app.accent)
        retry.setOnClickListener { start() }
        start()
    }

    private fun start() {
        if (running) return
        if (store.isInstalled(app)) {
            launch()
            return
        }
        if (!Net.isOnline(this)) {
            showError("Internet is needed the first time you open ${app.name}.")
            return
        }
        running = true
        retry.visibility = View.GONE
        bar.visibility = View.VISIBLE
        bar.progress = 0
        status.text = "Getting ${app.name} ready…  0%"
        Bg.run {
            try {
                store.sync(app) { p ->
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            bar.progress = p
                            status.text = "Getting ${app.name} ready…  $p%"
                        }
                    }
                }
                Prefs.setLastCheck(this, app.id)
                runOnUiThread {
                    running = false
                    if (!isFinishing && !isDestroyed) launch()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    running = false
                    if (!isFinishing && !isDestroyed) showError("Download failed. Check your internet and try again.")
                }
            }
        }
    }

    private fun showError(msg: String) {
        status.text = msg
        bar.visibility = View.INVISIBLE
        retry.visibility = View.VISIBLE
    }

    private fun launch() {
        startActivity(Intent(this, AppActivity::class.java).putExtra(AppActivity.EXTRA_ID, app.id))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
