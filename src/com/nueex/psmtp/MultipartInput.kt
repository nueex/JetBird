/* Copyright (C) 2026 nueex. SPDX-License-Identifier: GPL-3.0-or-later */
package com.nueex.psmtp

import android.os.SystemClock
import java.io.EOFException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLongArray
import javax.net.ssl.HttpsURLConnection

internal const val MIN_MULTIPART_BYTES = 512L * 1024
private const val MAX_PART_BYTES = 2L * 1024 * 1024
private val byteRange = Regex("""bytes (\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)

internal data class MultipartProgress(val percentages: List<Int>, val bytesPerSecond: Long)

internal fun strongEtag(connection: HttpsURLConnection) = connection.getHeaderField("ETag").orEmpty()
    .takeIf { it.length in 2..1024 && it.first() == '"' && it.last() == '"' }

/** Reads bounded ranges ahead in parallel and delivers them in file order. */
internal class MultipartInput(
    private val job: Transfer,
    private val url: String,
    private val size: Long,
    private val etag: String,
    offset: Long,
    private val onReceive: () -> Unit
) : InputStream() {
    private val stopped = AtomicBoolean()
    private val connections = ConcurrentHashMap.newKeySet<HttpsURLConnection>()
    private val pool = Executors.newFixedThreadPool(job.parts)
    private val requests = ArrayDeque<Future<ByteArray>>()
    private val partBytes = minOf(MAX_PART_BYTES, (size - offset + job.parts - 1) / job.parts)
    private val ranges = (size - offset + partBytes - 1) / partBytes
    private val received = AtomicLongArray(job.parts)
    private val partTotals = LongArray(job.parts) { index ->
        val count = (ranges + job.parts - 1 - index) / job.parts
        val trim = if ((ranges - 1) % job.parts == index.toLong()) ranges * partBytes - (size - offset) else 0L
        count * partBytes - trim
    }
    private var nextPart = 0
    private var sampleAt = 0L
    private var sampleBytes = 0L
    private var speed = 0L
    private var cursor = offset
    private var block = ByteArray(0)
    private var used = 0

    fun start(): Boolean {
        // Servers can ignore Range even when they advertise byte ranges.
        withRange(cursor, cursor) { response ->
            if (response.code in setOf(200, 400, 403, 405, 416, 501)) return false
            verify(response, cursor, cursor)
        }
        synchronized(this) { sampleAt = SystemClock.elapsedRealtime() }
        repeat(job.parts) { enqueue() }
        return true
    }

    private fun enqueue() {
        if (cursor >= size) return
        checkOpen()
        val first = cursor
        val limit = minOf(size, first + partBytes)
        val part = nextPart
        try {
            requests.addLast(pool.submit<ByteArray> { fetch(first, limit - 1, part) })
        } catch (error: RejectedExecutionException) {
            checkOpen()
            throw error
        }
        cursor = limit
        nextPart = (part + 1) % job.parts
    }

    @Synchronized
    fun progress(): MultipartProgress? {
        if (sampleAt == 0L) return null
        val bytes = List(job.parts) { received.get(it) }
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - sampleAt
        if (elapsed >= 750) {
            val total = bytes.sum()
            speed = (total - sampleBytes) * 1000 / elapsed
            sampleBytes = total
            sampleAt = now
        }
        return MultipartProgress(bytes.mapIndexed { i, value -> (100 * value / partTotals[i]).toInt() }, speed)
    }

    override fun read(): Int {
        if (!advance()) return -1
        return block[used++].toInt() and 255
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!advance()) return -1
        val count = minOf(block.size - used, length)
        block.copyInto(bytes, offset, used, used + count)
        used += count
        return count
    }

    private fun advance(): Boolean {
        checkOpen()
        if (used < block.size) return true
        val next = requests.removeFirstOrNull() ?: return false
        block = try {
            next.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (_: CancellationException) {
            throw InterruptedIOException("Download interrupted.")
        }
        used = 0
        enqueue()
        return true
    }

    private fun fetch(first: Long, last: Long, part: Int): ByteArray {
        var reported = 0
        repeat(3) { attempt ->
            checkOpen()
            try {
                return withRange(first, last) { response ->
                    verify(response, first, last)
                    val bytes = ByteArray((last - first + 1).toInt())
                    response.connection.inputStream.use { input ->
                        var received = 0
                        while (received < bytes.size) {
                            checkOpen()
                            val count = input.read(bytes, received, bytes.size - received)
                            if (count < 0) throw EOFException()
                            received += count
                            if (received > reported) {
                                this.received.addAndGet(part, (received - reported).toLong())
                                reported = received
                            }
                            if (count > 0) onReceive()
                        }
                        if (input.read() != -1) throw TransferError("A download part exceeded its expected size.")
                    }
                    bytes
                }
            } catch (error: java.io.IOException) {
                if (attempt == 2 || stopped.get() ||
                    error !is SocketTimeoutException && error !is SocketException &&
                    error !is EOFException && error !is ProtocolException) throw error
                try {
                    Thread.sleep(200L shl attempt)
                } catch (_: InterruptedException) {
                    throw InterruptedIOException("Download interrupted.")
                }
            }
        }
        error("Unreachable")
    }

    private inline fun <T> withRange(first: Long, last: Long, consume: (SourceResponse) -> T): T {
        var active: HttpsURLConnection? = null
        try {
            return openDownloadSource(job, range = "bytes=$first-$last", validator = etag, startUrl = url) {
                active?.let(connections::remove)
                active = it
                connections.add(it)
                checkOpen()
            }.use(consume)
        } finally {
            active?.let(connections::remove)
        }
    }

    private fun verify(response: SourceResponse, first: Long, last: Long) {
        if (response.code != 206) throw TransferError("The server rejected a download part (HTTP ${response.code}).")
        val connection = response.connection
        val range = byteRange.matchEntire(connection.getHeaderField("Content-Range").orEmpty())
        val length = connection.contentLengthLong
        if (range == null || range.groupValues[1].toLongOrNull() != first ||
            range.groupValues[2].toLongOrNull() != last || range.groupValues[3].toLongOrNull() != size ||
            strongEtag(connection) != etag || connection.url.toExternalForm().substringBefore('#') != url ||
            (length >= 0 && length != last - first + 1) ||
            connection.contentEncoding?.equals("identity", true) == false) {
            throw TransferError("The server returned a different file or invalid byte range.")
        }
    }

    private fun checkOpen() {
        if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Download interrupted.")
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        // Disconnect the socket separately from its reader so a blocked range can stop.
        pool.shutdownNow().forEach { (it as Future<*>).cancel(false) }
        connections.forEach { it.disconnect() }
    }
}
