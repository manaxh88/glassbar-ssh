package com.glassbar.ssh.ui.screen.ssh

import android.content.Context
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/** A public SSH host key as presented by a server. */
data class SshHostKey(
    val host: String,
    val port: Int,
    val algorithm: String,
    val keyBase64: String,
    val fingerprintSha256: String,
    val trustedAtEpochMillis: Long? = null,
)

/**
 * Host-key verification result for the most recent connection attempt.
 * Unknown and Changed keys are never accepted until the user explicitly
 * trusts the exact SHA-256 fingerprint.
 */
sealed interface SshHostKeyStatus {
    data object NotChecked : SshHostKeyStatus
    data class Trusted(val hostKey: SshHostKey) : SshHostKeyStatus
    data class Unknown(val presented: SshHostKey) : SshHostKeyStatus
    data class Changed(
        val trusted: SshHostKey,
        val presented: SshHostKey,
    ) : SshHostKeyStatus
}

internal fun classifySshHostKey(
    trusted: SshHostKey?,
    presented: SshHostKey,
): SshHostKeyStatus = when {
    trusted == null -> SshHostKeyStatus.Unknown(presented)
    MessageDigest.isEqual(
        decodeSshHostKey(trusted.keyBase64),
        decodeSshHostKey(presented.keyBase64),
    ) -> SshHostKeyStatus.Trusted(trusted)
    else -> SshHostKeyStatus.Changed(trusted, presented)
}

internal fun encodeSshHostKey(value: ByteArray): String =
    Base64.getEncoder().encodeToString(value)

internal fun decodeSshHostKey(value: String): ByteArray =
    Base64.getDecoder().decode(value)

internal fun fingerprintSshHostKey(key: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}

internal fun algorithmOfSshHostKey(key: ByteArray): String = runCatching {
    require(key.size >= Int.SIZE_BYTES)
    val length = ByteBuffer.wrap(key, 0, Int.SIZE_BYTES).int
    require(length in 1..(key.size - Int.SIZE_BYTES))
    String(key, Int.SIZE_BYTES, length, StandardCharsets.US_ASCII)
}.getOrDefault("unknown")

/** Persistent trust-on-first-use store used by [SshSession]. */
class SshKnownHostsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun find(host: String, port: Int): SshHostKey? {
        val endpoint = Endpoint(host, port)
        val raw = preferences.getString(endpoint.preferenceKey, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val key = json.getString("key")
            val decoded = decodeSshHostKey(key)
            SshHostKey(
                host = endpoint.host,
                port = endpoint.port,
                algorithm = algorithmOfSshHostKey(decoded),
                keyBase64 = encodeSshHostKey(decoded),
                fingerprintSha256 = fingerprintSshHostKey(decoded),
                trustedAtEpochMillis = json.optLong("trusted_at").takeIf { it > 0L },
            )
        }.getOrNull()
    }

    /** Replaces any previous key for this endpoint with an explicitly approved key. */
    @Synchronized
    internal fun trust(hostKey: SshHostKey) {
        val endpoint = Endpoint(hostKey.host, hostKey.port)
        val decoded = decodeSshHostKey(hostKey.keyBase64)
        val algorithm = algorithmOfSshHostKey(decoded)
        require(algorithm != "unknown" && algorithm == hostKey.algorithm) {
            "Host-key algorithm does not match the presented key"
        }
        require(fingerprintSshHostKey(decoded) == hostKey.fingerprintSha256) {
            "Host-key fingerprint does not match the presented key"
        }
        val json = JSONObject().apply {
            put("algorithm", algorithm)
            put("key", encodeSshHostKey(decoded))
            put("trusted_at", System.currentTimeMillis())
        }
        check(preferences.edit().putString(endpoint.preferenceKey, json.toString()).commit()) {
            "Failed to persist trusted SSH host key"
        }
    }

    @Synchronized
    internal fun forget(host: String, port: Int) {
        preferences.edit().remove(Endpoint(host, port).preferenceKey).commit()
    }

    internal fun repository(
        host: String,
        port: Int,
        onStatus: (SshHostKeyStatus) -> Unit,
    ): HostKeyRepository = VerifyingHostKeyRepository(
        store = this,
        endpoint = Endpoint(host, port),
        onStatus = onStatus,
    )

    private class VerifyingHostKeyRepository(
        private val store: SshKnownHostsStore,
        private val endpoint: Endpoint,
        private val onStatus: (SshHostKeyStatus) -> Unit,
    ) : HostKeyRepository {
        override fun check(host: String?, key: ByteArray): Int {
            val presented = endpoint.toHostKey(key)
            val trusted = store.find(endpoint.host, endpoint.port)
            val status = classifySshHostKey(trusted, presented)
            onStatus(status)
            return when (status) {
                is SshHostKeyStatus.Unknown ->
                    HostKeyRepository.NOT_INCLUDED
                is SshHostKeyStatus.Trusted ->
                    HostKeyRepository.OK
                is SshHostKeyStatus.Changed ->
                    HostKeyRepository.CHANGED
                SshHostKeyStatus.NotChecked -> error("Host key must be classified")
            }
        }

        override fun add(hostkey: HostKey, ui: UserInfo?) {
            // Trust is persisted only through the explicit fingerprint confirmation flow.
        }

        override fun remove(host: String?, type: String?) {
            // Persistent trust changes only through the explicit UI flow.
        }

        override fun remove(host: String?, type: String?, key: ByteArray?) {
            // Persistent trust changes only through the explicit UI flow.
        }

        override fun getKnownHostsRepositoryID(): String = "GlassBar SSH known hosts"

        override fun getHostKey(): Array<HostKey> = getHostKey(null, null)

        override fun getHostKey(host: String?, type: String?): Array<HostKey> {
            val trusted = store.find(endpoint.host, endpoint.port) ?: return emptyArray()
            if (type != null && type != trusted.algorithm) return emptyArray()
            return runCatching {
                arrayOf(HostKey(endpoint.jSchHost, decodeSshHostKey(trusted.keyBase64)))
            }.getOrDefault(emptyArray())
        }
    }

    private class Endpoint(rawHost: String, val port: Int) {
        val host: String = rawHost.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .trimEnd('.')
            .lowercase(Locale.ROOT)

        init {
            require(host.isNotBlank()) { "SSH host must not be blank" }
            require(port in 1..65535) { "SSH port is out of range" }
        }

        val jSchHost: String = if (port == 22) host else "[$host]:$port"
        val preferenceKey: String = "host:${encodeUrlSafe("$host\u0000$port")}"

        fun toHostKey(key: ByteArray): SshHostKey = SshHostKey(
            host = host,
            port = port,
            algorithm = algorithmOfSshHostKey(key),
            keyBase64 = encodeSshHostKey(key),
            fingerprintSha256 = fingerprintSshHostKey(key),
        )
    }

    companion object {
        private const val PREFS_NAME = "ssh_known_hosts"

        private fun encodeUrlSafe(value: String): String = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }
}
