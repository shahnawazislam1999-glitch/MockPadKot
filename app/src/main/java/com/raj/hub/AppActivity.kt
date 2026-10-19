package com.raj.hub

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import org.json.JSONObject

/** Runs the saved web app full screen. Press Back twice quickly to exit. */
class AppActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "app_id"

        // Lets a page save files it creates (backups, PDFs) to Downloads. Android's WebView cannot do this alone.
        private const val DOWNLOAD_HOOK_JS = """
(function () {
  if (window.__rajHook) return;
  window.__rajHook = true;
  function send(url, name) {
    fetch(url).then(function (r) { return r.blob(); }).then(function (b) {
      if (b.size > 120 * 1024 * 1024) { alert('This file is too big to save from inside the app.'); return; }
      var fr = new FileReader();
      fr.onloadend = function () {
        var s = String(fr.result);
        RajBridge.save(name || 'download', b.type || 'application/octet-stream', s.substring(s.indexOf(',') + 1));
      };
      fr.readAsDataURL(b);
    }).catch(function () { alert('Could not save the file.'); });
  }
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (this.hasAttribute('download') && /^(blob:|data:)/.test(this.href)) {
      send(this.href, this.getAttribute('download'));
      return;
    }
    return origClick.apply(this, arguments);
  };
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
    if (a && /^(blob:|data:)/.test(a.href)) {
      e.preventDefault();
      send(a.href, a.getAttribute('download'));
    }
  }, true);
})();
"""
    }

    private lateinit var store: AppStore
    private lateinit var app: AppInfo
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var loader: WebViewAssetLoader
    private lateinit var nv: NativeVideo

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var lastBack = 0L
    private var ready = false

    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val cb = fileCallback
        fileCallback = null
        cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
    }

    // ==== ANCHOR: remembered video (played by the native ExoPlayer, see NativeVideo.kt) ====
    private val videoPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        if (r.resultCode != RESULT_OK || uri == null) return@registerForActivityResult
        // keeps access with a persistable permission (no copy); falls back to a private copy only if Android refuses
        if (::nv.isInitialized) nv.onPicked(uri)
    }

    private fun videoMime(name: String): String {
        val n = name.lowercase()
        return when {
            n.endsWith(".mkv") -> "video/x-matroska"
            n.endsWith(".webm") -> "video/webm"
            n.endsWith(".mov") -> "video/quicktime"
            n.endsWith(".avi") -> "video/x-msvideo"
            else -> "video/mp4"
        }
    }

    private fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", HashMap<String, String>(), ByteArrayInputStream(ByteArray(0)))

    /** Streams the remembered video to the page with HTTP Range support so seeking works on multi-GB files. */
    private fun serveVideo(req: WebResourceRequest): WebResourceResponse {
        val sp = getSharedPreferences("rajhub", MODE_PRIVATE)
        val uriStr = sp.getString("vid_uri_" + app.id, null) ?: return notFound()
        return try {
            val pfd = contentResolver.openFileDescriptor(Uri.parse(uriStr), "r") ?: return notFound()
            var total = pfd.statSize
            if (total <= 0) total = sp.getLong("vid_size_" + app.id, 0L)
            if (total <= 0) {
                pfd.close()
                return notFound()
            }
            var start = 0L
            var end = total - 1
            var partial = false
            val range = req.requestHeaders["Range"] ?: req.requestHeaders["range"]
            if (range != null && range.startsWith("bytes=")) {
                val spec = range.substring(6).split(",")[0].trim()
                val dash = spec.indexOf('-')
                if (dash >= 0) {
                    val a = spec.substring(0, dash)
                    val b = spec.substring(dash + 1)
                    if (a.isEmpty()) {
                        val n = b.toLongOrNull() ?: 0L
                        start = maxOf(0L, total - n)
                    } else {
                        start = a.toLongOrNull() ?: 0L
                        if (b.isNotEmpty()) end = minOf(end, b.toLongOrNull() ?: end)
                    }
                    partial = true
                }
            }
            if (start < 0 || start > end || start >= total) {
                pfd.close()
                val h = HashMap<String, String>()
                h["Content-Range"] = "bytes */$total"
                return WebResourceResponse("text/plain", "UTF-8", 416, "Range Not Satisfiable", h, ByteArrayInputStream(ByteArray(0)))
            }
            val fis = FileInputStream(pfd.fileDescriptor)
            fis.channel.position(start)
            val length = end - start + 1
            val headers = HashMap<String, String>()
            headers["Accept-Ranges"] = "bytes"
            headers["Content-Length"] = length.toString()
            headers["Access-Control-Allow-Origin"] = "*"
            if (partial) headers["Content-Range"] = "bytes $start-$end/$total"
            val mime = videoMime(sp.getString("vid_name_" + app.id, "") ?: "")
            WebResourceResponse(
                mime, null,
                if (partial) 206 else 200,
                if (partial) "Partial Content" else "OK",
                headers,
                RangeStream(pfd, fis, length)
            )
        } catch (e: Exception) {
            notFound()
        }
    }

    // ==== ANCHOR: web page camera / microphone / location permission requests ====
    private var pendingWebReq: PermissionRequest? = null
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCb: GeolocationPermissions.Callback? = null

    private fun has(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private val webPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val req = pendingWebReq
            pendingWebReq = null
            if (req != null) answerWebPermission(req)
        }

    private val geoLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val cb = pendingGeoCb
            val origin = pendingGeoOrigin
            pendingGeoCb = null
            pendingGeoOrigin = null
            val ok = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)
            cb?.invoke(origin, ok, false)
        }

    /** Gives the page the camera / microphone it asked for, if Android allows it. */
    private fun answerWebPermission(req: PermissionRequest) {
        val ok = ArrayList<String>()
        for (r in req.resources) {
            when (r) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> if (has(Manifest.permission.CAMERA)) ok.add(r)
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> if (has(Manifest.permission.RECORD_AUDIO)) ok.add(r)
                else -> ok.add(r)
            }
        }
        if (ok.isEmpty()) req.deny() else req.grant(ok.toTypedArray())
    }

    private fun handleWebPermission(req: PermissionRequest) {
        val missing = ArrayList<String>()
        for (r in req.resources) {
            if (r == PermissionRequest.RESOURCE_VIDEO_CAPTURE && !has(Manifest.permission.CAMERA)) {
                missing.add(Manifest.permission.CAMERA)
            }
            if (r == PermissionRequest.RESOURCE_AUDIO_CAPTURE && !has(Manifest.permission.RECORD_AUDIO)) {
                missing.add(Manifest.permission.RECORD_AUDIO)
            }
        }
        if (missing.isEmpty()) {
            answerWebPermission(req)
            return
        }
        pendingWebReq?.deny()
        pendingWebReq = req
        webPermLauncher.launch(missing.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AppStore(this)

        val id = intent.getStringExtra(EXTRA_ID)
        val found = AppRegistry.load(this).firstOrNull { it.id == id }
        if (found == null || !store.isInstalled(found)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        app = found

        val lp = window.attributes
        lp.layoutInDisplayCutoutMode = if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.attributes = lp

        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0B1020"))
        web = WebView(this)
        // Native video panel on top (hidden until a video is opened), the web page below it.
        nv = NativeVideo(this, app.id, web) { js -> web.evaluateJavascript(js, null) }
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.addView(nv.panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // Full screen: the page draws edge to edge (under the camera notch too). Only the keyboard pushes it up.
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(0, 0, 0, ime.bottom)
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            nv.setInsets(cut.left, cut.top, cut.right)
            insets
        }

        loader = WebViewAssetLoader.Builder()
            .setDomain(app.host)
            .addPathHandler("/", WebViewAssetLoader.InternalStoragePathHandler(this, store.siteDir(app)))
            .build()

        setupWebView()
        hideSystemBars()
        ready = true

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (nv.handleBack()) return
                if (customView != null) {
                    hideCustomView()
                    return
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastBack < 2000) {
                    finish()
                } else {
                    lastBack = now
                    Toast.makeText(this@AppActivity, "Press Back again to exit", Toast.LENGTH_SHORT).show()
                }
            }
        })

        web.loadUrl("https://${app.host}/${app.entry}")
        checkForUpdate()
    }

    private fun setupWebView() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        @Suppress("DEPRECATION")
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        // Needed so the Android file picker's content:// files (big MKV, SRT) can be read by the page
        s.allowContentAccess = true
        s.useWideViewPort = true
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.textZoom = 100
        s.setGeolocationEnabled(true)
        s.loadWithOverviewMode = false
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        // Lets you debug the page from Chrome on a PC: chrome://inspect (USB debugging)
        WebView.setWebContentsDebuggingEnabled(true)

        web.setBackgroundColor(Color.parseColor("#0B1020"))
        web.addJavascriptInterface(Bridge(), "RajBridge")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val req = request ?: return null
                val u = req.url
                if (u.host == app.host && u.path == "/__video__") return serveVideo(req)
                val local = loader.shouldInterceptRequest(u)
                if (local != null) return local
                if (u.host == app.host) {
                    return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", HashMap<String, String>(), ByteArrayInputStream(ByteArray(0)))
                }
                if (req.method == "GET") {
                    val saved = store.extResponse(app, u.toString())
                    if (saved != null) return saved
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                if (u.host == app.host) return false
                return openExternal(u)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(DOWNLOAD_HOOK_JS, null)
            }

            // A very big video can make Android kill the page's render process. Without this the whole app crashes.
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                Toast.makeText(this@AppActivity, "The page ran out of memory and was restarted.", Toast.LENGTH_LONG).show()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        try { web.loadUrl("https://${app.host}/${app.entry}") } catch (e: Exception) { finish() }
                    }
                }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                if (view == null) return
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                web.visibility = View.GONE
            }

            override fun onHideCustomView() {
                hideCustomView()
            }

            override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
                if (m != null) Log.d("RajHubWeb", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                val r = result ?: return false
                if (isFinishing || isDestroyed) { r.cancel(); return true }
                AlertDialog.Builder(this@AppActivity)
                    .setMessage(message ?: "")
                    .setPositiveButton(android.R.string.ok) { _, _ -> r.confirm() }
                    .setOnCancelListener { r.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                val r = result ?: return false
                if (isFinishing || isDestroyed) { r.cancel(); return true }
                AlertDialog.Builder(this@AppActivity)
                    .setMessage(message ?: "")
                    .setPositiveButton(android.R.string.ok) { _, _ -> r.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> r.cancel() }
                    .setOnCancelListener { r.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                val r = result ?: return false
                if (isFinishing || isDestroyed) { r.cancel(); return true }
                val input = EditText(this@AppActivity)
                input.setText(defaultValue ?: "")
                input.setSelection(input.text.length)
                AlertDialog.Builder(this@AppActivity)
                    .setMessage(message ?: "")
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ -> r.confirm(input.text.toString()) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> r.cancel() }
                    .setOnCancelListener { r.cancel() }
                    .show()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                val req = request ?: return
                runOnUiThread { handleWebPermission(req) }
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                val cb = callback ?: return
                if (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    cb.invoke(origin, true, false)
                    return
                }
                pendingGeoCb?.invoke(pendingGeoOrigin, false, false)
                pendingGeoCb = cb
                pendingGeoOrigin = origin
                geoLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    val intent = try {
                        fileChooserParams.createIntent()
                    } catch (e: Exception) {
                        Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    // Never filter by type: .srt has no Android MIME type and big .mkv files are often hidden by filters.
                    intent.type = "*/*"
                    intent.removeExtra(Intent.EXTRA_MIME_TYPES)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    chooser.launch(intent)
                    true
                } catch (e: Exception) {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                    Toast.makeText(this@AppActivity, "Could not open the file picker", Toast.LENGTH_SHORT).show()
                    true
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val msg = resultMsg ?: return false
                val transport = msg.obj as? WebView.WebViewTransport ?: return false
                val temp = WebView(this@AppActivity)
                temp.webViewClient = ExternalLinkClient()
                transport.webView = temp
                msg.sendToTarget()
                return true
            }
        }
    }

    /** Links that go outside the app (WhatsApp, websites) open in the phone's own apps. */
    private inner class ExternalLinkClient : WebViewClient() {
        private var handled = false

        private fun handle(view: WebView?, uri: Uri?) {
            if (handled || uri == null) return
            val scheme = uri.scheme ?: return
            if (scheme == "about" || scheme == "blob" || scheme == "javascript") return
            handled = true
            openExternal(uri)
            view?.let { v -> v.post { v.destroy() } }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            handle(view, request?.url)
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            if (url != null) handle(view, Uri.parse(url))
        }
    }

    private fun openExternal(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false
        val ok = scheme == "http" || scheme == "https" || scheme == "mailto" || scheme == "tel" || scheme == "sms" || scheme == "geo"
        if (!ok) return false
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "No app found to open this link", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun hideCustomView() {
        val v = customView ?: return
        root.removeView(v)
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        web.visibility = View.VISIBLE
    }

    private fun hideSystemBars() {
        // ==== ANCHOR: AppActivity.hideSystemBars (full screen, no top bar) ====
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ready) hideSystemBars()
    }

    /** Quietly refreshes the saved copy from GitHub (at most every 6 hours) when online. */
    private fun checkForUpdate() {
        if (!Net.isOnline(this)) return
        val sixHours = 6L * 60 * 60 * 1000
        if (System.currentTimeMillis() - Prefs.lastCheck(this, app.id) < sixHours) return
        Bg.run {
            try {
                val changed = store.sync(app) { }
                Prefs.setLastCheck(this, app.id)
                if (changed) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            Toast.makeText(this, "${app.name} updated. It will use the new version next time you open it.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                // offline or GitHub unreachable: keep using the saved copy
            }
        }
    }

    /** Called from the page's JavaScript: save a file into Downloads/<app name>, or show a notification. */
    private inner class Bridge {
        /** Opens Android's file picker and remembers the chosen video between app launches. */
        @JavascriptInterface
        fun pickVideo() {
            runOnUiThread {
                try {
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    i.addCategory(Intent.CATEGORY_OPENABLE)
                    i.type = "*/*"
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    videoPicker.launch(i)
                } catch (e: Exception) {
                    Toast.makeText(this@AppActivity, "Could not open the file picker", Toast.LENGTH_SHORT).show()
                }
            }
        }

        /** JSON of the remembered video, or an empty string (also empty when the file can no longer be opened). */
        @JavascriptInterface
        fun getSavedVideo(): String = if (ready) nv.savedJson() else ""

        /** Name of a remembered video that can no longer be opened, so the page can tell the user which one to pick again. */
        @JavascriptInterface
        fun getMissingVideoName(): String = if (ready) nv.missingName() else ""

        @JavascriptInterface
        fun forgetVideo() {
            runOnUiThread { if (ready) nv.forget() }
            getSharedPreferences("rajhub", MODE_PRIVATE).edit()
                .remove("vid_uri_" + app.id).remove("vid_name_" + app.id)
                .remove("vid_size_" + app.id).remove("vid_stamp_" + app.id).apply()
        }

        // ---- native player (ExoPlayer) controls used by the page ----

        /** Opens the remembered video in the native player. Returns false when there is nothing usable to open. */
        @JavascriptInterface
        fun nvOpen(): Boolean {
            if (!ready) return false
            val ok = nv.canResolve()
            if (ok) runOnUiThread { nv.openSaved() }
            return ok
        }

        /** op: play | pause | seek (arg = milliseconds) | speed (arg = 1.0 normal) | retry. seq lets the page match replies. */
        @JavascriptInterface
        fun nvCmd(seq: Double, op: String?, arg: Double) {
            val o = op ?: return
            runOnUiThread { if (ready) nv.cmd(seq.toInt(), o, arg) }
        }

        @JavascriptInterface
        fun nvVisible(on: Boolean) {
            runOnUiThread { if (ready) nv.setVisible(on) }
        }

        @JavascriptInterface
        fun nvControls(on: Boolean) {
            runOnUiThread { if (ready) nv.setControls(on) }
        }

        @JavascriptInterface
        fun nvMute(on: Boolean) {
            runOnUiThread { if (ready) nv.setMuted(on) }
        }

        @JavascriptInterface
        fun nvOverlay(text: String?, sizePct: Double, opacity: Double, on: Boolean) {
            runOnUiThread { if (ready) nv.setOverlay(text, sizePct, opacity, on) }
        }

        @JavascriptInterface
        fun nvFx(mirror: Boolean, blurCssPx: Double, oldTv: Boolean) {
            runOnUiThread { if (ready) nv.setFx(mirror, blurCssPx, oldTv) }
        }

        @JavascriptInterface
        fun notify(title: String, text: String) {
            Notifier.show(this@AppActivity, title, text)
        }

        @JavascriptInterface
        fun save(name: String, mime: String, base64: String) {
            Bg.run {
                try {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "download" }
                    val values = ContentValues()
                    values.put(MediaStore.Downloads.DISPLAY_NAME, safe)
                    values.put(MediaStore.Downloads.MIME_TYPE, if (mime.isBlank()) "application/octet-stream" else mime)
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + app.name)
                    values.put(MediaStore.Downloads.IS_PENDING, 1)
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("Could not create file")
                    val out = contentResolver.openOutputStream(uri) ?: throw IOException("Could not open file")
                    out.use { it.write(bytes) }
                    val done = ContentValues()
                    done.put(MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(uri, done, null, null)
                    runOnUiThread {
                        Toast.makeText(this@AppActivity, "Saved to Downloads/${app.name}/$safe", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@AppActivity, "Could not save the file", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (ready) web.onResume()
    }

    override fun onPause() {
        if (ready) {
            nv.onPause()
            web.onPause()
        }
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (ready) nv.relayout()
    }

    override fun onDestroy() {
        if (ready) {
            nv.release()
            root.removeAllViews()
            web.destroy()
        }
        super.onDestroy()
    }
}

/** Reads at most [remaining] bytes from an already-positioned file; closes the descriptor when done. */
private class RangeStream(
    private val pfd: ParcelFileDescriptor,
    private val fis: FileInputStream,
    private var remaining: Long
) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val r = fis.read()
        if (r >= 0) remaining--
        return r
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = fis.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n.toLong()
        return n
    }

    override fun available(): Int = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()

    override fun close() {
        try { fis.close() } catch (e: Exception) { }
        try { pfd.close() } catch (e: Exception) { }
    }
}
