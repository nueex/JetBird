/* Copyright (C) 2026 nueex. SPDX-License-Identifier: GPL-3.0-or-later */
package com.nueex.psmtp

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

internal enum class TransferStatus { SAVED, QUEUED, ACTIVE, PAUSING, PAUSED, FAILED, COMPLETE, STOPPING, STOPPED }

internal data class Transfer(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val name: String,
    val keep: Boolean,
    val install: Boolean,
    val console: String = "",
    val browserAgent: String = "",
    val browserPage: String = "",
    val status: TransferStatus = TransferStatus.QUEUED,
    val done: Long = 0,
    val total: Long = -1,
    val message: String = "",
    val phoneCopy: String = "",
    val explicitName: Boolean = false,
    val speed: Long = 0,
    val checkpoint: Boolean = false,
    val resumeIdentity: String = "",
    val pendingPhone: String = "",
    val browserFilename: String = "",
    val phoneOnly: Boolean = false,
    val direct: Boolean = false,
    val checkpointOffset: Long = 0,
    val phoneFolder: String = "",
    val parts: Int = 1,
    val partsProgress: MultipartProgress? = null
) {
    val needsUsb get() = !phoneOnly && !wireless
    val wireless get() = console.isNotEmpty()
    val percent get() = if (total > 0) (100.0 * done / total).roundToInt().coerceIn(0, 100) else 0

    fun json(includeSource: Boolean = true) = JSONObject().put("id", id).put("url", url.takeIf { includeSource }.orEmpty()).put("name", name)
        .put("browserFilename", browserFilename).put("phoneOnly", phoneOnly).put("direct", direct).put("keep", keep).put("install", install).put("console", console)
        .put("agent", browserAgent.takeIf { includeSource }.orEmpty())
        .put("page", browserPage.takeIf { includeSource }.orEmpty()).put("status", status.name)
        .put("done", done).put("total", total).put("message", message).put("phone", phoneCopy)
        .put("explicitName", explicitName).put("checkpoint", checkpoint)
        .put("resumeIdentity", resumeIdentity).put("pendingPhone", pendingPhone)
        .put("checkpointOffset", checkpointOffset).put("phoneFolder", phoneFolder).put("parts", parts)

    companion object {
        fun read(item: JSONObject) = Transfer(
            item.getString("id"), item.getString("url"), item.getString("name"),
            item.optBoolean("keep"), item.optBoolean("install"), item.optString("console"),
            item.optString("agent"), item.optString("page"), TransferStatus.valueOf(item.getString("status")),
            item.optLong("done"), item.optLong("total", -1), item.optString("message"), item.optString("phone"),
            item.optBoolean("explicitName", !item.has("explicitName")), checkpoint = item.optBoolean("checkpoint"),
            resumeIdentity = item.optString("resumeIdentity"), pendingPhone = item.optString("pendingPhone"),
            browserFilename = item.optString("browserFilename"), phoneOnly = item.optBoolean("phoneOnly"), direct = item.optBoolean("direct"),
            checkpointOffset = item.optLong("checkpointOffset", item.optLong("done").takeIf { item.optBoolean("checkpoint") } ?: 0),
            phoneFolder = item.optString("phoneFolder"),
            parts = item.optInt("parts", 1).takeIf { it in DOWNLOAD_PART_CHOICES } ?: 1
        )
    }
}

internal val DOWNLOAD_PART_CHOICES = listOf(1, 2, 4, 8)

/** Accessed from the main looper by the activity and the service. */
internal class TransferStore(context: Context) {
    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    fun all(): List<Transfer> {
        val items = runCatching { JSONArray(prefs.getString("transfers", "[]")) }.getOrElse { JSONArray() }
        return (0 until items.length()).mapNotNull { index ->
            runCatching { items.getJSONObject(index) }.getOrNull()?.let { item ->
                val job = runCatching { Transfer.read(item) }.getOrNull() ?: return@let null
                val source = liveSources[job.id] ?: unseal(job.id, item.optString("source"))
                job.copy(url = source?.url ?: job.url, browserAgent = source?.agent ?: job.browserAgent,
                    browserPage = source?.page ?: job.browserPage, speed = rates[job.id] ?: 0,
                    partsProgress = partProgress[job.id])
            }
        }
    }

    fun save(items: List<Transfer>, durable: Boolean = false) {
        liveSources.keys.retainAll(items.mapTo(HashSet()) { it.id })
        rates.clear()
        partProgress.clear()
        for (job in items) {
            if (job.status != TransferStatus.COMPLETE && job.status != TransferStatus.STOPPING &&
                job.status != TransferStatus.STOPPED && job.url.isNotBlank()) {
                val old = liveSources[job.id]
                if (old?.url != job.url || old.agent != job.browserAgent || old.page != job.browserPage) {
                    liveSources[job.id] = seal(job)
                }
            } else liveSources.remove(job.id)
            if (job.status == TransferStatus.ACTIVE) {
                if (job.speed > 0) rates[job.id] = job.speed
                job.partsProgress?.let { partProgress[job.id] = it }
            }
        }
        val change = prefs.edit().putString("transfers", JSONArray(items.map {
            it.json(includeSource = false).put("source", liveSources[it.id]?.sealed.orEmpty())
        }).toString())
        if (durable) change.commit() else change.apply()
    }

