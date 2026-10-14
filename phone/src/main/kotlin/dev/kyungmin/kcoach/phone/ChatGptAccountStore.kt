package dev.kyungmin.kcoach.phone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class ChatGptSavedAccounts(
    val hostId: String = "urn:uuid:${UUID.randomUUID()}",
    val accounts: MutableList<ChatGptAccount> = mutableListOf(),
    var activeClientId: String? = null,
    var unfinishedClientId: String? = null,
) {
    val active get() = accounts.firstOrNull { it.clientId == activeClientId }
}

/** noBackupFilesDir excludes OAuth credentials and host identity from backup/device transfer. */
internal class ChatGptAccountStore(
    context: Context,
    private val secretKey: () -> SecretKey = ::chatGptStorageKey,
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "chatgpt-accounts.enc"))

    fun read(): ChatGptSavedAccounts {
        // Let AtomicFile recover an interrupted write before deciding this is a new installation.
        val bytes = try { file.readFully() } catch (_: FileNotFoundException) { return ChatGptSavedAccounts() }
        val envelope = JSONObject(String(bytes, Charsets.UTF_8))
        require(envelope.getInt("version") == 1)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.getDecoder().decode(envelope.getString("iv"))))
        cipher.updateAAD("KCoach.ChatGPT.v1".toByteArray())
        val root = JSONObject(String(cipher.doFinal(Base64.getDecoder().decode(envelope.getString("data"))), Charsets.UTF_8))
        val accounts = root.getJSONArray("accounts")
        return ChatGptSavedAccounts(
            hostId = root.getString("host_id"),
            accounts = (0 until accounts.length()).map { index ->
                val item = accounts.getJSONObject(index)
                val scopes = item.getJSONArray("scopes")
                ChatGptAccount(
                    item.getString("client_id"), item.getString("subject"), item.optString("email"), item.optString("name"),
                    item.optString("access_token"), item.optString("refresh_token"), item.optString("id_token"),
                    item.optLong("expires_at"), (0 until scopes.length()).map { scopes.getString(it) }.toSet(), item.optBoolean("welcome_seen"),
                    item.optString("work_model"), item.optString("work_effort").takeIf { it.isNotBlank() },
                )
            }.toMutableList(),
            activeClientId = root.optString("active_client_id").takeIf { it.isNotBlank() },
            unfinishedClientId = root.optString("unfinished_client_id").takeIf { it.isNotBlank() },
        )
    }

    fun write(saved: ChatGptSavedAccounts) {
        val root = JSONObject().put("host_id", saved.hostId)
            .put("active_client_id", saved.activeClientId.orEmpty())
            .put("unfinished_client_id", saved.unfinishedClientId.orEmpty())
            .put("accounts", JSONArray().also { list ->
                saved.accounts.forEach { account ->
                    list.put(JSONObject().put("client_id", account.clientId).put("subject", account.subject)
                        .put("email", account.email).put("name", account.name)
                        .put("access_token", account.accessToken).put("refresh_token", account.refreshToken).put("id_token", account.idToken)
                        .put("expires_at", account.expiresAtMs).put("scopes", JSONArray(account.scopes.toList()))
                        .put("welcome_seen", account.welcomeSeen).put("work_model", account.workModel).put("work_effort", account.workEffort.orEmpty()))
                }
            })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD("KCoach.ChatGPT.v1".toByteArray())
        val encrypted = cipher.doFinal(root.toString().toByteArray(Charsets.UTF_8))
        val envelope = JSONObject().put("version", 1)
            .put("iv", Base64.getEncoder().encodeToString(cipher.iv))
            .put("data", Base64.getEncoder().encodeToString(encrypted))
        val output = file.startWrite()
        try {
            output.write(envelope.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) { file.failWrite(output); throw e }
    }
}

private fun chatGptStorageKey(): SecretKey {
    val alias = "kcoach.chatgpt.accounts.v1"
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
    }.generateKey()
}
