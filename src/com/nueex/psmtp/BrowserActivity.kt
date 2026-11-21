/* Copyright (C) 2026 nueex. SPDX-License-Identifier: GPL-3.0-or-later */
package com.nueex.psmtp

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast
import android.widget.TextView
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicReference

class BrowserActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(base))

    private enum class SearchEngine(val label: String, val endpoint: String) {
        GOOGLE("Google", "https://www.google.com/search"),
        BING("Bing", "https://www.bing.com/search"),
        DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/")
    }
    private data class Popup(val opener: String, val foreground: Boolean)

    private val settings by lazy { getSharedPreferences("browser", MODE_PRIVATE) }
    private val searchEngine: SearchEngine
        get() {
            val saved = settings.getString(SEARCH_ENGINE, null)
            return SearchEngine.entries.firstOrNull { it.name == saved } ?: SearchEngine.GOOGLE
        }
    private val records by lazy { BrowserRecords.get(this) }
    private val mobileAgent by lazy { WebSettings.getDefaultUserAgent(this) }
    private val desktopAgent by lazy {
        mobileAgent.replaceFirst(Regex("""\(Linux; Android[^)]*\)"""), "(X11; Linux x86_64)")
            .replace(Regex(""" Version/[^\s]+"""), "").replace(" Mobile", "")
    }
    private val desktopSite get() = settings.getBoolean("desktop_site", false)
    @Volatile private var adBlock = true
    private lateinit var d: Design
    private val updates by lazy { UpdateUi(this, { d }) }
    private lateinit var address: EditText
    private lateinit var root: FrameLayout
    private lateinit var viewport: FrameLayout
    private lateinit var blankPage: ImageView
    private lateinit var pageError: TextView
    private lateinit var refresh: BrowserRefresh
    private lateinit var tabButton: FrameLayout
    private lateinit var bookmarkButton: FrameLayout
    private lateinit var loading: ProgressBar
    private lateinit var browserContent: View
    private var menu: PopupWindow? = null
    private var clearDialog: Dialog? = null
    private var tabsSheet: FrameLayout? = null
    private var bookmarkList: LinearLayout? = null
    private var bookmarks: List<BrowserRecords.Bookmark>? = null
    private val bookmarkWrites = mutableSetOf<String>()
    private var clearingData = false
    private var systemTop = 0
    private var systemBottom = 0
    private val tabs = mutableListOf<WebView?>()
    private val redirectTabs = mutableSetOf<WebView>()
    private val pageErrors = mutableMapOf<WebView, String>()
    private val popups = mutableMapOf<WebView, Popup>()
    private var selected = 0
    private val active get() = tabs.getOrNull(selected)
    private var filtersReady = false
    private var waitingPage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adBlock = settings.getBoolean("adblock", true)
        BrowserShield.enabled = adBlock
        CookieManager.getInstance().setAcceptCookie(true)
        d = Design(this, Design.isDark(this, intent.getStringExtra("theme") ?: "System"))
        with(d) {
            window.setBackgroundDrawable(ColorDrawable(browserBackground))
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, resource("page_open_enter", "anim"),
                    resource("page_open_exit", "anim"), browserBackground)
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, resource("page_close_enter", "anim"),
                    resource("page_close_exit", "anim"), browserBackground)
            }
            val column = column()
            browserContent = column
            column.space(4)
            val toolbar = row().apply { padding(12, 0, 11, 0) }
            val box = row().apply { background = round(search, 48); padding(11, 0, 12, 0) }
            box.add(iconButton("search", 24, tr("Search or enter URL"), subtle) { address.requestFocus(); showKeyboard() },
                43, 47)
            address = edit(tr("Search or URL"), "", 19).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                setSelectAllOnFocus(true)
                setOnEditorActionListener { _, action, event ->
                    val enter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                    if (action == EditorInfo.IME_ACTION_GO || (enter && event.action == android.view.KeyEvent.ACTION_DOWN)) {
                        navigate(text.toString())
                    }
                    action == EditorInfo.IME_ACTION_GO || enter
                }
            }
            box.add(address, 0, 47, 1f)
            bookmarkButton = frame().apply { tap { toggleBookmark() } }
            box.add(bookmarkButton, 40, 47)
            toolbar.add(box, 0, 47, 1f)
            toolbar.add(View(this@BrowserActivity), 10, 1)
            tabButton = frame().apply { tap(tr("Tabs")) { showTabs() } }
            toolbar.add(tabButton, 49, 47)
            toolbar.add(iconButton("more", 24, tr("Browser menu"), action = ::showMenu), 46, 47)
            column.add(toolbar, height = 47)
            loading = ProgressBar(this@BrowserActivity, null, android.R.attr.progressBarStyleHorizontal)
                .apply {
                    max = 100
                    visibility = View.INVISIBLE
                    progressTintList = ColorStateList.valueOf(ink)
                    progressBackgroundTintList = ColorStateList.valueOf(line)
                }
            column.add(loading, height = 3)
            column.space(10)
            viewport = frame()
            blankPage = ImageView(this@BrowserActivity).apply {
                val header = headerArtwork(dark = dark)
                // The bird occupies the left 40% of the supplied wordmark.
                setImageBitmap(Bitmap.createBitmap(header, 0, 0, header.width * 2 / 5, header.height))
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "JetBird"
            }
            pageError = text("", 19, 550, subtle).apply {
                gravity = Gravity.CENTER
                padding(24, 0, 24, 0)
            }
            column.add(viewport, height = 0, weight = 1f)
            refresh = BrowserRefresh(d, column, indicatorTop = { viewport.top }, canRefresh = {
                tabsSheet == null && active?.let {
                    (it.isShown || it in pageErrors) && httpsUrl(it.url.orEmpty()) && !it.canScrollVertically(-1)
                } == true
            }) { active?.reload() }
            root = refresh.apply { setBackgroundColor(browserBackground); isFocusableInTouchMode = true }
            setContentView(root)
            bars(this@BrowserActivity, root) { insets ->
                systemBottom = Design.bottomInset(insets)
                systemTop = Design.topInset(insets)
                column.setPadding(0, systemTop, 0, systemBottom)
                tabsSheet?.setPadding(0, systemTop, 0, systemBottom)
            }
            val session = savedInstanceState?.takeIf { it.containsKey("tabs") }
                ?: retainedTabs.also { retainedTabs = null } ?: records.restoreSession()
            @Suppress("DEPRECATION")
            val savedTabs = runCatching {
                if (Build.VERSION.SDK_INT >= 33) session?.getParcelableArrayList("tabs", Bundle::class.java)
                else session?.getParcelableArrayList<Bundle>("tabs")
            }.getOrNull()
            val urls = runCatching { JSONArray(settings.getString("open_tabs", "[]")) }.getOrElse { JSONArray() }
            val toLoad = mutableMapOf<WebView, String>()
            if (!savedTabs.isNullOrEmpty()) {
                savedTabs.take(MAX_TABS).forEachIndexed { index, state ->
                    val tab = if (state.isEmpty) null else createTab().apply {
                        if (runCatching { restoreState(state) }.getOrNull() == null) {
                            urls.optString(index).takeIf(::httpsUrl)?.let { toLoad[this] = it }
                        }
                    }
                    tabs.add(tab)
                }
                selected = (session?.getInt("selected") ?: 0).coerceIn(tabs.indices)
            } else {
                for (index in 0 until urls.length().coerceAtMost(MAX_TABS)) {
                    val url = urls.optString(index)
                    tabs.add(if (httpsUrl(url)) createTab().also { toLoad[it] = url } else null)
                }
                if (tabs.isEmpty()) tabs.add(null)
                selected = settings.getInt("selected_tab", 0).coerceIn(tabs.indices)
            }
            switchTab(selected)
            records.loadBookmarks { result -> runOnUiThread { showBookmarkResult(result) } }
            root.requestFocus()
            BrowserShield.prepare(this@BrowserActivity) {
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    filtersReady = true
                    toLoad.forEach { (tab, url) ->
                        if (tab in tabs && (tab != active || waitingPage == null)) {
                            redirectTabs.add(tab)
                            tab.loadUrl(url)
                        }
                    }
                    waitingPage?.let(::openAddress)
                    waitingPage = null
                }
            }
        }
    }

    private fun saveTabs() = Bundle().apply {
        putParcelableArrayList("tabs", ArrayList(tabs.map { tab -> Bundle().apply { tab?.saveState(this) } }))
        putInt("selected", selected)
    }

    private fun persistTabs() {
        settings.edit().putString("open_tabs", JSONArray(tabs.map { it?.url.orEmpty() }).toString())
            .putInt("selected_tab", selected).apply()
        val snapshot = saveTabs()
        retainedTabs = snapshot
        runCatching { records.saveSession(snapshot) }
    }

    private fun createTab(): WebView = WebView(this).apply {
        setBackgroundColor(d.browserBackground)
        visibility = View.INVISIBLE
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.safeBrowsingEnabled = true
        settings.setSupportMultipleWindows(true)
        if (desktopSite) settings.userAgentString = desktopAgent
        val pageHost = AtomicReference("")
        webViewClient = object : WebViewClient() {
            private var committedPage = ""
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (!clearingData && !isReload && httpsUrl(url) && view in tabs) records.record(url, view.title.orEmpty())
                if (view == active) updateBookmarkButton()
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                val popup = popups[view]
                if (popup != null && request.isForMainFrame) {
                    if (target == "about:blank") return false
                    if (!httpsUrl(target) || BrowserShield.blocksNavigation(target, popup.opener, "popup") ||
                        BrowserShield.blocksNavigation(target, popup.opener, "document")) {
                        dismissPopup(view)
                        return true
                    }
                    return false
                }
                if (!httpsUrl(target)) return true
                val redirects = request.isRedirect ||
                    (committedPage.isNotEmpty() && !request.hasGesture() && view.url == committedPage &&
                        target.substringBefore('#') != committedPage.substringBefore('#'))
                if (request.isForMainFrame && redirects && request.method == "GET") {
                    if (BrowserShield.blocksNavigation(target, committedPage, "document") ||
                        BrowserShield.blocksNavigation(target, committedPage, "popunder")) return true
                    if (view in redirectTabs) return false
                    val child = addTab(view == active) ?: return false
                    redirectTabs.add(child)
                    child.loadUrl(target)
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                BrowserShield.intercept(request, pageHost.get())

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                popups[view]?.let { popup ->
                    if (url == "about:blank") return
                    if (!httpsUrl(url) || BrowserShield.blocksNavigation(url, popup.opener, "popup") ||
                        BrowserShield.blocksNavigation(url, popup.opener, "document")) {
                        dismissPopup(view)
                        return
                    }
                    if (view !in tabs) {
                        tabs.add(view)
                        redirectTabs.add(view)
                        switchTab(if (popup.foreground) tabs.lastIndex else selected)
                    }
                }
                pageHost.set(ContentFilters.host(url))
                pageErrors.remove(view)
                view.visibility = View.VISIBLE
                if (view == active) {
                    pageError.visibility = View.GONE
                    blankPage.visibility = if (httpsUrl(url)) View.GONE else View.VISIBLE
                    if (!address.hasFocus()) showAddress(url)
                    updateBookmarkButton()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame || !httpsUrl(request.url.toString())) return
                val connectionError = error.errorCode == ERROR_HOST_LOOKUP || error.errorCode == ERROR_CONNECT ||
                    error.errorCode == ERROR_TIMEOUT || error.errorCode == ERROR_IO
                pageErrors[view] = if (!hasInternetRoute() || connectionError && !hasValidatedInternet())
                    NO_INTERNET_CONNECTION else "Couldn’t load the page. Try again."
                view.visibility = View.INVISIBLE
                if (view == active) {
                    showPageError()
                    refresh.pageFinished()
                }
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                committedPage = url
                BrowserShield.applyCosmetic(view, url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!clearingData && view.url == url && httpsUrl(url) && view in tabs) records.title(url, view.title.orEmpty())
                if (view.url == url) {
                    redirectTabs.remove(view)
                    if (view in tabs) popups.remove(view)
                }
                if (view == active) {
                    if (view.url == url) refresh.pageFinished()
                    if (!address.hasFocus()) showAddress(url)
                    updateBookmarkButton()
                }
                BrowserShield.applyCosmetic(view, url)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view == active) showProgress(newProgress)
            }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture || clearingData || tabs.size + popups.keys.count { it !in tabs } >= MAX_TABS) return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                val child = createTab()
                popups[child] = Popup(view.url.orEmpty(), view == active)
                transport.webView = child
                resultMsg.sendToTarget()
                root.postDelayed({ if (child in popups && child !in tabs) dismissPopup(child) }, 15_000)
                return true
            }
            override fun onCloseWindow(window: WebView) {
                if (window in popups) { dismissPopup(window); return }
                val index = tabs.indexOf(window)
                if (index >= 0 && tabs.size > 1) closeTab(index)
            }
        }
        setDownloadListener { url, agent, disposition, _, length ->
            offerDownload(url, agent.orEmpty(), this.url.orEmpty(), dispositionFilename(disposition), length)
        }
        setOnLongClickListener {
            val hit = hitTestResult
            val url = hit.extra.orEmpty()
            if (hit.type != WebView.HitTestResult.SRC_ANCHOR_TYPE || !httpsUrl(url)) false
            else {
                d.dialog().setItems(arrayOf(tr("Open link"), tr("Open in new tab"), tr("Download link"))) { _, option ->
                    when (option) {
                        0 -> navigate(url)
                        1 -> addTab(true)?.loadUrl(url)
                        else -> offerDownload(url, settings.userAgentString, this.url.orEmpty())
                    }
                }.show()
                true
            }
        }
    }

    private fun dismissPopup(view: WebView) {
        if (popups.remove(view) == null) return
        val attached = view in tabs
        root.post {
            val index = tabs.indexOf(view)
            if (index >= 0 && !isDestroyed) closeTab(index)
            else if (!attached) view.destroy()
        }
    }

    private fun addTab(focus: Boolean): WebView? {
        if (tabs.size + popups.keys.count { it !in tabs } >= MAX_TABS) return null
        val view = createTab()
        tabs.add(view)
        switchTab(if (focus) tabs.lastIndex else selected)
        return view
    }

    private fun closeTab(index: Int) {
        val previous = selected
        tabs.removeAt(index)?.let {
            popups.remove(it)
            redirectTabs.remove(it)
            pageErrors.remove(it)
            viewport.removeView(it)
            it.destroy()
        }
        if (tabs.isEmpty()) tabs.add(null)
        switchTab((if (index < previous) previous - 1 else previous).coerceAtMost(tabs.lastIndex))
    }

    private fun switchTab(index: Int) = with(d) {
        val animate = index != selected && Motion.enabled
        refresh.finish()
        (viewport.getChildAt(0) as? WebView)?.takeIf { it != tabs[index] }?.onPause()
        viewport.removeAllViews()
        selected = index
        active?.let { view ->
            viewport.place(view, height = -1)
            if (view.url != null) view.visibility = if (view in pageErrors) View.INVISIBLE else View.VISIBLE
            view.onResume()
            applyDesktopSite(view)
        }
        blankPage.visibility = if (httpsUrl(active?.url.orEmpty())) View.GONE else View.VISIBLE
        viewport.place(blankPage, 130, 130, Gravity.CENTER)
        viewport.place(pageError, height = -2, gravity = Gravity.CENTER)
        showPageError()
        showAddress(active?.url.orEmpty())
        showProgress(active?.progress ?: 100)
        updateBookmarkButton()
        tabButton.removeAllViews()
        tabButton.place(icon("tab:${tabs.size}", 26), 43, 44, Gravity.CENTER)
        tabButton.contentDescription = tr("Tabs, {0} open", tabs.size)
        if (animate) {
            viewport.animate().cancel()
            viewport.alpha = .5f
            viewport.animate().alpha(1f).setDuration(200).setInterpolator(Motion.settle).start()
        }
    }

    private fun showPageError() {
        val message = pageErrors[active]
        pageError.text = message?.let { tr(it) }.orEmpty()
        pageError.visibility = if (message == null) View.GONE else View.VISIBLE
        if (message != null) blankPage.visibility = View.GONE
    }

    private fun showProgress(progress: Int) {
        loading.progress = progress
        loading.visibility = if (progress < 100) View.VISIBLE else View.INVISIBLE
    }

    private fun navigate(input: String) {
        val value = input.trim()
        if (value.isEmpty()) return
        val inferred = "https://$value"
        val host = Uri.parse(inferred).host.orEmpty()
        val candidate = when {
            value.startsWith("https://", true) -> value
            value.startsWith("http://", true) -> "https://" + value.substring(7)
            Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(value) -> value
            (host.contains('.') || host.contains(':') || host.equals("localhost", true)) && httpsUrl(inferred) -> inferred
            else -> Uri.parse(searchEngine.endpoint).buildUpon().appendQueryParameter("q", value).build().toString()
        }
        if (!httpsUrl(candidate)) {
            address.error = tr("Enter a valid HTTPS address.")
            return
        }
        hideKeyboard()
        openAddress(candidate)
    }

    private fun openAddress(candidate: String) {
        if (clearingData) return
        if (adBlock && !filtersReady) { waitingPage = candidate; showAddress(candidate); return }
        val view = active ?: createTab().also {
            tabs[selected] = it
            switchTab(selected)
        }
        showAddress(candidate)
        redirectTabs.add(view)
        view.loadUrl(candidate)
    }

    private fun showAddress(url: String) {
        val uri = Uri.parse(url)
        val path = uri.path.orEmpty()
        val friendly = if ((path.isEmpty() || path == "/") && uri.query == null && uri.fragment == null) {
            uri.host?.removePrefix("www.") ?: url
        } else url.removePrefix("https://")
        address.setText(friendly)
        address.setSelection(0)
    }

    private fun showMenu(anchor: View) {
        hideKeyboard()
        menu?.dismiss()
        menu = d.showMenu(anchor, 235, 5, -6, listOf(
            MenuOption(tr("Ad Blocker"), checked = adBlock, bold = false) {
                adBlock = !adBlock
                BrowserShield.enabled = adBlock
                settings.edit().putBoolean("adblock", adBlock).apply()
                if (active?.url != null) active?.reload()
            },
            MenuOption(tr("Desktop Site"), checked = desktopSite, bold = false) {
                settings.edit().putBoolean("desktop_site", !desktopSite).apply()
                active?.let(::applyDesktopSite)
            },
            MenuOption(tr("Back")) { active?.takeIf(WebView::canGoBack)?.goBack() },
            MenuOption(tr("Forward")) { active?.takeIf(WebView::canGoForward)?.goForward() },
            MenuOption(tr("Reload")) { active?.reload() },
            MenuOption(tr("Bookmarks")) { showBookmarks() },
            MenuOption(tr("History")) { showHistory() },
            MenuOption(tr("Settings")) { showSettings() },
            MenuOption(tr("Check for Updates")) { updates.check(manual = true) },
            MenuOption(tr("Return to App")) { returnToApp() }
        ), rowHeight = 47)
    }

    private fun applyDesktopSite(view: WebView) {
        val agent = if (desktopSite) desktopAgent else mobileAgent
        if (view.settings.userAgentString == agent) return
        val reload = view.progress == 100 && httpsUrl(view.url.orEmpty())
        view.settings.userAgentString = agent
        if (reload) view.reload()
    }

    private fun showSettings() = with(d) {
        val content = showBrowserPage(tr("Browser Settings"))
        val gap = View(this@BrowserActivity)
        val selector = frame()
        fun showSearchEngine() {
            val selected = searchEngine
            selector.removeAllViews()
            selector.place(settingRow(tr("Search engine"), selected.label, "down") { anchor ->
                menu?.dismiss()
                menu = showSettingsMenu(anchor, gap, 22, SearchEngine.entries.map { engine ->
                    MenuOption(engine.label, checked = engine == selected) {
                        if (engine != selected) {
                            settings.edit().putString(SEARCH_ENGINE, engine.name).apply()
                            showSearchEngine()
                        }
                    }
                })
            })
        }
        content.space(8)
        content.add(selector)
        content.add(gap, 1, 22)
        showSearchEngine()
        content.add(text(tr("Privacy"), 19, 650))
        content.space(10)
        content.add(row().apply {
            background = round(card, 16)
            padding(16, 12, 13, 12)
            minimumHeight = u(68)
            add(column().apply {
                add(text(tr("Clear browsing data"), 18, 650))
                space(5)
                add(text(tr("Cache, cookies, site data, and history"), 15, color = subtle))
            }, 0, -2, 1f)
            add(icon("right", 18, subtle), 28, 37)
            tap(radius = 16) { confirmClearBrowsingData() }
            isEnabled = !clearingData
        })
    }

    private fun showBrowserPage(title: String, showScrollbar: Boolean = true): LinearLayout = with(d) {
        closeTabsSheet()
        hideKeyboard()
        refresh.finish()
        val overlay = frame().apply { setBackgroundColor(d.background); isClickable = true }
        val screen = column().apply { padding(18, 10, 18, 8); accessibilityPaneTitle = title }
        screen.add(row().apply {
            add(iconButton("left", 22, tr("Back to browser")) { closeTabsSheet() }, 46, 49)
            add(text(title, 28, 700), 0, -2, 1f)
        }, height = 57)
        val list = column().apply { padding(5, 8, 5, 20) }
        screen.add(ScrollView(this@BrowserActivity).apply {
            addView(list)
            isFillViewport = true
            isVerticalScrollBarEnabled = showScrollbar
        },
            height = 0, weight = 1f)
        overlay.place(screen, height = -1)
        overlay.setPadding(0, systemTop, 0, systemBottom)
        tabsSheet = overlay
        browserContent.visibility = View.INVISIBLE
        root.place(overlay, height = -1)
        if (Motion.enabled) {
            screen.alpha = 0f
            screen.animate().alpha(1f).setDuration(160).start()
        }
        list
    }

    private fun updateBookmarkButton() = with(d) {
        val url = active?.url?.takeIf(::httpsUrl)
        val saved = url != null && bookmarks.orEmpty().any { it.url == url }
        bookmarkButton.removeAllViews()
        bookmarkButton.place(icon(if (saved) "star-on" else "star", 22, if (saved) ink else subtle),
            -1, -1)
        bookmarkButton.isEnabled = url != null && bookmarks != null && url !in bookmarkWrites && !clearingData
        bookmarkButton.alpha = if (bookmarkButton.isEnabled) 1f else .4f
        bookmarkButton.isSelected = saved
        bookmarkButton.contentDescription = if (saved) tr("Remove bookmark") else tr("Add bookmark")
    }

    private fun toggleBookmark() {
        val current = active ?: return
        val url = current.url?.takeIf(::httpsUrl) ?: return
        val saved = bookmarks?.any { it.url == url } ?: return
        val title = current.title?.takeIf(String::isNotBlank) ?: Uri.parse(url).host ?: url
        changeBookmark(url, title.takeUnless { saved })
    }

    private fun changeBookmark(url: String, title: String?) {
        if (!bookmarkWrites.add(url)) return
        updateBookmarkButton()
        records.changeBookmark(url, title) { result -> runOnUiThread {
            bookmarkWrites.remove(url)
            showBookmarkResult(result)
        } }
    }

    private fun showBookmarkResult(result: Result<List<BrowserRecords.Bookmark>>) {
        if (isDestroyed || isFinishing) return
        result.onSuccess { bookmarks = it }
            .onFailure { Toast.makeText(this, tr("Couldn’t update bookmarks."), Toast.LENGTH_SHORT).show() }
        updateBookmarkButton()
        bookmarkList?.let(::renderBookmarks)
    }

    private fun showBookmarks() {
        val list = showBrowserPage(tr("Bookmarks"))
        bookmarkList = list
        renderBookmarks(list)
        if (bookmarks == null) records.loadBookmarks { result -> runOnUiThread { showBookmarkResult(result) } }
    }

    private fun renderBookmarks(list: LinearLayout) = with(d) {
        list.removeAllViews()
        val saved = bookmarks
        if (saved.isNullOrEmpty()) {
            list.add(text(if (saved == null) tr("Loading bookmarks…") else tr("No bookmarks yet."), 16, color = muted)
                .apply { padding(10, 16, 10, 16) })
            return@with
        }
        saved.forEach { bookmark ->
            list.add(row().apply {
                background = round(card, 14)
                padding(12, 5, 6, 5)
                add(column().apply {
                    padding(4, 8, 5, 8)
                    add(text(bookmark.title, 17, 650).oneLine())
                    space(3)
                    add(text(bookmark.url.removePrefix("https://"), 14, color = muted).oneLine())
                    tap(tr("Open {0}", bookmark.title)) { closeTabsSheet(); openAddress(bookmark.url) }
                }, 0, -2, 1f)
                add(iconButton("more", 20, tr("Manage {0}", bookmark.title)) { anchor ->
                    menu?.dismiss()
                    menu = d.showMenu(anchor, 195, 3, 0, listOf(
                        MenuOption(tr("Rename")) { renameBookmark(bookmark) },
                        MenuOption(tr("Remove")) { changeBookmark(bookmark.url, null) }
                    ))
                }, 44, 48)
            })
            list.space(7)
        }
    }

    private fun renameBookmark(bookmark: BrowserRecords.Bookmark) = with(d) {
        val field = edit(tr("Bookmark name"), bookmark.title).apply {
            setSelectAllOnFocus(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(200))
        }
        dialog().setTitle(tr("Rename bookmark")).setView(column().apply {
            padding(22, 10, 22, 8)
            add(field, height = 50)
        }).setNegativeButton(tr("Cancel"), null).setPositiveButton(tr("Save")) { _, _ ->
            field.text.toString().trim().takeIf(String::isNotEmpty)?.let { changeBookmark(bookmark.url, it) }
        }.show()
    }

    private fun confirmClearBrowsingData() = with(d) {
        if (clearingData || clearDialog != null) return@with
        val dialog = Dialog(this@BrowserActivity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val form = column().apply {
            background = sheetSurface(34)
            padding(20, 12, 20, 20)
            accessibilityPaneTitle = tr("Clear browsing data?")
            add(frame().apply {
                place(View(context).apply { background = round(muted, 8) }, 46, 6, Gravity.CENTER)
            }, height = 16)
            space(12)
            add(text(tr("Clear browsing data?"), 27, 750))
            space(10)
            add(text(tr("This clears cache, cookies, site data, and history and closes your open tabs. Your bookmarks are kept."),
                16, color = subtle).apply { setLineSpacing(u(3).toFloat(), 1f) })
            space(20)
            add(row().apply {
                add(pill(tr("Cancel"), 18, field, ink) { dialog.dismiss() }, 0, 48, 1f)
                add(View(context), 10, 1)
                add(pill(tr("Clear"), 18, tone(0xae2222, 0xe38383), tone(0xffffff, 0x201414)) {
                    dialog.dismiss()
                    clearBrowsingData()
                }, 0, 48, 1f)
            })
        }
        dialog.setOnDismissListener { clearDialog = null }
        clearDialog = dialog
        showBottomDialog(dialog, form)
    }

    private fun clearBrowsingData() {
        clearingData = true
        waitingPage = null
        val clearView = active ?: WebView(this)
        val views = (tabs.filterNotNull() + popups.keys + clearView).distinct()
        tabs.clear()
        popups.clear()
        redirectTabs.clear()
        pageErrors.clear()
        views.forEach(WebView::stopLoading)
        clearView.clearCache(true)
        views.forEach { view ->
            view.clearHistory()
            viewport.removeView(view)
            view.destroy()
        }
        tabs.add(null)
        switchTab(0)
        retainedTabs = null
        settings.edit().remove("open_tabs").remove("selected_tab").apply()
        WebStorage.getInstance().deleteAllData()
        WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
        GeolocationPermissions.getInstance().clearAll()

        var operations = 2
        var successful = true
        fun completed(ok: Boolean) {
            successful = successful && ok
            if (--operations != 0) return
            clearingData = false
            if (isDestroyed || isFinishing) return
            updateBookmarkButton()
            Toast.makeText(this, if (successful) tr("Browsing data cleared.") else tr("Couldn’t clear all browsing data."),
                Toast.LENGTH_SHORT).show()
        }
        records.clearBrowsingHistory { result -> runOnUiThread { completed(result.isSuccess) } }
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies {
            Thread({
                val flushed = runCatching { cookies.flush() }.isSuccess
                runOnUiThread { completed(flushed) }
            }, "Clear browser cookies").start()
        }
    }

    private fun showHistory(): Unit = with(d) {
        val list = showBrowserPage(tr("History"), showScrollbar = false)
        val overlay = tabsSheet

        fun load(before: Long?) {
            val loading = text(tr("Loading history…"), 15, color = muted).apply { padding(14, 14, 14, 14) }
            list.add(loading)
            records.loadVisits(before) { result ->
                runOnUiThread {
                    if (tabsSheet !== overlay || isDestroyed) return@runOnUiThread
                    list.removeView(loading)
                    val visits = result.getOrElse {
                        list.add(text(tr("Couldn’t open browsing history."), 16))
                        return@runOnUiThread
                    }
                    if (before == null && visits.isEmpty()) list.add(text(tr("No visited pages yet."), 16, color = muted))
                    for (visit in visits) {
                        list.add(column().apply {
                            padding(14, 9, 12, 9)
                            add(text(visit.title.ifBlank { Uri.parse(visit.url).host ?: visit.url }, 17, 650).oneLine())
                            add(text(visit.url.removePrefix("https://"), 14, color = muted).oneLine())
                            tap(radius = 10) { closeTabsSheet(); openAddress(visit.url) }
                        })
                        list.add(divider(), height = 1)
                    }
                    if (visits.size == BrowserRecords.PAGE_SIZE) {
                        lateinit var more: View
                        more = pill(tr("Earlier visits"), 16) { list.removeView(more); load(visits.last().id) }
                        list.add(more, height = 44)
                    }
                }
            }
        }
        load(null)
    }

    private fun showTabs(): Unit = with(d) {
        hideKeyboard()
        closeTabsSheet()
        menu?.dismiss()
        val overlay = frame().apply { setBackgroundColor(d.background); isClickable = true }
        val screen = column().apply {
            padding(18, 10, 18, 8)
            accessibilityPaneTitle = tr("Browser tabs")
        }
        screen.add(row().apply {
            add(iconButton("left", 22, tr("Back to browser")) { closeTabsSheet() }, 46, 49)
            add(text(tr("Tabs"), 26, 700), 0, 49, 1f)
            if (tabs.size < MAX_TABS) add(pill(tr("New Tab"), 16, weight = 650) {
                closeTabsSheet()
                tabs.add(null)
                switchTab(tabs.lastIndex)
            }.oneLine(), -2, 42)
        }, height = 55)
        screen.space(12)
        val list = column()
        for ((index, page) in tabs.withIndex()) {
            val url = page?.url.orEmpty()
            val title = page?.title?.takeIf(String::isNotBlank) ?: Uri.parse(url).host ?: tr("New tab")
            val site = Uri.parse(url).host?.removePrefix("www.").orEmpty()
            val item = row().apply {
                if (index == selected) background = round(elevated, 10)
                padding(11, 4, 5, 4)
            }
            item.add(row().apply {
                tap(tr("Open {0}", title)) { closeTabsSheet(); switchTab(index) }
                val favicon = frame().apply { background = round(search, 11) }
                val image = page?.favicon
                if (image != null) favicon.place(ImageView(this@BrowserActivity).apply {
                    setImageBitmap(image); scaleType = ImageView.ScaleType.FIT_CENTER
                }, width = 24, height = 24, gravity = Gravity.CENTER)
                else favicon.place(icon("globe", 22, subtle), width = 26, height = 26, gravity = Gravity.CENTER)
                add(favicon, 42, 42)
                add(column().apply {
                    padding(11, 0, 5, 0)
                    add(text(title, 17, if (index == selected) 700 else 550).apply {
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    })
                    if (site.isNotEmpty() && site != title) add(text(site, 13, color = muted).apply {
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    })
                }, 0, -2, 1f)
            }, 0, 60, 1f)
            item.add(iconButton("close", 19, tr("Close {0}", title)) { closeTab(index); showTabs() }, 45, 60)
            list.add(item, height = 64)
            if (index < tabs.lastIndex) list.add(divider(), height = 1)
        }
        screen.add(ScrollView(this@BrowserActivity).apply { addView(list); isFillViewport = true },
            height = 0, weight = 1f)
        overlay.place(screen, height = -1)
        overlay.setPadding(0, systemTop, 0, systemBottom)
        tabsSheet = overlay
        browserContent.visibility = View.INVISIBLE
        root.place(overlay, height = -1)
        if (Motion.enabled) {
            screen.alpha = 0f
            screen.animate().alpha(1f).setDuration(160).start()
        }
    }

    private fun closeTabsSheet() {
        tabsSheet?.let {
            root.removeView(it)
            tabsSheet = null
            bookmarkList = null
            browserContent.visibility = View.VISIBLE
        }
    }

    private fun returnToApp() {
        finish()
    }

    private fun offerDownload(url: String, agent: String, page: String, filename: String = "", size: Long = -1) {
        if (!httpsUrl(url)) {
            Toast.makeText(this, tr("This download requires a direct HTTPS link."), Toast.LENGTH_SHORT).show()
            return
        }
        val result = Intent().putExtra("url", url)
            .putExtra("agent", agent).putExtra("page", page).putExtra("filename", filename).putExtra("size", size)
        setResult(RESULT_OK, result)
        finish()
    }

    private fun showKeyboard() {
        getSystemService(InputMethodManager::class.java).showSoftInput(address, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
        address.clearFocus()
        root.requestFocus()
    }

    @Deprecated("Android platform back handler")
    override fun onBackPressed() {
        when {
            tabsSheet != null -> closeTabsSheet()
            active?.canGoBack() == true -> active?.goBack()
            tabs.size > 1 -> closeTab(selected)
            else -> returnToApp()
        }
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(d.resource("page_close_enter", "anim"), d.resource("page_close_exit", "anim"))
        }
    }

    override fun onPause() {
        updates.pause()
        refresh.finish()
        if (!clearingData && tabs.isNotEmpty()) persistTabs()
        if (tabs.isNotEmpty()) active?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        updates.resume()
        if (tabs.isNotEmpty()) active?.onResume()
    }

    override fun onDestroy() {
        updates.close()
        menu?.dismiss()
        clearDialog?.dismiss()
        viewport.removeAllViews()
        (tabs.filterNotNull() + popups.keys).distinct().forEach(WebView::destroy)
        tabs.clear()
        popups.clear()
        super.onDestroy()
    }

    @Deprecated("Android platform activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        updates.installPermissionResult(requestCode)
    }

    companion object {
        private const val MAX_TABS = 12
        private const val SEARCH_ENGINE = "search_engine"
        private var retainedTabs: Bundle? = null
    }
}
