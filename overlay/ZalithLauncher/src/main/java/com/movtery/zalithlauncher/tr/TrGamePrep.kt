package com.movtery.zalithlauncher.tr

import android.content.Context
import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionFolders
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "TrGamePrep"
private val NAME_OK = Regex("^[A-Za-z0-9_]{3,16}$")
private const val CSL_PROJECT = "idMHQ4n2"   // CustomSkinLoader en Modrinth (el mismo que usa el launcher de PC)

/**
 * Prepara la carpeta del juego justo antes de abrir Minecraft (equivale a lo que hace el launcher de PC):
 *  1) mod CustomSkinLoader (se descarga una vez) para ver las skins de todos en cualquier servidor,
 *  2) skins de todos los jugadores T.R en CustomSkinLoader/LocalSkin/skins/<Nombre>.png,
 *  3) el servidor de skins T.R en la lista del mod,
 *  4) idioma español (una vez) y paquete con las traducciones.
 * Nunca lanza errores: si algo falla (por ejemplo sin Internet) el juego se abre igualmente.
 */
object TrGamePrep {
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    suspend fun prepare(context: Context, version: Version, account: Account, log: (String) -> Unit = {}) {
        val gameDir = version.getGameDir()
        runCatching { writeLanguage(context, gameDir) }.onFailure { Logger.warning(TAG, "Idioma: ${it.message}") }
        runCatching { withTimeoutOrNull(40_000) { ensureSkinMod(gameDir, log) } }.onFailure { Logger.warning(TAG, "Mod de skins: ${it.message}") }
        runCatching { withTimeoutOrNull(25_000) { syncSkins(gameDir, log) } }.onFailure { Logger.warning(TAG, "Skins: ${it.message}") }
        runCatching { putOwnSkin(gameDir, account) }
        runCatching { patchConfig(gameDir) }.onFailure { Logger.warning(TAG, "Config del mod: ${it.message}") }
    }

    /* ---------- Idioma ---------- */

    private suspend fun writeLanguage(context: Context, gameDir: File) = withContext(Dispatchers.IO) {
        gameDir.mkdirs()
        val optionsFile = File(gameDir, "options.txt")
        val map = LinkedHashMap<String, String>()
        if (optionsFile.exists()) {
            optionsFile.readLines().filter { it.isNotBlank() }.forEach { line ->
                val i = line.indexOf(':')
                if (i > 0) map[line.substring(0, i)] = line.substring(i + 1)
            }
        }
        val mark = File(gameDir, "tr-lang.json")
        if (!mark.exists()) {
            map["lang"] = "es_es"
            mark.writeText("{\"lang\":\"es_es\"}")
        }
        // Paquete con las traducciones de los mods (el mismo de PC)
        val packsDir = File(gameDir, "resourcepacks").apply { mkdirs() }
        val dst = File(packsDir, "TR-Espanol.zip")
        context.assets.open("tr/TR-Espanol.zip").use { input ->
            val bytes = input.readBytes()
            if (!dst.exists() || dst.length() != bytes.size.toLong()) dst.writeBytes(bytes)
        }
        val packs = runCatching { JSONArray(map["resourcePacks"] ?: "[]") }.getOrDefault(JSONArray())
        val list = (0 until packs.length()).map { packs.getString(it) }.toMutableList()
        if ("vanilla" !in list) list.add(0, "vanilla")
        if ("file/TR-Espanol.zip" !in list) list.add("file/TR-Espanol.zip")
        map["resourcePacks"] = JSONArray(list).toString()
        optionsFile.writeText(map.entries.joinToString("\n") { "${it.key}:${it.value}" } + "\n")
    }

    /* ---------- Mod CustomSkinLoader ---------- */

