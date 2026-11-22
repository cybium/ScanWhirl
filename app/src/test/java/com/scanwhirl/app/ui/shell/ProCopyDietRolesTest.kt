package com.scanwhirl.app.ui.shell

import android.app.Activity
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.shell.ProScreen
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * COPY_DIET PRO2 roles: P price, S payment line, one All-tools action, no tertiary on the gold
 * tile and no duplicate slogan. Contrast is computed from the documented sampled gold-tile
 * background (#433A14), where tertiary #8D979E fails at 3.80:1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProCopyDietRolesTest {

    private var controller: org.robolectric.android.controller.ActivityController<Activity>? = null

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        RuntimeEnvironment.setQualifiers("en-w360dp-h800dp-xhdpi")
    }

    private fun screen(lang: String = "en", active: Boolean = false): Pair<Activity, ProScreen> {
        RuntimeEnvironment.setQualifiers("$lang-w360dp-h800dp-xhdpi")
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller!!.get()
        val pro = ProScreen(activity, object : ProScreen.Callbacks {
            override fun onClose() = Unit
            override fun onBuy() = Unit
            override fun onRestore() = Unit
            override fun onSeeAllProTools() = Unit
        }, active = active)
        activity.setContentView(pro)
        controller!!.visible()
        return activity to pro
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

    private fun sp(activity: Activity, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, activity.resources.displayMetrics)

    private fun luminance(argb: Int): Double {
        fun channel(c: Int): Double {
            val v = (argb shr c and 0xFF) / 255.0
            return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun ratio(a: Int, b: Int): Double {
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    @Test(timeout = 30_000)
    fun `the price is P 28 and the payment line is S 14`() {
        val (activity, pro) = screen()
        try {
            pro.setPrice("$4.99")
            val price = pro.findViewById<TextView>(R.id.swProPrice)
            assertEquals(Color.parseColor("#FFFFFF"), price.currentTextColor)
            assertEquals(sp(activity, 28f), price.textSize, 0.5f)

            val line = texts(pro).single { it.text.toString() == "One payment. No subscription." }
            assertEquals(Color.parseColor("#B3BCC2"), line.currentTextColor)
            assertEquals(sp(activity, 14f), line.textSize, 0.5f)
            assertTrue("the single payment line is shown", line.isShown)

            assertFalse(
                "the old 'one time' suffix is gone",
                texts(pro).any { it.text.toString() == activity.getString(R.string.pro_one_time) }
            )
        } finally {
            controller?.pause()?.stop()?.destroy()
            controller = null
        }
    }

    @Test(timeout = 30_000)
    fun `the no-price state is P 16 with Try again and no invented price`() {
        val (activity, pro) = screen()
        try {
            pro.setPrice(null)
            val price = pro.findViewById<TextView>(R.id.swProPrice)
            assertEquals(Color.parseColor("#FFFFFF"), price.currentTextColor)
            assertEquals(sp(activity, 16f), price.textSize, 0.5f)

            val status = pro.findViewById<TextView>(R.id.swProPriceStatus)
            assertEquals("Price unavailable", status.text.toString())
            assertEquals(Color.parseColor("#FFFFFF"), status.currentTextColor)
            assertEquals(sp(activity, 16f), status.textSize, 0.5f)

            val retry = pro.findViewById<TextView>(R.id.swProPriceRetry)
            assertEquals(Color.parseColor("#62E1F1"), retry.currentTextColor)
            assertTrue("Try again stays visible", retry.isShown)
            assertFalse("buy is not purchasable without Play's price", pro.buyButton.isEnabled)
        } finally {
            controller?.pause()?.stop()?.destroy()
            controller = null
        }
    }

    @Test(timeout = 30_000)
    fun `the owned state is a short P status in English and Hindi`() {
        for ((lang, expected) in listOf(
            "en" to "All Pro tools unlocked",
            "hi" to "Pro के सभी काम खुले हैं"
        )) {
            val (_, pro) = screen(lang = lang, active = true)
            val status = texts(pro).single { it.text.toString() == expected }
            assertEquals("owned status is P on $lang", Color.parseColor("#FFFFFF"), status.currentTextColor)
            assertFalse("no grey thank-you paragraph on $lang", status.text.toString().contains("धन्यवाद"))
            controller?.pause()?.stop()?.destroy()
            controller = null
        }
    }

    @Test(timeout = 30_000)
    fun `no tertiary text on the gold tile and the All tools action passes contrast`() {
        val (activity, pro) = screen()
        try {
            val tertiary = Color.parseColor("#8D979E")
            assertFalse(
                "T (#8D979E) is never body text on the Pro screen",
                texts(pro).any { it.currentTextColor == tertiary }
            )
            val expected = activity.getString(
                R.string.copydiet_pro2_all_tools, ToolRegistry.proCount()
            )
            val action = texts(pro).single { it.text.toString() == expected }
            assertEquals("the single All tools action is P, not T",
                Color.parseColor("#FFFFFF"), action.currentTextColor)
            val goldTile = Color.parseColor("#433A14")
            assertTrue(
                "P on the sampled gold tile is >= 4.5:1, got ${ratio(action.currentTextColor, goldTile)}",
                ratio(action.currentTextColor, goldTile) >= 4.5
            )
            assertTrue(
                "T on the same tile would fail (documented 3.80:1)",
                ratio(tertiary, goldTile) < 4.5
            )
        } finally {
            controller?.pause()?.stop()?.destroy()
            controller = null
        }
    }

    @Test(timeout = 30_000)
    fun `no duplicate slogan or coming-soon promise remains on the paywall`() {
        val (activity, pro) = screen()
        try {
            val labels = texts(pro).map { it.text.toString() }
            for (key in listOf(
                R.string.pro_sub_compact, R.string.pro_sub,
                R.string.pro_one_time, R.string.pro_panel_note
            )) {
                assertFalse(
                    "no duplicate sales slogan: ${activity.getString(key)}",
                    labels.any { it == activity.getString(key) }
                )
            }
            // pro_price_note shares the canonical wording; only ONE occurrence may be bound.
            assertEquals(
                "exactly one payment disclosure",
                1,
                labels.count { it == "One payment. No subscription." }
            )
            assertFalse(
                "no duplicate See all resource on this screen",
                labels.any { it == activity.getString(R.string.pro_see_all, ToolRegistry.proCount()) }
            )
            assertFalse(
                "no coming-soon promise on this screen",
                labels.any { it == "The Pro product is not available yet." || it == "Pro unavailable in Google Play" }
            )
        } finally {
            controller?.pause()?.stop()?.destroy()
            controller = null
        }
    }

    @Test(timeout = 30_000)
    fun `copy diet resources keep EN HI parity and format arguments`() {
        val en = File("src/main/res/values/strings_copydiet_pro2.xml").readText()
        val hi = File("src/main/res/values-hi/strings_copydiet_pro2.xml").readText()
        fun keys(text: String) = Regex("""<string name="([^"]+)">""").findAll(text).map { it.groupValues[1] }.toSet()
        assertEquals("EN/HI copy-diet keys match", keys(en), keys(hi))
        for (k in listOf("copydiet_pro2_all_tools")) {
            // A literal '$' is escaped for the regex with a backslash; ${'$'} inserts it in Kotlin.
            assertTrue("the count is a format argument in EN $k", Regex("""name="$k">[^<]*%1\${'$'}d""").containsMatchIn(en))
            assertTrue("the count is a format argument in HI $k", Regex("""name="$k">[^<]*%1\${'$'}d""").containsMatchIn(hi))
        }
        assertTrue("EN product failure copy", File("src/main/res/values/strings_pro2.xml")
            .readText().contains("Pro unavailable in Google Play"))
        assertTrue("HI product failure copy", File("src/main/res/values-hi/strings_pro2.xml")
            .readText().contains("Google Play पर Pro उपलब्ध नहीं है"))
    }
}
