/* Copyright (C) 2026 nueex. SPDX-License-Identifier: GPL-3.0-or-later */
package com.nueex.psmtp

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.text.Editable
import android.text.TextWatcher
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

internal class Design(val context: Context, val dark: Boolean) {
    val background = tone(0xededf0, 0x19191b)
    val browserBackground = tone(0xf3f3f5, 0x1d1d1f)
    val card = tone(0xf7f7f9, 0x27272a)
    val elevated = tone(0xf3f3f5, 0x303033)
    val sheet = tone(0xf3f3f3, 0x252527)
    val search = tone(0xe9e9ec, 0x313134)
    val field = tone(0xe1e1e2, 0x3a3a3d)
    val ink = tone(0x111112, 0xf6f6f7)
    val subtle = tone(0x46464a, 0xc4c4c8)
    val muted = tone(0x79797e, 0xa5a5ab)
    val line = tone(0xe7e7e9, 0x38383b)
    val button = tone(0x1a1a1a, 0xececef)
    val chip = tone(0x282829, 0xdedee3)
    val onButton = tone(0xffffff, 0x151517)

    private val font by lazy { context.resources.getFont(resource("app_ui", "font")) }
    val rtl get() = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    private val scale = 0.856f
    fun resource(name: String, type: String) = context.resources.getIdentifier(name, type, context.packageName)

    fun headerArtwork(dark: Boolean): Bitmap {
        val bitmap = BitmapFactory.decodeResource(context.resources, resource("jetbird_header", "drawable"),
            BitmapFactory.Options().apply { inSampleSize = 4; inMutable = true })
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        // Remove the white matte of the supplied JPEG, including the white mixed into edge pixels.
        val white = 247
        for (i in pixels.indices) {
            val color = pixels[i]
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            val coverage = white - minOf(red, green, blue)
            if (coverage <= 0) {
                pixels[i] = Color.TRANSPARENT
                continue
            }
            fun unmatte(channel: Int) = ((channel - white) * 255 / coverage + 255).coerceIn(0, 255)
            val r = unmatte(red)
            val g = unmatte(green)
            val b = unmatte(blue)
            val alpha = coverage * 255 / white
            if (dark) {
                // Lift the purple artwork and make the near-neutral wordmark readable on dark backgrounds.
                val lift = 255 - maxOf(r, g, b) * 158 / 255
                fun lighten(channel: Int) = channel + (255 - channel) * lift / 255
                pixels[i] = Color.argb(alpha, lighten(r), lighten(g), lighten(b))
            } else {
                pixels[i] = Color.argb(alpha, r, g, b)
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    fun u(value: Number): Int = (value.toFloat() * scale * context.resources.displayMetrics.density).roundToInt()
    private fun dimension(value: Int) = if (value < 0) value else u(value)
    fun tone(light: Int, dark: Int) = (if (this.dark) dark else light) or (0xff shl 24)

    fun round(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = u(radius).toFloat()
        stroke?.let { setStroke(u(1).coerceAtLeast(1), it) }
    }

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setBaselineAligned(false) }
    fun frame() = FrameLayout(context)

    fun View.padding(left: Int, top: Int, right: Int, bottom: Int) =
        setPaddingRelative(u(left), u(top), u(right), u(bottom))

    fun LinearLayout.add(view: View, width: Int = -1, height: Int = -2, weight: Float = 0f) =
        addView(view, LinearLayout.LayoutParams(dimension(width), dimension(height), weight))

    fun FrameLayout.place(
        view: View, width: Int = -1, height: Int = -2, gravity: Int = Gravity.TOP or Gravity.START,
        left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0
    ) = addView(view, FrameLayout.LayoutParams(dimension(width), dimension(height), gravity).apply {
        marginStart = u(left)
        marginEnd = u(right)
        topMargin = u(top)
        bottomMargin = u(bottom)
    })

    fun LinearLayout.space(height: Int) = add(View(context), 1, height)
    fun divider() = View(context).apply { setBackgroundColor(line) }

    fun text(value: String, size: Int, weight: Int = 400, color: Int = ink) = TextView(context).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size * scale)
        typeface = font
        fontVariationSettings = "'wght' $weight"
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        includeFontPadding = false
    }

