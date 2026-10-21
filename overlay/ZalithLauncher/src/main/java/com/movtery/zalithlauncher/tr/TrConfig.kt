package com.movtery.zalithlauncher.tr

/**
 * Configuración central de T.R Launcher (Android). Es el equivalente de src/config.js del launcher de PC.
 * Edita aquí los datos de tu servidor y vuelve a compilar.
 */
object TrConfig {
    /** Nombre que se muestra en la aplicación. */
    const val NAME = "T.R Launcher"

    /**
     * Dirección del servidor de cuentas (el mismo Worker de Cloudflare que usa el launcher de PC),
     * por ejemplo "https://tr-launcher-api.tuusuario.workers.dev". Sin barra final.
     */
    const val API_URL = "https://tr-launcher-api.tintino12.workers.dev"

    /** Única versión de Minecraft disponible. */
    const val MC_VERSION = "1.21.11"

    /** Cargador Fabric que se instala junto con la versión. */
    const val FABRIC_LOADER = "0.19.5"

    /** Nombre de la versión instalada dentro del launcher. */
    const val VERSION_NAME = "T.R $MC_VERSION"

    const val DISCORD_URL = "https://discord.gg/Fymufuwt6"

    /** Longitud de las contraseñas (igual que en PC). */
    const val PW_MIN = 6
    const val PW_MAX = 64

    data class Server(val name: String, val host: String, val port: Int) {
        val address: String get() = "$host:$port"
    }

    val SERVERS = listOf(
        Server("Nunca Jamás", "NuncaJamasbyTicoCR_.aternos.me", 64008),
        Server("Nunca Creativo", "NuncaCreativoTR.aternos.me", 14689),
    )

    val apiEnabled: Boolean get() = API_URL.trim().startsWith("https://")
    val apiBase: String get() = API_URL.trim().trimEnd('/')
}
