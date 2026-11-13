package com.myhouse.app

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The hidden remote link (N6). Away from home the app writes an encrypted request into a Firebase
 * Realtime Database; the device picks it up, runs it against its own web server and writes the
 * encrypted answer back. The settings are fetched by the app itself from the device while it is
 * connected at home (GET /api/fbcfg), so family phones never type anything.
 */
class Remote(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("mhr", 0)
    private val rnd = SecureRandom()

    @Volatile private var idToken = ""
    @Volatile private var tokenExp = 0L

    private val url: String get() = prefs.getString("url", "") ?: ""
    private val apiKey: String get() = prefs.getString("key", "") ?: ""
    private val hid: String get() = prefs.getString("hid", "") ?: ""
    private val hk: String get() = prefs.getString("hk", "") ?: ""

    val ready: Boolean
        get() = url.startsWith("https://") && apiKey.length >= 10 && hid.length == 24 && hk.length == 64

    /** Saves (or clears) what the device sent in /api/fbcfg. */
    fun store(j: JSONObject) {
        if (j.optBoolean("en") && j.optString("url").startsWith("https://")) {
            prefs.edit()
                .putString("url", j.optString("url"))
                .putString("key", j.optString("key"))
                .putString("hid", j.optString("hid"))
                .putString("hk", j.optString("hk"))
                .apply()
        } else {
            prefs.edit().remove("url").remove("key").remove("hid").remove("hk").apply()
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun http(method: String, u: String, body: String?, ctype: String = "application/json", timeout: Int = 8000): Pair<Int, String> {
        val c = URL(u).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.setRequestProperty("Content-Type", ctype)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Pair(code, text)
        } finally {
            c.disconnect()
        }
    }

    private fun take(id: String, refresh: String, expires: String) {
        idToken = id
        val s = expires.toLongOrNull() ?: 3600L
        tokenExp = System.currentTimeMillis() + (if (s > 400) s - 300 else s) * 1000L
        if (refresh.isNotEmpty()) prefs.edit().putString("rt", refresh).apply()
    }

    /** Anonymous Firebase sign-in, renewed when needed. */
    @Synchronized
    private fun auth(): String {
        if (idToken.isNotEmpty() && System.currentTimeMillis() < tokenExp) return idToken
        val rt = prefs.getString("rt", "") ?: ""
        if (rt.isNotEmpty()) {
            try {
                val r = http("POST", "https://securetoken.googleapis.com/v1/token?key=$apiKey",
                    "grant_type=refresh_token&refresh_token=$rt", "application/x-www-form-urlencoded")
                if (r.first == 200) {
                    val j = JSONObject(r.second)
                    take(j.optString("id_token"), j.optString("refresh_token"), j.optString("expires_in", "3600"))
                    if (idToken.isNotEmpty()) return idToken
                }
            } catch (e: Exception) {
            }
        }
        val r = http("POST", "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$apiKey", "{\"returnSecureToken\":true}")
        if (r.first != 200) throw IOException("auth " + r.first)
        val j = JSONObject(r.second)
        take(j.optString("idToken"), j.optString("refreshToken"), j.optString("expiresIn", "3600"))
        if (idToken.isEmpty()) throw IOException("auth")
        return idToken
    }

    private fun keySpec(): SecretKeySpec {
        val h = hk
        val b = ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        return SecretKeySpec(b, "AES")
    }

    /** base64( iv(12) | ciphertext | tag(16) ), the same layout the device uses. */
    private fun encrypt(plain: String): String {
        val iv = ByteArray(12)
        rnd.nextBytes(iv)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keySpec(), GCMParameterSpec(128, iv))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(b64: String): String {
        val raw = Base64.decode(b64, Base64.DEFAULT)
        if (raw.size < 29) throw IOException("short")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keySpec(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    // ---------------------------------------------------------------- the request

    /** Same shape as Api.call: the answer JSON with "_s" = the device's HTTP status. Throws when nothing came back. */
    suspend fun call(path: String, method: String, body: JSONObject?, token: String, timeoutMs: Int = 12000): JSONObject =
        withContext(Dispatchers.IO) {
            if (!ready) throw IOException("not set")
            val tk = auth()
            val tag = ByteArray(4)
            rnd.nextBytes(tag)
            val id = (System.currentTimeMillis() / 1000).toString() + "-" + tag.joinToString("") { "%02x".format(it) }
            val plain = JSONObject()
                .put("m", method)
                .put("p", path)
                .put("b", body?.toString() ?: "")
                .put("k", token)
                .put("t", System.currentTimeMillis() / 1000)
                .toString()
            val base = "$url/houses/$hid"
            val put = http("PUT", "$base/req/$id.json?auth=$tk", JSONObject().put("d", encrypt(plain)).toString())
            if (put.first !in 200..299) {
                if (put.first == 401 || put.first == 403) idToken = ""
                throw IOException("put " + put.first)
            }
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                delay(350)
                val g = http("GET", "$base/res/$id.json?auth=$tk", null, timeout = 6000)
                if (g.first == 200 && g.second.isNotBlank() && g.second.trim() != "null") {
                    val r = JSONObject(decrypt(JSONObject(g.second).optString("d")))
                    try { http("DELETE", "$base/res/$id.json?auth=$tk", null) } catch (e: Exception) { }
                    val out = try { JSONObject(r.optString("b")) } catch (e: Exception) { JSONObject() }
                    out.put("_s", r.optInt("c", 0))
                    return@withContext out
                }
            }
            try { http("DELETE", "$base/req/$id.json?auth=$tk", null) } catch (e: Exception) { }
            throw IOException("timeout")
        }

    // ---------------------------------------------------------------- live sync

    /** Calls onChange each time the device says something changed (lights, switches, schedules...). */
    suspend fun watchRev(onChange: () -> Unit) = withContext(Dispatchers.IO) {
        var last = ""
        while (isActive) {
            try {
                if (!ready) { delay(5000); continue }
                val tk = auth()
                val c = URL("$url/houses/$hid/rev.json?auth=$tk").openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "text/event-stream")
                c.connectTimeout = 8000
                c.readTimeout = 70000
                try {
                    if (c.responseCode != 200) {
                        if (c.responseCode == 401 || c.responseCode == 403) idToken = ""
                    } else {
                        val r = c.inputStream.bufferedReader(Charsets.UTF_8)
                        var ev = ""
                        while (isActive) {
                            val line = r.readLine() ?: break
                            if (line.startsWith("event:")) ev = line.substring(6).trim()
                            else if (line.startsWith("data:") && (ev == "put" || ev == "patch")) {
                                val d = line.substring(5).trim()
                                if (d != last) {
                                    if (last.isNotEmpty()) onChange()
                                    last = d
                                }
                            }
                        }
                    }
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
            }
            delay(3000)
        }
    }
}
