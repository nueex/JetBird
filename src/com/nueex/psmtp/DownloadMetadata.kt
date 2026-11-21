/* Copyright (C) 2026 nueex. SPDX-License-Identifier: GPL-3.0-or-later */
package com.nueex.psmtp

import android.webkit.CookieManager
import java.net.URL
import java.net.URLDecoder
import javax.net.ssl.HttpsURLConnection

internal data class DownloadMetadata(val name: String, val size: Long, val resolvedUrl: String)

private val dispositionParameter = Regex(""";\s*([^\s=;]+)\s*=\s*("(?:\\.|[^"\\])*"|[^;]*)""")

internal fun responseFilename(link: String, disposition: String?): String {
    return dispositionFilename(disposition).ifBlank { guessName(link) }
}

internal fun dispositionFilename(disposition: String?): String {
    val parameters = dispositionParameter.findAll(disposition.orEmpty()).toList()
    for (parameter in listOf("filename*", "filename")) {
        val value = parameters.firstOrNull { it.groupValues[1].equals(parameter, true) }
            ?.groupValues?.get(2)?.trim() ?: continue
        val decoded = if (parameter.endsWith('*')) {
            val parts = value.split('\'', limit = 3)
            if (parts.size != 3 || (!parts[0].equals("UTF-8", true) && !parts[0].equals("ISO-8859-1", true))) continue
            runCatching { URLDecoder.decode(parts[2].replace("+", "%2B"), parts[0]) }.getOrNull() ?: continue
        } else value.removeSurrounding("\"").replace("\\\"", "\"")
        val cleaned = cleanFilename(decoded)
        if (cleaned.isNotEmpty()) return cleaned
    }
    return ""
}

internal class SourceResponse(val connection: HttpsURLConnection, val code: Int) : AutoCloseable {
    override fun close() = connection.disconnect()
}

internal fun openDownloadSource(job: Transfer, method: String = "GET", range: String? = null, validator: String? = null,
    timeout: Int = 12_000, readTimeout: Int = 15_000, startUrl: String = job.url,
    onConnection: (HttpsURLConnection) -> Unit = {}): SourceResponse {
    if (!httpsUrl(startUrl)) throw TransferError("Enter a direct HTTPS file URL.")
    val initial = URL(startUrl)
    var address = initial
    repeat(6) {
        if (!httpsUrl(address.toExternalForm())) throw TransferError("The server redirected outside HTTPS.")
        val connection = address.openConnection() as HttpsURLConnection
        var handoff = false
        try {
            onConnection(connection)
            connection.requestMethod = method
            connection.connectTimeout = timeout
            connection.readTimeout = readTimeout
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (range != null) connection.setRequestProperty("Range", range)
            if (validator != null) connection.setRequestProperty("If-Range", validator)
            for ((key, value) in sourceHeaders(job, address)) connection.setRequestProperty(key, value)
            val code = connection.responseCode
            val redirect = connection.getHeaderField("Location")
            if (code !in redirectCodes || redirect == null) {
                handoff = true
                return SourceResponse(connection, code)
            }
            address = URL(address, redirect)
        } finally {
            if (!handoff) connection.disconnect()
        }
    }
    throw TransferError("The download URL redirected too many times.")
}

internal fun sourceHeaders(job: Transfer, address: URL): Map<String, String> = buildMap {
    put("User-Agent", job.browserAgent.ifBlank { System.getProperty("http.agent") ?: "JetBird" })
    if (job.browserAgent.isNotBlank()) {
        if (sameOrigin(URL(job.url), address)) {
            CookieManager.getInstance().getCookie(address.toExternalForm())?.let { put("Cookie", it) }
            if (httpsUrl(job.browserPage) && sameOrigin(URL(job.browserPage), address))
                put("Referer", job.browserPage.substringBefore('#'))
        }
    }
}

private val redirectCodes = setOf(301, 302, 303, 307, 308)
private val headFallbackCodes = setOf(400, 401, 403, 405, 501)
private val probeRange = Regex("""bytes 0-0/(\d+)""", RegexOption.IGNORE_CASE)

internal fun probeMetadata(job: Transfer): DownloadMetadata {
    var head: DownloadMetadata? = null
    if (!job.direct) openDownloadSource(job, "HEAD", timeout = 5_000, readTimeout = 5_000).use { response ->
        if (response.code == 200) {
            head = metadata(response, job)
            if (head?.size != -1L && dispositionFilename(response.connection.getHeaderField("Content-Disposition")).isNotBlank()) {
                head?.let { return it }
            }
        } else if (response.code !in headFallbackCodes) {
            throw TransferError("The download link returned HTTP ${response.code}.")
        }
    }
    openDownloadSource(job, range = "bytes=0-0", timeout = 5_000, readTimeout = 5_000).use { response ->
        if (response.code == 416 && response.connection.getHeaderField("Content-Range").equals("bytes */0", true)) {
            head?.takeIf { it.size == 0L }?.let { return it }
            if (job.direct) openDownloadSource(job, timeout = 5_000, readTimeout = 5_000).use { empty ->
                if (empty.code == 200) metadata(empty, job)?.takeIf { it.size == 0L }?.let { return it }
            }
        }
        if (response.code !in listOf(200, 206)) throw TransferError("The download link returned HTTP ${response.code}.")
        return metadata(response, job) ?: throw TransferError("The server did not return a named file. Use a direct download link.")
    }
}

private fun metadata(response: SourceResponse, job: Transfer): DownloadMetadata? {
    val source = response.connection
    val disposition = source.getHeaderField("Content-Disposition")
    if (!isFileResponse(source) || source.contentEncoding?.equals("identity", true) == false) return null
    val size = if (response.code == 206) {
        probeRange.matchEntire(source.getHeaderField("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
            ?.takeIf { it > 0 } ?: return null
    } else source.contentLengthLong.coerceAtLeast(-1)
    if (size > MAX_TRANSFER) throw TransferError("File exceeds the 1 TiB transfer limit.")
    val name = dispositionFilename(disposition).ifBlank { job.browserFilename }
        .ifBlank { guessName(source.url.toExternalForm()) }.ifBlank { job.name }
    return name.takeIf(::validFilename)?.let { DownloadMetadata(it, if (size < 0 && job.browserFilename.isNotEmpty()) job.total else size, source.url.toURI().toASCIIString().substringBefore('#')) }
}

internal fun isFileResponse(source: HttpsURLConnection): Boolean {
    val type = source.contentType?.substringBefore(';')?.trim().orEmpty()
    val attachment = source.getHeaderField("Content-Disposition")?.substringBefore(';')?.trim().equals("attachment", true)
    return attachment || (!type.equals("text/html", true) && !type.equals("application/xhtml+xml", true))
}

private fun sameOrigin(first: URL, second: URL) = first.host.equals(second.host, true) &&
    first.protocol.equals(second.protocol, true) && first.effectivePort() == second.effectivePort()

private fun URL.effectivePort() = if (port >= 0) port else defaultPort
