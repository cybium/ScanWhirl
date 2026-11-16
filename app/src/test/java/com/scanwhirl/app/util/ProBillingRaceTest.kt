package com.scanwhirl.app.util

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * R1–R7 from the pro-fixes re-review. The fake holds Play's callbacks and can
 * deliver them later, on this thread, or throw inside the posted body.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProBillingRaceTest {

    private lateinit var app: Application
    private lateinit var gateway: HoldingGateway

    private class HoldingGateway : ProBilling.Gateway {
        private var storeListener: ((ProBilling.Event) -> Unit)? = null
        var listenerClears = 0
        val launched = mutableListOf<String>()
        var launchOnMain = false
        var priceText: String? = "₹100"
        var offerToken = "offer"
        var purchases: List<ProBilling.Owned>? = emptyList()
        var throwOnPrice = false
        var throwOnQuery = false
        var throwOnAck = false
        var ackResult = true
        var launchResult = ProBilling.LaunchResult.STARTED
        var captureOffers = false
        val capturedOffers = mutableListOf<(ProBilling.Offer?) -> Unit>()
        private val held = ArrayDeque<() -> Unit>()
        private val heldPurchases = ArrayDeque<(List<ProBilling.Owned>?) -> Unit>()
        var holdPurchases = true

        override fun setListener(listener: ((ProBilling.Event) -> Unit)?) {
            if (listener == null) listenerClears++
            storeListener = listener
        }

        override fun connect(onReady: (Boolean) -> Unit) {
            held.addLast { onReady(true) }
        }

        override fun price(onPrice: (String?) -> Unit) {
            if (throwOnPrice) throw IllegalStateException("price")
            held.addLast { onPrice(priceText) }
        }

        override fun queryOffer(onOffer: (ProBilling.Offer?) -> Unit) {
            if (throwOnPrice) throw IllegalStateException("price")
            if (captureOffers) capturedOffers += onOffer
            else held.addLast { onOffer(priceText?.let { ProBilling.Offer(it, offerToken) }) }
        }

        override fun launch(activity: Activity?) = Unit

        override fun launch(activity: Activity?, offer: ProBilling.Offer): ProBilling.LaunchResult {
            launched += offer.token
            launchOnMain = Looper.myLooper() == Looper.getMainLooper()
            return launchResult
        }

        override fun queryPurchases(onResult: (List<ProBilling.Owned>?) -> Unit) {
            if (holdPurchases) heldPurchases.addLast(onResult) else onResult(purchases)
        }

        override fun acknowledge(purchase: ProBilling.Owned, onDone: (Boolean) -> Unit) {
            if (throwOnAck) throw IllegalStateException("ack")
            held.addLast { onDone(ackResult) }
        }

        override fun close() = Unit

        fun releaseNext() {
            if (held.isNotEmpty()) held.removeFirst()()
        }

        fun releasePurchases() {
            if (heldPurchases.isNotEmpty()) heldPurchases.removeFirst()(purchases)
        }

        fun emit(event: ProBilling.Event) = storeListener?.invoke(event)
    }

    private fun purchased(): Boolean =
        app.getSharedPreferences("scanwhirl_prefs", 0).getBoolean("pro_unlocked", false)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun activity(): Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        ProBilling.resetForTests()
        gateway = HoldingGateway()
        ProBilling.gatewayFactory = { gateway }
        ProGate.setPro(app, false)
    }

    @After
    fun tearDown() {
        ProBilling.resetForTests()
        ProGate.setPro(app, false)
    }

    @Test
    fun `R1 restore reports None Owned Pending and Failed to subscribers`() {
        val seen = mutableListOf<ProBilling.State>()
        ProBilling.subscribe { state, replay -> if (!replay) seen += state }

        gateway.purchases = emptyList()
        ProBilling.restore(app, testBuild = false) { }
        gateway.releaseNext(); idle()
        gateway.releasePurchases(); idle()
        assertEquals(ProBilling.Op.RESTORE, ProBilling.lastOperation())
        assertTrue(seen.last() == ProBilling.State.None)

        ProBilling.resetForTests()
        ProBilling.gatewayFactory = { gateway }
        seen.clear()
        ProBilling.subscribe { state, replay -> if (!replay) seen += state }
        gateway.purchases = listOf(
            ProBilling.Owned("scanwhirl_pro", acknowledged = true, token = "owned")
        )
        ProBilling.restore(app, testBuild = false) { }
        gateway.releaseNext(); idle()
        gateway.releasePurchases(); idle()
        assertEquals(ProBilling.State.Owned, seen.last())
        assertTrue(purchased())

        ProBilling.resetForTests()
        ProBilling.gatewayFactory = { gateway }
        ProGate.setPro(app, false)
        seen.clear()
        ProBilling.subscribe { state, replay -> if (!replay) seen += state }
        gateway.purchases = listOf(
            ProBilling.Owned(
                "scanwhirl_pro", acknowledged = false, token = "pend",
                state = ProBilling.PurchaseState.PENDING
            )
        )
        ProBilling.restore(app, testBuild = false) { }
        gateway.releaseNext(); idle()
        gateway.releasePurchases(); idle()
        assertEquals(ProBilling.State.Pending, seen.last())
        assertFalse(purchased())

        ProBilling.resetForTests()
        ProBilling.gatewayFactory = { gateway }
        seen.clear()
        ProBilling.subscribe { state, replay -> if (!replay) seen += state }
        gateway.purchases = null
        ProBilling.restore(app, testBuild = false) { }
        gateway.releaseNext(); idle()
        gateway.releasePurchases(); idle()
        assertTrue(seen.last() is ProBilling.State.Failed)
    }

    @Test
    fun `R1b a Restore that answers during a Buy never takes the Buy's callback`() {
        val restoreStates = mutableListOf<ProBilling.State>()
        gateway.purchases = emptyList()
        ProBilling.restore(app, testBuild = false) { restoreStates += it }
        gateway.releaseNext(); idle() // restore connect; its purchase query is held

        val buyStates = mutableListOf<ProBilling.State>()
        ProBilling.buy(activity(), testBuild = false) { buyStates += it }
        gateway.releaseNext(); idle() // buy connect
        gateway.releaseNext(); idle() // buy offer -> launch
        assertTrue(ProBilling.isPurchaseInFlight())

        gateway.releasePurchases(); idle() // Restore answers "nothing" while the Buy is open
        assertEquals(ProBilling.State.None, restoreStates.last())
        assertTrue("the Buy is still open", ProBilling.isPurchaseInFlight())
        assertTrue("the Buy never saw the Restore result", buyStates.none { it == ProBilling.State.None })

        gateway.emit(
            ProBilling.Event.Purchased(
                ProBilling.Owned("scanwhirl_pro", acknowledged = true, token = "paid")
            )
        )
        idle()
        assertEquals(ProBilling.State.Owned, buyStates.last())
        assertTrue(purchased())
        assertFalse(ProBilling.isPurchaseInFlight())
    }

    @Test
    fun `R2 startup empty result after Buy launches does not drop the purchase`() {
        ProBilling.refreshOwned(app, testBuild = false)
        gateway.releaseNext(); idle() // connect; purchase query is held

        val states = mutableListOf<ProBilling.State>()
        val activity = activity()
        ProBilling.buy(activity, testBuild = false) { states += it }
        gateway.releaseNext(); idle() // buy connect
        gateway.releaseNext(); idle() // buy offer -> launch
        assertEquals(listOf("offer"), gateway.launched)
        assertEquals(0, gateway.listenerClears)
        assertTrue(ProBilling.isPurchaseInFlight())

        gateway.purchases = emptyList()
        gateway.releasePurchases(); idle()
        assertEquals("the startup snapshot must not clear the listener", 0, gateway.listenerClears)
        assertTrue("Buy stays open across the stale snapshot", ProBilling.isPurchaseInFlight())
        assertFalse(purchased())

        gateway.emit(
            ProBilling.Event.Purchased(
                ProBilling.Owned("scanwhirl_pro", acknowledged = true, token = "paid")
            )
        )
        idle()
        assertTrue(purchased())
        assertEquals(ProBilling.State.Owned, states.last())
        assertFalse(ProBilling.isPurchaseInFlight())
    }

    @Test
    fun `R3 a recreated subscriber receives the terminal state and a backgrounded Buy ends as None`() {
        val a = mutableListOf<ProBilling.State>()
        val stopA = ProBilling.subscribe { state, replay -> if (!replay) a += state }
        val activity = activity()
        ProBilling.buy(activity, testBuild = false) { }
        stopA()
        val b = mutableListOf<ProBilling.State>()
        ProBilling.subscribe { state, replay ->
            if (replay) assertTrue(ProBilling.isPurchaseInFlight() || state == ProBilling.State.Busy)
            else b += state
        }

        gateway.releaseNext(); idle()
        gateway.releaseNext(); idle()
        gateway.emit(ProBilling.Event.Cancelled)
        idle()

        assertTrue("A was detached", a.none { it == ProBilling.State.None })
        assertEquals(ProBilling.State.None, b.last())
        assertFalse(ProBilling.isPurchaseInFlight())

        ProBilling.resetForTests()
        // A fresh fake: the first half already launched once, so its `launched` list is not empty.
        gateway = HoldingGateway()
        ProBilling.gatewayFactory = { gateway }
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val hosted = controller.get()
        val states = mutableListOf<ProBilling.State>()
        ProBilling.buy(hosted, testBuild = false) { states += it }
        gateway.releaseNext(); idle()
        controller.pause()
        gateway.releaseNext(); idle()
        assertTrue("a stopped activity must not launch", gateway.launched.isEmpty())
        assertEquals(ProBilling.State.None, states.last())
        assertFalse(ProBilling.isPurchaseInFlight())
    }

    @Test
    fun `R4 already-owned with a null or empty re-query releases Buy`() {
        gateway.launchResult = ProBilling.LaunchResult.ALREADY_OWNED
        gateway.purchases = null
        val states = mutableListOf<ProBilling.State>()
        ProBilling.buy(activity(), testBuild = false) { states += it }
        gateway.releaseNext(); idle()
        gateway.releaseNext(); idle()
        gateway.releasePurchases(); idle()
        assertTrue(states.last() is ProBilling.State.Failed)
        assertFalse(ProBilling.isPurchaseInFlight())

        gateway.purchases = emptyList()
        gateway.launchResult = ProBilling.LaunchResult.ALREADY_OWNED
        ProBilling.buy(activity(), testBuild = false) { states += it }
        gateway.releaseNext(); idle()
        gateway.releaseNext(); idle()
        gateway.releasePurchases(); idle()
        assertFalse("a second tap works after the empty re-query", ProBilling.isPurchaseInFlight())
        assertTrue(states.last() is ProBilling.State.Failed)
        assertEquals(2, gateway.launched.size)
    }

    @Test
    fun `R5 a throw inside a posted price query or ack releases Buy and stays on main`() {
        gateway.throwOnPrice = true
        val states = mutableListOf<ProBilling.State>()
        ProBilling.buy(activity(), testBuild = false) { states += it }
        gateway.releaseNext(); idle()
        gateway.releaseNext(); idle()
        assertTrue(states.last() is ProBilling.State.Failed)
        assertFalse(ProBilling.isPurchaseInFlight())

        ProBilling.resetForTests()
        ProBilling.gatewayFactory = { gateway }
        gateway.throwOnPrice = false
        gateway.throwOnAck = true
        states.clear()
        ProBilling.buy(activity(), testBuild = false) { states += it }
        gateway.releaseNext(); idle()
        gateway.releaseNext(); idle()
        gateway.emit(
            ProBilling.Event.Purchased(
                ProBilling.Owned("scanwhirl_pro", acknowledged = false, token = "ack")
            )
        )
        idle()
        gateway.releaseNext(); idle()
        assertTrue(states.any { it is ProBilling.State.Failed || it is ProBilling.State.Finishing })
        assertFalse(ProBilling.isPurchaseInFlight())
        assertFalse(purchased())
    }

    @Test
    fun `R6 a late answer is not relabelled as the new locale`() {
        val english = localeTag()
        ProBilling.price(app) { }
        val config = android.content.res.Configuration(app.resources.configuration)
        config.setLocale(java.util.Locale.FRANCE)
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(config, app.resources.displayMetrics)
        gateway.releaseNext(); idle()
        gateway.releaseNext(); idle()
        assertNull(ProBilling.cachedPrice(app))
        val enConfig = android.content.res.Configuration(app.resources.configuration)
        enConfig.setLocale(java.util.Locale.forLanguageTag(english.ifEmpty { "en" }))
        val englishContext = app.createConfigurationContext(enConfig)
        assertNull(
            "the old answer must not be stored under the new locale",
            ProBilling.cachedPrice(app)
        )
        assertTrue(ProBilling.cachedPrice(englishContext) == null || english.isEmpty())
    }

    @Test
    fun `R7 Buy launches the offer from its own query not a later prefetch`() {
        gateway.captureOffers = true
        ProBilling.price(app) { }
        gateway.releaseNext(); idle() // connect, registers offer waiter 0
        val activity = activity()
        ProBilling.buy(activity, testBuild = false) { }
        gateway.releaseNext(); idle() // buy connect, registers offer waiter 1
        assertEquals(2, gateway.capturedOffers.size)
        gateway.capturedOffers[1](ProBilling.Offer("$4.99", "buy-offer"))
        gateway.capturedOffers[0](ProBilling.Offer("₹100", "old-prefetch"))
        idle()
        assertEquals(listOf("buy-offer"), gateway.launched)
    }

    private fun localeTag(): String =
        app.resources.configuration.locales[0]?.toLanguageTag().orEmpty()
}
