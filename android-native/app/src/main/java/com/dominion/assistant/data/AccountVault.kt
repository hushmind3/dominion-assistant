package com.dominion.assistant.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Account(val id: String, val token: String)

/** Android Keystore backed account token; no plaintext credentials in preferences. */
class AccountVault(context: Context) {
    private val prefs = context.getSharedPreferences("dominion.native.account.v1", Context.MODE_PRIVATE)
    private val alias = "dominion_assistant_account_v1"
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        val existing = store.getKey(alias, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return generator.generateKey()
    }

    fun save(account: Account) {
        require(Regex("^dapp_[A-Za-z0-9_-]{43}$").matches(account.token))
        require(account.id.startsWith("google:"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(
            JSONObject().put("id", account.id).put("token", account.token)
                .toString().toByteArray(Charsets.UTF_8)
        )
        prefs.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("value", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    fun load(): Account? {
        val iv = prefs.getString("iv", null) ?: return null
        val value = prefs.getString("value", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, key(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            val data = JSONObject(
                String(cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)), Charsets.UTF_8)
            )
            val result = Account(data.getString("id"), data.getString("token"))
            if (!Regex("^dapp_[A-Za-z0-9_-]{43}$").matches(result.token) ||
                !result.id.startsWith("google:")) throw IllegalStateException("Invalid account")
            result
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
