/* Copyright (C) 2026 nueex. SPDX-License-Identifier: GPL-3.0-or-later */
package com.nueex.psmtp

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

internal const val DEFAULT_DOWNLOAD_PATH = "Download/JetBird"
internal const val APK_MIME = "application/vnd.android.package-archive"
private const val PART_PREFIX = ".jetbird-"

internal fun downloadMime(name: String, fallback: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(name))
        ?: fallback.ifBlank { "application/octet-stream" }

private fun extension(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

private fun category(name: String, mime: String): String {
    return when (extension(name)) {
        "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "cab", "iso" -> "Compressed"
        "pkg", "apk", "apks", "xapk", "aab", "obb", "ipa", "deb", "rpm", "msi" -> "Packages"
        "mp4", "mkv", "avi", "mov", "webm", "wmv", "m4v", "mpg", "mpeg", "3gp", "ts" -> "Videos"
        "mp3", "m4a", "wav", "flac", "aac", "ogg", "opus", "wma", "aiff" -> "Audio"
        "jpg", "jpeg", "png", "gif", "webp", "heic", "avif", "svg", "bmp", "tiff" -> "Images"
        "pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "md", "rtf",
        "odt", "ods", "odp", "epub", "mobi", "log" -> "Documents"
        else -> when {
            mime.startsWith("video/") -> "Videos"
            mime.startsWith("audio/") -> "Audio"
            mime.startsWith("image/") -> "Images"
            mime.startsWith("text/") -> "Documents"
            else -> "Other"
        }
    }
}

private fun Uri.isTreeDocument() = DocumentsContract.isTreeUri(this)

internal fun deletePhoneCopy(resolver: ContentResolver, uri: Uri): Boolean =
    if (uri.isTreeDocument()) DocumentsContract.deleteDocument(resolver, uri)
    else resolver.delete(uri, null, null) == 1

internal data class PhoneCopyStatus(val pending: Boolean, val name: String, val size: Long)

internal fun removePendingPhoneCopy(resolver: ContentResolver, uri: Uri) {
    if (phoneCopyStatus(resolver, uri)?.pending == true && !deletePhoneCopy(resolver, uri))
        throw TransferError("Could not remove the unfinished phone copy.")
}

internal fun phoneCopyStatus(resolver: ContentResolver, uri: Uri): PhoneCopyStatus? {
    val document = uri.isTreeDocument()
    val columns = if (document) arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_SIZE) else arrayOf(MediaStore.Downloads.DISPLAY_NAME,
        MediaStore.Downloads.SIZE, MediaStore.Downloads.IS_PENDING)
    val cursor = resolver.query(uri, columns, null, null, null)
        ?: throw TransferError("Android could not check the phone copy.")
    return cursor.use { row ->
        if (!row.moveToFirst()) return@use null
        val name = row.getString(0)
        val pending = if (document) name.startsWith(PART_PREFIX) else row.getInt(2) != 0
        PhoneCopyStatus(pending, name, row.getLong(1))
    }
}

internal class PhoneCopy(
    private val resolver: ContentResolver,
    private val name: String,
    mime: String,
    tree: String = "",
    pending: String = "",
    subfolder: String = category(name, mime)
) {
    private var uri: Uri
    val output: FileOutputStream
    val pendingUri get() = uri.toString()
    private var writing = true
    private var published = false

    init {
        uri = when {
            pending.isNotBlank() -> Uri.parse(pending)
            tree.isNotBlank() -> {
                val folder = Uri.parse(tree)
                val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
                DocumentsContract.createDocument(resolver, parent, mime, "$PART_PREFIX${UUID.randomUUID()}.part")
                    ?: throw TransferError("Could not create a file in the selected download folder.")
            }
            else -> {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "$DEFAULT_DOWNLOAD_PATH/$subfolder")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                    ?: throw TransferError("Could not create the file in Android Downloads.")
            }
        }
        try {
            val descriptor = resolver.openFileDescriptor(uri, "rw") ?: throw TransferError("Could not write the phone copy.")
            output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
        } catch (error: Exception) {
            if (pending.isBlank()) runCatching { deletePhoneCopy(resolver, uri) }
            throw error
        }
    }

    fun size(): Long = output.channel.size()

    fun seek(offset: Long) {
        output.channel.truncate(offset)
        output.channel.position(offset)
    }

    fun finishWriting() {
        output.fd.sync()
        output.close()
        writing = false
    }

    fun publish(): String {
        if (uri.isTreeDocument()) {
            uri = DocumentsContract.renameDocument(resolver, uri, name)
                ?: throw TransferError("Could not finish the file in the selected download folder.")
        } else {
            val ready = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            if (resolver.update(uri, ready, null, null) != 1) throw TransferError("Could not publish the phone copy.")
        }
        published = true
        return uri.toString()
    }

    fun close(preserve: Boolean) {
        if (writing) runCatching { output.close() }
        if (!published && !preserve && !deletePhoneCopy(resolver, uri))
            throw TransferError("Could not remove the unfinished phone copy.")
    }
}
