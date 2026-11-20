package com.scanwhirl.app.util

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.Purchase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProBillingAuthenticityTest {
    private lateinit var app: Application
    private lateinit var binding: ProBilling.Gateway
    private lateinit var controller: ProBilling.Controller
    private val acked = mutableListOf<String>()
    private val states = mutableListOf<ProBilling.State>()

    private companion object {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }

    @Before
    fun setUp() {
        ProBilling.resetForTests()
        app = ApplicationProvider.getApplicationContext()
        ProGate.setPro(app, false)
        val type = Class.forName("com.scanwhirl.app.util.PlayBilling")
        binding = type.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
            .newInstance(app) as ProBilling.Gateway
        controller = ProBilling.Controller(app, object : ProBilling.Gateway {
            override fun setListener(listener: ((ProBilling.Event) -> Unit)?) = Unit
            override fun connect(onReady: (Boolean) -> Unit) = onReady(true)
            override fun price(onPrice: (String?) -> Unit) = onPrice(null)
            override fun launch(activity: Activity?) = Unit
            override fun queryPurchases(onResult: (List<ProBilling.Owned>?) -> Unit) = onResult(null)
            override fun acknowledge(purchase: ProBilling.Owned, onDone: (Boolean) -> Unit) {
                acked += purchase.token
                onDone(true)
            }
            override fun close() = Unit
        }, testBuild = false) { states += it }
        binding.setListener(controller::handle)
    }

    @After
    fun tearDown() {
        binding.close()
        ProBilling.resetForTests()
        ProGate.setPro(app, false)
    }

    private fun row(
        acknowledged: Boolean,
        packageName: String = app.packageName,
        product: String = ProBilling.PRODUCT_ID,
        modifyAfterSigning: Boolean = false,
        unsigned: Boolean = false
    ): Purchase {
        val body = JSONObject()
            .put("packageName", packageName)
            .put("productId", product)
            .put("purchaseState", 0)
            .put("purchaseToken", "fictional-test-token")
            .put("acknowledged", acknowledged)
        val sig = Signature.getInstance("SHA1withRSA").run {
            initSign(keyPair.private)
            update(body.toString().toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(sign())
        }
        if (modifyAfterSigning) body.put("purchaseToken", "different-fictional-test-token")
        return Purchase(body.toString(), if (unsigned) "" else sig)
    }

    private fun update(row: Purchase) {
        binding.javaClass.getDeclaredMethod("dispatch", Purchase::class.java)
            .apply { isAccessible = true }.invoke(binding, row)
    }

    private fun query(row: Purchase) {
        val owned = binding.javaClass.getDeclaredMethod("owned", Purchase::class.java)
            .apply { isAccessible = true }.invoke(binding, row) as ProBilling.Owned?
        controller.applyOwned(owned?.let(::listOf))
    }

    private fun purchased() = ProGate.isPro(app, testBuild = false)

    @Test fun `listener ignores unauthenticated or out of scope rows`() = rejectsInvalid(::update)

    @Test fun `query ignores unauthenticated or out of scope rows`() = rejectsInvalid(::query)

    private fun rejectsInvalid(deliver: (Purchase) -> Unit) {
        for (acknowledged in listOf(false, true)) {
            for ((label, purchase) in listOf(
                "modified" to row(acknowledged, modifyAfterSigning = true),
                "unsigned" to row(acknowledged, unsigned = true),
                "wrong package" to row(acknowledged, packageName = "example.other"),
                "wrong product" to row(acknowledged, product = "example.other")
            )) {
                deliver(purchase)
                assertFalse("$label cannot grant Pro (acknowledged=$acknowledged)", purchased())
                assertTrue("$label must not be acknowledged", acked.isEmpty())
            }
        }
    }

    @Test fun `signed purchase is granted and acknowledged once across listener and query`() {
        val purchase = row(acknowledged = false)
        update(purchase)
        query(purchase)
        assertTrue(purchased())
        assertEquals(listOf("fictional-test-token"), acked)
    }

    @Test fun `an unverifiable ownership response does not revoke offline access`() {
        ProGate.setPro(app, true)
        query(row(acknowledged = true, modifyAfterSigning = true))
        assertTrue(purchased())
        assertTrue(acked.isEmpty())
        assertFalse(states.contains(ProBilling.State.Owned))
    }
}
