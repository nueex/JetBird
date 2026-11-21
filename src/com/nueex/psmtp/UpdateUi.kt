/* Copyright (C) 2026 nueex. SPDX-License-Identifier: GPL-3.0-or-later */
package com.nueex.psmtp

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast
import java.lang.ref.WeakReference

internal class UpdateUi(
    private val activity: Activity,
    private val design: () -> Design,
    private val canOfferAutomatically: () -> Boolean = { true }
) {
    private val prefs = activity.getSharedPreferences("updates", Activity.MODE_PRIVATE)
    private var checkInFlight = false
    private var checkIsManual = false
    private var offer: Dialog? = null
    private var automaticOffer = false
    private var resumed = false

    var automatic: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(enabled) {
            prefs.edit().putBoolean("automatic", enabled).apply()
            if (!enabled && automaticOffer) offer?.dismiss()
        }

    fun resume(): Boolean {
        resumed = true
        foreground = WeakReference(this)
        return offerInstaller()
    }

    fun pause() {
        resumed = false
        if (foreground?.get() === this) foreground = null
    }

    fun close() {
        pause()
        offer?.dismiss()
    }

    fun check(manual: Boolean) {
        if (!manual && (!automatic || FIREBASE_UPDATE_URL.isBlank())) return
        if (manual) checkIsManual = true
        if (checkInFlight) return
        checkInFlight = true
        if (manual) toast(tr("Checking for updates…"))
        val app = activity.applicationContext
        Thread({
            val result = runCatching {
                app.requireInternetRoute()
                app.internetRequest { checkAndroidRelease(app) }
            }
            activity.runOnUiThread {
                checkInFlight = false
                val requested = checkIsManual
                checkIsManual = false
                if (!resumed || activity.isFinishing || (!requested && (!automatic || !canOfferAutomatically())))
                    return@runOnUiThread
                result.fold(
                    onSuccess = { release ->
                        if (release != null) show(release, requested)
                        else if (requested) toast(tr("JetBird is up to date."))
                    },
                    onFailure = { error ->
                        if (requested) toast((error as? TransferError)?.message?.let { activity.transferMessage(it) }
                            ?: tr("Couldn’t check for updates. Check your connection and Firebase configuration."))
                    }
                )
            }
        }, "Check JetBird release").start()
    }

    private fun show(release: AndroidRelease, manual: Boolean) = with(design()) {
        if (offer?.isShowing == true) return@with
        val modal = Dialog(activity)
        modal.requestWindowFeature(Window.FEATURE_NO_TITLE)
        modal.setCanceledOnTouchOutside(true)
        val form = column().apply {
            background = round(sheet, 0).apply {
                val radius = u(34).toFloat()
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            padding(20, 12, 20, 20)
            accessibilityPaneTitle = tr("Update Available")
            add(frame().apply {
                place(View(activity).apply { background = round(muted, 8) }, 46, 6, Gravity.CENTER)
            }, height = 16)
            space(12)
            add(text(tr("Update Available"), 27, 750))
            space(12)
            add(text(release.title, 22, 700))
            val link = text(tr("Version {0} · Release page", release.version), 17, 650, Color.BLACK).apply {
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                background = round(Color.WHITE, 10)
                padding(10, 8, 10, 8)
                setOnClickListener {
                    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.releaseUrl))) }
                        .onFailure { toast(tr("Couldn’t open the release page.")) }
                }
            }
            space(8)
            add(link, width = WRAP_CONTENT)
            space(14)
            add(text(release.changelog, 16).apply { setLineSpacing(u(4).toFloat(), 1f) })
            space(20)
            val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                visibility = View.GONE
                contentDescription = tr("Downloading update")
            }
            add(progress, height = 6)
            add(row().apply {
                add(pill(tr("Not Now"), 18, field, ink) { modal.dismiss() }, 0, 48, 1f)
                add(View(activity), 10, 1)
                lateinit var download: View
                download = pill(tr("Download"), 18) {
                    if (downloading) {
                        toast(tr("JetBird is already downloading an update."))
                    } else {
                        download.isEnabled = false
                        progress.visibility = View.VISIBLE
                        download(release) { success ->
                            if (success) modal.dismiss()
                            else {
                                download.isEnabled = true
                                progress.visibility = View.GONE
                            }
                        }
                    }
                }
                add(download, 0, 48, 1f)
            })
        }
        modal.setContentView(ScrollView(activity).apply {
            isFillViewport = false
            addView(form)
        })
        modal.setOnDismissListener {
            if (offer === modal) {
                offer = null
                automaticOffer = false
            }
        }
        offer = modal
        automaticOffer = !manual
        modal.show()
        modal.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH_PARENT, WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.52f)
        }
    }

    private fun download(release: AndroidRelease, finished: (Boolean) -> Unit) {
        downloading = true
        val app = activity.applicationContext
        Thread({
            val result = runCatching {
                app.requireInternetRoute()
                app.internetRequest { downloadAndroidRelease(app, release) }
            }
            result.onSuccess { uri ->
                prefs.edit().putString("apk_uri", uri.toString()).putBoolean("install_pending", true).apply()
            }
            activity.runOnUiThread {
                downloading = false
                finished(result.isSuccess)
                val active = foreground?.get()
                if (result.isSuccess) active?.offerInstaller()
                else active?.toast((result.exceptionOrNull() as? TransferError)?.message?.let { activity.transferMessage(it) }
                    ?: tr("Couldn’t save the JetBird update. Check your connection and free storage."))
            }
        }, "Download JetBird update").start()
    }

    private fun offerInstaller(): Boolean {
        if (!resumed || activity.isFinishing || !prefs.getBoolean("install_pending", false)) return false
        prefs.edit().remove("install_pending").apply()
        if (activity.packageManager.canRequestPackageInstalls()) return openInstaller()
        toast(tr("Allow installs from JetBird to continue."))
        val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", activity.packageName, null))
        @Suppress("DEPRECATION")
        return runCatching { activity.startActivityForResult(settings, INSTALL_PERMISSION) }
            .onFailure { toast(tr("Open the saved APK in Download/JetBird/updates to install it.")) }.isSuccess
    }

    fun installPermissionResult(requestCode: Int): Boolean {
        if (requestCode != INSTALL_PERMISSION) return false
        if (activity.packageManager.canRequestPackageInstalls()) openInstaller()
        else toast(tr("The APK is saved in Download/JetBird/updates."))
        return true
    }

    private fun openInstaller(): Boolean {
        val uri = prefs.getString("apk_uri", null)?.let(Uri::parse) ?: return false
        val install = Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching { activity.startActivity(install) }
            .onFailure { toast(tr("Open the saved APK in Download/JetBird/updates to install it.")) }.isSuccess
    }

    private fun tr(source: String, vararg values: Any) = activity.tr(source, *values)

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val INSTALL_PERMISSION = 58
        var foreground: WeakReference<UpdateUi>? = null
        var downloading = false
    }
}
