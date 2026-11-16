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
import com.scanwhirl.app.R

/**
 * The one place Play Billing lives: one-time in-app product [PRODUCT_ID], the localized price,
 * the purchase flow, "Restore purchase" and the start-up ownership check.
 *
 * Everything that talks to Play is behind [Gateway], so the decisions (acknowledge asynchronously,
 * pending versus paid, a failed query never locks Pro, a refund locks it only in a release build)
 * are plain unit tests with a fake that answers **later**, like the real client.
 *
 * Play calls back on arbitrary threads. Every callback that changes state or reaches the UI is
 * marshalled through [onMain]; one purchase operation may be in flight at a time, so a double tap
 * cannot open two billing flows or install two listeners.
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

        /** A Buy is in flight. Screens disable the button; a new subscriber sees this on replay. */
        object Busy : State()
        data class Failed(val message: String) : State()
    }

    /**
     * The product offer a Buy query just returned. [token] identifies that answer so the launch
     * uses this object, never a shared field a later prefetch can overwrite.
     * Store-country / account currency changes with the same locale are a known limit: the cache
     * is keyed only by the locale captured when the request was issued.
     */
    class Offer(
        val price: String,
        val token: String,
        internal val details: ProductDetails? = null
    )

    enum class LaunchResult { STARTED, CANCELLED, ALREADY_OWNED, FAILED }

    /** Which operation last published a state. Restore's [State.None] is "nothing to restore". */
    enum class Op { NONE, BUY, RESTORE, STARTUP }

    /** The billing seam: [PlayBilling] in the app, a fake in tests. */
    interface Gateway {
        fun setListener(listener: ((Event) -> Unit)?)
        fun connect(onReady: (Boolean) -> Unit)
        fun price(onPrice: (String?) -> Unit)
        fun launch(activity: Activity?)

        /**
         * The offer from this query alone. Default wraps [price] so older fakes keep working.
         * The real client attaches the [ProductDetails] it just received; launch must use that.
         */
        fun queryOffer(onOffer: (Offer?) -> Unit) {
            price { text -> onOffer(text?.let { Offer(it, it) }) }
        }

        /**
         * Open the sheet with [offer] from this Buy's own query. Default keeps the old
         * [launch] and reports [LaunchResult.STARTED]; a throw still propagates.
         */
        fun launch(activity: Activity?, offer: Offer): LaunchResult {
            launch(activity)
            return LaunchResult.STARTED
        }

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
     * The last localized price Play answered with, and the locale it belongs to. The shell starts
     * the query in onCreate, when no Pro screen exists yet; every Pro screen created later shows
     * this instead of falling back to the hard-coded ₹100. A different locale never reuses it.
     */
    @Volatile
    private var lastPrice: String? = null

    @Volatile
    private var lastPriceLocale: String? = null

    /** Last price request issued; an older answer never overwrites a newer publication. */
    @Volatile
    private var priceRequest = 0

    /**
     * Entitlement generation: bumped every time this process records a purchase. A negative
     * ownership snapshot that *started* before the bump is stale and must never revoke Pro.
     */
    @Volatile
    private var generation = 0

    /** One purchase operation at a time: a second tap while Play is answering is ignored. */
    @Volatile
    private var buyInFlight = false

    /** True from the moment Buy starts until a terminal state. Finishing does not clear it. */
    @Volatile
    private var buyOpen = false

    private val subscribers = mutableListOf<(State) -> Unit>()
    private var latest: State = State.None
    private var opListener: ((State) -> Unit)? = null
    private var lastOp: Op = Op.NONE
    private var listenerInstalled = false
    private var appContext: Context? = null

    private val mainThread: Handler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Screens subscribe on the main thread in onStart and drop the returned handle in onStop.
     * The first delivery is a replay of [latest], so a recreated screen catches an in-flight Buy.
     */
    fun subscribe(listener: (state: State, replay: Boolean) -> Unit): () -> Unit {
        val wrapped = object : (State) -> Unit {
            var seen = false
            override fun invoke(state: State) {
                val replay = !seen
                seen = true
                listener(state, replay)
            }
        }
        fun add() {
            subscribers += wrapped
            wrapped(latest)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) add() else mainThread.post { add() }
        return { onMain { subscribers.remove(wrapped) } }
    }

    internal fun lastOperation(): Op = lastOp

    /**
     * Runs [block] on the main thread. Work that is already on main runs immediately, so callers
     * on the UI thread keep their synchronous behaviour; a Play callback on a worker thread is
     * posted. Every public state delivery and every UI touch goes through here.
     */
    private fun gateway(context: Context): Gateway {
        val app = context.applicationContext
        appContext = app
        return gateway ?: gatewayFactory(app).also {
            gateway = it
            // One PurchasesUpdatedListener for the process. Queries must never clear it (R2).
            if (!listenerInstalled) {
                listenerInstalled = true
                it.setListener { event -> onMain { handleStoreEvent(app, event) } }
            }
        }
    }

    /**
     * Runs [block] on the main thread. The body is inside the posted runnable, so a throw
     * in a Play callback cannot escape the looper or leave Buy latched (R5).
     */
    internal fun onMain(block: () -> Unit) {
        val safe = {
            try {
                block()
            } catch (t: Throwable) {
                if (buyOpen) endBuy(State.Failed(playMessage()))
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) safe() else mainThread.post(safe)
    }

    private fun playMessage(): String =
        appContext?.getString(R.string.pro_error_play) ?: "Play Store is not available."

    private fun deliver(state: State) {
        latest = state
        opListener?.invoke(state)
        subscribers.toList().forEach { runCatching { it(state) } }
    }

    private fun endBuy(state: State) {
        if (state is State.Finishing) {
            if (buyOpen) deliver(state)
            return
        }
        if (!buyOpen) return
        buyOpen = false
        buyInFlight = false
        deliver(state)
        if (lastOp == Op.BUY) opListener = null
    }

    private fun Activity.canLaunchBilling(): Boolean {
        if (isFinishing || isDestroyed) return false
        val owner = this as? androidx.lifecycle.LifecycleOwner ?: return true
        return owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    }

    private fun handleStoreEvent(app: Context, event: Event) {
        buyController(app).handle(event)
    }

    private var buyHandler: Controller? = null

    private fun buyController(app: Context): Controller =
        buyHandler ?: Controller(app, gateway(app), testBuild = false, post = { onMain(it) }) { state ->
            if (buyOpen) {
                if (state is State.Finishing) deliver(state) else endBuy(state)
            } else if (state is State.Owned) {
                deliver(state)
            }
        }.also { buyHandler = it }

    private fun localeTag(context: Context): String =
        context.resources.configuration.locales[0]?.toLanguageTag().orEmpty()

    /** The last price Play reported for the current locale, or null when it has not answered. */
    fun cachedPrice(context: Context): String? =
        if (localeTag(context) == lastPriceLocale) lastPrice else null

    /**
     * Publish only when the locale captured at request time is still the current one.
     * An answer that arrives after a language change is not relabelled as the new locale (R6).
     */
    private fun publishPrice(app: Context, requestLocale: String, text: String?) {
        if (text == null) return
        if (requestLocale != localeTag(app)) return
        lastPrice = text
        lastPriceLocale = requestLocale
    }

    /**
     * The launch-time query: it only fills the cache for every Pro screen and never touches UI,
     * so a price that arrives before the first [com.scanwhirl.app.ui.shell.ProScreen] exists
     * cannot crash or write to a screen that is not there.
     */
    fun prefetchPrice(context: Context) {
        price(context) { }
    }

    /** Shows Play's localized price when it can be fetched; [onPrice] gets null otherwise. */
    fun price(context: Context, onPrice: (String?) -> Unit) {
        val app = context.applicationContext
        val requestLocale = localeTag(context)
        val request = ++priceRequest
        val client = try {
            gateway(app)
        } catch (t: Throwable) {
            onMain { onPrice(null) }
            return
        }
        try {
            client.connect { ok ->
                onMain {
                    try {
                        if (!ok) {
                            onPrice(null)
                            return@onMain
                        }
                        client.queryOffer { offer ->
                            onMain {
                                try {
                                    val still = request == priceRequest && requestLocale == localeTag(app)
                                    if (still) publishPrice(app, requestLocale, offer?.price)
                                    onPrice(if (still) offer?.price else null)
                                } catch (t: Throwable) {
                                    onPrice(null)
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        onPrice(null)
                    }
                }
            }
        } catch (t: Throwable) {
            onMain { onPrice(null) }
        }
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
            onMain {
                lastOp = Op.BUY
                opListener = onState
                deliver(State.Owned)
                opListener = null
            }
            return
        }
        onMain {
            try {
                // A double tap must not open a second billing flow. The listener stays the one
                // installed with the client; this call does not replace it (R2).
                if (buyInFlight || buyOpen) return@onMain
                buyInFlight = true
                buyOpen = true
                lastOp = Op.BUY
                opListener = onState
                latest = State.Busy
                subscribers.toList().forEach { runCatching { it(State.Busy) } }
                val app = activity.applicationContext
                val requestLocale = localeTag(activity)
                val client = try {
                    gateway(app)
                } catch (t: Throwable) {
                    endBuy(State.Failed(app.getString(R.string.pro_error_play)))
                    return@onMain
                }
                client.connect { ok ->
                    onMain {
                        try {
                            if (!ok) {
                                endBuy(State.Failed(app.getString(R.string.pro_error_play)))
                                return@onMain
                            }
                            val request = ++priceRequest
                            client.queryOffer { offer ->
                                onMain {
                                    try {
                                        if (offer != null && request == priceRequest &&
                                            requestLocale == localeTag(app)
                                        ) {
                                            publishPrice(app, requestLocale, offer.price)
                                        }
                                        // The Activity is used only here, and only if it is resumed (R3).
                                        if (!activity.canLaunchBilling()) {
                                            endBuy(State.None)
                                            return@onMain
                                        }
                                        if (offer == null) {
                                            endBuy(State.Failed(app.getString(R.string.pro_error_product)))
                                            return@onMain
                                        }
                                        when (client.launch(activity, offer)) {
                                            LaunchResult.STARTED -> Unit
                                            LaunchResult.CANCELLED -> endBuy(State.None)
                                            LaunchResult.FAILED ->
                                                endBuy(State.Failed(app.getString(R.string.pro_error_play)))
                                            LaunchResult.ALREADY_OWNED -> reconcileOwned(app, client)
                                        }
                                    } catch (t: Throwable) {
                                        endBuy(State.Failed(app.getString(R.string.pro_error_play)))
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            endBuy(State.Failed(app.getString(R.string.pro_error_play)))
                        }
                    }
                }
            } catch (t: Throwable) {
                endBuy(State.Failed(activity.applicationContext.getString(R.string.pro_error_play)))
            }
        }
    }

    /**
     * ITEM_ALREADY_OWNED must end the Buy on every path, including a failed or empty re-query (R4).
     */
    private fun reconcileOwned(app: Context, client: Gateway) {
        val startGeneration = generation
        client.queryPurchases { owned ->
            onMain {
                try {
                    val mine = owned?.filter {
                        it.productId == PRODUCT_ID && it.state == PurchaseState.PURCHASED
                    }
                    when {
                        owned == null || mine.isNullOrEmpty() ->
                            endBuy(State.Failed(app.getString(R.string.pro_error_purchase)))
                        else -> buyController(app).applyOwned(mine, startGeneration, silent = false)
                    }
                } catch (t: Throwable) {
                    endBuy(State.Failed(app.getString(R.string.pro_error_play)))
                }
            }
        }
    }

    /** "Restore purchase": anything owned unlocks Pro (and is acknowledged). */
    fun restore(context: Context, onState: (State) -> Unit) =
        restore(context, com.scanwhirl.app.BuildConfig.TEST_BUILD, onState)

    /**
     * Release restore, with the test-build shortcut exposed so a unit test can drive the real
     * query. Outcomes are delivered to [onState] and to every subscriber (R1).
     */
    @androidx.annotation.VisibleForTesting
    internal fun restore(context: Context, testBuild: Boolean, onState: (State) -> Unit) {
        val app = context.applicationContext
        if (testBuild) {
            onMain {
                lastOp = Op.RESTORE
                opListener = onState
                deliver(if (ProGate.isPro(app)) State.Owned else State.None)
                opListener = null
            }
            return
        }
        onMain {
            try {
                lastOp = Op.RESTORE
                opListener = onState
                val client = try {
                    gateway(app)
                } catch (t: Throwable) {
                    deliver(State.Failed(app.getString(R.string.pro_error_play)))
                    opListener = null
                    return@onMain
                }
                // A restore query must not replace the purchase listener (R2).
                val controller = Controller(
                    app, client, testBuild = false, post = { block -> onMain(block) }
                ) { state ->
                    deliver(state)
                    if (state !is State.Finishing) opListener = null
                }
                client.connect { ok ->
                    onMain {
                        try {
                            if (!ok) {
                                deliver(State.Failed(app.getString(R.string.pro_error_play)))
                                opListener = null
                                return@onMain
                            }
                            val startGeneration = generation
                            client.queryPurchases { owned ->
                                onMain {
                                    try {
                                        if (owned == null) {
                                            deliver(State.Failed(app.getString(R.string.pro_error_play)))
                                            opListener = null
                                        } else {
                                            controller.applyOwned(owned, startGeneration, silent = false)
                                        }
                                    } catch (t: Throwable) {
                                        deliver(State.Failed(app.getString(R.string.pro_error_play)))
                                        opListener = null
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            deliver(State.Failed(app.getString(R.string.pro_error_play)))
                            opListener = null
                        }
                    }
                }
            } catch (t: Throwable) {
                deliver(State.Failed(app.getString(R.string.pro_error_play)))
                opListener = null
            }
        }
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
        refreshOwned(context, testBuild = false)
    }

    /** Release start-up query, exposed so a test can overlap it with Buy (R2). */
    @androidx.annotation.VisibleForTesting
    internal fun refreshOwned(context: Context, testBuild: Boolean) {
        if (testBuild) return
        val app = context.applicationContext
        onMain {
            if (checkedOnStart) return@onMain
            checkedOnStart = true
            val client = try {
                gateway(app)
            } catch (t: Throwable) {
                return@onMain
            }
            val controller = Controller(app, client, testBuild = false, post = { block -> onMain(block) }) { }
            try {
                client.connect { ok ->
                    onMain {
                        try {
                            if (!ok) {
                                if (!buyOpen) client.close()
                            } else {
                                val startGeneration = generation
                                client.queryPurchases { owned ->
                                    onMain {
                                        try {
                                            // Silent, and it must not clear the purchase listener (R2).
                                            controller.applyOwned(owned, startGeneration, silent = true)
                                        } catch (t: Throwable) {
                                            // A failed snapshot never changes Pro.
                                        }
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            // Retried on the next process start; a failed query never changes Pro.
                        }
                    }
                }
            } catch (t: Throwable) {
                // Retried on the next process start; a failed query never changes Pro.
            }
        }
    }

    /** True while a purchase operation is waiting on Play (a rotated screen re-attaches to it). */
    fun isPurchaseInFlight(): Boolean = buyInFlight

    /** Test seam: forget the cached client and the once-per-process check. */
    internal fun resetForTests() {
        runCatching { gateway?.close() }
        gateway = null
        checkedOnStart = false
        lastPrice = null
        lastPriceLocale = null
        priceRequest = 0
        generation = 0
        buyInFlight = false
        buyOpen = false
        subscribers.clear()
        latest = State.None
        opListener = null
        lastOp = Op.NONE
        listenerInstalled = false
        buyHandler = null
        appContext = null
        gatewayFactory = { PlayBilling(it) }
    }

    /** Test seam: the entitlement generation a query would capture right now. */
    @androidx.annotation.VisibleForTesting
    internal fun generationForTests(): Int = generation

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
         *
         * [queryGeneration] is the entitlement generation captured when the query *started*. A
         * negative answer from before this process recorded a purchase is stale: the grant is
         * newer than the snapshot, so it is discarded instead of revoking the fresh purchase.
         */
        fun applyOwned(
            purchases: List<Owned>?,
            queryGeneration: Int = generation,
            silent: Boolean = false
        ) {
            if (purchases == null) return
            val mine = purchases.filter { it.productId == PRODUCT_ID }
            val bought = mine.filter { it.state == PurchaseState.PURCHASED }
            if (bought.isEmpty()) {
                val pending = mine.any { it.state == PurchaseState.PENDING }
                if (pending) {
                    if (!silent) onState(State.Pending)
                    return
                }
                if (queryGeneration != generation) return
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
                                onState(
                                    State.Failed(
                                        context.getString(R.string.pro_error_finish)
                                    )
                                )
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
                // A newer grant than any ownership snapshot still in flight.
                generation++
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

    override fun setListener(listener: ((ProBilling.Event) -> Unit)?) {
        this.listener = listener
    }

    private fun client(): BillingClient = client ?: BillingClient.newBuilder(context)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .setListener { result, purchases ->
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    val list = purchases.orEmpty()
                    // An OK update with nothing usable must not leave Buy latched.
                    if (list.isEmpty()) listener?.invoke(ProBilling.Event.Cancelled)
                    else list.forEach { dispatch(it) }
                }
                BillingClient.BillingResponseCode.USER_CANCELED ->
                    listener?.invoke(ProBilling.Event.Cancelled)
                else -> listener?.invoke(
                    ProBilling.Event.Failed(context.getString(R.string.pro_error_purchase))
                )
            }
        }
        .build()
        .also { client = it }

    private fun stateOf(purchase: Purchase): ProBilling.PurchaseState = when (purchase.purchaseState) {
        Purchase.PurchaseState.PURCHASED -> ProBilling.PurchaseState.PURCHASED
        Purchase.PurchaseState.PENDING -> ProBilling.PurchaseState.PENDING
        else -> ProBilling.PurchaseState.UNSPECIFIED
    }

    private fun owned(purchase: Purchase) = ProBilling.Owned(
        productId = purchase.products.firstOrNull().orEmpty(),
        acknowledged = purchase.isAcknowledged,
        token = purchase.purchaseToken,
        state = stateOf(purchase)
    )

    private fun dispatch(purchase: Purchase) {
        val owned = owned(purchase)
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
        queryOffer { onPrice(it?.price) }
    }

    override fun queryOffer(onOffer: (ProBilling.Offer?) -> Unit) {
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
            // Kept only so a legacy launch(activity) still has something. Buy never reads it (R7).
            details = product
            val formatted = product?.oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice
                ?: product?.oneTimePurchaseOfferDetails?.formattedPrice
            onOffer(
                if (product == null || formatted == null) null
                else ProBilling.Offer(formatted, product.productId, product)
            )
        }
    }

    override fun launch(activity: Activity?) {
        val cached = details
        if (cached == null) {
            listener?.invoke(ProBilling.Event.Failed(context.getString(R.string.pro_error_product)))
            return
        }
        launch(activity, ProBilling.Offer("", cached.productId, cached))
    }

    override fun launch(activity: Activity?, offer: ProBilling.Offer): ProBilling.LaunchResult {
        val detail = offer.details ?: return ProBilling.LaunchResult.FAILED
        val target = activity ?: return ProBilling.LaunchResult.FAILED
        val flow = runCatching {
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
        }.getOrElse {
            return ProBilling.LaunchResult.FAILED
        }
        return when (flow.responseCode) {
            BillingClient.BillingResponseCode.OK -> ProBilling.LaunchResult.STARTED
            BillingClient.BillingResponseCode.USER_CANCELED -> ProBilling.LaunchResult.CANCELLED
            // The manager re-queries and ends the Buy even when that re-query is empty (R4).
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> ProBilling.LaunchResult.ALREADY_OWNED
            else -> ProBilling.LaunchResult.FAILED
        }
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
            onResult(purchases.map { owned(it) })
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
