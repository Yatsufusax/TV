package com.mistv.canales

import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Prueba cada canal en segundo plano y recuerda cuáles no responden. */
object ChannelChecker {
    private val dead = HashSet<String>()

    @Volatile
    var running = false

    private fun key(url: String) = url.hashCode().toString()

    fun load(repo: ChannelRepository) {
        synchronized(dead) {
            dead.clear()
            dead.addAll(repo.getDeadUrls())
        }
    }

    fun isDeadUrl(url: String): Boolean = synchronized(dead) { dead.contains(key(url)) }

    /** Si un link marcado como caído vuelve a andar, se le saca la marca (y se guarda). */
    fun markAlive(repo: ChannelRepository, url: String) {
        var snapshot: HashSet<String>? = null
        synchronized(dead) {
            if (dead.remove(key(url))) snapshot = HashSet(dead)
        }
        snapshot?.let { repo.setDeadUrls(it) }
    }

    /** Un canal está caído solo si TODAS sus señales (principal y respaldos) están caídas. */
    fun isDead(ch: Channel): Boolean = ch.sources.all { isDeadUrl(it.url) }

    /** onDone recibe la cantidad de canales caídos, o -1 si parece que no hay internet. */
    fun run(
        repo: ChannelRepository,
        list: List<Channel>,
        onProgress: (Int, Int) -> Unit,
        onDone: (Int) -> Unit
    ) {
        if (running) return
        running = true
        Thread {
            // Se prueba cada link (principal y respaldos), sin repetir.
            val targets = list.flatMap { it.sources }.distinctBy { it.url }
            val total = targets.size
            val done = AtomicInteger(0)
            val newDead = Collections.synchronizedSet(HashSet<String>())
            val pool = Executors.newFixedThreadPool(8)
            val latch = CountDownLatch(total)
            for (src in targets) {
                pool.execute {
                    try {
                        if (!probe(src)) newDead.add(key(src.url))
                    } catch (t: Throwable) {
                        newDead.add(key(src.url))
                    } finally {
                        onProgress(done.incrementAndGet(), total)
                        latch.countDown()
                    }
                }
            }
            latch.await()
            pool.shutdown()
            running = false
            if (total > 0 && newDead.size == total) {
                onDone(-1) // todos fallaron: casi seguro no hay internet; no guardamos nada
            } else {
                val snapshot = HashSet(newDead)
                synchronized(dead) {
                    dead.clear()
                    dead.addAll(snapshot)
                }
                repo.setDeadUrls(snapshot)
                onDone(list.count { isDead(it) }) // canales sin NINGUNA señal que ande
            }
        }.start()
    }

    private fun probe(ch: Source): Boolean {
        val conn = URL(ch.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 7000
        conn.setRequestProperty(
            "User-Agent",
            ch.userAgent
                ?: "Mozilla/5.0 (Linux; Android 11; Google TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        )
        ch.referer?.let { conn.setRequestProperty("Referer", it) }
        try {
            val code = conn.responseCode
            if (code in 300..399) return true // redirección que Android no sigue sola: el servidor responde
            if (code !in 200..299) return false
            val type = (conn.contentType ?: "").lowercase()
            val buf = ByteArray(1024)
            val n = conn.inputStream.read(buf)
            if (n <= 0) return false
            val head = String(buf, 0, n, Charsets.ISO_8859_1)
            return head.startsWith("#EXTM3U") ||
                buf[0] == 0x47.toByte() ||
                type.contains("mpegurl") ||
                type.startsWith("video") ||
                type.contains("octet-stream") ||
                type.contains("mp2t")
        } finally {
            conn.disconnect()
        }
    }
}
