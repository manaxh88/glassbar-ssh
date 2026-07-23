package com.glassbar.ssh.ui.screen.ssh

import com.glassbar.ssh.glassBarApp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock as jvmWithLock

enum class SshConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

/**
 * Configuration payload for an SSH connection.
 *
 * Note: Array fields ([privateKey], [publicKey], [privateKeyPassphrase]) use JVM array identity
 * equality per Kotlin `data class` rules. Compare array content explicitly with [contentEquals]
 * if equality checks are needed.
 */
data class SshConfig(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
    /** OpenSSH/PEM private-key bytes. They are never persisted by SshSession. */
    val privateKey: ByteArray? = null,
    val publicKey: ByteArray? = null,
    val privateKeyPassphrase: ByteArray? = null,
    val privateKeyName: String = "glassbar-imported-key",
)

class SshSession(
    private val terminalBuffer: TerminalBuffer,
    private val knownHostsStore: SshKnownHostsStore = SshKnownHostsStore(glassBarApp),
) {
    private val _state = MutableStateFlow(SshConnectionState.DISCONNECTED)
    val state: StateFlow<SshConnectionState> = _state.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _hostKeyStatus = MutableStateFlow<SshHostKeyStatus>(SshHostKeyStatus.NotChecked)
    val hostKeyStatus: StateFlow<SshHostKeyStatus> = _hostKeyStatus.asStateFlow()

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sessionOutputBuffer = TerminalOutputBuffer { text ->
        terminalBuffer.write(text)
    }

    /**
     * Dual-lock protocol:
     *
     * [connectMutex] — coroutine-level Mutex that serialises the entire connect / disconnect
     * lifecycle.  It is suspending, so it never blocks a thread while waiting.
     *
     * [lifecycleLock] — JVM ReentrantLock that protects brief, non-suspending reads and writes of
     * mutable fields (session, channel, streams, writerEndpoint, …).  It is held only for the
     * duration of a few field assignments or reads, never across a suspension point, and never
     * while waiting to acquire [connectMutex].  This one-way nesting order (connectMutex → then
     * lifecycleLock, never the reverse) makes deadlock impossible.
     *
     * [connectionGeneration] — AtomicLong that lets background threads and coroutines detect
     * stale generations without entering either lock.
     */
    private val connectMutex = Mutex()
    private val lifecycleLock = ReentrantLock()
    private val connectionGeneration = AtomicLong(0L)
    private var writerEndpoint: WriterEndpoint? = null

    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var readThread: Thread? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    val isConnected: Boolean get() = _state.value == SshConnectionState.CONNECTED

    suspend fun connect(config: SshConfig) = connectMutex.withLock {
      try {
       withContext(Dispatchers.IO) {
        if (_state.value == SshConnectionState.CONNECTING) return@withContext
        if (_state.value == SshConnectionState.CONNECTED) {
            disconnect()
        }

        val generation = lifecycleLock.jvmWithLock {
            _state.value = SshConnectionState.CONNECTING
            _errorMessage.value = null
            _hostKeyStatus.value = SshHostKeyStatus.NotChecked
            connectionGeneration.incrementAndGet()
        }
        terminalBuffer.clear()
        sessionOutputBuffer.reset()

        var connectingSession: Session? = null
        var connectingChannel: ChannelShell? = null
        val jsch = JSch()

        try {
            config.privateKey?.let { privateKey ->
                jsch.addIdentity(
                    config.privateKeyName,
                    privateKey,
                    config.publicKey,
                    config.privateKeyPassphrase,
                )
            }
            jsch.setHostKeyRepository(
                knownHostsStore.repository(config.host, config.port) { status ->
                    lifecycleLock.jvmWithLock {
                        if (connectionGeneration.get() == generation) {
                            _hostKeyStatus.value = status
                        }
                    }
                },
            )

            val sshSession = jsch.getSession(config.username, config.host, config.port)
            connectingSession = sshSession
            if (!installSession(generation, sshSession)) {
                sshSession.disconnect()
                return@withContext
            }
            sshSession.setConfig("StrictHostKeyChecking", "yes")
            sshSession.setConfig(
                "PreferredAuthentications",
                if (config.privateKey != null) {
                    "publickey,password,keyboard-interactive"
                } else {
                    "password,keyboard-interactive"
                },
            )
            if (config.password.isNotEmpty()) {
                sshSession.setPassword(config.password)
            }
            sshSession.setTimeout(SESSION_SOCKET_TIMEOUT_MS)
            sshSession.connect(SESSION_CONNECT_TIMEOUT_MS)
            if (connectionGeneration.get() != generation) {
                sshSession.disconnect()
                return@withContext
            }

            val shellChannel = sshSession.openChannel("shell") as? ChannelShell
                ?: throw Exception("Failed to open shell channel")
            connectingChannel = shellChannel
            if (!installChannel(generation, shellChannel)) {
                shellChannel.disconnect()
                sshSession.disconnect()
                return@withContext
            }

            // Request a pseudo-terminal
            shellChannel.setPtySize(
                terminalBuffer.cols, terminalBuffer.rows,
                terminalBuffer.cols * 8, terminalBuffer.rows * 16
            )
            shellChannel.setPtyType("xterm-256color")

            val shellOutput = shellChannel.outputStream
            if (!installOutputStream(generation, shellOutput)) {
                shellChannel.disconnect()
                sshSession.disconnect()
                return@withContext
            }
            shellChannel.connect(SHELL_CONNECT_TIMEOUT_MS)
            if (connectionGeneration.get() != generation) {
                shellChannel.disconnect()
                sshSession.disconnect()
                return@withContext
            }
            val shellInput = shellChannel.inputStream
            val writerQueue = Channel<ByteArray>(Channel.BUFFERED)
            val sshReader = Thread {
                val buf = CharArray(4096)
                try {
                    val reader = InputStreamReader(shellInput, Charsets.UTF_8)
                    while (!Thread.currentThread().isInterrupted) {
                        val n = reader.read(buf)
                        if (n == -1) break
                        if (connectionGeneration.get() == generation) {
                            val str = String(buf, 0, n)
                            sessionOutputBuffer.add(str)
                        }
                    }
                    if (!Thread.currentThread().isInterrupted &&
                        connectionGeneration.get() == generation &&
                        _state.value == SshConnectionState.CONNECTED
                    ) {
                        disconnectIfCurrent(generation)
                    }
                } catch (e: Exception) {
                    if (!Thread.currentThread().isInterrupted &&
                        connectionGeneration.get() == generation
                    ) {
                        val errMsg = "ERR: ${e.message ?: "unknown"}"
                        disconnectIfCurrent(
                            generation,
                            finalState = SshConnectionState.ERROR,
                            errorMessage = errMsg,
                        )
                    }
                }
            }.apply {
                name = "SSH-Reader"
                isDaemon = true
            }

            // Installing all runtime resources and publishing CONNECTED share
            // the same lock as disconnect(), so a cancelled generation can
            // never become connected after its final generation check.
            val writerScope = lifecycleLock.jvmWithLock {
                if (connectionGeneration.get() != generation) {
                    null
                } else {
                    inputStream = shellInput
                    writerEndpoint = WriterEndpoint(generation, writerQueue)
                    readThread = sshReader
                    _state.value = SshConnectionState.CONNECTED
                    ioScope
                }
            }
            if (writerScope == null) {
                writerQueue.close()
                shellChannel.disconnect()
                sshSession.disconnect()
                return@withContext
            }
            startWriter(writerScope, generation, writerQueue, shellOutput)
            sshReader.start()

        } catch (cancelled: CancellationException) {
            connectingChannel?.runCatching { disconnect() }
            connectingSession?.runCatching { disconnect() }
            disconnectIfCurrent(generation)
            throw cancelled
        } catch (e: Exception) {
            connectingChannel?.runCatching { disconnect() }
            connectingSession?.runCatching { disconnect() }
            if (connectionGeneration.get() == generation) {
                disconnectIfCurrent(
                    generation,
                    finalState = SshConnectionState.ERROR,
                    errorMessage = hostKeyErrorMessage() ?: e.message ?: "Connection failed",
                )
            }
        } finally {
            // JSch wipes its password byte array when connect() completes.
            // Explicitly remove parsed identities as soon as authentication is done.
            runCatching { jsch.removeAllIdentity() }
        }
       }
      } catch (cancelled: CancellationException) {
          // withContext can surface cancellation after its block has returned.
          // Always tear down the matching runtime instead of publishing a
          // late connected or error state.
          disconnect()
          throw cancelled
      }
    }

    /**
     * Persists the currently presented key only when the caller confirms the
     * exact fingerprint shown to the user. Call [connect] again afterwards.
     */
    fun trustPendingHostKey(expectedFingerprintSha256: String): Boolean {
        return lifecycleLock.jvmWithLock {
            val presented = when (val status = _hostKeyStatus.value) {
                is SshHostKeyStatus.Unknown -> status.presented
                is SshHostKeyStatus.Changed -> status.presented
                else -> return@jvmWithLock false
            }
            if (presented.fingerprintSha256 != expectedFingerprintSha256) {
                return@jvmWithLock false
            }

            val trusted = runCatching {
                knownHostsStore.trust(presented)
                knownHostsStore.find(presented.host, presented.port)
                    ?: error("Trusted SSH host key could not be reloaded")
            }.getOrElse {
                _errorMessage.value = "Failed to persist trusted SSH host key"
                return@jvmWithLock false
            }
            _hostKeyStatus.value = SshHostKeyStatus.Trusted(trusted)
            true
        }
    }

    fun forgetHostKey(host: String, port: Int) {
        knownHostsStore.forget(host, port)
        val current = _hostKeyStatus.value
        val currentKey = when (current) {
            is SshHostKeyStatus.Trusted -> current.hostKey
            is SshHostKeyStatus.Unknown -> current.presented
            is SshHostKeyStatus.Changed -> current.presented
            SshHostKeyStatus.NotChecked -> null
        }
        if (currentKey?.host.equals(host, ignoreCase = true) && currentKey?.port == port) {
            _hostKeyStatus.value = SshHostKeyStatus.NotChecked
        }
    }

    fun send(data: String) {
        val encoded = data.toByteArray(Charsets.UTF_8)
        try {
            send(encoded)
        } finally {
            encoded.fill(0)
        }
    }

    fun send(bytes: ByteArray) {
        val endpoint = lifecycleLock.jvmWithLock {
            writerEndpoint?.takeIf {
                _state.value == SshConnectionState.CONNECTED &&
                    it.generation == connectionGeneration.get()
            }
        } ?: return
        // ownedBytes is a defensive copy so the caller may immediately clear its buffer.
        // The copy lives in the Channel queue until the writer coroutine flushes it, after
        // which startWriter() calls fill(0).  This brief window of in-memory plaintext is
        // an acceptable trade-off: the data is only ever a single pending SSH input packet.
        val ownedBytes = bytes.copyOf()
        if (endpoint.queue.trySend(ownedBytes).isFailure) {
            ownedBytes.fill(0)
            lifecycleLock.jvmWithLock {
                if (writerEndpoint === endpoint &&
                    endpoint.generation == connectionGeneration.get()
                ) {
                    _errorMessage.value = "SSH input queue is full or closed"
                }
            }
        }
    }

    private fun startWriter(
        scope: CoroutineScope,
        generation: Long,
        queue: Channel<ByteArray>,
        output: OutputStream,
    ) {
        scope.launch {
            try {
                for (first in queue) {
                    if (generation != connectionGeneration.get() ||
                        _state.value != SshConnectionState.CONNECTED
                    ) {
                        first.fill(0)
                        break
                    }

                    val chunks = ArrayList<ByteArray>()
                    chunks += first
                    var size = first.size
                    while (size < 4 * 1024) {
                        val next = queue.tryReceive().getOrNull() ?: break
                        chunks += next
                        size += next.size
                    }
                    try {
                        chunks.forEach { chunk -> output.write(chunk) }
                        output.flush()
                    } finally {
                        chunks.forEach { chunk -> chunk.fill(0) }
                    }
                }
            } catch (error: Exception) {
                if (generation == connectionGeneration.get()) {
                    disconnectIfCurrent(
                        generation,
                        finalState = SshConnectionState.ERROR,
                        errorMessage = error.message ?: "SSH input failed",
                    )
                }
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0 || _state.value != SshConnectionState.CONNECTED) return
        val activeChannel = lifecycleLock.jvmWithLock { channel }
        runCatching { activeChannel?.setPtySize(cols, rows, cols * 8, rows * 16) }
    }

    fun disconnect() {
        lifecycleLock.jvmWithLock {
            connectionGeneration.incrementAndGet()
            cleanupConnectionLocked(SshConnectionState.DISCONNECTED)
            _errorMessage.value = null
            _hostKeyStatus.value = SshHostKeyStatus.NotChecked
        }
    }

    private fun disconnectIfCurrent(
        generation: Long,
        finalState: SshConnectionState = SshConnectionState.DISCONNECTED,
        errorMessage: String? = null,
    ) {
        lifecycleLock.jvmWithLock {
            if (connectionGeneration.get() != generation) return
            connectionGeneration.incrementAndGet()
            if (errorMessage != null) _errorMessage.value = errorMessage
            cleanupConnectionLocked(finalState)
        }
    }

    private fun cleanupConnectionLocked(finalState: SshConnectionState) {
        writerEndpoint?.queue?.let { queue ->
            queue.close()
            while (true) {
                val queued = queue.tryReceive().getOrNull() ?: break
                queued.fill(0)
            }
        }
        writerEndpoint = null
        sessionOutputBuffer.reset()
        ioScope.coroutineContext.cancelChildren()
        try {
            inputStream?.close()
        } catch (_: Exception) {}
        inputStream = null
        readThread?.interrupt()
        readThread = null
        try {
            outputStream?.close()
        } catch (_: Exception) {}
        outputStream = null
        try {
            channel?.disconnect()
        } catch (_: Exception) {}
        channel = null
        try {
            session?.disconnect()
        } catch (_: Exception) {}
        session = null
        _state.value = finalState
    }

    private fun installSession(generation: Long, candidate: Session): Boolean =
        lifecycleLock.jvmWithLock {
            if (connectionGeneration.get() != generation) false else {
                session = candidate
                true
            }
        }

    private fun installChannel(generation: Long, candidate: ChannelShell): Boolean =
        lifecycleLock.jvmWithLock {
            if (connectionGeneration.get() != generation) false else {
                channel = candidate
                true
            }
        }

    private fun installOutputStream(generation: Long, candidate: OutputStream): Boolean =
        lifecycleLock.jvmWithLock {
            if (connectionGeneration.get() != generation) false else {
                outputStream = candidate
                true
            }
        }

    private fun hostKeyErrorMessage(): String? = when (val status = _hostKeyStatus.value) {
        is SshHostKeyStatus.Unknown ->
            "Unknown SSH host key (${status.presented.algorithm}) " +
                status.presented.fingerprintSha256
        is SshHostKeyStatus.Changed ->
            "SSH host key changed. Expected ${status.trusted.fingerprintSha256}, " +
                "received ${status.presented.fingerprintSha256}"
        else -> null
    }

    private data class WriterEndpoint(
        val generation: Long,
        val queue: Channel<ByteArray>,
    )
    private companion object {
        const val SESSION_CONNECT_TIMEOUT_MS = 10_000
        const val SESSION_SOCKET_TIMEOUT_MS = 10_000
        const val SHELL_CONNECT_TIMEOUT_MS = 5_000
    }
}