    fun update(transfer: Transfer, durable: Boolean = false) = save(all().map { if (it.id == transfer.id) transfer else it }, durable)
    fun remove(id: String, durable: Boolean = false) = save(all().filterNot { it.id == id }, durable)

    fun recover() {
        val items = all()
        val interrupted = setOf(TransferStatus.ACTIVE, TransferStatus.PAUSING, TransferStatus.QUEUED)
        save(items.map {
            when {
                it.status == TransferStatus.STOPPING -> it.copy(status = TransferStatus.STOPPED, speed = 0,
                    message = "The app closed before it could confirm that cleanup finished.")
                it.status in interrupted -> it.copy(status = TransferStatus.PAUSED,
                    done = it.checkpointOffset, speed = 0, message = "Interrupted")
                else -> it
            }
        })
    }

    private fun seal(job: Transfer): Source {
        val encoded = runCatching {
            val payload = JSONArray(listOf(job.url, job.browserAgent, job.browserPage)).toString().toByteArray()
            LocalSecrets.seal(SOURCE_KEY, job.id, payload)
        }.getOrDefault("")
        return Source(job.url, job.browserAgent, job.browserPage, encoded)
    }

    private fun unseal(id: String, sealed: String): Source? {
        if (sealed.isBlank()) return null
        return runCatching {
            val fields = JSONArray(String(LocalSecrets.open(SOURCE_KEY, id, sealed), Charsets.UTF_8))
            Source(fields.getString(0), fields.getString(1), fields.getString(2), sealed).also { liveSources[id] = it }
        }.getOrNull()
    }

    private data class Source(val url: String, val agent: String, val page: String, val sealed: String)
    companion object {
        private const val SOURCE_KEY = "psmtp-download-source"
        private val liveSources = HashMap<String, Source>()
        private val rates = HashMap<String, Long>()
        private val partProgress = HashMap<String, MultipartProgress>()
    }
}

internal fun usbAccessory(manager: UsbManager): UsbAccessory? = manager.accessoryList?.firstOrNull {
    it.manufacturer == "nueex" && it.model == "PS MTP Explorer"
}

internal fun httpsUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", true) && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null
}.getOrDefault(false)

private val invalidFilename = Regex("[\\p{Cc}\\p{Cf}/\\\\:*?\"<>|\\uFFFD]")

internal fun validFilename(value: String) = value.isNotBlank() && !value.startsWith('.') &&
    !value.endsWith('.') && value.toByteArray().size <= 200 && !invalidFilename.containsMatchIn(value)

internal fun guessName(link: String): String {
    val segment = runCatching { URI(link).rawPath?.substringAfterLast('/') }.getOrNull().orEmpty()
    return cleanFilename(Uri.decode(segment))
}

internal fun cleanFilename(value: String): String {
    val leaf = value.substringAfterLast('/').substringAfterLast('\\')
    val normalized = java.text.Normalizer.normalize(leaf, java.text.Normalizer.Form.NFC)
        .replace(invalidFilename, "").trim().trim('.').trim()
    if (validFilename(normalized)) return normalized
    if (normalized.toByteArray().size <= 200) return ""
    val dot = normalized.lastIndexOf('.')
    val extension = if (dot > 0 && normalized.length - dot <= 16) normalized.substring(dot) else ""
    val prefix = normalized.removeSuffix(extension)
    val shortened = StringBuilder()
    var offset = 0
    var budget = 200 - extension.toByteArray().size
    while (offset < prefix.length) {
        val point = prefix.codePointAt(offset)
        val characters = String(Character.toChars(point))
        budget -= characters.toByteArray().size
        if (budget < 0) break
        shortened.append(characters)
        offset += characters.length
    }
    return (shortened.toString().trimEnd().trimEnd('.') + extension).takeIf(::validFilename).orEmpty()
}

internal fun localAddress(value: String): InetAddress? {
    val parts = value.split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { ch -> ch !in '0'..'9' } }) return null
    val bytes = parts.map { it.toInt() }
    if (bytes.any { it > 255 }) return null
    val address = InetAddress.getByAddress(bytes.map(Int::toByte).toByteArray())
    return address.takeIf { it.isSiteLocalAddress || it.isLinkLocalAddress }
}

internal fun bytes(value: Long, locale: Locale = Locale.getDefault(),
                   label: (String, String) -> String = { amount, unit -> "$amount $unit" }): String {
    if (value < 0) return "Unknown size"
    val divisor = when {
        value >= 1_000_000_000 -> 1_000_000_000.0 to "GB"
        value >= 1_000_000 -> 1_000_000.0 to "MB"
        value >= 1_000 -> 1_000.0 to "KB"
        else -> return label(String.format(locale, "%d", value), "B")
    }
    val size = value / divisor.first
    val format = if (size >= 20 || size % 1 == 0.0) "%.0f" else "%.1f"
    return label(String.format(locale, format, size), divisor.second)
}
