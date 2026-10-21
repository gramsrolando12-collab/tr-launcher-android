package com.movtery.zalithlauncher.ui.screens.content.elements

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.tr.TrConfig
import com.movtery.zalithlauncher.tr.TrInstallState
import com.movtery.zalithlauncher.tr.TrInstaller

/**
 * Bloque de instalación de T.R: solo se ve mientras la versión única de Minecraft no está instalada
 * (o mientras se instala). Un botón descarga Minecraft + Fabric + Fabric API.
 */
@Composable
fun TrInstallSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val versions by VersionsManager.versions.collectAsStateWithLifecycle()
    val state by TrInstaller.state.collectAsStateWithLifecycle()

    val installed = versions.any { it.getVersionName() == TrConfig.VERSION_NAME }
    if (installed && state !is TrInstallState.Running) return

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
        when (val s = state) {
            is TrInstallState.Running -> {
                Text(text = "Instalando Minecraft ${TrConfig.MC_VERSION} con Fabric…", style = MaterialTheme.typography.titleSmall)
                Text(text = s.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                if (s.progress in 0f..1f) {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                TextButton(onClick = { TrInstaller.cancel() }) { Text("Cancelar") }
            }
            is TrInstallState.Failed -> {
                Text(
                    text = "No se pudo instalar: ${s.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                Button(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), onClick = { TrInstaller.start(context) }) {
                    Text("Reintentar instalación")
                }
            }
            is TrInstallState.Idle -> {
                Text(
                    text = "Falta instalar el juego (una sola vez, necesita Internet).",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), onClick = { TrInstaller.start(context) }) {
                    Text("Instalar Minecraft ${TrConfig.MC_VERSION} con Fabric")
                }
            }
        }
    }
}
