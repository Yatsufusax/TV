package com.mistv.canales

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.util.VLCVideoLayout
import org.videolan.libvlc.MediaPlayer as VlcPlayer

class MainActivity : Activity() {

    private lateinit var repo: ChannelRepository
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var vlcLayout: VLCVideoLayout

    // Motor VLC de respaldo (se crea solo si hace falta)
    private var libVlc: LibVLC? = null
    private var vlcPlayer: VlcPlayer? = null
    private var vlcAttached = false
    private var usingVlc = false
    private var audioApplied = false // ya se aplicó la pista de audio guardada para este canal
    private lateinit var osd: View
    private lateinit var osdNumber: TextView
    private lateinit var osdName: TextView
    private lateinit var osdGroup: TextView
    private lateinit var osdLogo: ImageView
    private lateinit var osdNow: TextView
    private lateinit var osdNext: TextView
    private lateinit var osdProgress: ProgressBar
    private lateinit var digitsView: TextView
    private lateinit var messageView: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: ChannelAdapter

    private val ui = Handler(Looper.getMainLooper())

    // ── Gestos táctiles (para celular): toque = lista, deslizar = cambiar canal, mantener = menú
    private lateinit var gestures: GestureDetector

    private var channels: List<Channel> = emptyList()
    private var shown: List<Channel> = emptyList() // canales que se muestran en la lista (puede ocultar caídos)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var index = 0
    private var prevIndex = -1
    private var digits = ""
    private var panelVisible = false
    private var lastBack = 0L
    private var stopped = false

    // Intentos de reproducción por canal: true = HLS, false = automático (TS directo, etc.)
    private var modes: List<Boolean> = listOf(true)
    private var attempt = 0

    private val hideOsd = Runnable { osd.visibility = View.GONE }
    private val hideMessageRunnable = Runnable { messageView.visibility = View.GONE }
    private val commitRunnable = Runnable { commitDigits() }
    private val retryPlayback = Runnable {
        attempt = 0
        startPlayback()
    }
    private val retryUpdate = Runnable { updateNow(false) }
    private val epgUpdate = object : Runnable {
        override fun run() {
            updateEpg(false)
            ui.postDelayed(this, 12L * 60 * 60 * 1000)
        }
    }
    private val autoUpdate = object : Runnable {
        override fun run() {
            updateNow(false)
            ui.postDelayed(this, Config.AUTO_UPDATE_MS)
        }
    }

