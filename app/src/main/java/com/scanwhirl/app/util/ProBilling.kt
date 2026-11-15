package com.scanwhirl.app.util

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
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
 * the purchase flow, "Restore purchase" and the start-up ownership check.
 *
 * Everything that talks to Play is behind [Gateway], so the decisions (acknowledge asynchronously,
 * pending versus paid, a failed query never locks Pro, a refund locks it only in a release build)
 * are plain unit tests with a fake that answers **later**, like the real client.
 *
 * A phone without Play Store (Robolectric, some emulators) never crashes: the client reports
 * unavailable and the UI shows a short sheet. The product is INAPP (one time, non-consumable) and
 * must be created in Play Console after the first upload that contains this library.
 */
object ProBilling {

    const val PRODUCT_ID = "scanwhirl_pro"

    enum class PurchaseState { PURCHASED, PENDING, UNSPECIFIED }

    /** One purchase as the store reports it. */
    data class Owned(
        val productId: String,
        val acknowledged: Boolean,
        val token: String,
        val state: PurchaseState = PurchaseState.PURCHASED
    )

    sealed class Event {
        data class Purchased(val purchase: Owned) : Event()
        object Pending : Event()
        object Cancelled : Event()
        data class Failed(val message: String) : Event()
    }

    sealed class State {
        object Owned : State()

        /** Asked the store to acknowledge; Pro follows when Play answers. */
        object Finishing : State()
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
        /** `null` = the query failed; an empty list = a real answer with nothing owned. */
        fun queryPurchases(onResult: (List<Owned>?) -> Unit)
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

    /**
     * The last localized price Play answered with. The shell starts the query in onCreate, when
     * no Pro screen exists yet; every Pro screen created later shows this instead of falling back
     * to the hard-coded ₹100 copy.
     */
    @Volatile
    private var lastPrice: String? = null

    private val mainPost: (() -> Unit) -> Unit = { Handler(Looper.getMainLooper()).post(it) }

    private fun gateway(context: Context): Gateway =
        gateway ?: gatewayFactory(context.applicationContext).also { gateway = it }

    /** The last price Play reported, or null when it has not answered yet. */
    fun cachedPrice(): String? = lastPrice

    /** Shows Play's localized price when it can be fetched; [onPrice] gets null otherwise. */
    fun price(context: Context, onPrice: (String?) -> Unit) {
        runCatching {
            val client = gateway(context)
            client.connect { ok ->
                if (!ok) {
                    onPrice(null)
                    return@connect
                }
                client.price { text ->
                    if (text != null) lastPrice = text
                    onPrice(text)
                }
            }
        }.onFailure { onPrice(null) }
    }

    /** Opens the Play purchase sheet from "Unlock Pro". */
    fun buy(activity: Activity, onState: (State) -> Unit) =
        buy(activity, com.scanwhirl.app.BuildConfig.TEST_BUILD, onState)

    /**
     * The purchase flow with the test-build switch exposed, so a unit test can drive the real
     * release path (the gateway is a fake) instead of the test build's "already Pro" shortcut.
     */
    @androidx.annotation.VisibleForTesting
    internal fun buy(activity: Activity, testBuild: Boolean, onState: (State) -> Unit) {
        // Test builds are Pro unless QA forced Free; there is nothing to buy and no store to ask.
        if (testBuild) {
            onState(State.Owned)
            return
        }
        val context = activity.applicationContext
        runCatching {
            val client = gateway(context)
            val controller = Controller(context, client, testBuild, mainPost, onState)
            client.setListener { event -> controller.handle(event) }
            client.connect { ok ->
                if (!ok) {
                    onState(State.Failed("Google Play isn't available on this phone."))
                    return@connect
                }
                // Never rely on the launch-time query: it may have failed (Play busy, offline) and
                // the cached details would then make "Unlock Pro" say "not available" for the whole
                // session. Ask again now, and only open the sheet when the product really exists.
                client.price { price ->
                    if (price == null) {
                        onState(State.Failed("The Pro product is not available yet."))
                    } else {
                        client.launch(activity)
                    }
                }
            }
        }.onFailure { onState(State.Failed("Google Play isn't available on this phone.")) }
    }

