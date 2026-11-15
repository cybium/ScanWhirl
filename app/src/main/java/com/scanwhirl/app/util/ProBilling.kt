package com.scanwhirl.app.util

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * The one place Play Billing lives: one-time in-app product [PRODUCT_ID], the localized price,
 * the purchase flow and "Restore purchase".
 *
 * Everything that talks to Play is behind [Gateway], so the decision logic (acknowledge, then
 * unlock; pending; cancel; restore; a refund or a missing purchase in a release build) is a plain
 * unit test with a fake. A phone without Play Store (Robolectric, some emulators) never crashes:
 * the client reports "unavailable" and the UI shows a short sheet.
 *
 * The product is INAPP (one time, non-consumable) and must be created in Play Console after the
 * first upload that contains this library.
 */
object ProBilling {

    const val PRODUCT_ID = "scanwhirl_pro"

    /** One purchase as the store reports it. */
    data class Owned(val productId: String, val acknowledged: Boolean, val token: String)

    sealed class Event {
        data class Purchased(val purchase: Owned) : Event()
        object Pending : Event()
        object Cancelled : Event()
        data class Failed(val message: String) : Event()
    }

    sealed class State {
        object Owned : State()
        object None : State()
        object Pending : State()
        data class Failed(val message: String) : State()
    }

    /** The billing seam: [PlayBilling] in the app, a fake in tests. */
    interface Gateway {
        fun setListener(listener: (Event) -> Unit)
        fun connect(onReady: (Boolean) -> Unit)
        fun price(onPrice: (String?) -> Unit)
        fun launch(activity: Activity?)
        fun queryPurchases(onResult: (List<Owned>) -> Unit)
        fun acknowledge(purchase: Owned, onDone: (Boolean) -> Unit)
        fun close()
    }

    /** Test seam: replaced before use. */
    @Volatile
    var gatewayFactory: (Context) -> Gateway = { PlayBilling(it) }

    @Volatile
    private var gateway: Gateway? = null

    /** Once per process is enough for the start-up ownership check. */
    @Volatile
    private var checkedOnStart = false

    private fun gateway(context: Context): Gateway =
        gateway ?: gatewayFactory(context.applicationContext).also { gateway = it }

    /** Shows Play's localized price when it can be fetched; [onPrice] gets null otherwise. */
    fun price(context: Context, onPrice: (String?) -> Unit) {
        runCatching {
            val client = gateway(context)
            client.connect { ok ->
                if (!ok) {
                    onPrice(null)
                    return@connect
                }
                client.price(onPrice)
            }
        }.onFailure { onPrice(null) }
    }

    /** Opens the Play purchase sheet from "Unlock Pro". */
    fun buy(activity: Activity, onState: (State) -> Unit) {
        // Test builds are Pro unless QA forced Free; there is nothing to buy and no store to ask.
        if (com.scanwhirl.app.BuildConfig.TEST_BUILD) {
            onState(State.Owned)
            return
        }
        val context = activity.applicationContext
        runCatching {
            val client = gateway(context)
            val controller = Controller(context, client, com.scanwhirl.app.BuildConfig.TEST_BUILD, onState)
            client.setListener { event -> controller.handle(event) }
            client.connect { ok ->
                if (ok) {
                    client.launch(activity)
                } else {
                    onState(State.Failed("Google Play isn't available on this phone."))
                }
            }
        }.onFailure { onState(State.Failed("Google Play isn't available on this phone.")) }
    }

    /** "Restore purchase": anything owned unlocks Pro. */
    fun restore(context: Context, onState: (State) -> Unit) {
        if (com.scanwhirl.app.BuildConfig.TEST_BUILD) {
            onState(if (ProGate.isPro(context)) State.Owned else State.None)
            return
        }
        runCatching {
            val client = gateway(context)
            val controller = Controller(context, client, com.scanwhirl.app.BuildConfig.TEST_BUILD, onState)
            client.connect { ok ->
                if (!ok) {
                    onState(State.Failed("Google Play isn't available on this phone."))
                    return@connect
                }
                client.queryPurchases { controller.applyOwned(it) }
            }
        }.onFailure { onState(State.Failed("Google Play isn't available on this phone.")) }
    }

    /**
     * Cheap start-up check (ShellActivity.onResume): a purchase on another phone, a reinstall or
     * Family Library unlocks automatically; a refund or a missing purchase locks Pro again, but
     * only in a release build ([Controller.ownedState]).
     */
    fun refreshOwned(context: Context) {
        // Test builds are Pro by design; they never need to talk to Play on start-up.
        if (com.scanwhirl.app.BuildConfig.TEST_BUILD) return
        if (checkedOnStart) return
        checkedOnStart = true
        runCatching {
            val client = gateway(context)
            val controller = Controller(context, client, com.scanwhirl.app.BuildConfig.TEST_BUILD) { }
            client.connect { ok ->
                if (ok) client.queryPurchases { controller.applyOwned(it, silent = true) } else client.close()
            }
        }
    }

