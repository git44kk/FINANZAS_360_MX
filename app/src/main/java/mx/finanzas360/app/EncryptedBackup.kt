package mx.finanzas360.app

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-protected portable snapshot. The SQLCipher key is included only inside the encrypted payload. */
object EncryptedBackup {
    private val magic = "F360BK02".toByteArray(Charsets.US_ASCII)
    private const val MAX_DATABASE_BYTES = 512 * 1024 * 1024

    fun export(context: Context, destination: Uri, password: CharArray) {
        val bytes = createBytes(context, password)
        context.contentResolver.openOutputStream(destination)?.use { it.write(bytes) }
            ?: error("No se pudo crear el archivo de respaldo.")
    }

    fun createBytes(context: Context, password: CharArray): ByteArray {
        try {
            require(password.size >= 8) { "Usa una contraseña de al menos 8 caracteres." }
            val helper = FinanceDb(context)
            helper.checkpoint()
            val databaseKey = DatabaseKeyStore.getOrCreatePassphrase(context)
            helper.close()
            val dbFile = context.getDatabasePath("finanzas360.db")
            require(dbFile.exists()) { "Todavía no existe una base de datos para respaldar." }
            val dbBytes = dbFile.readBytes()
            require(dbBytes.size in 4096..MAX_DATABASE_BYTES) { "El tamaño de la base de datos no es válido." }
            val payload = ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { out ->
                    val keyBytes = databaseKey.toByteArray(Charsets.UTF_8)
                    out.writeInt(keyBytes.size)
                    out.write(keyBytes)
                    out.writeInt(dbBytes.size)
                    out.write(dbBytes)
                }
                buffer.toByteArray()
            }
            return encryptPayload(payload, password)
        } finally { password.fill('\u0000') }
    }

    fun restore(context: Context, source: Uri, password: CharArray) {
        val bytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() }
            ?: error("No se pudo abrir el respaldo.")
        restoreBytes(context, bytes, password)
    }

    fun restoreBytes(context: Context, encrypted: ByteArray, password: CharArray) {
        val payload = try { decryptPayload(encrypted, password) } finally { password.fill('\u0000') }
        val input = DataInputStream(ByteArrayInputStream(payload))
        val keyLength = input.readInt()
        require(keyLength in 16..512) { "El respaldo no contiene una clave de base de datos válida." }
        val newDatabaseKey = ByteArray(keyLength).also { input.readFully(it) }.toString(Charsets.UTF_8)
        val dbLength = input.readInt()
        require(dbLength in 4096..MAX_DATABASE_BYTES && dbLength <= input.available()) { "El contenido de la base de datos está incompleto." }
        val dbBytes = ByteArray(dbLength).also { input.readFully(it) }
        require(input.available() == 0) { "El respaldo contiene datos adicionales inesperados." }

        val target = context.getDatabasePath("finanzas360.db")
        val temp = File(target.parentFile, "finanzas360.restore.tmp")
        val previous = File(target.parentFile, "finanzas360.before-restore.tmp")
        target.parentFile?.mkdirs()
        temp.writeBytes(dbBytes)
        val oldKey = DatabaseKeyStore.getOrCreatePassphrase(context)
        val hadOld = target.exists()
        if (hadOld) {
            target.copyTo(previous, overwrite = true)
        }
        File(target.parentFile, "finanzas360.db-wal").delete()
        File(target.parentFile, "finanzas360.db-shm").delete()
        try {
            DatabaseKeyStore.replacePassphrase(context, newDatabaseKey)
            if (target.exists()) target.delete()
            check(temp.renameTo(target)) { "No se pudo reemplazar la base de datos." }
            val verify = FinanceDb(context)
            val valid = try { verify.integrityCheck() } finally { verify.close() }
            check(valid) { "La base de datos restaurada no superó la validación de integridad." }
            previous.delete()
        } catch (e: Exception) {
            target.delete()
            val rollbackSucceeded = !hadOld || (previous.exists() && previous.renameTo(target))
            DatabaseKeyStore.replacePassphrase(context, oldKey)
            check(rollbackSucceeded) { "Falló la restauración y tampoco se pudo recuperar la base de datos anterior. Conservé el respaldo temporal en ${previous.absolutePath}." }
            throw e
        } finally {
            temp.delete()
            if (!previous.exists() || target.exists()) previous.delete()
        }
    }

    private fun encryptPayload(plain: ByteArray, password: CharArray): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plain)
        return magic + salt + iv + encrypted
    }

    private fun decryptPayload(bytes: ByteArray, password: CharArray): ByteArray {
        require(bytes.size > magic.size + 28 && bytes.copyOfRange(0, magic.size).contentEquals(magic)) {
            "El archivo no es un respaldo FINANZAS 360 MX válido."
        }
        val salt = bytes.copyOfRange(magic.size, magic.size + 16)
        val iv = bytes.copyOfRange(magic.size + 16, magic.size + 28)
        val encrypted = bytes.copyOfRange(magic.size + 28, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, 310_000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(key, "AES")
    }
}