    fun TextView.oneLine() = apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    fun edit(hint: String, value: String, size: Int = 19) = EditText(context).apply {
        this.hint = hint
        setText(value)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size * scale)
        setTextColor(ink)
        setHintTextColor(muted)
        typeface = font
        includeFontPadding = false
        setSingleLine(true)
        background = null
        padding(0, 0, 0, 0)
    }

    fun View.tap(label: String? = null, radius: Int = 14, action: (View) -> Unit) {
        if (label != null) contentDescription = label
        isFocusable = true
        foreground = RippleDrawable(
            ColorStateList.valueOf(if (dark) 0x22ffffff else 0x22000000),
            null, round(Color.WHITE, radius)
        )
        setOnClickListener(action)
    }

    fun pill(label: String, size: Int = 21, fill: Int = button, color: Int = onButton,
             weight: Int = 650, action: () -> Unit) =
        text(label, size, weight, color).apply {
            padding(14, 0, 14, 0)
            gravity = Gravity.CENTER
            background = round(fill, 64)
            tap(radius = 64) { action() }
        }

    fun icon(name: String, size: Int, color: Int = ink) = Glyph(context, name, u(size).toFloat(), color, font)

    fun iconButton(name: String, size: Int, label: String, color: Int = ink, action: (View) -> Unit) =
        icon(name, size, color).apply { tap(label, 64, action) }

    fun dialog() = AlertDialog.Builder(context, resource(if (dark) "AppDialogDark" else "AppDialogLight", "style"))

    fun settingRow(title: String, detail: String, glyph: String = "right", height: Int = 62,
                   wrapDetail: Boolean = false, action: (View) -> Unit) = row().apply {
        background = round(card, 16)
        padding(19, 0, 12, 0)
        val titleLabel = text(title, 19, 650).oneLine()
        val detailLabel = detail.takeIf(String::isNotEmpty)?.let {
            text(it, 16, color = subtle).apply { if (!wrapDetail) oneLine() }
        }
        val iconWidth = if (glyph == "spinner") 35 else 29
        val availableWidth = context.resources.displayMetrics.widthPixels - u(40 + 19 + 12 + iconWidth)
        val labelsWidth = titleLabel.paint.measureText(title) +
            (detailLabel?.paint?.measureText(detail) ?: 0f) + u(8)
        if (detailLabel != null && labelsWidth > availableWidth) {
            add(column().apply {
                padding(0, 7, 8, 7)
                add(titleLabel)
                space(3)
                add(detailLabel)
            }, 0, -2, 1f)
        } else {
            add(titleLabel, 0, height, 1f)
            if (detailLabel != null) {
                add(View(context), 8, 1)
                add(detailLabel, -2, height)
            }
        }
        if (glyph == "spinner") {
            val indicator = ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(ink)
            }
            add(frame().apply { place(indicator, 24, 24, Gravity.CENTER) }, iconWidth, height)
        } else add(icon(glyph, 20, subtle), iconWidth, height)
        tap(action = action)
    }

    fun sheetSurface(radius: Int, color: Int = sheet) = round(color, 0).apply {
        val r = u(radius).toFloat()
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

    fun showBottomDialog(dialog: Dialog, form: View) {
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(form)
        })
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(if (Motion.enabled) resource("BottomSheetMotion", "style") else 0)
            setDimAmount(0.52f)
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Suppress("DEPRECATION")
    fun bars(activity: Activity, root: View, light: Boolean = !dark, insets: (WindowInsets) -> Unit) {
        val window = activity.window
        if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) flags else 0, flags)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        }
        root.setOnApplyWindowInsetsListener { _, received -> insets(received); received }
        root.requestApplyInsets()
    }

    companion object {
        fun isDark(context: Context, choice: String) = choice == "Dark" ||
            (choice == "System" && context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)

        @Suppress("DEPRECATION")
        fun topInset(insets: WindowInsets) = if (android.os.Build.VERSION.SDK_INT >= 30) {
            insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
        } else insets.systemWindowInsetTop

        @Suppress("DEPRECATION")
        fun bottomInset(insets: WindowInsets) = if (android.os.Build.VERSION.SDK_INT >= 30) {
            insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.ime()).bottom
        } else insets.systemWindowInsetBottom
    }
}

