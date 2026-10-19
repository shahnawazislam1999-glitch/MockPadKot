package com.raj.hub

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Permission Center. Shown once on first launch (then continues to the app),
 * and any time from the app icon's long-press menu (Permissions).
 */
class PermissionsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FIRST = "first_run"
    }

    private lateinit var rows: LinearLayout
    private lateinit var summary: TextView
    private lateinit var allowAll: TextView
    private lateinit var done: TextView

    private var first = false
    private var auto = false
    private var waitingSettings = false
    private var requesting: PermItem? = null
    private val tried = HashSet<String>()
    private val denied = HashSet<String>()

    private val runtimeLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val item = requesting
            requesting = null
            if (item != null) {
                if (item.granted(this)) denied.remove(item.id) else denied.add(item.id)
            }
            refresh()
            if (auto) {
                runAuto()
            } else if (item != null && !item.granted(this)) {
                Toast.makeText(this, "Not allowed. Tap Allow again to open Settings.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)
        first = intent.getBooleanExtra(EXTRA_FIRST, false)

        rows = findViewById(R.id.rows)
        summary = findViewById(R.id.summary)
        allowAll = findViewById(R.id.allowAll)
        done = findViewById(R.id.done)

        done.text = if (first) "Continue" else "Done"
        allowAll.setOnClickListener { startAuto() }
        done.setOnClickListener { finishUp() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishUp()
            }
        })

        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (waitingSettings) {
            waitingSettings = false
            refresh()
            if (auto) runAuto()
        } else {
            refresh()
        }
    }

    // ==== ANCHOR: PermissionsActivity.refresh (draws the rows) ====
    private fun refresh() {
        rows.removeAllViews()
        var ok = 0
        for (item in Perms.all) {
            val v = layoutInflater.inflate(R.layout.item_perm, rows, false)
            v.findViewById<TextView>(R.id.pTitle).text = item.title
            v.findViewById<TextView>(R.id.pDesc).text = item.desc
            val state = v.findViewById<TextView>(R.id.pState)
            val btn = v.findViewById<TextView>(R.id.pBtn)
            if (item.granted(this)) {
                ok++
                state.text = "●  Allowed"
                state.setTextColor(getColor(R.color.green))
                btn.visibility = View.GONE
            } else {
                state.text = "●  Not allowed"
                state.setTextColor(getColor(R.color.amber))
                btn.visibility = View.VISIBLE
                btn.setOnClickListener { request(item) }
            }
            rows.addView(v)
        }
        summary.text = "$ok of ${Perms.all.size} allowed"
        allowAll.visibility = if (ok == Perms.all.size) View.GONE else View.VISIBLE
    }

    // ==== ANCHOR: PermissionsActivity.request (one permission) ====
    private fun request(item: PermItem) {
        val list = item.runtimeList()
        if (list.isNotEmpty() && !denied.contains(item.id)) {
            requesting = item
            runtimeLauncher.launch(list)
            return
        }
        openSettings(item)
    }

    private fun openSettings(item: PermItem) {
        val tries = ArrayList<Intent>()
        item.settings?.invoke(this)?.let { tries.addAll(it) }
        tries.add(Perms.appDetails(this))
        for (i in tries) {
            try {
                waitingSettings = true
                startActivity(i)
                return
            } catch (e: Exception) {
                // try the next screen
            }
        }
        waitingSettings = false
        if (auto) runAuto()
    }

    // ==== ANCHOR: PermissionsActivity.auto (Allow everything walkthrough) ====
    private fun startAuto() {
        auto = true
        tried.clear()
        denied.clear()
        runAuto()
    }

    private fun runAuto() {
        val next = Perms.all.firstOrNull { !it.granted(this) && !tried.contains(it.id) }
        if (next == null) {
            auto = false
            refresh()
            val left = Perms.missingCount(this)
            val msg = if (left == 0) "All permissions allowed" else "$left permission(s) still off. You can allow them one by one."
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }
        tried.add(next.id)
        val list = next.runtimeList()
        if (list.isNotEmpty()) {
            requesting = next
            runtimeLauncher.launch(list)
        } else {
            openSettings(next)
        }
    }

    private fun finishUp() {
        Prefs.setPermsShown(this)
        if (first) {
            startActivity(Router.next(this))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        finish()
    }
}
