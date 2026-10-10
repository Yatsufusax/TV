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

    private val suffixes = listOf(
        "latinamerica", "latinoamerica", "southatlantic", "panregional", "latam",
        "fhd", "uhd", "hd", "sd", "4k",
        "us", "ar", "cl", "co", "mx", "py", "br", "es", "pe", "uy"
    )

    /** Variantes de un nombre o código: sin tildes, sin HD/SD ni sufijo de país o región. */
    fun variants(raw: String): List<String> {
        val out = LinkedHashSet<String>()
        for (base in listOf(raw, raw.replace(Regex("\\([^)]*\\)"), ""))) {
            var cur = norm(base)
            if (cur.isEmpty()) continue
            out.add(cur)
            var changed = true
            while (changed) {
                changed = false
                for (sfx in suffixes) {
                    if (cur.endsWith(sfx) && cur.length - sfx.length >= 4) {
                        cur = cur.removeSuffix(sfx)
                        out.add(cur)
                        changed = true
                        break
                    }
                }
            }
        }
        return out.toList()
    }

    /** Devuelve (id de la guía encontrado, claves que se probaron). */
    private fun findId(ch: Channel): Pair<String?, List<String>> {
        val p = progs
        val a = alias
        val keys = ArrayList<String>()
        val tid = ch.tvgId
        if (tid != null) {
            keys.add(tid)
            keys.add(tid.substringBefore('@'))
            keys.addAll(variants(tid.substringBefore('@')))
        }
        keys.addAll(variants(ch.name))
        for (k in keys) {
            if (k.isEmpty()) continue
            val id = if (p.containsKey(k)) k else a[k]
            if (id != null && !p[id].isNullOrEmpty()) return Pair(id, keys)
        }
        return Pair(null, keys)
    }

    private fun find(ch: Channel): List<Prog>? {
        if (progs.isEmpty()) return null
        val id = findId(ch).first ?: return null
        return progs[id]
    }

    /** Texto para entender por qué un canal tiene o no tiene guía. */
    fun debug(ch: Channel?): String {
        val p = progs
        val sb = StringBuilder()
        sb.append("Guía cargada: ${p.size} canales con programas\n")
        sb.append("Ejemplos de la guía:\n  ").append(p.keys.sorted().take(14).joinToString(", ")).append("\n\n")
        if (ch != null) {
            val r = findId(ch)
            sb.append("Canal actual: ${ch.name}\n")
            sb.append("tvg-id: ${ch.tvgId ?: "(no tiene)"}\n")
            sb.append("Buscado como: ${r.second.distinct().take(8).joinToString(", ")}\n")
            sb.append("Resultado: ").append(if (r.first != null) "SÍ → ${r.first}" else "no encontrado")
        }
        return sb.toString()
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
                        if (id != null) for (v in variants(id)) if (!alias.containsKey(v)) alias[v] = id
                    }
                    "display-name" -> {
                        val id = chId
                        if (id != null) {
                            for (v in variants(parser.nextText())) if (!alias.containsKey(v)) alias[v] = id
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
