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

/** Base class for all recoverable Stats-fetch failures. */
sealed class StatsFetchException(message: String) : IOException(message)

/** The fetch did not complete within the configured timeout. */
class StatsFetchTimeoutException : StatsFetchException("Stats fetch timed out")

/** The remote command produced more output than the configured cap. */
class StatsOutputTooLargeException : StatsFetchException("Stats command output exceeded size limit")

/** The remote command exited with a non-zero status code. */
class StatsCommandFailedException(exitCode: Int, stderr: String) : StatsFetchException(
    "Stats command exited with code $exitCode" + if (stderr.isNotBlank()) ": $stderr" else "",
)

/** The command output is missing an expected field. */
class StatsParseException(field: String) : StatsFetchException("Stats output missing field: $field")

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

    /**
     * Fetches CPU and memory statistics for the given host.
     *
     * Returns [ServerStats] on success.  On failure throws a [StatsFetchException] subtype or
     * any underlying [IOException] / SSH exception.  [CancellationException] is always
     * re-thrown so coroutine cancellation propagates correctly.
     *
     * Callers are responsible for catching exceptions and mapping them to user-visible strings.
     */
    suspend fun fetch(
        host: String,
        port: Int,
        username: String,
        password: String,
        configureSession: StatsSessionConfigurator = { _, _ -> },
    ): ServerStats = withContext(Dispatchers.IO) {
        val output = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            fetchOutput(host, port, username, password, configureSession)
        } ?: throw StatsFetchTimeoutException()
        parse(output, System.currentTimeMillis())
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
                throw StatsOutputTooLargeException()
            }
            val stderr = errorOutput.toString(Charsets.UTF_8.name()).trim()
            if (channel.exitStatus != 0) {
                throw StatsCommandFailedException(channel.exitStatus, stderr)
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
            ?: throw StatsParseException("CPU")
        val memory = values["MEM"]?.toFloatOrNull()?.takeIf { it.isFinite() }
            ?: throw StatsParseException("MEM")

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
