package com.glassbar.ssh.ui.screen.ssh

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

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

    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var readThread: Thread? = null
    private var outputStream: OutputStream? = null

    val isConnected: Boolean get() = _state.value == SshConnectionState.CONNECTED

    suspend fun connect(config: SshConfig) = withContext(Dispatchers.IO) {
        if (_state.value == SshConnectionState.CONNECTED) {
            disconnect()
        }

        _state.value = SshConnectionState.CONNECTING
        _errorMessage.value = null
        terminalBuffer.clear()

        try {
            val jsch = JSch()

            // Disable strict host key checking
            val sshProps = java.util.Properties()
            sshProps.setProperty("StrictHostKeyChecking", "no")
            sshProps.setProperty("PreferredAuthentications", "password,keyboard-interactive")
            JSch.setConfig(sshProps)

            session = jsch.getSession(config.username, config.host, config.port)
            session?.setPassword(config.password)
            session?.setTimeout(10000)
            session?.connect(10000)

            channel = session?.openChannel("shell") as? ChannelShell
                ?: throw Exception("Failed to open shell channel")

            // Request a pseudo-terminal
            channel?.setPtySize(
                terminalBuffer.cols, terminalBuffer.rows,
                terminalBuffer.cols * 8, terminalBuffer.rows * 16
            )
            channel?.setPtyType("xterm-256color")

            outputStream = channel?.outputStream
            channel?.connect(5000)
            val inputStream = channel?.inputStream
                ?: throw Exception("Failed to get input stream")

            // Start read thread
            readThread = Thread {
                try {
                    val buf = ByteArray(4096)
                    while (!Thread.currentThread().isInterrupted) {
                        val n = inputStream.read(buf)
                        if (n == -1) break
                        terminalBuffer.write(buf, 0, n)
                    }
                } catch (e: InterruptedException) {
                    terminalBuffer.write(" [EOF]\n".toByteArray())
                } catch (e: Exception) {
                    if (readThread?.isInterrupted == false) {
                        val errMsg = "ERR: ${e.message ?: "unknown"}"
                        _errorMessage.value = errMsg
                        terminalBuffer.write((errMsg + "\n").toByteArray())
                    }
                }
            }.apply {
                name = "SSH-Reader"
                isDaemon = true
                start()
            }

            // Write a marker to confirm the read thread should receive data
            terminalBuffer.write("~\n".toByteArray())

            _state.value = SshConnectionState.CONNECTED
        } catch (e: Exception) {
            _state.value = SshConnectionState.ERROR
            _errorMessage.value = e.message ?: "Connection failed"
            disconnect()
        }
    }

    fun send(data: String) {
        try {
            outputStream?.write(data.toByteArray())
            outputStream?.flush()
        } catch (_: Exception) {
        }
    }

    fun send(bytes: ByteArray) {
        if (_state.value != SshConnectionState.CONNECTED) return
        try {
            outputStream?.write(bytes)
            outputStream?.flush()
        } catch (_: Exception) {
            // Connection lost
        }
    }

    fun resize(cols: Int, rows: Int) {
        channel?.setPtySize(cols, rows, cols * 8, rows * 16)
    }

    fun disconnect() {
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
