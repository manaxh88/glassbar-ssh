package com.glassbar.ssh.ui.screen.ssh

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SshConnectionInfo(
    val id: String = kotlin.random.Random.nextLong().toString(36),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    /**
     * The decrypted password exists in memory only. It is persisted only when
     * [savePassword] is explicitly enabled.
     */
    val password: String = "",
    val savePassword: Boolean = false,
    /** Persisted content URI for a user-selected private key; key bytes are never copied here. */
    val privateKeyUri: String = "",
) {
    internal fun toJson(encryptedPassword: String?): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("username", username)
        put("private_key_uri", privateKeyUri)
        put("save_password", savePassword && encryptedPassword != null)
        encryptedPassword?.let { put("password_encrypted", it) }
    }

    companion object {
        internal fun fromJson(
            obj: JSONObject,
            id: String,
            decryptedPassword: String,
            savePassword: Boolean,
        ): SshConnectionInfo = SshConnectionInfo(
            id = id,
            name = obj.optString("name", ""),
            host = obj.optString("host", ""),
            port = obj.optInt("port", 22),
            username = obj.optString("username", ""),
            password = decryptedPassword,
            savePassword = savePassword,
            privateKeyUri = obj.optString("private_key_uri", ""),
        )
    }
}

data class SshConnectionLoadError(
    val occurredAtMillis: Long,
    val detail: String,
    val backupAvailable: Boolean,
)

class SshConnectionStoreBlockedException : IllegalStateException(
    "SSH connection storage is locked because its data could not be decoded",
)

internal sealed class ConnectionDataDecodeResult<out T> {
    data object Empty : ConnectionDataDecodeResult<Nothing>()
    data class Success<T>(val value: T) : ConnectionDataDecodeResult<T>()
    data class Corrupt(val raw: String, val cause: Exception) : ConnectionDataDecodeResult<Nothing>()
}

internal inline fun <T> decodeConnectionData(
    raw: String?,
    decoder: (String) -> T,
): ConnectionDataDecodeResult<T> {
    if (raw == null) return ConnectionDataDecodeResult.Empty
    return try {
        ConnectionDataDecodeResult.Success(decoder(raw))
    } catch (error: Exception) {
        ConnectionDataDecodeResult.Corrupt(raw, error)
    }
}

internal fun shouldBlockConnectionStoreWrites(
    persistedCorruption: Boolean,
    runtimeLoadError: SshConnectionLoadError?,
): Boolean = persistedCorruption || runtimeLoadError != null

/**
 * Stores connection metadata in SharedPreferences and credentials with an
 * Android Keystore backed AES-256-GCM key. Plaintext credentials from older
 * versions are migrated on the first successful read.
 */
object SshConnectionStore {
    private const val PREFS_NAME = "ssh_connections"
    private const val KEY_CONNECTIONS = "connections_json"
    private const val KEY_CORRUPT_BACKUP = "connections_json_corrupt_backup"
    private const val KEY_CORRUPTION_BLOCKED = "connections_json_corruption_blocked"
    private const val KEY_CORRUPTION_DETAIL = "connections_json_corruption_detail"
    private const val KEY_CORRUPTION_TIME = "connections_json_corruption_time"
    private const val LEGACY_PASSWORD = "password"
    private const val ENCRYPTED_PASSWORD = "password_encrypted"
    private const val UNKNOWN_CORRUPTION_DETAIL = "Unable to decode stored SSH connections"

    private val _loadError = MutableStateFlow<SshConnectionLoadError?>(null)
    val loadError: StateFlow<SshConnectionLoadError?> = _loadError.asStateFlow()

