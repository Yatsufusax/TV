package com.mistv.canales

data class Channel(
    val number: Int,
    val name: String,
    val group: String,
    val url: String,
    val userAgent: String?,
    val referer: String?,
    val logo: String? = null,
    val tvgId: String? = null
)

object M3uParser {
    private val attrRegex = Regex("([\\w\\-]+)=\"([^\"]*)\"")

    private class Raw {
        var name: String = ""
        var group: String = "Sin grupo"
        var chno: Int? = null
        var ua: String? = null
        var referer: String? = null
        var logo: String? = null
        var tvgId: String? = null
    }

    fun parse(input: String): List<Channel> {
        val text = input.removePrefix("\uFEFF")
        val raws = ArrayList<Pair<Raw, String>>()
        var cur: Raw? = null

        for (line0 in text.lineSequence()) {
            val line = line0.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val r = Raw()
                // El nombre va después de la primera coma posterior a la última comilla
                // (los atributos pueden tener comas adentro, por ejemplo en el user-agent).
                val lastQuote = line.lastIndexOf('"')
                val comma = line.indexOf(',', if (lastQuote >= 0) lastQuote else 0)
                val head = if (comma >= 0) line.substring(0, comma) else line
                r.name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                for (m in attrRegex.findAll(head)) {
                    val v = m.groupValues[2]
                    when (m.groupValues[1].lowercase()) {
                        "group-title" -> if (v.isNotBlank()) r.group = v
                        "tvg-logo" -> if (v.isNotBlank()) r.logo = v.trim()
                        "tvg-id" -> if (v.isNotBlank()) r.tvgId = v.trim()
                        "tvg-chno", "channel-number" -> r.chno = v.trim().toIntOrNull()
                        "http-user-agent" -> r.ua = v
                        "http-referrer", "http-referer" -> r.referer = v
                    }
                }
                cur = r
            } else if (line.startsWith("#EXTVLCOPT:", ignoreCase = true)) {
                val c = cur
                if (c != null) {
                    val kv = line.substringAfter(':')
                    val key = kv.substringBefore('=').trim().lowercase()
                    val value = kv.substringAfter('=', "").trim()
                    when (key) {
                        "http-user-agent" -> c.ua = value
                        "http-referrer", "http-referer" -> c.referer = value
                    }
                }
            } else if (line.startsWith("#")) {
                // otros comentarios: se ignoran
            } else {
                val c = cur
                if (c != null) {
                    if (c.name.isBlank()) c.name = line
                    raws.add(Pair(c, line))
                }
                cur = null
            }
        }

        // Numeración: se respeta tvg-chno si existe y no está repetido;
        // el resto recibe el primer número libre en el orden de la lista.
        val used = HashSet<Int>()
        val claimed = arrayOfNulls<Int>(raws.size)
        raws.forEachIndexed { i, (r, _) ->
            val n = r.chno
            if (n != null && n > 0 && used.add(n)) claimed[i] = n
        }
        var next = 1
        val result = ArrayList<Channel>(raws.size)
        raws.forEachIndexed { i, (r, url) ->
            val num = claimed[i] ?: run {
                while (next in used) next++
                used.add(next)
                next
            }
            result.add(Channel(num, r.name, r.group, url, r.ua, r.referer, r.logo, r.tvgId))
        }
        return result.sortedBy { it.number }
    }
}
