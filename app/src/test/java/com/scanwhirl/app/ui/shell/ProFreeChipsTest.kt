package com.scanwhirl.app.ui.shell

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.TrustPill
import com.scanwhirl.app.util.ProBilling
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B2-M4 (real-phone sweep 2026-11-19, build 2 = 1485874c): on the Free Pro screen at 200 % system
 * font the pinned price card's top edge cut straight through the green "No ads / No account /
 * Offline" chips, so the promise was half hidden.
 *
 * The pinned offer (T22) stays: price and "Unlock Pro" are still readable without a swipe and
 * still above the floating dock. What changes is that the chips are never sliced by the bar: at
 * 100 %, 130 % and 200 % font, in English and Hindi, on a 360dp phone, the chip row is fully
 * visible and does not intersect the price card or the pinned bar.
 */
abstract class ProFreeChipsBase {

    private lateinit var app: Application

    /** Answers nothing: the Pro screen must not wait on (or invent) a Play price here. */
    private class SilentGateway : ProBilling.Gateway {
        override fun setListener(listener: ((ProBilling.Event) -> Unit)?) = Unit
        override fun connect(onReady: (Boolean) -> Unit) = Unit
        override fun price(onPrice: (String?) -> Unit) = Unit
        override fun launch(activity: Activity?) = Unit
        override fun queryPurchases(onResult: (List<ProBilling.Owned>?) -> Unit) = Unit
        override fun acknowledge(purchase: ProBilling.Owned, onDone: (Boolean) -> Unit) = Unit
        override fun close() = Unit
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        ProBilling.resetForTests()
        ProBilling.gatewayFactory = { SilentGateway() }
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, false)
        RuntimeEnvironment.setFontScale(1.0f)
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
        ProBilling.resetForTests()
        RuntimeEnvironment.setFontScale(1.0f)
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun pollUntil(timeoutMs: Long = 8000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }

    private fun contentRoot(activity: Activity): View {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        return content.getChildAt(0) ?: content
    }

    /** The sweep's phone shape: 360dp wide, 800dp tall, system bars inset as on the device. */
    private fun layoutAt360(activity: Activity, root: View) {
        val d = activity.resources.displayMetrics.density
        val w = Math.round(360f * d)
        val h = Math.round(800f * d)
        val bar = Math.round(48f * d)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, bar, 0, bar))
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
        val passW = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
        val passH = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        repeat(2) {
            root.measure(passW, passH)
            root.layout(0, 0, w, h)
        }
        idle()
    }

    private fun rootBounds(v: View): Rect {
        var left = 0
        var top = 0
        var cur: View = v
        while (true) {
            left += cur.left
            top += cur.top
            val parent = cur.parent as? View ?: break
            left -= parent.scrollX
            top -= parent.scrollY
            cur = parent
        }
        return Rect(left, top, left + v.width, top + v.height)
    }

    private fun visibleInRoot(v: View): Rect? {
        if (v.width <= 0 || v.height <= 0) return null
        val rect = rootBounds(v)
        var cur: View? = v.parent as? View
        while (cur != null) {
            if (!rect.intersect(rootBounds(cur))) return null
            cur = cur.parent as? View
        }
        return rect.takeIf { it.width() > 0 && it.height() > 0 }
    }

    private fun fullyVisible(v: View): Boolean {
        val seen = visibleInRoot(v) ?: return false
        return seen.width() == v.width && seen.height() == v.height
    }

    private fun openProFree(activity: ShellActivity) {
        activity.findViewById<View>(R.id.swNavMe).performClick()
        idle()
        activity.findViewById<View>(R.id.swMePro).performClick()
        idle()
        pollUntil { activity.findViewById<View>(R.id.swProScreen) != null }
    }

    /** The row of green promise pills ("No ads" / "No account" / "Offline"). */
    private fun trustRow(root: View): View? {
        var found: View? = null
        fun walk(v: View) {
            if (found != null) return
            if (v is TrustPill) {
                found = v.parent as? View
                return
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return found
    }

    @Test
    fun `the pro promise chips are never cut by the pinned price card`() {
        val diag = mutableListOf<String>()
        for (scale in floatArrayOf(1.0f, 1.3f, 2.0f)) {
            RuntimeEnvironment.setFontScale(scale)
            ActivityScenario.launch(ShellActivity::class.java).use { s ->
                s.onActivity { a ->
                    idle()
                    openProFree(a)
                    val root = contentRoot(a)
                    layoutAt360(a, root)

                    val chips = trustRow(root)
                    val price = a.findViewById<View>(R.id.swProPrice)
                    val buy = a.findViewById<View>(R.id.swProBuy)
                    val pill = a.findViewById<View>(R.id.swShellNav)
                    val screen = a.findViewById<View>(R.id.swProScreen)
                    assertNotNull("@$scale: the promise chips are on screen", chips)
                    assertNotNull("@$scale: the price line is on screen", price)
                    assertNotNull("@$scale: Unlock Pro is on screen", buy)
                    assertNotNull("@$scale: the floating dock is on screen", pill)
                    assertNotNull("@$scale: the paywall screen exists", screen)

                    val scroller = (0 until (screen as ViewGroup).childCount)
                        .map { screen.getChildAt(it) }
                        .filterIsInstance<android.widget.ScrollView>()
                        .firstOrNull()
                    val bar = (0 until screen.childCount)
                        .map { screen.getChildAt(it) }
                        .firstOrNull { it !== scroller && it.isShown }
                    assertNotNull("@$scale: the offer sits in a pinned bar (T22)", bar)

                    val chipsRect = rootBounds(chips!!)
                    val priceRect = rootBounds(price!!)
                    val barRect = rootBounds(bar!!)
                    val dockTop = rootBounds(pill!!).top
                    diag += "@$scale chips=$chipsRect bar=$barRect price=$priceRect dockTop=$dockTop"

                    assertTrue(
                        "@$scale: the promise chips are not shown whole (B2-M4): chips " +
                            "$chipsRect, bar $barRect, price card $priceRect",
                        fullyVisible(chips)
                    )
                    assertTrue(
                        "@$scale: the price line is not shown without a swipe (T22)",
                        fullyVisible(price)
                    )
                    assertTrue(
                        "@$scale: Unlock Pro is not shown without a swipe (T22)",
                        fullyVisible(buy!!)
                    )
                    assertFalse(
                        "@$scale: the price card covers the promise chips (B2-M4): " +
                            "chips $chipsRect vs card $priceRect",
                        Rect.intersects(chipsRect, priceRect)
                    )
                    assertFalse(
                        "@$scale: the pinned bar covers the promise chips (B2-M4): " +
                            "chips $chipsRect vs bar $barRect",
                        Rect.intersects(chipsRect, barRect)
                    )
                    assertTrue(
                        "@$scale: Unlock Pro is not above the dock (T22): " +
                            "${rootBounds(buy).bottom} vs $dockTop",
                        rootBounds(buy).bottom <= dockTop
                    )
                }
            }
        }
        assertTrue(diag.joinToString("\n"), true)
    }
}

/** The phone the sweep used: 360dp, English. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class ProFreeChipsEnTest : ProFreeChipsBase()

/** The same phone in Hindi. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h800dp-xhdpi")
class ProFreeChipsHiTest : ProFreeChipsBase()
