package mx.finanzas360.app

import android.content.Context
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

 data class CloudSyncConfig(
    val endpoint: String,
    val username: String,
    val password: String,
    val encryptionPassword: String
)

data class CloudSyncResult(val status: String, val message: String)

object CloudSyncSettings {
    private const val ENDPOINT = "cloud_endpoint"
    private const val USER = "cloud_user"
    private const val PASSWORD = "cloud_password"
    private const val ENCRYPTION = "cloud_encryption_password"

    fun load(context: Context): CloudSyncConfig = CloudSyncConfig(
        SecureVault.get(context, ENDPOINT).orEmpty(), SecureVault.get(context, USER).orEmpty(),
        SecureVault.get(context, PASSWORD).orEmpty(), SecureVault.get(context, ENCRYPTION).orEmpty()
    )

    fun save(context: Context, config: CloudSyncConfig) {
        SecureVault.put(context, ENDPOINT, config.endpoint.trim())
        SecureVault.put(context, USER, config.username.trim())
        SecureVault.put(context, PASSWORD, config.password)
        SecureVault.put(context, ENCRYPTION, config.encryptionPassword)
    }

    fun isAutoSyncEnabled(context: Context): Boolean =
        context.getSharedPreferences("finanzas360_cloud_options", Context.MODE_PRIVATE).getBoolean("auto_sync", false)

    fun setAutoSyncEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("finanzas360_cloud_options", Context.MODE_PRIVATE).edit().putBoolean("auto_sync", enabled).apply()
    }
}

/** Encrypted WebDAV snapshot sync with timestamp conflict detection; it never silently overwrites two changed copies. */
private data class RemoteMeta(val modifiedAt: Long, val etag: String?)

object CloudSyncManager {
    private const val PREFS = "finanzas360_cloud_sync"
    private const val LAST_SYNC = "last_successful_sync"
    private const val LAST_ETAG = "last_remote_etag"
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    fun sync(context: Context, db: FinanceDb, config: CloudSyncConfig): CloudSyncResult {
        validate(config)

        db.checkpoint()
        val localChangedAt = db.lastModifiedAt()
        db.close()
        val localSnapshot = EncryptedBackup.createBytes(context, config.encryptionPassword.toCharArray())
        val remoteMeta = head(config)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSync = prefs.getLong(LAST_SYNC, 0L)
        val lastEtag = prefs.getString(LAST_ETAG, null)
        val now = System.currentTimeMillis()

        if (remoteMeta == null) {
            put(config, config.endpoint, localSnapshot)
            saveLastSync(context, now, head(config)?.etag)
            return CloudSyncResult("UPLOADED", "Se creó el primer respaldo cifrado en la nube.")
        }

        if (lastSync == 0L) {
            if (localChangedAt == 0L) {
                val remote = get(config, config.endpoint)
                EncryptedBackup.restoreBytes(context, remote, config.encryptionPassword.toCharArray())
                saveLastSync(context, now, head(config)?.etag)
                return CloudSyncResult("DOWNLOADED", "Se descargó el respaldo existente y se restauró en este dispositivo.")
            }
            val conflictUrl = conflictUrl(config.endpoint, now)
            put(config, conflictUrl, localSnapshot)
            return CloudSyncResult("CONFLICT", "Ya existe un respaldo remoto y este dispositivo también tiene datos. Conservé la copia local en $conflictUrl; no sobrescribí ninguna versión.")
        }

        val localChanged = localChangedAt > lastSync
        val remoteChanged = if (!remoteMeta.etag.isNullOrBlank() && !lastEtag.isNullOrBlank())
            remoteMeta.etag != lastEtag else remoteMeta.modifiedAt > lastSync
        if (localChanged && remoteChanged) {
            val conflictUrl = conflictUrl(config.endpoint, now)
            put(config, conflictUrl, localSnapshot)
            return CloudSyncResult("CONFLICT", "Detecté cambios en ambos dispositivos. Guardé esta copia local en $conflictUrl y conservé el archivo remoto principal.")
        }
        if (remoteChanged) {
            val remote = get(config, config.endpoint)
            EncryptedBackup.restoreBytes(context, remote, config.encryptionPassword.toCharArray())
            saveLastSync(context, now, head(config)?.etag)
            return CloudSyncResult("DOWNLOADED", "Se descargaron los cambios remotos y se validó la base de datos.")
        }
        if (localChanged) {
            put(config, config.endpoint, localSnapshot)
            saveLastSync(context, now, head(config)?.etag)
            return CloudSyncResult("UPLOADED", "Se subieron los cambios locales con cifrado de extremo a extremo.")
        }
        saveLastSync(context, now, remoteMeta.etag)
        return CloudSyncResult("CURRENT", "Este dispositivo y la copia en la nube ya están sincronizados.")
    }

