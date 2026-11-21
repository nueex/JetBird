/* Copyright (C) 2026 nueex. SPDX-License-Identifier: GPL-3.0-or-later */
package com.nueex.psmtp

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer

internal class TransferError(message: String) : IOException(message)
/* Once the request/commit is on the wire the console may finish even if
 * its last reply is lost. Preserve explicit server errors at the call site. */
internal inline fun finalReply(read: () -> Int): Int = try {
    read()
} catch (error: TransferError) {
    throw error
} catch (_: IOException) {
    throw TransferError("The PS4 connection closed before confirmation. Check the download or installation on the console before retrying.")
}
internal data class ResumeOffer(val key: ByteArray, val maximum: Long)

internal const val STOP_FRAME = -2
internal const val INSTALL_WAIT = 6
internal const val STOP_CONFIRMED = 8
internal const val MULTIPART_FRAME = -3
internal const val MULTIPART_ALPN = "psmtp-parts/1"

internal fun MultipartProgress.frame(): ByteArray =
    ByteBuffer.allocate(21).putInt(MULTIPART_FRAME).put(percentages.size.toByte())
        .putLong(bytesPerSecond).apply {
            for (index in 0 until 8) put((percentages.getOrNull(index) ?: 0).toByte())
        }.array()

internal fun ps4ReplyMessage(code: Int): String {
    if (code in 0x10064..0x10257) {
        val http = code - 0x10000
        return when (http) {
            401, 403 -> "The download server denied access from the PS4 (HTTP $http). The link may require a new login or have expired."
            404, 410 -> "The download server could not find the file (HTTP $http)."
            else -> "The PS4 received HTTP $http when opening the download link."
        }
    }
    if (code < 0) {
        val reason = when (code.toUInt()) {
            0x80990077u, 0x80990065u, 0x80990061u, 0x80990078u, 0x8099006bu -> "The package failed the PS4 integrity check."
            0x8099003au, 0x80990074u -> "The PS4 installer could not read the package."
            0x80990039u, 0x80990021u -> "There is not enough PS4 storage to install this package."
            0x80990064u, 0x80990066u, 0x80990067u, 0x80990068u, 0x8099007au -> "The update package does not match the installed base game."
            0x80990015u, 0x80990086u -> "An installation task already exists for this content."
            0x80990088u -> "This package conflicts with installed content."
            else -> "The PS4 system installer rejected the package."
        }
        return "$reason System error 0x${code.toUInt().toString(16).padStart(8, '0')}."
    }
    return when (code) {
        1 -> "The PS4 rejected or cancelled the request."
        2 -> "There is not enough free space on the PS4."
        3 -> "A file with that name already exists on the PS4."
        4 -> "The PS4 could not verify the received file or its expected size."
        5 -> "The PS4 could not write the file to storage."
        7 -> "The PS4 installer rejected the transfer without providing an error code."
        9 -> "The PS4 could not establish an HTTPS connection to the download server."
        10 -> "The download server did not respond to the PS4 in time."
        11 -> "The PS4 received a web page, incomplete file, or unexpected response. Try a fresh direct download link."
        12 -> "The download link did not return a complete PS4 package."
        13 -> "The PS4 already has an HTTPS download or update check running."
        14 -> "The PS4 could not initialize its HTTPS download support."
        15 -> "This update package requires its matching base game to be installed first."
        16 -> "The PS4 could not start or finish the installation."
        17 -> NO_INTERNET_CONNECTION
        else -> "Unexpected PS4 transfer response (code $code)."
    }
}

internal fun requireHandoffReady(code: Int) {
    if (code == 0) return
    if (code == 7) throw TransferError("The PS4 receiver does not support Direct PS4 Download. Update PS MTP Explorer.")
    throw TransferError(ps4ReplyMessage(code))
}

internal fun transferHeader(magic: Int, name: ByteArray, size: Long): ByteArray =
    ByteBuffer.allocate(16).putInt(magic).putInt(name.size).putLong(size).array()

internal fun discardHeader(install: Boolean, name: ByteArray, size: Long): ByteArray =
    transferHeader(if (install) 0x50535832 else 0x50535831, name, size) // PSX2 / PSX1

internal interface Destination : Closeable {
    fun handoff(name: String, size: Long, request: ByteArray)
    fun begin(name: String, total: Long, resume: ResumeOffer? = null): Long
    fun write(bytes: ByteArray, count: Int)
    fun showParts(progress: MultipartProgress)
    fun pause(digest: ByteArray)
    fun finish(digest: ByteArray, stopping: () -> Boolean, progress: (Long, Long) -> Unit): Boolean
    fun stop()
    fun discard(name: String, size: Long, key: ByteArray)
    fun abort()
}
