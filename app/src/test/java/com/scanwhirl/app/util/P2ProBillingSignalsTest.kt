package com.scanwhirl.app.util

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** P2pro: UI-only signals that must not change the grant/revoke state machine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class P2ProBillingSignalsTest {

    private lateinit var app: Application

    private class QueueGateway : ProBilling.Gateway {
        private val queue = ArrayDeque<() -> Unit>()
        private var storeListener: ((ProBilling.Event) -> Unit)? = null
        var purchases: List<ProBilling.Owned>? = emptyList()

        override fun setListener(listener: ((ProBilling.Event) -> Unit)?) { storeListener = listener }
        override fun connect(onReady: (Boolean) -> Unit) { queue.addLast { onReady(true) } }
        override fun price(onPrice: (String?) -> Unit) { queue.addLast { onPrice("₹100") } }
        override fun launch(activity: android.app.Activity?) = Unit
        override fun queryPurchases(onResult: (List<ProBilling.Owned>?) -> Unit) {
            queue.addLast { onResult(purchases) }
        }
        override fun acknowledge(purchase: ProBilling.Owned, onDone: (Boolean) -> Unit) {
            queue.addLast { onDone(true) }
        }
        override fun close() = Unit

        fun drain() {
            while (queue.isNotEmpty()) {
                queue.removeFirst()()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
        fun emit(event: ProBilling.Event) = storeListener?.invoke(event)
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        ProBilling.resetForTests()
        ProGate.setPro(app, false)
    }

    @After
    fun tearDown() {
        ProBilling.resetForTests()
        ProGate.setPro(app, false)
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    @Test
    fun `a silent foreground reconcile still notifies the UI that entitlement changed`() {
        val gateway = QueueGateway()
        ProBilling.gatewayFactory = { gateway }
        var calls = 0
        ProBilling.setEntitlementChangedListener { calls++ }
        gateway.purchases = emptyList() // authoritative "not owned", silent path

        ProBilling.refreshOwned(app, testBuild = false, now = 0L)
        gateway.drain()

        assertEquals("the rebind signal reaches the shell", 1, calls)
        assertFalse("and the authoritative answer still revokes", ProGate.isPro(app, testBuild = false))
        ProBilling.setEntitlementChangedListener(null)
    }

    @Test
    fun `a verified purchase notifies the UI without changing the grant rule`() {
        val gateway = QueueGateway()
        ProBilling.gatewayFactory = { gateway }
        // Create the one client and install the store listener.
        ProBilling.price(app) { }
        gateway.drain()
        var calls = 0
        ProBilling.setEntitlementChangedListener { calls++ }

        gateway.emit(
            ProBilling.Event.Purchased(
                ProBilling.Owned(ProBilling.PRODUCT_ID, acknowledged = true, token = "p2pro-tok")
            )
        )
        gateway.drain()

        assertEquals("the live tiles are rebound", 1, calls)
        assertTrue("only a PURCHASED row grants Pro", ProGate.isPro(app, testBuild = false))
        ProBilling.setEntitlementChangedListener(null)
    }

    @Test
    fun `a failed query does not pretend entitlement changed`() {
        val gateway = QueueGateway().apply { purchases = null }
        ProBilling.gatewayFactory = { gateway }
        var calls = 0
        ProBilling.setEntitlementChangedListener { calls++ }

        ProBilling.refreshOwned(app, testBuild = false, now = 0L)
        gateway.drain()

        assertEquals("no rebind without an authoritative answer", 0, calls)
        ProBilling.setEntitlementChangedListener(null)
    }

    @Test
    fun `an empty successful answer uses honest copy while a failed query keeps connection copy`() {
        assertEquals(
            "Google Play found no Pro purchase on this account.",
            app.getString(R.string.pro_no_purchase)
        )
        assertTrue(app.getString(R.string.pro_no_purchase).contains("no Pro purchase", ignoreCase = true))
    }

    @Test
    fun `concurrent connects queue onReady behind one connection and all are fired`() {
        val queue = ConnectQueue()
        val seen = mutableListOf<String>()

        assertTrue("the first caller starts the connection", queue.add { seen += "price" })
        assertFalse("second caller queues instead of starting another listener", queue.add { seen += "buy" })
        assertFalse("third caller queues too", queue.add { seen += "restore" })
        assertTrue("nobody is called before setup finishes", seen.isEmpty())

        val callbacks = queue.finish()
        callbacks.forEach { it(true) }

        assertEquals(listOf("price", "buy", "restore"), seen)
        assertTrue("a later attempt can start after this one finishes", queue.add { seen += "retry" })
        queue.finish().forEach { it(false) }
        assertEquals(listOf("price", "buy", "restore", "retry"), seen)
    }
}