    @Synchronized
    fun getAll(context: Context): List<SshConnectionInfo> {
        val prefs = preferences(context)
        if (prefs.getBoolean(KEY_CORRUPTION_BLOCKED, false)) {
            publishPersistedLoadError(prefs)
            return emptyList()
        }

        return when (
            val result = decodeConnectionData(prefs.getString(KEY_CONNECTIONS, null)) {
                decodeConnections(it)
            }
        ) {
            ConnectionDataDecodeResult.Empty -> {
                _loadError.value = null
                emptyList()
            }

            is ConnectionDataDecodeResult.Corrupt -> {
                recordCorruption(prefs, result)
                emptyList()
            }

            is ConnectionDataDecodeResult.Success -> {
                _loadError.value = null
                val decoded = result.value
                // Rewriting removes legacy plaintext only after encryption has
                // succeeded. A Keystore failure leaves the old value untouched.
                if (decoded.containsLegacyPlaintext) {
                    runCatching { save(context, decoded.connections) }
                }
                decoded.connections
            }
        }
    }

    @Synchronized
    fun save(context: Context, connections: List<SshConnectionInfo>) {
        val prefs = preferences(context)
        requireWritable(prefs)
        persistConnections(prefs, encodeConnections(prefs, connections))
    }

    @Synchronized
    fun add(context: Context, info: SshConnectionInfo) {
        val list = getAll(context).toMutableList()
        requireWritable(preferences(context))
        list.add(0, info)
        save(context, list)
    }

    @Synchronized
    fun update(context: Context, info: SshConnectionInfo) {
        val list = getAll(context).toMutableList()
        requireWritable(preferences(context))
        val idx = list.indexOfFirst { it.id == info.id }
        if (idx >= 0) {
            list[idx] = info
            save(context, list)
        }
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        val list = getAll(context).toMutableList()
        requireWritable(preferences(context))
        list.removeAll { it.id == id }
        save(context, list)
    }

    /** Returns the exact raw value retained after the latest decode failure. */
    fun getCorruptDataBackup(context: Context): String? =
        preferences(context).getString(KEY_CORRUPT_BACKUP, null)

    /**
     * Explicitly replaces data after the caller has recovered, migrated, or
     * chosen to discard the corrupt value. The backup is retained for export
     * or diagnostics until a later corruption replaces it.
     */
    @Synchronized
    fun replaceCorruptData(
        context: Context,
        recoveredConnections: List<SshConnectionInfo>,
    ) {
        val prefs = preferences(context)
        check(isWriteBlocked(prefs)) { "SSH connection storage is not corrupt" }
        val encoded = encodeConnections(prefs, recoveredConnections)
        check(
            prefs.edit()
                .putString(KEY_CONNECTIONS, encoded)
                .remove(KEY_CORRUPTION_BLOCKED)
                .remove(KEY_CORRUPTION_DETAIL)
                .remove(KEY_CORRUPTION_TIME)
                .commit(),
        ) { "Failed to recover SSH connections" }
        _loadError.value = null
    }

    private fun decodeConnections(raw: String): DecodedConnections {
        val arr = JSONArray(raw)
        var containsLegacyPlaintext = false
        val connections = (0 until arr.length()).map { index ->
            val obj = arr.getJSONObject(index)
            val id = obj.optString("id", kotlin.random.Random.nextLong().toString(36))
            val encrypted = obj.optString(ENCRYPTED_PASSWORD).takeIf(String::isNotBlank)
            val legacy = obj.optString(LEGACY_PASSWORD).takeIf(String::isNotBlank)
            val shouldSave = obj.optBoolean(
                "save_password",
                encrypted != null || legacy != null,
            )

            val password = when {
                !shouldSave -> ""
                encrypted != null -> SshCredentialCrypto.decrypt(id, encrypted).orEmpty()
                legacy != null -> {
                    containsLegacyPlaintext = true
                    legacy
                }
                else -> ""
            }
            SshConnectionInfo.fromJson(obj, id, password, shouldSave)
        }
        return DecodedConnections(connections, containsLegacyPlaintext)
    }

    private fun encodeConnections(
        prefs: SharedPreferences,
        connections: List<SshConnectionInfo>,
    ): String {
        val existingCredentials = readExistingEncryptedCredentials(
            prefs.getString(KEY_CONNECTIONS, null),
        )
        return JSONArray().apply {
            connections.forEach { info ->
                val encryptedPassword = when {
                    !info.savePassword -> null
                    info.password.isNotEmpty() -> SshCredentialCrypto.encrypt(info.id, info.password)
                    else -> existingCredentials[info.id]
                }
                put(info.toJson(encryptedPassword))
            }
        }.toString()
    }

