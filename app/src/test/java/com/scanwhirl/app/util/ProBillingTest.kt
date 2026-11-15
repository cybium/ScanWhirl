package com.scanwhirl.app.util

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Billing decisions with a fake that answers **later**, like the real client: the acknowledge
 * callback and the controller's posted decision both go through an explicit queue, so a bug that
 * reads an async result synchronously fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProBillingTest {

    private lateinit var app: Application

    private class FakeGateway : ProBilling.Gateway {
        var eventListener: ((ProBilling.Event) -> Unit)? = null
        private val queue = ArrayDeque<() -> Unit>()
        val acknowledged = mutableListOf<String>()
        var launches = 0
        var purchases: List<ProBilling.Owned>? = emptyList()
        var priceText: String? = "₹100"
        var priceCalls = 0
        var ackResult = true
        var connectResult = true

        override fun setListener(listener: (ProBilling.Event) -> Unit) {
            eventListener = listener
        }

        override fun connect(onReady: (Boolean) -> Unit) {
            queue.addLast { onReady(connectResult) }
        }

        override fun price(onPrice: (String?) -> Unit) {
            priceCalls++
            queue.addLast { onPrice(priceText) }
        }

        override fun launch(activity: Activity?) {
            launches++
        }

        override fun queryPurchases(onResult: (List<ProBilling.Owned>?) -> Unit) {
            queue.addLast { onResult(purchases) }
        }

        override fun acknowledge(purchase: ProBilling.Owned, onDone: (Boolean) -> Unit) {
            acknowledged += purchase.token
            queue.addLast { onDone(ackResult) }
        }

        override fun close() = Unit

        fun enqueue(work: () -> Unit) {
            queue.addLast(work)
        }

        /** Runs every queued callback in order (the controller posts its decision to the same queue). */
        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst()()
        }

        fun emit(event: ProBilling.Event) = eventListener?.invoke(event)
    }

    private fun purchased(): Boolean =
        app.getSharedPreferences("scanwhirl_prefs", 0).getBoolean("pro_unlocked", false)

    private fun controller(gateway: FakeGateway, testBuild: Boolean, states: MutableList<ProBilling.State> = mutableListOf()) =
        ProBilling.Controller(app, gateway, testBuild, { gateway.enqueue(it) }) { states += it }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        ProBilling.resetForTests()
    }

    @After
    fun tearDown() {
        ProBilling.resetForTests()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    @Test
    fun `acknowledge is asynchronous - Finishing first, Pro only after the callback`() {
        val gateway = FakeGateway()
        val states = mutableListOf<ProBilling.State>()
        val controller = controller(gateway, testBuild = false, states = states)

        controller.handle(
            ProBilling.Event.Purchased(ProBilling.Owned("scanwhirl_pro", acknowledged = false, token = "tok-1"))
        )

        assertEquals("the UI shows Finishing while Play answers", listOf(ProBilling.State.Finishing), states)
        assertFalse("Pro must not be granted before the acknowledge callback", purchased())

        gateway.drain()

        assertEquals(ProBilling.State.Owned, states.last())
        assertEquals(listOf("tok-1"), gateway.acknowledged)
        assertTrue("Pro recorded after the acknowledge", purchased())
    }

    @Test
    fun `an acknowledge failure reports Failed and keeps Pro locked`() {
        val gateway = FakeGateway().apply { ackResult = false }
        val states = mutableListOf<ProBilling.State>()
        val controller = controller(gateway, testBuild = false, states = states)

        controller.handle(
            ProBilling.Event.Purchased(ProBilling.Owned("scanwhirl_pro", acknowledged = false, token = "tok-2"))
        )
        gateway.drain()

        assertTrue(states.last() is ProBilling.State.Failed)
        assertFalse(purchased())
    }

    @Test
    fun `an already acknowledged purchase unlocks without a second acknowledge`() {
        val gateway = FakeGateway()
        val states = mutableListOf<ProBilling.State>()
        val controller = controller(gateway, testBuild = false, states = states)

        controller.handle(
            ProBilling.Event.Purchased(ProBilling.Owned("scanwhirl_pro", acknowledged = true, token = "tok-3"))
        )

        assertEquals(ProBilling.State.Owned, states.last())
        assertTrue(gateway.acknowledged.isEmpty())
        assertTrue(purchased())
    }

    @Test
    fun `a start-up purchase that was never acknowledged gets acknowledged`() {
        val gateway = FakeGateway()
        val states = mutableListOf<ProBilling.State>()
        val controller = controller(gateway, testBuild = false, states = states)

        controller.applyOwned(
            listOf(ProBilling.Owned("scanwhirl_pro", acknowledged = false, token = "tok-9"))
        )
        assertEquals(listOf<ProBilling.State>(ProBilling.State.Finishing), states)

        gateway.drain()

        assertEquals(listOf("tok-9"), gateway.acknowledged)
        assertEquals(ProBilling.State.Owned, states.last())
        assertTrue(purchased())
    }

    @Test
    fun `a failed query keeps Pro (offline or Play busy)`() {
        ProGate.setPro(app, true)
        val gateway = FakeGateway()
        val states = mutableListOf<ProBilling.State>()
        val controller = controller(gateway, testBuild = false, states = states)

        controller.applyOwned(null)
        controller.applyOwned(null, silent = true)
        gateway.drain()

        assertTrue("a paying user keeps Pro", purchased())
        assertTrue("no state is reported", states.isEmpty())
    }

    @Test
    fun `an OK answer without the product locks Pro in a release build`() {
        ProGate.setPro(app, true)
        val gateway = FakeGateway()
        val controller = controller(gateway, testBuild = false)

        controller.applyOwned(emptyList(), silent = true)

        assertFalse("a refund locks Pro again", purchased())
    }

    @Test
    fun `a pending purchase neither grants nor locks Pro`() {
        val gateway = FakeGateway()
        val states = mutableListOf<ProBilling.State>()
        val controller = controller(gateway, testBuild = false, states = states)

        controller.handle(ProBilling.Event.Pending)
        assertEquals(ProBilling.State.Pending, states.last())
        assertFalse(purchased())

        ProGate.setPro(app, true)
        controller.applyOwned(
            listOf(ProBilling.Owned("scanwhirl_pro", acknowledged = false, token = "p", state = ProBilling.PurchaseState.PENDING))
        )
        gateway.drain()
        assertTrue("a pending purchase never locks an existing Pro", purchased())
        assertTrue("nothing is acknowledged before payment", gateway.acknowledged.isEmpty())
    }

    @Test
    fun `cancel does nothing and a test build unlocks without the store`() {
        val gateway = FakeGateway()
        val states = mutableListOf<ProBilling.State>()
        val controller = controller(gateway, testBuild = true, states = states)
        controller.handle(ProBilling.Event.Cancelled)
        assertEquals(ProBilling.State.None, states.last())

        ProBilling.gatewayFactory = { gateway }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var state: ProBilling.State? = null
        ProBilling.buy(activity) { state = it }
        assertEquals(ProBilling.State.Owned, state)
        assertEquals("no billing connection in a test build", 0, gateway.launches)
    }

    @Test
    fun `a phone without Play Store reports no price instead of crashing`() {
        ProBilling.gatewayFactory = { throw IllegalStateException("no Play Store") }
        var price: String? = "sentinel"
        ProBilling.price(app) { price = it }
        assertNull(price)
    }

    @Test
    fun `the localized price is used when the store answers`() {
        val gateway = FakeGateway()
        ProBilling.gatewayFactory = { gateway }
        var price: String? = null
        ProBilling.price(app) { price = it }
        gateway.drain()
        assertEquals("₹100", price)
    }

    @Test
    fun `buy asks the store for the product again when the first query failed`() {
        val gateway = FakeGateway().apply { priceText = null }
        ProBilling.gatewayFactory = { gateway }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var state: ProBilling.State? = null
        ProBilling.buy(activity, testBuild = false) { state = it }
        gateway.drain()

        assertEquals("Unlock Pro must query the product details again", 1, gateway.priceCalls)
        assertEquals("a product the store does not have never opens the sheet", 0, gateway.launches)
        assertTrue("the UI hears about the missing product", state is ProBilling.State.Failed)
        assertEquals(
            "The Pro product is not available yet.",
            (state as ProBilling.State.Failed).message
        )
    }

    @Test
    fun `buy launches the purchase sheet once the product details arrive`() {
        val gateway = FakeGateway()
        ProBilling.gatewayFactory = { gateway }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var state: ProBilling.State? = null
        ProBilling.buy(activity, testBuild = false) { state = it }
        gateway.drain()

        assertEquals("one details query per tap", 1, gateway.priceCalls)
        assertEquals("the purchase sheet opens", 1, gateway.launches)
        assertNull("no failure to report", state)
    }
}
