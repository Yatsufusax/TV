package com.mistv.canales

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.media.AudioManager
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
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView

class MainActivity : Activity() {

    private lateinit var repo: ChannelRepository
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var osd: View
    private lateinit var osdNumber: TextView
    private lateinit var osdName: TextView
    private lateinit var osdGroup: TextView
    private lateinit var digitsView: TextView
    private lateinit var messageView: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: ChannelAdapter

    private val ui = Handler(Looper.getMainLooper())

    // ── Gestos táctiles (para celular): toque = lista, deslizar = cambiar canal, mantener = menú
    private lateinit var gestures: GestureDetector

    private var channels: List<Channel> = emptyList()
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
        osd = findViewById(R.id.osd)
        osdNumber = findViewById(R.id.osdNumber)
        osdName = findViewById(R.id.osdName)
        osdGroup = findViewById(R.id.osdGroup)
        digitsView = findViewById(R.id.digits)
        messageView = findViewById(R.id.message)
        listView = findViewById(R.id.channelList)

        repo = ChannelRepository(this)
        channels = repo.loadLocal()

        adapter = ChannelAdapter()
        listView.adapter = adapter
        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            hideList()
            tune(pos)
        }

        player = ExoPlayer.Builder(this).build()
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onPlaybackError()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    ui.removeCallbacks(retryPlayback)
                    hideMessage()
                }
            }
        })
        playerView.player = player

        val last = repo.lastName
        index = channels.indexOfFirst { it.name == last }.let { if (it >= 0) it else 0 }
        tune(index)

        ui.postDelayed(autoUpdate, 1500)
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
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        player.release()
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
        adapter.notifyDataSetChanged()
    }

    private fun startPlayback() {
        val ch = channels.getOrNull(index) ?: return
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
        osd.visibility = View.VISIBLE
        ui.removeCallbacks(hideOsd)
        ui.postDelayed(hideOsd, Config.OSD_MS)
    }

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
        adapter.notifyDataSetChanged()
        listView.visibility = View.VISIBLE
        panelVisible = true
        listView.requestFocus()
        listView.post { listView.setSelection(index) }
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
        tune((index + delta + channels.size) % channels.size)
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
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> player.playWhenReady = !player.playWhenReady
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
            "Cerrar"
        )
        AlertDialog.Builder(this)
            .setTitle("Menú  ·  ${channels.size} canales")
            .setItems(items) { d, which ->
                d.dismiss()
                when (which) {
                    0 -> updateNow(true)
                    1 -> askUrl()
                }
            }
            .show()
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
                adapter.notifyDataSetChanged() // sigue el mismo canal, sin cortar la imagen
            }
            found >= 0 -> tune(found)
            else -> tune(index.coerceIn(0, list.size - 1))
        }
        toast("Lista actualizada: ${list.size} canales")
    }

    // ───────────────────────── Adaptador de la lista ─────────────────────────

    private inner class ChannelAdapter : BaseAdapter() {
        override fun getCount() = channels.size
        override fun getItem(position: Int) = channels[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView
                ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_channel, parent, false)
            val ch = channels[position]
            v.findViewById<TextView>(R.id.itemNum).text = ch.number.toString()
            v.findViewById<TextView>(R.id.itemName).text =
                (if (position == index) "▶ " else "") + ch.name
            v.findViewById<TextView>(R.id.itemGroup).text = ch.group
            return v
        }
    }
}
