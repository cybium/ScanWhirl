package com.scanwhirl.app.util

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.BillingClient
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
 * Research judge §3 item 4 (and §4 item 2): a failed ownership query must never latch the
 * process into "checked once", a pending purchase that completes while disconnected must be
 * acknowledged on the next foreground reconcile, and a known paid entitlement survives it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProBillingRecoveryTest {

    private lateinit var app: Application
    private lateinit var gateway: QueueGateway

    /** Holds Play's answers until [drain], like the real client on another thread. */
    private class QueueGateway : ProBilling.Gateway {
        private var storeListener: ((ProBilling.Event) -> Unit)? = null
        private val queue = ArrayDeque<() -> Unit>()
        var purchases: List<ProBilling.Owned>? = emptyList()
        var connectResult = true
        var errorCode: Int? = null
        var priceText: String? = "₹100"
        var offerToken: String? = null
        var connectCalls = 0
        var queryCalls = 0
        val acknowledged = mutableListOf<String>()
        var holdAcks = false
        val ackWaiters = mutableListOf<(Boolean) -> Unit>()
        val ackResults = ArrayDeque<Boolean>()
        var launchResult = ProBilling.LaunchResult.STARTED
        val launched = mutableListOf<ProBilling.Offer>()

        override fun setListener(listener: ((ProBilling.Event) -> Unit)?) {
            storeListener = listener
        }

        override fun connect(onReady: (Boolean) -> Unit) {
            connectCalls++
            queue.addLast { onReady(connectResult) }
        }

        override fun price(onPrice: (String?) -> Unit) {
            queue.addLast { onPrice(priceText) }
        }

        override fun queryOffer(onOffer: (ProBilling.Offer?) -> Unit) {
            queue.addLast {
                val text = priceText
                onOffer(
                    text?.let {
                        ProBilling.Offer(it, "identity", offerToken = offerToken)
                    }
                )
            }
        }

        override fun lastErrorCode(): Int? = errorCode

        override fun launch(activity: Activity?) = Unit

        override fun launch(activity: Activity?, offer: ProBilling.Offer): ProBilling.LaunchResult {
            launched += offer
            return launchResult
        }

        override fun queryPurchases(onResult: (List<ProBilling.Owned>?) -> Unit) {
            queryCalls++
            queue.addLast { onResult(purchases) }
        }

        override fun acknowledge(purchase: ProBilling.Owned, onDone: (Boolean) -> Unit) {
            acknowledged += purchase.token
            val result = if (ackResults.isEmpty()) true else ackResults.removeFirst()
            if (holdAcks) ackWaiters += onDone else queue.addLast { onDone(result) }
        }

        override fun close() = Unit

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst()()
        }

        fun emit(event: ProBilling.Event) = storeListener?.invoke(event)
    }

    private fun paid(token: String, acknowledged: Boolean = true) =
        ProBilling.Owned("scanwhirl_pro", acknowledged = acknowledged, token = token)

    private fun purchased(): Boolean =
        app.getSharedPreferences("scanwhirl_prefs", 0).getBoolean("pro_unlocked", false)

    private fun refresh(now: Long = 0L) =
        ProBilling.refreshOwned(app, testBuild = false, now = now)

    private fun activity(): Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        ProBilling.resetForTests()
        gateway = QueueGateway()
        ProBilling.gatewayFactory = { gateway }
        ProGate.setPro(app, false)
    }

    @After
    fun tearDown() {
        ProBilling.resetForTests()
        ProGate.setPro(app, false)
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    /**
     * #4: the failed start-up query used to set `checkedOnStart` before anything succeeded, so
     * every later onResume in the process returned without asking Play.
     */
    @Test
    fun `a failed start-up query runs again on the next foreground and unlocks`() {
        gateway.purchases = null
        refresh()
        gateway.drain()
        assertFalse("a failed query grants nothing", purchased())

        gateway.purchases = listOf(paid("tok-recovered"))
        refresh()
        gateway.drain()

        assertEquals("the next foreground must ask Play again", 2, gateway.queryCalls)
        assertTrue("the successful answer unlocks Pro", purchased())
    }

    @Test
    fun `a failed query never revokes an already unlocked entitlement`() {
        ProGate.setPro(app, true)
        gateway.purchases = null

        refresh()
        gateway.drain()
        refresh()
        gateway.drain()

        assertTrue("a failed snapshot changes nothing", purchased())
    }

    @Test
    fun `a failed connection never revokes an already unlocked entitlement`() {
        ProGate.setPro(app, true)
        gateway.connectResult = false

        refresh()
        gateway.drain()

        assertTrue("Play being unreachable never locks a paying user out", purchased())
    }

    /**
     * A PENDING purchase that completes while the app is backgrounded is only detectable by a
     * later query. It must be acknowledged exactly once and unlock Pro.
     */
    @Test
    fun `a pending purchase that completed is acknowledged once on reconcile`() {
        gateway.purchases = listOf(
            ProBilling.Owned(
                "scanwhirl_pro", acknowledged = false, token = "pending-token",
                state = ProBilling.PurchaseState.PENDING
            )
        )
        refresh()
        gateway.drain()
        assertFalse("a pending purchase does not unlock yet", purchased())
        assertTrue("nothing is acknowledged while pending", gateway.acknowledged.isEmpty())

        // Play completed the payment while the process was disconnected.
        gateway.purchases = listOf(paid("pending-token", acknowledged = false))
        refresh()
        gateway.drain()
        assertTrue("the completed purchase unlocks Pro", purchased())
        assertEquals("the purchase is acknowledged once", listOf("pending-token"), gateway.acknowledged)

        // A later reconcile sees the same, now acknowledged purchase and must not ack again.
        gateway.purchases = listOf(paid("pending-token", acknowledged = true))
        refresh(now = 40_000L)
        gateway.drain()
        assertEquals("an acknowledged purchase is never acknowledged twice", listOf("pending-token"), gateway.acknowledged)
    }

    @Test
    fun `a successful reconcile is debounced but asked again later`() {
        gateway.purchases = listOf(paid("tok-debounce"))
        refresh(now = 1_000L)
        gateway.drain()
        assertEquals(1, gateway.queryCalls)

        refresh(now = 2_000L)
        gateway.drain()
        assertEquals("a quick second resume must not query Play again", 1, gateway.queryCalls)

        refresh(now = 1_000L + 31_000L)
        gateway.drain()
        assertEquals("a later foreground checks ownership again", 2, gateway.queryCalls)
    }

    @Test
    fun `already owned from the store listener unlocks and reconciles`() {
        val seen = mutableListOf<ProBilling.State>()
        ProBilling.subscribe { state, replay -> if (!replay) seen += state }
        gateway.purchases = listOf(paid("listener-owned"))
        // Any operation creates the one client and installs its purchase listener.
        ProBilling.price(app) { }
        gateway.drain()

        gateway.emit(ProBilling.Event.AlreadyOwned)
        gateway.drain()

        assertTrue(purchased())
        assertTrue("the listener path reports the unlock", seen.contains(ProBilling.State.Owned))
    }

    @Test
    fun `billing response codes map to the right message`() {
        val connection = app.getString(R.string.pro_error_connection)
        val unavailable = app.getString(R.string.pro_error_unavailable)
        val generic = app.getString(R.string.pro_error_purchase)

        assertEquals(
            connection,
            ProBilling.failureText(app, BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)
        )
        assertEquals(
            connection,
            ProBilling.failureText(app, BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
        )
        assertEquals(
            connection,
            ProBilling.failureText(app, BillingClient.BillingResponseCode.NETWORK_ERROR)
        )
        assertEquals(
            unavailable,
            ProBilling.failureText(app, BillingClient.BillingResponseCode.BILLING_UNAVAILABLE)
        )
        assertEquals(
            "everything else stays generic",
            generic,
            ProBilling.failureText(app, BillingClient.BillingResponseCode.ERROR)
        )
        assertNull(
            "a cancel is silent, not an error",
            ProBilling.failureText(app, BillingClient.BillingResponseCode.USER_CANCELED)
        )
    }

    @Test
    fun `a failed connection reports the mapped unavailable message`() {
        gateway.connectResult = false
        gateway.errorCode = BillingClient.BillingResponseCode.BILLING_UNAVAILABLE
        val states = mutableListOf<ProBilling.State>()

        ProBilling.buy(activity(), testBuild = false) { states += it }
        gateway.drain()

        assertEquals(
            app.getString(R.string.pro_error_unavailable),
            (states.last() as ProBilling.State.Failed).message
        )
    }

    @Test
    fun `a launch failure says Play cannot be reached`() {
        gateway.launchResult = ProBilling.LaunchResult.CONNECTION
        val states = mutableListOf<ProBilling.State>()

        ProBilling.buy(activity(), testBuild = false) { states += it }
        gateway.drain()

        assertEquals(
            app.getString(R.string.pro_error_connection),
            (states.last() as ProBilling.State.Failed).message
        )
        assertFalse(ProBilling.isPurchaseInFlight())
    }

    @Test
    fun `the offer token from the priced offer reaches the launch`() {
        gateway.priceText = "$4.99"
        gateway.offerToken = "play-offer-token"
        val states = mutableListOf<ProBilling.State>()

        ProBilling.buy(activity(), testBuild = false) { states += it }
        gateway.drain()

        val launched = gateway.launched.single()
        assertEquals("$4.99", launched.price)
        assertEquals("play-offer-token", launched.offerToken)
        assertEquals("the query identity is not repurposed", "identity", launched.token)
        assertNull(states.lastOrNull())
    }

    /**
     * fixbilling: critic D1/D2 (lead ruling). ITEM_ALREADY_OWNED is not an entitlement by
     * itself: when the confirming query fails nothing unlocks, the Buy is released, and the
     * next foreground asks again.
     */
    @Test
    fun `already owned with a failing query does not unlock and retries on the next foreground`() {
        gateway.launchResult = ProBilling.LaunchResult.ALREADY_OWNED
        gateway.purchases = null
        val states = mutableListOf<ProBilling.State>()

        ProBilling.buy(activity(), testBuild = false) { states += it }
        gateway.drain()

        assertEquals(
            app.getString(R.string.pro_error_connection),
            (states.last() as ProBilling.State.Failed).message
        )
        assertFalse("already owned alone grants nothing", purchased())
        assertFalse("the Buy is released", ProBilling.isPurchaseInFlight())
        assertTrue("nothing to acknowledge", gateway.acknowledged.isEmpty())

        val queriesAfterBuy = gateway.queryCalls
        gateway.purchases = listOf(paid("owned-token"))
        refresh(now = 1_000L)
        gateway.drain()
        assertEquals(
            "the unanswered purchase stays due on the next foreground",
            queriesAfterBuy + 1,
            gateway.queryCalls
        )
        assertTrue("the successful reconcile grants Pro", purchased())
    }

    /**
     * fixbilling: critic D1/D2 (lead ruling). A PENDING purchase never unlocks and is never
     * acknowledged; ITEM_ALREADY_OWNED with a pending-only answer shows the pending copy and
     * keeps the reconcile due.
     */
    @Test
    fun `already owned with a pending purchase stays locked and unacknowledged`() {
        gateway.launchResult = ProBilling.LaunchResult.ALREADY_OWNED
        gateway.purchases = listOf(
            ProBilling.Owned(
                "scanwhirl_pro", acknowledged = false, token = "pending-token",
                state = ProBilling.PurchaseState.PENDING
            )
        )
        val states = mutableListOf<ProBilling.State>()

        ProBilling.buy(activity(), testBuild = false) { states += it }
        gateway.drain()

        assertEquals(ProBilling.State.Pending, states.last())
        assertFalse("a pending purchase never unlocks", purchased())
        assertTrue("a pending purchase is never acknowledged", gateway.acknowledged.isEmpty())
        assertFalse(ProBilling.isPurchaseInFlight())

        val queriesAfterBuy = gateway.queryCalls
        refresh(now = 1_000L)
        gateway.drain()
        assertEquals(
            "pending keeps the reconcile due for the next foreground",
            queriesAfterBuy + 1,
            gateway.queryCalls
        )
    }

    /** fixbilling: critic D1/D2 (lead ruling): unlock only on a PURCHASED row, acknowledge once. */
    @Test
    fun `already owned applies and acknowledges a purchased row exactly once`() {
        gateway.launchResult = ProBilling.LaunchResult.ALREADY_OWNED
        gateway.purchases = listOf(paid("owned-token", acknowledged = false))
        val states = mutableListOf<ProBilling.State>()

        ProBilling.buy(activity(), testBuild = false) { states += it }
        gateway.drain()

        assertEquals(ProBilling.State.Owned, states.last())
        assertEquals("the re-queried purchase is acknowledged", listOf("owned-token"), gateway.acknowledged)
        assertTrue(purchased())

        gateway.purchases = listOf(paid("owned-token", acknowledged = true))
        refresh(now = 40_000L)
        gateway.drain()
        assertEquals("an acknowledged purchase is never acknowledged twice", listOf("owned-token"), gateway.acknowledged)
    }

    /**
     * Judge must-fix 1: acknowledgement state is per purchase token and shared by the listener,
     * the foreground reconcile and ALREADY_OWNED, so overlapping paths send one acknowledge.
     */
    @Test
    fun `overlapping listener reconcile and already owned acknowledge the token once`() {
        gateway.holdAcks = true
        ProBilling.price(app) { } // creates the one client and installs its listener
        gateway.drain()
        val purchase = paid("shared-token", acknowledged = false)

        gateway.emit(ProBilling.Event.Purchased(purchase))

        gateway.purchases = listOf(purchase)
        refresh(now = 1_000L)
        gateway.drain()

        gateway.launchResult = ProBilling.LaunchResult.ALREADY_OWNED
        val states = mutableListOf<ProBilling.State>()
        ProBilling.buy(activity(), testBuild = false) { states += it }
        gateway.drain()

        assertEquals(
            "one acknowledge for one token across listener, reconcile and already-owned",
            1,
            gateway.acknowledged.size
        )

        gateway.ackWaiters.removeAt(0)(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("the single acknowledge grants Pro", purchased())
        assertEquals("the Buy follows the in-flight acknowledgement", ProBilling.State.Owned, states.last())
        assertFalse(ProBilling.isPurchaseInFlight())

        // A stale snapshot still reports isAcknowledged=false; the process remembers the success.
        gateway.purchases = listOf(purchase)
        refresh(now = 40_000L)
        gateway.drain()
        assertEquals("a stale unacknowledged snapshot is not acknowledged again", 1, gateway.acknowledged.size)
    }

    /** Judge must-fix 1: a failed acknowledge retries itself, then stops once Play agrees. */
    @Test
    fun `a failed acknowledge is retried without another foreground then stops`() {
        gateway.ackResults.addAll(listOf(false, true))
        ProBilling.price(app) { }
        gateway.drain()

        gateway.emit(ProBilling.Event.Purchased(paid("retry-token", acknowledged = false)))
        gateway.drain()
        assertEquals(1, gateway.acknowledged.size)
        assertFalse(purchased())

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
        gateway.drain()
        assertEquals("retried without a new foreground", 2, gateway.acknowledged.size)
        assertTrue("the successful retry grants Pro", purchased())

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(200))
        gateway.drain()
        assertEquals("a successful retry is not retried again", 2, gateway.acknowledged.size)
    }

    /**
     * Judge must-fix 2: a debounced foreground schedules one deferred reconcile, so a purchase
     * that completes in the background is found without another resume.
     */
    @Test
    fun `a suppressed foreground schedules one deferred reconcile`() {
        gateway.purchases = emptyList()
        refresh(now = 0L)
        gateway.drain()
        val before = gateway.queryCalls

        gateway.purchases = listOf(paid("late-token", acknowledged = false))
        refresh(now = 2_000L)
        gateway.drain()
        assertEquals("the resume itself is debounced", before, gateway.queryCalls)

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(28))
        gateway.drain()

        assertEquals("the deferred reconcile runs at the end of the window", before + 1, gateway.queryCalls)
        assertTrue("the background purchase unlocks Pro", purchased())
        assertEquals(listOf("late-token"), gateway.acknowledged)
    }

    @Test
    fun `a pending listener update keeps the next foreground due`() {
        gateway.purchases = emptyList()
        ProBilling.price(app) { }
        gateway.drain()
        refresh(now = 0L)
        gateway.drain()
        val before = gateway.queryCalls

        gateway.emit(ProBilling.Event.Pending)
        refresh(now = 2_000L)
        gateway.drain()

        assertEquals("a pending purchase is not debounced away", before + 1, gateway.queryCalls)
    }
}
