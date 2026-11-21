/* Copyright (C) 2026 nueex. SPDX-License-Identifier: GPL-3.0-or-later */
package com.nueex.psmtp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("DEPRECATION")
class QrScanActivity : Activity(), TextureView.SurfaceTextureListener {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(base))

    private lateinit var preview: TextureView
    private var camera: Camera? = null
    private val decoder = Executors.newSingleThreadExecutor()
    private val scanning = AtomicBoolean()
    private var lastFrame = 0L
    private var visible = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val d = Design(this, true)
        with(d) {
            val root = frame().apply { setBackgroundColor(Color.BLACK) }
            preview = TextureView(this@QrScanActivity).also { it.surfaceTextureListener = this@QrScanActivity }
            root.addView(preview, FrameLayout.LayoutParams(-1, -1))
            root.place(frame().apply { background = round(Color.TRANSPARENT, 20, Color.WHITE) },
                width = 250, height = 250, gravity = Gravity.CENTER)
            setContentView(root)
            bars(this@QrScanActivity, root) { insets -> root.setPadding(0, Design.topInset(insets), 0, Design.bottomInset(insets)) }
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        openCamera()
    }

    override fun onPause() {
        visible = false
        releaseCamera()
        super.onPause()
    }

    override fun onDestroy() {
        decoder.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != CAMERA_PERMISSION) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera()
        else unavailable(tr("Camera access was declined. You can pair using a code."))
    }

    private fun openCamera() {
        val texture = preview.surfaceTexture ?: return
        if (!visible || camera != null || isFinishing ||
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            val device = Camera.open()
            camera = device
            val settings = device.parameters
            val size = settings.supportedPreviewSizes
                .filter { it.width >= 640 && it.height >= 480 }
                .minByOrNull { kotlin.math.abs(it.width * it.height - 1280 * 720) }
                ?: settings.previewSize
            settings.setPreviewSize(size.width, size.height)
            if (android.graphics.ImageFormat.NV21 in settings.supportedPreviewFormats.orEmpty())
                settings.previewFormat = android.graphics.ImageFormat.NV21
            if (Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE in settings.supportedFocusModes.orEmpty())
                settings.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            device.parameters = settings
            device.setDisplayOrientation(90)
            device.setPreviewTexture(texture)
            device.setPreviewCallback { bytes, _ -> scan(bytes, size.width, size.height) }
            device.startPreview()
        } catch (_: Exception) {
            releaseCamera()
            unavailable(tr("Could not open the camera. You can pair using a code."))
        }
    }

    private fun scan(bytes: ByteArray?, width: Int, height: Int) {
        val now = SystemClock.elapsedRealtime()
        if (bytes == null || bytes.size < width * height || now - lastFrame < 250 ||
            !scanning.compareAndSet(false, true)) return
        lastFrame = now
        val luminance = bytes.copyOf(width * height)
        decoder.execute {
            val value = try {
                val image = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
                QRCodeReader().decode(BinaryBitmap(HybridBinarizer(image)), mapOf(DecodeHintType.TRY_HARDER to true)).text
            } catch (_: com.google.zxing.ReaderException) {
                null
            } finally {
                scanning.set(false)
            }
            if (value != null && WirelessPair.fromQr(value, ::localAddress) != null) runOnUiThread {
                if (!visible || isFinishing) return@runOnUiThread
                setResult(RESULT_OK, Intent().putExtra("pairing", value))
                finish()
            }
        }
    }

    private fun releaseCamera() {
        camera?.let { device ->
            runCatching { device.setPreviewCallback(null); device.stopPreview() }
            device.release()
        }
        camera = null
    }

    private fun unavailable(message: String) {
        if (isFinishing) return
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = openCamera()
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { releaseCamera(); return true }

    private companion object { const val CAMERA_PERMISSION = 1 }
}
