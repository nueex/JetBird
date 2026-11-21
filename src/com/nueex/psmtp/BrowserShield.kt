/* Copyright (C) 2026 nueex. SPDX-License-Identifier: GPL-3.0-or-later */
package com.nueex.psmtp

import android.content.Context
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

internal object BrowserShield {
    @Volatile var enabled = true
    @Volatile private var rules: ContentFilters? = null
    private var started = false
    private val executor = Executors.newSingleThreadExecutor()
    private val styles = object : LinkedHashMap<String, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 8
    }

    @Synchronized
    fun prepare(context: Context, ready: () -> Unit) {
        val assets = context.applicationContext.assets
        if (!started) {
            started = true
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                    intercept(request, "")
            })
            executor.execute {
                try {
                    fun lines(name: String) = GZIPInputStream(assets.open("filters/$name.gz")).bufferedReader()
                    val suffixes = lines("public_suffix_list.dat").use { input ->
                        input.lineSequence().filter { it.isNotBlank() && !it.startsWith("//") }
                            .mapNotNull(ContentFilters::publicSuffixRule).toSet()
                    }
                    val input = sequence {
                        for (name in listOf("easylist.txt", "easyprivacy.txt", "ublock-ads.txt", "ublock-privacy.txt",
                            "ublock-2026.txt", "ublock-mobile.txt")) {
                            lines(name).use { yieldAll(activeLines(it.lineSequence())) }
                        }
                    }
                    rules = ContentFilters.compile(input, suffixes)
                } catch (error: Exception) {
                    android.util.Log.w("PSMTP-Browser", "Failed to load browser filters", error)
                }
            }
        }
        executor.execute(ready)
    }

    private fun activeLines(lines: Sequence<String>) = sequence {
        val parents = ArrayDeque<Boolean>()
        var enabled = true
        for (line in lines) {
            when {
                line.startsWith("!#if ") -> {
                    parents.addLast(enabled)
                    val condition = line.removePrefix("!#if ").trim()
                    val supported = condition.removePrefix("!") in setOf("env_mobile", "env_chromium")
                    enabled = enabled && (supported != condition.startsWith('!'))
                }
                line == "!#else" -> enabled = parents.lastOrNull() == true && !enabled
                line == "!#endif" -> enabled = parents.removeLastOrNull() ?: true
                enabled && !line.startsWith("!#") -> yield(line)
            }
        }
    }

    fun blocksNavigation(target: String, opener: String, type: String): Boolean = enabled &&
        rules?.blocks(target, ContentFilters.host(target), ContentFilters.host(opener), type) == true

    fun intercept(request: WebResourceRequest, document: String): WebResourceResponse? {
        val filter = rules ?: return null
        if (!enabled || request.isForMainFrame || request.url.scheme !in listOf("http", "https")) return null
        val url = request.url
        val host = url.host.orEmpty().trimEnd('.').lowercase(Locale.ROOT)
        val headers = request.requestHeaders
        val origin = document.ifEmpty { ContentFilters.host(headers.header("Referer")) }
        val type = when (headers.header("Sec-Fetch-Dest")) {
            "script", "worker", "sharedworker", "serviceworker" -> "script"
            "style" -> "stylesheet"
            "image", "font" -> headers.header("Sec-Fetch-Dest")
            "audio", "video", "track" -> "media"
            "frame", "iframe" -> "subdocument"
            "empty" -> "xmlhttprequest"
            else -> when (url.lastPathSegment.orEmpty().substringAfterLast('.').lowercase(Locale.ROOT)) {
                "js", "mjs" -> "script"
                "css" -> "stylesheet"
                "svg", "png", "jpeg", "jpg", "gif", "webp", "avif", "ico" -> "image"
                "woff", "woff2", "ttf" -> "font"
                "mp4", "webm", "mp3", "m3u8" -> "media"
                else -> "other"
            }
        }
        if (!filter.blocks(url.toString(), host, origin, type)) return null
        return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
            mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
    }

    private fun Map<String, String>.header(name: String) = entries.firstOrNull { it.key.equals(name, true) }?.value.orEmpty()

    fun applyCosmetic(view: WebView, url: String) {
        val filter = rules ?: return
        val host = ContentFilters.host(url)
        if (!enabled || host.isEmpty()) return
        executor.execute {
            val css = styles.getOrPut(host) {
                filter.selectors(host).take(16000).joinToString("\n") { "$it{display:none!important}" }
            }
            if (css.isBlank()) return@execute
            view.post {
                if (!enabled || ContentFilters.host(view.url.orEmpty()) != host || !view.isAttachedToWindow) return@post
                view.evaluateJavascript("""
                    (() => {
                      if (!document.documentElement || document.querySelector('style[data-psmtp-filters]')) return;
                      const node = document.createElement('style');
                      node.setAttribute('data-psmtp-filters', '');
                      node.textContent = ${JSONObject.quote(css)};
                      (document.head || document.documentElement).appendChild(node);
                    })()
                """.trimIndent(), null)
            }
        }
    }
}