    private fun persistConnections(prefs: SharedPreferences, encoded: String) {
        // commit() makes writes atomic from the caller's perspective and lets
        // storage failures surface instead of pretending they succeeded.
        check(prefs.edit().putString(KEY_CONNECTIONS, encoded).commit()) {
            "Failed to persist SSH connections"
        }
    }

    private fun recordCorruption(
        prefs: SharedPreferences,
        corruption: ConnectionDataDecodeResult.Corrupt,
    ) {
        val occurredAt = System.currentTimeMillis()
        val detail = corruption.cause.toSafeDetail()
        val backupSaved = runCatching {
            prefs.edit()
                .putString(KEY_CORRUPT_BACKUP, corruption.raw)
                .putBoolean(KEY_CORRUPTION_BLOCKED, true)
                .putString(KEY_CORRUPTION_DETAIL, detail)
                .putLong(KEY_CORRUPTION_TIME, occurredAt)
                .commit()
        }.getOrDefault(false)
        _loadError.value = SshConnectionLoadError(
            occurredAtMillis = occurredAt,
            detail = detail,
            backupAvailable = backupSaved,
        )
    }

    private fun publishPersistedLoadError(prefs: SharedPreferences) {
        _loadError.value = SshConnectionLoadError(
            occurredAtMillis = prefs.getLong(KEY_CORRUPTION_TIME, 0L),
            detail = prefs.getString(KEY_CORRUPTION_DETAIL, null)
                ?: UNKNOWN_CORRUPTION_DETAIL,
            backupAvailable = prefs.contains(KEY_CORRUPT_BACKUP),
        )
    }

    private fun requireWritable(prefs: SharedPreferences) {
        if (isWriteBlocked(prefs)) throw SshConnectionStoreBlockedException()
    }

    private fun isWriteBlocked(prefs: SharedPreferences): Boolean =
        shouldBlockConnectionStoreWrites(
            persistedCorruption = prefs.getBoolean(KEY_CORRUPTION_BLOCKED, false),
            runtimeLoadError = _loadError.value,
        )

    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun Exception.toSafeDetail(): String {
        val type = javaClass.simpleName.ifBlank { "Exception" }
        // Parser messages can echo fragments of the raw JSON, including a
        // legacy plaintext password. Keep diagnostics useful without copying
        // any input into the observable or persisted error metadata.
        return "$type: $UNKNOWN_CORRUPTION_DETAIL"
    }

    private fun readExistingEncryptedCredentials(raw: String?): Map<String, String> {
        if (raw == null) return emptyMap()
        return runCatching {
            val arr = JSONArray(raw)
            buildMap {
                repeat(arr.length()) { index ->
                    val obj = arr.getJSONObject(index)
                    val id = obj.optString("id")
                    val encrypted = obj.optString(ENCRYPTED_PASSWORD)
                    if (id.isNotBlank() && encrypted.isNotBlank()) put(id, encrypted)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private data class DecodedConnections(
        val connections: List<SshConnectionInfo>,
        val containsLegacyPlaintext: Boolean,
    )
}

private object SshCredentialCrypto {
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "glassbar_ssh_credentials_aes_gcm_v1"
    private const val PAYLOAD_VERSION = "v1"
    private const val GCM_TAG_LENGTH_BITS = 128

    fun encrypt(connectionId: String, plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(connectionId.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val value = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        return "$PAYLOAD_VERSION:$iv:$value"
    }

    fun decrypt(connectionId: String, payload: String): String? = runCatching {
        val parts = payload.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == PAYLOAD_VERSION) {
            "Unsupported SSH credential format"
        }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
        )
        cipher.updateAAD(connectionId.toByteArray(StandardCharsets.UTF_8))
        String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return keyGenerator.generateKey()
    }
}
