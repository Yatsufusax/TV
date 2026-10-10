package com.mistv.canales

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

class Prog(val start: Long, val stop: Long, val title: String)

/** Guía de programación (XMLTV): qué están dando ahora y qué sigue en cada canal. */
object Epg {
    @Volatile private var progs: Map<String, List<Prog>> = emptyMap()
    @Volatile private var alias: Map<String, String> = emptyMap()
    @Volatile private var busy = false

    fun norm(s: String): String {
        val t = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return t.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    private fun find(ch: Channel): List<Prog>? {
        val p = progs
        val a = alias
        if (p.isEmpty()) return null
        val keys = ArrayList<String>()
        val tid = ch.tvgId
        if (tid != null) {
            keys.add(tid)
            keys.add(tid.substringBefore('@'))
            keys.add(norm(tid))
            keys.add(norm(tid.substringBefore('@')))
        }
        keys.add(norm(ch.name))
        for (k in keys) {
            if (k.isEmpty()) continue
            val id = if (p.containsKey(k)) k else a[k]
            if (id != null) {
                val l = p[id]
                if (l != null && l.isNotEmpty()) return l
            }
        }
        return null
    }

    /** Programa actual y el siguiente (cualquiera puede ser null). */
    fun nowNext(ch: Channel): Pair<Prog?, Prog?> {
        val list = find(ch) ?: return Pair(null, null)
        val now = System.currentTimeMillis()
        val cur = list.firstOrNull { it.start <= now && now < it.stop }
        val next = if (cur != null) list.firstOrNull { it.start >= cur.stop } else list.firstOrNull { it.start > now }
        return Pair(cur, next)
    }

    fun refresh(url: String, done: (Boolean, String) -> Unit) {
        if (busy) return
        busy = true
        Thread {
            try {
                val result = download(url)
                progs = result.first
                alias = result.second
                done(true, "Guía actualizada: ${result.first.size} canales con programación")
            } catch (ex: Exception) {
                done(false, "No se pudo cargar la guía: ${ex.message}")
            } finally {
                busy = false
            }
        }.start()
    }

    private fun download(url: String): Pair<Map<String, List<Prog>>, Map<String, String>> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.setRequestProperty("User-Agent", "MisCanales/1.0")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val raw = BufferedInputStream(conn.inputStream)
            raw.mark(2)
            val b1 = raw.read()
            val b2 = raw.read()
            raw.reset()
            val stream: InputStream = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(raw) else raw
            return parse(stream)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(input: InputStream): Pair<Map<String, List<Prog>>, Map<String, String>> {
        val parser = Xml.newPullParser()
        parser.setInput(input, null)
        val now = System.currentTimeMillis()
        val minStop = now - 2 * 3600_000L
        val maxStart = now + 36 * 3600_000L
        val fmtZ = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val fmtN = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        fun time(s: String?): Long {
            if (s == null) return 0L
            val x = s.trim()
            return try {
                if (x.length > 14) fmtZ.parse(x.substring(0, 14) + " " + x.substring(14).trim())!!.time
                else fmtN.parse(x.take(14))!!.time
            } catch (e: Exception) {
                0L
            }
        }

        val progs = HashMap<String, ArrayList<Prog>>()
        val alias = HashMap<String, String>()
        var chId: String? = null
        var pChan: String? = null
        var pStart = 0L
        var pStop = 0L
        var pTitle: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        chId = parser.getAttributeValue(null, "id")
                        val id = chId
                        if (id != null) alias[norm(id)] = id
                    }
                    "display-name" -> {
                        val id = chId
                        if (id != null) {
                            val k = norm(parser.nextText())
                            if (k.isNotEmpty() && !alias.containsKey(k)) alias[k] = id
                        }
                    }
                    "programme" -> {
                        pChan = parser.getAttributeValue(null, "channel")
                        pStart = time(parser.getAttributeValue(null, "start"))
                        pStop = time(parser.getAttributeValue(null, "stop"))
                        pTitle = null
                    }
                    "title" -> {
                        if (pChan != null && pTitle == null) pTitle = parser.nextText()
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "channel" -> chId = null
                    "programme" -> {
                        val c = pChan
                        val t = pTitle
                        if (c != null && t != null && pStop > pStart && pStop >= minStop && pStart <= maxStart) {
                            progs.getOrPut(c) { ArrayList() }.add(Prog(pStart, pStop, t.trim()))
                        }
                        pChan = null
                        pTitle = null
                    }
                }
            }
            event = parser.next()
        }
        for (l in progs.values) l.sortBy { it.start }
        return Pair(progs, alias)
    }
}
