package com.mistv.canales

import android.util.Log
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnóstico simple de reproducción: guarda líneas en Logcat (tag "IPTV") y en un archivo
 * chico (files/diagnostico.log, máx ~200 KB). Nunca guarda usuario, clave, token ni la ruta
 * completa del link: solo "host:puerto".
 */
object StreamLog {
    private const val TAG = "IPTV"
    private const val MAX_BYTES = 200_000L
    private var file: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(dir: File) {
        file = File(dir, "diagnostico.log")
    }

    /** Devuelve solo host:puerto (sin credenciales, ruta ni parámetros). */
    fun safeHost(url: String): String = try {
        val u = URI(url.trim())
        val h = u.host ?: return "?"
        if (u.port > 0) "$h:${u.port}" else h
    } catch (_: Throwable) {
        "?"
    }

    @Synchronized
    fun log(msg: String) {
        Log.i(TAG, msg)
        val f = file ?: return
        try {
            if (f.exists() && f.length() > MAX_BYTES) {
                // Se queda con la mitad más reciente.
                val txt = f.readText()
                f.writeText(txt.substring(txt.length / 2).substringAfter('\n'))
            }
            f.appendText("${fmt.format(Date())}  $msg\n")
        } catch (_: Throwable) {
        }
    }

    /**
     * Política de reintentos HLS: ante errores de red o de segmento reintenta más veces (6 en vez de 3)
     * con espera creciente (1, 2, 3, 4, 5, 5 s) sin reiniciar el reproductor. Si se agotan,
     * ExoPlayer lanza el error y entra la lógica de reconexión/respaldo de siempre.
     */
    class HlsRetryPolicy(private val host: () -> String) : DefaultLoadErrorHandlingPolicy(6) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val delay = super.getRetryDelayMsFor(loadErrorInfo)
            if (delay != androidx.media3.common.C.TIME_UNSET) {
                log(
                    "Error de carga HLS en ${host()} (intento ${loadErrorInfo.errorCount}/6): " +
                        "${loadErrorInfo.exception.javaClass.simpleName}: ${loadErrorInfo.exception.message?.take(80)} " +
                        "-> reintento en ${delay} ms"
                )
            }
            return delay
        }
    }
}
