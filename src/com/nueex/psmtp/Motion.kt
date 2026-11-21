/* Copyright (C) 2026 nueex. SPDX-License-Identifier: GPL-3.0-or-later */
package com.nueex.psmtp

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout

internal object Motion {
    val enabled get() = ValueAnimator.areAnimatorsEnabled()
    val settle = PathInterpolator(.22f, 1f, .36f, 1f)
    val leave = PathInterpolator(.4f, 0f, 1f, 1f)

    fun switchPage(container: FrameLayout, previous: View, next: View, offset: Float) {
        previous.animate().cancel()
        container.overlay.add(previous)
        next.translationX = offset
        next.alpha = 0f
        next.animate().translationX(0f).alpha(1f).setDuration(290).setInterpolator(settle).start()
        previous.animate().translationX(-offset / 2).alpha(0f).setDuration(220).setInterpolator(settle)
            .withEndAction { container.overlay.remove(previous) }.start()
    }
}
