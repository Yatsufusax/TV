package com.mistv.canales

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ChannelRepository(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)
    private val cacheFile = File(ctx.filesDir, "lista.m3u")

    var listUrl: String
        get() = prefs.getString("url", Config.DEFAULT_LIST_URL) ?: ""
        set(v) {
            prefs.edit().putString("url", v.trim()).apply()
        }

    /** Pista de audio elegida por el usuario para un canal (-1 = ninguna). */
    fun getAudioPref(channel: String): Int = prefs.getInt("aud_$channel", -1)

    fun setAudioPref(channel: String, value: Int) {
        prefs.edit().putInt("aud_$channel", value).apply()
    }

    /** Ajuste de pantalla por canal: 0 = ajustar, 1 = zoom (recorta), 2 = estirar. */
    fun getAspect(channel: String): Int = prefs.getInt("asp_$channel", 0)

    fun setAspect(channel: String, value: Int) {
        prefs.edit().putInt("asp_$channel", value).apply()
    }

    /** Canales que se reproducen siempre con VLC (el equipo no puede con su video o audio). */
    fun needsVlc(url: String): Boolean = prefs.getBoolean("vlc_${url.hashCode()}", false)

    fun setNeedsVlc(url: String, value: Boolean) {
        prefs.edit().putBoolean("vlc_${url.hashCode()}", value).apply()
    }

    /** Dirección de la guía de programación (XMLTV, puede ser .xml o .xml.gz). */
    var epgUrl: String
        get() = prefs.getString("epg_urls", Config.DEFAULT_EPG_URLS) ?: Config.DEFAULT_EPG_URLS
        set(v) {
            prefs.edit().putString("epg_urls", v.trim()).apply()
        }

    /** Ocultar los canales que la prueba marcó como caídos. */
    var hideDead: Boolean
        get() = prefs.getBoolean("hidedead", false)
        set(v) {
            prefs.edit().putBoolean("hidedead", v).apply()
        }

    fun getDeadUrls(): Set<String> = HashSet(prefs.getStringSet("dead", emptySet()) ?: emptySet())

    fun setDeadUrls(s: Set<String>) {
        prefs.edit().putStringSet("dead", HashSet(s)).apply()
    }

    var lastName: String?
        get() = prefs.getString("last", null)
        set(v) {
            prefs.edit().putString("last", v).apply()
        }

    /** Lista guardada en el equipo; si no hay, la que viene dentro de la app. */
    fun loadLocal(): List<Channel> {
        if (cacheFile.exists()) {
            try {
                val list = M3uParser.parse(cacheFile.readText())
                if (list.isNotEmpty()) return list
            } catch (_: Exception) {
            }
        }
        val text = ctx.assets.open("lista.m3u").bufferedReader(Charsets.UTF_8).use { it.readText() }
        return M3uParser.parse(text)
    }

    /** Descarga la lista desde GitHub. Lanza excepción si falla. */
    fun download(): List<Channel> {
        val url = listUrl
        require(url.isNotBlank()) { "Falta la dirección de la lista" }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "MisCanales/1.0")
        conn.setRequestProperty("Cache-Control", "no-cache")
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val list = M3uParser.parse(text)
            check(list.isNotEmpty()) { "La lista descargada no tiene canales" }
            val tmp = File(ctx.filesDir, "lista.m3u.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.writeText(text)
                tmp.delete()
            }
            return list
        } finally {
            conn.disconnect()
        }
    }
}
