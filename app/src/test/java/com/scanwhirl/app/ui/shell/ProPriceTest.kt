package com.scanwhirl.app.ui.shell

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.ProBilling
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v2 UI: 06_pro — Play's localized price must reach every Pro screen, even when the query
 * finished before the screen existed (the shell fetches it in onCreate). PRC-001: while no
 * answer has arrived the price line names Google Play and shows no amount (no ₹ fallback).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProPriceTest {

    private lateinit var app: Application

    /** Answers later, like Play: nothing happens until [drain]. */
    private class FakeGateway(var priceText: String? = "$4.99") : ProBilling.Gateway {
        private val queue = ArrayDeque<() -> Unit>()
        var priceCalls = 0
        var launches = 0

        override fun setListener(listener: ((ProBilling.Event) -> Unit)?) = Unit
        override fun connect(onReady: (Boolean) -> Unit) {
            queue.addLast { onReady(true) }
        }

        override fun price(onPrice: (String?) -> Unit) {
            priceCalls++
            queue.addLast { onPrice(priceText) }
        }

        override fun launch(activity: Activity?) {
            launches++
        }

        override fun queryPurchases(onResult: (List<ProBilling.Owned>?) -> Unit) {
            queue.addLast { onResult(emptyList()) }
        }

        override fun acknowledge(purchase: ProBilling.Owned, onDone: (Boolean) -> Unit) {
            queue.addLast { onDone(true) }
        }

        override fun close() = Unit

        fun enqueue(work: () -> Unit) {
            queue.addLast(work)
        }

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst()()
        }
    }

    private lateinit var gateway: FakeGateway

    /** Answers only when the test says so, so the order of replies can be reversed. */
    private class ManualGateway : ProBilling.Gateway {
        val priceWaiters = mutableListOf<(String?) -> Unit>()
        var launches = 0

        override fun setListener(listener: ((ProBilling.Event) -> Unit)?) = Unit
        override fun connect(onReady: (Boolean) -> Unit) = onReady(true)
        override fun price(onPrice: (String?) -> Unit) {
            priceWaiters += onPrice
        }

        override fun launch(activity: Activity?) {
            launches++
        }

        override fun queryPurchases(onResult: (List<ProBilling.Owned>?) -> Unit) = onResult(emptyList())
        override fun acknowledge(purchase: ProBilling.Owned, onDone: (Boolean) -> Unit) = onDone(true)
        override fun close() = Unit
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        ProBilling.resetForTests()
        gateway = FakeGateway()
        ProBilling.gatewayFactory = { gateway }
        ProGate.setPro(app, false)
        // The price card belongs to the Free Pro screen (a Pro build shows the active state).
        ProGate.setTestOverride(app, false)
    }

    @After
    fun tearDown() {
        ProBilling.resetForTests()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun priceText(activity: ShellActivity): String =
        activity.findViewById<TextView>(R.id.swProPrice).text.toString()

    @Test
    fun `a price fetched before the Pro screen exists is on every Pro screen`() {
        var fetched: String? = null
        ProBilling.price(app) { fetched = it }
        gateway.drain()
        assertEquals("$4.99", fetched)

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                activity.showPro()
                idle()
                assertEquals(
                    "the localized price must reach the first Pro screen",
                    "$4.99",
                    priceText(activity)
                )
                activity.findViewById<View>(R.id.swProClose).performClick()
                idle()
                activity.showPro()
                idle()
                assertEquals(
                    "the localized price must reach every rebuilt Pro screen",
                    "$4.99",
                    priceText(activity)
                )
            }
        }
    }

    @Test
    fun `no amount is shown before Play answers`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                activity.showPro()
                idle()
                assertEquals(
                    "PRC-001: no built-in amount before Play's price arrives",
                    activity.getString(R.string.pro_price_play),
                    priceText(activity)
                )
            }
        }
    }

    /**
     * The launch-time answer may arrive before the first Pro screen exists (the old crash:
     * `proScreen` is a lateinit non-null property, so `proScreen?.setPrice` threw). A synchronous
     * answer during onCreate must be harmless and still reach the screen opened later.
     */
    @Test
    fun `a launch-time answer before Pro exists neither crashes nor is lost`() {
        val manual = ManualGateway()
        ProBilling.gatewayFactory = { manual }
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                // onCreate's prefetch is the only waiter so far: answer it with no Pro screen.
                assertEquals(1, manual.priceWaiters.size)
                manual.priceWaiters[0]("$4.99")
                idle()
                activity.showPro()
                idle()
                assertEquals(
                    "the launch-time price is on the first Pro screen",
                    "$4.99",
                    priceText(activity)
                )
            }
        }
    }

    @Test
    fun `an older price answer cannot overwrite a newer Pro screen`() {
        val manual = ManualGateway()
        ProBilling.gatewayFactory = { manual }
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                activity.showPro() // waiter 1
                activity.findViewById<View>(R.id.swProClose).performClick()
                idle()
                activity.showPro() // waiter 2 — the screen that is now showing

                // The newest answer first, then the two obsolete ones in reverse order.
                manual.priceWaiters[2]("$3.00")
                idle()
                manual.priceWaiters[0]("$1.00")
                manual.priceWaiters[1]("$2.00")
                idle()

                assertEquals(
                    "only the current screen's answer may win",
                    "$3.00",
                    priceText(activity)
                )
            }
        }
    }
}
