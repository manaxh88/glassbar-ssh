package com.glassbar.ssh.ui.screen.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Properties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ServerStats(
    val cpuPercent: Float = 0f,
    val memPercent: Float = 0f,
    val error: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

/**
 * Applies the application's known-host policy before a statistics session connects.
 *
 * The callback receives both [JSch] (for installing the known-hosts repository) and the
 * newly-created [Session] (for any per-session policy). The default deliberately does not
 * disable host-key verification; callers should provide the same verifier used by the
 * interactive terminal.
 */
typealias StatsSessionConfigurator = (JSch, Session) -> Unit

object StatsFetcher {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val COMMAND_CONNECT_TIMEOUT_MS = 5_000
    private const val FETCH_TIMEOUT_MS = 15_000L
    private const val COMMAND_POLL_INTERVAL_MS = 25L
    private const val MAX_COMMAND_OUTPUT_BYTES = 64 * 1024

    /**
     * CPU and memory are sampled by one remote shell, so a refresh authenticates only once
     * and opens only one exec channel. `/proc` is used instead of localized `top`/`free` output.
     */
    private val statsCommand = """
        LC_ALL=C
        read _ u1 n1 s1 i1 w1 q1 sq1 st1 _ < /proc/stat
        t1=${'$'}((u1+n1+s1+i1+w1+q1+sq1+st1))
        idle1=${'$'}((i1+w1))
        sleep 0.2
        read _ u2 n2 s2 i2 w2 q2 sq2 st2 _ < /proc/stat
        t2=${'$'}((u2+n2+s2+i2+w2+q2+sq2+st2))
        idle2=${'$'}((i2+w2))
        total=${'$'}((t2-t1))
        busy=${'$'}((total-(idle2-idle1)))
        awk -v busy="${'$'}busy" -v total="${'$'}total" 'BEGIN { printf "CPU=%.2f\n", total > 0 ? busy * 100 / total : 0 }'
        awk '/^MemTotal:/ { total=${'$'}2 } /^MemAvailable:/ { available=${'$'}2 } /^MemFree:/ { free=${'$'}2 } /^Buffers:/ { buffers=${'$'}2 } /^Cached:/ { cached=${'$'}2 } END { if (!available) available=free+buffers+cached; printf "MEM=%.2f\n", total > 0 ? (total-available) * 100 / total : 0 }' /proc/meminfo
    """.trimIndent()

    suspend fun fetch(
        host: String,
        port: Int,
        username: String,
        password: String,
        configureSession: StatsSessionConfigurator = { _, _ -> },
    ): ServerStats = withContext(Dispatchers.IO) {
        try {
            val output = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                fetchOutput(host, port, username, password, configureSession)
            } ?: return@withContext ServerStats(
                error = "获取服务器状态超时",
                updatedAtMillis = System.currentTimeMillis(),
            )
            parse(output, System.currentTimeMillis())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ServerStats(
                error = error.message?.takeIf(String::isNotBlank) ?: "无法获取服务器状态",
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun fetchOutput(
        host: String,
        port: Int,
        username: String,
        password: String,
        configureSession: StatsSessionConfigurator,
    ): String {
        var session: Session? = null
        try {
            val jsch = JSch()
            val activeSession = jsch.getSession(username, host, port).apply {
                setConfig(Properties().apply {
                    setProperty("PreferredAuthentications", "publickey,keyboard-interactive,password")
                })
                setPassword(password)
                setServerAliveInterval(5_000)
                setServerAliveCountMax(1)
            }
            session = activeSession
            configureSession(jsch, activeSession)
            currentCoroutineContext().ensureActive()
            activeSession.connect(CONNECT_TIMEOUT_MS)
            currentCoroutineContext().ensureActive()
            return execCommand(activeSession, statsCommand)
        } finally {
            session?.runCatching { disconnect() }
        }
    }

    private suspend fun execCommand(session: Session, command: String): String {
        var channel: ChannelExec? = null
        val standardOutput = LimitedByteArrayOutputStream(MAX_COMMAND_OUTPUT_BYTES)
        val errorOutput = LimitedByteArrayOutputStream(MAX_COMMAND_OUTPUT_BYTES)
        try {
            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            channel.setInputStream(null)
            channel.setOutputStream(standardOutput)
            channel.setErrStream(errorOutput)
            channel.connect(COMMAND_CONNECT_TIMEOUT_MS)

            while (!channel.isClosed) {
                currentCoroutineContext().ensureActive()
                delay(COMMAND_POLL_INTERVAL_MS)
            }

            if (standardOutput.limitExceeded || errorOutput.limitExceeded) {
                throw IOException("服务器状态命令输出过大")
            }
            val stderr = errorOutput.toString(Charsets.UTF_8.name()).trim()
            if (channel.exitStatus != 0) {
                throw IOException(stderr.ifBlank { "服务器状态命令执行失败（${channel.exitStatus}）" })
            }
            return standardOutput.toString(Charsets.UTF_8.name())
        } finally {
            channel?.runCatching { disconnect() }
        }
    }

    internal fun parse(output: String, updatedAt: Long): ServerStats {
        val values = output.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }
            .toMap()

        val cpu = values["CPU"]?.toFloatOrNull()?.takeIf { it.isFinite() }
            ?: throw IOException("服务器未返回 CPU 数据")
        val memory = values["MEM"]?.toFloatOrNull()?.takeIf { it.isFinite() }
            ?: throw IOException("服务器未返回内存数据")

        return ServerStats(
            cpuPercent = cpu.coerceIn(0f, 100f),
            memPercent = memory.coerceIn(0f, 100f),
            updatedAtMillis = updatedAt,
        )
    }
}

private class LimitedByteArrayOutputStream(
    private val maxBytes: Int,
) : ByteArrayOutputStream(minOf(maxBytes, 1024)) {
    @Volatile
    var limitExceeded: Boolean = false
        private set

    @Synchronized
    override fun write(value: Int) {
        if (count < maxBytes) {
            super.write(value)
        } else {
            limitExceeded = true
        }
    }

    @Synchronized
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        val remaining = (maxBytes - count).coerceAtLeast(0)
        val accepted = minOf(length, remaining)
        if (accepted > 0) {
            super.write(buffer, offset, accepted)
        }
        if (accepted < length) {
            limitExceeded = true
        }
    }
}
