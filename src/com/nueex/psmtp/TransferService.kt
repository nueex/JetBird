/* Copyright (C) 2026 nueex. SPDX-License-Identifier: GPL-3.0-or-later */
package com.nueex.psmtp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.Locale

class TransferService : Service() {
    internal companion object {
        const val CHANGED = "com.nueex.psmtp.TRANSFERS_CHANGED"
        const val START = "com.nueex.psmtp.START"
        const val PAUSE = "com.nueex.psmtp.PAUSE"
        const val CANCEL = "com.nueex.psmtp.CANCEL"
        private const val CHANNEL = "transfers"
        private const val ACTIVE_NOTIFICATION = 41
        private const val RESULT_NOTIFICATION = 42
        @Volatile var alive = false
            private set

        fun command(context: Context, action: String, id: String = "") {
            val intent = Intent(context, TransferService::class.java).setAction(action).putExtra("id", id)
            if (action == START) context.startForegroundService(intent) else context.startService(intent)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val store by lazy { TransferStore(this) }
    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val notificationBird by lazy {
        Icon.createWithResource(this, resources.getIdentifier("jetbird_notification", "drawable", packageName))
    }
    private var running: TransferWorker? = null
    private var current: Transfer? = null
    private var accessory: UsbAccessory? = null
    private var requestedAction: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastWake = 0L
    private var lastSave = 0L
    private var lastNotification = 0L
    private var sampleAt = 0L
    private var sampleBytes = 0L
    private var foreground = false
    private val wakeHeartbeat = object : Runnable {
        override fun run() {
            if (running != null) {
                renewWake(SystemClock.elapsedRealtime())
                main.postDelayed(this, 60_000)
            }
        }
    }

    private val detached = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            } else intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
            if (device != null && device == accessory && requestedAction == null) stopCurrent("detach")
        }
    }

    override fun onCreate() {
        super.onCreate()
        alive = true
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, tr("Transfers"), NotificationManager.IMPORTANCE_LOW))
        val filter = IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(detached, filter, RECEIVER_EXPORTED)
        else registerReceiver(detached, filter)
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> next()
            PAUSE, CANCEL -> {
                val id = intent.getStringExtra("id").orEmpty()
                val action = intent.action ?: return START_NOT_STICKY
                if (current?.id == id) {
                    if (requestedAction == null || action == CANCEL && requestedAction != CANCEL) stopCurrent(action)
                } else {
                    val queued = store.all().firstOrNull { it.id == id && it.status == TransferStatus.QUEUED }
                    if (queued != null && (action == PAUSE || !queued.checkpoint)) {
                        if (action == CANCEL) store.remove(queued.id, durable = true)
                        else store.update(queued.copy(status = TransferStatus.PAUSED))
                        signal()
                    }
                    if (running == null) stopSelf(startId)
                }
            }
            else -> if (running == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun next() {
        if (running != null) return
        val candidate = store.all().firstOrNull { it.status == TransferStatus.QUEUED }
        if (candidate == null) {
            if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            stopSelf()
            return
        }
        val device = if (candidate.needsUsb) usbAccessory(usb) else null
        if (candidate.needsUsb && (device == null || !usb.hasPermission(device))) {
            store.save(store.all().map {
                if (it.status == TransferStatus.QUEUED && it.needsUsb) {
                    it.copy(status = TransferStatus.FAILED, message = "Reconnect the USB data cable and retry.")
                } else it
            })
            signal()
            next()
            return
        }
        val opening = candidate.copy(status = TransferStatus.ACTIVE, done = candidate.checkpointOffset, message = "Opening download…")
        val foregroundType = if (!candidate.needsUsb) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        try {
            startForeground(ACTIVE_NOTIFICATION, notification(opening, true), foregroundType)
            foreground = true
        } catch (_: RuntimeException) {
            store.save(store.all().map {
                if (it.status == TransferStatus.QUEUED) {
                    it.copy(status = TransferStatus.FAILED, message = "Android could not start the background transfer.")
                } else it
            })
            signal()
            stopSelf()
            return
        }
        releaseWake()
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "psmtp:transfer").apply {
            setReferenceCounted(false)
        }
        renewWake(SystemClock.elapsedRealtime())
        lastNotification = lastWake
        lastSave = lastWake
        sampleAt = lastWake
        sampleBytes = opening.done
        current = opening
        accessory = device
        requestedAction = null
        store.update(opening)
        signal()
        startWorker(opening)
        main.postDelayed(wakeHeartbeat, 60_000)
    }

    private fun startWorker(job: Transfer, stopping: Boolean = false) {
        val worker = TransferWorker(this, usb, accessory, job,
            { name, done, total, caption -> main.post { progress(job.id, name, done, total, caption) } },
            { parts -> main.post {
                val active = current
                if (active?.id == job.id && requestedAction == null && active.message.isEmpty()) {
                    reportProgress(active.copy(partsProgress = parts, speed = parts.bytesPerSecond), SystemClock.elapsedRealtime())
                }
            } },
            { identity, phone -> main.post {
                current?.takeIf { it.id == job.id }?.copy(resumeIdentity = identity, pendingPhone = phone)?.let {
                    current = it
                    store.update(it, durable = stopping || requestedAction == CANCEL)
                }
            } },
            { result -> main.post { end(job.id, result) } })
        running = worker
        if (stopping) worker.requestStop()
        Thread(worker, "PS MTP download").start()
    }

    private fun progress(id: String, name: String, done: Long, total: Long, message: String) {
        val job = current ?: return
        if (id != job.id || requestedAction != null) return
        val now = SystemClock.elapsedRealtime()
        var speed = if (message.isEmpty()) job.speed else 0L
        val elapsed = now - sampleAt
        if (elapsed >= 750 || done < sampleBytes || message.isNotEmpty()) {
            if (message.isEmpty() && job.partsProgress == null && elapsed >= 750 && done >= sampleBytes) {
                val observed = (done - sampleBytes) * 1000 / elapsed
                speed = if (speed == 0L) observed else (speed + observed * 2) / 3
            }
            sampleAt = now
            sampleBytes = done
        }
        val updated = job.copy(name = name, done = done, total = total, message = message, speed = speed)
        reportProgress(updated, now)
    }

    private fun reportProgress(updated: Transfer, now: Long) {
        val previousMessage = current?.message
        current = updated
        if (now - lastSave >= 480 || previousMessage != updated.message) {
            lastSave = now
            store.update(updated)
            signal()
        }
        if (now - lastNotification >= 1_000) {
            lastNotification = now
            notifications.notify(ACTIVE_NOTIFICATION, notification(updated, true))
        }
    }

    private fun stopCurrent(action: String) {
        val job = current ?: return
        requestedAction = action
        val updating = job.copy(status = if (action == CANCEL) TransferStatus.STOPPING else TransferStatus.PAUSING,
            speed = 0, message = if (action == PAUSE) "Pausing…" else "Stopping…")
        current = updating
        store.update(updating, durable = action == CANCEL)
        signal()
        when (action) {
            PAUSE -> running?.requestPause()
            CANCEL -> running?.requestStop()
            else -> running?.cancel()
        }
        notifications.notify(ACTIVE_NOTIFICATION, notification(updating, true))
    }

    private fun end(id: String, result: TransferWorker.Result) {
        val job = current?.takeIf { it.id == id } ?: return
        val action = requestedAction
        if (action == CANCEL && result.checkpoint) {
            startWorker(job.copy(name = result.name, total = result.total, checkpoint = true,
                checkpointOffset = result.done), stopping = true)
            return
        }
        running = null
        current = null
        accessory = null
        requestedAction = null
        releaseWake()
        val status = when {
            result.complete -> TransferStatus.COMPLETE
            action == CANCEL -> TransferStatus.STOPPED
            result.paused || action == PAUSE && result.checkpoint -> TransferStatus.PAUSED
            else -> TransferStatus.FAILED
        }
        val item = job.copy(status = status, name = result.name, done = result.done, total = result.total, speed = 0,
            checkpoint = result.checkpoint, pendingPhone = job.pendingPhone.takeIf { result.checkpoint }.orEmpty(),
            checkpointOffset = if (result.checkpoint) result.done else 0,
            phoneCopy = result.phone, message = if (action == "detach" && !result.complete) "USB disconnected. Reconnect the PS4 and retry." else result.message)
        if (action == CANCEL && (result.stopped || result.paused)) store.remove(id, durable = true)
        else {
            store.update(if (result.complete) item.copy(url = "", browserAgent = "", browserPage = "", resumeIdentity = "") else item,
                durable = result.paused || result.complete || status == TransferStatus.STOPPED)
            if (status != TransferStatus.PAUSED && notifications.areNotificationsEnabled()) {
                notifications.notify(item.id, RESULT_NOTIFICATION, notification(item, false))
            }
        }
        signal()
        next()
    }

    private fun notification(job: Transfer, active: Boolean): Notification {
        val operation = if (job.install) "Parallel Install" else "Direct Download"
        val mode = when {
            job.status == TransferStatus.COMPLETE && job.direct -> "Sent to PS4"
            job.status == TransferStatus.COMPLETE -> if (job.install) "Installed on PS4" else "Download complete"
            job.status == TransferStatus.STOPPED -> "Transfer stop needs attention"
            !active -> "Transfer interrupted"
            job.status == TransferStatus.PAUSING || job.status == TransferStatus.STOPPING -> job.message
            else -> operation
        }
        val amount = tr("{0} MB", String.format(AppLanguage.selected(this).locale, "%.1f", job.done / 1_000_000.0))
        val percent = if (job.total > 0) displayPercent(job.percent) else ""
        val summary = if (active) listOf(percent, tr("{0}/s", displayBytes(job.speed)), amount)
            .filter(String::isNotEmpty).joinToString(" · ") else if (job.status == TransferStatus.COMPLETE && !job.direct) amount else job.message
        val launch = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(notificationBird)
            .setLargeIcon(notificationBird)
            .setContentIntent(open)
            .setCategory(if (active) Notification.CATEGORY_PROGRESS else Notification.CATEGORY_STATUS)
            .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(!active).setOngoing(active)
        if (active) {
            builder.setContentTitle(transferMessage(mode)).setContentText("$summary · ${job.name}")
                .setStyle(Notification.BigTextStyle().bigText("${job.name}\n$summary"))
                .setProgress(100, job.percent, job.total <= 0)
            if (job.status != TransferStatus.STOPPING) {
                val stop = Intent(this, TransferService::class.java).setAction(CANCEL).putExtra("id", job.id)
                    .setData(Uri.parse("psmtp://transfer/${job.id}"))
                val pending = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE)
                val icon = Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel)
                builder.addAction(Notification.Action.Builder(icon, tr("Stop"), pending).build())
            }
            if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        } else builder.setContentTitle(job.name).setContentText(transferMessage(summary)).setSubText(transferMessage(mode))
        return builder.build()
    }

    private fun renewWake(now: Long) {
        wakeLock?.acquire(180_000)
        lastWake = now
    }

    private fun releaseWake() {
        main.removeCallbacks(wakeHeartbeat)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun signal() = sendBroadcast(Intent(CHANGED).setPackage(packageName))

    override fun onTimeout(startId: Int, fgsType: Int) {
        current = null // A late worker completion cannot start the next queued job during teardown.
        running?.cancel()
        store.recover()
        signal()
        stopSelf()
    }

    override fun onDestroy() {
        alive = false
        running?.cancel()
        running = null
        current = null
        store.recover()
        signal()
        releaseWake()
        unregisterReceiver(detached)
        super.onDestroy()
    }
}
