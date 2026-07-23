package com.glassbar.ssh.ui.screen.ssh

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glassbar.ssh.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val context: Application
        get() = getApplication()

    private val _connections = MutableStateFlow(SshConnectionStore.getAll(context))
    val connections: StateFlow<List<SshConnectionInfo>> = _connections.asStateFlow()

    private val _serverStats = MutableStateFlow<Map<String, ServerStats>>(emptyMap())
    val serverStats: StateFlow<Map<String, ServerStats>> = _serverStats.asStateFlow()

    private val _refreshingIds = MutableStateFlow<Set<String>>(emptySet())
    val refreshingIds: StateFlow<Set<String>> = _refreshingIds.asStateFlow()

    val storageLoadError: StateFlow<SshConnectionLoadError?> = SshConnectionStore.loadError

    private val _storageOperationError = MutableStateFlow<String?>(null)
    val storageOperationError: StateFlow<String?> = _storageOperationError.asStateFlow()

    private val refreshSemaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
    private var failureCounts = emptyMap<String, Int>()
    private var nextAutoRefreshAt = emptyMap<String, Long>()
    private var pollingJob: Job? = null
    private var manualRefreshJob: Job? = null
    @Volatile
    private var isScreenActive = false

    fun setActive(active: Boolean) {
        isScreenActive = active
        if (!active) {
            pollingJob?.cancel()
            pollingJob = null
            manualRefreshJob?.cancel()
            manualRefreshJob = null
            return
        }
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val due = if (storageLoadError.value == null) {
                    _connections.value.filter { connection ->
                        connection.hasSavedAuthentication() &&
                            (nextAutoRefreshAt[connection.id] ?: 0L) <= now
                    }
                } else {
                    emptyList()
                }
                refresh(due)
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    fun refreshNow(connection: SshConnectionInfo) {
        if (!isScreenActive ||
            storageLoadError.value != null ||
            !connection.hasSavedAuthentication() ||
            manualRefreshJob?.isActive == true
        ) {
            return
        }
        manualRefreshJob = viewModelScope.launch {
            refresh(listOf(connection))
        }
    }

    fun add(connection: SshConnectionInfo): Boolean = mutateConnections {
        SshConnectionStore.add(context, connection)
    }

    fun update(connection: SshConnectionInfo): Boolean = mutateConnections {
        SshConnectionStore.update(context, connection)
    }

    fun delete(id: String): Boolean = mutateConnections {
        SshConnectionStore.delete(context, id)
    }

    fun clearStorageOperationError() {
        _storageOperationError.value = null
    }

    fun resetCorruptConnections(): Boolean = runCatching {
        SshConnectionStore.replaceCorruptData(context, emptyList())
        reloadConnections()
        _storageOperationError.value = null
    }.fold(
        onSuccess = { true },
        onFailure = {
            runCatching { reloadConnections() }
            _storageOperationError.value = context.getString(R.string.connection_storage_write_failed)
            false
        },
    )

    private inline fun mutateConnections(block: () -> Unit): Boolean = runCatching {
        block()
        reloadConnections()
        _storageOperationError.value = null
    }.fold(
        onSuccess = { true },
        onFailure = {
            _storageOperationError.value = context.getString(R.string.connection_storage_write_failed)
            false
        },
    )

    private fun reloadConnections() {
        val previousConnections = _connections.value.associateBy(SshConnectionInfo::id)
        val loaded = SshConnectionStore.getAll(context)
        _connections.value = loaded
        val unchangedIds = loaded
            .filterTo(mutableListOf()) { previousConnections[it.id] == it }
            .mapTo(mutableSetOf(), SshConnectionInfo::id)
        _serverStats.value = _serverStats.value.filterKeys(unchangedIds::contains)
        _refreshingIds.value = _refreshingIds.value.filter(unchangedIds::contains).toSet()
        failureCounts = failureCounts.filterKeys(unchangedIds::contains)
        nextAutoRefreshAt = nextAutoRefreshAt.filterKeys(unchangedIds::contains)
    }

    private suspend fun refresh(
        targets: List<SshConnectionInfo>,
    ) {
        val requestedTargets = targets.distinctBy(SshConnectionInfo::id)
        if (requestedTargets.isEmpty()) return

        val currentConnectionsAtStart = _connections.value.associateBy(SshConnectionInfo::id)
        var refreshTargets = emptyList<SshConnectionInfo>()
        
        _refreshingIds.update { currentRefreshing ->
            refreshTargets = requestedTargets.filter { target ->
                currentConnectionsAtStart[target.id] == target && target.id !in currentRefreshing
            }
            if (refreshTargets.isEmpty() || storageLoadError.value != null) {
                currentRefreshing
            } else {
                currentRefreshing + refreshTargets.map { it.id }
            }
        }

        if (refreshTargets.isEmpty() || storageLoadError.value != null) return

        try {
            val results = supervisorScope {
                refreshTargets.map { connection ->
                    async {
                        refreshSemaphore.withPermit {
                            connection.id to fetchStats(connection)
                        }
                    }
                }.awaitAll().toMap()
            }
            val requestedConnections = refreshTargets.associateBy(SshConnectionInfo::id)
            val currentConnections = _connections.value.associateBy(SshConnectionInfo::id)
            val currentResults = results.filter { (id, _) ->
                currentConnections[id] == requestedConnections[id]
            }
            _serverStats.value = _serverStats.value + currentResults

            val completedAt = System.currentTimeMillis()
            val updatedFailures = failureCounts.toMutableMap()
            val updatedNextRefresh = nextAutoRefreshAt.toMutableMap()
            currentResults.forEach { (id, stats) ->
                if (stats.error == null) {
                    updatedFailures.remove(id)
                    updatedNextRefresh[id] = completedAt + REFRESH_INTERVAL_MS
                } else {
                    val failureCount = (updatedFailures[id] ?: 0) + 1
                    updatedFailures[id] = failureCount
                    val multiplier = 1L shl (failureCount - 1).coerceAtMost(4)
                    updatedNextRefresh[id] = completedAt +
                        (REFRESH_INTERVAL_MS * multiplier).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
            failureCounts = updatedFailures
            nextAutoRefreshAt = updatedNextRefresh
        } finally {
            _refreshingIds.update { it - refreshTargets.mapTo(mutableSetOf(), SshConnectionInfo::id) }
        }
    }

    private suspend fun fetchStats(connection: SshConnectionInfo): ServerStats {
        var privateKey: ByteArray? = null
        return try {
            privateKey = readPrivateKey(context, connection.privateKeyUri)
            StatsFetcher.fetch(
                host = connection.host,
                port = connection.port,
                username = connection.username,
                password = connection.password,
                configureSession = statsSessionConfigurator(context, connection, privateKey),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            ServerStats(error = context.getString(R.string.server_stats_unavailable))
        } finally {
            privateKey?.fill(0)
        }
    }

    private fun SshConnectionInfo.hasSavedAuthentication(): Boolean =
        password.isNotBlank() || privateKeyUri.isNotBlank()

    private companion object {
        const val REFRESH_INTERVAL_MS = 30_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L
        const val MAX_CONCURRENT_REQUESTS = 3
    }
}
