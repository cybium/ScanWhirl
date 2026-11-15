package com.scanwhirl.app.ui.kit

import android.content.Context
import androidx.recyclerview.widget.DefaultItemAnimator

/**
 * v2 UI: 74_motion — the kit list animator: add/remove/move/change run on the
 * motion tokens (220 ms), and every duration is 0 when the user turned
 * animations off.
 */
class SwItemAnimator(context: Context) : DefaultItemAnimator() {
    init {
        addDuration = SwMotion.duration(context, SwMotion.MEDIUM)
        removeDuration = SwMotion.duration(context, SwMotion.MEDIUM)
        moveDuration = SwMotion.duration(context, SwMotion.MEDIUM)
        changeDuration = SwMotion.duration(context, SwMotion.MEDIUM)
        supportsChangeAnimations = true
    }
}
