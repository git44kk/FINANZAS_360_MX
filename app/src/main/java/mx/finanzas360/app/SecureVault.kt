package mx.finanzas360.app

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/** Small encrypted preference vault backed by a non-exportable Android Keystore AES key. */
object SecureVault {
    private const val PREFS = "finanzas360_secure_v1"
    private const val ALIAS = "finanzas360.vault.aes.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun put(context: Context, name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun get(context: Context, name: String): String? {
        val packedText = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null) ?: return null
        val packed = Base64.decode(packedText, Base64.NO_WRAP)
        require(packed.size >= 12 + 16) { "El dato seguro almacenado está incompleto: $name" }
        val iv = packed.copyOfRange(0, 12)
        val ciphertext = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    fun remove(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(name).apply()
    }
}

object DatabaseKeyStore {
    private const val KEY_NAME = "database_passphrase"

    fun getOrCreatePassphrase(context: Context): String {
        System.loadLibrary("sqlcipher")
        SecureVault.get(context, KEY_NAME)?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val value = Base64.encodeToString(bytes, Base64.NO_WRAP)
        SecureVault.put(context, KEY_NAME, value)
        return value
    }

    fun replacePassphrase(context: Context, passphrase: String) {
        require(passphrase.isNotBlank())
        SecureVault.put(context, KEY_NAME, passphrase)
    }
}

object AppLockSettings {
    private const val PREFS = "finanzas360_settings"
    private const val LOCK_ENABLED = "biometric_lock_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(LOCK_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(LOCK_ENABLED, enabled).apply()
    }
}
