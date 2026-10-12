package com.mistv.canales

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Busca una versión nueva de la app en GitHub Releases, la descarga y abre el instalador. */
object AppUpdater {
    @Volatile private var busy = false
    private var declined = 0L

    fun currentVersion(a: Activity): Long = try {
        PackageInfoCompat.getLongVersionCode(a.packageManager.getPackageInfo(a.packageName, 0))
    } catch (e: Exception) {
        0L
    }

    fun check(a: Activity, manual: Boolean) {
        if (Config.UPDATE_BASE_URL.isBlank()) {
            if (manual) toast(a, "Falta la dirección de actualizaciones de la app")
            return
        }
        if (busy) return
        busy = true
        if (manual) toast(a, "Buscando actualización…")
        Thread {
            try {
                val remote = fetchText(Config.UPDATE_BASE_URL + "version.txt").trim().toLong()
                val local = currentVersion(a)
                val news = if (remote > local) {
                    try { fetchText(Config.UPDATE_BASE_URL + "novedades.txt").trim() } catch (_: Exception) { "" }
                } else ""
                a.runOnUiThread {
                    if (remote > local) {
                        if (manual || remote != declined || isForced(news)) ask(a, remote, local, news)
                    } else if (manual) {
                        toast(a, "Ya tenés la última versión (v$local)")
                    }
                }
            } catch (ex: Exception) {
                if (manual) toast(a, "No se pudo buscar actualización: ${ex.message}")
            } finally {
                busy = false
            }
        }.start()
    }

    // Si la primera línea de NOVEDADES.txt es [OBLIGATORIA], la actualización no se puede posponer.
    private const val TAG_FORCED = "[OBLIGATORIA]"
    @Volatile private var dialogShowing = false

    private fun isForced(news: String): Boolean =
        news.trimStart().startsWith(TAG_FORCED, ignoreCase = true)

    private fun cleanNews(news: String): String =
        if (isForced(news)) news.trimStart().substring(TAG_FORCED.length).trim() else news.trim()

    @Volatile private var forcedPending = false

    private fun forcedMessage(remote: Long, local: Long, text: String, left: Int): String =
        "Hay una versión nueva (v$remote). Tenés la v$local." +
            (if (text.isNotBlank()) "\n\nNovedades:\n${text.take(1200)}" else "") +
            "\n\nHay que actualizar para seguir usando la app.\nSe cierra en $left segundos si no actualizás."

    private fun ask(a: Activity, remote: Long, local: Long, news: String) {
        if (a.isFinishing || dialogShowing) return
        val forced = isForced(news)
        val text = cleanNews(news)
        dialogShowing = true
        forcedPending = forced
        val cd = CenterDialog(a, if (forced) "Actualización obligatoria" else "Actualización disponible")
        if (forced) {
            var left = Config.FORCED_CLOSE_SECONDS
            val handler = Handler(Looper.getMainLooper())
            cd.setMessage(forcedMessage(remote, local, text, left))
            cd.setCancelable(false)
            cd.addButton("Salir", false) { a.finishAffinity() }
            cd.addButton("Actualizar ahora", true) { download(a) }
            val tick = object : Runnable {
                override fun run() {
                    if (!cd.isShowing) return
                    left--
                    if (left <= 0) {
                        // No actualizó a tiempo: se cierra la app.
                        cd.dismiss()
                        a.finishAffinity()
                        return
                    }
                    cd.setMessage(forcedMessage(remote, local, text, left))
                    handler.postDelayed(this, 1000)
                }
            }
            cd.setOnDismiss {
                dialogShowing = false
                handler.removeCallbacks(tick)
            }
            cd.show()
            handler.postDelayed(tick, 1000)
        } else {
            cd.setMessage(
                "Hay una versión nueva (v$remote). Tenés la v$local." +
                    (if (text.isNotBlank()) "\n\nNovedades:\n${text.take(1200)}" else "") +
                    "\n\n¿Descargar e instalar?"
            )
            cd.addButton("Ahora no", false) { declined = remote }
            cd.addButton("Actualizar", true) { download(a) }
            cd.setOnDismiss { dialogShowing = false }
            cd.show()
        }
    }

    private fun download(a: Activity) {
        if (busy) return
        busy = true
        toast(a, "Descargando actualización…")
        Thread {
            try {
                val dir = File(a.cacheDir, "updates")
                dir.mkdirs()
                val file = File(dir, "TVCanales.apk")
                val conn = URL(Config.UPDATE_BASE_URL + "TVCanales.apk").openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                try {
                    if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
                    conn.inputStream.use { input -> file.outputStream().use { out -> input.copyTo(out) } }
                } finally {
                    conn.disconnect()
                }
                a.runOnUiThread { install(a, file) }
            } catch (ex: Exception) {
                toast(a, "No se pudo descargar: ${ex.message}")
                // Si la actualización es obligatoria, vuelve a mostrar el aviso en vez de dejar seguir.
                if (forcedPending) Handler(Looper.getMainLooper()).postDelayed({ check(a, false) }, 3000)
            } finally {
                busy = false
            }
        }.start()
    }

    private fun install(a: Activity, file: File) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
                toast(a, "Activá \"Permitir de esta fuente\" y volvé a buscar la actualización")
                a.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}"))
                )
                return
            }
            val uri = FileProvider.getUriForFile(a, "${a.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            a.startActivity(intent)
        } catch (ex: Exception) {
            toast(a, "No se pudo abrir el instalador: ${ex.message}")
        }
    }

    private fun fetchText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun toast(a: Activity, s: String) =
        a.runOnUiThread { Toast.makeText(a, s, Toast.LENGTH_LONG).show() }
}
