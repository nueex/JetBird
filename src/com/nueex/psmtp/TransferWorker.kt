/* Copyright (C) 2026 nueex. SPDX-License-Identifier: GPL-3.0-or-later */
package com.nueex.psmtp

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.DataInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

internal const val MAX_TRANSFER = 1L shl 40
private const val BUFFER = 128 * 1024
private const val PKG_HEADER = 0x440

internal class TransferWorker(
    private val context: Context,
    private val usb: UsbManager,
    private val accessory: UsbAccessory?,
    private val job: Transfer,
    private val progress: (String, Long, Long, String) -> Unit,
    private val multipartProgress: (MultipartProgress) -> Unit,
    private val remember: (String, String) -> Unit,
    private val finished: (Result) -> Unit
) : Runnable {
    private val resolver = context.contentResolver
    data class Result(
        val complete: Boolean, val name: String, val done: Long, val total: Long,
        val message: String, val phone: String = "", val paused: Boolean = false,
        val checkpoint: Boolean = false, val stopped: Boolean = false
    )

    @Volatile private var cancelled = false
    @Volatile private var timedOut = false
    @Volatile private var pauseRequested = false
    @Volatile private var stopAt = 0L
    @Volatile private var http: HttpsURLConnection? = null
    @Volatile private var multipart: MultipartInput? = null
    @Volatile private var destination: Destination? = null
    @Volatile private var lastProgress = 0L
    private var name = job.name
    private var done = job.checkpointOffset
    private var total = if (job.checkpoint) job.total else -1L
    private var preservePhone = job.checkpoint
    private var phone: PhoneCopy? = null
    private var localPending = ""
    private var receiverContacted = false
    private var stopConfirmed = false
    private var pauseAcknowledged = false
    private var checkpointIdentity = job.resumeIdentity.takeIf { job.checkpoint }.orEmpty()
    private var checkpointName = job.name
    private var checkpointSize = job.total

    fun requestPause() {
        pauseRequested = true
        if (destination != null || job.phoneOnly) disconnectSource()
    }

    fun cancel() {
        cancelled = true
        Thread({ destination?.abort() }, "Interrupt PS4 connection").start()
        disconnectSource()
    }

    fun requestStop() {
        if (stopAt != 0L) return
        stopAt = SystemClock.elapsedRealtime()
        disconnectSource()
    }

    private fun disconnectSource() {
        val parts = multipart
        val source = http
        if (parts == null && source == null) return
        Thread({
            runCatching { parts?.close() }
            runCatching { source?.disconnect() }
        }, "Interrupt download source").start()
    }

    private fun checkRunning(allowStop: Boolean = false) {
        if (cancelled) throw TransferError(if (timedOut) "The transfer stopped responding." else "Transfer stopped.")
        if (!allowStop && stopAt != 0L) throw TransferError("Transfer stopped.")
    }

    private var remoteComplete = false
    private var failurePhase = "downloading the file"
    private val transport = if (job.phoneOnly) "phone" else if (job.wireless) "lan" else "usb"

    private fun trace(event: String) {
        if (DIAGNOSTIC_LOGGING) android.util.Log.i("PSMTP-Transfer", event)
    }

    override fun run() {
        trace("stage=transfer-start transport=$transport direct=${job.direct} requested-parts=${job.parts} resume-bytes=$done")
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        watchdog.scheduleWithFixedDelay({
            val now = SystemClock.elapsedRealtime()
            val stopping = stopAt
            if (!cancelled && stopping != 0L && now - stopping >= 30_000) cancel()
            else if (!cancelled && stopping == 0L && lastProgress != 0L &&
                now - lastProgress >= if (job.direct) 60_000 else 45_000) {
                timedOut = true
                cancel()
            }
            if (!cancelled && stopping == 0L && !pauseRequested) multipart?.progress()?.let(multipartProgress)
        }, 1, 1, TimeUnit.SECONDS)
        var result = try {
            if (job.direct) handoff() else download()
        } catch (error: Exception) {
            val message = when {
                remoteComplete -> "Finished on PS4. The phone copy could not be saved."
                timedOut -> "The transfer stopped responding. Try again."
                cancelled || stopAt != 0L -> "Transfer stopped."
                error is TransferError -> error.message.orEmpty()
                job.phoneOnly && pauseRequested -> "Paused. Resume to restart."
                error is java.net.SocketTimeoutException -> "The connection timed out while $failurePhase."
                error is javax.net.ssl.SSLException -> "The HTTPS connection failed while $failurePhase."
                error is java.net.UnknownHostException -> "The download server’s address could not be found."
                error is java.net.ConnectException -> "Could not establish a connection while $failurePhase."
                job.phoneOnly -> "Download interrupted. Check the link and available phone storage."
                job.direct && receiverContacted -> "The PS4 connection closed before it confirmed the download. Reconnect to the console and retry."
                else -> "Transfer interrupted. Check the link and PS4 connection."
            }
            trace("stage=transfer-error transport=$transport phase=$failurePhase bytes=$done total=$total error=${error.javaClass.simpleName} stopped=$cancelled")
            Result(remoteComplete, name, if (job.checkpoint && !remoteComplete) job.checkpointOffset else done,
                if (job.checkpoint && !remoteComplete) job.total else total, message,
                checkpoint = job.checkpoint && !remoteComplete, paused = job.phoneOnly && pauseRequested && !cancelled)
        } finally {
            destination = null
            runCatching { http?.disconnect() }
            http = null
        }
        try {
            if (stopAt != 0L && !result.complete) {
                val consoleClean = stopConfirmed || when {
                    checkpointIdentity.isNotBlank() && !cancelled && (!receiverContacted || pauseAcknowledged) -> discardCheckpoint()
                    else -> !receiverContacted && checkpointIdentity.isBlank()
                }
                val phoneClean = runCatching {
                    val pending = if (job.phoneOnly) localPending else job.pendingPhone
                    if (phone == null && pending.isNotBlank()) removePendingPhoneCopy(resolver, Uri.parse(pending))
                    phone?.close(preserve = false)
                }.isSuccess
                val message = when {
                    !consoleClean && !phoneClean -> "Couldn’t confirm cleanup on your phone or the PS4. Check both devices for temporary files."
                    !consoleClean -> "The phone transfer stopped, but the PS4 did not confirm cleanup. Check PS MTP Explorer on the console."
                    !phoneClean -> "The PS4 transfer stopped, but Android couldn’t remove the temporary phone copy."
                    else -> "Transfer stopped."
                }
                result = Result(false, name, done, total, message, stopped = consoleClean && phoneClean)
            } else runCatching {
                if (phone == null && localPending.isNotEmpty()) removePendingPhoneCopy(resolver, Uri.parse(localPending))
                phone?.close(preservePhone && !remoteComplete)
            }
        } finally {
            watchdog.shutdownNow()
            destination = null
        }
        trace("stage=transfer-end transport=$transport complete=${result.complete} paused=${result.paused} bytes=${result.done} total=${result.total}")
        finished(result)
    }

    private fun handoff(): Result {
        failurePhase = "checking the download link"
        context.requireInternetRoute()
        val file = context.internetRequest { probeMetadata(job) }
        trace("stage=direct-metadata transport=$transport bytes=${file.size}")
        name = if (job.explicitName && validFilename(job.name)) job.name else file.name
        val headers = sourceHeaders(job, java.net.URL(file.resolvedUrl))
        val fields = listOf(file.resolvedUrl, headers["User-Agent"].orEmpty(), headers["Referer"].orEmpty(), headers["Cookie"].orEmpty())
        if (fields.any { value -> value.any { it.code < 32 || it.code == 127 } })
            throw TransferError("The browser supplied an invalid request header.")
        val request = (fields.joinToString("\u0000") + "\u0000").toByteArray()
        if (request.size > 16384) throw TransferError("The browser link and headers exceed the PS4 receiver limit.")
        checkRunning()
        if (pauseRequested) return Result(false, name, 0, file.size, "Paused", paused = true)
        lastProgress = SystemClock.elapsedRealtime()
        failurePhase = "waiting for the PS4 to open the link"
        val target = makeDestination()
        destination = target
        progress(name, 0, file.size, "Sending link to PS4…")
        if (job.install && !name.endsWith(".pkg", true))
            name = if (name.toByteArray().size <= 196) "$name.pkg" else "download.pkg"
        receiverContacted = true
        trace("stage=direct-offer transport=$transport install=${job.install} request-bytes=${request.size}")
        target.use { it.handoff(name, file.size, request) }
        trace("stage=direct-accepted transport=$transport")
        remoteComplete = true
        return Result(true, name, 0, file.size, "The PS4 accepted the download. You can close JetBird.")
    }

    private fun download(): Result {
        if (job.phoneOnly && job.pendingPhone.isNotEmpty()) {
            val uri = Uri.parse(job.pendingPhone)
            val existing = phoneCopyStatus(resolver, uri)
            if (existing != null) {
                if (!existing.pending) return Result(true, existing.name, existing.size, existing.size,
                    "Saved on phone.", uri.toString())
                localPending = job.pendingPhone
            }
        }
        val source = openSource()
        val mime = source.contentType?.substringBefore(';')?.trim().orEmpty()
        if (!job.install && !isFileResponse(source)) {
            throw TransferError("The URL opened a web page. Use the direct file link.")
        }
        if (source.contentEncoding?.equals("identity", true) == false) {
            throw TransferError("The server changed the file encoding.")
        }
        total = source.contentLengthLong
        if (total > MAX_TRANSFER) throw TransferError("File exceeds the 1 TiB transfer limit.")
        name = if (job.checkpoint) job.name else resolveFilename(source)
        return PushbackInputStream(source.inputStream, PKG_HEADER).use { input ->
            if (job.install) {
                total = packageSize(input, total)
                if (!name.endsWith(".pkg", true)) name = if (name.toByteArray().size <= 196) "$name.pkg" else "download.pkg"
            }
            checkRunning()
            if (job.phoneOnly) {
                val local = PhoneCopy(resolver, name, downloadMime(name, mime), job.phoneFolder, localPending)
                phone = local
                remember("", local.pendingUri)
                local.seek(0)
                done = 0
                copySource(input, source, 0, null, null)
                checkRunning()
                if (pauseRequested) return@use Result(false, name, 0, total, "Paused. Resume to restart.", paused = true)
                local.finishWriting()
                return@use Result(true, name, done, total, "Saved on phone.", local.publish())
            }
            val target = makeDestination()
            destination = target
            lastProgress = SystemClock.elapsedRealtime()
            val resolvedMime = downloadMime(name, mime)
            target.use transfer@{
                var receiving = false
                try {
                    val etag = strongEtag(source)
                    val identity = if (etag == null || total < 0) "" else representation(source, etag)
                    if (job.checkpoint && identity != job.resumeIdentity) {
                        throw TransferError("The source changed or no longer supports resuming. The saved checkpoint was kept.")
                    }
                    if (job.keep) phone = PhoneCopy(resolver, name, resolvedMime, job.phoneFolder, job.pendingPhone.takeIf { job.checkpoint }.orEmpty())
                    remember(identity, phone?.pendingUri.orEmpty())
                    val offered = if (identity.isBlank()) null else ResumeOffer(
                        resumeKey(identity),
                        if (job.keep) checkNotNull(phone).size().coerceAtMost(total) else total
                    )
                    checkRunning()
                    receiverContacted = true
                    val offset = target.begin(name, total, offered)
                    receiving = true
                    if (offset < 0 || offset > (offered?.maximum ?: 0L) || (job.checkpoint && offset != job.checkpointOffset)) {
                        throw TransferError("The saved checkpoint is unavailable. Reconnect to the same PS4 and retry.")
                    }
                    done = offset
                    phone?.seek(offset)
                    val digest = MessageDigest.getInstance("SHA-256")
                    if (offset != total || total < 0) {
                        copySource(input, source, offset, target, digest)
                    }
                    checkRunning()
                    if (pauseRequested) {
                        if (offered != null) {
                            phone?.finishWriting()
                            checkRunning()
                            receiving = false
                            checkpointIdentity = identity
                            checkpointName = name
                            checkpointSize = total
                            target.pause(digest.digest())
                            pauseAcknowledged = true
                            preservePhone = true
                            return@transfer Result(false, name, done, total, "Paused at ${bytes(done)}.",
                                paused = true, checkpoint = true)
                        }
                        return@transfer Result(false, name, 0, total, "Paused. This server does not support a verified resume.",
                            paused = true)
                    }
                    phone?.finishWriting()
                    checkRunning()
                    receiving = false
                    remoteComplete = target.finish(digest.digest(), { stopAt != 0L }) { amount, size ->
                        checkRunning(allowStop = true)
                        lastProgress = SystemClock.elapsedRealtime()
                        done = amount
                        progress(name, amount, size, "Installing on PS4…")
                    }
                    if (!remoteComplete) {
                        stopConfirmed = true
                        return@transfer Result(false, name, done, total, "Transfer stopped.")
                    }
                    if (total >= 0) done = total
                    val saved = phone?.publish().orEmpty()
                    val message = when {
                        job.install -> "Installed on PS4."
                        else -> "Saved on PS4."
                    }
                    Result(true, name, done, total, if (saved.isNotBlank()) "$message Copy saved in Android Downloads." else message, saved)
                } finally {
                    if (receiving && stopAt != 0L && !cancelled) {
                        stopConfirmed = runCatching { target.stop() }.isSuccess
                    }
                }
            }
        }
    }

    private fun copySource(input: InputStream, source: HttpsURLConnection, offset: Long,
                           target: Destination?, digest: MessageDigest?) {
        if (pauseRequested) return
        val etag = strongEtag(source)
        if (job.parts > 1 && etag != null && total - offset >= MIN_MULTIPART_BYTES) {
            val ranged = MultipartInput(job, source.url.toExternalForm().substringBefore('#'), total, etag, offset) {
                lastProgress = SystemClock.elapsedRealtime()
            }
            multipart = ranged
            try {
                ranged.use {
                    if (context.internetRequest { ranged.start() }) {
                        trace("stage=source-start transport=$transport mode=multipart parts=${job.parts} remaining=${total - offset}")
                        source.disconnect()
                        copy(ranged, target, digest)
                        return
                    }
                }
            } catch (error: java.io.IOException) {
                if (pauseRequested) return
                throw error
            } finally {
                multipart = null
            }
        }
        if (pauseRequested) return
        trace("stage=source-start transport=$transport mode=single remaining=${if (total < 0) total else total - offset}")
        if (offset == 0L) {
            copy(input, target, digest)
        } else {
            val validator = checkNotNull(etag)
            val identity = representation(source, validator)
            source.disconnect()
            val remaining = openSource(offset, validator)
            validateRange(remaining, offset, validator, identity)
            remaining.inputStream.use { copy(it, target, digest) }
        }
    }

    private fun resumeKey(identity: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("${job.id}:$identity".toByteArray())

    private fun discardCheckpoint(): Boolean {
        // A checkpoint reply precedes the console closing and reopening its receiver.
        SystemClock.sleep(750)
        return runCatching {
            val target = makeDestination()
            destination = target
            target.use {
                checkRunning(allowStop = true)
                it.discard(checkpointName, checkpointSize, resumeKey(checkpointIdentity))
            }
        }.isSuccess
    }

    private fun copy(source: InputStream, target: Destination?, digest: MessageDigest?) {
        var sentUpdate = 0L
        var lastPartUpdate = 0L
        var sourceMillis = 0L
        var channelMillis = 0L
        val buffer = ByteArray(BUFFER)
        while (!pauseRequested) {
            checkRunning()
            val readStarted = SystemClock.elapsedRealtime()
            val count = try { context.internetRequest {
                if (job.wireless) readWirelessChunk(source, buffer) else source.read(buffer)
            } }
                catch (error: java.io.IOException) { if (pauseRequested) break else throw error }
            if (job.wireless) sourceMillis += SystemClock.elapsedRealtime() - readStarted
            if (count < 0) break
            if (count == 0) continue
            if (pauseRequested) break
            if (count > MAX_TRANSFER - done || (total >= 0 && count > total - done)) {
                throw TransferError("The download exceeded the expected size.")
            }
            checkRunning()
            phone?.output?.write(buffer, 0, count)
            digest?.update(buffer, 0, count)
            val sendStarted = SystemClock.elapsedRealtime()
            target?.write(buffer, count)
            if (job.wireless) channelMillis += SystemClock.elapsedRealtime() - sendStarted
            done += count
            lastProgress = SystemClock.elapsedRealtime()
            if (source is MultipartInput && lastProgress - lastPartUpdate >= 750) {
                source.progress()?.let {
                    multipartProgress(it)
                    target?.showParts(it)
                }
                lastPartUpdate = lastProgress
            }
            if (lastProgress - sentUpdate >= 250) {
                progress(name, done, total, "")
                sentUpdate = lastProgress
            }
        }
        if (source is MultipartInput && !pauseRequested) source.progress()?.let {
            multipartProgress(it)
            target?.showParts(it)
        }
        if (!pauseRequested && total >= 0 && done != total) throw TransferError("The server ended the download early.")
        if (job.wireless) trace("transport=lan bytes=$done source-ms=$sourceMillis channel-ms=$channelMillis")
    }

    private fun readWirelessChunk(source: InputStream, buffer: ByteArray): Int {
        var count = source.read(buffer)
        if (count <= 0) return count
        val deadline = SystemClock.elapsedRealtime() + 150
        while (count < buffer.size && SystemClock.elapsedRealtime() < deadline) {
            checkRunning()
            if (pauseRequested) break
            val next = source.read(buffer, count, buffer.size - count)
            if (next <= 0) break
            count += next
        }
        return count
    }

    private fun makeDestination(): Destination {
        if (!job.wireless) {
            return UsbDestination(usb, accessory ?: throw TransferError("The PS4 USB connection is unavailable."), job.install)
        }
        val pair = WirelessPair.active?.takeIf { it.address.hostAddress == job.console }
            ?: throw TransferError("Pair with this PS4 in Wireless Transfer settings, then retry.")
        return WirelessDestination(pair, job.install)
    }

    private fun openSource(offset: Long = 0, validator: String? = null): HttpsURLConnection {
        context.requireInternetRoute()
        val range = if (offset > 0) "bytes=$offset-" else null
        val response = context.internetRequest {
            openDownloadSource(job, range = range, validator = validator) {
                http = it
                checkRunning()
            }
        }
        if (response.code == if (offset > 0) 206 else 200) return response.connection
        response.close()
        if (offset > 0) throw TransferError("The server rejected the saved byte range (HTTP ${response.code}). The checkpoint was kept.")
        throw TransferError("The download server returned HTTP ${response.code}.")
    }

    private fun representation(source: HttpsURLConnection, etag: String): String {
        val fields = listOf(source.url.toExternalForm().substringBefore('#'), etag, total.toString())
        val hash = MessageDigest.getInstance("SHA-256")
        for (field in fields) {
            val value = field.toByteArray()
            hash.update(ByteBuffer.allocate(4).putInt(value.size).array())
            hash.update(value)
        }
        return android.util.Base64.encodeToString(hash.digest(), android.util.Base64.NO_WRAP)
    }

    private fun validateRange(connection: HttpsURLConnection, offset: Long, etag: String, identity: String) {
        val range = Regex("""bytes (\d+)-(\d+)/(\d+)""").matchEntire(connection.getHeaderField("Content-Range").orEmpty())
        val actualEncoding = connection.contentEncoding
        if (range == null || range.groupValues[1].toLongOrNull() != offset ||
            range.groupValues[2].toLongOrNull() != total - 1 || range.groupValues[3].toLongOrNull() != total ||
            connection.getHeaderField("ETag") != etag || representation(connection, etag) != identity ||
            (connection.contentLengthLong >= 0 && connection.contentLengthLong != total - offset) ||
            (actualEncoding != null && !actualEncoding.equals("identity", true))) {
            throw TransferError("The server returned different file data. The checkpoint was kept.")
        }
    }

    private fun resolveFilename(source: HttpsURLConnection): String {
        if (job.explicitName && validFilename(job.name)) return job.name
        return responseFilename(source.url.toString(), source.getHeaderField("Content-Disposition"))
            .ifBlank { job.name.takeIf(::validFilename) ?: "download" }
    }

    private fun packageSize(input: PushbackInputStream, responseSize: Long): Long {
        val prefix = ByteArray(PKG_HEADER)
        try { DataInputStream(input).readFully(prefix) }
        catch (_: java.io.EOFException) { throw TransferError("The link did not return a complete PS4 PKG header.") }
        val header = ByteBuffer.wrap(prefix)
        if (header.int != 0x7f434e54) throw TransferError("The link did not return a PS4 PKG.")
        val size = header.getLong(0x430)
        if (size !in 0x2000L..MAX_TRANSFER || (responseSize >= 0 && responseSize != size)) {
            throw TransferError("The server did not return the complete PS4 PKG.")
        }
        input.unread(prefix)
        return size
    }
}

internal class UsbDestination(private val manager: UsbManager, private val accessory: UsbAccessory,
                             private val install: Boolean) : Destination {
    @Volatile private var input: InputStream? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var aborted = false
    private var total = -1L
    private val packet = ByteBuffer.allocate(4)
    private val response = ByteArray(16 * 1024)
    private var nextResponse = 0
    private var responseEnd = 0

    @Suppress("DEPRECATION")
    private fun openStreams() {
        if (aborted) throw TransferError("USB transfer stopped.")
        if (Build.VERSION.SDK_INT >= 36) {
            input = manager.openAccessoryInputStream(accessory)
            output = manager.openAccessoryOutputStream(accessory)
        } else {
            val fd = manager.openAccessory(accessory) ?: throw TransferError("Could not open the PS4 USB connection.")
            output = FileOutputStream(fd.fileDescriptor)
            input = ParcelFileDescriptor.AutoCloseInputStream(fd)
        }
        if (aborted) throw TransferError("USB transfer stopped.")
    }

    override fun handoff(name: String, size: Long, request: ByteArray) {
        synchronized(usbCapacityLock) { openStreams() }
        val filename = name.toByteArray()
        val stream = checkNotNull(output)
        stream.write(transferHeader(if (install) 0x50534c32 else 0x50534c31, filename, size))
        requireHandoffReady(readStatus())
        stream.write(filename)
        stream.write(ByteBuffer.allocate(4).putInt(request.size).array())
        stream.write(request)
        requireSuccess(finalReply { readStatus() })
    }

    override fun begin(name: String, total: Long, resume: ResumeOffer?): Long {
        this.total = total
        synchronized(usbCapacityLock) { openStreams() }
        val filename = name.toByteArray()
        val magic = (if (install) 0x50534931 else 0x50534431) + if (resume != null) 1 else 0
        val stream = checkNotNull(output)
        stream.write(transferHeader(magic, filename, total))
        stream.write(filename)
        if (resume != null) {
            stream.write(resume.key)
            stream.write(ByteBuffer.allocate(8).putLong(resume.maximum).array())
        }
        requireSuccess(readStatus())
        return if (resume != null) readFrame(8).long else 0
    }

    fun storage(): ConsoleStorage? = synchronized(usbCapacityLock) {
        if (TransferService.alive) return@synchronized null
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        try {
            openStreams()
            watchdog.schedule({ abort() }, 3, TimeUnit.SECONDS)
            checkNotNull(output).write(storageRequest)
            decodeStorage(readFrame(24))
        } finally {
            watchdog.shutdownNow()
            close()
        }
    }

    override fun write(bytes: ByteArray, count: Int) {
        val stream = checkNotNull(output)
        stream.write(packet.putInt(0, count).array())
        stream.write(bytes, 0, count)
        requireSuccess(readStatus())
    }

    override fun showParts(progress: MultipartProgress) {
        if (accessory.version != "2") return
        val frame = progress.frame()
        val stream = checkNotNull(output)
        stream.write(frame, 0, 4)
        stream.write(frame, 4, frame.size - 4)
        requireSuccess(readStatus())
    }

    override fun finish(digest: ByteArray, stopping: () -> Boolean, progress: (Long, Long) -> Unit): Boolean {
        if (stopping()) {
            stop()
            return false
        }
        val stream = checkNotNull(output)
        stream.write(packet.putInt(0, 0).array())
        stream.write(digest)
        if (install) progress(total, total)
        var status = finalReply { readStatus() }
        var stopSent = false
        while (install && status == INSTALL_WAIT) {
            if (stopping() && !stopSent) {
                stream.write(packet.putInt(0, STOP_FRAME).array())
                stopSent = true
            }
            progress(total, total)
            status = finalReply { readStatus() }
        }
        if (stopSent && status == STOP_CONFIRMED) return false
        requireSuccess(status)
        return true
    }

    override fun pause(digest: ByteArray) {
        val stream = checkNotNull(output)
        stream.write(packet.putInt(0, -1).array())
        stream.write(digest)
        requireSuccess(readStatus())
    }

    override fun stop() {
        checkNotNull(output).write(packet.putInt(0, STOP_FRAME).array())
        if (readStatus() != STOP_CONFIRMED) throw TransferError("The PS4 did not confirm the stop.")
    }

    override fun discard(name: String, size: Long, key: ByteArray) {
        synchronized(usbCapacityLock) { openStreams() }
        val filename = name.toByteArray()
        val stream = checkNotNull(output)
        stream.write(discardHeader(install, filename, size))
        stream.write(filename)
        stream.write(key)
        if (readStatus() != STOP_CONFIRMED) throw TransferError("The PS4 did not confirm the stop.")
    }

    private fun readFrame(size: Int): ByteBuffer {
        val frame = ByteArray(size)
        var taken = 0
        while (taken < size) {
            if (nextResponse == responseEnd) {
                if (aborted) throw TransferError("USB transfer stopped.")
                responseEnd = checkNotNull(input).read(response)
                nextResponse = 0
                if (responseEnd <= 0) throw java.io.EOFException("The PS4 USB connection closed.")
            }
            val available = minOf(size - taken, responseEnd - nextResponse)
            response.copyInto(frame, taken, nextResponse, nextResponse + available)
            nextResponse += available
            taken += available
        }
        return ByteBuffer.wrap(frame)
    }

    private fun readStatus(): Int {
        val header = readFrame(8)
        if (header.int != 0x50535231) throw TransferError("Unsupported PS4 USB receiver.")
        return header.int
    }

    private fun requireSuccess(code: Int) {
        if (code == 0) return
        throw TransferError(ps4ReplyMessage(code))
    }

    override fun abort() {
        aborted = true
        close()
    }

    override fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
    }
}

private val usbCapacityLock = Any()

internal data class ConsoleStorage(val available: Long, val total: Long)
internal val storageRequest = ByteBuffer.allocate(16).putInt(0x50535131).array()
internal fun decodeStorage(frame: ByteBuffer): ConsoleStorage? {
    if (frame.int != 0x50535331 || frame.int != 0) return null
    val available = frame.long
    val total = frame.long
    return if (available >= 0 && total > 0 && available <= total) ConsoleStorage(available, total) else null
}
