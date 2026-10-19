package com.raj.hub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.webkit.WebResourceResponse
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One web app that can be opened from the lobby. Defined in assets/apps.json. */
data class AppInfo(
    val id: String,
    val name: String,
    val description: String,
    val baseUrl: String,
    val entry: String,
    val files: List<String>,
    val externals: List<String>,
    val accent: Int
) {
    /** Every app gets its own private web address, so their saved data never mixes. */
    val host: String get() = "$id.rajhub.local"
}

object Bg {
    private val pool: ExecutorService = Executors.newFixedThreadPool(2)
    fun run(job: () -> Unit) {
        pool.execute { job() }
    }
}

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("rajhub", Context.MODE_PRIVATE)

    fun lastApp(c: Context): String? = p(c).getString("last_app", null)

    fun setLastApp(c: Context, id: String?) {
        val e = p(c).edit()
        if (id == null) e.remove("last_app") else e.putString("last_app", id)
        e.apply()
    }

    // ==== ANCHOR: Prefs favorites / pins / first-run flag ====
    private fun idSet(c: Context, key: String): Set<String> =
        p(c).getStringSet(key, emptySet())?.toSet() ?: emptySet()

    private fun toggle(c: Context, key: String, id: String): Boolean {
        val s = idSet(c, key).toMutableSet()
        val nowOn = if (s.contains(id)) { s.remove(id); false } else { s.add(id); true }
        p(c).edit().putStringSet(key, s).apply()
        return nowOn
    }

    fun favorites(c: Context): Set<String> = idSet(c, "favs")
    fun pins(c: Context): Set<String> = idSet(c, "pins")

    /** Returns true when the app is now a favorite. */
    fun toggleFavorite(c: Context, id: String): Boolean = toggle(c, "favs", id)

    /** Returns true when the app is now pinned to the top. */
    fun togglePin(c: Context, id: String): Boolean = toggle(c, "pins", id)

    fun permsShown(c: Context): Boolean = p(c).getBoolean("perms_shown", false)
    fun setPermsShown(c: Context) { p(c).edit().putBoolean("perms_shown", true).apply() }

    fun lastCheck(c: Context, id: String): Long = p(c).getLong("chk_$id", 0L)

    fun setLastCheck(c: Context, id: String) {
        p(c).edit().putLong("chk_$id", System.currentTimeMillis()).apply()
    }
}

object Net {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Cache-Control", "no-cache")
        return c
    }

    /** Downloads [url] into [dest]. Returns the content type the server reported (may be null). */
    fun download(url: String, dest: File): String? {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} for $url")
            c.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            return c.contentType
        } finally {
            c.disconnect()
        }
    }

    fun getText(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

/** Decides where the app goes after the splash / permission screen: straight into the app, or the first-time download. */
object Router {
    fun next(ctx: Context): Intent {
        val app = AppRegistry.load(ctx).first()
        return if (AppStore(ctx).isInstalled(app)) {
            Intent(ctx, AppActivity::class.java).putExtra(AppActivity.EXTRA_ID, app.id)
        } else {
            Intent(ctx, SetupActivity::class.java)
        }
    }
}

/** Lets a web app show an Android notification (page calls RajBridge.notify(title, text)). */
object Notifier {
    private const val CHANNEL = "app_general"

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.app_name), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    fun show(ctx: Context, title: String, text: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        ensureChannel(ctx)
        val open = Intent(ctx, SplashActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
        } catch (e: SecurityException) {
            // notification permission was revoked
        }
    }
}

object AppRegistry {
    private fun readAsset(ctx: Context): String =
        ctx.assets.open("apps.json").bufferedReader().use { it.readText() }

    private fun remoteFile(ctx: Context) = File(ctx.filesDir, "apps_remote.json")

    fun load(ctx: Context): List<AppInfo> {
        val map = LinkedHashMap<String, AppInfo>()
        for (a in parse(readAsset(ctx))) map[a.id] = a
        val rf = remoteFile(ctx)
        if (rf.isFile) {
            try {
                for (a in parse(rf.readText())) map[a.id] = a
            } catch (e: Exception) {
                // Ignore a broken cached list and keep the built-in apps.
            }
        }
        return map.values.toList()
    }

    fun remoteUrl(ctx: Context): String =
        try {
            JSONObject(readAsset(ctx)).optString("remote", "").trim()
        } catch (e: Exception) {
            ""
        }

    /** Fetches the online app list (if one is configured) and caches it. Returns true when updated. */
    fun refreshRemote(ctx: Context): Boolean {
        val url = remoteUrl(ctx)
        if (url.isEmpty()) return false
        val text = Net.getText(url)
        parse(text) // throws if invalid, so a bad file is never saved
        val rf = remoteFile(ctx)
        val old = if (rf.isFile) rf.readText() else ""
        if (old == text) return false
        rf.writeText(text)
        return true
    }

