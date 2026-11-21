/* Copyright (C) 2026 nueex. SPDX-License-Identifier: GPL-3.0-or-later */
package com.nueex.psmtp

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.SeekBar

internal class SteppedSeekBar(private val design: Design, lastStep: Int, selected: Int) : SeekBar(design.context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val railColor = design.tone(0xc8c8ce, 0x56565d)
    private val inactiveTick = design.tone(0x747479, 0xa3a3ab)
    private val activeTick = design.tone(0xededf0, 0x252527)
    private var position = selected.toFloat()
    private var emphasis = 0f
    private var transition: ValueAnimator? = null

    init {
        max = lastStep
        keyProgressIncrement = 1
        progress = selected
        thumb = null
        progressDrawable = null
        background = null
        val inset = design.u(18)
        setPadding(inset, 0, inset, 0)
    }

    fun animateTo(step: Int, pressed: Boolean) {
        val from = position
        val fromEmphasis = emphasis
        val to = step.toFloat()
        val toEmphasis = if (pressed) 1f else 0f
        transition?.cancel()
        if (!isLaidOut || !Motion.enabled) {
            position = to
            emphasis = toEmphasis
            invalidate()
        } else {
            transition = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 165
                interpolator = Motion.settle
                addUpdateListener {
                    val fraction = it.animatedValue as Float
                    position = from + (to - from) * fraction
                    emphasis = fromEmphasis + (toEmphasis - fromEmphasis) * fraction
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val start = if (rtl) right else left
        val end = if (rtl) left else right
        val spacing = (end - start) / max
        val center = height / 2f
        val handle = start + position * spacing
        paint.strokeWidth = design.u(5).toFloat()
        paint.color = railColor
        canvas.drawLine(left, center, right, center, paint)
        paint.color = design.ink
        canvas.drawLine(start, center, handle, center, paint)
        for (step in 0..max) {
            paint.color = if (step <= position) activeTick else inactiveTick
            canvas.drawCircle(start + step * spacing, center, design.u(2).toFloat(), paint)
        }
        paint.color = design.ink
        canvas.drawCircle(handle, center, design.u(7) + design.u(3) * emphasis, paint)
    }

    override fun onDetachedFromWindow() {
        transition?.cancel()
        transition = null
        position = progress.toFloat()
        emphasis = 0f
        super.onDetachedFromWindow()
    }
}
