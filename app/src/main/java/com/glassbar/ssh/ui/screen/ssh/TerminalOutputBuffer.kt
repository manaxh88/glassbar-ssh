package com.glassbar.ssh.ui.screen.ssh

import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Preserves SSH output order while parsing reasonably small batches on one background thread.
 *
 * This class intentionally uses a plain JVM scheduler rather than an Android HandlerThread. It is
 * independently testable, starts its worker lazily, and [close] can cancel queued work immediately.
 */
class TerminalOutputBuffer(
    private val flushIntervalMs: Long = 8L,
    private val maxCharsPerFlush: Int = 4096,
    private val onError: (Throwable) -> Unit = {},
    private val onFlush: (String) -> Unit,
) : AutoCloseable {
    init {
        require(flushIntervalMs >= 0) { "flushIntervalMs cannot be negative" }
        require(maxCharsPerFlush > 0) { "maxCharsPerFlush must be positive" }
    }

    private data class Chunk(val text: String, var offset: Int = 0)

    private val lock = Any()
    private val chunks = ArrayDeque<Chunk>()
    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Terminal-Output").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    private var pendingChars = 0L
    private var scheduledTask: ScheduledFuture<*>? = null
    private var closed = false

    fun add(data: String) {
        if (data.isEmpty()) return
        synchronized(lock) {
            if (closed) return
            chunks.addLast(Chunk(data))
            pendingChars = if (pendingChars > Long.MAX_VALUE - data.length) {
                Long.MAX_VALUE
            } else {
                pendingChars + data.length
            }
            scheduleLocked(flushIntervalMs)
        }
    }

    private fun scheduleLocked(delayMs: Long) {
        if (closed || scheduledTask != null || pendingChars == 0L) return
        try {
            scheduledTask = worker.schedule(::doFlush, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            // close() won the race after the state check.
            scheduledTask = null
        }
    }

    private fun doFlush() {
        val output = synchronized(lock) {
            scheduledTask = null
            if (closed || pendingChars == 0L) return

            var remaining = maxCharsPerFlush
            val builder = StringBuilder(minOf(pendingChars, maxCharsPerFlush.toLong()).toInt())
            while (chunks.isNotEmpty() && remaining > 0) {
                val chunk = chunks.first()
                val available = chunk.text.length - chunk.offset
                val take = minOf(available, remaining)
                builder.append(chunk.text, chunk.offset, chunk.offset + take)
                chunk.offset += take
                pendingChars -= take.toLong()
                remaining -= take
                if (chunk.offset == chunk.text.length) chunks.removeFirst()
            }
            builder.toString()
        }

        if (output.isNotEmpty()) {
            try {
                onFlush(output)
            } catch (error: Exception) {
                // Keep later terminal output flowing even if one consumer callback fails.
                runCatching { onError(error) }
            }
        }

        synchronized(lock) {
            val delay = if (pendingChars > maxCharsPerFlush.toLong()) 0L else flushIntervalMs
            scheduleLocked(delay)
        }
    }

    /** Drops queued output and permanently releases the worker thread. */
    fun clear() = close()

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            scheduledTask?.cancel(false)
            scheduledTask = null
            chunks.clear()
            pendingChars = 0L
        }
        worker.shutdownNow()
    }
}
