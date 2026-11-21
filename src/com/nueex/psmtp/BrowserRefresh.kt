/* Copyright (C) 2026 nueex. SPDX-License-Identifier: GPL-3.0-or-later */
package com.nueex.psmtp

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ProgressBar
import kotlin.math.abs

internal class BrowserRefresh(design: Design, content: View, private val indicatorTop: () -> Int,
                              private val canRefresh: () -> Boolean, private val reload: () -> Unit) : FrameLayout(design.context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val threshold = design.u(64).toFloat()
    private val spinner = ProgressBar(context).apply {
        background = design.round(design.elevated, 28)
        val inset = design.u(9)
        setPadding(inset, inset, inset, inset)
        indeterminateTintList = ColorStateList.valueOf(design.ink)
        elevation = design.u(3).toFloat()
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.INVISIBLE
    }
    private val refreshTimeout = Runnable { finish() }
    private var eligible = false
    private var pulling = false
    private var startX = 0f
    private var startY = 0f
    private var distance = 0f
    private var refreshing = false

    init {
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val size = design.u(46)
        addView(spinner, LayoutParams(size, size, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (pulling) return dispatchPull(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                eligible = !refreshing && canRefresh()
            }
            MotionEvent.ACTION_MOVE -> if (eligible) {
                val dx = abs(event.x - startX)
                val dy = event.y - startY
                if (dx > slop && dx >= abs(dy) || dy < -slop || !canRefresh()) eligible = false
                else if (dy > slop && dy > dx) {
                    // Web content may disable interception. Claim a vertical pull here and cancel its touch stream.
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    pulling = true
                    pull(dy)
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> eligible = false
        }
        return super.dispatchTouchEvent(event) || eligible
    }

    private fun dispatchPull(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> pull(event.y - startY)
            MotionEvent.ACTION_UP -> {
                pull(event.y - startY)
                eligible = false
                pulling = false
                if (distance >= threshold && canRefresh()) {
                    refreshing = true
                    spinner.animate().cancel()
                    val target = indicatorTop() + threshold - spinner.height
                    if (Motion.enabled) spinner.animate().alpha(1f).translationY(target)
                        .setDuration(160).setInterpolator(Motion.settle).start()
                    else {
                        spinner.alpha = 1f
                        spinner.translationY = target
                    }
                    postDelayed(refreshTimeout, 15_000)
                    reload()
                } else finish()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> finish()
        }
        return true
    }

    private fun pull(dy: Float) {
        distance = ((dy - slop) * .55f).coerceIn(0f, threshold * 1.4f)
        spinner.animate().cancel()
        spinner.visibility = View.VISIBLE
        spinner.alpha = (distance / threshold).coerceIn(0f, 1f)
        spinner.translationY = indicatorTop() + distance - spinner.height
    }

    fun pageFinished() {
        if (refreshing) finish()
    }

    fun finish() {
        removeCallbacks(refreshTimeout)
        refreshing = false
        eligible = false
        pulling = false
        distance = 0f
        spinner.animate().cancel()
        if (spinner.visibility != View.VISIBLE || !Motion.enabled) spinner.visibility = View.INVISIBLE
        else spinner.animate().alpha(0f).translationY((indicatorTop() - spinner.height).toFloat())
            .setDuration(160).setInterpolator(Motion.settle)
            .withEndAction { spinner.visibility = View.INVISIBLE }.start()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refreshTimeout)
        spinner.animate().cancel()
        super.onDetachedFromWindow()
    }
}
