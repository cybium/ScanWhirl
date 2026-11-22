package com.scanwhirl.app.ui.shell

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.util.ProBilling
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * PRO2 critic fix: when Play has no price/product details, Get Pro is not purchasable, and the
 * paywall offers an explicit "Try again" that re-queries Play. Continue free / Restore stay
 * visible, aligned and equal height. Owned and in-flight-purchase states are unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProPriceUnavailableTest {

    private var controller: org.robolectric.android.controller.ActivityController<Activity>? = null
    private lateinit var gateway: FakeGateway

    private class FakeGateway(var priceText: String? = null) : ProBilling.Gateway {
        var priceCalls = 0
        var launches = 0
        private val queue = ArrayDeque<() -> Unit>()

        override fun setListener(listener: ((ProBilling.Event) -> Unit)?) = Unit
        override fun connect(onReady: (Boolean) -> Unit) { queue.addLast { onReady(true) } }
        override fun price(onPrice: (String?) -> Unit) {
            priceCalls++
            queue.addLast { onPrice(priceText) }
        }
        override fun launch(activity: Activity?) { launches++ }
        override fun queryPurchases(onResult: (List<ProBilling.Owned>?) -> Unit) {
            queue.addLast { onResult(emptyList()) }
        }
        override fun acknowledge(purchase: ProBilling.Owned, onDone: (Boolean) -> Unit) {
            queue.addLast { onDone(true) }
        }
        override fun close() = Unit
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
    }

    private data class Counters(var close: Int = 0, var buy: Int = 0, var restore: Int = 0, var all: Int = 0)

    @Before
    fun setUp() {
        ProBilling.resetForTests()
        gateway = FakeGateway(priceText = null)
        ProBilling.gatewayFactory = { gateway }
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        ProBilling.resetForTests()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun screen(lang: String = "en", active: Boolean = false): Pair<Activity, ProScreen> {
        org.robolectric.RuntimeEnvironment.setQualifiers("$lang-w360dp-h800dp-xhdpi")
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller!!.get()
        val counters = Counters()
        val pro = ProScreen(activity, object : ProScreen.Callbacks {
            override fun onClose() { counters.close++ }
            override fun onBuy() { counters.buy++ }
            override fun onRestore() { counters.restore++ }
            override fun onSeeAllProTools() { counters.all++ }
        }, active = active)
        activity.setContentView(pro)
        pro.setTag(R.string.sheet_cancel, counters)
        controller!!.visible()
        return activity to pro
    }

    private fun counters(pro: ProScreen): Counters = pro.getTag(R.string.sheet_cancel) as Counters

    private fun byId(activity: Activity, root: View, name: String): View? {
        val id = activity.resources.getIdentifier(name, "id", activity.packageName)
        return if (id == 0) null else root.findViewById(id)
    }

    private fun texts(root: View): List<TextView> {
        val out = mutableListOf<TextView>()
        fun walk(v: View) {
            if (v is TextView) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun measure(activity: Activity, pro: ProScreen) {
        val d = activity.resources.displayMetrics.density
        repeat(2) {
            pro.measure(
                View.MeasureSpec.makeMeasureSpec((360 * d).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((800 * d).toInt(), View.MeasureSpec.EXACTLY)
            )
            pro.layout(0, 0, pro.measuredWidth, pro.measuredHeight)
        }
    }

    @Test(timeout = 30_000)
    fun `price unavailable cannot launch the billing flow`() {
        val (activity, pro) = screen()
        pro.setPrice(null)
        val status = byId(activity, pro, "swProPriceStatus") as TextView
        assertEquals("Price unavailable", status.text.toString())
        assertEquals(View.VISIBLE, status.visibility)
        assertFalse("no Play price means Get Pro is not purchasable", pro.buyButton.isEnabled)

        pro.buyButton.performClick()
        assertEquals("a disabled Get Pro never starts a purchase", 0, counters(pro).buy)
        assertEquals("and never reaches the billing library", 0, gateway.launches)
    }

    @Test(timeout = 30_000)
    fun `Try again re-queries Play and makes Get Pro purchasable when a price arrives`() {
        val (activity, pro) = screen()
        pro.setPrice(null)
        val retry = byId(activity, pro, "swProPriceRetry") as TextView
        assertNotNull("a named Try again action", retry)
        assertTrue("Try again keeps a 48dp target", retry.minimumHeight >= SwStyle.dp(activity, 48f))
        assertEquals("Try again", retry.text.toString())

        val before = gateway.priceCalls
        gateway.priceText = "$4.99"
        retry.performClick()
        gateway.drain()
        idle()
        assertEquals("Try again re-queries product details", before + 1, gateway.priceCalls)

        val status = byId(activity, pro, "swProPriceStatus") as TextView
        assertEquals(View.GONE, status.visibility)
        assertEquals(View.GONE, retry.visibility)
        assertEquals("the re-queried price is shown", "$4.99",
            (byId(activity, pro, "swProPrice") as TextView).text.toString())
        assertTrue("Get Pro becomes purchasable once Play answers", pro.buyButton.isEnabled)
        pro.buyButton.performClick()
        assertEquals(1, counters(pro).buy)
    }

    @Test(timeout = 30_000)
    fun `owned and pending-purchase states are unchanged`() {
        val (activity, owned) = screen(active = true)
        owned.setPrice(null)
        assertEquals(View.GONE, (byId(activity, owned, "swProPriceStatus") as TextView).visibility)
        assertNull("an owned screen has no price card", owned.priceWrapForTest().parent)
        assertNull("an owned screen has no buy button", owned.buyWrapForTest().parent)

        controller!!.pause()!!.stop()!!.destroy()
        val (activity2, pro) = screen()
        pro.setPrice("$4.99")
        pro.setBuying(true)
        assertFalse("a purchase in flight keeps Get Pro disabled", pro.buyButton.isEnabled)
        val restore = pro.restoreRowForTest()!!
        assertFalse("Restore is disabled while a purchase is in flight", restore.isEnabled)
        assertEquals(
            activity2.getString(R.string.pro_purchase_in_progress),
            restore.contentDescription.toString()
        )
        pro.setBuying(false)
        assertTrue("Get Pro returns when the purchase ends", pro.buyButton.isEnabled)
        assertTrue("Restore returns when the purchase ends", restore.isEnabled)
    }

    @Test(timeout = 30_000)
    fun `Continue free and Restore stay visible aligned and equal height`() {
        val (activity, pro) = screen()
        measure(activity, pro)
        val free = byId(activity, pro, "swProContinueFree") as TextView
        val restore = byId(activity, pro, "swProRestoreAction") as TextView
        assertTrue("Continue free is visible", free.isShown)
        assertTrue("Restore purchase is visible", restore.isShown)
        assertTrue("Continue free keeps 48dp", free.height >= SwStyle.dp(activity, 48f))
        assertTrue("Restore keeps 48dp", restore.height >= SwStyle.dp(activity, 48f))
        assertEquals("the two actions are equal height", free.height, restore.height)
        assertEquals("the two actions are aligned on one row", free.top, restore.top)
        val stack = free.parent as LinearLayout
        assertEquals(LinearLayout.HORIZONTAL, stack.orientation)
    }

    @Test(timeout = 30_000)
    fun `only one counted See all Pro tools link remains`() {
        val (activity, pro) = screen()
        val counted = activity.getString(R.string.copydiet_pro2_all_tools, ToolRegistry.proCount())
        assertEquals("exactly one counted See all link", 1, texts(pro).count { it.text.toString() == counted })
        assertEquals(
            "no second un-counted See all link",
            0,
            texts(pro).count { it.text.toString() == activity.getString(R.string.v2r_see_all_pro_tools) }
        )
    }

    @Test(timeout = 30_000)
    fun `English and Hindi price-unavailable copy is present and Hindi thanks has no exclamation`() {
        val (_, en) = screen(lang = "en")
        en.setPrice(null)
        val enLabels = texts(en).map { it.text.toString() }
        assertTrue("English price unavailable", enLabels.any { it == "Price unavailable" })
        assertTrue("English Try again", enLabels.any { it == "Try again" })

        controller!!.pause()!!.stop()!!.destroy()
        val (_, hi) = screen(lang = "hi")
        hi.setPrice(null)
        val hiLabels = texts(hi).map { it.text.toString() }
        assertTrue("Hindi price unavailable", hiLabels.any { it == "कीमत उपलब्ध नहीं है" })
        assertTrue("Hindi Try again", hiLabels.any { it == "फिर कोशिश करें" })

        val hiCopy = File("src/main/res/values-hi/strings_copydiet_pro2.xml").readText()
        val match = Regex("""<string name="copydiet_pro2_active_sub">([^<]*)</string>""").find(hiCopy)
        assertNotNull("Hindi owned status exists", match)
        assertFalse("Hindi owned status has no exclamation mark", match!!.groupValues[1].contains("!"))
        assertTrue("Hindi owned status keeps the reviewed words", match.groupValues[1].contains("खुले हैं"))
    }
}