internal class Glyph(context: Context, private val glyph: String, private val size: Float, tint: Int, private val font: Typeface) :
    View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate((width - size) / 2, (height - size) / 2)
        canvas.scale(size / 24, size / 24)
        if (layoutDirection == LAYOUT_DIRECTION_RTL && (glyph == "left" || glyph == "right")) {
            canvas.translate(24f, 0f)
            canvas.scale(-1f, 1f)
        }
        paint.style = Paint.Style.STROKE

        fun stroke(vararg points: Float) {
            val path = Path().apply {
                moveTo(points[0], points[1])
                for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
            }
            canvas.drawPath(path, paint)
        }
        fun dot(x: Float, y: Float, radius: Float) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(x, y, radius, paint)
            paint.style = Paint.Style.STROKE
        }

        when (glyph) {
            "more" -> for (y in 5..19 step 7) dot(12f, y.toFloat(), 2.1f)
            "search" -> {
                canvas.drawCircle(10f, 10f, 6.6f, paint)
                stroke(15f, 15f, 21f, 21f)
            }
            "plus" -> {
                stroke(12f, 3f, 12f, 21f)
                stroke(3f, 12f, 21f, 12f)
            }
            "star", "star-on" -> {
                val path = Path().apply {
                    moveTo(12f, 2.5f)
                    lineTo(14.9f, 8.7f)
                    lineTo(21.8f, 9.6f)
                    lineTo(16.8f, 14.3f)
                    lineTo(18f, 21.2f)
                    lineTo(12f, 18f)
                    lineTo(6f, 21.2f)
                    lineTo(7.2f, 14.3f)
                    lineTo(2.2f, 9.6f)
                    lineTo(9.1f, 8.7f)
                    close()
                }
                if (glyph == "star-on") paint.style = Paint.Style.FILL
                canvas.drawPath(path, paint)
            }
            "close" -> {
                stroke(5f, 5f, 19f, 19f)
                stroke(19f, 5f, 5f, 19f)
            }
            "right" -> stroke(9f, 5f, 16f, 12f, 9f, 19f)
            "left" -> stroke(15f, 5f, 8f, 12f, 15f, 19f)
            "warning" -> {
                stroke(12f, 3f, 22f, 21f, 2f, 21f, 12f, 3f)
                stroke(12f, 9f, 12f, 14f)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(12f, 17.5f, 1.2f, paint)
            }
            "battery-warning" -> {
                val tint = paint.color
                paint.style = Paint.Style.FILL
                paint.alpha = 85
                canvas.drawRoundRect(2f, 4f, 16f, 21f, 2.4f, 2.4f, paint)
                paint.color = tint
                paint.style = Paint.Style.STROKE
                canvas.drawRoundRect(2f, 4f, 16f, 21f, 2.4f, 2.4f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(6f, 1f, 12f, 4f, 1f, 1f, paint)
                canvas.drawPath(Path().apply {
                    moveTo(10f, 7f)
                    lineTo(5f, 13f)
                    lineTo(8.5f, 13f)
                    lineTo(8.5f, 17f)
                    lineTo(13f, 10.8f)
                    lineTo(10f, 10.8f)
                    close()
                }, paint)
                canvas.drawPath(Path().apply {
                    moveTo(18.5f, 13f)
                    lineTo(24f, 22.8f)
                    lineTo(13f, 22.8f)
                    close()
                }, paint)
                paint.color = Color.rgb(166, 21, 28)
                canvas.drawRoundRect(17.9f, 16.6f, 19.1f, 19.8f, .5f, .5f, paint)
                canvas.drawCircle(18.5f, 21.1f, .65f, paint)
                paint.color = tint
            }
            "qr" -> {
                canvas.drawRect(3f, 3f, 9f, 9f, paint)
                canvas.drawRect(15f, 3f, 21f, 9f, paint)
                canvas.drawRect(3f, 15f, 9f, 21f, paint)
                stroke(15f, 14f, 15f, 18f, 19f, 18f, 19f, 21f, 22f, 21f)
                stroke(20f, 14f, 22f, 14f)
                stroke(14f, 21f, 15f, 21f)
            }
            "down" -> stroke(5f, 9f, 12f, 16f, 19f, 9f)
            "external" -> {
                stroke(5f, 19f, 19f, 5f)
                stroke(7f, 5f, 19f, 5f, 19f, 17f)
            }
            "check" -> stroke(4f, 12f, 10f, 18f, 20f, 6f)
            "pause" -> {
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(5f, 3f, 10f, 21f, 2f, 2f, paint)
                canvas.drawRoundRect(14f, 3f, 19f, 21f, 2f, 2f, paint)
            }
            "stop" -> {
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(4f, 4f, 20f, 20f, 2f, 2f, paint)
            }
            "play" -> {
                paint.style = Paint.Style.FILL
                stroke(7f, 3f, 21f, 12f, 7f, 21f, 7f, 3f)
            }
            "home", "home-on" -> {
                if (glyph.endsWith("-on")) paint.style = Paint.Style.FILL_AND_STROKE
                stroke(1.8f, 11f, 12f, 2f, 22.2f, 11f, 20f, 11f, 20f, 22f, 14.6f, 22f,
                    14.6f, 15.5f, 9.4f, 15.5f, 9.4f, 22f, 4f, 22f, 4f, 11f, 1.8f, 11f)
            }
            "gear", "gear-on" -> {
                if (glyph.endsWith("-on")) paint.style = Paint.Style.FILL
                val path = Path().apply {
                    moveTo(9.6f, 1.5f); lineTo(14.4f, 1.5f); lineTo(15.5f, 4.6f)
                    lineTo(17.9f, 5.9f); lineTo(21.1f, 5.5f); lineTo(23.4f, 9.6f)
                    lineTo(21.3f, 12f); lineTo(21.3f, 14f); lineTo(23.4f, 16.5f)
                    lineTo(21.1f, 20.4f); lineTo(17.9f, 20f); lineTo(15.5f, 21.2f)
                    lineTo(14.5f, 23f); lineTo(9.5f, 23f); lineTo(8.5f, 21.2f)
                    lineTo(6.1f, 20f); lineTo(2.9f, 20.4f); lineTo(.6f, 16.5f)
                    lineTo(2.7f, 14f); lineTo(2.7f, 12f); lineTo(.6f, 9.6f)
                    lineTo(2.9f, 5.5f); lineTo(6.1f, 5.9f); lineTo(8.5f, 4.6f); close()
                    addCircle(12f, 12.6f, 3.7f, Path.Direction.CCW)
                    fillType = Path.FillType.EVEN_ODD
                }
                canvas.scale(.89f, .89f, 12f, 12f)
                canvas.drawPath(path, paint)
            }
            "globe" -> {
                canvas.drawCircle(12f, 12f, 10f, paint)
                canvas.drawOval(7.5f, 2f, 16.5f, 22f, paint)
                stroke(3.3f, 8.1f, 20.7f, 8.1f)
                stroke(3.3f, 15.9f, 20.7f, 15.9f)
            }
            "refresh" -> {
                canvas.drawArc(2.7f, 2.7f, 21.3f, 21.3f, 194f, 144f, false, paint)
                canvas.drawArc(2.7f, 2.7f, 21.3f, 21.3f, 14f, 144f, false, paint)
                stroke(21f, 2.6f, 21f, 9f, 14.8f, 9f)
                stroke(3f, 21.4f, 3f, 15f, 9.2f, 15f)
            }
            else -> if (glyph.startsWith("tab:")) {
                canvas.drawRoundRect(1.5f, 1.5f, 22.5f, 22.5f, 4.5f, 4.5f, paint)
                paint.style = Paint.Style.FILL
                paint.typeface = Typeface.create(font, Typeface.BOLD)
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = if (glyph.length > 5) 11f else 14f
                canvas.drawText(glyph.substringAfter(":"), 12f, 17f, paint)
            }
        }
        canvas.restore()
    }
}

