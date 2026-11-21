/* Copyright (C) 2026 nueex. SPDX-License-Identifier: GPL-3.0-or-later */
package com.nueex.psmtp

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

private const val MAX_MANIFEST = 32 * 1024
private const val MAX_APK_BYTES = 512L * 1024 * 1024

internal data class AndroidRelease(
    val code: Long,
    val version: String,
    val title: String,
    val changelog: String,
    val releaseUrl: String,
    val apkUrl: String,
    val size: Long,
    val sha256: String
)

internal fun checkAndroidRelease(context: Context): AndroidRelease? {
    if (FIREBASE_UPDATE_URL.isBlank()) throw TransferError("Firebase updates aren’t configured for this build.")
    val manifest = openDownloadSource(Transfer(url = FIREBASE_UPDATE_URL, name = "", keep = false, install = false))
        .use { response ->
            if (response.code != 200) throw TransferError("Couldn’t read the Firebase release (HTTP ${response.code}).")
            response.connection.inputStream.use { stream ->
                val data = ByteArray(MAX_MANIFEST + 1)
                var size = 0
                while (size < data.size) {
                    val count = stream.read(data, size, data.size - size)
                    if (count < 0) break
                    size += count
                }
                if (size > MAX_MANIFEST) throw TransferError("The Firebase release is too large.")
                JSONObject(String(data, 0, size, Charsets.UTF_8))
            }
        }
    return newerAndroidRelease(manifest, context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode)
}

internal fun newerAndroidRelease(manifest: JSONObject, installed: Long): AndroidRelease? {
    fun invalid(): Nothing = throw TransferError("The Firebase release has invalid metadata.")
    fun number(field: String): Long {
        val value = manifest.opt(field)
        if (value is Int) return value.toLong()
        return value as? Long ?: invalid()
    }
    fun detail(field: String, limit: Int): String =
        (manifest.opt(field) as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= limit } ?: invalid()

    if (number("schema") != 1L) invalid()
    val enabled = manifest.opt("enabled") as? Boolean ?: invalid()
    if (!enabled) return null
    val code = number("versionCode")
    if (code !in 1..2_100_000_000) invalid()
    if (code <= installed) return null
    return AndroidRelease(
        code, detail("versionName", 40), detail("title", 160), detail("changelog", 20_000),
        detail("releaseUrl", 2048).takeIf(::httpsUrl) ?: invalid(),
        detail("apkUrl", 2048).takeIf(::httpsUrl) ?: invalid(),
        number("size").takeIf { it in 1..MAX_APK_BYTES } ?: invalid(),
        detail("sha256", 64).takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) } ?: invalid()
    )
}

internal fun downloadAndroidRelease(context: Context, release: AndroidRelease): Uri {
    val file = File.createTempFile("jetbird-update-", ".apk", context.cacheDir)
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        openDownloadSource(Transfer(url = release.apkUrl, name = "", keep = false, install = false)).use { response ->
            if (response.code != 200) throw TransferError("Couldn’t download the APK (HTTP ${response.code}).")
            val connection = response.connection
            if (connection.contentEncoding?.equals("identity", true) == false ||
                connection.contentLengthLong.let { it >= 0 && it != release.size })
                throw TransferError("The APK size or encoding differs from the Firebase release.")
            var received = 0L
            connection.inputStream.use { source ->
                file.outputStream().use { output ->
                    val data = ByteArray(128 * 1024)
                    while (true) {
                        val count = source.read(data)
                        if (count < 0) break
                        received += count
                        if (received > release.size) throw TransferError("The APK exceeds the expected size.")
                        digest.update(data, 0, count)
                        output.write(data, 0, count)
                    }
                }
            }
            if (received != release.size || !digest.digest().joinToString("") { "%02x".format(it) }.equals(release.sha256, true))
                throw TransferError("The APK failed its size or SHA-256 check.")
        }
        verifyUpdateApk(context, file, release)
        val copy = PhoneCopy(context.contentResolver, "JetBird-${release.code}.apk", APK_MIME, subfolder = "updates")
        try {
            file.inputStream().use { it.copyTo(copy.output) }
            copy.finishWriting()
            return Uri.parse(copy.publish())
        } finally {
            copy.close(preserve = false)
        }
    } finally {
        file.delete()
    }
}

private fun verifyUpdateApk(context: Context, file: File, release: AndroidRelease) {
    val manager = context.packageManager
    val flags = PackageManager.GET_SIGNING_CERTIFICATES
    val installed = manager.getPackageInfo(context.packageName, flags)
    val update = manager.getPackageArchiveInfo(file.path, flags)
    if (update == null || update.packageName != installed.packageName ||
        update.longVersionCode != release.code || update.versionName != release.version ||
        update.longVersionCode <= installed.longVersionCode)
        throw TransferError("The downloaded APK version or package doesn’t match this release.")
    val signers = installed.signingInfo?.apkContentsSigners?.toSet().orEmpty()
    if (signers.isEmpty() || update.signingInfo?.apkContentsSigners?.toSet() != signers)
        throw TransferError("The update APK must use the same signing key as your installed JetBird.")
}