    /** "Restore purchase": anything owned unlocks Pro (and is acknowledged). */
    fun restore(context: Context, onState: (State) -> Unit) {
        if (com.scanwhirl.app.BuildConfig.TEST_BUILD) {
            onState(if (ProGate.isPro(context)) State.Owned else State.None)
            return
        }
        runCatching {
            val client = gateway(context)
            val controller = Controller(context, client, com.scanwhirl.app.BuildConfig.TEST_BUILD, mainPost, onState)
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
     * Cheap start-up check (ShellActivity.onResume): a purchase on another phone with the same Google
     * account or a reinstall unlocks automatically (in-app purchases are NOT shared through Family
     * Library, so the UI must never promise family sharing); a refund locks Pro again, but only in a release build
     * and only when Play answers OK without the product.
     */
    fun refreshOwned(context: Context) {
        // Test builds are Pro by design; they never need to talk to Play on start-up.
        if (com.scanwhirl.app.BuildConfig.TEST_BUILD) return
        if (checkedOnStart) return
        checkedOnStart = true
        runCatching {
            val client = gateway(context)
            val controller = Controller(context, client, com.scanwhirl.app.BuildConfig.TEST_BUILD, mainPost) { }
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
        lastPrice = null
        gatewayFactory = { PlayBilling(it) }
    }

    /**
     * Purchase handling, independent of Play's threading. Acknowledge happens asynchronously:
     * Play answers on its own thread, so [post] hands the decision back to the main thread and the
     * UI sees [State.Finishing] until then.
     */
    internal class Controller(
        private val context: Context,
        private val gateway: Gateway,
        private val testBuild: Boolean,
        private val post: (() -> Unit) -> Unit = { it() },
        private val onState: (State) -> Unit
    ) {
        /** Handles one purchase update. */
        fun handle(event: Event) {
            when (event) {
                is Event.Purchased -> onPurchase(event.purchase)
                Event.Pending -> onState(State.Pending)
                Event.Cancelled -> onState(State.None)
                is Event.Failed -> onState(State.Failed(event.message))
            }
        }

        /**
         * Applies a restore / start-up query. `null` is a failed query: change nothing (a paying
         * user offline or with Play busy must keep Pro). Only an OK answer that does not contain
         * the product locks Pro, and only in a release build.
         */
        fun applyOwned(purchases: List<Owned>?, silent: Boolean = false) {
            if (purchases == null) return
            val mine = purchases.filter { it.productId == PRODUCT_ID }
            val bought = mine.filter { it.state == PurchaseState.PURCHASED }
            if (bought.isEmpty()) {
                val pending = mine.any { it.state == PurchaseState.PENDING }
                if (pending) {
                    if (!silent) onState(State.Pending)
                    return
                }
                if (!testBuild) record(false)
                if (!silent) onState(State.None)
                return
            }
            acknowledgeThenUnlock(bought, silent)
        }

        private fun onPurchase(purchase: Owned) {
            when (purchase.state) {
                PurchaseState.PURCHASED -> {
                    if (purchase.acknowledged) {
                        unlock()
                    } else {
                        acknowledgeThenUnlock(listOf(purchase), silent = false)
                    }
                }
                PurchaseState.PENDING -> onState(State.Pending)
                PurchaseState.UNSPECIFIED -> onState(State.None)
            }
        }

        private fun acknowledgeThenUnlock(purchases: List<Owned>, silent: Boolean) {
            val unacknowledged = purchases.filterNot { it.acknowledged }
            if (unacknowledged.isEmpty()) {
                if (!silent) unlock()
                else record(true)
                return
            }
            if (!silent) onState(State.Finishing)
            var remaining = unacknowledged.size
            var anyOk = false
            unacknowledged.forEach { purchase ->
                gateway.acknowledge(purchase) { ok ->
                    post {
                        anyOk = anyOk || ok
                        remaining--
                        if (remaining == 0) {
                            if (anyOk) {
                                record(true)
                                if (!silent) onState(State.Owned)
                            } else if (!silent) {
                                onState(State.Failed("Finishing the purchase failed. Try Restore purchase."))
                            }
                        }
                    }
                }
            }
        }

        private fun unlock() {
            record(true)
            onState(State.Owned)
        }

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

    private fun stateOf(purchase: Purchase): ProBilling.PurchaseState = when (purchase.purchaseState) {
        Purchase.PurchaseState.PURCHASED -> ProBilling.PurchaseState.PURCHASED
        Purchase.PurchaseState.PENDING -> ProBilling.PurchaseState.PENDING
        else -> ProBilling.PurchaseState.UNSPECIFIED
    }

    private fun dispatch(purchase: Purchase) {
        val owned = ProBilling.Owned(
            productId = purchase.products.firstOrNull().orEmpty(),
            acknowledged = purchase.isAcknowledged,
            token = purchase.purchaseToken,
            state = stateOf(purchase)
        )
        when (owned.state) {
            ProBilling.PurchaseState.PURCHASED -> listener?.invoke(ProBilling.Event.Purchased(owned))
            ProBilling.PurchaseState.PENDING -> listener?.invoke(ProBilling.Event.Pending)
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
            // Billing 8 one-time products can carry several purchase options; take the first offer
            // from the list and fall back to the single-offer getter.
            onPrice(
                product?.oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice
                    ?: product?.oneTimePurchaseOfferDetails?.formattedPrice
            )
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

    override fun queryPurchases(onResult: (List<ProBilling.Owned>?) -> Unit) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client().queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                // Not an answer: the caller must change nothing (never lock a paying user out).
                onResult(null)
                return@queryPurchasesAsync
            }
            onResult(
                purchases.map { purchase ->
                    ProBilling.Owned(
                        productId = purchase.products.firstOrNull().orEmpty(),
                        acknowledged = purchase.isAcknowledged,
                        token = purchase.purchaseToken,
                        state = stateOf(purchase)
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