    private fun parse(text: String): List<AppInfo> {
        val arr = JSONObject(text).getJSONArray("apps")
        val out = ArrayList<AppInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("id").lowercase().filter { it.isLetterOrDigit() }
            if (id.isEmpty()) continue
            var base = o.getString("url")
            if (!base.endsWith("/")) base += "/"
            val entry = o.optString("entry", "index.html")
            val files = ArrayList<String>()
            val fa = o.optJSONArray("files")
            if (fa != null) for (j in 0 until fa.length()) files.add(fa.getString(j))
            if (!files.contains(entry)) files.add(0, entry)
            val ext = ArrayList<String>()
            val ea = o.optJSONArray("externals")
            if (ea != null) for (j in 0 until ea.length()) ext.add(ea.getString(j))
            val accent = try {
                Color.parseColor(o.optString("accent", "#4F8CFF"))
            } catch (e: Exception) {
                Color.parseColor("#4F8CFF")
            }
            out.add(
                AppInfo(
                    id = id,
                    name = o.optString("name", id),
                    description = o.optString("description", ""),
                    baseUrl = base,
                    entry = entry,
                    files = files,
                    externals = ext,
                    accent = accent
                )
            )
        }
        return out
    }
}

/** Saves each web app on the phone and serves it back for offline use. */
class AppStore(private val ctx: Context) {

    fun siteDir(app: AppInfo) = File(ctx.filesDir, "apps/${app.id}")
    private fun extDir(app: AppInfo) = File(ctx.filesDir, "ext/${app.id}")

    fun isInstalled(app: AppInfo): Boolean = File(siteDir(app), app.entry).isFile

    fun sizeBytes(app: AppInfo): Long {
        var total = 0L
        for (d in listOf(siteDir(app), extDir(app))) {
            if (d.exists()) {
                total += d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
        }
        return total
    }

    fun remove(app: AppInfo) {
        siteDir(app).deleteRecursively()
        extDir(app).deleteRecursively()
    }

    /** Offline copy of an outside file (font, library) that the app asked for, if we saved one. */
    fun extResponse(app: AppInfo, url: String): WebResourceResponse? {
        if (app.externals.isEmpty()) return null
        val key = sha1(url)
        val f = File(extDir(app), key)
        if (!f.isFile) return null
        val tf = File(extDir(app), "$key.type")
        val type = if (tf.isFile) tf.readText() else "application/octet-stream"
        val textual = type.startsWith("text/") || type.contains("javascript")
        val headers = HashMap<String, String>()
        headers["Access-Control-Allow-Origin"] = "*"
        return WebResourceResponse(type, if (textual) "UTF-8" else null, 200, "OK", headers, FileInputStream(f))
    }

    /**
     * Downloads the latest files of [app] from GitHub and saves them.
     * Returns true if anything changed. Throws if the main files could not be downloaded.
     */
    fun sync(app: AppInfo, onProgress: (Int) -> Unit): Boolean {
        val site = siteDir(app)
        val stage = File(ctx.filesDir, "apps/${app.id}.stage")
        val ext = extDir(app)
        val extStage = File(ctx.filesDir, "ext/${app.id}.stage")
        stage.deleteRecursively()
        extStage.deleteRecursively()
        stage.mkdirs()
        extStage.mkdirs()
        ext.mkdirs()
        site.mkdirs()

        var total = app.files.size + app.externals.size
        var done = 0
        try {
            onProgress(0)
            for (f in app.files) {
                val dest = File(stage, f)
                dest.parentFile?.mkdirs()
                Net.download(app.baseUrl + f, dest)
                done++
                onProgress(minOf(99, done * 100 / total))
            }

            // Outside files (fonts, libraries). These are best effort: a failure here never blocks the app.
            val queue = java.util.LinkedList<String>()
            val seen = HashSet<String>()
            for (u in app.externals) {
                queue.add(u)
                seen.add(u)
            }
            val urlRegex = Regex("url\\((https://[^)\\s]+)\\)")
            while (true) {
                val u = queue.poll() ?: break
                try {
                    val key = sha1(u)
                    val dest = File(extStage, key)
                    val ct = Net.download(u, dest)
                    File(extStage, "$key.type").writeText(mimeFor(u, ct))
                    if (u.contains("fonts.googleapis.com")) {
                        for (m in urlRegex.findAll(dest.readText())) {
                            val fu = m.groupValues[1]
                            if (seen.add(fu)) {
                                queue.add(fu)
                                total++
                            }
                        }
                    }
                } catch (e: Exception) {
                    // keep going
                }
                done++
                onProgress(minOf(99, done * 100 / total))
            }

            // Everything downloaded: now swap into place (entry page last).
            var changed = false
            val ordered = app.files.filter { it != app.entry } + app.entry
            for (f in ordered) {
                val s = File(stage, f)
                val d = File(site, f)
                if (d.isFile && s.length() == d.length() && s.readBytes().contentEquals(d.readBytes())) {
                    s.delete()
                    continue
                }
                d.parentFile?.mkdirs()
                if (!s.renameTo(d)) {
                    s.copyTo(d, overwrite = true)
                    s.delete()
                }
                changed = true
            }
            extStage.listFiles()?.forEach { s ->
                val d = File(ext, s.name)
                if (!s.renameTo(d)) {
                    s.copyTo(d, overwrite = true)
                    s.delete()
                }
            }
            onProgress(100)
            return changed
        } finally {
            stage.deleteRecursively()
            extStage.deleteRecursively()
        }
    }

    private fun mimeFor(url: String, serverType: String?): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            url.contains("fonts.googleapis.com") -> "text/css"
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".woff") -> "font/woff"
            path.endsWith(".ttf") -> "font/ttf"
            path.endsWith(".json") -> "application/json"
            serverType != null -> serverType.substringBefore(';').trim()
            else -> "application/octet-stream"
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
