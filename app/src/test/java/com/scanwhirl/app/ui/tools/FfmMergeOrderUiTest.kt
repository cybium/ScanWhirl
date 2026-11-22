package com.scanwhirl.app.ui.tools

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * FF-M setup screen: the merge order preview. Sources are shown numbered in the order the person
 * picked, each with a named 48dp up/down action, and the resulting pages/bytes are stated before
 * Continue is pressed. Hindi carries the same wording with purna viram and no rejected loanword.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FfmMergeOrderUiTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun findByTag(root: View?, tag: String): View? {
        if (root == null) return null
        if (root.tag == tag) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findByTag(root.getChildAt(i), tag)
                if (found != null) return found
            }
        }
        return null
    }

    private fun allByTag(root: View?, tag: String): List<View> {
        val out = mutableListOf<View>()
        fun walk(v: View?) {
            if (v == null) return
            if (v.tag == tag) out += v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun strings(path: String): Map<String, String> {
        val xml = File("src/main/res/$path").readText()
        val regex = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        return regex.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `EN and HI carry every FF-M string with matching placeholders`() {
        val en = strings("values/strings_ffm.xml")
        val hi = strings("values-hi/strings_ffm.xml")
        assertEquals("string keys must match", en.keys, hi.keys)
        en.keys.forEach { key ->
            val a = Regex("%[0-9]+\\$[a-zA-Z]").findAll(en.getValue(key)).map { it.value }.toList()
            val b = Regex("%[0-9]+\\$[a-zA-Z]").findAll(hi.getValue(key)).map { it.value }.toList()
            assertEquals("placeholder mismatch for $key", a, b)
        }
        assertEquals("A source page is missing. Nothing was merged.", en["ffm_merge_missing_page"])
        assertEquals("Move order, not a 4-page success", "एक स्रोत पन्ना गायब है। कुछ भी नहीं मिलाया गया।", hi["ffm_merge_missing_page"])
        assertTrue("Hindi uses purna viram", hi.getValue("ffm_merge_compress_hint").endsWith("।"))
    }

    @Test(timeout = 30_000)
    fun `the order preview is numbered and reorders with named 48dp actions`() {
        val repo = ScanRepository(app)
        // Library order is Class 10 then Income certificate; the picker is tapped in reverse.
        val class10 = DocFixtures.saved(repo, app, pages = 2, name = "Class 10")
        val income = DocFixtures.saved(repo, app, pages = 3, name = "Income certificate")
        val controller = Robolectric.buildActivity(
            ToolSetupActivity::class.java, ToolSetupActivity.intent(app, "merge")
        ).setup()
        try {
            idle()
            val activity = controller.get()
            fun row(id: String): View =
                findByTag(activity.findViewById(R.id.toolSetupSource), id)!!
            fun selected(id: String): Boolean =
                row(id).findViewById<View>(R.id.toolSetupDocCheck)?.visibility == View.VISIBLE
            // The screen preselects one document: clear it so the order below is unambiguous.
            if (selected(class10)) { row(class10).performClick(); idle() }
            if (selected(income)) { row(income).performClick(); idle() }
            // Tap in reverse order to prove the preview follows selection, not library sort.
            row(income).performClick()
            idle()
            row(class10).performClick()
            idle()

            val options = activity.findViewById<View>(R.id.toolSetupOptions)
            val rows = allByTag(options, "ffm_order_row")
            assertEquals("one numbered row per selected document", 2, rows.size)
            val labels = rows.map {
                (findByTag(it, "ffm_order_number") as? TextView)?.text.toString()
            }
            assertEquals(listOf("1. Income certificate", "2. Class 10"), labels)

            val downs = allByTag(options, "ffm_move_down")
            assertEquals("each row has a named down action", 2, downs.size)
            assertTrue("down action is 48dp", downs[0].minimumHeight >= 48)
            assertTrue("down action speaks the document name",
                downs[0].contentDescription?.toString()?.contains("Income certificate") == true)
            downs[0].performClick()
            idle()

            val after = allByTag(activity.findViewById(R.id.toolSetupOptions), "ffm_order_number")
                .map { (it as TextView).text.toString() }
            assertEquals("Move down actually reorders", listOf("1. Class 10", "2. Income certificate"), after)

            val summary = findByTag(activity.findViewById(R.id.toolSetupOptions), "ffm_summary") as? TextView
            assertNotNull("the resulting pages and bytes are shown", summary)
            assertTrue("summary states the page count: ${summary!!.text}", summary.text.contains("5 pages"))
            assertNotNull(
                "one helper line links to Compress",
                findByTag(activity.findViewById(R.id.toolSetupOptions), "ffm_compress_hint")
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
