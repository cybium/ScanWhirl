package com.scanwhirl.app.util

import android.app.Activity
import android.app.Application
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
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProBillingAuthenticityTest {
    private lateinit var app: Application
    private lateinit var binding: PlayBilling
    private lateinit var controller: ProBilling.Controller
    private val acked = mutableListOf<String>()
    private val states = mutableListOf<ProBilling.State>()

    private companion object {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val unrelatedKey by lazy {
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        }
    }

    @Before
    fun setUp() {
        ProBilling.resetForTests()
        app = ApplicationProvider.getApplicationContext()
        ProGate.setPro(app, false)
        binding = PlayBilling(app, Base64.getEncoder().encodeToString(keyPair.public.encoded))
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
        unsigned: Boolean = false,
        state: Int = 0,
        useProductList: Boolean = false,
        signingKey: PrivateKey = keyPair.private
    ): Purchase {
        val body = JSONObject()
            .put("packageName", packageName)
            .put("purchaseState", state)
            .put("purchaseToken", "fictional-test-token")
            .put("acknowledged", acknowledged)
        if (useProductList) body.put("productIds", org.json.JSONArray(listOf(product)))
        else body.put("productId", product)
        val sig = Signature.getInstance("SHA1withRSA").run {
            initSign(signingKey)
            update(body.toString().toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(sign())
        }
        if (modifyAfterSigning) body.put("purchaseToken", "different-fictional-test-token")
        return Purchase(body.toString(), if (unsigned) "" else sig)
    }

    private fun update(row: Purchase) {
        binding.onPurchaseUpdate(listOf(row))
    }

    private fun query(row: Purchase) {
        controller.applyOwned(binding.verifiedSnapshot(listOf(row)))
    }

    private fun purchased() = ProGate.isPro(app, testBuild = false)

    @Test fun `listener ignores unauthenticated or out of scope rows`() = rejectsInvalid(::update)

    @Test fun `query ignores unauthenticated or out of scope rows`() = rejectsInvalid(::query)

    private fun rejectsInvalid(deliver: (Purchase) -> Unit) {
        for (acknowledged in listOf(false, true)) {
            for ((label, purchase) in listOf(
                "modified" to row(acknowledged, modifyAfterSigning = true),
                "wrong signer" to row(acknowledged, signingKey = unrelatedKey),
                "unsigned" to row(acknowledged, unsigned = true),
                "wrong package" to row(acknowledged, packageName = "example.other"),
                "wrong product" to row(acknowledged, product = "example.other"),
                "unknown purchase state" to row(acknowledged, state = 1)
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

    @Test fun `signed product list row can restore Pro without acknowledgement when already acked`() {
        query(row(acknowledged = true, useProductList = true))
        assertEquals(listOf(ProBilling.State.Owned), states)
        assertTrue(purchased())
        assertTrue(acked.isEmpty())
    }

    @Test fun `verified pending snapshot revokes legacy cache but an update alone does not`() {
        ProGate.setPro(app, true)
        val pending = row(acknowledged = false, state = 4)
        update(pending)
        assertTrue(purchased())
        query(pending)
        assertFalse(purchased())
        assertEquals(listOf(ProBilling.State.Pending, ProBilling.State.Pending), states)
        assertTrue(acked.isEmpty())
    }

    @Test fun `mixed pending and unverifiable rows do not prove non-ownership`() {
        ProGate.setPro(app, true)
        controller.applyOwned(binding.verifiedSnapshot(listOf(
            row(acknowledged = false, state = 4),
            row(acknowledged = true, unsigned = true)
        )))
        assertTrue(purchased())
        assertTrue(acked.isEmpty())
    }

    @Test fun `no licensing key does not turn a response into proof of purchase or loss`() {
        val unconfigured = PlayBilling(app, "")
        unconfigured.setListener(controller::handle)
        val paid = row(acknowledged = true)
        unconfigured.onPurchaseUpdate(listOf(paid))
        controller.applyOwned(unconfigured.verifiedSnapshot(listOf(paid)))
        assertFalse(purchased())
        assertTrue(acked.isEmpty())

        ProGate.setPro(app, true)
        controller.applyOwned(unconfigured.verifiedSnapshot(emptyList()))
        assertTrue(purchased())
    }
}
