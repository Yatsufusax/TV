package com.mistv.canales

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Eventos administrados en un archivo JSON separado de la lista M3U principal. */
class EventRepository(private val context: Context) {
    private val cacheFile = File(context.filesDir, "eventos.json")

    data class LoadedEvents(
        val channels: List<Channel>,
        /** URLs que deben abrirse en el WebView, no en ExoPlayer/VLC. */
        val webUrls: Set<String>
    )

    fun load(): LoadedEvents {
        val remote = Config.EVENTS_URL.trim()
        if (remote.isNotBlank()) {
            try {
                val connection = URL(remote).openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 12000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "YuzuPlay/1.0")
                connection.setRequestProperty("Cache-Control", "no-cache")
                try {
                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val parsed = parse(text)
                        // Solo reemplaza la caché con un JSON válido.
                        cacheFile.writeText(text)
                        return parsed
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                // La caché local permite que los eventos sigan visibles sin conexión.
            }
        }
        return try {
            if (cacheFile.exists()) parse(cacheFile.readText())
            else context.assets.open("eventos.json").bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
        } catch (_: Exception) {
            LoadedEvents(emptyList(), emptySet())
        }
    }

    private fun parse(text: String): LoadedEvents {
        val root = JSONObject(text)
        val array = root.optJSONArray("events") ?: JSONArray()
        val channels = ArrayList<Channel>()
        val webUrls = LinkedHashSet<String>()
        val usedNumbers = HashSet<Int>()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (!item.optBoolean("enabled", true)) continue
            val number = item.optInt("number", -1)
            val name = item.optString("name", "").trim()
            val type = item.optString("type", "DIRECT_STREAM").uppercase()
            val url = item.optString("url", "").trim()
            if (number !in 1000..1999 || name.isBlank() || !isSafeHttpsUrl(url) || !usedNumbers.add(number)) continue

            val userAgent = item.optString("userAgent", "").takeIf { it.isNotBlank() }
            val referer = item.optString("referer", "").takeIf { it.isNotBlank() && isSafeHttpsUrl(it) }
            val logo = item.optString("logo", "").takeIf { it.isNotBlank() && isSafeHttpsUrl(it) }
            when (type) {
                "WEB_PAGE" -> {
                    // Esta primera integración solo permite páginas de LaCancha.tv.
                    // Para sumar otro proveedor, añadí su dominio a isAllowedWebPageUrl.
                    if (!isAllowedWebPageUrl(url)) continue
                    webUrls.add(url)
                    channels.add(Channel(number, name, "Eventos", url, null, null, logo))
                }
                "DIRECT_STREAM" -> channels.add(Channel(number, name, "Eventos", url, userAgent, referer, logo))
            }
        }
        return LoadedEvents(channels.sortedBy { it.number }, webUrls)
    }

    private fun isSafeHttpsUrl(value: String): Boolean = try {
        val parsed = URL(value)
        parsed.protocol.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank()
    } catch (_: Exception) {
        false
    }

    private fun isAllowedWebPageUrl(value: String): Boolean = try {
        val parsed = URL(value)
        val host = parsed.host.lowercase()
        parsed.protocol.equals("https", ignoreCase = true) &&
            (host == "lacancha.tv" || host.endsWith(".lacancha.tv"))
    } catch (_: Exception) { false }
}
