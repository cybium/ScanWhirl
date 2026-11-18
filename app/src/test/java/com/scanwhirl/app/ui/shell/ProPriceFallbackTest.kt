package com.scanwhirl.app.ui.shell

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.ProBilling
import com.scanwhirl.app.util.ProGate
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * PRC-001: the app shows only Play's localized price ("{Play price} once. No subscription.").
 * When Play has not answered (not loaded, billing unavailable, offline) the Pro screen shows no
 * amount at all: a ₹ figure is never invented, and the launch price (₹299) never appears here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProPriceFallbackTest {

    private lateinit var app: Application

    /** Answers only when the test drains, like Play. A null [priceText] means "no price". */
    private class FakeGateway(var priceText: String? = null) : ProBilling.Gateway {
        private val queue = ArrayDeque<() -> Unit>()

        override fun setListener(listener: ((ProBilling.Event) -> Unit)?) = Unit
        override fun connect(onReady: (Boolean) -> Unit) {
            queue.addLast { onReady(true) }
        }

        override fun price(onPrice: (String?) -> Unit) {
            queue.addLast { onPrice(priceText) }
        }

        override fun launch(activity: Activity?) = Unit
        override fun queryPurchases(onResult: (List<ProBilling.Owned>?) -> Unit) {
            queue.addLast { onResult(emptyList()) }
        }

        override fun acknowledge(purchase: ProBilling.Owned, onDone: (Boolean) -> Unit) {
            queue.addLast { onDone(true) }
        }

        override fun close() = Unit

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst()()
        }
    }

    private lateinit var gateway: FakeGateway

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        ProBilling.resetForTests()
        gateway = FakeGateway(priceText = null)
        ProBilling.gatewayFactory = { gateway }
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, false)
    }

    @After
    fun tearDown() {
        ProBilling.resetForTests()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun priceText(activity: ShellActivity): String =
        activity.findViewById<TextView>(R.id.swProPrice).text.toString()

    private fun textViews(root: View): List<TextView> {
        val out = mutableListOf<TextView>()
        fun walk(view: View) {
            if (view is TextView) out += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun oneTimeMark(activity: ShellActivity): TextView =
        textViews(activity.findViewById(R.id.swProScreen))
            .single { it.text.toString() == activity.getString(R.string.pro_one_time) }

    @Test
    fun `no Play price shows no amount and no rupee on the Pro screen`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                activity.showPro()
                idle()
                // Both the launch-time prefetch and this screen's query answer null.
                gateway.drain()
                idle()

                val shown = priceText(activity)
                assertFalse("no rupee amount may be invented: $shown", shown.contains("₹"))
                assertFalse(
                    "no digits read as a price when Play has none: $shown",
                    Regex("[0-9]").containsMatchIn(shown)
                )
                assertTrue("the price line still says where the price comes from", shown.isNotBlank())
                assertEquals(
                    "the amount placeholder is the reviewed string, not an English literal",
                    stringFromSources("values", "pro_price_play"),
                    shown
                )
                assertFalse(
                    "no 'one time' next to a missing amount",
                    oneTimeMark(activity).isShown
                )
            }
        }
    }

    @Test
    fun `a Play price is shown exactly as Play sent it`() {
        gateway.priceText = "$4.99"
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                activity.showPro()
                idle()
                gateway.drain()
                idle()

                assertEquals("Play's own price string is shown verbatim", "$4.99", priceText(activity))
                assertTrue("the amount keeps its 'one time' mark", oneTimeMark(activity).isShown)
            }
        }
    }

    @Test
    fun `a Play price that happens to use the rupee sign is not rewritten`() {
        gateway.priceText = "₹299"
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                activity.showPro()
                idle()
                gateway.drain()
                idle()

                assertEquals("Play's India price is displayed unchanged", "₹299", priceText(activity))
            }
        }
    }

    /**
     * PRC-001 guard over the sources, not over one call site: no string in values/ or values-hi/
     * may carry a hardcoded rupee amount (that is how the Pro screen got ₹100 as a fallback).
     */
    @Test
    fun `no string resource contains a hardcoded rupee amount`() {
        for (locale in listOf("values", "values-hi")) {
            val dir = newResDir(locale)
            val offenders = dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }
                .orEmpty()
                .flatMap { file ->
                    Regex("""₹\s*[0-9]""").findAll(file.readText()).map { file.name }
                }
                .toSet()
            assertTrue("hardcoded rupee amount in $locale: $offenders", offenders.isEmpty())
        }
    }

    @Test
    fun `the Pro price placeholder exists in both languages and no numeric pro_price remains`() {
        val en = stringsFile("values")
        val hi = stringsFile("values-hi")
        assertFalse("pro_price is gone from values", en.readText().contains("name=\"pro_price\""))
        assertFalse("pro_price is gone from values-hi", hi.readText().contains("name=\"pro_price\""))
        assertNotNull(
            "values has the no-amount placeholder",
            Regex("""<string name="pro_price_play">([^<]{3,})</string>""").find(en.readText())
        )
        assertNotNull(
            "values-hi has the no-amount placeholder",
            Regex("""<string name="pro_price_play">([^<]{3,})</string>""").find(hi.readText())
        )
    }

    // ---- source helpers (Gradle runs unit tests from the module directory) ----

    private fun moduleDir(): File {
        val candidates = listOf(File("."), File("app"), File(".."))
        return candidates.firstOrNull { File(it, "src/main/res").isDirectory }
            ?: throw AssertionError(
                "cannot find app/src/main/res from ${File(".").absolutePath}"
            )
    }

    private fun newResDir(locale: String): File =
        File(moduleDir(), "src/main/res/$locale").apply {
            assertTrue("missing resource folder $path", isDirectory)
        }

    private fun stringsFile(locale: String): File =
        File(newResDir(locale), "strings_l1.xml").apply {
            assertTrue("missing $path", isFile)
        }

    private fun stringFromSources(locale: String, name: String): String {
        val match = Regex("""<string name="$name">([^<]*)</string>""")
            .find(stringsFile(locale).readText())
        assertNotNull("$name missing from values/strings_l1.xml", match)
        return match!!.groupValues[1].replace("\\'", "'")
    }
}
