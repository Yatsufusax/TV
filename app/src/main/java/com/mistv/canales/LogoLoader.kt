package com.mistv.canales

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Descarga y guarda los logos de los canales (memoria + carpeta de caché). */
object LogoLoader {
    private val cache = LruCache<String, Bitmap>(80)
    private val failed = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private var dir: File? = null

    fun init(ctx: Context) {
        dir = File(ctx.cacheDir, "logos").also { it.mkdirs() }
    }

    fun load(view: ImageView, url: String?) {
        view.tag = url
        if (url.isNullOrBlank()) {
            view.setImageDrawable(null)
            return
        }
        val hit = cache.get(url)
        if (hit != null) {
            view.setImageBitmap(hit)
            return
        }
        view.setImageDrawable(null)
        synchronized(failed) { if (failed.contains(url)) return }
        pool.execute {
            val bmp = fetch(url)
            if (bmp == null) {
                synchronized(failed) { failed.add(url) }
                return@execute
            }
            cache.put(url, bmp)
            main.post { if (view.tag == url) view.setImageBitmap(bmp) }
        }
    }

    private fun fetch(url: String): Bitmap? {
        try {
            val d = dir ?: return null
            val f = File(d, "${url.hashCode()}.img")
            if (!f.exists() || f.length() == 0L) {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                try {
                    if (conn.responseCode != 200) return null
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (bytes.isEmpty() || bytes.size > 3_000_000) return null
                    f.writeBytes(bytes)
                } finally {
                    conn.disconnect()
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 200 && bounds.outHeight / (sample * 2) >= 200) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(f.path, opts)
            if (bmp == null) f.delete()
            return bmp
        } catch (e: Exception) {
            return null
        }
    }
}
