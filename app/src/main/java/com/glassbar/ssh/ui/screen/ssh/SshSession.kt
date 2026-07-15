package com.glassbar.ssh.ui.screen.ssh

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

enum class SshConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

data class SshConfig(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
)

class SshSession(
    private val terminalBuffer: TerminalBuffer,
) {
    private val _state = MutableStateFlow(SshConnectionState.DISCONNECTED)
    val state: StateFlow<SshConnectionState> = _state.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connectMutex = Mutex()
    private val sendMutex = Mutex()
    private val connectionGeneration = AtomicLong(0L)
    private var outputBuffer: TerminalOutputBuffer? = null

    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var readThread: Thread? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    val isConnected: Boolean get() = _state.value == SshConnectionState.CONNECTED

    suspend fun connect(config: SshConfig) = connectMutex.withLock {
      withContext(Dispatchers.IO) {
        if (_state.value == SshConnectionState.CONNECTING) return@withContext
        if (_state.value == SshConnectionState.CONNECTED) {
            disconnect()
        }

        _state.value = SshConnectionState.CONNECTING
        _errorMessage.value = null
        terminalBuffer.clear()
        val generation = connectionGeneration.incrementAndGet()
        val sessionOutput = TerminalOutputBuffer { text ->
            if (connectionGeneration.get() == generation) terminalBuffer.write(text)
        }
        outputBuffer = sessionOutput

        try {
            val jsch = JSch()

            // Disable strict host key checking
            val sshProps = java.util.Properties()
            sshProps.setProperty("StrictHostKeyChecking", "no")
            sshProps.setProperty("PreferredAuthentications", "password,keyboard-interactive")
            JSch.setConfig(sshProps)

            val sshSession = jsch.getSession(config.username, config.host, config.port)
            session = sshSession
            sshSession.setPassword(config.password)
            sshSession.setTimeout(10000)
            sshSession.connect(10000)

            val shellChannel = sshSession.openChannel("shell") as? ChannelShell
                ?: throw Exception("Failed to open shell channel")
            channel = shellChannel

            // Request a pseudo-terminal
            shellChannel.setPtySize(
                terminalBuffer.cols, terminalBuffer.rows,
                terminalBuffer.cols * 8, terminalBuffer.rows * 16
            )
            shellChannel.setPtyType("xterm-256color")

            val shellOutput = shellChannel.outputStream
            outputStream = shellOutput
            shellChannel.connect(5000)
            val shellInput = shellChannel.inputStream
            inputStream = shellInput

            // Mark connected before starting the reader so an immediate EOF is
            // handled as a real remote disconnect rather than being missed.
            _state.value = SshConnectionState.CONNECTED

            // Start read thread
            readThread = Thread {
                val reader = InputStreamReader(shellInput, Charsets.UTF_8)
                val buf = CharArray(4096)
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val n = reader.read(buf)
                        if (n == -1) break
                        val str = String(buf, 0, n)
                        sessionOutput.add(str)
                    }
                    if (!Thread.currentThread().isInterrupted &&
                        connectionGeneration.get() == generation &&
                        _state.value == SshConnectionState.CONNECTED
                    ) {
                        disconnectIfCurrent(generation)
                    }
                } catch (e: Exception) {
                    if (readThread?.isInterrupted == false && connectionGeneration.get() == generation) {
                        val errMsg = "ERR: ${e.message ?: "unknown"}"
                        _errorMessage.value = errMsg
                        disconnectIfCurrent(generation)
                    }
                }
            }.apply {
                name = "SSH-Reader"
                isDaemon = true
                start()
            }

        } catch (e: Exception) {
            _state.value = SshConnectionState.ERROR
            _errorMessage.value = e.message ?: "Connection failed"
            disconnectIfCurrent(generation)
        }
      }
    }

    fun send(data: String) {
        if (_state.value != SshConnectionState.CONNECTED) return
        val generation = connectionGeneration.get()
        ioScope.launch {
            sendMutex.withLock {
                if (generation != connectionGeneration.get() || _state.value != SshConnectionState.CONNECTED) return@withLock
                try {
                    outputStream?.write(data.toByteArray(Charsets.UTF_8))
                    outputStream?.flush()
                } catch (_: Exception) {
                    // The reader/connection lifecycle reports disconnects.
                }
            }
        }
    }

    fun send(bytes: ByteArray) {
        if (_state.value != SshConnectionState.CONNECTED) return
        val generation = connectionGeneration.get()
        ioScope.launch {
            sendMutex.withLock {
                if (generation != connectionGeneration.get() || _state.value != SshConnectionState.CONNECTED) return@withLock
                try {
                    outputStream?.write(bytes)
                    outputStream?.flush()
                } catch (_: Exception) {
                    // The reader/connection lifecycle reports disconnects.
                }
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0 || _state.value != SshConnectionState.CONNECTED) return
        runCatching { channel?.setPtySize(cols, rows, cols * 8, rows * 16) }
    }

    fun disconnect() {
        connectionGeneration.incrementAndGet()
        cleanupConnection()
    }

    private fun disconnectIfCurrent(generation: Long) {
        if (!connectionGeneration.compareAndSet(generation, generation + 1)) return
        cleanupConnection()
    }

    private fun cleanupConnection() {
        outputBuffer?.clear()
        outputBuffer = null
        ioScope.cancel()
        ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
        _state.value = SshConnectionState.DISCONNECTED
    }
}
