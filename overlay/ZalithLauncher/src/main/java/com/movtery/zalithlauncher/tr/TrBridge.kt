package com.movtery.zalithlauncher.tr

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.AccountsManager
import com.movtery.zalithlauncher.game.account.accountUUID
import com.movtery.zalithlauncher.game.account.getUUIDFromUserName
import com.movtery.zalithlauncher.game.account.isMicrosoftAccount
import com.movtery.zalithlauncher.game.account.wardrobe.SkinModelType
import com.movtery.zalithlauncher.game.account.wardrobe.getLocalUUIDWithSkinModel
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "TrBridge"
private val NAME_RE = Regex("^[A-Za-z0-9_]{3,16}$")

/**
 * Puente entre la pantalla web de T.R (assets/tr-ui, la misma interfaz del launcher de PC adaptada al teléfono)
 * y el motor de Android. El JavaScript llama a [invoke] con un nombre de método y recibe la respuesta con
 * window.__trDone(id, json). Los eventos (progreso de instalación…) llegan con window.__trEmit(nombre, json).
 */
class TrBridge(
    private val context: Context,
    private val webView: WebView,
    private val scope: CoroutineScope,
    private val onLaunch: (version: Version, serverAddress: String?) -> Unit,
    private val onEngineSettings: () -> Unit,
) {
    private val prefs = context.getSharedPreferences("tr_launcher", Context.MODE_PRIVATE)

    /* ---------- Entrada desde JavaScript ---------- */

    @JavascriptInterface
    fun invoke(id: Int, method: String, argsJson: String) {
        scope.launch(Dispatchers.IO) {
            val out: JSONObject = try {
                handle(method, runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject()))
            } catch (e: TrApiException) {
                fail(e.message ?: "Error del servidor de cuentas.", e.code)
            } catch (e: Exception) {
                Logger.error(TAG, "Fallo en $method", e)
                fail(e.message ?: "Error desconocido.")
            }
            webView.post { webView.evaluateJavascript("window.__trDone($id,${JSONObject.quote(out.toString())})", null) }
        }
    }

    private fun emit(name: String, data: JSONObject) {
        webView.post { webView.evaluateJavascript("window.__trEmit('$name',${JSONObject.quote(data.toString())})", null) }
    }

    private fun ok(block: JSONObject.() -> Unit = {}) = JSONObject().put("ok", true).apply(block)
    private fun fail(msg: String, code: String? = null) = JSONObject().put("ok", false).put("error", msg).apply { if (code != null) put("code", code) }

    /* ---------- Estado ---------- */

    private fun isInstalled() = VersionsManager.versions.value.any { it.getVersionName() == TrConfig.VERSION_NAME }
    private fun installedVersion(): Version? = VersionsManager.versions.value.firstOrNull { it.getVersionName() == TrConfig.VERSION_NAME }

    private fun accounts(): List<Account> = AccountsManager.accountsFlow.value
    private fun active(): Account? = AccountsManager.currentAccountFlow.value
    private fun byId(id: String?): Account? = accounts().firstOrNull { it.uniqueUUID == id }
    private fun isTr(a: Account) = a.isTrAccount()

    private fun roleOf(a: Account) = prefs.getString("role." + a.uniqueUUID, "player") ?: "player"
    private fun expired(a: Account) = prefs.getBoolean("expired." + a.uniqueUUID, false)
    private fun rank(role: String) = when (role) { "owner" -> 3; "admin" -> 2; "staff" -> 1; else -> 0 }

    private fun accJson(a: Account): JSONObject = JSONObject()
        .put("id", a.uniqueUUID)
        .put("type", when { isTr(a) -> "tr"; a.isMicrosoftAccount() -> "microsoft"; else -> "offline" })
        .put("name", a.username)
        .put("uuid", runCatching { accountUUID(getUUIDFromUserName(a.username)) }.getOrDefault(""))
        .put("role", if (isTr(a)) roleOf(a) else "player")
        .put("variant", if (a.skinModelType == SkinModelType.ALEX) "slim" else "classic")
        .put("skinId", JSONObject.NULL)
        .put("hasPassword", false)
        .put("locked", false)
        .put("expired", isTr(a) && expired(a))

    private fun listJson(exceptId: String? = null): JSONObject {
        val arr = JSONArray()
        accounts().filter { it.uniqueUUID != exceptId }.forEach { arr.put(accJson(it)) }
        val act = active()?.takeIf { it.uniqueUUID != exceptId }
        return JSONObject().put("ok", true).put("active", act?.uniqueUUID ?: JSONObject.NULL).put("accounts", arr)
    }

    private fun staffJson(): JSONObject {
        val a = active()
        val role = if (a != null && isTr(a)) roleOf(a) else "player"
        val allowed = rank(role) >= 1 && !expired(a ?: return JSONObject().put("allowed", false).put("installed", false))
        return JSONObject().put("allowed", allowed).put("role", if (allowed) role else JSONObject.NULL).put("installed", false)
    }

    /** Pregunta al servidor el rol actual y si la sesión sigue válida. */
    private suspend fun refreshActive() {
        val a = active() ?: return
        if (!isTr(a) || !TrConfig.apiEnabled) return
        try {
            val (name, role) = TrApi.me(a.trToken, 5)
            prefs.edit().putString("role." + a.uniqueUUID, role).putBoolean("expired." + a.uniqueUUID, false).apply()
            if (name.isNotBlank() && name != a.username) {
                a.username = name
                AccountsManager.suspendSaveAccount(a)
            }
        } catch (e: TrApiException) {
            if (e.code == "session") prefs.edit().putBoolean("expired." + a.uniqueUUID, true).apply()
        } catch (_: Exception) {
        }
    }

    /* ---------- Métodos ---------- */

    private suspend fun handle(method: String, a: JSONObject): JSONObject = when (method) {
        "info" -> {
            refreshActive()
            val mem = ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
            val ram = (AllSettings.ramAllocation.getValue() ?: 2048)
            ok {
                put("appVersion", "1.0-android")
                put("totalMemMb", (mem.totalMem / (1024 * 1024)).toInt())
                put("ramMaxMb", ram)
                put("installed", isInstalled())
                put("running", false)
                put("installDir", "Almacenamiento de la app (se descarga una sola vez)")
                put("staff", staffJson())
                put("apiEnabled", TrConfig.apiEnabled)
            }
        }
        "settings.save" -> {
            val ram = a.optInt("ramMaxMb", 0)
            if (ram >= 256) runCatching { AllSettings.ramAllocation.save(ram) }
            ok()
        }
        "install" -> install()
        "staff.state" -> { refreshActive(); ok { put("staff", staffJson()) } }
        "staff.cmd" -> {
            val acc = active()
            if (acc == null || !isTr(acc)) fail("Inicia sesión con tu cuenta T.R para usar los comandos.")
            else try {
                val lines = TrApi.command(acc.trToken, a.optString("text"))
                ok { put("lines", JSONArray(lines)); put("failed", false) }
            } catch (e: TrApiException) {
                JSONObject().put("ok", false).put("failed", true).put("error", e.message ?: "Error")
            }
        }
        "copy" -> {
            val text = a.optString("text")
            withContext(Dispatchers.Main) {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("servidor", text))
            }
            ok()
        }
        "open" -> {
            val url = when (a.optString("key")) { "discord" -> TrConfig.DISCORD_URL; else -> a.optString("url") }
            if (url.startsWith("https://")) {
                withContext(Dispatchers.Main) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                ok()
            } else fail("Enlace no válido.")
        }
        "engineSettings" -> { withContext(Dispatchers.Main) { onEngineSettings() }; ok() }

        "accounts.list" -> { refreshActive(); listJson() }
        "accounts.register" -> {
            checkName(a.optString("name")); checkPass(a.optString("password"))
            val s = TrApi.register(a.optString("name").trim(), a.optString("password"), a.optString("claim").takeIf { it.isNotBlank() })
            afterAuth(s)
        }
        "accounts.login" -> {
            checkName(a.optString("name"))
            afterAuth(TrApi.login(a.optString("name").trim(), a.optString("password")))
        }
        "accounts.recover" -> {
            checkName(a.optString("name")); checkPass(a.optString("password"))
            afterAuth(TrApi.recover(a.optString("name").trim(), a.optString("code").trim(), a.optString("password")))
        }
        "accounts.addOffline" -> fail("Ahora las cuentas se crean en el servidor para que nadie repita tu nombre. Usa «Crear cuenta T.R».")
        "accounts.addMicrosoft" -> fail("Para entrar con Microsoft abre «Ajustes avanzados del motor».")
        "accounts.setActive" -> {
            val acc = byId(a.optString("id")) ?: return fail("Cuenta no encontrada.")
            AccountsManager.setCurrentAccount(acc)
            refreshActive()
            listJson().put("staff", staffJson())
        }
        "accounts.remove" -> {
            val acc = byId(a.optString("id")) ?: return fail("Cuenta no encontrada.")
            if (isTr(acc)) TrApi.logout(acc.trToken)   // cierra la sesión en el servidor (la cuenta sigue existiendo)
            AccountsManager.deleteAccount(acc)
            delay(250)
            listJson(exceptId = acc.uniqueUUID).put("staff", staffJson())
        }
        "accounts.setPassword" -> {
            val acc = byId(a.optString("id")) ?: return fail("Cuenta no encontrada.")
            if (!isTr(acc)) return fail("Solo las cuentas T.R tienen contraseña.")
            checkPass(a.optString("newPassword"))
            TrApi.changePassword(acc.trToken, acc.username, a.optString("oldPassword"), a.optString("newPassword"))
            listJson()
        }
        "accounts.rename" -> {
            val acc = byId(a.optString("id")) ?: return fail("Cuenta no encontrada.")
            checkName(a.optString("name"))
            if (!isTr(acc)) return fail("Solo las cuentas T.R se pueden renombrar desde aquí.")
            val approved = TrApi.rename(acc.trToken, a.optString("name").trim())
            acc.username = approved
            acc.profileId = getLocalUUIDWithSkinModel(approved, acc.skinModelType)
            AccountsManager.suspendSaveAccount(acc)
            listJson().put("account", accJson(acc))
        }

        "skin.set" -> setSkin(a)
        "skin.current" -> {
            val acc = byId(a.optString("id"))
            if (acc == null) ok { put("skin", JSONObject.NULL) }
            else {
                if (!acc.getSkinFile().exists() && isTr(acc)) runCatching { downloadOwnSkin(acc) }
                val f = acc.getSkinFile()
                ok { put("skin", if (f.exists()) "data:image/png;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP) else JSONObject.NULL) }
            }
        }
        "skin.player" -> {
            val name = a.optString("name").trim()
            if (!NAME_RE.matches(name)) return fail("Escribe un nombre de jugador válido.")
            val (hash, slim) = TrApi.skinOf(name) ?: return fail("Ese jugador no tiene skin en T.R.")
            val png = TrApi.texture(hash)
            setSkin(JSONObject().put("id", a.optString("id")).put("variant", if (slim) "slim" else "classic")
                .put("dataUrl", "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)))
        }
        "savePng" -> savePng(a.optString("dataUrl"))

        "launch" -> launch(a)
        else -> fail("Función no disponible: $method")
    }

    private fun checkName(n: String) {
        if (!NAME_RE.matches(n.trim())) throw TrApiException("El nombre debe tener de 3 a 16 caracteres: letras, números o guion bajo.")
    }

    private fun checkPass(p: String) {
        if (p.length < TrConfig.PW_MIN || p.length > TrConfig.PW_MAX)
            throw TrApiException("La contraseña debe tener entre ${TrConfig.PW_MIN} y ${TrConfig.PW_MAX} caracteres.")
    }

    private suspend fun afterAuth(s: TrSession): JSONObject {
        val acc = saveTrAccount(s)
        prefs.edit().putString("role." + acc.uniqueUUID, s.role).putBoolean("expired." + acc.uniqueUUID, false).apply()
        return listJson().put("account", accJson(acc)).put("staff", staffJson())
    }

    /* ---------- Skins ---------- */

    private suspend fun setSkin(a: JSONObject): JSONObject {
        val acc = byId(a.optString("id")) ?: return fail("Cuenta no encontrada.")
        val dataUrl = a.optString("dataUrl")
        val m = Regex("^data:image/png;base64,([A-Za-z0-9+/=]+)$").find(dataUrl) ?: return fail("Imagen no válida.")
        val bytes = Base64.decode(m.groupValues[1], Base64.DEFAULT)
        if (bytes.size > 100 * 1024) return fail("La skin pesa demasiado (máximo 100 KB).")
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth != 64 || (opts.outHeight != 64 && opts.outHeight != 32))
            return fail("La skin debe medir 64x64 (o 64x32). La tuya mide ${opts.outWidth}x${opts.outHeight}.")
        val slim = a.optString("variant") == "slim"
        withContext(Dispatchers.IO) {
            acc.getSkinFile().apply { parentFile?.mkdirs(); writeBytes(bytes) }
            acc.skinModelType = if (slim) SkinModelType.ALEX else SkinModelType.STEVE
            acc.profileId = getLocalUUIDWithSkinModel(acc.username, acc.skinModelType)
        }
        AccountsManager.suspendSaveAccount(acc)
        AccountsManager.refreshWardrobe()
        val problem = uploadOwnSkin(acc)
        return ok {
            put("skin", dataUrl)
            put("account", accJson(acc))
            put("note", if (problem == null) (if (isTr(acc)) "Skin aplicada: ya la ven todos los jugadores." else "Skin aplicada.")
            else "La skin se guardó en tu teléfono, pero no se pudo subir al servidor: $problem")
        }
    }

    private fun savePng(dataUrl: String): JSONObject {
        val m = Regex("^data:image/png;base64,([A-Za-z0-9+/=]+)$").find(dataUrl) ?: return fail("Imagen no válida.")
        val bytes = Base64.decode(m.groupValues[1], Base64.DEFAULT)
        val name = "mi-skin-${System.currentTimeMillis()}.png"
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/T.R Launcher")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) fail("No se pudo guardar la imagen.")
            else { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }; ok() }
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return fail("No se pudo guardar la imagen.")
            dir.mkdirs(); java.io.File(dir, name).writeBytes(bytes); ok()
        }
    }

    /* ---------- Instalar y jugar ---------- */

    private suspend fun install(): JSONObject {
        if (!isInstalled() && !TrInstaller.isRunning()) TrInstaller.start(context)
        if (!TrInstaller.isRunning() && isInstalled()) return ok()
        while (TrInstaller.isRunning()) {
            (TrInstaller.state.value as? TrInstallState.Running)?.let { s ->
                val pct = if (s.progress in 0f..1f) (s.progress * 1000).toInt() else 0
                emit("install", JSONObject().put("stage", s.text).put("done", pct).put("total", if (s.progress in 0f..1f) 1000 else 0))
            }
            delay(400)
        }
        // La lista de versiones se actualiza un instante después de terminar
        repeat(10) { if (!isInstalled()) delay(300) }
        return when (val s = TrInstaller.state.value) {
            is TrInstallState.Failed -> fail(s.message)
            else -> if (isInstalled()) ok() else fail("La instalación se canceló.")
        }
    }

    private suspend fun launch(a: JSONObject): JSONObject {
        val acc = active() ?: return fail("Elige o crea una cuenta primero.")
        val version = installedVersion() ?: return fail("Primero instala el juego (botón «Descargar Minecraft»).")
        val join = a.optString("join").takeIf { it.isNotBlank() && it != "false" }
        val address = join?.let { id -> TrConfig.SERVERS.firstOrNull { idOf(it) == id }?.address }
        if (isTr(acc) && expired(acc)) {
            // Se puede jugar igual; solo avisamos de que la skin no se subirá hasta volver a entrar.
            emit("log", JSONObject().put("line", "Tu sesión T.R caducó: vuelve a iniciar sesión para subir skins."))
        }
        withContext(Dispatchers.Main) {
            VersionsManager.saveVersion(version)
            onLaunch(version, address)
        }
        return ok()
    }

    /** Identificadores de servidor que usa la interfaz (los mismos de config.js de PC). */
    private fun idOf(s: TrConfig.Server) = when (s.port) { 64008 -> "jamas"; 14689 -> "creativo"; else -> s.host }
}
