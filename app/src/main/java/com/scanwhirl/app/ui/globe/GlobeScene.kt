package com.scanwhirl.app.ui.globe

import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.util.CategoryRules
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything about the holographic globe home that is *not* drawing:
 * the Fibonacci-sphere dot field, the land mask lookup, rotation physics,
 * the five category rings, card placement and search dimming.
 *
 * Kept free of Android types so it can be unit-tested directly. The view
 * ([HolographicEarthView]) owns pixels; this owns math and state.
 *
 * States: HOME (Earth + orbiting cards) and RINGS (Earth shrinks up, five
 * category rings expand below). [stateT] eases 0→1 between them exactly like
 * the HTML prototype (lerp per 30 fps frame).
 */
class GlobeScene(private val landMask: ByteArray) {

    /** One document card on a ring (or orbiting the globe at home). */
    data class Card(
        val docId: String,
        val title: String,
        val category: String,
        val ringIndex: Int,
        val slot: Int,
        val slotCount: Int,
        /** Locked or vaulted: far side only, glyph-only (never exposes title). */
        val hidden: Boolean,
        /** Recent doc riding the home orbit instead of a ring slot. */
        val orbit: Boolean = false
    ) {
        /** Title only ever surfaced for cards the user may see. */
        val visibleTitle: String? get() = if (hidden) null else title
    }

    data class GlobeGeom(val cx: Float, val cy: Float, val r: Float)
    data class RingGeom(val cx: Float, val cy: Float, val rx: Float, val ry: Float)
    data class Star(
        val x: Float, val y: Float, val size: Float,
        val alpha: Float, val speed: Float, val phase: Float
    )

    companion object {
        const val DOTS = 2200
        const val RING_COUNT = 5
        const val ORBIT_MAX = 5
        const val STATE_HOME = 0
        const val STATE_RINGS = 1

        /** 1 revolution per 60 s (prototype BASE_ROT_SPEED). */
        val BASE_ROT = (2.0 * PI / 60.0).toFloat()

        /** Per-frame easing at 30 fps (prototype: *0.12 / *0.14 lerp). */
        const val EASE_STATE = 0.12f
        private val GOLDEN_ANGLE = (PI * (3.0 - sqrt(5.0))).toFloat()

        /** Ring radii as fractions of width — five rings, prototype had four. */
        private val RING_RX = floatArrayOf(0.155f, 0.24f, 0.325f, 0.41f, 0.495f)
        private const val RING_RY_RATIO = 0.42f

        /** Search miss dimming (prototype: 0.12) and far-side dimming (0.45). */
        const val DIM_ALPHA = 0.12f
        const val BACK_ALPHA = 0.45f

        fun ringIndexFor(category: String): Int =
            CategoryRules.ALL.indexOf(category).coerceAtLeast(0)
    }

    // --- Fibonacci sphere -------------------------------------------------
    val dotX = FloatArray(DOTS)
    val dotY = FloatArray(DOTS)
    val dotZ = FloatArray(DOTS)
    val dotLand = BooleanArray(DOTS)
    val dotSeed = FloatArray(DOTS)
    var landCount = 0
        private set

    // --- Stars ------------------------------------------------------------
    val stars: List<Star>

    // --- Rotation / state -------------------------------------------------
    var state = STATE_HOME
        private set
    var stateT = 0f
    var earthRotY = 0f
    var earthRotX = 0.22f // slight axial tilt, like the prototype
    var earthVelY = 0f
    var earthVelX = 0f
    val ringAngles = FloatArray(RING_COUNT)
    val ringVels = FloatArray(RING_COUNT)
    var time = 0f
        private set

    // --- Drag bookkeeping (set by the view) -------------------------------
    var draggingGlobe = false
    var draggingRing = -1

    // --- Documents --------------------------------------------------------
    var cards: List<Card> = emptyList()
        private set
    var orbitCards: List<Card> = emptyList()
        private set

    /** Live search query; non-matching cards dim to [DIM_ALPHA]. */
    var query: String = ""

    /**
     * Doc ids matched through OCR text (Pro) — the title itself does not
     * contain the query but the Shelf would still show them. Vaulted ids
     * can never appear here ([SearchMatcher] excludes them).
     */
    var extraMatchIds: Set<String> = emptySet()

    init {
        require(landMask.size >= 360 * 180 / 8) { "Land mask too small" }
        // Fibonacci sphere, land looked up from the Natural Earth mask.
        var land = 0
        for (i in 0 until DOTS) {
            val y = 1f - (i.toFloat() / (DOTS - 1)) * 2f
            val r = sqrt((1f - y * y).coerceAtLeast(0f))
            val theta = GOLDEN_ANGLE * i
            val x = r * cos(theta)
            val z = r * sin(theta)
            val lat = (asin(y.coerceIn(-1f, 1f)) * 180.0 / PI).toFloat()
            val lon = (atan2(x, z) * 180.0 / PI).toFloat()
            val isLand = isLand(lon, lat)
            if (isLand) land++
            dotX[i] = x; dotY[i] = y; dotZ[i] = z
            dotLand[i] = isLand
            dotSeed[i] = ((i * 7919) % 1000) / 1000f
        }
        landCount = land

        // Deterministic star field (stable across runs / tests).
        val rnd = java.util.Random(42)
        stars = List(120) {
            Star(
                x = rnd.nextFloat(),
                y = rnd.nextFloat(),
                size = rnd.nextFloat() * 1.5f + 0.5f,
                alpha = rnd.nextFloat() * 0.7f + 0.2f,
                speed = rnd.nextFloat() * 2f + 1f,
                phase = rnd.nextFloat() * 2f * PI.toFloat()
            )
        }
    }