    /** Test seam: forget the cached client and the once-per-process check. */
    internal fun resetForTests() {
        runCatching { gateway?.close() }
        gateway = null
        checkedOnStart = false
        gatewayFactory = { PlayBilling(it) }
    }

    /**
     * Purchase handling, independent of Play: acknowledge a fresh purchase before unlocking,
     * ignore a cancel, surface a pending payment, and unlock/lock only in a release build.
     */
    internal class Controller(
        private val context: Context,
        private val gateway: Gateway,
        private val testBuild: Boolean,
        private val onState: (State) -> Unit
    ) {
        /** Returns the state after [event]; also reports it to [onState]. */
        fun handle(event: Event): State {
            val state = when (event) {
                is Event.Purchased -> {
                    val purchase = event.purchase
                    if (purchase.acknowledged) {
                        record(true)
                        State.Owned
                    } else {
                        var acked = false
                        gateway.acknowledge(purchase) { ok -> acked = ok }
                        if (!acked) {
                            // The store can acknowledge a moment later; an unacknowledged purchase
                            // is refunded automatically after three days, so Pro waits for it.
                            record(testBuild)
                            State.Failed("Finishing the purchase failed. Try Restore purchase.")
                        } else {
                            record(true)
                            State.Owned
                        }
                    }
                }
                Event.Pending -> State.Pending
                Event.Cancelled -> State.None
                is Event.Failed -> State.Failed(event.message)
            }
            onState(state)
            return state
        }

        /** Applies [purchases] from a restore/start-up query. */
        fun applyOwned(purchases: List<Owned>, silent: Boolean = false): State {
            val owned = purchases.any { it.productId == PRODUCT_ID }
            val state = if (owned) State.Owned else State.None
            record(if (owned) true else ownedState())
            if (!silent) onState(state)
            return state
        }

        /**
         * A release build follows the store: a refund or a purchase that no longer exists locks
         * Pro again. A test build keeps its Pro-for-review behaviour.
         */
        private fun ownedState(): Boolean = if (testBuild) true else false

        private fun record(pro: Boolean) {
            if (pro) {
                ProGate.setPro(context, true)
            } else if (!testBuild) {
                ProGate.setPro(context, false)
            }
        }
    }
}

/**
 * The real client. Every call is wrapped by [ProBilling], so a phone without Play Store simply
 * reports unavailable instead of crashing.
 */
private class PlayBilling(private val context: Context) : ProBilling.Gateway {

    private var client: BillingClient? = null
    private var listener: ((ProBilling.Event) -> Unit)? = null
    private var details: ProductDetails? = null

    override fun setListener(listener: (ProBilling.Event) -> Unit) {
        this.listener = listener
    }

    private fun client(): BillingClient = client ?: BillingClient.newBuilder(context)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .setListener { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.orEmpty().forEach { purchase -> dispatch(purchase) }
            }
        }
        .build()
        .also { client = it }

    private fun dispatch(purchase: Purchase) {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> listener?.invoke(
                ProBilling.Event.Purchased(
                    ProBilling.Owned(
                        productId = purchase.products.firstOrNull().orEmpty(),
                        acknowledged = purchase.isAcknowledged,
                        token = purchase.purchaseToken
                    )
                )
            )
            Purchase.PurchaseState.PENDING -> listener?.invoke(ProBilling.Event.Pending)
            else -> listener?.invoke(ProBilling.Event.Cancelled)
        }
    }

    override fun connect(onReady: (Boolean) -> Unit) {
        val c = client()
        if (c.isReady) {
            onReady(true)
            return
        }
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                onReady(result.responseCode == BillingClient.BillingResponseCode.OK)
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    override fun price(onPrice: (String?) -> Unit) {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(ProBilling.PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        client().queryProductDetailsAsync(params) { result, queryResult ->
            val product = if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                queryResult.productDetailsList.firstOrNull()
            } else {
                null
            }
            details = product
            onPrice(product?.oneTimePurchaseOfferDetails?.formattedPrice)
        }
    }

    override fun launch(activity: Activity?) {
        val detail = details ?: run {
            listener?.invoke(ProBilling.Event.Failed("The Pro product is not available yet."))
            return
        }
        val target = activity ?: return
        client().launchBillingFlow(
            target,
            BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(detail)
                            .build()
                    )
                )
                .build()
        )
    }

    override fun queryPurchases(onResult: (List<ProBilling.Owned>) -> Unit) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client().queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                onResult(emptyList())
                return@queryPurchasesAsync
            }
            onResult(
                purchases.map { purchase ->
                    ProBilling.Owned(
                        productId = purchase.products.firstOrNull().orEmpty(),
                        acknowledged = purchase.isAcknowledged,
                        token = purchase.purchaseToken
                    )
                }
            )
        }
    }

    override fun acknowledge(purchase: ProBilling.Owned, onDone: (Boolean) -> Unit) {
        client().acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.token).build()
        ) { result -> onDone(result.responseCode == BillingClient.BillingResponseCode.OK) }
    }

    override fun close() {
        runCatching { client?.endConnection() }
        client = null
        details = null
    }
}
