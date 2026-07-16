package com.glassbar.ssh.ui.screen.ssh

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

private const val MAX_PRIVATE_KEY_BYTES = 2 * 1024 * 1024

internal fun persistPrivateKeyPermission(context: Context, uri: Uri): Boolean =
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }.isSuccess

internal suspend fun readPrivateKey(context: Context, uriValue: String): ByteArray? {
    if (uriValue.isBlank()) return null
    return withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(uriValue.toUri())
            ?: throw IOException("Unable to open the selected SSH private key")
        inputStream.use { input ->
            val buffer = ByteArray(8 * 1024)
            try {
                WipingByteArrayOutputStream().use { output ->
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_PRIVATE_KEY_BYTES) {
                            "SSH private key must not exceed 2 MB"
                        }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
            } finally {
                buffer.fill(0)
            }
        }
    }
}

internal fun privateKeyDisplayName(context: Context, uriValue: String): String {
    if (uriValue.isBlank()) return ""
    val uri = uriValue.toUri()
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }
}

internal fun statsSessionConfigurator(
    context: Context,
    connection: SshConnectionInfo,
    privateKey: ByteArray?,
): StatsSessionConfigurator {
    val knownHosts = SshKnownHostsStore(context)
    val hasPrivateKey = privateKey != null
    var pendingPrivateKey = privateKey
    return { jsch, session ->
        jsch.setHostKeyRepository(
            knownHosts.repository(connection.host, connection.port) { },
        )
        pendingPrivateKey?.let { key ->
            pendingPrivateKey = null
            try {
                jsch.addIdentity("glassbar-${connection.id}", key, null, null)
            } finally {
                key.fill(0)
            }
        }
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig(
            "PreferredAuthentications",
            if (hasPrivateKey) {
                "publickey,password,keyboard-interactive"
            } else {
                "password,keyboard-interactive"
            },
        )
    }
}

private class WipingByteArrayOutputStream : ByteArrayOutputStream() {
    override fun close() {
        buf.fill(0)
        reset()
        super.close()
    }
}
