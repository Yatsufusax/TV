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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
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
    private lateinit var infoPanel: TextView
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
    private lateinit var listPanel: View
    private lateinit var listCount: TextView
    private lateinit var splash: View
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

    // Señales de respaldo: srcIndex = señal actual del canal (0 = principal).
    // workingSrc recuerda (mientras la app está abierta) cuál señal anduvo para cada canal.
    private var srcIndex = 0
    private val workingSrc = HashMap<String, Int>()

    private val hideOsd = Runnable { osd.visibility = View.GONE }
    private val hideMessageRunnable = Runnable { messageView.visibility = View.GONE }
    private val commitRunnable = Runnable { commitDigits() }
    private val retryPlayback = Runnable {
        attempt = 0
        srcIndex = 0
        channels.getOrNull(index)?.let {
            workingSrc.remove(it.name)
            srcIndex = repo.getPinnedSource(it.name).coerceIn(0, it.sources.size - 1)
            modes = modesFor(it.sources[srcIndex].url)
        }
        startPlayback()
    }
    private val retryUpdate = Runnable { updateNow(false) }

    // ── Vigilante anti-congelamiento: si la imagen se queda quieta, reconecta solo
    private var wdLastPos = -1L
    private var wdSamePos = 0
    private var wdLastFrames = -1L
    private var wdSameFrames = 0
    private var wdBuffering = 0
    private var vlcUserPaused = false
    private var wdGood = 0
    private var stallCount = 0
    private val watchdog = object : Runnable {
        override fun run() {
            try {
                checkStall()
            } catch (_: Throwable) {
            }
            ui.postDelayed(this, 4000)
        }
    }
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
    // Busca una versión nueva de la app cada pocos minutos (no solo al abrirla).
    private val updateCheck = object : Runnable {
        override fun run() {
            // Si venció el código mensual con la app abierta, vuelve a pedirlo.
            if (!AccessGate.isUnlocked(this@MainActivity)) {
                startActivity(android.content.Intent(this@MainActivity, AccessActivity::class.java))
                this@MainActivity.finish()
                return
            }
            if (!stopped) AppUpdater.check(this@MainActivity, false)
            ui.postDelayed(this, Config.UPDATE_CHECK_MS)
        }
    }

    // ───────────────────────── Ciclo de vida ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sin código de acceso no se carga nada: se pasa a la pantalla del código.
        if (!AccessGate.isUnlocked(this)) {
            startActivity(android.content.Intent(this, AccessActivity::class.java))
            finish()
            return
        }
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
        infoPanel = findViewById(R.id.infoPanel)
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
        listPanel = findViewById(R.id.listPanel)
        listCount = findViewById(R.id.listCount)
        splash = findViewById(R.id.splash)
        // Pantalla de bienvenida: se desvanece sola mientras el canal ya está arrancando
        ui.postDelayed({
            splash.animate().alpha(0f).setDuration(450).withEndAction { splash.visibility = View.GONE }.start()
        }, 1700)

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
        // Buffers chicos = el canal aparece más rápido al cambiar.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                Config.BUF_MIN_MS, Config.BUF_MAX_MS,
                Config.BUF_START_MS, Config.BUF_REBUFFER_MS
            )
            .build()
        player = ExoPlayer.Builder(this, renderers).setLoadControl(load).build()
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Se quedó atrás de la transmisión en vivo: basta con volver a engancharse.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    startPlayback()
                    return
                }
                onPlaybackError()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    ui.removeCallbacks(retryPlayback)
                    hideMessage()
                    channels.getOrNull(index)?.let {
                        workingSrc[it.name] = srcIndex
                        // Si este link estaba marcado como caído y anda, se le saca la marca.
                        ChannelChecker.markAlive(repo, currentSource(it).url)
                    }
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

        ui.postDelayed(watchdog, 8000)
        ui.postDelayed(autoUpdate, 1500)
        ui.postDelayed(epgUpdate, 3000)
        ui.postDelayed(updateCheck, 5000)
    }

    override fun onStart() {
        super.onStart()
        if (stopped) {
            stopped = false
            if (channels.isNotEmpty()) startPlayback()
            // Al volver (por ejemplo del instalador cancelado) se vuelve a mirar si hay versión nueva.
            ui.postDelayed({ AppUpdater.check(this, false) }, 1500)
        }
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        if (::player.isInitialized) player.stop()
        stopVlc()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
        stopVlc()
        vlcPlayer?.release()
        libVlc?.release()
        super.onDestroy()
    }

    // ───────────────────────── Reproducción ─────────────────────────

    private fun looksHls(url: String) = url.contains(".m3u8", ignoreCase = true)
    private fun looksDash(url: String) = url.contains(".mpd", ignoreCase = true)

    private fun modesFor(url: String): List<Boolean> =
        if (looksHls(url) || looksDash(url)) listOf(true, false) else listOf(false, true)

    /** Señal que se está reproduciendo ahora del canal actual. */
    private fun currentSource(ch: Channel): Source =
        ch.sources[srcIndex.coerceIn(0, ch.sources.size - 1)]

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
        // Arranca con la fuente que fijó el usuario; si no hay, con la que ya anduvo antes; si no, con la principal.
        val pinned = repo.getPinnedSource(ch.name)
        srcIndex = (if (pinned >= 0) pinned else (workingSrc[ch.name] ?: 0)).coerceIn(0, ch.sources.size - 1)
        modes = modesFor(ch.sources[srcIndex].url)
        applyAspect()
        startPlayback()
        showOsd(ch)
        refreshShown()
    }

    // ───────────────────────── Ajuste de pantalla ─────────────────────────

    private val aspectNames = arrayOf("Ajustar (ver todo)", "Zoom (llenar, recorta)", "Estirar")

    private fun applyAspect() {
        val ch = channels.getOrNull(index) ?: return
        val a = repo.getAspect(ch.name).coerceIn(0, 2)
        playerView.resizeMode = when (a) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        try {
            vlcPlayer?.videoScale = when (a) {
                1 -> VlcPlayer.ScaleType.SURFACE_FIT_SCREEN
                2 -> VlcPlayer.ScaleType.SURFACE_FILL
                else -> VlcPlayer.ScaleType.SURFACE_BEST_FIT
            }
        } catch (_: Throwable) {
        }
    }

    private fun cycleAspect() {
        val ch = channels.getOrNull(index) ?: return
        val next = (repo.getAspect(ch.name) + 1) % 3
        repo.setAspect(ch.name, next)
        applyAspect()
        showMessage("Pantalla: ${aspectNames[next]}", 2500)
    }

    // ───────────────────────── Información del canal ─────────────────────────

    private val hideInfo = Runnable { infoPanel.visibility = View.GONE }

    private fun toggleInfo() {
        if (infoPanel.visibility == View.VISIBLE) {
            infoPanel.visibility = View.GONE
            ui.removeCallbacks(hideInfo)
            return
        }
        val ch = channels.getOrNull(index) ?: return
        infoPanel.text = buildInfo(ch)
        infoPanel.visibility = View.VISIBLE
        ui.removeCallbacks(hideInfo)
        ui.postDelayed(hideInfo, Config.INFO_MS)
        showOsd(ch)
    }

    private fun buildInfo(ch: Channel): String {
        val sb = StringBuilder()
        sb.append("${ch.number}  ${ch.name}\n")
        if (ch.group.isNotBlank()) sb.append("Grupo: ${ch.group}\n")
        val src = currentSource(ch)
        sb.append("Señal: ${srcIndex + 1} de ${ch.sources.size}\n")
        sb.append("Servidor: ${try { Uri.parse(src.url).host ?: "?" } catch (_: Exception) { "?" }}\n")
        sb.append("Motor: ${if (usingVlc) "VLC" else "ExoPlayer"}\n")
        sb.append("Pantalla: ${aspectNames[repo.getAspect(ch.name).coerceIn(0, 2)]}\n")
        try {
            if (usingVlc) {
                val vt = vlcPlayer?.currentVideoTrack
                if (vt != null && vt.width > 0) sb.append("Video: ${vt.width}x${vt.height}  ${vt.codec ?: ""}\n")
                val at = (vlcPlayer?.audioTracks ?: emptyArray()).firstOrNull { it.id == vlcPlayer?.audioTrack }
                if (at != null) sb.append("Audio: ${at.name}\n")
            } else {
                val vf = player.videoFormat
                if (vf != null) {
                    val fps = if (vf.frameRate > 0) "  ${vf.frameRate.toInt()} fps" else ""
                    val br = if (vf.bitrate > 0) "  ${vf.bitrate / 1000} kbps" else ""
                    sb.append("Video: ${vf.width}x${vf.height}$fps$br  ${vf.sampleMimeType?.substringAfter('/') ?: ""}\n")
                }
                val af = player.audioFormat
                if (af != null) {
                    sb.append("Audio: ${af.sampleMimeType?.substringAfter('/') ?: ""}  ${af.language ?: ""}  ${af.channelCount} canales\n")
                }
            }
        } catch (_: Throwable) {
        }
        val (now, _) = Epg.nowNext(ch)
        if (now != null) {
            sb.append("\nAhora: ${now.title}\n${fmt(now.start)} – ${fmt(now.stop)}")
        }
        return sb.toString().trimEnd()
    }

    private fun startPlayback() {
        val ch = channels.getOrNull(index) ?: return
        audioApplied = false
        val src = currentSource(ch)
        wdReset()
        if (repo.needsVlc(src.url)) {
            startVlc(ch, src)
            return
        }
        stopVlc()
        val ua = src.userAgent
            ?: "Mozilla/5.0 (Linux; Android 11; Google TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        val headers = HashMap<String, String>()
        src.referer?.let { headers["Referer"] = it }

        val dsf = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(15000)
            .setDefaultRequestProperties(headers)

        val source = if (looksDash(src.url)) {
            DashMediaSource.Factory(dsf).createMediaSource(MediaItem.fromUri(src.url))
        } else if (modes[attempt.coerceIn(0, modes.size - 1)]) {
            HlsMediaSource.Factory(dsf)
                .setAllowChunklessPreparation(true)
                .createMediaSource(
                    MediaItem.Builder()
                        .setUri(src.url)
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()
                )
        } else {
            ProgressiveMediaSource.Factory(dsf)
                .createMediaSource(MediaItem.fromUri(src.url))
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
            val src = currentSource(ch)
            repo.setNeedsVlc(src.url, true)
            startVlc(ch, src)
        } else if (audio.isNotEmpty() && video.isEmpty()) {
            showMessage("Este canal no trae video (solo audio)", 8000)
        }
    }

    // ───────────────────────── Motor VLC (respaldo) ─────────────────────────

    private fun startVlc(ch: Channel, src: Source) {
        audioApplied = false
        vlcUserPaused = false
        try {
            player.stop()
            ui.removeCallbacks(retryPlayback)
            val lib = libVlc ?: LibVLC(this, arrayListOf("--network-caching=${Config.VLC_CACHE_MS}")).also { libVlc = it }
            val vp = vlcPlayer ?: VlcPlayer(lib).also { p ->
                p.setEventListener { ev ->
                    when (ev.type) {
                        VlcPlayer.Event.Playing -> runOnUiThread {
                            hideMessage()
                            applyAspect()
                            ui.postDelayed({ applySavedAudioVlc() }, 1500)
                        }
                        VlcPlayer.Event.EncounteredError -> runOnUiThread {
                            if (!tryNextSource()) {
                                showMessage("Sin señal: ${channels.getOrNull(index)?.name ?: ""}", 0)
                                ui.removeCallbacks(retryPlayback)
                                ui.postDelayed(retryPlayback, 10_000)
                            }
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

            val ua = src.userAgent
                ?: "Mozilla/5.0 (Linux; Android 11; Google TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
            val media = Media(lib, Uri.parse(src.url))
            media.addOption(":http-user-agent=$ua")
            media.addOption(":audio-language=spa,es") // preferir audio en español
            src.referer?.let { media.addOption(":http-referrer=$it") }
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

    private fun wdReset() {
        wdLastPos = -1L
        wdSamePos = 0
        wdLastFrames = -1L
        wdSameFrames = 0
        wdBuffering = 0
    }

    /** Se llama cada 4 s: detecta imagen congelada o carga eterna y reconecta. */
    private fun checkStall() {
        if (stopped || channels.isEmpty()) return
        var stalled = false
        if (usingVlc) {
            val vp = vlcPlayer ?: return
            if (!vp.isPlaying) {
                // Pausado por el usuario: no tocar. Si no, VLC quedó trabado cargando: reconectar.
                if (vlcUserPaused) {
                    wdReset()
                    return
                }
                wdSamePos = 0
                wdBuffering++
                if (wdBuffering >= 4) reconnect() // ~16 s sin reproducir
                return
            }
            wdBuffering = 0
            val t = vp.time
            if (t == wdLastPos) wdSamePos++ else {
                wdSamePos = 0
                wdLastPos = t
            }
            stalled = wdSamePos >= 3
        } else {
            val st = player.playbackState
            if (st == Player.STATE_IDLE || st == Player.STATE_ENDED || !player.playWhenReady) {
                wdReset()
                return
            }
            if (st == Player.STATE_BUFFERING) {
                wdBuffering++
                stalled = wdBuffering >= 3 // ~12 s cargando sin avanzar
            } else {
                wdBuffering = 0
                val pos = player.currentPosition
                if (pos == wdLastPos) wdSamePos++ else {
                    wdSamePos = 0
                    wdLastPos = pos
                }
                if (wdSamePos >= 2) stalled = true // el tiempo no avanza
                // Video congelado aunque el audio siga: no se dibujan cuadros nuevos
                val counters = player.videoDecoderCounters
                if (counters != null && player.videoFormat != null) {
                    counters.ensureUpdated()
                    val frames = counters.renderedOutputBufferCount.toLong()
                    if (frames == wdLastFrames) wdSameFrames++ else {
                        wdSameFrames = 0
                        wdLastFrames = frames
                    }
                    if (wdSameFrames >= 3) stalled = true
                }
            }
        }
        if (stalled) {
            reconnect()
        } else if (wdSamePos == 0 && wdSameFrames == 0 && wdBuffering == 0) {
            if (++wdGood >= 8) stallCount = 0 // ~30 s estable: se olvidan los tropiezos
        }
    }

    /** Reintenta el mismo link; si se congela varias veces seguidas pasa al respaldo. */
    private fun reconnect() {
        wdReset()
        wdGood = 0
        stallCount++
        if (stallCount > 2) {
            stallCount = 0
            if (tryNextSource()) return
        }
        showMessage("Reconectando…", 2500)
        startPlayback()
    }

    /** Pasa a la siguiente señal de respaldo del canal actual. false si ya no quedan. */
    private fun tryNextSource(): Boolean {
        val ch = channels.getOrNull(index) ?: return false
        if (srcIndex >= ch.sources.size - 1) return false
        srcIndex++
        attempt = 0
        modes = modesFor(ch.sources[srcIndex].url)
        showMessage("Señal caída, probando respaldo ${srcIndex + 1}/${ch.sources.size}…", 4000)
        startPlayback()
        return true
    }

    /** Elige a mano la fuente (enlace) del canal actual. pin = true la deja fijada para este canal. */
    private fun selectSource(i: Int, pin: Boolean = true) {
        val ch = channels.getOrNull(index) ?: return
        if (i !in ch.sources.indices) return
        ui.removeCallbacks(retryPlayback)
        srcIndex = i
        attempt = 0
        stallCount = 0
        modes = modesFor(ch.sources[i].url)
        workingSrc[ch.name] = i
        repo.setPinnedSource(ch.name, if (pin) i else -1)
        showMessage(
            if (pin) "Fuente ${i + 1} de ${ch.sources.size} (fijada)" else "Fuente automática",
            2500
        )
        startPlayback()
    }

    private fun showSources() {
        val ch = channels.getOrNull(index) ?: return
        val n = ch.sources.size
        if (n < 2) {
            toast("Este canal tiene una sola fuente")
            return
        }
        val dead = ch.sources.map { ChannelChecker.isDeadUrl(it.url) }
        SourceDialog.show(
            this, ch.name, n, srcIndex.coerceIn(0, n - 1), repo.getPinnedSource(ch.name), dead,
            { selectSource(0, false) },
            { i -> selectSource(i, true) }
        )
    }

    private fun onPlaybackError() {
        if (attempt < modes.size - 1) {
            attempt++
            startPlayback()
            return
        }
        if (tryNextSource()) return
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
        listPanel.visibility = View.VISIBLE
        panelVisible = true
        listView.requestFocus()
        listView.post {
            val cur = channels.getOrNull(index)
            listView.setSelection(shown.indexOfFirst { it === cur }.coerceAtLeast(0))
        }
    }

    private fun isDead(ch: Channel) = ChannelChecker.isDead(ch)

    /** Recalcula qué canales se ven en la lista (si está activado, oculta los caídos). */
    private fun refreshShown() {
        val cur = channels.getOrNull(index)
        shown = if (repo.hideDead) channels.filter { !isDead(it) || it === cur } else channels
        listCount.text = "${shown.size} canales"
        adapter.notifyDataSetChanged()
    }

    private fun hideList() {
        listPanel.visibility = View.GONE
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
        val onList = panelVisible && ev.x <= listPanel.width
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
            KeyEvent.KEYCODE_INFO -> toggleInfo()
            KeyEvent.KEYCODE_LAST_CHANNEL -> if (prevIndex in channels.indices) tune(prevIndex)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                if (usingVlc) {
                    vlcPlayer?.let {
                        if (it.isPlaying) { vlcUserPaused = true; it.pause() } else { vlcUserPaused = false; it.play() }
                    }
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
        val aspectNow = aspectNames[repo.getAspect(channels.getOrNull(index)?.name ?: "").coerceIn(0, 2)]
            .substringBefore(" (")
        val entries = listOf(
            MenuEntry(R.drawable.ic_m_refresh, "Actualizar canales", null, "#4ADE80") { updateNow(true) },
            MenuEntry(R.drawable.ic_m_audio, "Pista de audio", "Idioma", "#C084FC") { showAudioMenu() },
            MenuEntry(R.drawable.ic_m_swap, "Fuentes", "Cambiar enlace", "#FB923C") { showSources() },
            MenuEntry(R.drawable.ic_m_check, "Probar canales", "Detectar caídos", "#2DD4BF") { testChannels() },
            MenuEntry(R.drawable.ic_m_eye, "Canales caídos", if (repo.hideDead) "Ocultos" else "Visibles", "#FBBF24") {
                repo.hideDead = !repo.hideDead
                refreshShown()
                toast(if (repo.hideDead) "Canales caídos ocultos" else "Canales caídos visibles")
            },
            MenuEntry(R.drawable.ic_m_calendar, "Guía (EPG)", "Programación", "#60A5FA") { askEpg() },
            MenuEntry(R.drawable.ic_m_help, "Diagnóstico", "De la guía", "#94A3B8") {
                AlertDialog.Builder(this)
                    .setTitle("Diagnóstico de la guía")
                    .setMessage(Epg.debug(channels.getOrNull(index)))
                    .setPositiveButton("Cerrar", null)
                    .show()
            },
            MenuEntry(R.drawable.ic_m_info, "Info del canal", null, "#38BDF8") { toggleInfo() },
            MenuEntry(R.drawable.ic_m_aspect, "Pantalla", aspectNow, "#F472B6") { cycleAspect() },
            MenuEntry(R.drawable.ic_m_download, "Actualizar app", "v${AppUpdater.currentVersion(this)}", "#34D399") {
                AppUpdater.check(this, true)
            }
        )
        MenuDialog.show(this, "Menú", "${channels.size} canales  ·  v${AppUpdater.currentVersion(this)}", entries)
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
                        repo.setNeedsVlc(currentSource(ch).url, false)
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
                        repo.setNeedsVlc(currentSource(ch).url, true)
                        startVlc(ch, currentSource(ch))
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
            repo.setNeedsVlc(currentSource(ch).url, true)
            startVlc(ch, currentSource(ch))
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

    private fun updateNow(manual: Boolean) {
        if (repo.listUrl.isBlank()) {
            if (manual) toast("Todavía no hay lista cargada. Cerrá la app y volvé a abrirla")
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
            nameView.setTextColor(
                when {
                    dead -> 0xFF78909C.toInt()
                    isCurrent -> 0xFFFFC93C.toInt()
                    else -> 0xFFFFFFFF.toInt()
                }
            )
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
