package com.mistv.canales

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Código de acceso por equipo. Hay dos tipos:
 *  - permanente: se pide una sola vez y no vence.
 *  - mensual: uno distinto por mes; sirve solo durante su mes y después la app lo vuelve a pedir.
 * Solo se comparan huellas (hash) guardadas en Config; los códigos no están en la app.
 */
object AccessGate {
    private const val PREFS = "access"
    private const val KEY_OK = "ok"
    private const val KEY_UNTIL = "until"
    private const val KEY_MAXSEEN = "max_seen"
    private const val KEY_FAILS = "fails"
    private const val KEY_LOCK_UNTIL = "lock_until"
    private const val KEY_URL = "list_url"

    // Tiene que ser igual a ROUNDS en tools/generar_codigo.py
    private const val ROUNDS = 100_000

    /** Mayúsculas y solo letras/números: "xpqeq-88l3f" y "XPQEQ88L3F" valen lo mismo. */
    fun normalize(code: String): String =
        code.uppercase(Locale.ROOT).filter { (it in 'A'..'Z') || (it in '0'..'9') }

    fun hash(code: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        var h = (Config.ACCESS_SALT + normalize(code)).toByteArray(Charsets.UTF_8)
        repeat(ROUNDS) { h = md.digest(h) }
        return h.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Hora que se usa para los códigos mensuales: nunca retrocede. Si alguien atrasa el reloj
     * del equipo para reutilizar un código viejo, se sigue usando la fecha más alta que la app ya vio.
     */
    private fun trustedNow(ctx: Context): Long {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val seen = p.getLong(KEY_MAXSEEN, 0L)
        val t = maxOf(now, seen)
        if (t - seen > 60_000L) p.edit().putLong(KEY_MAXSEEN, t).apply()
        return t
    }

    private fun monthKey(t: Long): String {
        val c = Calendar.getInstance()
        c.timeInMillis = t
        return String.format(Locale.ROOT, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }

    private fun startOfNextMonth(t: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = t
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.MONTH, 1)
        return c.timeInMillis
    }

    private fun monthlyHash(key: String): String? =
        Config.MONTHLY_HASHES.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(':')

    /** Permanente (si se cambia el código en Config, se vuelve a pedir) o mensual todavía vigente. */
    fun isUnlocked(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (p.getString(KEY_OK, "") == Config.ACCESS_HASH) return true
        val until = p.getLong(KEY_UNTIL, 0L)
        return until > 0L && until > trustedNow(ctx)
    }

    /** True si este equipo usó un código mensual (para avisar "venció" cuando se acaba el mes). */
    fun hadMonthly(ctx: Context): Boolean = prefs(ctx).getLong(KEY_UNTIL, 0L) > 0L

    // ───────── Dirección de la lista, cifrada con una llave que sale del código ─────────

    private fun deriveKey(code: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        var h = ("K|" + Config.ACCESS_SALT + normalize(code)).toByteArray(Charsets.UTF_8)
        repeat(ROUNDS) { h = md.digest(h) }
        return h
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(data)
    }

    private fun blobFor(label: String): String? =
        Config.LIST_BLOBS.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$label:") }
            ?.substringAfter(':')

    /** nonce(16) + datos cifrados + etiqueta HMAC(32). Devuelve null si el código no corresponde. */
    private fun decrypt(master: ByteArray, b64: String): String? {
        try {
            val raw = Base64.decode(b64, Base64.DEFAULT)
            if (raw.size < 16 + 32) return null
            val nonce = raw.copyOfRange(0, 16)
            val tag = raw.copyOfRange(raw.size - 32, raw.size)
            val ct = raw.copyOfRange(16, raw.size - 32)
            val ek = hmac(master, "enc".toByteArray(Charsets.UTF_8))
            val mk = hmac(master, "mac".toByteArray(Charsets.UTF_8))
            if (!MessageDigest.isEqual(hmac(mk, nonce + ct), tag)) return null
            val out = ByteArray(ct.size)
            var block = 0
            var pos = 0
            while (pos < ct.size) {
                val counter = byteArrayOf(
                    (block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()
                )
                val ks = hmac(ek, nonce + counter)
                var j = 0
                while (j < ks.size && pos < ct.size) {
                    out[pos] = (ct[pos].toInt() xor ks[j].toInt()).toByte()
                    pos++
                    j++
                }
                block++
            }
            return String(out, Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
    }

    private fun storeUrl(p: SharedPreferences, label: String, code: String) {
        val blob = blobFor(label) ?: return
        val url = decrypt(deriveKey(code), blob) ?: return
        p.edit().putString(KEY_URL, url).apply()
    }

    /** Dirección de la lista que se abrió con el código (vacía si todavía no se desbloqueó). */
    fun listUrl(ctx: Context): String = prefs(ctx).getString(KEY_URL, "") ?: ""

    /** Segundos que faltan para poder volver a intentar (0 = se puede). */
    fun lockedSeconds(ctx: Context): Long {
        val left = prefs(ctx).getLong(KEY_LOCK_UNTIL, 0L) - System.currentTimeMillis()
        return if (left <= 0) 0L else (left + 999) / 1000
    }

    /** Verifica el código. Tarda un instante a propósito: llamalo fuera del hilo principal. */
    fun tryUnlock(ctx: Context, input: String): Boolean {
        if (lockedSeconds(ctx) > 0) return false
        val typed = hash(input).toByteArray(Charsets.UTF_8)
        val now = trustedNow(ctx)
        val permanent = MessageDigest.isEqual(typed, Config.ACCESS_HASH.toByteArray(Charsets.UTF_8))
        val mh = monthlyHash(monthKey(now))
        val monthly = mh != null && MessageDigest.isEqual(typed, mh.toByteArray(Charsets.UTF_8))
        val p = prefs(ctx)
        if (permanent) {
            p.edit().putString(KEY_OK, Config.ACCESS_HASH).remove(KEY_UNTIL)
                .putInt(KEY_FAILS, 0).remove(KEY_LOCK_UNTIL).apply()
            storeUrl(p, "perm", input)
            return true
        }
        if (monthly) {
            p.edit().putLong(KEY_UNTIL, startOfNextMonth(now))
                .putInt(KEY_FAILS, 0).remove(KEY_LOCK_UNTIL).apply()
            storeUrl(p, monthKey(now), input)
            return true
        }
        val fails = p.getInt(KEY_FAILS, 0) + 1
        val e = p.edit().putInt(KEY_FAILS, fails)
        if (fails % 5 == 0) {
            // cada 5 errores seguidos: espera de 1 min, 2 min, 4 min... (máximo 1 hora)
            val secs = minOf(3600L, 30L * (1L shl minOf(fails / 5, 10)))
            e.putLong(KEY_LOCK_UNTIL, System.currentTimeMillis() + secs * 1000)
        }
        e.apply()
        return false
    }
}