    // ───────────────────────── Ciclo de vida ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        volumeControlStream = AudioManager.STREAM_MUSIC // botones de volumen = volumen multimedia

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (panelVisible) hideList() else showList()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!panelVisible) showMenu()
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || panelVisible) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 120) {
                    if (dy < 0) step(+1) else step(-1) // deslizar arriba = canal siguiente
                    return true
                }
                return false
            }
        })

        playerView = findViewById(R.id.player)
        vlcLayout = findViewById(R.id.vlcLayout)
        osd = findViewById(R.id.osd)
        osdNumber = findViewById(R.id.osdNumber)
        osdName = findViewById(R.id.osdName)
        osdGroup = findViewById(R.id.osdGroup)
        osdLogo = findViewById(R.id.osdLogo)
        osdNow = findViewById(R.id.osdNow)
        osdNext = findViewById(R.id.osdNext)
        osdProgress = findViewById(R.id.osdProgress)
        digitsView = findViewById(R.id.digits)
        messageView = findViewById(R.id.message)
        listView = findViewById(R.id.channelList)

        repo = ChannelRepository(this)
        channels = repo.loadLocal()
        shown = channels
        LogoLoader.init(this)
        ChannelChecker.load(repo)

        adapter = ChannelAdapter()
        listView.adapter = adapter
        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            val picked = shown.getOrNull(pos)
            if (picked != null) {
                hideList()
                tune(channels.indexOfFirst { it === picked })
            }
        }

        // Si el decodificador de video principal falla, prueba con otro (software) antes de rendirse.
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderers).build()
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onPlaybackError()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    ui.removeCallbacks(retryPlayback)
                    hideMessage()
                    checkVideoTrack()
                    applySavedAudioExo()
                }
            }
        })
        // Si el canal trae varias pistas de audio, preferir español.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages("es", "spa")
            .build()
        playerView.player = player

        val last = repo.lastName
        index = channels.indexOfFirst { it.name == last }.let { if (it >= 0) it else 0 }
        tune(index)

        ui.postDelayed(autoUpdate, 1500)
        ui.postDelayed(epgUpdate, 3000)
        ui.postDelayed({ AppUpdater.check(this, false) }, 5000)
    }

    override fun onStart() {
        super.onStart()
        if (stopped) {
            stopped = false
            if (channels.isNotEmpty()) startPlayback()
        }
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        player.stop()
        stopVlc()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        player.release()
        stopVlc()
        vlcPlayer?.release()
        libVlc?.release()
        super.onDestroy()
    }

    // ───────────────────────── Reproducción ─────────────────────────

    private fun looksHls(url: String) = url.contains(".m3u8", ignoreCase = true)

    private fun tune(i: Int) {
        if (channels.isEmpty()) return
        val idx = i.coerceIn(0, channels.size - 1)
        if (idx != index) prevIndex = index
        index = idx
        val ch = channels[idx]
        repo.lastName = ch.name
        ui.removeCallbacks(retryPlayback)
        hideMessage()
        attempt = 0
        modes = if (looksHls(ch.url)) listOf(true, false) else listOf(false, true)
        startPlayback()
        showOsd(ch)
        refreshShown()
    }

    private fun startPlayback() {
        val ch = channels.getOrNull(index) ?: return
        audioApplied = false
        if (repo.needsVlc(ch.url)) {
            startVlc(ch)
            return
        }
        stopVlc()
        val ua = ch.userAgent
            ?: "Mozilla/5.0 (Linux; Android 11; Google TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        val headers = HashMap<String, String>()
        ch.referer?.let { headers["Referer"] = it }

        val dsf = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(15000)
            .setDefaultRequestProperties(headers)

        val source = if (modes[attempt]) {
            HlsMediaSource.Factory(dsf)
                .setAllowChunklessPreparation(true)
                .createMediaSource(
                    MediaItem.Builder()
                        .setUri(ch.url)
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()
                )
        } else {
            ProgressiveMediaSource.Factory(dsf)
                .createMediaSource(MediaItem.fromUri(ch.url))
        }
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    // Si el equipo no puede decodificar el video o el audio del canal, pasamos al motor VLC.
    private fun checkVideoTrack() {
        if (usingVlc) return
        val groups = player.currentTracks.groups
        val video = groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        val audio = groups.filter { it.type == C.TRACK_TYPE_AUDIO }

        val videoBroken = video.isNotEmpty() && video.none { it.isSelected }
        val audioBroken = audio.isNotEmpty() && audio.none { it.isSelected }

        if (videoBroken || audioBroken) {
            val ch = channels.getOrNull(index) ?: return
            repo.setNeedsVlc(ch.url, true)
            startVlc(ch)
        } else if (audio.isNotEmpty() && video.isEmpty()) {
            showMessage("Este canal no trae video (solo audio)", 8000)
        }
    }

    // ───────────────────────── Motor VLC (respaldo) ─────────────────────────

    private fun startVlc(ch: Channel) {
        audioApplied = false
        try {
            player.stop()
            ui.removeCallbacks(retryPlayback)
            val lib = libVlc ?: LibVLC(this, arrayListOf("--network-caching=2000")).also { libVlc = it }
            val vp = vlcPlayer ?: VlcPlayer(lib).also { p ->
                p.setEventListener { ev ->
                    when (ev.type) {
                        VlcPlayer.Event.Playing -> runOnUiThread {
                            hideMessage()
                            ui.postDelayed({ applySavedAudioVlc() }, 1500)
                        }
                        VlcPlayer.Event.EncounteredError -> runOnUiThread {
                            showMessage("Sin señal: ${channels.getOrNull(index)?.name ?: ""}", 0)
                        }
                    }
                }
                vlcPlayer = p
            }
            if (vlcAttached) vp.detachViews()
            vp.attachViews(vlcLayout, null, false, false)
            vlcAttached = true
            vlcLayout.visibility = View.VISIBLE
            playerView.visibility = View.INVISIBLE
            usingVlc = true

            val ua = ch.userAgent
                ?: "Mozilla/5.0 (Linux; Android 11; Google TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
            val media = Media(lib, Uri.parse(ch.url))
            media.addOption(":http-user-agent=$ua")
            media.addOption(":audio-language=spa,es") // preferir audio en español
            ch.referer?.let { media.addOption(":http-referrer=$it") }
            vp.media = media
            media.release()
            vp.play()
        } catch (ex: Throwable) {
            showMessage("No se pudo abrir con VLC: ${ex.message}", 8000)
        }
    }

    private fun stopVlc() {
        if (!usingVlc && !vlcAttached) return
        try {
            vlcPlayer?.stop()
            if (vlcAttached) vlcPlayer?.detachViews()
        } catch (_: Throwable) {
        }
        vlcAttached = false
        usingVlc = false
        vlcLayout.visibility = View.GONE
        playerView.visibility = View.VISIBLE
    }

    private fun onPlaybackError() {
        if (attempt < modes.size - 1) {
            attempt++
            startPlayback()
            return
        }
        val ch = channels.getOrNull(index) ?: return
        showMessage("Sin señal: ${ch.name}\nProbá con otro canal", 0)
        ui.removeCallbacks(retryPlayback)
        ui.postDelayed(retryPlayback, 10_000)
    }

    // ───────────────────────── Pantalla ─────────────────────────

    private fun showOsd(ch: Channel) {
        osdNumber.text = ch.number.toString()
        osdName.text = ch.name
        osdGroup.text = ch.group
        LogoLoader.load(osdLogo, ch.logo)
        osdLogo.visibility = if (ch.logo.isNullOrBlank()) View.GONE else View.VISIBLE
        val (now, next) = Epg.nowNext(ch)
        if (now != null) {
            osdNow.text = "Ahora:  ${now.title}   (${fmt(now.start)} – ${fmt(now.stop)})"
            osdNow.visibility = View.VISIBLE
            val total = (now.stop - now.start).coerceAtLeast(1L)
            osdProgress.progress = (((System.currentTimeMillis() - now.start) * 100) / total).toInt().coerceIn(0, 100)
            osdProgress.visibility = View.VISIBLE
        } else {
            osdNow.visibility = View.GONE
            osdProgress.visibility = View.GONE
        }
        if (next != null) {
            osdNext.text = "Después:  ${next.title}   (${fmt(next.start)})"
            osdNext.visibility = View.VISIBLE
        } else {
            osdNext.visibility = View.GONE
        }
        osd.visibility = View.VISIBLE
        ui.removeCallbacks(hideOsd)
        ui.postDelayed(hideOsd, Config.OSD_MS)
    }

    private fun fmt(t: Long): String = timeFmt.format(Date(t))

    private fun showMessage(text: String, autoHideMs: Long) {
        messageView.text = text
        messageView.visibility = View.VISIBLE
        ui.removeCallbacks(hideMessageRunnable)
        if (autoHideMs > 0) ui.postDelayed(hideMessageRunnable, autoHideMs)
    }

    private fun hideMessage() {
        ui.removeCallbacks(hideMessageRunnable)
        messageView.visibility = View.GONE
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun showList() {
        refreshShown()
        listView.visibility = View.VISIBLE
        panelVisible = true
        listView.requestFocus()
        listView.post {
            val cur = channels.getOrNull(index)
            listView.setSelection(shown.indexOfFirst { it === cur }.coerceAtLeast(0))
        }
    }

    private fun isDead(ch: Channel) = ChannelChecker.isDead(ch.url)

    /** Recalcula qué canales se ven en la lista (si está activado, oculta los caídos). */
    private fun refreshShown() {
        val cur = channels.getOrNull(index)
        shown = if (repo.hideDead) channels.filter { !isDead(it) || it === cur } else channels
        adapter.notifyDataSetChanged()
    }

    private fun hideList() {
        listView.visibility = View.GONE
        panelVisible = false
    }

    // ───────────────────────── Números ─────────────────────────

    private fun updateDigitsView() {
        if (digits.isEmpty()) {
            digitsView.visibility = View.GONE
            return
        }
        val n = digits.toIntOrNull() ?: 0
        val ch = channels.firstOrNull { it.number == n }
        digitsView.text = if (ch != null) "$digits  ${ch.name}" else digits
        digitsView.visibility = View.VISIBLE
    }

    private fun onDigit(d: Int) {
        if (digits.length >= 4) digits = ""
        digits += d.toString()
        updateDigitsView()
        ui.removeCallbacks(commitRunnable)
        val n = digits.toLong()
        val max = channels.maxOfOrNull { it.number } ?: 0
        // Si ya no se puede agregar otro dígito, se confirma enseguida.
        if (n * 10 > max) commitDigits() else ui.postDelayed(commitRunnable, Config.DIGIT_COMMIT_MS)
    }

    private fun commitDigits() {
        ui.removeCallbacks(commitRunnable)
        val n = digits.toIntOrNull()
        digits = ""
        updateDigitsView()
        if (n == null) return
        if (n == 0) {
            if (prevIndex in channels.indices) tune(prevIndex)
            return
        }
        val i = channels.indexOfFirst { it.number == n }
        if (i >= 0) tune(i) else showMessage("El canal $n no existe", 2000)
    }

    // ───────────────────────── Control remoto ─────────────────────────

    private fun step(delta: Int) {
        if (channels.isEmpty()) return
        var i = index
        for (k in 0 until channels.size) {
            i = (i + delta + channels.size) % channels.size
            if (!(repo.hideDead && isDead(channels[i]))) break // salta los caídos si están ocultos
        }
        tune(i)
    }

    private fun isNumberKey(code: Int) =
        code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
            code in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9

    private fun digitOf(code: Int) =
        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) code - KeyEvent.KEYCODE_0
        else code - KeyEvent.KEYCODE_NUMPAD_0

    private fun isMyKey(code: Int): Boolean = isNumberKey(code) || when (code) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS,
        KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE,
        KeyEvent.KEYCODE_LAST_CHANNEL, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> true
        else -> false
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Si la lista está abierta y se toca sobre ella, la lista maneja el toque (scroll / elegir canal).
        val onList = panelVisible && ev.x <= listView.width
        if (!onList) {
            gestures.onTouchEvent(ev)
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode

        if (panelVisible) {
            // Con la lista abierta, la lista maneja las flechas y OK.
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (e.action == KeyEvent.ACTION_DOWN) hideList()
                return true
            }
            return super.dispatchKeyEvent(e)
        }

        if (!isMyKey(code)) return super.dispatchKeyEvent(e) // volumen, mute, etc. → el sistema

        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) onRemoteKey(code)
        return true
    }

    private fun onRemoteKey(code: Int) {
        if (isNumberKey(code)) {
            onDigit(digitOf(code))
            return
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> step(+1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> step(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (digits.isNotEmpty()) commitDigits() else showList()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_GUIDE -> showList()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> showMenu()
            KeyEvent.KEYCODE_INFO -> channels.getOrNull(index)?.let { showOsd(it) }
            KeyEvent.KEYCODE_LAST_CHANNEL -> if (prevIndex in channels.indices) tune(prevIndex)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                if (usingVlc) {
                    vlcPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
                } else {
                    player.playWhenReady = !player.playWhenReady
                }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            panelVisible -> hideList()
            digits.isNotEmpty() -> {
                digits = ""
                updateDigitsView()
            }
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBack < 2000) {
                    super.onBackPressed()
                } else {
                    lastBack = now
                    Toast.makeText(this, "Presioná Atrás otra vez para salir", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ───────────────────────── Menú y actualización ─────────────────────────

    private fun showMenu() {
        val items = arrayOf(
            "Actualizar canales ahora",
            "Cambiar dirección de la lista (GitHub)",
            "Cambiar pista de audio (idioma)",
            "Probar canales (detectar caídos)",
            "Ocultar canales caídos:  " + (if (repo.hideDead) "SÍ" else "NO"),
            "Guía de programación (EPG)",
            "Guía: diagnóstico (canal actual)",
            "Buscar actualización de la app",
            "Cerrar"
        )
        AlertDialog.Builder(this)
            .setTitle("Menú  ·  ${channels.size} canales  ·  v${AppUpdater.currentVersion(this)}")
            .setItems(items) { d, which ->
                d.dismiss()
                when (which) {
                    0 -> updateNow(true)
                    1 -> askUrl()
                    2 -> showAudioMenu()
                    3 -> testChannels()
                    4 -> {
                        repo.hideDead = !repo.hideDead
                        refreshShown()
                        toast(if (repo.hideDead) "Canales caídos ocultos" else "Canales caídos visibles")
                    }
                    5 -> askEpg()
                    6 -> AlertDialog.Builder(this)
                        .setTitle("Diagnóstico de la guía")
                        .setMessage(Epg.debug(channels.getOrNull(index)))
                        .setPositiveButton("Cerrar", null)
                        .show()
                    7 -> AppUpdater.check(this, true)
                }
            }
            .show()
    }

    // ───────────────────────── Pista de audio (idioma) ─────────────────────────

    private fun exoAudioGroups() = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }

    private fun showAudioMenu() {
        val ch = channels.getOrNull(index) ?: return
        if (usingVlc) {
            val vp = vlcPlayer ?: return
            val tracks = (vp.audioTracks ?: emptyArray()).filter { it.id >= 0 }
            val names = ArrayList<String>()
            tracks.forEachIndexed { i, t -> names.add("${i + 1}.  ${t.name}") }
            names.add("↩  Volver al reproductor normal (este canal)")
            AlertDialog.Builder(this)
                .setTitle("Pista de audio")
                .setItems(names.toTypedArray()) { d, which ->
                    d.dismiss()
                    if (which < tracks.size) {
                        vp.setAudioTrack(tracks[which].id)
                        repo.setAudioPref(ch.name, which)
                        toast("Audio: ${tracks[which].name}")
                    } else {
                        repo.setNeedsVlc(ch.url, false)
                        startPlayback()
                    }
                }
                .show()
        } else {
            val groups = exoAudioGroups()
            val names = ArrayList<String>()
            groups.forEachIndexed { i, g ->
                val f = g.getTrackFormat(0)
                val name = f.label ?: f.language ?: "Pista"
                val mark = (if (g.isSelected) "   ✔" else "") + (if (!g.isSupported) "   (este equipo no la soporta → usa VLC)" else "")
                names.add("${i + 1}.  $name$mark")
            }
            names.add("▶  Probar con VLC (muestra todas las pistas)")
            AlertDialog.Builder(this)
                .setTitle("Pista de audio")
                .setItems(names.toTypedArray()) { d, which ->
                    d.dismiss()
                    if (which < groups.size) {
                        repo.setAudioPref(ch.name, which)
                        selectAudioExo(which)
                    } else {
                        repo.setNeedsVlc(ch.url, true)
                        startVlc(ch)
                    }
                }
                .show()
        }
    }

    private fun selectAudioExo(which: Int) {
        val g = exoAudioGroups().getOrNull(which) ?: return
        if (!g.isSupported) {
            // El equipo no puede decodificar esa pista: pasamos este canal a VLC.
            val ch = channels.getOrNull(index) ?: return
            repo.setNeedsVlc(ch.url, true)
            startVlc(ch)
            return
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
            .build()
    }

    private fun applySavedAudioExo() {
        if (audioApplied || usingVlc) return
        val ch = channels.getOrNull(index) ?: return
        val pref = repo.getAudioPref(ch.name)
        audioApplied = true
        if (pref >= 0) selectAudioExo(pref)
    }

    private fun applySavedAudioVlc() {
        if (audioApplied || !usingVlc) return
        val ch = channels.getOrNull(index) ?: return
        val vp = vlcPlayer ?: return
        val pref = repo.getAudioPref(ch.name)
        audioApplied = true
        if (pref < 0) return
        val tracks = (vp.audioTracks ?: emptyArray()).filter { it.id >= 0 }
        tracks.getOrNull(pref)?.let { vp.setAudioTrack(it.id) }
    }

    private fun testChannels() {
        if (ChannelChecker.running) {
            toast("Ya se están probando los canales")
            return
        }
        val list = channels
        showMessage("Probando canales…  0/${list.size}", 0)
        ChannelChecker.run(repo, list, { done, total ->
            if (done % 5 == 0 || done == total) {
                runOnUiThread { showMessage("Probando canales…  $done/$total", 0) }
            }
        }, { dead ->
            runOnUiThread {
                if (dead < 0) {
                    showMessage("No se pudo probar: revisá tu conexión a internet", 6000)
                } else {
                    showMessage("Listo: $dead de ${list.size} canales sin señal", 8000)
                    refreshShown()
                }
            }
        })
    }

    private fun askEpg() {
        val edit = EditText(this)
        edit.setText(repo.epgUrl)
        edit.hint = "Una dirección por línea (formato XMLTV, .xml o .xml.gz)"
        edit.setSingleLine(false)
        edit.minLines = 5
        edit.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        edit.textSize = 13f
        val box = LinearLayout(this)
        box.setPadding(48, 16, 48, 0)
        box.addView(edit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle("Guías de programación (una por línea)")
            .setView(box)
            .setPositiveButton("Guardar y actualizar") { _, _ ->
                repo.epgUrl = edit.text.toString()
                updateEpg(true)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun updateEpg(manual: Boolean) {
        val urls = Epg.parseUrls(repo.epgUrl)
        if (urls.isEmpty()) {
            if (manual) toast("Falta la dirección de la guía (Menú → Guía de programación)")
            return
        }
        if (manual) toast("Descargando ${urls.size} guías…")
        Epg.refresh(urls) { ok, msg ->
            runOnUiThread {
                if (manual) {
                    toast(if (ok) "$msg\nTus canales con guía: ${Epg.coverage(channels)} de ${channels.size}" else msg)
                    channels.getOrNull(index)?.let { showOsd(it) }
                }
                refreshShown()
            }
        }
    }

    private fun askUrl() {
        val edit = EditText(this)
        edit.setText(repo.listUrl)
        edit.hint = "https://raw.githubusercontent.com/usuario/repo/main/lista.m3u"
        edit.setSingleLine(true)
        val box = LinearLayout(this)
        box.setPadding(48, 16, 48, 0)
        box.addView(edit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle("Dirección de la lista")
            .setView(box)
            .setPositiveButton("Guardar y actualizar") { _, _ ->
                repo.listUrl = edit.text.toString()
                updateNow(true)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun updateNow(manual: Boolean) {
        if (repo.listUrl.isBlank()) {
            if (manual) toast("Falta la dirección de GitHub (Menú → Cambiar dirección)")
            return
        }
        if (manual) toast("Actualizando canales…")
        Thread {
            try {
                val list = repo.download()
                runOnUiThread { applyChannels(list, manual) }
            } catch (ex: Exception) {
                runOnUiThread {
                    if (manual) {
                        toast("No se pudo actualizar: ${ex.message}")
                    } else {
                        ui.removeCallbacks(retryUpdate)
                        ui.postDelayed(retryUpdate, 120_000)
                    }
                }
            }
        }.start()
    }

    private fun applyChannels(list: List<Channel>, manual: Boolean) {
        if (list == channels) {
            if (manual) toast("La lista ya está al día (${list.size} canales)")
            return
        }
        val cur = channels.getOrNull(index)
        channels = list
        val found = if (cur == null) -1 else list.indexOfFirst { it.name == cur.name }
        prevIndex = -1
        val same = cur != null && found >= 0 &&
            list[found].url == cur.url && list[found].userAgent == cur.userAgent
        when {
            same -> {
                index = found
                refreshShown() // sigue el mismo canal, sin cortar la imagen
            }
            found >= 0 -> tune(found)
            else -> tune(index.coerceIn(0, list.size - 1))
        }
        toast("Lista actualizada: ${list.size} canales")
    }

    // ───────────────────────── Adaptador de la lista ─────────────────────────

    private inner class ChannelAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView
                ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_channel, parent, false)
            val ch = shown[position]
            val isCurrent = ch === channels.getOrNull(index)
            val dead = isDead(ch)
            v.findViewById<TextView>(R.id.itemNum).text = ch.number.toString()
            val nameView = v.findViewById<TextView>(R.id.itemName)
            nameView.text = (if (isCurrent) "▶ " else "") + ch.name
            nameView.setTextColor(if (dead) 0xFF78909C.toInt() else 0xFFFFFFFF.toInt())
            v.findViewById<TextView>(R.id.itemGroup).text = ch.group
            LogoLoader.load(v.findViewById<ImageView>(R.id.itemLogo), ch.logo)
            val nowView = v.findViewById<TextView>(R.id.itemNow)
            val now = Epg.nowNext(ch).first
            if (now != null) {
                nowView.text = "${fmt(now.start)}  ${now.title}"
                nowView.visibility = View.VISIBLE
            } else {
                nowView.visibility = View.GONE
            }
            v.findViewById<TextView>(R.id.itemStatus).visibility = if (dead) View.VISIBLE else View.GONE
            return v
        }
    }
}
