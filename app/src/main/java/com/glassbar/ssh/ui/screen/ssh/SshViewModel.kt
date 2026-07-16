package com.glassbar.ssh.ui.screen.ssh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SshViewModel : ViewModel() {
    val terminalBuffer = TerminalBuffer(rows = 24, cols = 80)
    val session = SshSession(terminalBuffer)

    private var connectJob: Job? = null
    private val configLock = Any()
    private var retainedCallerConfig: SshConfig? = null

    fun connect(config: SshConfig) {
        val ownedConfig = config.copyWithOwnedSecrets()
        synchronized(configLock) {
            if (retainedCallerConfig !== config) {
                retainedCallerConfig?.clearOwnedSecrets()
            }
            retainedCallerConfig = config
        }
        connectJob?.cancel()
        session.disconnect()
        val job = viewModelScope.launch {
            session.connect(ownedConfig)
        }
        connectJob = job
        // Completion handlers run even when the scope was already cancelled
        // and the coroutine body never started.
        job.invokeOnCompletion {
            ownedConfig.clearOwnedSecrets()
            synchronized(configLock) {
                if (connectJob === job && !session.hostKeyStatus.value.requiresApproval()) {
                    config.clearOwnedSecrets()
                    if (retainedCallerConfig === config) retainedCallerConfig = null
                }
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        session.disconnect()
        synchronized(configLock) {
            retainedCallerConfig?.clearOwnedSecrets()
            retainedCallerConfig = null
        }
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }
}

internal fun SshConfig.copyWithOwnedSecrets(): SshConfig = copy(
    privateKey = privateKey?.copyOf(),
    publicKey = publicKey?.copyOf(),
    privateKeyPassphrase = privateKeyPassphrase?.copyOf(),
)

internal fun SshConfig.clearOwnedSecrets() {
    privateKey?.fill(0)
    publicKey?.fill(0)
    privateKeyPassphrase?.fill(0)
}

private fun SshHostKeyStatus.requiresApproval(): Boolean =
    this is SshHostKeyStatus.Unknown || this is SshHostKeyStatus.Changed
