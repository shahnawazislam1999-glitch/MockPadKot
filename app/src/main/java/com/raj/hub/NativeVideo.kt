package com.raj.hub

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.GradientDrawable
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.OpenableColumns
import android.text.TextPaint
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Native video player (Media3 ExoPlayer) for the web pages in this app.
 *
 *  - MKV / MP4 / WebM etc. are played straight from the file. Nothing is converted or re-encoded,
 *    and nothing is loaded into memory: ExoPlayer reads only the parts it needs (works for multi-GB files).
 *  - The picked video is remembered: first through a persistable Android URI permission (no copy),
 *    and only if Android refuses that, through a byte-exact copy in the app's private storage.
 *  - The page keeps using its normal <video> calls; a small script in the page forwards them to [cmd]
 *    and receives player state back through window.__nvOn(...).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class NativeVideo(
    private val act: AppCompatActivity,
    private val appId: String,
    private val web: View,
    private val evalJs: (String) -> Unit
) {
    private class Source(val uri: Uri, val name: String, val size: Long, val stamp: Long, val kind: String)

    /** Add this view above the WebView (it is hidden until a video is opened). */
    val panel: FrameLayout = act.layoutInflater.inflate(R.layout.panel_native_video, null, false) as FrameLayout

    private val playerView: PlayerView = panel.findViewById(R.id.nv_player)
    private val tiles = TextTileView(act)
    private val prefs: SharedPreferences = act.getSharedPreferences("rajhub", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private var exo: ExoPlayer? = null
    private var loadedKey: String? = null
    private var hasMedia = false
    private var wantVisible = false
    private var fullscreen = false
    private var released = false
    private var muted = false

    private var lastSeq = 0
    private var seekPending = false
    private var seekDone = 0
    private var tickCount = 0
    private var copyGen = 0

    private var videoAspect = 16f / 9f
    private var insetL = 0
    private var insetT = 0
    private var insetR = 0

    private var fxMirror = false
    private var fxBlurCssPx = 0f
    private var fxOldTv = false

    // audio language switching (dual audio MKV, e.g. English / Hindi)
    private var audioList: List<Tracks.Group> = emptyList()
    private var audioPrefApplied = false
    private val audioChip = ImageView(act)

    init {
        panel.visibility = View.GONE
        panel.addView(tiles, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // only a small speaker icon, very faint (10% opacity) so it does not distract from the picture
        audioChip.setImageResource(R.drawable.ic_audio_track)
        audioChip.alpha = 0.10f
        audioChip.setPadding(dp(10), dp(10), dp(10), dp(10))
        audioChip.contentDescription = "Audio language"
        audioChip.visibility = View.GONE
        audioChip.setOnClickListener { showAudioDialog() }
        val chipLp = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END)
        chipLp.setMargins(0, dp(4), dp(4), 0)
        panel.addView(audioChip, chipLp)
        playerView.setFullscreenButtonClickListener { setFullscreen(!fullscreen) }
        playerView.subtitleView?.visibility = View.GONE
    }

    // ------------------------------------------------------------------ player

    private fun ensurePlayer(): ExoPlayer {
        exo?.let { return it }
        val renderers = DefaultRenderersFactory(act).setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 40_000, 1_000, 2_000)
            .setTargetBufferBytes(64 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            // keeps the last seconds in memory so "repeat this sentence" seeks backwards instantly
            .setBackBuffer(20_000, true)
            .build()
        val audio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val p = ExoPlayer.Builder(act, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(act, DefaultExtractorsFactory()))
            .setLoadControl(loadControl)
            .setAudioAttributes(audio, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        // The page draws its own subtitles from the .srt file; never show the ones embedded in the MKV.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        p.volume = if (muted) 0f else 1f
        p.addListener(listener)
        playerView.player = p
        exo = p
        applyFx()
        return p
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if ((playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) && seekPending) {
                seekPending = false
                seekDone++
            }
            pushState()
            updateTicker()
            if (playbackState == Player.STATE_ENDED) savePos()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            pushState()
            updateTicker()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            pushState()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            pushState()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                videoAspect = (videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height).coerceIn(0.4f, 4f)
                relayout()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            checkTracks(tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            val o = JSONObject()
            o.put("e", "err")
            o.put("code", 4)
            o.put("msg", describe(error))
            emit(o)
            pushState()
        }
    }

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            "the video file was moved, deleted, or the app lost access to it. Tap 🎬+ to pick it again"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "this phone cannot decode the video format inside this file"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
            "the file looks damaged or is not a supported video container"
        else -> "playback failed (" + e.errorCodeName + ")"
    }

    /** Warns (once per video) when the phone has no decoder for the sound or the picture. */
    private fun checkTracks(t: Tracks) {
        var audioGroups = 0
        var audioOn = false
        var videoGroups = 0
        var videoOn = false
        val audioNames = ArrayList<String>()
        val videoNames = ArrayList<String>()
        updateAudioTracks(t)
        for (g in t.groups) {
            val mime = if (g.length > 0) (g.getTrackFormat(0).sampleMimeType ?: "") else ""
            val nice = mime.substringAfter('/').uppercase().ifEmpty { "?" }
            if (g.type == C.TRACK_TYPE_AUDIO) {
                audioGroups++
                if (g.isSelected) audioOn = true
                audioNames.add(nice)
            } else if (g.type == C.TRACK_TYPE_VIDEO) {
                videoGroups++
                if (g.isSelected) videoOn = true
                videoNames.add(nice)
            }
        }
        val msg = StringBuilder()
        if (audioGroups > 0 && !audioOn) {
            msg.append("This phone cannot decode the sound in this file (" + audioNames.joinToString(", ") + "), so it plays without sound. ")
            msg.append("Re-save the audio as AAC; the video can stay untouched. ")
        }
        if (videoGroups > 0 && !videoOn) {
            msg.append("This phone cannot decode the picture in this file (" + videoNames.joinToString(", ") + "). ")
        }
        if (msg.isNotEmpty()) {
            val o = JSONObject()
            o.put("e", "warn")
            o.put("msg", msg.toString().trim())
            emit(o)
            Toast.makeText(act, msg.toString().trim(), Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ audio language

    private fun langKey(code: String): String {
        val c = code.lowercase()
        return when {
            c.startsWith("en") -> "en"
            c.startsWith("hi") -> "hi"
            else -> c.take(2)
        }
    }

    private fun audioName(g: Tracks.Group, i: Int): String {
        val f = g.getTrackFormat(0)
        val code = f.language ?: ""
        val label = f.label
        val lang = when (langKey(code)) {
            "en" -> "English"
            "hi" -> "Hindi"
            else -> if (code.isNotEmpty() && code != "und") Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifEmpty { code } else ""
        }
        return when {
            lang.isNotEmpty() && label != null && !label.contains(lang, true) -> "$lang ($label)"
            lang.isNotEmpty() -> lang
            label != null -> label
            else -> "Audio " + (i + 1)
        }
    }

    private fun updateAudioTracks(t: Tracks) {
        val all = t.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }
        val list = all.filter { it.isSupported }
        audioList = list
        if (all.size > list.size && list.isNotEmpty()) {
            Toast.makeText(act, "Another audio track in this file uses a format this phone cannot play, so it is not listed.", Toast.LENGTH_LONG).show()
        }
        if (list.size < 2) {
            audioChip.visibility = View.GONE
            return
        }
        if (!audioPrefApplied) {
            audioPrefApplied = true
            val wantLang = prefs.getString(key("aud_lang"), null)
            var idx = -1
            if (wantLang != null) idx = list.indexOfFirst { langKey(it.getTrackFormat(0).language ?: "") == wantLang }
            if (idx < 0) {
                val wantIdx = prefs.getInt(key("aud_idx"), -1)
                if (wantIdx in list.indices && wantLang == null) idx = wantIdx
            }
            if (idx >= 0 && !list[idx].isSelected) {
                selectAudio(idx, false)
                return // the player reports the tracks again after the switch
            }
        }
        val sel = list.indexOfFirst { it.isSelected }.coerceAtLeast(0)
        audioChip.visibility = View.VISIBLE
    }

    private fun selectAudio(idx: Int, remember: Boolean) {
        val p = exo ?: return
        val g = audioList.getOrNull(idx) ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
            .build()
        if (remember) {
            prefs.edit()
                .putString(key("aud_lang"), langKey(g.getTrackFormat(0).language ?: "").ifEmpty { "?" })
                .putInt(key("aud_idx"), idx)
                .apply()
        }
    }

    private fun showAudioDialog() {
        val list = audioList
        if (list.size < 2 || act.isFinishing) return
        val names = Array(list.size) { audioName(list[it], it) }
        val cur = list.indexOfFirst { it.isSelected }.coerceAtLeast(0)
        AlertDialog.Builder(act)
            .setTitle("Audio")
            .setSingleChoiceItems(names, cur) { d, which ->
                selectAudio(which, true)
                d.dismiss()
            }
            .show()
    }

    // ------------------------------------------------------------------ state -> page

    private fun emit(o: JSONObject) {
        if (released) return
        try {
            evalJs("window.__nvOn&&window.__nvOn(" + o.toString() + ")")
        } catch (e: Exception) {
            // WebView is gone
        }
    }

    private fun pushState() {
        val p = exo ?: return
        if (released) return
        val st = p.playbackState
        val o = JSONObject()
        o.put("e", "state")
        o.put("q", lastSeq)
        o.put("t", p.currentPosition.coerceAtLeast(0L))
        val d = p.duration
        o.put("d", if (d == C.TIME_UNSET) -1L else d)
        o.put("pwr", p.playWhenReady && (st == Player.STATE_READY || st == Player.STATE_BUFFERING))
        o.put("pl", p.isPlaying)
        o.put("st", st)
        o.put("r", p.playbackParameters.speed.toDouble())
        o.put("sk", seekDone)
        emit(o)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            val p = exo ?: return
            checkSeek()
            pushState()
            tickCount++
            if (tickCount % 50 == 0) savePos()
            val st = p.playbackState
            if (p.playWhenReady && (st == Player.STATE_READY || st == Player.STATE_BUFFERING)) {
                main.postDelayed(this, 100)
            }
        }
    }

    private fun updateTicker() {
        main.removeCallbacks(tick)
        val p = exo ?: return
        val st = p.playbackState
        if (p.playWhenReady && (st == Player.STATE_READY || st == Player.STATE_BUFFERING)) main.postDelayed(tick, 100)
    }

    /** Safety net: a seek inside already-buffered data may not change the player state at all. */
    private fun checkSeek() {
        val p = exo ?: return
        if (seekPending && (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_ENDED)) {
            seekPending = false
            seekDone++
        }
    }

    private fun savePos() {
        val p = exo ?: return
        if (!hasMedia || loadedKey == null) return
        val st = p.playbackState
        if (st == Player.STATE_IDLE) return
        val pos = if (st == Player.STATE_ENDED) 0L else p.currentPosition.coerceAtLeast(0L)
        prefs.edit().putLong("vid_pos_$appId", pos).apply()
    }

    // ------------------------------------------------------------------ page -> player (main thread)

    fun cmd(seq: Int, op: String, arg: Double) {
        if (released) return
        val p = exo
        if (p != null) {
            when (op) {
                "play" -> {
                    if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition()
                    if (p.playbackState == Player.STATE_IDLE && p.currentMediaItem != null) p.prepare()
                    p.play()
                }
                "pause" -> p.pause()
                "seek" -> {
                    seekPending = true
                    p.seekTo(arg.toLong().coerceAtLeast(0L))
                    main.postDelayed({ checkSeek(); pushState() }, 120)
                }
                "speed" -> p.setPlaybackSpeed(arg.toFloat().coerceIn(0.25f, 4f))
                "retry" -> if (p.playbackState == Player.STATE_IDLE && p.currentMediaItem != null) p.prepare()
            }
        }
        lastSeq = seq
        pushState()
        updateTicker()
    }

    fun setMuted(on: Boolean) {
        muted = on
        exo?.volume = if (on) 0f else 1f
    }

    fun setControls(on: Boolean) {
        playerView.useController = on
    }

    fun setVisible(on: Boolean) {
        wantVisible = on
        applyVisibility()
    }

    fun setOverlay(text: String?, sizePct: Double, opacity: Double, on: Boolean) {
        tiles.update(text ?: "", sizePct.toFloat(), opacity.toFloat(), on)
    }

    fun setFx(mirror: Boolean, blurCssPx: Double, oldTv: Boolean) {
        fxMirror = mirror
        fxBlurCssPx = blurCssPx.toFloat()
        fxOldTv = oldTv
        applyFx()
    }

    private fun applyFx() {
        val v = playerView.videoSurfaceView ?: return
        try {
            v.scaleX = if (fxMirror) -1f else 1f
            if (fxOldTv) {
                val gray = ColorMatrix()
                gray.setSaturation(0f)
                val k = 1.24f
                val o = -40f
                gray.postConcat(ColorMatrix(floatArrayOf(
                    k, 0f, 0f, 0f, o,
                    0f, k, 0f, 0f, o,
                    0f, 0f, k, 0f, o,
                    0f, 0f, 0f, 1f, 0f
                )))
                gray.postConcat(ColorMatrix(floatArrayOf(
                    1.08f, 0f, 0f, 0f, 0f,
                    0f, 1.0f, 0f, 0f, 0f,
                    0f, 0f, 0.85f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )))
                val paint = Paint()
                paint.colorFilter = ColorMatrixColorFilter(gray)
                v.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
            } else if (v is TextureView) {
                v.setLayerType(View.LAYER_TYPE_NONE, null)
            }
            if (Build.VERSION.SDK_INT >= 31) applyBlur(v, fxBlurCssPx * act.resources.displayMetrics.density)
        } catch (e: Exception) {
            // effects are optional
        }
    }

    @RequiresApi(31)
    private fun applyBlur(v: View, px: Float) {
        v.setRenderEffect(if (px > 0.5f) RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP) else null)
    }

    // ------------------------------------------------------------------ layout / fullscreen

    fun setInsets(l: Int, t: Int, r: Int) {
        if (l == insetL && t == insetT && r == insetR) return
        insetL = l
        insetT = t
        insetR = r
        relayout()
    }

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()

    /** Panel height follows the video's shape but never takes more than 40% of the screen (like the old page player). */
    fun relayout() {
        val lp = panel.layoutParams as? LinearLayout.LayoutParams ?: return
        val dm = act.resources.displayMetrics
        val newH: Int
        if (fullscreen) {
            newH = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            val availW = (dm.widthPixels - insetL - insetR).coerceAtLeast(1)
            val maxH = maxOf(dp(96), (dm.heightPixels * 0.4f).toInt())
            newH = (availW / videoAspect).toInt().coerceIn(dp(96), maxH) + insetT
        }
        panel.setPadding(insetL, insetT, insetR, 0)
        if (lp.height != newH) {
            lp.height = newH
            panel.layoutParams = lp
        }
    }

    private fun applyVisibility() {
        val show = wantVisible && hasMedia
        panel.visibility = if (show) View.VISIBLE else View.GONE
        if (!show && fullscreen) setFullscreen(false)
        if (show) relayout()
    }

    private fun setFullscreen(on: Boolean) {
        if (on == fullscreen) return
        fullscreen = on
        web.visibility = if (on) View.GONE else View.VISIBLE
        act.requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        relayout()
    }

    /** Back button: leaves full screen first. Returns true when it was used. */
    fun handleBack(): Boolean {
        if (fullscreen) {
            setFullscreen(false)
            return true
        }
        return false
    }

    // ------------------------------------------------------------------ lifecycle

    fun onPause() {
        if (released) return
        exo?.pause()
        savePos()
    }

    fun release() {
        if (released) return
        savePos()
        released = true
        copyGen++
        main.removeCallbacksAndMessages(null)
        exo?.let {
            it.removeListener(listener)
            playerView.player = null
            it.release()
        }
        exo = null
    }

    // ------------------------------------------------------------------ remembered video

    private fun key(name: String): String = name + "_" + appId

    private fun canOpen(uri: Uri): Boolean = try {
        act.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    private fun readInfo(uri: Uri): Pair<String, Long> {
        var name = "video"
        var size = -1L
        try {
            act.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (e: Exception) {
            // keep defaults
        }
        if (size < 0) {
            try {
                act.contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
            } catch (e: Exception) {
                // keep -1
            }
        }
        return Pair(name, size)
    }

    private fun privateDir(): File = File(act.filesDir, "videos/$appId")

    private fun removePrivateExcept(keep: File?) {
        try {
            privateDir().listFiles()?.forEach { f ->
                if (keep == null || f.absolutePath != keep.absolutePath) f.delete()
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    /** Where the remembered video can be read from right now, or null. Safe to call from any thread. */
    private fun resolveSource(): Source? {
        val name = prefs.getString(key("vid_name"), "video") ?: "video"
        val size = prefs.getLong(key("vid_size"), 0L)
        val stamp = prefs.getLong(key("vid_stamp"), 0L)
        val priv = prefs.getString(key("vid_priv"), null)
        if (priv != null) {
            val f = File(priv)
            if (f.isFile && f.length() > 0L) return Source(Uri.fromFile(f), name, f.length(), stamp, "private")
        }
        val u = prefs.getString(key("vid_uri"), null) ?: return null
        val uri = try { Uri.parse(u) } catch (e: Exception) { return null }
        return if (canOpen(uri)) Source(uri, name, size, stamp, "content") else null
    }

    fun canResolve(): Boolean = resolveSource() != null

    fun savedJson(): String {
        val s = resolveSource() ?: return ""
        return infoJson(s)
    }

    /** Name of a remembered video that can no longer be opened (so the page can say which one to pick again). */
    fun missingName(): String {
        if (resolveSource() != null) return ""
        if (prefs.getString(key("vid_uri"), null) == null && prefs.getString(key("vid_priv"), null) == null) return ""
        return prefs.getString(key("vid_name"), "") ?: ""
    }

    private fun infoJson(s: Source): String {
        val o = JSONObject()
        o.put("name", s.name)
        o.put("size", s.size)
        o.put("stamp", s.stamp)
        o.put("source", s.kind)
        return o.toString()
    }

    private fun notifyPage() {
        val s = resolveSource() ?: return
        evalJs("window.__onNativeVideo&&window.__onNativeVideo(" + infoJson(s) + ")")
    }

    /** Opens the remembered video in the player (position restored). Called by the page. */
    fun openSaved() {
        if (released) return
        val s = resolveSource() ?: return
        val p = ensurePlayer()
        val k = s.uri.toString() + "|" + s.stamp
        val startMs = prefs.getLong(key("vid_pos"), 0L).coerceAtLeast(0L)
        if (k != loadedKey) {
            loadedKey = k
            hasMedia = true
            seekPending = false
            audioPrefApplied = false
            audioList = emptyList()
            audioChip.visibility = View.GONE
            videoAspect = 16f / 9f
            p.setMediaItem(MediaItem.fromUri(s.uri), startMs)
            p.playWhenReady = false
            p.prepare()
        }
        val o = JSONObject()
        o.put("e", "load")
        o.put("name", s.name)
        o.put("size", s.size)
        o.put("pos", p.currentPosition.coerceAtLeast(0L))
        o.put("sk", seekDone)
        emit(o)
        applyVisibility()
        relayout()
        pushState()
    }

    /** The user picked a video with Android's file picker. */
    fun onPicked(uri: Uri) {
        val cr = act.contentResolver
        var persisted = false
        try {
            cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            persisted = cr.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        } catch (e: Exception) {
            // this provider does not allow keeping access
        }
        if (!canOpen(uri)) {
            Toast.makeText(act, "Could not open that file", Toast.LENGTH_LONG).show()
            return
        }
        val info = readInfo(uri)
        val stamp = System.currentTimeMillis()
        copyGen++
        if (persisted) {
            commit(uri, info.first, info.second, stamp, null)
            notifyPage()
        } else {
            startPrivateCopy(uri, info.first, info.second, stamp)
        }
    }

    private fun commit(uri: Uri, name: String, size: Long, stamp: Long, priv: File?) {
        // the player still holds the previous video: stop it so its position cannot overwrite the new entry
        exo?.pause()
        loadedKey = null
        val e = prefs.edit()
        e.putString(key("vid_uri"), uri.toString())
        e.putString(key("vid_name"), name)
        e.putLong(key("vid_size"), size)
        e.putLong(key("vid_stamp"), stamp)
        e.putLong(key("vid_pos"), 0L)
        if (priv != null) e.putString(key("vid_priv"), priv.absolutePath) else e.remove(key("vid_priv"))
        e.apply()
        removePrivateExcept(priv)
    }

    /** Android would not let us keep access to the file, so keep an exact copy in private storage. */
    private fun startPrivateCopy(uri: Uri, name: String, size: Long, stamp: Long) {
        val gen = copyGen
        val dir = privateDir()
        dir.mkdirs()
        if (size > 0) {
            val free = try { StatFs(act.filesDir.path).availableBytes } catch (e: Exception) { Long.MAX_VALUE }
            if (free < size + 64L * 1024 * 1024) {
                commit(uri, name, size, stamp, null)
                Toast.makeText(act, "Not enough free space to keep a copy. The video plays now but will not be remembered after you close the app.", Toast.LENGTH_LONG).show()
                notifyPage()
                return
            }
        }
        Toast.makeText(act, "Saving a private copy of the video so it is remembered…", Toast.LENGTH_LONG).show()
        val ext = name.substringAfterLast('.', "mkv").filter { it.isLetterOrDigit() }.take(5).ifEmpty { "bin" }
        Thread {
            val tmp = File(dir, "video_$stamp.part")
            val dest = File(dir, "video_$stamp.$ext")
            var ok = false
            try {
                val input = act.contentResolver.openInputStream(uri)
                if (input != null) {
                    input.use { ins ->
                        FileOutputStream(tmp).use { out ->
                            val buf = ByteArray(1 shl 20)
                            var total = 0L
                            var lastPct = -1
                            while (true) {
                                if (gen != copyGen || released) throw InterruptedException()
                                val n = ins.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                total += n.toLong()
                                if (size > 0) {
                                    val pct = (total * 100L / size).toInt().coerceIn(0, 100)
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        main.post {
                                            if (gen == copyGen && !released) {
                                                val o = JSONObject()
                                                o.put("e", "copy")
                                                o.put("pct", pct)
                                                emit(o)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    ok = tmp.length() > 0L && tmp.renameTo(dest)
                }
            } catch (t: Throwable) {
                ok = false
            }
            if (!ok) tmp.delete()
            main.post { finishCopy(gen, ok, dest, uri, name, size, stamp) }
        }.start()
    }

    private fun finishCopy(gen: Int, ok: Boolean, dest: File, uri: Uri, name: String, size: Long, stamp: Long) {
        if (gen != copyGen || released) {
            if (ok) dest.delete()
            return
        }
        if (ok) {
            commit(uri, name, dest.length(), stamp, dest)
            notifyPage()
        } else if (canOpen(uri)) {
            commit(uri, name, size, stamp, null)
            Toast.makeText(act, "Could not keep a copy. The video plays now but will not be remembered after you close the app.", Toast.LENGTH_LONG).show()
            notifyPage()
        } else {
            Toast.makeText(act, "Could not read that video file", Toast.LENGTH_LONG).show()
        }
    }

    /** Forget the remembered video completely (also deletes any private copy). */
    fun forget() {
        copyGen++
        exo?.let {
            it.stop()
            it.clearMediaItems()
        }
        loadedKey = null
        hasMedia = false
        prefs.edit()
            .remove(key("vid_uri")).remove(key("vid_name")).remove(key("vid_size"))
            .remove(key("vid_stamp")).remove(key("vid_priv")).remove(key("vid_pos"))
            .remove(key("aud_lang")).remove(key("aud_idx"))
            .apply()
        removePrivateExcept(null)
        applyVisibility()
    }
}

/**
 * Draws the page's "overlay text" on top of the native video: the text repeated in a grid that fills the picture
 * (same layout rules as the page's own overlay), with adjustable size and opacity.
 */
class TextTileView(ctx: Context) : View(ctx) {
    private var text = ""
    private var sizePct = 8f
    private var on = false
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = false
        isFocusable = false
        visibility = View.GONE
        paint.color = Color.WHITE
        paint.isFakeBoldText = true
        paint.textAlign = Paint.Align.CENTER
        paint.setShadowLayer(8f, 0f, 3f, 0xA6000000.toInt())
    }

    fun update(t: String, pct: Float, opacity: Float, enabled: Boolean) {
        text = t
        sizePct = pct.coerceIn(2f, 40f)
        on = enabled
        alpha = opacity.coerceIn(0f, 1f)
        visibility = if (on && text.isNotBlank()) View.VISIBLE else View.GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!on || text.isBlank()) return
        val pad = width * 0.03f
        val w = width - 2f * pad
        val h = height - 2f * pad
        if (w <= 0f || h <= 0f) return
        paint.textSize = maxOf(6f, h * sizePct / 100f)
        val lines = text.split("\n")
        val fm = paint.fontMetrics
        val lineH = fm.descent - fm.ascent
        var pw = 1f
        for (l in lines) pw = maxOf(pw, paint.measureText(l))
        val ph = maxOf(1f, lineH * lines.size)
        var cols = maxOf(1, (w / (pw * 1.35f)).toInt())
        var rows = maxOf(1, (h / (ph * 1.5f)).toInt())
        while (cols * rows > 1800) {
            cols = maxOf(1, (cols * 0.9f).toInt())
            rows = maxOf(1, (rows * 0.9f).toInt())
        }
        val cw = w / cols
        val ch = h / rows
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cx = pad + (c + 0.5f) * cw
                val top = pad + (r + 0.5f) * ch - ph / 2f
                for (i in lines.indices) {
                    canvas.drawText(lines[i], cx, top + i * lineH - fm.ascent, paint)
                }
            }
        }
    }
}
