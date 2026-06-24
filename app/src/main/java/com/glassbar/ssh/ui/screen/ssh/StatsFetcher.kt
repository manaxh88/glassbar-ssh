package com.glassbar.ssh.ui.screen.ssh

import com.jcraft.jsch.JSch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ServerStats(
    val cpuPercent: Float = 0f,
    val memPercent: Float = 0f,
    val error: String? = null,
)

object StatsFetcher {

    suspend fun fetch(host: String, port: Int, username: String, password: String): ServerStats =
        withContext(Dispatchers.IO) {
            try {
                val jsch = JSch()
                val config = java.util.Properties()
                config.setProperty("StrictHostKeyChecking", "no")
                JSch.setConfig(config)
                val session = jsch.getSession(username, host, port)
                session.setPassword(password)
                session.connect(5000)

                val cpu = execCommand(session, "top -bn1 | grep 'Cpu(s)' | awk '{print \$2+\$4}'")
                val memTotal = execCommand(session, "free -m | grep Mem | awk '{print \$2}'")
                val memUsed = execCommand(session, "free -m | grep Mem | awk '{print \$3}'")

                session.disconnect()

                val cpuVal = cpu.trim().toFloatOrNull() ?: 0f
                val total = memTotal.trim().toFloatOrNull() ?: 1f
                val used = memUsed.trim().toFloatOrNull() ?: 0f
                val memVal = if (total > 0) (used / total * 100f).coerceIn(0f, 100f) else 0f

                ServerStats(cpuPercent = cpuVal, memPercent = memVal)
            } catch (e: Exception) {
                ServerStats(error = e.message)
            }
        }

    private fun execCommand(session: com.jcraft.jsch.Session, command: String): String {
        val channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
        channel.setCommand(command)
        channel.inputStream
        val result = channel.inputStream.bufferedReader().readText()
        channel.disconnect()
        return result
    }
}
