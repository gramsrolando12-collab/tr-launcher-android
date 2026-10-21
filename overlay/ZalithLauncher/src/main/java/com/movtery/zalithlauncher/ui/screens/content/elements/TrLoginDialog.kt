package com.movtery.zalithlauncher.ui.screens.content.elements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.movtery.zalithlauncher.tr.TrApi
import com.movtery.zalithlauncher.tr.TrApiException
import com.movtery.zalithlauncher.tr.TrConfig
import com.movtery.zalithlauncher.tr.saveTrAccount
import kotlinx.coroutines.launch

private val trNameRegex = Regex("^[A-Za-z0-9_]{3,16}$")

/**
 * Diálogo para entrar o crear una cuenta T.R (la misma base de datos que usa el launcher de PC).
 * El servidor impide dos cuentas con el mismo nombre (ni parecidos como «TicoCR» y «Tic0_CR»).
 */
@Composable
fun TrLoginDialog(
    onDismissRequest: () -> Unit,
    onSuccess: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var registerMode by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var password2 by rememberSaveable { mutableStateOf("") }
    var claim by rememberSaveable { mutableStateOf("") }
    var needClaim by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf("") }

    fun validate(): String? = when {
        !TrConfig.apiEnabled -> "El servidor de cuentas no está configurado en esta versión de la app."
        !trNameRegex.matches(name) -> "El nombre debe tener de 3 a 16 caracteres: letras, números o guion bajo."
        password.length < TrConfig.PW_MIN || password.length > TrConfig.PW_MAX ->
            "La contraseña debe tener entre ${TrConfig.PW_MIN} y ${TrConfig.PW_MAX} caracteres."
        registerMode && password != password2 -> "Las contraseñas no coinciden."
        else -> null
    }

    fun submit() {
        if (busy) return
        validate()?.let { error = it; return }
        error = ""
        busy = true
        scope.launch {
            try {
                val session = if (registerMode) TrApi.register(name, password, claim.takeIf { needClaim })
                else TrApi.login(name, password)
                saveTrAccount(session)
                busy = false
                onSuccess()
                onDismissRequest()
            } catch (e: TrApiException) {
                if (e.code == "needs_claim") needClaim = true
                error = e.message ?: "Error desconocido."
                busy = false
            } catch (e: Exception) {
                error = e.message ?: "Error desconocido."
                busy = false
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismissRequest() },
        properties = DialogProperties(decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.padding(6.dp).heightIn(max = 520.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (registerMode) "Crear cuenta T.R" else "Iniciar sesión en T.R",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.size(12.dp))
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = name,
                        onValueChange = { name = it.trim() },
                        label = { Text("Nombre de jugador") },
                        singleLine = true,
                        enabled = !busy
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Contraseña") },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    if (registerMode) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = password2,
                            onValueChange = { password2 = it },
                            label = { Text("Repite la contraseña") },
                            singleLine = true,
                            enabled = !busy,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                        )
                    }
                    if (registerMode && needClaim) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = claim,
                            onValueChange = { claim = it },
                            label = { Text("Clave de nombre reservado") },
                            singleLine = true,
                            enabled = !busy,
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }
                    if (error.isNotEmpty()) {
                        Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        text = if (registerMode) "Tu nombre es único: nadie más podrá usarlo ni copiarlo. La contraseña no se envía a ningún lado, solo una clave derivada de ella."
                        else "Usa la misma cuenta que en el launcher de PC.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(
                        enabled = !busy,
                        onClick = { registerMode = !registerMode; error = ""; needClaim = false }
                    ) {
                        Text(if (registerMode) "Ya tengo cuenta" else "No tengo cuenta: crear una")
                    }
                }
                Spacer(Modifier.size(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(modifier = Modifier.weight(1f), enabled = !busy, onClick = onDismissRequest) {
                        Text("Cancelar")
                    }
                    Button(modifier = Modifier.weight(1f), enabled = !busy, onClick = { submit() }) {
                        if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(if (registerMode) "Crear cuenta" else "Entrar")
                    }
                }
            }
        }
    }
}
