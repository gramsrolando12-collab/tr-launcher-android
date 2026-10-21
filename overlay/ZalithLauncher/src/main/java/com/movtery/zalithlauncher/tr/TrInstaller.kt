package com.movtery.zalithlauncher.tr

import android.content.Context
import com.movtery.zalithlauncher.coroutine.TaskStage
import com.movtery.zalithlauncher.game.addons.modloader.fabriclike.fabric.FabricAPIVersions
import com.movtery.zalithlauncher.game.addons.modloader.fabriclike.fabric.FabricVersions
import com.movtery.zalithlauncher.game.download.game.GameDownloadInfo
import com.movtery.zalithlauncher.game.download.game.GameInstaller
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.ui.toAndroidString
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "TrInstaller"

sealed interface TrInstallState {
    data object Idle : TrInstallState
    data class Running(val text: String, val progress: Float) : TrInstallState
    data class Failed(val message: String) : TrInstallState
}

/**
 * Instala con un solo botón la única versión del launcher: Minecraft [TrConfig.MC_VERSION] + Fabric + Fabric API.
 * (CustomSkinLoader, el idioma y las skins se preparan justo antes de abrir el juego: ver [TrGamePrep].)
 */
object TrInstaller {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<TrInstallState>(TrInstallState.Idle)
    val state: StateFlow<TrInstallState> = _state.asStateFlow()

    private var installer: GameInstaller? = null
    private var watcher: Job? = null

    fun isRunning() = _state.value is TrInstallState.Running

    fun dismissError() {
        if (_state.value is TrInstallState.Failed) _state.value = TrInstallState.Idle
    }

    fun start(context: Context) {
        if (isRunning()) return
        val app = context.applicationContext
        _state.value = TrInstallState.Running("Buscando versiones de Fabric…", -1f)
        scope.launch {
            val info = try {
                val loaders = FabricVersions.fetchFabricLoaderList(TrConfig.MC_VERSION)
                    ?: throw IllegalStateException("No se pudo obtener la lista de Fabric. Revisa tu Internet.")
                val loader = loaders.firstOrNull { it.version == TrConfig.FABRIC_LOADER }
                    ?: loaders.firstOrNull { it.stable }
                    ?: loaders.firstOrNull()
                    ?: throw IllegalStateException("No hay Fabric disponible para Minecraft ${TrConfig.MC_VERSION}.")
                val api = FabricAPIVersions.fetchVersionList(TrConfig.MC_VERSION)?.firstOrNull()
                    ?: throw IllegalStateException("No se encontró Fabric API para Minecraft ${TrConfig.MC_VERSION}.")
                GameDownloadInfo(
                    gameVersion = TrConfig.MC_VERSION,
                    customVersionName = TrConfig.VERSION_NAME,
                    fabric = loader,
                    fabricAPI = api
                )
            } catch (e: Exception) {
                Logger.error(TAG, "No se pudo preparar la instalación", e)
                _state.value = TrInstallState.Failed(e.message ?: "Error desconocido.")
                return@launch
            }

            val gameInstaller = GameInstaller(app, info, scope)
            installer = gameInstaller
            watcher = scope.launch {
                while (isActive) {
                    val tasks = gameInstaller.tasksFlow.value
                    if (tasks.isNotEmpty()) {
                        val done = tasks.count { it.task.stage.value == TaskStage.COMPLETED }
                        val current = tasks.firstOrNull { it.task.stage.value == TaskStage.RUNNING }
                        val title = current?.title?.toAndroidString(app) ?: "Instalando…"
                        val local = current?.task?.progress?.value ?: -1f
                        val total = if (local in 0f..1f) (done + local) / tasks.size else done.toFloat() / tasks.size
                        if (_state.value is TrInstallState.Running) {
                            _state.value = TrInstallState.Running("$title  (${done.coerceAtMost(tasks.size)}/${tasks.size})", total)
                        }
                    }
                    delay(400)
                }
            }
            gameInstaller.installGame(
                onInstalled = { version ->
                    finish()
                    VersionsManager.refresh("[TrInstaller] onInstalled", version)
                    _state.value = TrInstallState.Idle
                },
                onError = { th ->
                    finish()
                    Logger.error(TAG, "Falló la instalación", th)
                    _state.value = TrInstallState.Failed(th.message ?: th.javaClass.simpleName)
                },
                onGameAlreadyInstalled = {
                    finish()
                    VersionsManager.refresh("[TrInstaller] already", TrConfig.VERSION_NAME)
                    _state.value = TrInstallState.Idle
                }
            )
        }
    }

    fun cancel() {
        installer?.cancelInstall()
        finish()
        _state.value = TrInstallState.Idle
    }

    private fun finish() {
        watcher?.cancel()
        watcher = null
        installer = null
    }
}
