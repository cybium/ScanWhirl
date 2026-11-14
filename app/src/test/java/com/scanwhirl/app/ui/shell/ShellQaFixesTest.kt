package com.scanwhirl.app.ui.shell

import android.app.Application
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.graphics.Insets
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
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
import org.robolectric.shadows.ShadowDialog

/**
 * v2 UI: 01_home_A / 03_tools_hub — phone-QA fixes: search over names, OCR text
 * and tags with an honest empty state; the persisted sort sheet; the nav bar
 * hiding for the keyboard; Searchable PDF as a real tool; opaque sheets.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ShellQaFixesTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        Prefs.setLibrarySort(app, Prefs.LIB_NEWEST)
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
        Prefs.setLibrarySort(app, Prefs.LIB_NEWEST)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(block: (ShellActivity) -> Unit) {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                block(activity)
            }
        }
    }

    private fun cards(activity: ShellActivity): List<com.scanwhirl.app.ui.kit.DocCard> {
        val list = activity.findViewById<ViewGroup>(R.id.swDocList)
        return (0 until list.childCount).map { list.getChildAt(it) as com.scanwhirl.app.ui.kit.DocCard }
    }

    private fun search(activity: ShellActivity, q: String) {
        activity.findViewById<EditText>(R.id.swHomeSearch).setText(q)
        idle()
    }

    @Test
    fun `search matches a name, OCR text and tags, and explains an empty result`() {
        val invoice = DocFixtures.saved(repo, app, pages = 1, name = "Invoice 0231")
        val other = DocFixtures.saved(repo, app, pages = 1, name = "Passport copy")
        DocFixtures.withOcr(repo, other)
        repo.addTag(listOf(invoice), "bills")
        launch { activity ->
            search(activity, "invoice")
            assertEquals("name match", listOf("Invoice 0231"), cards(activity).map { it.doc?.name })

            search(activity, "text page_000")
            assertEquals("OCR text match", listOf("Passport copy"), cards(activity).map { it.doc?.name })

            search(activity, "bills")
            assertEquals("tag match", listOf("Invoice 0231"), cards(activity).map { it.doc?.name })

            search(activity, "zzz-no-hit")
            assertTrue("no cards", cards(activity).isEmpty())
            val empty = activity.findViewById<View>(R.id.swHomeEmpty)
            assertTrue("the empty state is shown", empty.isVisible)
            val title = (empty as ViewGroup).getChildAt(1) as TextView
            assertTrue("it names the query: ${title.text}", title.text.toString().contains("zzz-no-hit"))
        }
    }

    @Test
    fun `the Newest control sorts and the choice persists`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Beta")
        DocFixtures.saved(repo, app, pages = 1, name = "Alpha")
        launch { activity ->
            assertEquals(
                "Newest ▾",
                activity.findViewById<TextView>(R.id.swSort).text.toString()
            )
            activity.findViewById<View>(R.id.swSort).performClick()
            idle()
            val sheet = ShadowDialog.getLatestDialog()
            assertNotNull("the sort sheet opens", sheet)
            val options = sheet.findViewById<ViewGroup>(R.id.swSheetItems)
            assertEquals(5, options.childCount)
            options.getChildAt(2).performClick() // Name A-Z
            idle()
            assertEquals(
                listOf("Alpha", "Beta"),
                cards(activity).map { it.doc?.name }
            )
            assertEquals(Prefs.LIB_NAME_ASC, Prefs.librarySort(app))
            assertEquals(
                "Name A-Z ▾",
                activity.findViewById<TextView>(R.id.swSort).text.toString()
            )
        }
    }

    @Test
    fun `the nav bar and disc hide while the keyboard is up`() {
        launch { activity ->
            val navBar = activity.findViewById<View>(R.id.swShellNav)
            val navHost = navBar.parent as View
            assertTrue("nav is visible at rest", navHost.isVisible)
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

            val imeShown = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 600))
                .build()
            ViewCompat.dispatchApplyWindowInsets(root, imeShown)
            assertFalse("the nav bar hides for the keyboard", navHost.isVisible)

            val imeHidden = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                .build()
            ViewCompat.dispatchApplyWindowInsets(root, imeHidden)
            assertTrue("the nav bar returns", navHost.isVisible)
        }
    }

    @Test
    fun `Searchable PDF picks a document and builds the text layer`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Search me")
        DocFixtures.withOcr(repo, id)
        assertFalse("starts without a text layer", repo.getDocument(id)!!.isSearchable)

        launch { activity ->
            activity.findViewById<View>(R.id.swNavTools).performClick()
            idle()
            val tile = ToolsHubScreen.tiles(activity.findViewById(R.id.swToolsBody))
                .first { it.tool?.id == "searchable_pdf" }
            tile.performClick()
            idle()
            val picker = ShadowDialog.getLatestDialog()
            assertNotNull("the document chooser opens", picker)
            val rows = picker.findViewById<ViewGroup>(R.id.swSheetItems)
            assertNotNull("a real chooser", rows)
            (0 until rows.childCount).map { rows.getChildAt(it) }
                .first {
                    val label = (it as ViewGroup).getChildAt(0) as TextView
                    label.text == "Search me"
                }
                .performClick()
            idle()
            val sheet = ShadowDialog.getLatestDialog()
            assertNotNull("the make-searchable sheet opens", sheet)
            sheet.findViewById<View>(R.id.swSheetPrimary).performClick()
            idle()
            var searchable = false
            val deadline = System.currentTimeMillis() + 4000
            while (!searchable && System.currentTimeMillis() < deadline) {
                idle()
                searchable = repo.getDocument(id)?.isSearchable == true
                Thread.sleep(30)
            }
            assertTrue("the PDF now carries the text layer", searchable)
        }
    }

    @Test
    fun `sheet surface is fully opaque`() {
        val sheet = SwSheet.builder(app).setTitle("Opaque").build()
        val colour = (sheet.view.background as GradientDrawable).color?.defaultColor ?: 0
        assertEquals("no dock shows through a sheet", 255, (colour ushr 24) and 0xFF)
        sheet.dismiss()
    }
}
