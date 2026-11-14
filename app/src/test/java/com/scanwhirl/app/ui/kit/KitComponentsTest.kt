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
    fun `coming soon sheet names the tool and explains it in one line`() {
        val tool = ToolRegistry.byId("qr_code")!!
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
        tile.bind(ToolRegistry.byId("to_excel")!!)
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
