package com.scanwhirl.app.ui.kit

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.scanwhirl.app.R

/**
 * v2 UI: 74_motion — one place for movement, used by every kit component.
 *
 * Tokens: [SHORT] 120 ms (press, chips), [MEDIUM] 220 ms (tabs, sheets, list
 * items), [LONG] 320 ms (drill-in, success). Every duration is multiplied by the
 * system animator scale and becomes 0 when the user turned animations off
 * (Settings > Accessibility > Remove animations): all helpers then skip the
 * animation entirely, so nothing moves for those users.
 */
object SwMotion {

    const val SHORT = 120L
    const val MEDIUM = 220L
    const val LONG = 320L

    /** System animator duration scale (1 = normal, 0 = "Remove animations"). */
    fun animatorScale(context: Context): Float = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
    }.getOrDefault(1f)

    private val isRobolectric = Build.FINGERPRINT.contains("robolectric", ignoreCase = true)

    /**
     * Tests: force motion on/off. Robolectric defaults to OFF (its paused looper
     * would spin forever on an infinite animator and keep every activity alive);
     * motion tests opt in explicitly.
     */
    @Volatile
    internal var animatorOverride: Boolean? = null

    /** True when motion may run: override, then the user's setting, then platform. */
    fun enabled(context: Context): Boolean {
        animatorOverride?.let { return it }
        if (isRobolectric) return false
        // areAnimatorsEnabled() exists from API 26; minSdk is 24 (it would crash on Android 7.x).
        // Below 26 the animator duration scale alone tells us whether the user turned motion off.
        val platformOn = android.os.Build.VERSION.SDK_INT < 26 ||
            android.animation.ValueAnimator.areAnimatorsEnabled()
        return animatorScale(context) > 0f && platformOn
    }

    /** Token duration scaled by the system setting; 0 when animations are off. */
    fun duration(context: Context, baseMs: Long): Long {
        if (!enabled(context)) return 0L
        return (baseMs * animatorScale(context)).toLong().coerceAtLeast(1L)
    }

    // Live ambient animators, so detaching a view always cancels them.
    private val ambient = java.util.WeakHashMap<View, android.animation.ValueAnimator>()

    /** Test hook: the ambient animator currently attached to [view], if any. */
    internal fun ambientAnimator(view: View): android.animation.ValueAnimator? = ambient[view]

    private fun trackAmbient(view: View, animator: android.animation.ValueAnimator) {
        ambient[view] = animator
        if (view.getTag(R.id.swMotionTag) == null) {
            view.setTag(R.id.swMotionTag, true)
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    ambient[v]?.takeIf { !it.isStarted }?.start()
                }

                override fun onViewDetachedFromWindow(v: View) {
                    ambient.remove(v)?.cancel()
                }
            })
        }
        if (view.isAttachedToWindow) animator.start()
    }

    private fun stopAmbient(view: View) {
        ambient.remove(view)?.cancel()
    }

    /** Every tappable kit view: the same 0.97 press (sw_press) + ripple state. */
    fun press(view: View) {
        view.isClickable = true
        if (enabled(view.context)) {
            view.stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(
                view.context, R.animator.sw_press
            )
        }
    }

    /** Tabs: fade-through — old out 90 ms, swap, new in 210 ms from 0.92 scale. */
    fun fadeSwitch(host: ViewGroup, swap: () -> Unit) {
        if (!enabled(host.context) || host.childCount == 0) {
            swap()
            return
        }
        val old = (0 until host.childCount).map { host.getChildAt(it) }
        val out = AnimatorSet()
        out.playTogether(old.map {
            ObjectAnimator.ofFloat(it, View.ALPHA, 1f, 0f).setDuration(duration(host.context, 90))
        })
        out.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                swap()
                host.alpha = 1f
                val first = host.getChildAt(0) ?: return
                first.alpha = 0f
                first.scaleX = 0.92f
                first.scaleY = 0.92f
                first.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(duration(host.context, 210))
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            }
        })
        out.start()
    }

    /** Lists: first load only, staggered fade-up (30 ms each, at most 8 items). */
    fun stagger(container: ViewGroup, stepMs: Long = 30L, max: Int = 8) {
        if (!enabled(container.context)) return
        for (i in 0 until minOf(container.childCount, max)) {
            val child = container.getChildAt(i)
            child.alpha = 0f
            child.translationY = SwStyle.dp(container.context, 10f).toFloat()
            child.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(duration(container.context, stepMs * i))
                .setDuration(duration(container.context, MEDIUM))
                .start()
        }
    }

    /** Screen drill-in for any new Activity (back uses the platform animation). */
    fun drillIn(activity: Activity) {
        if (!enabled(activity)) return
        activity.overridePendingTransition(R.anim.sw_drill_in, R.anim.sw_fade_out_fast)
    }

    /**
     * Success moment: a check inside expanding rings, 600 ms, plus a confirm
     * haptic. Called once by the result screen; a no-op when animations are off
     * (the haptic still plays).
     */
    fun success(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        SwHaptics.confirm(decor)
        if (!enabled(activity)) return
        val d = activity.resources.displayMetrics.density
        val overlay = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = false
            isFocusable = false
        }
        for ((sizeDp, delay) in listOf(110f to 0L, 170f to 60L)) {
            val ring = View(activity).apply {
                background = SwStyle.rounded(
                    activity, Color.TRANSPARENT, radiusDp = 999f,
                    stroke = SwStyle.color(activity, R.color.cyan_primary), strokeDp = 2f
                )
                alpha = 0f
            }
            val px = SwStyle.dp(activity, sizeDp)
            overlay.addView(ring, FrameLayout.LayoutParams(px, px).apply { gravity = Gravity.CENTER })
            ring.animate()
                .alpha(0.85f).setDuration(1L).setStartDelay(0)
                .withEndAction {
                    ring.animate().scaleX(1.5f).scaleY(1.5f).alpha(0f)
                        .setStartDelay(duration(activity, delay))
                        .setDuration(duration(activity, 600L))
                        .start()
                }
                .start()
        }
        val check = ImageView(activity).apply {
            setImageResource(R.drawable.ic_sym_check_circle_filled)
            imageTintList = android.content.res.ColorStateList.valueOf(
                SwStyle.color(activity, R.color.cyan_primary)
            )
            alpha = 0f
            scaleX = 0.6f
            scaleY = 0.6f
        }
        val checkPx = SwStyle.dp(activity, 64f)
        overlay.addView(check, FrameLayout.LayoutParams(checkPx, checkPx).apply { gravity = Gravity.CENTER })
        check.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(duration(activity, 420L))
            .start()
        decor.addView(overlay)
        decor.postDelayed({ (overlay.parent as? ViewGroup)?.removeView(overlay) }, 900L)
        @Suppress("UNUSED_VARIABLE") val unusedDensity = d
    }

    /**
     * Home: the scan disc breathes softly (3 s loop); off when animations are off.
     *
     * Endless animation: it only runs while [view] is attached and is cancelled
     * the moment it leaves the window, so a closed screen can never keep the
     * activity (or a test) alive.
     */
    fun breathe(view: View) {
        stopAmbient(view)
        if (!enabled(view.context)) return
        val pulse = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.86f).apply {
            duration = 3000L
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        trackAmbient(view, pulse)
    }

    /** A spinning mark (progress sheets): endless, attach-scoped and cancellable. */
    fun spin(view: View, periodMs: Long = 1400L) {
        stopAmbient(view)
        if (!enabled(view.context)) return
        val spin = ObjectAnimator.ofFloat(view, View.ROTATION, 0f, 360f).apply {
            duration = periodMs
            repeatCount = ObjectAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
        }
        trackAmbient(view, spin)
    }

    /** Cancel any endless animation started by [breathe]/[spin]/[pulsePlaceholder]. */
    fun stop(view: View) {
        stopAmbient(view)
        view.alpha = 1f
    }

    /** Header mark: its rings turn once on cold start (600 ms). */
    fun rotateBrandOnce(view: View) {
        if (!enabled(view.context)) return
        ObjectAnimator.ofFloat(view, View.ROTATION, 0f, 360f).apply {
            duration = duration(view.context, 600L)
            interpolator = AccelerateDecelerateInterpolator()
        }.start()
    }

    /** Loading thumbnails: a soft alpha placeholder instead of a blank box. */
    fun pulsePlaceholder(view: View, start: Boolean) {
        if (start) {
            if (!enabled(view.context)) return
            if (ambientAnimator(view) != null) return
            val pulse = ObjectAnimator.ofFloat(view, View.ALPHA, 0.35f, 0.6f).apply {
                duration = 900L
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
            }
            trackAmbient(view, pulse)
        } else {
            stopAmbient(view)
            view.alpha = 1f
        }
    }

    /** The brand mark's line-art for empty states, at 30% opacity. */
    fun brandArt(context: Context): ImageView = ImageView(context).apply {
        setImageResource(R.drawable.ic_brand_mark)
        alpha = 0.3f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * v2 UI: 74_motion — the scan disc's whirl: two rings expand out from the
     * disc, then the scanner opens. Off when animations are off (then() runs at
     * once).
     */
    fun expandFrom(anchor: View, then: () -> Unit) {
        if (!enabled(anchor.context)) {
            then()
            return
        }
        val parent = anchor.parent as? ViewGroup ?: run { then(); return }
        val context = anchor.context
        val ring = View(context).apply {
            background = SwStyle.rounded(
                context, Color.TRANSPARENT, radiusDp = 999f,
                stroke = SwStyle.color(context, R.color.cyan_primary), strokeDp = 2f
            )
            alpha = 0.9f
        }
        val params = if (parent is FrameLayout) {
            FrameLayout.LayoutParams(anchor.width, anchor.height).apply {
                leftMargin = anchor.left
                topMargin = anchor.top
            }
        } else {
            ViewGroup.LayoutParams(anchor.width, anchor.height)
        }
        parent.addView(ring, params)
        ring.animate()
            .scaleX(2.2f).scaleY(2.2f).alpha(0f)
            .setDuration(duration(context, 320L))
            .withEndAction {
                (ring.parent as? ViewGroup)?.removeView(ring)
                then()
            }
            .start()
    }

    /**
     * v2 UI: 74_motion — Vault relock countdown: a thin ring around the lock icon
     * drains over [seconds]. [seconds] 0 removes the ring (locked now).
     */
    fun relockRing(anchor: View, seconds: Int = 120) {
        val existing = anchor.tag as? android.animation.ObjectAnimator
        existing?.cancel()
        if (seconds <= 0 || !enabled(anchor.context)) {
            anchor.tag = null
            anchor.background = SwStyle.rounded(anchor.context, Color.TRANSPARENT, radiusDp = 999f)
            return
        }
        val ring = RelockRingDrawable(
            SwStyle.color(anchor.context, R.color.cyan_primary),
            SwStyle.dp(anchor.context, 2f).toFloat()
        )
        anchor.background = ring
        val anim = ObjectAnimator.ofInt(ring, "level", 10000, 0).apply {
            duration = seconds * 1000L
            interpolator = android.view.animation.LinearInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    anchor.background = SwStyle.rounded(
                        anchor.context, Color.TRANSPARENT, radiusDp = 999f
                    )
                }
            })
        }
        anchor.tag = anim
        anim.start()
    }

    /** A thin ring that drains with its level (Vault relock). */
    class RelockRingDrawable(
        private val colour: Int,
        private val stroke: Float
    ) : android.graphics.drawable.Drawable() {

        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = stroke
            this.color = colour
        }

        override fun draw(canvas: android.graphics.Canvas) {
            val inset = stroke
            val oval = android.graphics.RectF(
                bounds.left + inset, bounds.top + inset,
                bounds.right - inset, bounds.bottom - inset
            )
            paint.alpha = 60
            canvas.drawArc(oval, 0f, 360f, false, paint)
            paint.alpha = 255
            canvas.drawArc(oval, -90f, 360f * (level / 10000f), false, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** Android 12+ haptics are available through the view path only (no permission). */
    internal fun supportsRichHaptics(): Boolean = Build.VERSION.SDK_INT >= 30
}
