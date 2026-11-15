package com.scanwhirl.app.ui.kit

import android.app.Application
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * v2 UI: 01_home_A / 03_tools_hub / 05_protect_pdf / 09_unlock_sheet — the kit
 * every lane reuses (70_APP_DESIGN.md §2).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class KitComponentsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `SwSheet shows grab handle, title, close and primary button`() {
        var clicked = false
        val sheet = SwSheet.builder(app)
            .setTitle("Rent agreement is protected")
            .setMessage("Enter its password to open it.")
            .setPrimary("Open") { clicked = true }
            .build()
        sheet.show()

        val root = sheet.view
        assertNotNull(root.findViewById<View>(R.id.swSheetGrab))
        assertEquals(
            "Rent agreement is protected",
            root.findViewById<TextView>(R.id.swSheetTitle).text.toString()
        )
        assertEquals(
            "Enter its password to open it.",
            root.findViewById<TextView>(R.id.swSheetMessage).text.toString()
        )
        val primary = root.findViewById<View>(R.id.swSheetPrimary)
        assertNotNull(primary)
        primary.performClick()
        assertTrue("primary button must run its action", clicked)
        assertTrue("close must be present", root.findViewById<View>(R.id.swSheetClose) != null)
        sheet.dismiss()
    }

    @Test
    fun `option sheet lists rows in order, checks the choice and closes on pick`() {
        var picked = -1
        val sheet = SwSheet.builder(app)
            .setTitle("Appearance")
            .setOptions(listOf("Light", "Dark", "System"), checked = 1) { which, _ -> picked = which }
            .build()
        sheet.show()

        val list = sheet.view.findViewById<android.view.ViewGroup>(R.id.swSheetItems)
        assertEquals("one row per option", 3, list.childCount)
        assertEquals(
            "Light",
            ((list.getChildAt(0) as android.view.ViewGroup).getChildAt(0) as TextView).text.toString()
        )
        // The checked row carries the 20dp check mark.
        assertEquals(2, (list.getChildAt(1) as android.view.ViewGroup).childCount)

        (list.getChildAt(2) as View).performClick()
        assertEquals("the tapped index reaches the caller", 2, picked)
        assertFalse("a single choice closes the sheet", sheet.isShowing)
    }

    @Test
    fun `multi-choice sheet toggles and stays open for the primary`() {
        val checked = booleanArrayOf(false, false, false)
        val sheet = SwSheet.builder(app)
            .setTitle("Merge")
            .setOptions(listOf("One", "Two", "Three"), multiChecked = checked) { which, isChecked ->
                checked[which] = isChecked
            }
            .setPrimary("Merge")
            .build()
        sheet.show()

        val list = sheet.view.findViewById<android.view.ViewGroup>(R.id.swSheetItems)
        (list.getChildAt(1) as View).performClick()
        assertTrue("row 1 toggled on", checked[1])
        assertTrue("multi-choice keeps the sheet open", sheet.isShowing)
        sheet.view.findViewById<View>(R.id.swSheetPrimary).performClick()
        assertFalse("the primary closes it", sheet.isShowing)
    }

    @Test
    fun `input sheet fills its field and the primary reads it`() {
        var typed: String? = null
        val sheet = SwSheet.builder(app)
            .setTitle("Rename")
            .setInput(initial = "Rent agreement", hint = "Name")
            .build()
        sheet.setPrimary("Save") { typed = sheet.inputView?.text?.toString() }
        sheet.show()

        val input = sheet.view.findViewById<android.widget.EditText>(R.id.swSheetInput)
        assertNotNull("the sheet carries swSheetInput", input)
        assertEquals("Rent agreement", input.text.toString())
        input.setText("Rent agreement 2026")
        sheet.view.findViewById<View>(R.id.swSheetPrimary).performClick()
        assertEquals("Rent agreement 2026", typed)
    }

    @Test
    fun `a stay-open primary keeps the sheet up until the work succeeds`() {
        var attempts = 0
        val sheet = SwSheet.builder(app).setTitle("Unsaved").setMessage("Save first?").build()
        sheet.setPrimaryStayOpen("Save") {
            attempts++
            if (attempts == 2) sheet.dismiss()
        }
        sheet.show()

        val primary = sheet.view.findViewById<View>(R.id.swSheetPrimary)
        primary.performClick()
        assertTrue("a failed attempt leaves the sheet open", sheet.isShowing)
        primary.performClick()
        assertEquals(2, attempts)
        assertFalse("a successful attempt closes it", sheet.isShowing)
    }

    @Test
    fun `coming soon sheet names the tool and explains it in one line`() {
        // v2 UI: 03_tools_hub - every catalogue tool ships now, so the sheet is tested directly.
        val tool = SwTool(
            "test_soon", ToolGroup.CONVERT, R.string.tool_pdfa, R.drawable.ic_sym_approval,
            true, ToolStatus.SOON, "test soon", soonNote = R.string.soon_generic
        )
        val sheet = SwSheet.comingSoon(app, tool)
        sheet.show()
        assertEquals(
            app.getString(R.string.tool_coming_soon_title),
            sheet.view.findViewById<TextView>(R.id.swSheetTitle).text.toString()
        )
        assertEquals(
            app.getString(tool.soonNote!!),
            sheet.view.findViewById<TextView>(R.id.swSheetMessage).text.toString()
        )
        sheet.dismiss()
    }

    @Test
    fun `PasswordField hides the text until the eye is tapped`() {
        val field = PasswordField(app)
        field.editText.setText("secret123")
        assertTrue(field.isHidden)
        field.toggleVisibility()
        assertFalse(field.isHidden)
        assertFalse("plain text must not be hidden once revealed", field.editText.transformationMethod != null)
        field.toggleVisibility()
        assertTrue(field.isHidden)
    }

    @Test
    fun `PasswordField shows an error line`() {
        val field = PasswordField(app)
        assertFalse(field.errorView.isVisible)
        field.setError("That password didn't match.")
        assertTrue(field.errorView.isVisible)
        assertEquals("That password didn't match.", field.errorView.text.toString())
        field.setError(null)
        assertFalse(field.errorView.isVisible)
    }

    @Test
    fun `PasswordField strength meter has four segments and rises with length`() {
        val field = PasswordField(app)
        assertTrue(field.showStrength)
        assertEquals(4, field.segments.size)
        val scores = listOf("abc", "abcdef", "abcdefghij", "abcdefghij1", "abcdefghij1234!")
            .map { PasswordStrength.score(it) }
        assertEquals(0, scores[0])
        assertTrue("6 chars is at least Weak", scores[1] >= 1)
        assertTrue("longer and mixed must beat weak", scores[2] > scores[1])
        assertTrue("digit must raise the score", scores[3] > scores[2])
        assertEquals(4, scores[4])
        field.editText.setText("abcdefghij1234!")
        assertEquals(4, field.litSegments)
    }

    @Test
    fun `ToolTile shows label and PRO tag for Pro tools`() {
        val tile = ToolTile(app)
        val sign = ToolRegistry.byId("sign")!!
        tile.bind(sign)
        assertEquals(app.getString(sign.title), tile.labelView.text.toString())
        assertTrue(tile.proTag.isVisible)
        assertFalse(tile.soonTag.isVisible)

        val scan = ToolRegistry.byId("scan")!!
        tile.bind(scan)
        assertFalse(tile.proTag.isVisible)
        assertFalse(tile.soonTag.isVisible)
    }

    @Test
    fun `ToolTile shows Soon tag for tool not yet built`() {
        val tile = ToolTile(app)
        // v2 UI: 03_tools_hub - no catalogue tool is Soon any more; bind one directly.
        tile.bind(
            SwTool(
                "test_soon", ToolGroup.CONVERT, R.string.tool_pdfa, R.drawable.ic_sym_approval,
                true, ToolStatus.SOON, "test soon", soonNote = R.string.soon_generic
            )
        )
        assertTrue(tile.soonTag.isVisible)
    }

    @Test
    fun `DocCard shows name, meta, category and protected badge`() {
        val doc = ScanDocument(
            id = "doc1",
            name = "Rent agreement",
            createdAt = System.currentTimeMillis(),
            pageCount = 4,
            pdfFile = File(app.cacheDir, "none.pdf"),
            pageFiles = emptyList(),
            isLocked = true,
            category = com.scanwhirl.app.util.CategoryRules.FORMS
        )
        val card = DocCard(app)
        var shared = false
        var more = false
        card.bind(doc, "4 pages · 1.2 MB · Yesterday", onShare = { shared = true }, onMore = { more = true })

        assertEquals("Rent agreement", card.nameView.text.toString())
        assertEquals("4 pages · 1.2 MB · Yesterday", card.metaView.text.toString())
        assertEquals("Forms", card.categoryView.text.toString())
        assertTrue(card.lockBadge.isVisible)
        card.shareButton.performClick()
        card.moreButton.performClick()
        assertTrue(shared)
        assertTrue(more)
    }

    @Test
    fun `TrustPill renders the offline promise`() {
        val pill = TrustPill.offlineNoAds(app)
        assertEquals(app.getString(R.string.trust_offline_no_ads), pill.text.toString())
    }
}
