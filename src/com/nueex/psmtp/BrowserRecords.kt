/* Copyright (C) 2026 nueex. SPDX-License-Identifier: GPL-3.0-or-later */
package com.nueex.psmtp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.util.AtomicFile
import android.webkit.WebView
import java.io.File
import java.util.concurrent.Executors

internal class BrowserRecords private constructor(private val context: Context) :
    SQLiteOpenHelper(context, "browser-history.db", null, 2) {
    data class Visit(val id: Long, val url: String, val title: String)
    data class Bookmark(val url: String, val title: String)

    private val writer = Executors.newSingleThreadExecutor()
    private val session = AtomicFile(File(context.filesDir, "browser-session"))
    @Volatile private var clearingSession = false

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE visits (_id INTEGER PRIMARY KEY, url TEXT NOT NULL, title TEXT NOT NULL)")
        db.execSQL("CREATE INDEX visits_url ON visits(url)")
        createBookmarks(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createBookmarks(db)
    }

    private fun createBookmarks(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bookmarks (_id INTEGER PRIMARY KEY, url TEXT NOT NULL UNIQUE, title TEXT NOT NULL)")
    }

    fun loadBookmarks(callback: (Result<List<Bookmark>>) -> Unit) {
        writer.execute { callback(runCatching(::bookmarks)) }
    }

    fun changeBookmark(url: String, title: String?, callback: (Result<List<Bookmark>>) -> Unit) {
        writer.execute {
            callback(runCatching {
                val db = writableDatabase
                if (title == null) db.delete("bookmarks", "url = ?", arrayOf(url))
                else {
                    val values = ContentValues().apply { put("title", title) }
                    if (db.update("bookmarks", values, "url = ?", arrayOf(url)) == 0) {
                        values.put("url", url)
                        db.insertOrThrow("bookmarks", null, values)
                    }
                }
                bookmarks()
            })
        }
    }

    private fun bookmarks(): List<Bookmark> = readableDatabase
        .query("bookmarks", arrayOf("url", "title"), null, null, null, null, "_id DESC")
        .use { cursor -> buildList {
            while (cursor.moveToNext()) add(Bookmark(cursor.getString(0), cursor.getString(1)))
        } }

    fun clearBrowsingHistory(callback: (Result<Unit>) -> Unit) {
        clearingSession = true
        writer.execute {
            val result = runCatching {
                writableDatabase.delete("visits", null, null)
                synchronized(session) {
                    session.delete()
                    check(!session.baseFile.exists())
                }
            }
            clearingSession = false
            callback(result)
        }
    }

    fun record(url: String, title: String) {
        writer.execute {
            runCatching {
                writableDatabase.insertOrThrow("visits", null, ContentValues().apply {
                    put("url", url)
                    put("title", title)
                })
            }
        }
    }

    fun title(url: String, title: String) {
        if (title.isBlank() || title == url) return
        writer.execute {
            runCatching {
                writableDatabase.update("visits", ContentValues().apply { put("title", title) },
                    "url = ? AND title <> ?", arrayOf(url, title))
            }
        }
    }

    fun loadVisits(before: Long?, callback: (Result<List<Visit>>) -> Unit) {
        writer.execute {
            callback(runCatching {
                readableDatabase.query("visits", arrayOf("_id", "url", "title"),
                    before?.let { "_id < ?" }, before?.let { arrayOf(it.toString()) }, null, null, "_id DESC", "$PAGE_SIZE")
                    .use { cursor -> buildList {
                        while (cursor.moveToNext()) add(Visit(cursor.getLong(0), cursor.getString(1), cursor.getString(2)))
                    } }
            })
        }
    }

    private fun sessionVersion() = "${Build.FINGERPRINT}:${WebView.getCurrentWebViewPackage()?.versionName.orEmpty()}"

    // Chromium's parcel state is disposable across platform and WebView updates.
    // The history database and the fallback open-tab URLs use stable formats.
    fun saveSession(bundle: Bundle) {
        val parcel = Parcel.obtain()
        val bytes = try {
            parcel.writeString(sessionVersion())
            parcel.writeBundle(bundle)
            if (parcel.dataSize() > MAX_SESSION) ByteArray(0) else parcel.marshall()
        } finally { parcel.recycle() }
        writer.execute {
            synchronized(session) {
                if (bytes.isEmpty()) { session.delete(); return@synchronized }
                runCatching {
                    val output = session.startWrite()
                    try {
                        output.write(bytes)
                        session.finishWrite(output)
                    } catch (error: Exception) {
                        session.failWrite(output)
                        throw error
                    }
                }
            }
        }
    }

    fun restoreSession(): Bundle? = synchronized(session) {
        runCatching {
            if (clearingSession) return@synchronized null
            if (session.baseFile.length() !in 1..MAX_SESSION) return@synchronized null
            val bytes = session.readFully()
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                if (parcel.readString() == sessionVersion()) parcel.readBundle() else null
            } finally { parcel.recycle() }
        }.getOrNull()
    }

    companion object {
        const val PAGE_SIZE = 50
        private const val MAX_SESSION = 2 * 1024 * 1024L
        private var instance: BrowserRecords? = null

        @Synchronized
        fun get(context: Context): BrowserRecords = instance ?: BrowserRecords(context.applicationContext).also { instance = it }
    }
}
