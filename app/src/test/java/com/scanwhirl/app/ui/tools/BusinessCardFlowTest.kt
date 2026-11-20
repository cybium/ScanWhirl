package com.scanwhirl.app.ui.tools

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.provider.ContactsContract
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
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
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * v2 UI: 03_tools_hub — Business card: capture -> on-device OCR (stubbed here) -> parsed kit form
 * -> Save to contacts / Share as vCard / Save card image. Cancel creates nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BusinessCardFlowTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    private val cardText = """
        Rahul Sharma
        Sales Manager
        Acme Technologies Pvt Ltd
        M: +91 98765 43210
        E-mail: rahul.sharma@acme.in
        www.acmetech.in
        12 MG Road, Bengaluru 560001
    """.trimIndent()

    @Before
    fun setUp() {
        BusinessCardActivity.recognizeForTest = { cardText }
        // androidx FileProvider caches path roots per authority; Robolectric gives each test a
        // fresh cache dir, so clear the stale entry (same fix as ShareUtilsTest).
        val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        f.isAccessible = true
        (f.get(null) as MutableMap<*, *>).clear()
    }

    @After
    fun tearDown() {
        BusinessCardActivity.recognizeForTest = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(900, 560, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawColor(Color.WHITE)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun waitUntil(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun openForm(scenario: ActivityScenario<BusinessCardActivity>) {
        scenario.onActivity { it.acceptCapture(jpeg()) }
        assertTrue("the form appears after OCR", waitUntil {
            var ready = false
            scenario.onActivity { ready = it.findViewById<EditText>(R.id.bcName) != null }
            ready
        })
    }

    @Test
    fun `cancelled capture shows the scan and gallery choice`() {
        ActivityScenario.launch(BusinessCardActivity::class.java).use { scenario ->
            scenario.onActivity {
                idle()
                val dialog = requireNotNull(ShadowDialog.getLatestDialog())
                assertEquals(
                    it.getString(R.string.tool_business_card),
                    dialog.findViewById<android.widget.TextView>(R.id.swSheetTitle).text.toString()
                )
                assertNotNull(dialog.findViewById<View>(R.id.swSheetPrimary))
                assertNotNull(dialog.findViewById<View>(R.id.swSheetSecondary))
            }
        }
    }

    @Test
    fun `the gallery choice goes through the shared scanner crop`() {
        var started = false
        com.scanwhirl.app.tools.GalleryCrop.startOverride = { started = true; true }
        try {
            ActivityScenario.launch(BusinessCardActivity::class.java).use { scenario ->
                scenario.onActivity {
                    idle()
                    val dialog = requireNotNull(ShadowDialog.getLatestDialog())
                    assertEquals(
                        it.getString(R.string.tools_id_pick_gallery),
                        dialog.findViewById<android.widget.TextView>(R.id.swSheetSecondary).text.toString()
                    )
                    dialog.findViewById<View>(R.id.swSheetSecondary).performClick()
                    idle()
                }
            }
            assertTrue("gallery must open launchGalleryCrop", started)
        } finally {
            com.scanwhirl.app.tools.GalleryCrop.startOverride = null
        }
    }

    @Test
    fun `parsed card prefills the editable fields`() {
        ActivityScenario.launch(BusinessCardActivity::class.java).use { scenario ->
            openForm(scenario)
            scenario.onActivity { activity ->
                assertEquals("Rahul Sharma", activity.findViewById<EditText>(R.id.bcName).text.toString())
                assertEquals(
                    "Sales Manager",
                    activity.findViewById<EditText>(R.id.bcJob).text.toString()
                )
                assertEquals(
                    "Acme Technologies Pvt Ltd",
                    activity.findViewById<EditText>(R.id.bcCompany).text.toString()
                )
                assertEquals(
                    "+91 98765 43210",
                    activity.findViewById<EditText>(R.id.bcPhone).text.toString()
                )
                assertEquals(
                    "rahul.sharma@acme.in",
                    activity.findViewById<EditText>(R.id.bcEmail).text.toString()
                )
                assertEquals(
                    "www.acmetech.in",
                    activity.findViewById<EditText>(R.id.bcWebsite).text.toString()
                )
                assertTrue(
                    activity.findViewById<EditText>(R.id.bcAddress).text.toString().contains("MG Road")
                )
            }
        }
    }

    @Test
    fun `save to contacts opens the prefilled insert intent`() {
        ActivityScenario.launch(BusinessCardActivity::class.java).use { scenario ->
            openForm(scenario)
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.bcSaveContact).performClick()
                idle()
                val intent = shadowOf(activity).nextStartedActivity
                assertNotNull("contacts editor must open", intent)
                assertEquals(ContactsContract.Intents.Insert.ACTION, intent.action)
                assertEquals(ContactsContract.Contacts.CONTENT_TYPE, intent.type)
                assertEquals(
                    "Rahul Sharma",
                    intent.getStringExtra(ContactsContract.Intents.Insert.NAME)
                )
                assertEquals(
                    "+91 98765 43210",
                    intent.getStringExtra(ContactsContract.Intents.Insert.PHONE)
                )
            }
        }
        assertTrue("contacts must not create a document", repo.listDocuments().isEmpty())
    }

    @Test
    fun `share as vcard writes a vcf and opens the sharesheet`() {
        ActivityScenario.launch(BusinessCardActivity::class.java).use { scenario ->
            openForm(scenario)
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.bcShareVCard).performClick()
            }
            val shareDir = File(app.cacheDir, "documents/share")
            assertTrue("the vCard is written", waitUntil {
                shareDir.walkTopDown().any { it.isFile && it.name.endsWith(".vcf") }
            })
            assertTrue("the sharesheet opens", waitUntil {
                var opened = false
                scenario.onActivity { activity ->
                    opened = shadowOf(activity).peekNextStartedActivity()?.action == Intent.ACTION_CHOOSER
                }
                opened
            })
        }
        val vcf = File(app.cacheDir, "documents/share").walkTopDown()
            .first { it.isFile && it.name.endsWith(".vcf") }
        val text = vcf.readText()
        assertTrue("vCard header: $text", text.startsWith("BEGIN:VCARD\r\nVERSION:3.0\r\n"))
        assertTrue(text.contains("FN:Rahul Sharma"))
        assertTrue(text.contains("TEL;TYPE=CELL:+91 98765 43210"))
        assertTrue(text.endsWith("END:VCARD\r\n"))
    }

    @Test
    fun `save card image publishes one document and opens the result`() {
        ActivityScenario.launch(BusinessCardActivity::class.java).use { scenario ->
            openForm(scenario)
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.bcSaveImage).performClick()
            }
            assertTrue("the card document is created", waitUntil {
                repo.listDocuments().isNotEmpty()
            })
            assertTrue("the result screen opens", waitUntil {
                var open = false
                scenario.onActivity { activity ->
                    open = shadowOf(activity).peekNextStartedActivity()?.component?.className ==
                        ToolResultActivity::class.java.name
                }
                open
            })
        }
        val doc = repo.listDocuments().first()
        assertEquals("the card is named after its contact", "Rahul Sharma", doc.name)
        assertEquals(1, doc.pageCount)
    }

    @Test
    fun `leaving without an action creates nothing`() {
        ActivityScenario.launch(BusinessCardActivity::class.java).use { scenario ->
            openForm(scenario)
            scenario.onActivity { it.finish() }
        }
        idle()
        assertTrue("cancel creates no document", repo.listDocuments().isEmpty())
    }
}
