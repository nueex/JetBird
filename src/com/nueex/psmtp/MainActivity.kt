/* Copyright (C) 2026 nueex. SPDX-License-Identifier: GPL-3.0-or-later */
package com.nueex.psmtp

import android.app.Activity
import android.app.PendingIntent
import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Toast

class MainActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(base))

    private companion object {
        const val USB_PERMISSION = "com.nueex.psmtp.USB_PERMISSION"
        const val PROJECT = "https://github.com/nueex/jetbird"
        const val BROWSER = 7
        const val SCAN_CONSOLE = 9
        const val PICK_DOWNLOAD_FOLDER = 10
    }

    private val prefs by lazy { getSharedPreferences("appearance", MODE_PRIVATE) }
    private val store by lazy { TransferStore(this) }
    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private lateinit var d: Design
    private val updates by lazy { UpdateUi(this, { d }) { currentSheet == null } }
    private val downloadFolder get() = prefs.getString("download_folder", "").orEmpty()
    private val defaultParts get() = prefs.getInt("download_parts", 1).takeIf { it in DOWNLOAD_PART_CHOICES } ?: 1
    private val lightHeader by lazy { d.headerArtwork(dark = false) }
    private val darkHeader by lazy { d.headerArtwork(dark = true) }
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var page: FrameLayout
    private lateinit var downloads: LinearLayout
    private var currentSheet: BottomSheet? = null
    private var popup: PopupWindow? = null
    private var settings = false
    private var filter = 0
    private var query = ""
    private var topInset = 0
    private var navigationInset = 0
    private var bottomInset = 0
    private var connecting = false
    private var manualPair: ManualPair? = null
    private var batteryWarning: View? = null
    private var batteryWarningDismissed = false
    private var wirelessSetupNeeded = false
    private var pendingPermission: (() -> Unit)? = null
    private var usbTimeout: Runnable? = null
    private var shownDraft: Transfer? = null
    private val transferMode get() = prefs.getString("transfer_mode", null) ?: when {
        prefs.getBoolean("wireless_selected", false) -> "wireless"
        prefs.contains("wireless_selected") -> "usb"
        else -> "none"
    }
    private val wirelessSelected get() = transferMode == "wireless"
    private fun newDraft(url: String = "") = Transfer(url = url, name = "", keep = false, install = false,
        phoneOnly = transferMode == "none", console = consoleAddress, parts = defaultParts)
    private val consoleAddress get() = if (wirelessSelected) prefs.getString("last_console", "").orEmpty() else ""
    private val appName get() = applicationInfo.loadLabel(packageManager).toString()
    private val theme get() = prefs.getString("theme", "System") ?: "System"

    private enum class SheetStyle(val radius: Int, val gripWidth: Int, val gripHeight: Int, val gripTop: Int,
                                  val titleSize: Int, val dim: Int, val closeLabel: String = "Cancel",
                                  val closeWidth: Int = 93) {
        DOWNLOAD(34, 46, 28, 12, 27, 0x85),
        WIRELESS(25, 54, 36, 14, 28, 0x93),
        ABOUT(23, 59, 29, 14, 25, 0x94, "Close", 76),
        ERROR(28, 46, 28, 12, 27, 0x85, "Done", 76)
    }

    private class BottomSheet(val overlay: FrameLayout, val panel: ScrollView, val scrim: View) {
        var closing = false
        val travel get() = (overlay.height - panel.top).toFloat()

        fun moveTo(distance: Float) {
            val limit = travel.coerceAtLeast(1f)
            panel.translationY = distance.coerceIn(0f, limit)
            scrim.alpha = 1f - panel.translationY / limit
        }

        fun settle() {
            if (!Motion.enabled) {
                moveTo(0f)
                return
            }
            panel.animate().translationY(0f).setDuration(320).setInterpolator(Motion.settle).start()
            scrim.animate().alpha(1f).setDuration(230).setInterpolator(Motion.settle).start()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TransferService.CHANGED -> if (!settings) renderDownloads()
                USB_PERMISSION -> finishPermission(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> if (pendingPermission != null) finishPermission(false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = savedInstanceState?.getBoolean("settings") ?: false
        filter = savedInstanceState?.getInt("filter") ?: 0
        query = savedInstanceState?.getString("search").orEmpty()
        batteryWarningDismissed = savedInstanceState?.getBoolean("battery_notice_dismissed") == true
        if (!TransferService.alive) store.recover()
        val privateActions = IntentFilter().apply {
            addAction(TransferService.CHANGED)
            addAction(USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, privateActions, RECEIVER_NOT_EXPORTED)
            registerReceiver(receiver, IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED), RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, privateActions)
            registerReceiver(receiver, IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED))
        }
        render()
        if (settings) reconnectWireless()
        val restored = savedInstanceState?.getString("draft")
        if (restored != null) {
            runCatching { Transfer.read(org.json.JSONObject(restored)) }.getOrNull()?.let(::showDownload)
        } else handleSharedLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedLink(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryWarning()
        if (!updates.resume()) maybeCheckUpdates()
    }

    private fun maybeCheckUpdates() {
        if (!automaticUpdateAttempted && !isFinishing && !isDestroyed &&
            currentSheet == null && updates.automatic) {
            automaticUpdateAttempted = true
            updates.check(manual = false)
        }
    }

    private var automaticUpdateAttempted = false

    override fun onPause() {
        updates.pause()
        super.onPause()
    }

    private fun refreshBatteryWarning() {
        val warning = batteryWarning ?: return
        val restricted = !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        warning.visibility = if (!batteryWarningDismissed && restricted) View.VISIBLE else View.GONE
    }

    private fun batteryWarningCard() = with(d) {
        frame().apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xffbd2627.toInt(), 0xffa6151c.toInt(), 0xffb62024.toInt())).apply {
                cornerRadius = u(14).toFloat()
            }
            accessibilityPaneTitle = tr("Disable Battery Optimization")
            val message = column().apply {
                add(text(tr("Disable Battery Optimization"), 17, 650, Color.WHITE).apply {
                    maxLines = 1
                    setAutoSizeTextTypeUniformWithConfiguration(9, 15, 1, TypedValue.COMPLEX_UNIT_SP)
                }, height = 25)
                space(5)
                add(text(tr("Helps JetBird keep downloads and transfers running in the background when the screen is off."),
                    16, color = 0xffffe3e1.toInt()).apply { setLineSpacing(u(3).toFloat(), 1f) })
            }
            place(row().apply {
                padding(14, 14, 45, 14)
                add(icon("battery-warning", 36, Color.WHITE), 37, 47)
                add(View(context), 10, 1)
                add(message, 0, -2, 1f)
                tap(radius = 14) { showBatterySettings() }
            })
            place(iconButton("close", 22, tr("Dismiss battery warning"), 0xffffb6b3.toInt()) {
                batteryWarningDismissed = true
                refreshBatteryWarning()
            }, 48, 48, Gravity.TOP or Gravity.END)
        }
    }

    private fun showBatterySettings() {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null))
        if (runCatching { startActivity(request) }.isFailure) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { toast(tr("Open Battery settings for {0} to allow background transfers.", appName)) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("settings", settings)
        outState.putInt("filter", filter)
        outState.putString("search", query)
        outState.putBoolean("battery_notice_dismissed", batteryWarningDismissed)
        shownDraft?.let { outState.putString("draft", it.json().toString()) }
    }

    override fun onDestroy() {
        updates.close()
        manualPair?.close()
        popup?.dismiss()
        closeSheet(animated = false)
        usbTimeout?.let(root::removeCallbacks)
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Android platform back handler")
    override fun onBackPressed() {
        when {
            currentSheet != null -> closeSheet()
            settings -> showSettings(false)
            query.isNotEmpty() -> { query = ""; render() }
            else -> super.onBackPressed()
        }
    }

    private fun showSettings(show: Boolean) {
        if (settings == show) {
            if (show) reconnectWireless()
            return
        }
        settings = show
        hideKeyboard()
        render(navigating = true)
        if (show) reconnectWireless()
    }

    private fun render(navigating: Boolean = false) {
        popup?.dismiss()
        popup = null
        batteryWarning = null
        d = Design(this, Design.isDark(this, theme))
        with(d) {
            if (!::root.isInitialized) {
                root = frame().apply { isFocusableInTouchMode = true }
                setContentView(root)
            }
            root.setBackgroundColor(background)
            val previous = if (navigating && Motion.enabled && ::page.isInitialized) page else null
            previous?.let { (it.parent as FrameLayout).removeView(it) }
            if (::content.isInitialized) root.removeView(content)
            content = frame().apply {
                clipChildren = false
                clipToPadding = false
                if (currentSheet != null) importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            root.addView(content, 0, FrameLayout.LayoutParams(-1, -1))
            val pages = frame()
            content.place(pages, height = -1)
            page = frame()
            pages.place(page, height = -1)
            val scroll = ScrollView(this@MainActivity).apply {
                isFillViewport = false
                isVerticalScrollBarEnabled = false
                clipToPadding = true
                padding(0, 0, 0, if (settings) 4 else 16)
            }
            val body = column()
            scroll.addView(body)
            page.place(scroll, height = -1, bottom = if (settings) 85 else 178)
            if (settings) settingsBody(body) else homeBody(body)
            content.place(navigation(), height = 77, gravity = Gravity.BOTTOM, left = 20, right = 20,
                bottom = if (settings) 2 else 7)
            if (!settings) {
                val add = iconButton("plus", 26, tr("New Download"), onButton) {
                    showDownload(newDraft())
                }.apply { background = round(chip, 36); elevation = u(7).toFloat() }
                page.place(add, 70, 70, Gravity.BOTTOM or Gravity.END, right = 20, bottom = 103)
            }
            if (previous != null) {
                Motion.switchPage(pages, previous, page, u(if (settings != rtl) 26 else -26).toFloat())
            }
            content.setPadding(0, topInset, 0, bottomInset)
            bars(this@MainActivity, root) { insets ->
                topInset = Design.topInset(insets)
                bottomInset = Design.bottomInset(insets)
                navigationInset = if (Build.VERSION.SDK_INT >= 30) {
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                } else bottomInset
                content.setPadding(0, topInset, 0, bottomInset)
                positionSheet()
            }
            if (currentSheet == null) root.requestFocus()
        }
    }

    private fun header(body: LinearLayout, settingsHeader: Boolean) = with(d) {
        body.space(if (settingsHeader) 62 else 54)
        val header = frame()
        val mark = ImageView(this@MainActivity).apply {
            setImageBitmap(if (dark) darkHeader else lightHeader)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "JetBird by nueex"
        }
        header.place(mark, 240, 80, left = 19, right = 58)
        header.place(iconButton("more", 24, tr("More options"), action = ::showAppMenu), 46, 46,
            Gravity.CENTER_VERTICAL or Gravity.END, right = 11)
        body.add(header, height = 80)
    }

    private fun navigation() = with(d) {
        row().apply {
            background = round(elevated, 20)
            elevation = u(6).toFloat()
            fun item(label: String, glyph: String, selected: Boolean, next: Boolean) = frame().apply {
                val labelColor = if (selected) ink else subtle
                place(icon(if (selected) "$glyph-on" else glyph, 28, if (selected) ink else muted),
                    30, 30, Gravity.TOP or Gravity.CENTER_HORIZONTAL, top = 12)
                if (selected) place(View(this@MainActivity).apply { background = round(ink, 8) },
                    5, 5, Gravity.TOP or Gravity.CENTER_HORIZONTAL, top = 43)
                place(text(label, 15, if (selected) 650 else 400, labelColor).apply { gravity = Gravity.CENTER },
                    -1, 21, Gravity.TOP, top = 48)
                isSelected = selected
                tap(label, 19) { showSettings(next) }
            }
            add(item(tr("Home"), "home", !settings, false), 0, -1, 1f)
            add(item(tr("Settings"), "gear", settings, true), 0, -1, 1f)
        }
    }

    private fun homeBody(body: LinearLayout) = with(d) {
        header(body, false)
        val sections = column().apply { padding(20, 14, 20, 0) }
        body.add(sections)
        val warning = column().apply {
            add(batteryWarningCard())
            space(12)
        }
        batteryWarning = warning
        sections.add(warning)
        refreshBatteryWarning()
        val searchBar = row().apply { background = round(d.search, 16); padding(12, 0, 8, 0) }
        searchBar.add(icon("search", 23, muted), 32, 42)
        val searchField = edit(tr("Search files"), query, 20).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId != EditorInfo.IME_ACTION_SEARCH) false else { hideKeyboard(); clearFocus(); true }
            }
        }
        searchBar.add(searchField, 0, 43, 1f)
        val clear = iconButton("close", 17, tr("Clear search"), muted) { searchField.text.clear() }
        clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
        searchBar.add(clear, 38, 43)
        sections.add(searchBar, height = 43)
        sections.space(20)
        val segments = row().apply { background = round(Color.TRANSPARENT, 24, line) }
        val tabs = listOf(tr("All"), tr("Active"), tr("Completed")).mapIndexed { index, name ->
            text(name, 18).oneLine().apply {
                gravity = Gravity.CENTER
                tap(radius = 24) {
                    filter = index
                    markFilters(segments)
                    renderDownloads()
                }
            }
        }
        tabs.forEachIndexed { index, item -> segments.add(item, 0, -1, if (index == 0) .97f else 1.03f) }
        markFilters(segments)
        sections.add(segments, height = 42)
        sections.space(26)
        downloads = column()
        sections.add(downloads)
        searchField.onChanged { value ->
            query = value
            clear.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
            renderDownloads()
        }
        renderDownloads()
    }

    private fun markFilters(layout: LinearLayout) = with(d) {
        for (i in 0 until layout.childCount) (layout.getChildAt(i) as android.widget.TextView).apply {
            isSelected = i == filter
            setTextColor(if (isSelected) onButton else ink)
            background = if (isSelected) round(chip, 24) else null
        }
    }

    private fun renderDownloads(): Unit = with(d) {
        downloads.removeAllViews()
        val list = store.all().filter { it.name.contains(query.trim(), ignoreCase = true) }
        val queued = list.filter { it.status == TransferStatus.SAVED }.takeIf { filter != 2 }.orEmpty()
        val active = list.filter { it.status != TransferStatus.COMPLETE && it.status != TransferStatus.SAVED }
            .takeIf { filter != 2 }.orEmpty()
        val completed = list.filter { it.status == TransferStatus.COMPLETE }.takeIf { filter != 1 }.orEmpty()
        if (active.isNotEmpty()) {
            downloads.add(text(tr("In Progress"), 25, 600), height = 32)
            downloads.space(12)
            downloads.add(column().apply {
                background = round(card, 17)
                active.forEach { add(activeRow(it), height = 99) }
            })
        }
        if (queued.isNotEmpty()) {
            if (active.isNotEmpty()) downloads.space(26)
            downloads.add(text(tr("Queue"), 25, 600), height = 32)
            downloads.space(12)
            downloads.add(column().apply {
                background = round(card, 17)
                queued.forEach { add(activeRow(it), height = 99) }
            })
        }
        if (completed.isNotEmpty()) {
            if (active.isNotEmpty() || queued.isNotEmpty()) downloads.space(32)
            downloads.add(text(tr("Completed"), 25, 600), height = 32)
            downloads.space(13)
            downloads.add(column().apply {
                background = round(card, 17)
                padding(0, 1, 0, 3)
                completed.forEach { add(completedRow(it), height = 75) }
            })
        }
        if (active.isEmpty() && queued.isEmpty() && completed.isEmpty()) {
            val title = when {
                query.isNotBlank() -> tr("No matching files")
                filter == 1 -> tr("No active downloads")
                filter == 2 -> tr("No completed downloads")
                else -> tr("No downloads yet")
            }
            downloads.add(text(title, 20, 500, subtle).apply { gravity = Gravity.CENTER }, height = 103)
        }
    }

    private fun activeRow(job: Transfer): LinearLayout = with(d) {
        val running = job.status == TransferStatus.ACTIVE
        val pausing = job.status == TransferStatus.PAUSING
        val stopping = job.status == TransferStatus.STOPPING
        val stopped = job.status == TransferStatus.STOPPED
        val shell = row().apply { padding(18, 0, 14, 0) }
        val info = column().apply { gravity = Gravity.CENTER_VERTICAL }
        val label = when {
            job.done > 0 && job.total > 0 -> tr("{0} of {1}", displayBytes(job.done), displayBytes(job.total))
            job.done > 0 -> displayBytes(job.done)
            else -> ""
        }
        val state = when (job.status) {
            TransferStatus.SAVED -> tr("Ready to start")
            TransferStatus.PAUSED -> if (job.checkpoint || job.done == 0L && job.message == "Interrupted") tr("Paused")
                else job.message.ifBlank { tr("Paused") }
            TransferStatus.PAUSING -> tr("Pausing…")
            TransferStatus.STOPPING -> tr("Stopping…")
            TransferStatus.STOPPED -> tr("Stopped · Cleanup unconfirmed")
            TransferStatus.QUEUED -> tr("Queued")
            TransferStatus.FAILED -> job.message.ifBlank { tr("Transfer failed") }
            else -> job.message.ifBlank { if (label.isBlank()) tr("Opening download…") else "" }
        }
        val rate = if (running && job.speed > 0) tr("{0}/s", displayBytes(job.speed)) else ""
        val details = listOf(transferMessage(state), rate, label).filter(String::isNotBlank).joinToString(" • ")
        info.add(text(job.name, 19, 700).oneLine(), height = 26)
        info.add(row().apply {
            add(text(details, 16).oneLine(), 0, 25, 1f)
            if (running && job.total > 0) add(text(displayPercent(job.percent), 16), -2, 25)
        })
        info.space(5)
        val segments = job.partsProgress?.percentages.orEmpty().ifEmpty { listOf(job.percent) }
        info.add(row().apply {
            if (segments.size > 1) contentDescription = segments.joinToString(" · ") { displayPercent(it) }
            segments.forEachIndexed { index, percent ->
                if (index > 0) add(View(this@MainActivity), 3, 1)
                add(frame().apply {
                    background = round(line, 10)
                    val fill = View(this@MainActivity).apply {
                        background = round(if (running) ink else tone(0xababac, 0x77777d), 10)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    place(fill, 0, -1)
                    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        val target = width * percent / 100
                        if (fill.layoutParams.width != target) fill.layoutParams = fill.layoutParams.apply { width = target }
                    }
                }, 0, 10, 1f)
            }
        }, height = 10)
        info.space(7)
        shell.add(info, 0, -1, 1f)
        shell.add(View(this@MainActivity), 8, 1)
        val queued = job.status == TransferStatus.QUEUED
        val action = if (stopped) "more" else if (running || pausing || stopping || queued) "pause" else "play"
        val actionLabel = when (action) {
            "more" -> tr("Options for {0}", job.name)
            "pause" -> tr("Pause {0}", job.name)
            else -> if (job.status == TransferStatus.SAVED) tr("Start {0}", job.name) else tr("Resume {0}", job.name)
        }
        val button = iconButton(action, 16, actionLabel, onButton) {
            when (action) {
                "more" -> showJobMenu(it, job)
                "pause" -> TransferService.command(this@MainActivity, TransferService.PAUSE, job.id)
                else -> resume(job)
            }
        }.apply { background = round(chip, 22); isEnabled = !pausing && !stopping && job.message != "Installing on PS4…" }
        shell.add(frame().apply { place(button, 40, 40, Gravity.CENTER) }, 44, 58)
        val anchor = if (running || pausing || stopping) {
            val stop = iconButton("stop", 17, tr("Stop {0}", job.name), tone(0xa12624, 0xffa6a0)) {
                TransferService.command(this@MainActivity, TransferService.CANCEL, job.id)
            }.apply { background = round(tone(0xfae5e4, 0x463030), 22); isEnabled = !stopping }
            shell.add(frame().apply { place(stop, 40, 40, Gravity.CENTER) }, 44, 58)
            stop
        } else button
        if (job.status == TransferStatus.FAILED || stopped) shell.tap { showJobMenu(anchor, job) }
        if (!stopping) shell.setOnLongClickListener { showJobMenu(anchor, job); true }
        shell
    }

    private fun completedRow(job: Transfer) = with(d) {
        row().apply {
            padding(18, 0, 6, 0)
            val options = iconButton("more", 24, tr("Options for {0}", job.name)) { showJobMenu(it, job) }
            val labels = column().apply {
                gravity = Gravity.CENTER_VERTICAL
                add(text(job.name, 19, 700).oneLine(), height = 26)
                add(text(if (job.direct) tr("Sent to PS4") else displayBytes(job.total.takeIf { it >= 0 } ?: job.done), 16).oneLine(), height = 23)
                if (job.direct) tap { toast(transferMessage(job.message)) }
                else tap(tr("Open {0}", job.name)) { openCopy(job) }
                setOnLongClickListener { showJobMenu(options, job); true }
            }
            setOnLongClickListener { showJobMenu(options, job); true }
            add(labels, 0, -1, 1f)
            add(options, 44, 56)
        }
    }

    private fun selectMode(mode: String) {
        prefs.edit().putString("transfer_mode", mode).apply()
        WirelessPair.active = null
        wirelessSetupNeeded = false
        render()
    }

    private fun settingsBody(body: LinearLayout) = with(d) {
        header(body, true)
        val sections = column().apply { padding(20, 14, 20, 0) }
        body.add(sections)
        sections.add(text(tr("Settings"), 36, 700).apply { letterSpacing = .036f }, height = 48)
        sections.space(27)
        sections.add(text(tr("Connection"), 24, 650), height = 31)
        sections.space(10)
        val modeGap = View(this@MainActivity)
        val mode = settingRow(tr("Transfer mode"), when {
            connecting -> tr("Connecting to PS4…")
            wirelessSelected -> tr("Wireless Transfer")
            transferMode == "usb" -> tr("USB Transfer")
            else -> tr("None")
        }, if (connecting) "spinner" else "down", 60) { anchor ->
            settingsMenu(anchor, modeGap, 25, listOf(
                MenuOption(tr("None"), checked = transferMode == "none") { selectMode("none") },
                MenuOption(tr("USB Transfer"), checked = transferMode == "usb") { selectMode("usb") },
                MenuOption(tr("Wireless Transfer"), checked = wirelessSelected) { showWireless() }
            ))
        }
        mode.isEnabled = !connecting
        sections.add(mode)
        if (wirelessSetupNeeded && wirelessSelected) {
            sections.add(text(tr("Couldn’t reconnect to the PS4. Set up Wireless Transfer again."), 15, 550,
                tone(0xa62e12, 0xffad91)).apply {
                padding(8, 10, 8, 6)
                tap(radius = 10) { showWireless() }
            })
        }
        sections.add(modeGap, 1, 25)
        sections.add(text(tr("Appearance"), 25, 650), height = 32)
        sections.space(10)
        val themeGap = View(this@MainActivity)
        sections.add(settingRow(tr("Theme"), tr(theme), "down") { anchor -> chooseTheme(anchor, themeGap) })
        sections.space(8)
        val language = AppLanguage.selected(this@MainActivity)
        val languageGap = View(this@MainActivity)
        sections.add(settingRow(tr("App Language"), language.label, "down") { anchor ->
            settingsMenu(anchor, languageGap, 0, AppLanguage.entries.map { option ->
                MenuOption(option.label, checked = option == language, fontName = option.font) {
                    if (option != language) {
                        option.save(this@MainActivity)
                        recreate()
                    }
                }
            }, extraSpace = -140)
        })
        sections.add(languageGap, 1, 0)
        sections.add(themeGap, 1, 22)
        sections.add(text(tr("Downloads"), 25, 650), height = 32)
        sections.space(10)
        val folderGap = View(this@MainActivity)
        sections.add(settingRow(tr("Download folder"), downloadFolderPath(), "down", wrapDetail = true) { anchor ->
            settingsMenu(anchor, folderGap, 8, listOf(
                MenuOption(tr("Default JetBird folder"), checked = downloadFolder.isBlank()) {
                    prefs.edit().remove("download_folder").remove("download_folder_name").apply()
                    render()
                },
                MenuOption(tr("Choose custom folder")) {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        .putExtra(Intent.EXTRA_LOCAL_ONLY, true), PICK_DOWNLOAD_FOLDER)
                }
            ), extraSpace = 24)
        })
        sections.add(folderGap, 1, 8)
        sections.add(downloadPartsSetting())
        sections.space(18)
        sections.add(text(tr("App"), 25, 650), height = 32)
        sections.space(10)
        sections.add(column().apply {
            background = round(card, 16)
            add(column().apply {
                padding(18, 0, 15, 0)
                val automatic = toggleRow(this, tr("Automatic Check for Updates"), updates.automatic, wrap = true)
                automatic.setOnCheckedChangeListener { _, enabled -> updates.automatic = enabled }
            })
            add(frame().apply { place(divider(), -1, 1, left = 18, right = 16) }, height = 1)
            add(settingRow(tr("Check for Updates"), "") { checkUpdates() }, height = 55)
            add(frame().apply { place(divider(), -1, 1, left = 18, right = 16) }, height = 1)
            add(settingRow(tr("Guide"), "", "external") { openExternal(PROJECT) }, height = 55)
            add(frame().apply { place(divider(), -1, 1, left = 18, right = 16) }, height = 1)
            add(settingRow(tr("About nueex"), "") { about() }, height = 55)
        })
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty().removePrefix("v")
        if (version.isNotBlank()) {
            sections.space(5)
            sections.add(text("v$version", 15, color = muted).apply { gravity = Gravity.CENTER }, height = 24)
        }
    }

    private fun downloadFolderPath(): String {
        if (downloadFolder.isBlank()) return "/$DEFAULT_DOWNLOAD_PATH"
        val tree = Uri.parse(downloadFolder)
        val path = if (tree.authority == "com.android.externalstorage.documents") {
            runCatching { DocumentsContract.getTreeDocumentId(tree).substringAfter(':', "") }.getOrNull()
        } else null
        return "/" + (path?.takeIf(String::isNotBlank)
            ?: prefs.getString("download_folder_name", tr("Selected folder")).orEmpty()).trimStart('/')
    }

    private fun partsText(parts: Int): String {
        val quantity = android.icu.text.PluralRules.forLocale(AppLanguage.selected(this).locale).select(parts.toDouble())
        val template = when (quantity) {
            "one" -> "{0} part"
            "two", "few", "many" -> "{0} parts ($quantity)"
            else -> "{0} parts"
        }
        return tr(template, parts)
    }

    private fun downloadPartsSetting() = with(d) {
        column().apply {
            background = round(card, 16)
            padding(18, 13, 18, 6)
            val value = text(partsText(defaultParts), 17, 550, subtle)
            add(row().apply {
                add(text(tr("Download parts"), 19, 650), 0, -2, 1f)
                add(value, -2, -2)
            })
            add(SteppedSeekBar(d, DOWNLOAD_PART_CHOICES.lastIndex, DOWNLOAD_PART_CHOICES.indexOf(defaultParts)).apply {
                contentDescription = tr("Download parts")
                if (Build.VERSION.SDK_INT >= 30) stateDescription = value.text
                else contentDescription = tr("Download parts, {0}", value.text)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        val parts = DOWNLOAD_PART_CHOICES[progress]
                        val label = partsText(parts)
                        value.text = label
                        if (Build.VERSION.SDK_INT >= 30) bar.stateDescription = label
                        else bar.contentDescription = tr("Download parts, {0}", label)
                        animateTo(progress, isPressed)
                        if (fromUser) prefs.edit().putInt("download_parts", parts).apply()
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) = animateTo(bar.progress, pressed = true)
                    override fun onStopTrackingTouch(bar: SeekBar) = animateTo(bar.progress, pressed = false)
                })
            }, height = 56)
        }
    }

    private fun showAppMenu(anchor: View) = menu(anchor, 232, -6, -6, listOf(
        MenuOption(tr("Browser"), "globe") { openBrowser() },
        MenuOption(tr("Guide"), "external") { openExternal(PROJECT) },
        MenuOption(tr("Check for Updates"), "refresh") { checkUpdates() }
    ))

    private fun showJobMenu(anchor: View, job: Transfer) {
        val options = mutableListOf<MenuOption>()
        if (job.phoneCopy.isNotBlank()) {
            options += MenuOption(tr("Open phone copy")) { openCopy(job) }
            options += MenuOption(tr("Share phone copy")) { openCopy(job, share = true) }
            options += MenuOption(tr("Delete")) { confirmDeleteCopy(job) }
        }
        if (job.status == TransferStatus.FAILED || job.status == TransferStatus.STOPPED) {
            options += MenuOption(tr("View Error")) { showTransferError(job) }
        }
        if (job.status in setOf(TransferStatus.ACTIVE, TransferStatus.PAUSING)) {
            options += MenuOption(tr("Stop")) { TransferService.command(this, TransferService.CANCEL, job.id) }
        } else if (job.status == TransferStatus.QUEUED) {
            if (!job.checkpoint) options += MenuOption(tr("Remove from queue")) {
                TransferService.command(this, TransferService.CANCEL, job.id)
            }
        } else options += MenuOption(tr("Remove from history")) { store.remove(job.id); renderDownloads() }
        if (options.isNotEmpty()) menu(anchor, 242, -4, -9, options)
    }

    private fun showTransferError(job: Transfer) = with(d) {
        val stopped = job.status == TransferStatus.STOPPED
        val summary = job.message.replace(Regex("""\s*Check PS4 Notifications?\.?""", RegexOption.IGNORE_CASE), "")
            .trim().ifBlank { tr("The transfer ended before the receiver returned an error code.") }
        val body = sheet(if (stopped) tr("Stop details") else tr("Transfer failed"), SheetStyle.ERROR)
        body.space(12)
        body.add(column().apply {
            background = round(card, 18)
            padding(16, 16, 16, 18)
            add(row().apply {
                add(icon("warning", 22, tone(0xa12624, 0xffa6a0)), 26, 30)
                add(View(this@MainActivity), 10, 1)
                val operation = when {
                    job.direct -> tr("Direct PS4 Download")
                    job.install -> tr("Parallel Install")
                    else -> tr("File download")
                }
                add(text(operation, 16, 650, subtle), 0, -2, 1f)
            })
            space(8)
            add(text(job.name, 19, 700))
            space(10)
            add(text(transferMessage(summary), 16).apply {
                setTextIsSelectable(true)
                setLineSpacing(u(3).toFloat(), 1f)
            })
        })
        body.space(22)
    }

    private fun confirmDeleteCopy(job: Transfer) {
        d.dialog().setTitle(tr("Delete phone copy?"))
            .setMessage(tr("Remove {0} from your phone?", job.name))
            .setNegativeButton(tr("Cancel"), null)
            .setPositiveButton(tr("Delete")) { _, _ ->
                val resolver = contentResolver
                Thread({
                    val deleted = runCatching { deletePhoneCopy(resolver, Uri.parse(job.phoneCopy)) }
                    runOnUiThread {
                        if (deleted.isSuccess) {
                            store.all().firstOrNull { it.id == job.id && it.phoneCopy == job.phoneCopy }?.let {
                                val message = when {
                                    it.phoneOnly -> "Phone copy removed."
                                    it.install -> "Installed on PS4."
                                    else -> "Saved on PS4."
                                }
                                store.update(it.copy(phoneCopy = "", message = message), durable = true)
                            }
                            if (!isDestroyed) {
                                if (!settings) renderDownloads()
                                toast(if (deleted.getOrThrow()) tr("Phone copy deleted.") else tr("The phone copy was already gone."))
                            }
                        } else if (!isDestroyed) toast(tr("Android couldn’t delete the phone copy."))
                    }
                }, "Delete phone copy").start()
            }.show()
    }

    private fun menu(anchor: View, width: Int, y: Int, x: Int, options: List<MenuOption>, onClose: () -> Unit = {}) {
        popup?.dismiss()
        popup = d.showMenu(anchor, width, y, x, options, onClose)
    }

    private fun settingsMenu(anchor: View, gap: View, restHeight: Int, options: List<MenuOption>, extraSpace: Int = 0) {
        popup?.dismiss()
        popup = d.showSettingsMenu(anchor, gap, restHeight, options, extraSpace)
    }

    private fun chooseTheme(anchor: View, gap: View) = settingsMenu(anchor, gap, 22, listOf("System", "Light", "Dark").map { option ->
        MenuOption(tr(option), checked = option == theme) {
            if (option != theme) {
                prefs.edit().putString("theme", option).apply()
                render()
            }
        }
    })

    private fun checkUpdates() = updates.check(manual = true)

    private fun about() = with(d) {
        val links = sheet(tr("About nueex"), SheetStyle.ABOUT)
        links.space(8)
        aboutLink(links, "GitHub", "about_github", "https://github.com/nueex")
        links.add(aboutDivider(), height = 1)
        aboutLink(links, "YouTube", "about_youtube", "https://www.youtube.com/@nueex")
        links.add(aboutDivider(), height = 1)
        aboutLink(links, "X", "about_x", "https://x.com/nueex_")
        links.space(8)
    }

    private fun Design.aboutDivider() = View(context).apply {
        setBackgroundColor(tone(0xd3d3d5, 0x48484c))
    }

    private fun Design.aboutLink(links: LinearLayout, label: String, logo: String, url: String) {
        links.add(row().apply {
            add(ImageView(context).apply {
                setImageResource(resource(logo, "drawable"))
                setColorFilter(ink)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, 36, 36)
            add(View(context), 20, 1)
            add(text(label, 20, 700), 0, -2, 1f)
            add(icon("external", 20), 28, 65)
            tap(label) {
                closeSheet()
                openExternal(url)
            }
        }, height = 65)
    }

    private fun openExternal(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast(tr("No browser is available.")) }
    }

    private fun openCopy(job: Transfer, share: Boolean = false) {
        if (job.phoneCopy.isBlank()) {
            toast(transferMessage(job.message).ifBlank { tr("This file is saved on the PS4.") })
            return
        }
        val uri = Uri.parse(job.phoneCopy)
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull() ?: "application/octet-stream"
        val intent = if (share) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(if (share) Intent.createChooser(intent, tr("Share file")) else intent) }
            .onFailure { toast(tr("No app could open this file.")) }
    }

    private fun openBrowser() {
        hideKeyboard()
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, BrowserActivity::class.java).putExtra("theme", theme), BROWSER)
        if (Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(d.resource("page_open_enter", "anim"), d.resource("page_open_exit", "anim"))
        }
    }

    @Deprecated("Android platform activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (updates.installPermissionResult(requestCode)) return
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            BROWSER -> openBrowserDownload(data)
            PICK_DOWNLOAD_FOLDER -> data.data?.let { folder ->
                val selected = runCatching {
                    val access = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(folder, access)
                    val document = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
                    val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_FLAGS)
                    contentResolver.query(document, columns, null, null, null)?.use { row ->
                        if (row.moveToFirst() && row.getInt(1) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0)
                            row.getString(0) else null
                    }
                }.getOrNull()
                if (selected == null) toast(tr("Choose a writable download folder."))
                else {
                    prefs.edit().putString("download_folder", folder.toString()).putString("download_folder_name", selected).apply()
                    render()
                }
            }
            SCAN_CONSOLE -> data.getStringExtra("pairing")?.let { value ->
                WirelessPair.fromQr(value, ::localAddress)?.let(::pairFromQr)
            }
        }
    }

    private fun openBrowserDownload(data: Intent) {
        val url = data.getStringExtra("url") ?: return
        if (!httpsUrl(url)) return
        val filename = data.getStringExtra("filename")?.takeIf(::validFilename).orEmpty()
        showSettings(false)
        showDownload(newDraft(url).copy(name = filename, browserFilename = filename,
            total = data.getLongExtra("size", -1), browserAgent = data.getStringExtra("agent").orEmpty(),
            browserPage = data.getStringExtra("page").orEmpty()))
    }

    private fun handleSharedLink(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val link = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty().trim()
        if (link.isEmpty()) return
        showSettings(false)
        showDownload(newDraft(link))
    }

    private fun showDownload(draft: Transfer): Unit = with(d) {
        val form = sheet(tr("New Download"), SheetStyle.DOWNLOAD, showClose = !draft.phoneOnly)
        shownDraft = draft
        val first = row()
        first.add(text(tr("File URL"), 20), 0, 31, 1f)
        val url = sheetField("https://", draft.url, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            .apply { textDirection = View.TEXT_DIRECTION_LTR }
        val name = sheetField(tr("File name"), draft.name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        var nameEdited = draft.explicitName
        var settingSuggestedName = false
        fun suggestName(value: String) {
            if (nameEdited || name.text.toString() == value) return
            settingSuggestedName = true
            name.setText(value)
            settingSuggestedName = false
        }
        if (!draft.phoneOnly) first.add(text(tr("Paste Link"), 18).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            tap(radius = 8) {
                val clipboard = getSystemService(ClipboardManager::class.java).primaryClip
                if (clipboard != null && clipboard.itemCount > 0) {
                    url.setText(clipboard.getItemAt(0).coerceToText(this@MainActivity).toString().trim())
                    url.setSelection(url.length())
                } else toast(tr("The clipboard is empty."))
            }
        }, -2, 31)
        form.add(first)
        form.space(2)
        form.add(url, height = 48)
        form.space(13)
        form.add(text(tr("File name"), 20), height = 31)
        form.space(2)
        form.add(name, height = 47)
        form.space(10)
        form.add(divider(), height = 1)
        val wireless = draft.wireless
        val mode = if (wireless) tr("Wireless") else "USB"
        val storageLabel = text(tr("Checking PS4 storage…"), 15).apply {
            gravity = Gravity.CENTER
            minHeight = u(35)
            padding(1, 3, 1, 3)
        }
        form.add(storageLabel)
        val activeForm = currentSheet
        var checkingStorage = false
        fun refreshStorage() {
            if (draft.phoneOnly) {
                storageLabel.text = runCatching {
                    val volume = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path)
                    tr("Phone storage · {0} free / {1} total", displayBytes(volume.availableBytes), displayBytes(volume.totalBytes))
                }.getOrDefault(tr("Phone storage unavailable"))
                return
            }
            if (checkingStorage) return
            checkingStorage = true
            storageLabel.text = tr("Checking PS4 storage…")
            Thread({
                val size = runCatching {
                    if (TransferService.alive) null else if (wireless) WirelessPair.active?.storage() else usbAccessory(usb)?.takeIf(usb::hasPermission)?.let {
                        UsbDestination(usb, it, false).storage()
                    }
                }.getOrNull()
                runOnUiThread {
                    checkingStorage = false
                    if (currentSheet === activeForm && activeForm?.closing == false) {
                            val storage = if (size == null) tr("PS4 storage unavailable. Tap to retry.")
                                else tr("PS4 {0} free / {1} total", displayBytes(size.available), displayBytes(size.total))
                        storageLabel.text = "$storage · $mode"
                    }
                }
            }, "Read PS4 storage").start()
        }
        if (!draft.phoneOnly) storageLabel.tap(radius = 10) {
            if (wireless && WirelessPair.active == null) reconnectWireless(::refreshStorage)
            else authorize(!wireless) { refreshStorage() }
        }
        refreshStorage()
        form.add(divider(), height = 1)
        val options = column()
        if (!draft.phoneOnly) form.add(options)
        val keep = toggleRow(options, tr("Save File on Phone"), draft.keep && !draft.direct)
        options.add(divider(), height = 1)
        val install = toggleRow(options, tr("Parallel Install"), draft.install)
        options.add(divider(), height = 1)
        val advanced = column().apply { visibility = if (draft.direct) View.VISIBLE else View.GONE }
        val disclosure = text(if (draft.direct) tr("Advanced") + " ▴" else tr("Advanced") + " ▾", 18, 550).apply {
            gravity = Gravity.CENTER_VERTICAL
            tap(radius = 8) {
                val expand = advanced.visibility != View.VISIBLE
                advanced.visibility = if (expand) View.VISIBLE else View.GONE
                text = if (expand) tr("Advanced") + " ▴" else tr("Advanced") + " ▾"
            }
        }
        options.add(disclosure, height = 48)
        options.add(advanced)
        val direct = toggleRow(advanced, tr("Direct PS4 Download"), draft.direct)
        fun updateKeep() {
            if (direct.isChecked) keep.isChecked = false
            keep.isEnabled = !direct.isChecked
            (keep.parent as View).alpha = if (keep.isEnabled) 1f else .5f
        }
        updateKeep()
        fun reify(): Transfer {
            val link = url.text.toString().trim()
            val filename = name.text.toString().trim()
            return draft.copy(url = link, name = filename, explicitName = nameEdited && filename.isNotEmpty(),
                keep = draft.phoneOnly || keep.isChecked, install = !draft.phoneOnly && install.isChecked,
                direct = !draft.phoneOnly && direct.isChecked,
                browserFilename = draft.browserFilename.takeIf { link == draft.url }.orEmpty(),
                total = if (link == draft.url) draft.total else -1,
                browserAgent = draft.browserAgent.takeIf { link == draft.url }.orEmpty(),
                browserPage = draft.browserPage.takeIf { link == draft.url }.orEmpty())
        }
        keep.setOnCheckedChangeListener { _, _ -> shownDraft = reify() }
        install.setOnCheckedChangeListener { _, _ -> shownDraft = reify() }
        form.space(10)
        val status = text("", 15, color = subtle).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        form.add(status)
        form.space(11)
        lateinit var queueButton: View
        lateinit var startButton: View
        var generation = 0
        var metadata: DownloadMetadata? = null
        fun invalidateDetails() {
            generation++
            metadata = null
            status.visibility = View.GONE
            queueButton.isEnabled = true
            startButton.isEnabled = true
            shownDraft = reify()
        }
        url.onChanged {
            url.error = null
            suggestName("")
            invalidateDetails()
        }
        name.onChanged {
            if (!settingSuggestedName) {
                nameEdited = true
                name.error = null
                invalidateDetails()
            }
        }
        direct.setOnCheckedChangeListener { _, _ -> updateKeep(); invalidateDetails() }
        fun save(start: Boolean, file: DownloadMetadata) {
            val job = reify()
            val resolved = job.copy(name = if (job.explicitName) job.name else file.name,
                phoneFolder = downloadFolder.takeIf { job.keep }.orEmpty(),
                total = if (job.checkpoint) job.total else file.size,
                status = if (start) TransferStatus.QUEUED else TransferStatus.SAVED)
            val enqueue: () -> Unit = enqueue@{
                if (currentSheet !== activeForm || activeForm?.closing != false || reify() != job) return@enqueue
                store.save(store.all().filterNot { it.id == resolved.id } + resolved, durable = true)
                filter = 0
                query = ""
                closeSheet()
                showSettings(false)
                render()
                if (start) startQueue()
            }
            if (start) authorizeTransfer(resolved, enqueue) else enqueue()
        }
        fun fetch(start: Boolean?) {
            val job = reify()
            if (!validate(job, url, name)) return
            if (!hasInternetRoute()) {
                status.text = tr(NO_INTERNET_CONNECTION)
                status.visibility = View.VISIBLE
                return
            }
            if (start != null) {
                hideKeyboard()
                metadata?.let { save(start, it); return }
            }
            val request = ++generation
            queueButton.isEnabled = false
            startButton.isEnabled = false
            val hint = job.browserFilename.takeIf { it.isNotBlank() } ?: guessName(job.url)
            val preview = listOf(hint, displayBytes(job.total).takeIf { job.total >= 0 }.orEmpty()).filter { it.isNotBlank() }
                .joinToString(" · ")
            val checking = tr("Checking file details…")
            status.text = if (job.browserAgent.isNotBlank() && preview.isNotEmpty()) "$preview\n$checking" else checking
            status.visibility = View.VISIBLE
            Thread({
                val result = runCatching { internetRequest { probeMetadata(job) } }
                runOnUiThread {
                    if (request != generation || currentSheet !== activeForm || activeForm?.closing != false) return@runOnUiThread
                    queueButton.isEnabled = true
                    startButton.isEnabled = true
                    val file = result.getOrElse { error ->
                        val problem = (error as? TransferError)?.message?.let { transferMessage(it) }
                            ?: tr("Couldn’t fetch file details. Check the link and try again.")
                        status.text = if (job.browserAgent.isNotBlank() && preview.isNotEmpty()) "$preview\n$problem" else problem
                        return@runOnUiThread
                    }
                    metadata = file
                    if (job.browserAgent.isNotBlank()) {
                        suggestName(file.name)
                        shownDraft = reify()
                    }
                    status.text = "${file.name} · ${displayBytes(file.size)}"
                    if (start != null) save(start, file)
                }
            }, "Check download metadata").start()
        }
        queueButton = iconButton("plus", 25, tr("Save to queue")) { fetch(false) }.apply {
            background = round(tone(0xfafafa, 0x343436), 36, muted)
        }
        startButton = pill(tr("Start Download"), 23, weight = 550) { fetch(true) }
        form.add(row().apply {
            add(queueButton, 62, 62)
            add(View(this@MainActivity), 3, 1)
            add(startButton, 0, 59, 1f)
        }, height = 63)
        form.space(24)
        if (httpsUrl(draft.url)) fetch(null)
    }

    private fun validate(job: Transfer, url: EditText, name: EditText): Boolean {
        if (!httpsUrl(job.url)) { url.error = tr("Enter a direct HTTPS file URL."); return false }
        if (job.name.isNotBlank() && !validFilename(job.name)) {
            name.error = tr("Use a filename up to 200 UTF-8 bytes without slashes or special characters.")
            return false
        }
        return true
    }

    private fun showWireless() = with(d) {
        val form = sheet(tr("Wireless Transfer"), SheetStyle.WIRELESS) {
            closeSheet(animated = false)
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this@MainActivity, QrScanActivity::class.java), SCAN_CONSOLE)
        }
        val panel = currentSheet
        form.add(text(tr("On the PS4, turn on Direct Download and select Wireless under Transfer Mode."), 16))
        val address = sheetField("192.168.1.100", prefs.getString("last_console", consoleAddress).orEmpty(),
            InputType.TYPE_CLASS_PHONE)
        form.space(13)
        form.add(text(tr("PS4 IP Address"), 20, 700), height = 33)
        form.space(5)
        form.add(address, height = 49)
        form.space(14)
        val codePanel = column()
        val request = pill(tr("Request Six-Digit Code"), 19, fill = elevated, color = ink, weight = 600) {}
        request.setOnClickListener {
            val target = localAddress(address.text.toString().trim().asciiDigits())
            if (target == null) { address.error = tr("Enter the PS4’s local IPv4 address."); return@setOnClickListener }
            manualPair?.close()
            manualPair = null
            codePanel.removeAllViews()
            codePanel.add(text(tr("Requesting a code from the PS4…"), 16, color = subtle))
            it.isEnabled = false
            Thread({
                val pairing = runCatching { ManualPair.open(target) }.getOrNull()
                runOnUiThread {
                    if (currentSheet !== panel || panel?.closing != false) pairing?.close()
                    else {
                        it.isEnabled = true
                        codePanel.removeAllViews()
                        if (pairing == null) codePanel.add(text(tr("Open Transfer Mode on the PS4 and try again."), 16))
                        else promptPairCode(pairing, codePanel, it)
                    }
                }
            }, "Request PS4 pairing code").start()
        }
        form.add(request, height = 54)
        form.space(8)
        form.add(codePanel)
        form.space(10)
    }

    private fun beginPairing() {
        connecting = true
        render()
    }

    private fun completePairing(pair: WirelessPair?) {
        if (isDestroyed || isFinishing) return
        connecting = false
        if (pair != null) {
            WirelessPair.active = pair
            wirelessSetupNeeded = false
            val sealed = runCatching { pair.sealedCredentials() }.getOrNull()
            prefs.edit().putString("transfer_mode", "wireless").putString("last_console", pair.address.hostAddress)
                .putString("wireless_credentials", sealed).apply()
            if (sealed == null) toast(tr("Connected. Android could not save this pairing for later."))
        } else toast(tr("Could not pair. Keep Wireless selected in PS4 Settings and try again."))
        render()
    }

    private fun reconnectWireless(onConnected: (() -> Unit)? = null) {
        if (!wirelessSelected || connecting || TransferService.alive) return
        val address = localAddress(consoleAddress)
        val credentials = prefs.getString("wireless_credentials", "").orEmpty()
        val current = WirelessPair.active?.takeIf { it.address == address }
        if (address == null || (current == null && credentials.isEmpty())) {
            wirelessSetupNeeded = true
            if (settings) render() else if (onConnected != null) toast(tr("Set up Wireless Transfer again in Settings."))
            return
        }
        connecting = true
        wirelessSetupNeeded = false
        if (settings) render()
        Thread({
            val pair = runCatching { (current ?: WirelessPair.restore(address, credentials)).also { it.probe() } }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                connecting = false
                if (wirelessSelected && consoleAddress == address.hostAddress) {
                    WirelessPair.active = pair
                    wirelessSetupNeeded = pair == null
                    if (settings) render()
                    if (pair != null) onConnected?.invoke()
                    else if (!settings && onConnected != null) toast(tr("Couldn’t reconnect. Set up Wireless Transfer again in Settings."))
                }
            }
        }, "Reconnect to paired PS4").start()
    }

    private fun pairFromQr(pair: WirelessPair) {
        beginPairing()
        Thread({
            val success = runCatching { pair.probe() }.isSuccess
            runOnUiThread { completePairing(pair.takeIf { success }) }
        }, "Pair with PS4 QR code").start()
    }

    private fun promptPairCode(pairing: ManualPair, body: LinearLayout, request: View) = with(d) {
        manualPair = pairing
        val entry = sheetField(tr("Six-digit code"), "", InputType.TYPE_CLASS_NUMBER).apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val caption = text(tr("Enter the six digits shown on your PS4."), 16, color = subtle)
        body.add(caption)
        body.space(8)
        body.add(entry, height = 50)
        body.space(10)
        val panel = currentSheet
        val connect = pill(tr("Connect"), 20, weight = 650) {}
        connect.setOnClickListener {
            val entered = entry.text.toString().trim().asciiDigits()
            if (!pairing.matches(entered)) {
                entry.error = tr("Enter the six digits shown on your PS4.")
                return@setOnClickListener
            }
            hideKeyboard()
            it.isEnabled = false
            request.isEnabled = false
            caption.text = tr("Connecting to your PS4…")
            Thread({
                val result = runCatching { pairing.use { it.finish(entered) } }.getOrNull()
                runOnUiThread {
                    if (currentSheet === panel && panel?.closing == false) {
                        manualPair = null
                        if (result != null) { closeSheet(); completePairing(result) }
                        else {
                            request.isEnabled = true
                            body.removeAllViews()
                            body.add(text(tr("Pairing expired or the PS4 rejected it. Request a new code."), 16))
                        }
                    }
                }
            }, "Connect to PS4").start()
        }
        body.add(connect, height = 53)
        entry.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_DONE) false else { connect.performClick(); true }
        }
    }

    private fun sheet(title: String, style: SheetStyle, showClose: Boolean = true, scanQr: (() -> Unit)? = null): LinearLayout = with(d) {
        popup?.dismiss()
        closeSheet(animated = false)
        val overlay = object : FrameLayout(this@MainActivity) {
            override fun onInterceptTouchEvent(event: MotionEvent) =
                currentSheet?.closing == true || super.onInterceptTouchEvent(event)
        }.apply {
            setOnClickListener { closeSheet() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val scrim = View(this@MainActivity).apply { setBackgroundColor(style.dim shl 24) }
        overlay.place(scrim, height = -1)
        content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val outer = column().apply {
            val surface = if (style == SheetStyle.ABOUT) tone(0xf7f7f7, 0x252527) else d.sheet
            background = sheetSurface(style.radius, surface)
            isClickable = true
            isFocusableInTouchMode = true
            accessibilityPaneTitle = title
            padding(20, 0, 20, 0)
        }
        val grip = frame().apply {
            val gripColor = if (style == SheetStyle.ABOUT) tone(0xa8a8aa, 0x86868b) else muted
            place(View(this@MainActivity).apply { background = round(gripColor, 8) }, style.gripWidth,
                6, Gravity.TOP or Gravity.CENTER_HORIZONTAL, top = style.gripTop)
            contentDescription = tr("Dismiss {0}", title)
            var startY = 0f
            setOnClickListener { closeSheet() }
            setOnTouchListener { view, event ->
                val active = currentSheet?.takeUnless { it.closing } ?: return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        active.panel.animate().cancel()
                        active.scrim.animate().cancel()
                        startY = event.rawY
                        view.parent.requestDisallowInterceptTouchEvent(true)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        active.moveTo(event.rawY - startY)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val distance = event.rawY - startY
                        if (distance >= u(46) || kotlin.math.abs(distance) <= u(5)) view.performClick()
                        else active.settle()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (!active.closing) active.settle()
                        true
                    }
                    else -> false
                }
            }
        }
        outer.add(grip, height = style.gripHeight)
        val closeFill = if (style == SheetStyle.ABOUT) tone(0xdddddd, 0x3a3a3d) else field
        outer.add(row().apply {
            minimumHeight = u(56)
            add(text(title, style.titleSize, 750).apply { padding(0, 8, 8, 8) }, 0, -2, 1f)
            if (scanQr != null) {
                add(iconButton("qr", 23, tr("Scan PS4 QR code")) { scanQr() }.apply {
                    background = round(closeFill, 14)
                }, 43, 39)
                add(View(this@MainActivity), 8, 1)
            }
            if (showClose) add(pill(tr(style.closeLabel), 18, closeFill, ink, weight = 500) {
                closeSheet()
            }.apply {
                minWidth = u(style.closeWidth)
                minimumHeight = u(35)
                padding(14, 5, 14, 5)
            }, -2, -2)
        })
        val panel = ScrollView(this@MainActivity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(outer)
        }
        val active = BottomSheet(overlay, panel, scrim)
        currentSheet = active
        if (Motion.enabled) {
            scrim.alpha = 0f
            panel.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int,
                                            oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                    v.removeOnLayoutChangeListener(this)
                    if (currentSheet !== active || active.closing) return
                    active.moveTo(active.travel)
                    active.settle()
                }
            })
        }
        overlay.place(panel, height = -2, gravity = Gravity.BOTTOM, top = 35)
        root.place(overlay, height = -1)
        positionSheet()
        outer.requestFocus()
        outer
    }

    private fun positionSheet() {
        val panel = currentSheet?.takeUnless { it.closing }?.panel ?: return
        val keyboard = bottomInset > navigationInset
        val params = panel.layoutParams as FrameLayout.LayoutParams
        val bottom = if (keyboard) bottomInset else 0
        if (params.bottomMargin != bottom) { params.bottomMargin = bottom; panel.layoutParams = params }
        val body = panel.getChildAt(0)
        val pad = if (keyboard) 0 else navigationInset
        body.setPadding(body.paddingLeft, 0, body.paddingRight, pad)
    }

    private fun closeSheet(animated: Boolean = true) {
        val active = currentSheet ?: return
        if (active.closing && animated) return
        active.closing = true
        manualPair?.close()
        manualPair = null
        active.panel.animate().cancel()
        active.scrim.animate().cancel()
        pendingPermission = null
        usbTimeout?.let(root::removeCallbacks)
        usbTimeout = null
        shownDraft = null
        hideKeyboard()
        active.panel.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val remove = {
            root.removeView(active.overlay)
            currentSheet = null
            content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        if (animated && Motion.enabled && active.panel.height > 0) {
            active.scrim.animate().alpha(0f).setDuration(200).setInterpolator(Motion.leave).start()
            active.panel.animate().translationY(active.travel).setDuration(240).setInterpolator(Motion.leave)
                .withEndAction(remove).start()
        } else remove()
    }

    private fun sheetField(hint: String, value: String, type: Int) = with(d) {
        edit(hint, value).apply {
            inputType = type
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = round(field, 11)
            padding(16, 0, 16, 0)
        }
    }

    private fun String.asciiDigits() = map { char ->
        char.digitToIntOrNull()?.let { '0' + it } ?: char
    }.joinToString("")

    private fun toggleRow(parent: LinearLayout, name: String, checked: Boolean, wrap: Boolean = false): AnimatedToggle = with(d) {
        val toggle = AnimatedToggle(this, name, checked)
        parent.add(row().apply {
            add(text(name, if (wrap) 19 else 20, if (wrap) 650 else 400).apply {
                gravity = Gravity.CENTER_VERTICAL
                if (wrap) padding(0, 7, 8, 7) else oneLine()
                setOnClickListener { if (toggle.isEnabled) toggle.toggle() }
            }, 0, if (wrap) -2 else 49, 1f)
            add(toggle, 68, 49)
        }, height = if (wrap) -2 else 49)
        toggle
    }

    private fun resume(job: Transfer) {
        if (job.url.isBlank()) {
            toast(tr("Enter the source link again to retry this download."))
            showDownload(job.copy(status = TransferStatus.QUEUED, message = ""))
            return
        }
        authorizeTransfer(job) {
            store.update(job.copy(status = TransferStatus.QUEUED, message = ""))
            renderDownloads()
            startQueue()
        }
    }

    private fun authorizeTransfer(job: Transfer, action: () -> Unit) {
        if (job.phoneOnly) action()
        else if (!job.wireless) {
            authorize(true, action)
        } else if (!wirelessSelected || job.console != consoleAddress) {
            toast(tr("Pair with this PS4 in Wireless Transfer settings before starting."))
        } else if (WirelessPair.active?.address?.hostAddress == job.console) {
            action()
        } else if (connecting || TransferService.alive) {
            toast(tr("Wait for the current PS4 connection to finish, then retry."))
        } else reconnectWireless(action)
    }

    private fun startQueue() {
        TransferService.command(this, TransferService.START)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("notifications_asked", false)) {
            prefs.edit().putBoolean("notifications_asked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
        }
    }

    private fun authorize(needsUsb: Boolean, action: () -> Unit) {
        if (!needsUsb) { action(); return }
        val accessory = usbAccessory(usb)
        if (accessory == null) {
            toast(tr("Connect the data cable and enable Direct Download on the PS4."))
            return
        }
        if (usb.hasPermission(accessory)) { action(); return }
        if (pendingPermission != null) return
        pendingPermission = action
        val timeout = Runnable { if (pendingPermission != null) finishPermission(false) }
        usbTimeout = timeout
        root.postDelayed(timeout, 45_000)
        val reply = Intent(USB_PERMISSION).setPackage(packageName)
        runCatching {
            usb.requestPermission(accessory, PendingIntent.getBroadcast(this, 0, reply,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }.onFailure { finishPermission(false) }
    }

    private fun finishPermission(granted: Boolean) {
        val action = pendingPermission ?: return
        pendingPermission = null
        usbTimeout?.let(root::removeCallbacks)
        usbTimeout = null
        if (granted && usbAccessory(usb)?.let(usb::hasPermission) == true) action()
        else toast(tr("USB access was unavailable."))
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
        currentFocus?.clearFocus()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
