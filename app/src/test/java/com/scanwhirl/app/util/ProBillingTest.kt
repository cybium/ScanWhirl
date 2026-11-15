package com.scanwhirl.app.util

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Play Billing decisions with a fake gateway: acknowledge then unlock, pending, cancel, restore,
 * a refund locking Pro in a release build, and a phone without Play Store (no crash).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProBillingTest {

    private lateinit var app: Application

    private class FakeGateway : ProBilling.Gateway {
        var eventListener: ((ProBilling.Event) -> Unit)? = null
        var connects = 0
        var launches = 0
        val acknowledged = mutableListOf<String>()
        var purchases: List<ProBilling.Owned> = emptyList()
        var priceText: String? = "₹100"
        var ackResult = true
        var connectResult = true

        override fun setListener(listener: (ProBilling.Event) -> Unit) {
            eventListener = listener
        }

        override fun connect(onReady: (Boolean) -> Unit) {
            connects++
            onReady(connectResult)
        }

        override fun price(onPrice: (String?) -> Unit) = onPrice(priceText)

        override fun launch(activity: Activity?) {
            launches++
        }

        override fun queryPurchases(onResult: (List<ProBilling.Owned>) -> Unit) = onResult(purchases)

        override fun acknowledge(purchase: ProBilling.Owned, onDone: (Boolean) -> Unit) {
            acknowledged += purchase.token
            onDone(ackResult)
        }

        override fun close() = Unit

        fun emit(event: ProBilling.Event) = eventListener?.invoke(event)
    }

    private fun purchased(): Boolean =
        app.getSharedPreferences("scanwhirl_prefs", 0).getBoolean("pro_unlocked", false)

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
    fun `a fresh purchase is acknowledged before Pro unlocks`() {
        val gateway = FakeGateway()
        val states = mutableListOf<ProBilling.State>()
        val controller = ProBilling.Controller(app, gateway, testBuild = false) { states += it }

        val state = controller.handle(
            ProBilling.Event.Purchased(ProBilling.Owned("scanwhirl_pro", acknowledged = false, token = "tok-1"))
        )

        assertEquals(ProBilling.State.Owned, state)
        assertEquals(listOf("tok-1"), gateway.acknowledged)
        assertTrue("Pro was recorded", purchased())
        assertEquals(listOf<ProBilling.State>(ProBilling.State.Owned), states)
    }

    @Test
    fun `an acknowledge failure keeps Pro locked`() {
        val gateway = FakeGateway().apply { ackResult = false }
        val controller = ProBilling.Controller(app, gateway, testBuild = false) { }

        val state = controller.handle(
            ProBilling.Event.Purchased(ProBilling.Owned("scanwhirl_pro", acknowledged = false, token = "tok-2"))
        )

        assertTrue(state is ProBilling.State.Failed)
        assertFalse("Pro stays locked", purchased())
    }

    @Test
    fun `an already acknowledged purchase unlocks without a second acknowledge`() {
        val gateway = FakeGateway()
        val controller = ProBilling.Controller(app, gateway, testBuild = false) { }

        val state = controller.handle(
            ProBilling.Event.Purchased(ProBilling.Owned("scanwhirl_pro", acknowledged = true, token = "tok-3"))
        )

        assertEquals(ProBilling.State.Owned, state)
        assertTrue(gateway.acknowledged.isEmpty())
        assertTrue(purchased())
    }

    @Test
    fun `pending and cancel do not unlock`() {
        val gateway = FakeGateway()
        val controller = ProBilling.Controller(app, gateway, testBuild = false) { }

        assertEquals(ProBilling.State.Pending, controller.handle(ProBilling.Event.Pending))
        assertEquals(ProBilling.State.None, controller.handle(ProBilling.Event.Cancelled))
        assertFalse(purchased())
        assertTrue("nothing acknowledged", gateway.acknowledged.isEmpty())
    }

    @Test
    fun `restore unlocks when the account owns Pro`() {
        val gateway = FakeGateway().apply {
            purchases = listOf(ProBilling.Owned("scanwhirl_pro", acknowledged = true, token = "tok-4"))
        }
        val controller = ProBilling.Controller(app, gateway, testBuild = false) { }

        assertEquals(ProBilling.State.Owned, controller.applyOwned(gateway.purchases))
        assertTrue(purchased())
    }

    @Test
    fun `a refund locks Pro in a release build and leaves a test build alone`() {
        val gateway = FakeGateway().apply { purchases = emptyList() }
        val release = ProBilling.Controller(app, gateway, testBuild = false) { }
        ProGate.setPro(app, true)
        assertEquals(ProBilling.State.None, release.applyOwned(emptyList(), silent = true))
        assertFalse("a refund locks Pro again", purchased())

        ProGate.setPro(app, true)
        val test = ProBilling.Controller(app, gateway, testBuild = true) { }
        assertEquals(ProBilling.State.None, test.applyOwned(emptyList(), silent = true))
        assertTrue("test builds stay Pro", purchased())
    }

    @Test
    fun `a phone without Play Store reports unavailable instead of crashing`() {
        // A store that cannot be created (no Play Store) must answer "no price", never throw.
        ProBilling.gatewayFactory = { throw IllegalStateException("no Play Store") }
        var price: String? = "sentinel"
        ProBilling.price(app) { price = it }
        assertEquals(null, price)
    }

    @Test
    fun `a test build unlocks without touching the store`() {
        val gateway = FakeGateway()
        ProBilling.gatewayFactory = { gateway }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var state: ProBilling.State? = null
        ProBilling.buy(activity) { state = it }
        assertEquals(ProBilling.State.Owned, state)
        assertEquals("no billing connection in a test build", 0, gateway.connects)
    }

    @Test
    fun `the localized price is used and the fallback is null`() {
        val gateway = FakeGateway()
        ProBilling.gatewayFactory = { gateway }
        var price: String? = null
        ProBilling.price(app) { price = it }
        assertEquals("₹100", price)

        ProBilling.resetForTests()
        val offline = FakeGateway().apply { priceText = null }
        ProBilling.gatewayFactory = { offline }
        price = "sentinel"
        ProBilling.price(app) { price = it }
        assertEquals("no price -> the screen keeps its own text", null, price)
    }
}
