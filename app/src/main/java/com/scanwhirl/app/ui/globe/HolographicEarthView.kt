package com.scanwhirl.app.ui.globe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.ui.pagegrid.PageThumbLoader
import com.scanwhirl.app.util.CategoryStyle
import com.scanwhirl.app.util.ScanFilter
import com.scanwhirl.app.util.SwMotion
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Custom Canvas renderer for the holographic globe home — no OpenGL, no 3D
 * library, no WebView. A straight Kotlin port of the HTML prototype:
 *
 * - deep-space background with a twinkling star field,
 * - a dotted Earth (Fibonacci sphere + Natural Earth land mask) rotating
 *   1 turn / 60 s with drag inertia, latitude rings, scanline shimmer,
 * - five category rings whose cards depth-sort around the ellipse,
 * - locked/vaulted cards pinned to the far side as glyph-only,
 * - search dimming non-matching cards to 12% alpha,
 * - home-state cards orbiting the globe.
 *
 * Rendering runs on a 30 fps Choreographer loop that stops whenever the
 * screen is not showing it. Dots are batched into ~10 [Canvas.drawPoints]
 * calls with quantized depth buckets — never 2,200 arcs per frame — and no
 * objects are allocated inside [onDraw].
 */
class HolographicEarthView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        /** Single tap on the Earth: expand/collapse the rings. */
        fun onGlobeTapped()

        /** Single tap on a document card (never fires for hidden cards). */
        fun onCardTapped(docId: String)

        /** Pinch-in: cross-fade into the Shelf (MainActivity). */
        fun onPinchToShelf()

        /** Pull down far enough: open the search panel. */
        fun onPullSearch()
    }

    var listener: Listener? = null

    /** Bottom gesture hint text (driven by the activity). */
    var hintText: String = ""

    /**
     * test25: the hint now lives in the `globeHint` pill (XML), so the
     * canvas copy is off by default. The property itself stays — tests
     * assert `hintText` directly — and the pill mirrors it in the activity.
     */
    var drawHintInCanvas: Boolean = false

    /** Pixels of HUD to reserve at the top; forwarded to [GlobeScene]. */
    var topReserve: Float
        get() = scene.topReserve
        set(value) {
            if (scene.topReserve != value) {
                scene.topReserve = value
                invalidate()
            }
        }

    val scene: GlobeScene

    // Thumbs for card backgrounds, keyed by doc id.
    private val thumbs = HashMap<String, Bitmap?>(64)
    private val thumbRequested = HashSet<String>()
    private val docFiles = HashMap<String, File>(64)

    // Reusable objects for the draw loop (zero allocation per frame).
    private val scratch = FloatArray(GlobeScene.DOTS * 2)
    private val dotSx = FloatArray(GlobeScene.DOTS)
    private val dotSy = FloatArray(GlobeScene.DOTS)
    private val dotZ = FloatArray(GlobeScene.DOTS)
    private val dotBucket = IntArray(GlobeScene.DOTS) // -1 back, 0..3 front, 9 glow
    private val latPath = Path()
    private val cardRect = RectF()
    private val accentRect = RectF()
    private val hitCards = ArrayList<HitCard>(48)

    private class HitCard(
        val docId: String, val cx: Float, val cy: Float,
        val halfW: Float, val halfH: Float, val back: Boolean
    )

    // Paints (colors mutated per bucket; never reallocated).
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val latPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shimmerPaint = Paint()
    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val cardTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val cardTagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.argb(115, 140, 210, 240)
    }
    private val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(230, 0, 240, 255)
    }

    // test25: ring tracks, ring labels and card accent bars.
    private val ringTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val ringLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringTrackRect = RectF()
    private val ringTrackPath = Path()
    private val ringLabelPath = Path()

    /**
     * Dash pattern lives in `u` units (width-dependent), so the effect is
     * rebuilt only when the view's width changes — never per frame.
     */
    private var ringDash: android.graphics.DashPathEffect? = null
    private var ringDashU = -1f

    // Animation loop -------------------------------------------------------
    private var animating = false
    private var lastFrameNanos = 0L
    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animating) return
            choreographer.postFrameCallback(this)
            if (lastFrameNanos == 0L) {
                lastFrameNanos = frameTimeNanos
                return
            }
            val elapsedMs = (frameTimeNanos - lastFrameNanos) / 1_000_000L
            if (elapsedMs < FRAME_MS) return // cap at ~30 fps
            lastFrameNanos = frameTimeNanos
            scene.update((elapsedMs / 1000f).coerceAtMost(0.1f))
            invalidate()
        }
    }

    // Gestures -------------------------------------------------------------
    private enum class TouchMode { NONE, PULL, GLOBE, RING }

    private var mode = TouchMode.NONE
    private var pullStartY = 0f
    private var pullDist = 0f
    private var scaleAccum = 1f
    private var pinchConsumed = false

    /** How far the user has pulled (0..1) — drawn as the pull hint. */
    var pullProgress = 0f
        private set

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                when (mode) {
                    TouchMode.GLOBE -> {
                        scene.earthRotY += dx * 0.012f
                        scene.earthRotX = (scene.earthRotX + dy * 0.008f).coerceIn(-0.6f, 0.6f)
                        scene.earthVelY = dx * 0.012f
                        scene.earthVelX = dy * 0.008f
                    }
                    TouchMode.RING -> {
                        val ring = scene.draggingRing
                        if (ring >= 0) {
                            val g = scene.ringGeometry(ring, width.toFloat(), height.toFloat())
                            val prevX = e2.x + dx // e2 is the current event; dx = current-prev
                            val prevY = e2.y + dy
                            val prev = kotlin.math.atan2(
                                (prevY - g.cy), (prevX - g.cx) * (g.ry / g.rx)
                            )
                            val cur = kotlin.math.atan2(
                                (e2.y - g.cy), (e2.x - g.cx) * (g.ry / g.rx)
                            )
                            var dAngle = cur - prev
                            if (dAngle > PI) dAngle -= (2 * PI).toFloat()
                            if (dAngle < -PI) dAngle += (2 * PI).toFloat()
                            scene.ringAngles[ring] += dAngle
                            scene.ringVels[ring] = dAngle
                        }
                    }
                    else -> Unit // PULL is handled in onTouchEvent
                }
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (pinchConsumed) return false
                handleTap(e.x, e.y)
                return true
            }
        }
    )

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleAccum *= detector.scaleFactor
                if (!pinchConsumed && scaleAccum < 0.8f) {
                    pinchConsumed = true
                    listener?.onPinchToShelf()
                }
                return true
            }
        }
    )

    init {
        val mask = context.assets.open("land_mask.bin").use { it.readBytes() }
        scene = GlobeScene(mask)
        isClickable = true
    }

    // --- Public API -------------------------------------------------------

    /** Feeds documents into the scene and starts loading card thumbnails. */
    fun bindDocs(docs: List<ScanDocument>) {
        scene.setDocs(docs)
        docFiles.clear()
        for (doc in docs) {
            doc.pageFiles.firstOrNull()?.let { docFiles[doc.id] = it }
        }
        for (doc in docs) {
            if (doc.isLocked || doc.isVaulted) continue // hidden: no thumbnail ever
            if (thumbs.containsKey(doc.id) || !thumbRequested.add(doc.id)) continue
            val file = docFiles[doc.id] ?: continue
            PageThumbLoader.loadAsync(file, THUMB_PX, ScanFilter.ORIGINAL) { bmp ->
                if (thumbRequested.contains(doc.id)) {
                    thumbs[doc.id] = bmp
                    invalidate()
                }
            }
        }
        invalidate()
    }

    /** Drops thumbnails for docs that changed (after edit/rotate). */
    fun clearThumb(docId: String) {
        thumbs.remove(docId)
        thumbRequested.remove(docId)
    }

    fun setQuery(q: String) {
        if (scene.query != q) {
            scene.query = q
            invalidate()
        }
    }

    /**
     * 30 fps loop on/off — the activity calls this from onResume/onPause.
     *
     * Never started under Robolectric: the shadow Choreographer runs vsync
     * callbacks eagerly inside looper `idle()`, so a self-rescheduling frame
     * callback would spin there forever (real devices throttle to vsync).
     * Scene behaviour is unit-tested by driving [GlobeScene.update] directly.
     */
    /**
     * test25 Phase 4: pull the ambient-motion gate into the scene. Called from
     * the activity's onResume so returning from system Settings with
     * animations switched off stops the rotation/drift/twinkle/shimmer too.
     * The 30fps loop itself keeps running — it also drives the stateT easing
     * that makes taps respond with animations disabled.
     */
    fun syncAmbientMotion() {
        scene.ambientMotion = SwMotion.ambientAllowed(context)
    }

    fun setAnimating(on: Boolean) {
        val enable = on && !UNDER_ROBOLECTRIC
        if (enable == animating) return
        animating = enable
        if (enable) {
            lastFrameNanos = 0L
            choreographer.postFrameCallback(frameCallback)
        } else {
            choreographer.removeFrameCallback(frameCallback)
        }
    }

    override fun onDetachedFromWindow() {
        setAnimating(false)
        super.onDetachedFromWindow()
    }

    // --- Input ------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = decideMode(event.x, event.y)
                pullStartY = event.y
                pullDist = 0f
                pullProgress = 0f
                pinchConsumed = false
                scaleAccum = 1f
                if (mode == TouchMode.GLOBE) {
                    scene.draggingGlobe = true
                    scene.earthVelY = 0f
                    scene.earthVelX = 0f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == TouchMode.PULL) {
                    pullDist = max(0f, event.y - pullStartY)
                    pullProgress = min(1f, pullDist / (height * PULL_THRESHOLD))
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == TouchMode.PULL && pullDist > height * PULL_THRESHOLD) {
                    listener?.onPullSearch()
                }
                pullProgress = 0f
                mode = TouchMode.NONE
                scene.draggingGlobe = false
                scene.draggingRing = -1
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    performClick()
                }
                invalidate()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun decideMode(x: Float, y: Float): TouchMode {
        val h = height.toFloat()
        val w = width.toFloat()
        if (y < h * PULL_START) return TouchMode.PULL
        val g = scene.globeGeometry(w, h)
        if (sqrt((x - g.cx).sq() + (y - g.cy).sq()) <= g.r * 1.15f) {
            return TouchMode.GLOBE
        }
        if (scene.state == GlobeScene.STATE_RINGS && scene.stateT > 0.6f) {
            var best = -1
            var bestDist = Float.MAX_VALUE
            for (r in 0 until GlobeScene.RING_COUNT) {
                val d = abs(scene.ringOutlineDistance(r, x, y, w, h) - 1f)
                if (d < 0.22f && d < bestDist) {
                    best = r
                    bestDist = d
                }
            }
            if (best >= 0) {
                scene.draggingRing = best
                scene.ringVels[best] = 0f
                return TouchMode.RING
            }
        }
        return TouchMode.NONE
    }

    private fun handleTap(x: Float, y: Float) {
        // Front cards first (drawn on top), then far-side cards that are not
        // hidden behind the Earth, then the Earth itself.
        for (i in hitCards.indices.reversed()) {
            val c = hitCards[i]
            if (c.back) continue
            if (abs(x - c.cx) <= c.halfW && abs(y - c.cy) <= c.halfH) {
                listener?.onCardTapped(c.docId)
                return
            }
        }
        val g = scene.globeGeometry(width.toFloat(), height.toFloat())
        val inGlobe = sqrt((x - g.cx).sq() + (y - g.cy).sq()) <= g.r * 1.15f
        if (!inGlobe) {
            for (i in hitCards.indices.reversed()) {
                val c = hitCards[i]
                if (!c.back) continue
                if (abs(x - c.cx) <= c.halfW && abs(y - c.cy) <= c.halfH) {
                    listener?.onCardTapped(c.docId)
                    return
                }
            }
        }
        if (inGlobe) listener?.onGlobeTapped()
    }

    private fun Float.sq(): Float = this * this

    // --- Drawing ----------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        hitCards.clear()

        val now = scene.time

        // (A) Stars
        drawStars(canvas, w, h, now)

        val ringAlpha = scene.stateT
        val orbitAlpha = 1f - scene.stateT

        // (A.5) Dashed category ring tracks + arc labels (test25, rings only).
        if (ringAlpha > 0.02f) drawRingTracks(canvas, w, h, ringAlpha)

        // (B) Far-side cards go behind the Earth (depth-correct).
        if (ringAlpha > 0.02f) drawRingCards(canvas, w, h, ringAlpha, back = true)

        // (C) Earth
        if (orbitAlpha > 0.02f || ringAlpha > 0.02f) drawEarth(canvas, w, h, now)

        // (D) Home orbit cards
        if (orbitAlpha > 0.02f) drawOrbitCards(canvas, w, h, orbitAlpha)

        // (E) Near-side ring cards
        if (ringAlpha > 0.02f) drawRingCards(canvas, w, h, ringAlpha, back = false)

        // (F) Pull hint + gesture hint
        if (pullProgress > 0.01f) drawPullHint(canvas, w)
        if (drawHintInCanvas && hintText.isNotEmpty()) {
            hintPaint.textSize = 10f * u(w)
            canvas.drawText(hintText, w / 2f, h - 10f * u(w), hintPaint)
        }
    }

    /** Density-ish unit: prototype pixels were authored on a 360px frame. */
    private fun u(w: Float): Float = w / 360f

    private fun drawStars(canvas: Canvas, w: Float, h: Float, now: Float) {
        dotPaint.style = Paint.Style.FILL
        for (s in scene.stars) {
            // Phase 4: twinkle is ambient motion — hold a fixed level when off.
            val twinkle = if (scene.ambientMotion) {
                sin(now * s.speed + s.phase) * 0.3f + 0.7f
            } else {
                0.85f
            }
            dotPaint.color = Color.argb(
                (s.alpha * twinkle * 255).toInt().coerceIn(0, 255), 180, 230, 255
            )
            val size = s.size * (w / 360f)
            canvas.drawRect(s.x * w, s.y * h, s.x * w + size, s.y * h + size, dotPaint)
        }
        dotPaint.style = Paint.Style.STROKE
    }

    /**
     * test25: the five category rings as dashed ellipses (4u dash / 4u gap,
     * 0.8u stroke, category colour at α0.30) with the category tag riding the
     * lower arc bottom-centred, counter-clockwise so it reads upright (9u caps,
     * α0.65). Drawn in the RINGS state only,
     * scaled by [alpha] (= stateT), under the cards and the Earth.
     */
    private fun drawRingTracks(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val unit = u(w)
        if (ringDashU != unit) {
            ringDashU = unit
            ringDash = android.graphics.DashPathEffect(floatArrayOf(4f * unit, 4f * unit), 0f)
        }
        ringTrackPaint.pathEffect = ringDash
        ringTrackPaint.strokeWidth = 0.8f * unit
        ringLabelPaint.textSize = 9f * unit

        for (ring in 0 until GlobeScene.RING_COUNT) {
            val g = scene.ringGeometry(ring, w, h)
            ringTrackRect.set(g.cx - g.rx, g.cy - g.ry, g.cx + g.rx, g.cy + g.ry)
            val color = GlobeRingShim.colorForRing(ring)

            ringTrackPath.rewind()
            ringTrackPath.addOval(ringTrackRect, Path.Direction.CW)
            // Phase 4: ambient shimmer — a slow alpha breathe, phase-offset per
            // ring so the five tracks don't pulse in lockstep. Held flat (and
            // screenshot-deterministic) when ambient motion is off.
            val shimmer = if (scene.ambientMotion) {
                sin(scene.time * 1.6f + ring * 1.1f) * 0.07f
            } else {
                0f
            }
            ringTrackPaint.color = withAlpha(color, (0.30f + shimmer) * alpha)
            canvas.drawPath(ringTrackPath, ringTrackPaint)

            // Label along the lower arc, swept counter-clockwise (125° → 55°
            // through the bottom) so glyphs run left-to-right and read upright.
            // vOffset moves the baseline along the path normal: with this sweep
            // positive = outward, so the text sits just outside the dashed
            // stroke instead of colliding with the ring interior. hOffset
            // centres each tag on the bottom of its ellipse.
            ringLabelPath.rewind()
            ringLabelPath.addArc(ringTrackRect, 125f, -70f)
            ringLabelPaint.color = withAlpha(color, 0.65f * alpha)
            val tag = GlobeRingShim.tagForRing(ring)
            val len = android.graphics.PathMeasure(ringLabelPath, false).length
            val hOff = ((len - ringLabelPaint.measureText(tag)) / 2f).coerceAtLeast(0f)
            canvas.drawTextOnPath(tag, ringLabelPath, hOff, 8.2f * unit, ringLabelPaint)
        }
    }

    private fun drawEarth(canvas: Canvas, w: Float, h: Float, now: Float) {
        val g = scene.globeGeometry(w, h)
        val cx = g.cx
        val cy = g.cy
        val r = g.r
        if (r < 4f) return
        val unit = u(w)

        // Soft halo
        haloPaint.shader = LinearGradient(
            0f, cy - r * 1.35f, 0f, cy + r * 1.35f,
            intArrayOf(
                Color.argb(0, 0, 100, 255),
                Color.argb(20, 0, 240, 255),
                Color.argb(56, 0, 240, 255),
                Color.argb(0, 0, 100, 255)
            ),
            floatArrayOf(0f, 0.25f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 1.35f, haloPaint)

        // Latitude rings (equator brighter).
        val cosY = cos(scene.earthRotY)
        val sinY = sin(scene.earthRotY)
        val cosX = cos(scene.earthRotX)
        val sinX = sin(scene.earthRotX)
        for (latDeg in LATITUDES) {
            val radLat = Math.toRadians(latDeg.toDouble())
            val circleY = sin(radLat).toFloat()
            val circleR = cos(radLat).toFloat()
            latPath.rewind()
            for (s in 0..LAT_STEPS) {
                val theta = (s / LAT_STEPS.toFloat()) * 2f * PI.toFloat()
                val x0 = circleR * cos(theta)
                val z0 = circleR * sin(theta)
                val x1 = x0 * cosY - z0 * sinY
                val z1 = x0 * sinY + z0 * cosY
                val y2 = circleY * cosX - z1 * sinX
                val sx = cx + x1 * r
                val sy = cy - y2 * r
                if (s == 0) latPath.moveTo(sx, sy) else latPath.lineTo(sx, sy)
            }
            latPaint.strokeWidth = if (latDeg == 0.0) 1f * unit else 0.6f * unit
            latPaint.color = if (latDeg == 0.0) {
                Color.argb(71, 0, 240, 255)
            } else {
                Color.argb(31, 0, 210, 255)
            }
            canvas.drawPath(latPath, latPaint)
        }

        // Dots: rotate once, then batch into depth buckets.
        drawDots(canvas, cx, cy, r, cosY, sinY, cosX, sinX)

        // Scanline shimmer
        val scanPhase = (now * 0.55f) % 1f
        val scanY = cy - r + scanPhase * 2f * r
        val half = sqrt((r * r - (scanY - cy) * (scanY - cy)).coerceAtLeast(0f))
        if (half > 4f * unit) {
            shimmerPaint.shader = LinearGradient(
                cx - half, scanY, cx + half, scanY,
                intArrayOf(Color.TRANSPARENT, Color.argb(71, 0, 245, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(cx - half, scanY - 2f * unit, cx + half, scanY + 2f * unit, shimmerPaint)
        }

        // Rim
        latPaint.strokeWidth = 1f * unit
        latPaint.color = Color.argb(56, 0, 240, 255)
        canvas.drawCircle(cx, cy, r, latPaint)
    }

    private fun drawDots(
        canvas: Canvas, cx: Float, cy: Float, r: Float,
        cosY: Float, sinY: Float, cosX: Float, sinX: Float
    ) {
        var glowCount = 0
        for (i in 0 until GlobeScene.DOTS) {
            val x1 = scene.dotX[i] * cosY - scene.dotZ[i] * sinY
            val z1 = scene.dotX[i] * sinY + scene.dotZ[i] * cosY
            val y2 = scene.dotY[i] * cosX - z1 * sinX
            val z2 = scene.dotY[i] * sinX + z1 * cosX
            dotSx[i] = cx + x1 * r
            dotSy[i] = cy - y2 * r
            dotZ[i] = z2
            val depth = (z2 + 1f) * 0.5f
            dotBucket[i] = if (z2 < 0f) {
                if (scene.dotLand[i]) -2 else -1 // back land / back ocean
            } else {
                val level = (depth * 4f).toInt().coerceIn(0, 3)
                when {
                    !scene.dotLand[i] -> if (depth > 0.5f) 5 else 4 // ocean front
                    level == 3 && scene.dotSeed[i] > 0.65f -> {
                        glowCount++
                        9 // land glow
                    }
                    else -> level
                }
            }
        }

        fun bucket(id: Int, size: Float, color: Int) {
            var n = 0
            for (i in 0 until GlobeScene.DOTS) {
                if (dotBucket[i] != id) continue
                scratch[n * 2] = dotSx[i]
                scratch[n * 2 + 1] = dotSy[i]
                n++
            }
            if (n == 0) return
            dotPaint.strokeWidth = size
            dotPaint.color = color
            canvas.drawPoints(scratch, 0, n * 2, dotPaint)
        }

        val unit = u(width.toFloat())
        // Land, front, 4 depth levels.
        bucket(0, 2f * 1.50f * unit, Color.argb(166, 0, 245, 255))
        bucket(1, 2f * 1.67f * unit, Color.argb(197, 0, 245, 255))
        bucket(2, 2f * 1.83f * unit, Color.argb(224, 0, 245, 255))
        bucket(3, 2f * 2.00f * unit, Color.argb(255, 0, 245, 255))
        // Prominent-dot glow.
        if (glowCount > 0) bucket(9, 2f * 4.2f * unit, Color.argb(89, 0, 240, 255))
        // Land, back (2 levels).
        bucket(-2, 2f * 1.0f * unit, Color.argb(71, 0, 200, 230))
        // Ocean: front (2 levels) + back.
        bucket(5, 2f * 0.9f * unit, Color.argb(46, 0, 110, 180))
        bucket(4, 2f * 0.9f * unit, Color.argb(28, 0, 110, 180))
        bucket(-1, 2f * 0.7f * unit, Color.argb(10, 0, 110, 180))
    }

    private fun drawOrbitCards(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val orbit = scene.orbitCards
        if (orbit.isEmpty()) return
        val g = scene.globeGeometry(w, h)
        // test25: clamp so the widest orbit card keeps a 6u gutter on screen.
        val rx = scene.homeOrbitRx(w, g.r, 31f * u(w) * 1.15f)
        val ry = g.r * 0.50f
        val baseAngle = scene.time * 0.35f
        // Two passes: behind the globe, then in front (depth without sorting).
        for (front in intArrayOf(0, 1)) {
            for ((idx, card) in orbit.withIndex()) {
                val theta = baseAngle + idx * (2f * PI.toFloat() / orbit.size)
                val ox = cos(theta) * rx
                val oz = sin(theta)
                val oy = sin(theta) * ry + ox * 0.25f
                val z = oz
                if ((z >= 0f) != (front == 1)) continue
                val depth = (z + 1f) * 0.5f
                val scale = 0.8f + depth * 0.35f
                val cardAlpha =
                    alpha * (if (z < 0f) 0.35f else 0.95f) * scene.ringAlphaFor(card)
                if (cardAlpha < 0.04f) continue
                drawCard(
                    canvas, card, g.cx + ox, g.cy + oy,
                    62f * scale * u(w), 42f * scale * u(w),
                    cardAlpha, matched = true, scale = scale * u(w), back = z < 0f,
                    front = z >= 0f, recordHit = z >= 0f && alpha > 0.6f
                )
            }
        }
    }

    private fun drawRingCards(canvas: Canvas, w: Float, h: Float, alpha: Float, back: Boolean) {
        for (card in scene.cards) {
            if (card.orbit) continue
            val pos = scene.cardPosition(card, w, h)
            val x = pos[0]
            val y = pos[1]
            val z = pos[2]
            if ((z < 0f) != back) continue
            // Hidden cards only ever live on the far arc; never in front.
            if (card.hidden && z >= 0f) continue
            val matched = scene.matches(card)
            var cardAlpha = scene.alphaFor(card, z) * scene.ringAlphaFor(card) * alpha
            // A search that misses dims hard; a far-side miss dims twice.
            if (cardAlpha < 0.03f) continue
            val depth = (z + 1f) * 0.5f
            val scale = 0.72f + depth * 0.32f
            drawCard(
                canvas, card, x, y,
                58f * scale * u(w), 42f * scale * u(w),
                cardAlpha, matched, scale * u(w), back, !back,
                recordHit = !back && alpha > 0.6f
            )
        }
    }

    private fun drawCard(
        canvas: Canvas, card: GlobeScene.Card, cx: Float, cy: Float,
        w: Float, h: Float, alpha: Float, matched: Boolean, scale: Float,
        back: Boolean, front: Boolean, recordHit: Boolean
    ) {
        val a = alpha.coerceIn(0f, 1f)
        cardRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)

        // Body: thumbnail when we have one, glass panel otherwise.
        val thumb = if (card.hidden) null else thumbs[card.docId]
        if (thumb != null && !thumb.isRecycled) {
            // Cover-fit the thumbnail inside the card, then scrim it.
            val s = max(w / thumb.width, h / thumb.height)
            val dw = thumb.width * s
            val dh = thumb.height * s
            canvas.save()
            canvas.clipRect(cardRect)
            canvas.drawBitmap(
                thumb, null,
                RectF(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f),
                null
            )
            cardBgPaint.color = Color.argb((140 * a).toInt().coerceIn(0, 255), 6, 18, 34)
            canvas.drawRect(cardRect, cardBgPaint)
            canvas.restore()
        } else {
            cardBgPaint.color = Color.argb((230 * a).toInt().coerceIn(0, 255), 8, 20, 36)
            canvas.drawRoundRect(cardRect, 6f * scale, 6f * scale, cardBgPaint)
        }

        // Border: mint highlight when a search matches this card.
        if (matched && scene.query.isNotBlank()) {
            cardBorderPaint.color = Color.argb((242 * a).toInt().coerceIn(0, 255), 0, 255, 200)
            cardBorderPaint.strokeWidth = 1.8f * scale
        } else {
            cardBorderPaint.color = Color.argb((166 * a).toInt().coerceIn(0, 255), 0, 240, 255)
            cardBorderPaint.strokeWidth = 1f * scale
        }
        canvas.drawRoundRect(cardRect, 6f * scale, 6f * scale, cardBorderPaint)

        if (card.hidden) {
            drawLockGlyph(canvas, cx, cy, scale, a)
        } else {
            // test25: 3u category accent bar hugging the left edge (reused
            // RectF — no allocation inside onDraw).
            accentPaint.color = withAlpha(CategoryStyle.color(card.category), a)
            accentRect.set(
                cardRect.left + 1.5f * scale,
                cardRect.top + 5f * scale,
                cardRect.left + 4.5f * scale,
                cardRect.bottom - 5f * scale
            )
            canvas.drawRoundRect(accentRect, 1.5f * scale, 1.5f * scale, accentPaint)

            // Category tag (top-left) — colour doubles as the ring legend.
            cardTagPaint.color = withAlpha(CategoryStyle.color(card.category), a)
            cardTagPaint.textSize = 7.5f * scale
            val tagX = cardRect.left + 7f * scale
            val tagY = cardRect.top + 11f * scale
            canvas.drawText(GlobeRingShim.shortTag(card.category), tagX, tagY, cardTagPaint)

            // Title, ellipsised to one line like the prototype.
            cardTextPaint.color = Color.argb((242 * a).toInt().coerceIn(0, 255), 225, 245, 255)
            cardTextPaint.textSize = 7.5f * scale
            val maxChars = max(6, (w / (cardTextPaint.textSize * 0.56f)).toInt())
            val title = if (card.title.length > maxChars) {
                card.title.take(maxChars - 2) + "…"
            } else {
                card.title
            }
            canvas.drawText(title, tagX, cardRect.top + 23f * scale, cardTextPaint)

            // Fake doc lines when there is no thumbnail (prototype style).
            if (thumb == null || thumb.isRecycled) {
                cardBgPaint.color = Color.argb((89 * a).toInt().coerceIn(0, 255), 0, 240, 255)
                canvas.drawRect(
                    tagX, cardRect.top + 27f * scale,
                    tagX + w * 0.7f, cardRect.top + 28.5f * scale, cardBgPaint
                )
                canvas.drawRect(
                    tagX, cardRect.top + 32f * scale,
                    tagX + w * 0.5f, cardRect.top + 33.5f * scale, cardBgPaint
                )
            }
        }

        // Search hit marker.
        if (matched && scene.query.isNotBlank() && !card.hidden) {
            cardBgPaint.color = withAlpha(0xFF00F0FF.toInt(), a)
            canvas.drawCircle(
                cardRect.right - 8f * scale, cardRect.top + 8f * scale,
                2.5f * scale, cardBgPaint
            )
        }

        // HUD telemetry reticle ticks on right corners
        if (!card.hidden && a > 0.3f) {
            val tick = 4f * scale
            canvas.drawLine(cardRect.right - tick, cardRect.top + 2f * scale, cardRect.right - 2f * scale, cardRect.top + 2f * scale, cardBorderPaint)
            canvas.drawLine(cardRect.right - 2f * scale, cardRect.top + 2f * scale, cardRect.right - 2f * scale, cardRect.top + tick, cardBorderPaint)
            canvas.drawLine(cardRect.right - tick, cardRect.bottom - 2f * scale, cardRect.right - 2f * scale, cardRect.bottom - 2f * scale, cardBorderPaint)
            canvas.drawLine(cardRect.right - 2f * scale, cardRect.bottom - 2f * scale, cardRect.right - 2f * scale, cardRect.bottom - tick, cardBorderPaint)
        }

        if (recordHit && front) {
            hitCards.add(HitCard(card.docId, cx, cy, w / 2f, h / 2f, back))
        }
    }

    /** Padlock drawn with paths (no emoji-font dependency). */
    private fun drawLockGlyph(canvas: Canvas, cx: Float, cy: Float, scale: Float, alpha: Float) {
        lockPaint.color = Color.argb((230 * alpha).toInt().coerceIn(0, 255), 0, 240, 255)
        lockPaint.strokeWidth = 1.6f * scale
        val bw = 13f * scale
        val bh = 10f * scale
        val body = RectF(cx - bw / 2f, cy - bh / 2f + 2f * scale, cx + bw / 2f, cy + bh / 2f + 2f * scale)
        canvas.drawRoundRect(body, 2f * scale, 2f * scale, lockPaint)
        val shackle = RectF(
            cx - 5f * scale, cy - 9f * scale,
            cx + 5f * scale, cy + 1f * scale
        )
        canvas.drawArc(shackle, 180f, 180f, false, lockPaint)
        canvas.drawLine(cx, cy - 1f * scale, cx, cy + 4f * scale, lockPaint)
    }

    private fun drawPullHint(canvas: Canvas, w: Float) {
        val text = if (pullProgress > 0.65f) {
            resources.getString(R.string.globe_pull_release)
        } else {
            resources.getString(R.string.globe_pull_search)
        }
        hintPaint.textSize = 10f * u(w)
        hintPaint.color = Color.argb(
            (min(1f, pullProgress * 1.6f) * 230).toInt().coerceIn(0, 255), 0, 240, 255
        )
        val y = 46f * u(w)
        val tw = hintPaint.measureText(text)
        val padX = 10f * u(w)
        val padY = 4f * u(w)
        cardBgPaint.color = Color.argb(90, 0, 240, 255)
        canvas.drawRoundRect(
            RectF(w / 2f - tw / 2f - padX, y - 10f * u(w) - padY,
                w / 2f + tw / 2f + padX, y + padY),
            12f * u(w), 12f * u(w), cardBgPaint
        )
        canvas.drawText(text, w / 2f, y, hintPaint)
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((255 * alpha).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        private const val FRAME_MS = 33L
        private const val THUMB_PX = 192
        private const val PULL_START = 0.14f
        private const val PULL_THRESHOLD = 0.10f
        private val LATITUDES = doubleArrayOf(0.0, 23.5, -23.5, 66.5, -66.5)
        private const val LAT_STEPS = 48

        /** Robolectric labels every Build with this fingerprint. */
        private val UNDER_ROBOLECTRIC =
            android.os.Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
    }
}
