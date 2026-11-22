package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * UI-W4 Part B in Hindi: the explicit folder route and the active-filter line use the
 * reviewed values-hi copy, never an English fallback. The folder name is the owner's
 * own Devanagari text and stays exactly as typed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi")
class LibraryFolderAccessHindiTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        Prefs.setIndexBackfillDone(app, true)
        Prefs.setShowGreeting(app, false)
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(10)
        }
        idle()
        assertTrue(what, runCatching(condition).getOrDefault(false))
    }

    private fun allTexts(root: View?): List<String> = allTextViews(root).mapNotNull { it.text?.toString() }

    private fun allTextViews(root: View?): List<TextView> {
        if (root == null) return emptyList()
        val out = mutableListOf<TextView>()
        fun walk(view: View) {
            if (view is TextView && view.visibility == View.VISIBLE) out += view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    private fun byDescription(root: View?, description: String): List<View> {
        if (root == null) return emptyList()
        val out = mutableListOf<View>()
        fun walk(view: View) {
            if (view.contentDescription?.toString() == description) out += view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    @Test
    fun `Hindi All folders lists a folder and names the active filter`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "कर रसीद")
        assertTrue(repo.createFolder("कर बिल"))
        repo.setFolder(listOf(id), "कर बिल")

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                waitFor("All folders exists") {
                    activity.findViewById<View>(R.id.swAllFolders) != null
                }
                val chip = activity.findViewById<TextView>(R.id.swAllFolders)
                assertTrue(chip.isVisible)
                assertEquals(activity.getString(R.string.cb_all_folders_control), chip.text.toString())

                chip.performClick()
                idle()
                val sheet = ShadowDialog.getLatestDialog()
                assertNotNull(sheet)
                assertEquals(
                    "सभी फ़ोल्डर",
                    sheet!!.findViewById<TextView>(R.id.swSheetTitle).text.toString()
                )
                val content = sheet.findViewById<ViewGroup>(R.id.swSheetContent)
                val texts = allTexts(content)
                assertTrue("the Hindi folder is listed: $texts", texts.any { it.startsWith("कर बिल (") })
                assertTrue(
                    "the row's More is named in Hindi",
                    byDescription(content, "कर बिल के फ़ोल्डर विकल्प").isNotEmpty()
                )
                // Council §7: OLD picked the folder chip from the face rail. NEW picks it
                // from the shared chooser that "All folders ▾" opens; choosing it filters.
                val row = content!!.findViewById<ViewGroup>(R.id.swFolderRow)
                val folderName = repo.listFolders().single()
                val folderChip = allTextViews(row).first { it.text.toString().startsWith(folderName) }
                folderChip.performClick()
                idle()

                val active = activity.findViewById<TextView>(R.id.swActiveFilter)
                assertNotNull("the active filter is stated in Hindi", active)
                assertTrue(active.isVisible)
                assertEquals("सिर्फ़ यह दिखा रहे हैं: कर बिल", active.text.toString())
                assertEquals("फ़ोल्डर के हिसाब से: कर बिल", active.contentDescription?.toString())
            }
        }
    }

    @Test
    fun `Hindi zero folder state still offers New folder`() {
        DocFixtures.saved(repo, app, pages = 1, name = "एक दस्तावेज़")
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                waitFor("All folders exists") {
                    activity.findViewById<View>(R.id.swAllFolders) != null
                }
                activity.findViewById<View>(R.id.swAllFolders).performClick()
                idle()
                val sheet = ShadowDialog.getLatestDialog()
                assertNotNull(sheet)
                val empty = sheet!!.findViewById<TextView>(R.id.swFolderSheetEmpty)
                assertNotNull("the empty state is explicit", empty)
                assertEquals("अभी कोई फ़ोल्डर नहीं", empty.text.toString())
                val primary = sheet.findViewById<TextView>(R.id.swSheetPrimary)
                assertTrue(
                    "the empty state offers a folder in Hindi: ${primary.text}",
                    primary.text.toString().contains("नया फ़ोल्डर")
                )
                assertTrue(primary.isVisible)
            }
        }
    }
}
