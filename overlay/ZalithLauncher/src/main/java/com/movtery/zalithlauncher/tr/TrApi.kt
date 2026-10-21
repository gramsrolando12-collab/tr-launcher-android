package com.movtery.zalithlauncher.tr

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class TrApiException(message: String, val status: Int = 0, val code: String? = null) : Exception(message)

data class TrSession(val token: String, val name: String, val role: String)
data class TrSkinEntry(val name: String, val hash: String, val variant: String)

/**
 * Cliente del servidor de cuentas (el mismo que usa el launcher de PC).
 * La contraseña nunca sale del teléfono: aquí se convierte con scrypt y al servidor solo llega el resultado.
 */
object TrApi {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private fun networkError() = TrApiException(
        "No se pudo conectar con el servidor de cuentas. Revisa tu Internet e inténtalo de nuevo.", 0, "network"
    )

    /** Hace la petición y devuelve el cuerpo (bytes) y si fue JSON. Lanza TrApiException con el mensaje del servidor. */
    private fun call(method: String, path: String, body: JSONObject? = null, token: String? = null, timeoutSec: Long = 0): ByteArray {
        if (!TrConfig.apiEnabled) throw TrApiException("El servidor de cuentas no está configurado (API_URL en TrConfig.kt).", 0, "off")
        val builder = Request.Builder().url(TrConfig.apiBase + path).header("User-Agent", "TR-Launcher-Android/1.0")
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (method == "POST") builder.post((body ?: JSONObject()).toString().toRequestBody(JSON)) else builder.get()
        val http = if (timeoutSec > 0) client.newBuilder().callTimeout(timeoutSec, TimeUnit.SECONDS).build() else client
        try {
            http.newCall(builder.build()).execute().use { r ->
                val bytes = r.body?.bytes() ?: ByteArray(0)
                val isJson = (r.header("content-type") ?: "").contains("json")
                if (isJson) {
                    val j = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
                    if (!r.isSuccessful || j == null || (j.has("ok") && !j.optBoolean("ok", true))) {
                        throw TrApiException(j?.optString("error").takeUnless { it.isNullOrBlank() } ?: "Error ${r.code}", r.code, j?.optString("code")?.takeIf { it.isNotBlank() })
                    }
                } else if (!r.isSuccessful) {
                    throw TrApiException("El servidor de cuentas respondió con un error (${r.code}).", r.code)
                }
                return bytes
            }
        } catch (e: TrApiException) {
            throw e
        } catch (e: IOException) {
            throw networkError()
        }
    }

    private fun obj(bytes: ByteArray) = JSONObject(String(bytes, Charsets.UTF_8))

    private fun newSalt(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun scrypt(password: String, saltHex: String): String = TrScrypt.deriveHex(password, TrScrypt.hexToBytes(saltHex))

    private fun session(j: JSONObject): TrSession {
        val acc = j.getJSONObject("account")
        return TrSession(j.getString("token"), acc.getString("name"), acc.optString("role", "player"))
    }

    private suspend fun saltOf(name: String): String = withContext(Dispatchers.IO) {
        obj(call("GET", "/salt?name=" + java.net.URLEncoder.encode(name, "UTF-8"))).getString("salt")
    }

    suspend fun register(name: String, password: String, claim: String? = null): TrSession = withContext(Dispatchers.IO) {
        val salt = newSalt()
        val body = JSONObject().put("name", name).put("salt", salt).put("key", scrypt(password, salt))
        if (!claim.isNullOrBlank()) body.put("claim", claim)
        session(obj(call("POST", "/register", body)))
    }

    suspend fun login(name: String, password: String): TrSession = withContext(Dispatchers.IO) {
        val salt = saltOf(name)
        session(obj(call("POST", "/login", JSONObject().put("name", name).put("key", scrypt(password, salt)))))
    }

    /** Comprueba la sesión y devuelve (nombre, rol). Lanza TrApiException con code == "session" si caducó. */
    suspend fun me(token: String, timeoutSec: Long = 8): Pair<String, String> = withContext(Dispatchers.IO) {
        val j = obj(call("GET", "/me", null, token, timeoutSec))
        val acc = j.optJSONObject("account") ?: j
        acc.optString("name") to acc.optString("role", "player")
    }

    suspend fun logout(token: String) = withContext(Dispatchers.IO) {
        runCatching { call("POST", "/logout", JSONObject(), token) }
        Unit
    }

    suspend fun setSkin(token: String, png: ByteArray, slim: Boolean) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("png", Base64.encodeToString(png, Base64.NO_WRAP)).put("variant", if (slim) "slim" else "classic")
        call("POST", "/skin", body, token)
        Unit
    }

    suspend fun skinList(timeoutSec: Long = 8): List<TrSkinEntry> = withContext(Dispatchers.IO) {
        val arr = obj(call("GET", "/skins", null, null, timeoutSec)).optJSONArray("skins") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(TrSkinEntry(o.getString("name"), o.getString("hash"), o.optString("variant", "classic")))
            }
        }
    }

    suspend fun texture(hash: String, timeoutSec: Long = 10): ByteArray = withContext(Dispatchers.IO) {
        call("GET", "/csl/textures/$hash", null, null, timeoutSec)
    }

    suspend fun recover(name: String, code: String, newPassword: String): TrSession = withContext(Dispatchers.IO) {
        val salt = newSalt()
        session(obj(call("POST", "/recover", JSONObject().put("name", name).put("code", code).put("salt", salt).put("key", scrypt(newPassword, salt)))))
    }

    /** Cambia el nombre de la cuenta (el servidor comprueba que nadie más lo tenga). Devuelve el nombre aprobado. */
    suspend fun rename(token: String, name: String): String = withContext(Dispatchers.IO) {
        obj(call("POST", "/rename", JSONObject().put("name", name), token)).getJSONObject("account").getString("name")
    }

    suspend fun changePassword(token: String, name: String, oldPassword: String, newPassword: String) = withContext(Dispatchers.IO) {
        val newSalt = newSalt()
        val body = JSONObject().put("salt", newSalt)
            .put("oldKey", scrypt(oldPassword, saltOf(name)))
            .put("newKey", scrypt(newPassword, newSalt))
        call("POST", "/password", body, token)
        Unit
    }

    /** Panel de comandos (/hacer staff a X, /lista...): el servidor comprueba el rol. Devuelve las líneas de respuesta. */
    suspend fun command(token: String, text: String): List<String> = withContext(Dispatchers.IO) {
        val arr = obj(call("POST", "/cmd", JSONObject().put("text", text.take(200)), token)).optJSONArray("lines")
        if (arr == null) emptyList() else List(arr.length()) { arr.optString(it) }
    }

    /** Skin pública de un jugador: (hash, esSlim) o null si no tiene skin. */
    suspend fun skinOf(name: String): Pair<String, Boolean>? = withContext(Dispatchers.IO) {
        val bytes = try {
            call("GET", "/csl/" + java.net.URLEncoder.encode(name, "UTF-8") + ".json")
        } catch (e: TrApiException) {
            if (e.status == 404) return@withContext null else throw e
        }
        val skins = obj(bytes).optJSONObject("skins") ?: return@withContext null
        skins.optString("slim").takeIf { it.isNotBlank() }?.let { return@withContext it to true }
        skins.optString("default").takeIf { it.isNotBlank() }?.let { return@withContext it to false }
        null
    }
}