    fun forceUpload(context: Context, db: FinanceDb, config: CloudSyncConfig): CloudSyncResult {
        validate(config)
        db.checkpoint()
        db.close()
        val snapshot = EncryptedBackup.createBytes(context, config.encryptionPassword.toCharArray())
        put(config, config.endpoint, snapshot)
        saveLastSync(context, System.currentTimeMillis(), head(config)?.etag)
        return CloudSyncResult("UPLOADED", "Se estableció este dispositivo como versión principal de la nube. La copia de conflicto se conserva.")
    }

    fun forceDownload(context: Context, db: FinanceDb, config: CloudSyncConfig): CloudSyncResult {
        validate(config)
        db.close()
        val remote = get(config, config.endpoint)
        EncryptedBackup.restoreBytes(context, remote, config.encryptionPassword.toCharArray())
        saveLastSync(context, System.currentTimeMillis(), head(config)?.etag)
        return CloudSyncResult("DOWNLOADED", "Se reemplazaron los datos locales con la versión principal de la nube.")
    }

    private fun validate(config: CloudSyncConfig) {
        require(config.endpoint.startsWith("https://", true)) { "Por seguridad, el servidor WebDAV debe usar HTTPS." }
        require(config.endpoint.endsWith(".f360", true)) { "Indica la URL completa del archivo remoto, por ejemplo .../FINANZAS360.f360." }
        require(config.username.isNotBlank() && config.password.isNotBlank()) { "Faltan las credenciales WebDAV." }
        require(config.encryptionPassword.length >= 8) { "La contraseña de cifrado debe tener al menos 8 caracteres." }
    }

    private fun auth(config: CloudSyncConfig) = Credentials.basic(config.username, config.password)

    private fun head(config: CloudSyncConfig): RemoteMeta? {
        val request = Request.Builder().url(config.endpoint).header("Authorization", auth(config)).head().build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404 || response.code == 410) return null
            if (!response.isSuccessful) error("WebDAV respondió HTTP ${response.code} al consultar el archivo.")
            val header = response.header("Last-Modified")
            val etag = response.header("ETag")
            val modified = header?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() } ?: 0L
            require(modified > 0L || !etag.isNullOrBlank()) { "El servidor WebDAV debe devolver Last-Modified o ETag para detectar cambios de forma segura." }
            return RemoteMeta(modified, etag)
        }
    }

    private fun get(config: CloudSyncConfig, url: String): ByteArray {
        val request = Request.Builder().url(url).header("Authorization", auth(config)).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("No se pudo descargar el respaldo: HTTP ${response.code}.")
            return response.body?.bytes() ?: error("El servidor devolvió un respaldo vacío.")
        }
    }

    private fun put(config: CloudSyncConfig, url: String, bytes: ByteArray) {
        val body = bytes.toRequestBody("application/octet-stream".toMediaType())
        val request = Request.Builder().url(url).header("Authorization", auth(config)).put(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("No se pudo subir el respaldo: HTTP ${response.code}.")
        }
    }

    private fun conflictUrl(url: String, timestamp: Long): String =
        url.removeSuffix(".f360") + "-conflict-$timestamp.f360"

    private fun saveLastSync(context: Context, timestamp: Long, etag: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(LAST_SYNC, timestamp)
            .putString(LAST_ETAG, etag)
            .apply()
    }
}
