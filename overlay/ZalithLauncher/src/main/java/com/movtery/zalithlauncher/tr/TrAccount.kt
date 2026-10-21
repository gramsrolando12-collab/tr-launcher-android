package com.movtery.zalithlauncher.tr

import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.AccountType
import com.movtery.zalithlauncher.game.account.AccountsManager
import com.movtery.zalithlauncher.game.account.wardrobe.SkinModelType
import com.movtery.zalithlauncher.game.account.wardrobe.getLocalUUIDWithSkinModel
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.io.FileUtils

private const val TAG = "TrAccount"
private const val TR_MARK = "tr"

/** Una cuenta T.R es una cuenta local (sin Microsoft) que además tiene una sesión en el servidor de cuentas. */
fun Account.isTrAccount(): Boolean = accountType == AccountType.LOCAL.tag && otherAccount == TR_MARK

/** Token de sesión del servidor de cuentas (solo cuentas T.R). */
val Account.trToken: String get() = refreshToken

/**
 * Crea (o actualiza) la cuenta T.R en el teléfono tras un registro o inicio de sesión correcto
 * y la deja como cuenta activa. Después descarga la skin que ya tuviera en el servidor.
 */
suspend fun saveTrAccount(session: TrSession): Account {
    val existing = AccountsManager.accountsFlow.value.firstOrNull { it.isTrAccount() && it.username.equals(session.name, ignoreCase = true) }
    val account = (existing ?: Account(accountType = AccountType.LOCAL.tag)).apply {
        username = session.name
        otherAccount = TR_MARK
        refreshToken = session.token
        if (existing == null) profileId = getLocalUUIDWithSkinModel(session.name, skinModelType)
    }
    AccountsManager.suspendSaveAccount(account)
    AccountsManager.markSessionValidated(account)
    runCatching { downloadOwnSkin(account) }.onFailure { Logger.warning(TAG, "No se pudo bajar la skin: ${it.message}") }
    return account
}

/** Baja desde el servidor la skin de esta cuenta (si tiene) y la deja como skin local. */
suspend fun downloadOwnSkin(account: Account) {
    val (hash, slim) = TrApi.skinOf(account.username) ?: return
    val png = TrApi.texture(hash)
    withContext(Dispatchers.IO) {
        FileUtils.writeByteArrayToFile(account.getSkinFile(), png)
        account.skinModelType = if (slim) SkinModelType.ALEX else SkinModelType.STEVE
        account.profileId = getLocalUUIDWithSkinModel(account.username, account.skinModelType)
    }
    AccountsManager.suspendSaveAccount(account)
    AccountsManager.refreshWardrobe()
}

/** Sube al servidor la skin local de la cuenta para que todos los jugadores la vean. Devuelve el error, o null si fue bien. */
suspend fun uploadOwnSkin(account: Account): String? {
    if (!account.isTrAccount()) return null
    return try {
        val png = withContext(Dispatchers.IO) { account.getSkinFile().readBytes() }
        TrApi.setSkin(account.trToken, png, account.skinModelType == SkinModelType.ALEX)
        null
    } catch (e: TrApiException) {
        e.message ?: "No se pudo subir la skin."
    } catch (e: Exception) {
        e.message ?: "No se pudo subir la skin."
    }
}
