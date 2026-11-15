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
 * finished before the screen existed (the shell fetches it in onCreate). The hard-coded
 * ₹100 is only the fallback while no answer has arrived.
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

        override fun setListener(listener: (ProBilling.Event) -> Unit) = Unit
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
    fun `the hard-coded price is still the fallback before Play answers`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                activity.showPro()
                idle()
                assertEquals(
                    activity.getString(R.string.pro_price),
                    priceText(activity)
                )
            }
        }
    }
}
