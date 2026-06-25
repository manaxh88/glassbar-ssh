package com.glassbar.ssh.ui.screen.ssh

import java.util.LinkedList

/**
 * Buffers SSH output strings and flushes them in batches every ~16ms
 * (60fps) to avoid overwhelming the terminal renderer.
 *
 * Inspired by flutter_server_box's TerminalOutputBuffer.
 */
class TerminalOutputBuffer(
    private val onFlush: (String) -> Unit,
) {
    private val chunks = LinkedList<String>()
    private var pendingChars = 0
    private var flushScheduled = false
    private val flushIntervalMs = 16L
    private val maxCharsPerFlush = 32768
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun add(data: String) {
        if (data.isEmpty()) return
        synchronized(chunks) {
            chunks.addLast(data)
            pendingChars += data.length
        }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        handler.postDelayed({ doFlush() }, flushIntervalMs)
    }

    private fun doFlush() {
        flushScheduled = false
        var output = ""
        synchronized(chunks) {
            if (pendingChars == 0) return
            var remaining = maxCharsPerFlush
            val sb = StringBuilder()
            while (chunks.isNotEmpty() && remaining > 0) {
                val chunk = chunks.removeFirst()
                if (chunk.length <= remaining) {
                    sb.append(chunk)
                    pendingChars -= chunk.length
                    remaining -= chunk.length
                } else {
                    sb.append(chunk.substring(0, remaining))
                    chunks.addFirst(chunk.substring(remaining))
                    pendingChars -= remaining
                    remaining = 0
                }
            }
            output = sb.toString()
        }
        if (output.isNotEmpty()) {
            onFlush(output)
        }
        // If more data remains, schedule next flush
        synchronized(chunks) {
            if (pendingChars > 0) {
                scheduleFlush()
            }
        }
    }
}
