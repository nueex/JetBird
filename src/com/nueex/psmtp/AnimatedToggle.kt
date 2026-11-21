/* Copyright (C) 2026 nueex. SPDX-License-Identifier: GPL-3.0-or-later */
package com.nueex.psmtp

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.ToggleButton

internal class AnimatedToggle(private val design: Design, label: String, checked: Boolean) : ToggleButton(design.context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colors = ArgbEvaluator()
    private val trackWidth = design.u(61).toFloat()
    private val trackHeight = design.u(30).toFloat()
    private val thumbRadius = design.u(11).toFloat()
    private var position = 0f
    private var transition: ValueAnimator? = null

    init {
        background = null
        textOn = ""
        textOff = ""
        text = ""
        contentDescription = label
        isChecked = checked
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Switch"

    override fun onDraw(canvas: Canvas) = with(design) {
        val radius = trackHeight / 2
        val left = width - trackWidth
        val center = height / 2f
        canvas.save()
        if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            canvas.translate(width.toFloat(), 0f)
            canvas.scale(-1f, 1f)
        }
        paint.color = colors.evaluate(position, tone(0xc4c4c5, 0x68686b), button) as Int
        canvas.drawRoundRect(left, center - radius, width.toFloat(), center + radius, radius, radius, paint)
        paint.color = colors.evaluate(position, tone(0x676767, 0xd6d6d8), onButton) as Int
        canvas.drawCircle(left + radius + position * (trackWidth - trackHeight), center, thumbRadius, paint)
        canvas.restore()
    }

    override fun setChecked(checked: Boolean) {
        val changed = checked != isChecked
        super.setChecked(checked)
        if (!changed) return
        transition?.cancel()
        val target = if (isChecked) 1f else 0f
        if (!isLaidOut || !isAttachedToWindow || !Motion.enabled) {
            position = target
            invalidate()
            return
        }
        transition = ValueAnimator.ofFloat(position, target).apply {
            duration = 220
            interpolator = Motion.settle
            addUpdateListener {
                position = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        transition?.cancel()
        transition = null
        position = if (isChecked) 1f else 0f
        super.onDetachedFromWindow()
    }
}