    /** Natural Earth 110m land lookup (360×180 bit mask, row-major). */
    fun isLand(lonDeg: Float, latDeg: Float): Boolean {
        var x = (lonDeg + 180f).toInt() % 360
        if (x < 0) x += 360
        val y = (90f - latDeg).toInt().coerceIn(0, 179)
        val idx = y * 360 + x
        val byte = landMask[idx shr 3].toInt()
        val bit = 7 - (idx and 7)
        return (byte and (1 shl bit)) != 0
    }

    // --- Documents --------------------------------------------------------

    /**
     * Distributes scans onto the five rings by category. Locked/vaulted
     * documents keep their ring slot but are [Card.hidden]: they only ever
     * render on the far side (z &lt; 0) as a glyph, never with a title.
     * Up to [ORBIT_MAX] recent public docs also ride the home orbit.
     */
    fun setDocs(docs: List<ScanDocument>) {
        val built = ArrayList<Card>(docs.size)
        for ((ring, cat) in CategoryRules.ALL.withIndex()) {
            val onRing = docs.filter { (it.category.takeIf { c -> CategoryRules.isValid(c) }
                ?: CategoryRules.OTHER) == cat }
            onRing.forEachIndexed { i, doc ->
                built += Card(
                    docId = doc.id,
                    title = doc.name,
                    category = cat,
                    ringIndex = ring,
                    slot = i,
                    slotCount = onRing.size,
                    hidden = doc.isLocked || doc.isVaulted
                )
            }
        }
        cards = built
        orbitCards = docs
            .filter { !it.isLocked && !it.isVaulted }
            .sortedByDescending { it.createdAt }
            .take(ORBIT_MAX)
            .mapIndexed { i, doc ->
                Card(
                    docId = doc.id, title = doc.name,
                    category = doc.category, ringIndex = -1,
                    slot = i, slotCount = ORBIT_MAX, hidden = false, orbit = true
                )
            }
    }

    /**
     * Search match for a card. Hidden cards never match: a query must not
     * reveal that a locked/vaulted document contains (or is named) something.
     */
    fun matches(card: Card): Boolean {
        if (query.isBlank()) return true
        if (card.hidden) return false
        return card.title.contains(query, true) ||
            card.category.contains(query, true) ||
            card.docId in extraMatchIds
    }

    /** How strongly to draw this card (search dimming + far-side dimming). */
    fun alphaFor(card: Card, z: Float): Float {
        val match = if (matches(card)) 1f else DIM_ALPHA
        val depth = if (z < 0f) BACK_ALPHA else 1f
        return match * depth
    }

    // --- Geometry ---------------------------------------------------------

    fun globeGeometry(w: Float, h: Float): GlobeGeom {
        val t = stateT
        return GlobeGeom(
            cx = w * 0.5f,
            cy = h * 0.44f + (h * 0.20f - h * 0.44f) * t,
            r = w * 0.31f + (w * 0.16f - w * 0.31f) * t
        )
    }

    fun ringGeometry(ring: Int, w: Float, h: Float): RingGeom {
        val rx = w * RING_RX[ring]
        return RingGeom(cx = w * 0.5f, cy = h * 0.63f, rx = rx, ry = rx * RING_RY_RATIO)
    }

    /** Ellipse-normalised distance from ring [ring]'s outline (1.0 = on it). */
    fun ringOutlineDistance(ring: Int, x: Float, y: Float, w: Float, h: Float): Float {
        val g = ringGeometry(ring, w, h)
        val dx = (x - g.cx) / g.rx
        val dy = (y - g.cy) / g.ry
        return sqrt(dx * dx + dy * dy)
    }

    // --- State & physics --------------------------------------------------

    fun toggleState() {
        state = if (state == STATE_RINGS) STATE_HOME else STATE_RINGS
    }

    /** Advances easing/rotation by [dt] seconds (call at ≤30 fps). */
    fun update(dt: Float) {
        time += dt
        val k = (dt * 30f).coerceIn(0f, 6f)

        val target = if (state == STATE_RINGS) 1f else 0f
        val ease = 1f - (1f - EASE_STATE).pow(k)
        stateT += (target - stateT) * ease
        if (abs(target - stateT) < 0.002f) stateT = target

        if (!draggingGlobe) {
            earthRotY += BASE_ROT * dt + earthVelY * k
            earthVelY *= 0.94f.pow(k)
            earthVelX *= 0.94f.pow(k)
            earthRotX = (earthRotX + earthVelX * k).coerceIn(-0.6f, 0.6f)
        }

        for (r in 0 until RING_COUNT) {
            if (draggingRing != r) {
                val drift = (if (r % 2 == 0) 0.03f else -0.02f) * dt
                ringAngles[r] += ringVels[r] * k + drift
                ringVels[r] *= 0.94f.pow(k)
            }
        }
    }

    /** Card position on its ring. Hidden cards only occupy the far arc. */
    fun cardPosition(card: Card, w: Float, h: Float): FloatArray {
        val g = ringGeometry(card.ringIndex, w, h)
        val step = (2.0 * PI / maxOf(1, card.slotCount)).toFloat()
        val base = ringAngles[card.ringIndex] + step * card.slot
        // Hidden cards stay on the back half of the ellipse (z = sin ≤ 0):
        // they orbit behind the globe and are only ever drawn there.
        val angle = if (card.hidden) {
            (PI.toFloat() + mod(base - PI.toFloat(), PI.toFloat()))
        } else {
            base
        }
        val x = g.cx + cos(angle) * g.rx
        val y = g.cy + sin(angle) * g.ry
        val z = sin(angle)
        return floatArrayOf(x, y, z)
    }

    private fun mod(a: Float, b: Float): Float {
        val r = a % b
        return if (r < 0f) r + b else r
    }
}
