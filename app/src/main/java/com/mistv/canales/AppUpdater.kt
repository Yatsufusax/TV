package com.mistv.canales

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
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
                a.runOnUiThread {
                    if (remote > local) {
                        if (manual || remote != declined) ask(a, remote, local)
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

    private fun ask(a: Activity, remote: Long, local: Long) {
        if (a.isFinishing) return
        AlertDialog.Builder(a)
            .setTitle("Actualización disponible")
            .setMessage("Hay una versión nueva (v$remote). Tenés la v$local.\n\n¿Descargar e instalar?")
            .setPositiveButton("Actualizar") { _, _ -> download(a) }
            .setNegativeButton("Ahora no") { _, _ -> declined = remote }
            .show()
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
