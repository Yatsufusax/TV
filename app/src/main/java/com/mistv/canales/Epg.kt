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
    @Volatile private var alias: Map<String, List<String>> = emptyMap()
    @Volatile private var busy = false

    fun norm(s: String): String {
        val t = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return t.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    // Sufijos que se ignoran al comparar nombres, con el largo mínimo que debe quedar del nombre.
    private val suffixes: List<Pair<String, Int>> =
        listOf(
            "latinamerica" to 3, "latinoamerica" to 3, "southatlantic" to 3, "panregional" to 3,
            "enespanol" to 3, "latam" to 3,
            "fhd" to 2, "uhd" to 2, "hd" to 2, "sd" to 2, "4k" to 2
        ) + listOf("us", "ar", "cl", "co", "mx", "py", "br", "es", "pe", "uy").map { it to 4 }

    private fun stripSuffixes(start: String, out: MutableCollection<String>) {
        var cur = start
        var changed = true
        while (changed) {
            changed = false
            for ((sfx, minLen) in suffixes) {
                if (cur.endsWith(sfx) && cur.length - sfx.length >= minLen) {
                    cur = cur.removeSuffix(sfx)
                    out.add(cur)
                    changed = true
                    break
                }
            }
        }
    }

    /** Variantes de un nombre o código: sin tildes, sin "Canal", sin HD/SD ni país o región, Jr = Junior. */
    fun variants(raw: String): List<String> {
        val out = LinkedHashSet<String>()
        for (base in listOf(raw, raw.replace(Regex("\\([^)]*\\)"), ""))) {
            val cur = norm(base)
            if (cur.isEmpty()) continue
            out.add(cur)
            stripSuffixes(cur, out)
        }
        // Algunas guías anteponen "Canal " a casi todos los nombres: probar también sin esa palabra.
        for (v in out.toList()) {
            if (v.startsWith("canal") && v.length - 5 >= 3) {
                val w = v.substring(5)
                out.add(w)
                stripSuffixes(w, out)
            }
        }
        // Siglas entre paréntesis, por ejemplo "Chilevisión (CHV)" → "chv".
        for (m in Regex("\\(([A-Z]{3,5})\\)").findAll(raw)) {
            val sg = m.groupValues[1]
            if (sg != "FHD" && sg != "UHD") out.add(sg.lowercase())
        }
        for (v in out.toList()) {
            if (v.contains("jr")) out.add(v.replace("jr", "junior"))
        }
        return out.toList()
    }

    /** País que se prefiere cuando el mismo canal está en varias guías (según su código o su grupo). */
    private fun preferredCode(ch: Channel): String? {
        val tid = ch.tvgId
        if (tid != null) {
            val code = tid.substringBefore('@').substringAfterLast('.')
            if (code.length == 2 && code != "us") return code.lowercase()
        }
        val g = ch.group.lowercase()
        return when {
            g.contains("chile") -> "cl"
            g.contains("colombia") -> "co"
            g.contains("argentina") -> "ar"
            g.contains("méxico") || g.contains("mexico") -> "mx"
            else -> null
        }
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
            val cands = if (p.containsKey(k)) listOf(k) else (a[k] ?: emptyList())
            val valid = cands.filter { !p[it].isNullOrEmpty() }
            if (valid.isNotEmpty()) {
                val want = preferredCode(ch)
                val pick = valid.firstOrNull { want != null && it.substringAfterLast('.').lowercase() == want }
                    ?: valid.first()
                return Pair(pick, keys)
            }
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

    /** Separa varias direcciones escritas con saltos de línea, espacios o comas. */
    fun parseUrls(text: String): List<String> =
        text.split(Regex("[\\s,;]+")).map { it.trim() }.filter { it.startsWith("http", ignoreCase = true) }.distinct()

    /** Cuántos canales de la lista tienen programación en la guía cargada. */
    fun coverage(list: List<Channel>): Int = list.count { find(it) != null }

    /** Descarga varias guías y las junta en una sola. Una que falle no impide cargar las demás. */
    fun refresh(urls: List<String>, done: (Boolean, String) -> Unit) {
        if (busy) return
        busy = true
        Thread {
            try {
                val allProgs = HashMap<String, List<Prog>>()
                val allAlias = HashMap<String, MutableList<String>>()
                var ok = 0
                val errors = ArrayList<String>()
                for (u in urls) {
                    try {
                        val r = download(u)
                        for ((k, v) in r.first) if (!allProgs.containsKey(k)) allProgs[k] = v
                        for ((k, v) in r.second) {
                            val l = allAlias.getOrPut(k) { ArrayList() }
                            for (x in v) if (!l.contains(x)) l.add(x)
                        }
                        ok++
                    } catch (ex: Exception) {
                        errors.add("${u.substringAfterLast('/')}: ${ex.message}")
                    }
                }
                if (ok == 0) {
                    done(false, "No se pudo cargar la guía: ${errors.firstOrNull() ?: "sin direcciones"}")
                } else {
                    progs = allProgs
                    alias = allAlias
                    val extra = if (errors.isEmpty()) "" else "  (fallaron ${errors.size}: ${errors.joinToString("; ")})"
                    done(true, "Guías cargadas: $ok de ${urls.size}  ·  ${allProgs.size} canales en la guía$extra")
                }
            } catch (ex: Throwable) {
                done(false, "No se pudo cargar la guía: ${ex.message}")
            } finally {
                busy = false
            }
        }.start()
    }

    private fun download(url: String): Pair<Map<String, List<Prog>>, Map<String, List<String>>> {
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

    private fun parse(input: InputStream): Pair<Map<String, List<Prog>>, Map<String, List<String>>> {
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
        val alias = HashMap<String, ArrayList<String>>()
        fun addAlias(k: String, id: String) {
            val l = alias.getOrPut(k) { ArrayList() }
            if (!l.contains(id)) l.add(id)
        }
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
                        if (id != null) for (v in variants(id)) addAlias(v, id)
                    }
                    "display-name" -> {
                        val id = chId
                        if (id != null) {
                            for (v in variants(parser.nextText())) addAlias(v, id)
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