    private suspend fun ensureSkinMod(gameDir: File, log: (String) -> Unit) = withContext(Dispatchers.IO) {
        val modsDir = VersionFolders.MOD.getDir(gameDir).apply { mkdirs() }
        if (modsDir.listFiles()?.any { it.name.startsWith("CustomSkinLoader", ignoreCase = true) && it.extension == "jar" } == true) return@withContext
        log("Descargando el mod de skins…")
        val url = "https://api.modrinth.com/v2/project/$CSL_PROJECT/version" +
            "?loaders=%5B%22fabric%22%5D&game_versions=%5B%22${TrConfig.MC_VERSION}%22%5D"
        val body = http.newCall(Request.Builder().url(url).header("User-Agent", "TR-Launcher-Android/1.0").build())
            .execute().use { if (!it.isSuccessful) error("Modrinth ${it.code}") else it.body!!.string() }
        val versions = JSONArray(body)
        if (versions.length() == 0) error("No hay CustomSkinLoader para ${TrConfig.MC_VERSION}")
        val files = versions.getJSONObject(0).getJSONArray("files")
        var file: JSONObject = files.getJSONObject(0)
        for (i in 0 until files.length()) if (files.getJSONObject(i).optBoolean("primary")) file = files.getJSONObject(i)
        val name = file.getString("filename").replace(Regex("[^A-Za-z0-9._+-]"), "_")
        val tmp = File(modsDir, "$name.part")
        http.newCall(Request.Builder().url(file.getString("url")).header("User-Agent", "TR-Launcher-Android/1.0").build())
            .execute().use { r ->
                if (!r.isSuccessful) error("Descarga ${r.code}")
                r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            }
        if (!tmp.renameTo(File(modsDir, name))) error("No se pudo guardar el mod")
    }

    /* ---------- Skins de todos los jugadores ---------- */

    private fun skinsDir(gameDir: File) = File(gameDir, "CustomSkinLoader/LocalSkin/skins")
    private fun indexFile(gameDir: File) = File(gameDir, "CustomSkinLoader/tr-skins.json")

    private suspend fun syncSkins(gameDir: File, log: (String) -> Unit) {
        if (!TrConfig.apiEnabled) return
        val list = try { TrApi.skinList(6) } catch (e: Exception) { log("Skins: no se pudo consultar el servidor"); return }
        val dir = skinsDir(gameDir).apply { mkdirs() }
        val idx = runCatching { JSONObject(indexFile(gameDir).readText()) }.getOrDefault(JSONObject())
        val next = JSONObject()
        val todo = list.filter { NAME_OK.matches(it.name) && Regex("^[0-9a-f]{64}$").matches(it.hash) }
        val sem = Semaphore(6)
        coroutineScope {
            todo.map { s ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        val dest = File(dir, s.name + ".png")
                        try {
                            if (idx.optString(s.name) == s.hash && dest.exists()) {
                                synchronized(next) { next.put(s.name, s.hash) }
                            } else {
                                dest.writeBytes(TrApi.texture(s.hash, 8))
                                synchronized(next) { next.put(s.name, s.hash) }
                            }
                        } catch (_: Exception) {
                            if (idx.has(s.name)) synchronized(next) { next.put(s.name, idx.getString(s.name)) }
                        }
                    }
                }
            }.awaitAll()
        }
        // Cuentas borradas: se quita su skin
        val keys = idx.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            if (!next.has(name) && todo.none { it.name == name }) File(dir, "$name.png").delete()
        }
        indexFile(gameDir).writeText(next.toString())
        log("Skins sincronizadas: ${next.length()} jugador(es)")
    }

    /** La skin local de la cuenta que juega (por si aún no se subió o no hay Internet). */
    private fun putOwnSkin(gameDir: File, account: Account) {
        if (!NAME_OK.matches(account.username) || !account.getSkinFile().exists()) return
        val dir = skinsDir(gameDir).apply { mkdirs() }
        account.getSkinFile().copyTo(File(dir, account.username + ".png"), overwrite = true)
    }

    /** Pone el servidor de skins T.R en la lista del mod (si el mod ya creó su archivo de configuración). */
    private fun patchConfig(gameDir: File) {
        if (!TrConfig.apiEnabled) return
        val f = File(gameDir, "CustomSkinLoader/CustomSkinLoader.json")
        if (!f.exists()) return
        val j = JSONObject(f.readText())
        val old = j.optJSONArray("loadlist") ?: return
        val rest = (0 until old.length()).map { old.getJSONObject(it) }.filter { it.optString("name") != "TR" }
        val local = rest.firstOrNull { it.optString("type") == "Legacy" && it.optString("name").contains("local", ignoreCase = true) }
        val mine = JSONObject().put("name", "TR").put("type", "CustomSkinAPI").put("root", TrConfig.apiBase + "/csl/")
        val out = JSONArray()
        local?.let { out.put(it) }
        out.put(mine)
        rest.filter { it !== local }.forEach { out.put(it) }
        if (out.toString() == old.toString()) return
        j.put("loadlist", out)
        f.writeText(j.toString(2))
    }
}