internal data class MenuOption(val title: String, val glyph: String = "", val checked: Boolean = false,
                               val bold: Boolean = checked,
                               val fontName: String? = null,
                               val action: () -> Unit)

internal fun Design.showMenu(anchor: View, width: Int, y: Int, x: Int, choices: List<MenuOption>,
                             closed: () -> Unit = {}, rowHeight: Int = 43): PopupWindow {
    val surface = column().apply {
        background = round(elevated, 16)
        padding(13, 3, 13, 3)
    }
    val screen = context.resources.displayMetrics.widthPixels
    val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
    val size = (u(width) * fontScale).roundToInt().coerceAtMost(screen - u(30))
    val scroller = ScrollView(context).apply {
        addView(surface)
        isVerticalScrollBarEnabled = false
    }
    val menu = PopupWindow(scroller, size, -2, true).apply {
        setBackgroundDrawable(round(elevated, 16))
        elevation = u(10).toFloat()
        animationStyle = resource("PopupMotion", "style")
        isOutsideTouchable = true
        setOnDismissListener(closed)
    }
    choices.forEachIndexed { index, option ->
        if (index > 0) surface.add(frame().apply {
            place(divider(), height = 1, left = if (option.glyph.isBlank()) 6 else 53, right = 6)
        }, height = 1)
        val choice = row().apply {
            padding(6, 0, 6, 0)
            if (option.glyph.isNotBlank()) {
                add(icon(option.glyph, 22), 30, 41)
                add(View(context), 17, 1)
            }
            add(text(option.title, 18, if (option.bold) 600 else 420).oneLine().apply {
                option.fontName?.let { typeface = resources.getFont(resource(it, "font")) }
            }, 0, rowHeight, 1f)
            if (option.checked) add(icon("check", 22), 27, rowHeight)
            isSelected = option.checked
            tap(radius = 10) { menu.dismiss(); option.action() }
        }
        surface.add(choice, height = rowHeight)
    }
    val availableHeight = menu.getMaxAvailableHeight(anchor, u(y))
    if (choices.size * u(rowHeight) + (choices.size - 1) * u(1) + u(6) > availableHeight && availableHeight > 0) {
        menu.height = availableHeight
    }
    menu.showAsDropDown(anchor, if (rtl) -u(x) else u(x), u(y), Gravity.END)
    return menu
}

internal fun Design.showSettingsMenu(anchor: View, gap: View, restHeight: Int, choices: List<MenuOption>,
                                     extraSpace: Int = 0): PopupWindow {
    fun resize(height: Int) {
        val container = gap.parent as? ViewGroup ?: return
        if (Motion.enabled && gap.isAttachedToWindow) {
            TransitionManager.beginDelayedTransition(container, ChangeBounds().apply {
                duration = 220
                interpolator = Motion.settle
            })
        }
        gap.layoutParams = gap.layoutParams.apply { this.height = u(height) }
    }
    val popup = showMenu(anchor, 260, -8, 0, choices, closed = { resize(restHeight) })
    resize(65 + (choices.size - 2) * 44 + extraSpace)
    return popup
}

internal fun EditText.onChanged(action: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = action(s.toString())
    override fun afterTextChanged(s: Editable?) = Unit
})
