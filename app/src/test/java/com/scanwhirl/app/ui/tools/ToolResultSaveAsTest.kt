package com.scanwhirl.app.ui.tools

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.FakeMediaStore
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
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.time.Duration

/**
 * F7-SAVE, hunt H2-2. "Save as..." on a tool result.
 *
 * The screen asks the system picker for ONE type (the result's own) but hands it a ZIP for several
 * results, so the Android picker appends the type's extension and the person is left with
 * "Split.zip.pdf" holding ZIP bytes. The receipt then names the suggestion, not the file the
 * picker actually created (which the person may also have renamed), and a copy that failed was
 * never removed, leaving an empty file in the folder the person chose.
 *
 * These tests pin the promise:
 *  - the picker is asked for application/zip when the bytes are a ZIP (several documents or
 *    several files) and for the result's own type otherwise;
 *  - the receipt and the confirmation name the file the provider really created
 *    (DISPLAY_NAME), not the suggestion;
 *  - a copy that could not be written is removed again, through DocumentsContract or the
 *    resolver's delete, and no location is claimed for it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ToolResultSaveAsTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var media: FakeMediaStore
    private lateinit var picker: FakeSaveAsProvider

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        media = FakeMediaStore().also { provider ->
            provider.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true })
            ShadowContentResolver.registerProviderInternal("media", provider)
        }
        picker = FakeSaveAsProvider().also { provider ->
            provider.attachInfo(app, ProviderInfo().apply { authority = AUTH; exported = true })
            ShadowContentResolver.registerProviderInternal(AUTH, provider)
        }
        val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        f.isAccessible = true
        (f.get(null) as MutableMap<*, *>).clear()
        DownloadSaver.resetForTests()
    }

    @After
    fun tearDown() {
        DownloadSaver.resetForTests()
        media.close()
    }

    // ---- 1. the picker type matches the bytes ------------------------------

    @Test
    fun `several documents ask the picker for a zip`() {
        val ids = listOf(
            DocFixtures.saved(repo, app, pages = 1, name = "Part one"),
            DocFixtures.saved(repo, app, pages = 1, name = "Part two")
        )
        assertEquals("application/zip", pickerType(ToolResultActivity.intent(app, "split", docIds = ids)))
    }

    @Test
    fun `several files ask the picker for a zip`() {
        val files = listOf(resultFile("Offer letter.docx"), resultFile("Terms.docx"))
        assertEquals(
            "application/zip",
            pickerType(ToolResultActivity.intent(app, "to_word", files = files, mime = DOCX_MIME))
        )
    }

    @Test
    fun `one document asks the picker for its pdf`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Rent receipt")
        assertEquals(
            "application/pdf",
            pickerType(ToolResultActivity.intent(app, "split", docIds = listOf(id)))
        )
    }

    @Test
    fun `one exported file asks the picker for the type it already has`() {
        val files = listOf(resultFile("Offer letter.docx"))
        assertEquals(
            DOCX_MIME,
            pickerType(ToolResultActivity.intent(app, "to_word", files = files, mime = DOCX_MIME))
        )
    }

    @Test
    fun `a zip result keeps asking the picker for a zip`() {
        val files = listOf(resultFile("images.zip"))
        assertEquals(
            "application/zip",
            pickerType(
                ToolResultActivity.intent(app, "pdf_to_images", files = files, mime = "application/zip")
            )
        )
    }

    // ---- 2. the receipt names the file the provider created ----------------

    @Test
    fun `the receipt names the file the picker really created`() {
        val ids = listOf(
            DocFixtures.saved(repo, app, pages = 1, name = "Part one"),
            DocFixtures.saved(repo, app, pages = 1, name = "Part two")
        )
        picker.displayName = "Split (1).zip"
        val target = pickerUri()
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "split", docIds = ids)
        ).use { scenario ->
            openPicker(scenario, target)
            assertTrue(
                "the ZIP bytes reach the picker's document",
                pollUntil { picker.bytesOf(target).isNotEmpty() }
            )
            assertTrue(
                "H2-2: the receipt names the created file, not the suggestion: ${summaryTextOf(scenario)}",
                pollUntil { summaryTextOf(scenario).contains("Split (1).zip") }
            )
            assertTrue(
                "H2-2: the confirmation names the created file too",
                pollUntil {
                    ShadowToast.getTextOfLatestToast().orEmpty()
                        .contains(app.getString(R.string.tools_result_saved, "Split (1).zip"))
                }
            )
            assertFalse(
                "the suggestion is not claimed as the created name: ${summaryTextOf(scenario)}",
                summaryTextOf(scenario).contains("Split.zip")
            )
        }
    }

    // ---- 3. a failed copy is removed again ---------------------------------

    @Test
    fun `a copy that could not be written is removed from the chosen folder`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Rent receipt")
        // A provider that keeps nothing: the written bytes never match on read-back.
        picker.keepWrites = false
        val target = pickerUri()
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "split", docIds = listOf(id))
        ).use { scenario ->
            openPicker(scenario, target)
            assertTrue(
                "H2-2: the failed copy is deleted again (delete=${picker.deletes}, " +
                    "deleteDocument=${picker.documentDeletes})",
                pollUntil { picker.deletes + picker.documentDeletes > 0 }
            )
            assertTrue(
                "H2-2: the created document is gone",
                pollUntil { !picker.existing(target) }
            )
            assertFalse(
                "no location is claimed for a copy that failed: ${summaryTextOf(scenario)}",
                summaryTextOf(scenario).contains(app.getString(R.string.rs_where_chosen))
            )
            assertTrue(
                "the screen says the chosen file could not be written: ${errorTextOf(scenario)}",
                pollUntil {
                    errorTextOf(scenario).contains(
                        app.getString(R.string.f7save_picker_write_failed)
                    )
                }
            )
        }
    }

    @Test
    fun `a cancelled picker writes nothing and claims nothing`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Rent receipt")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "split", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                activity.findViewById<View>(R.id.toolResultMore).performClick()
                idle()
                activity.findViewById<View>(R.id.toolResultSave).performClick()
                idle()
                shadowOf(activity).receiveResult(
                    requireNotNull(shadowOf(activity).nextStartedActivityForResult).intent,
                    android.app.Activity.RESULT_CANCELED,
                    null
                )
                idleFor(400)
            }
            assertTrue("nothing was created", picker.deletes + picker.documentDeletes == 0)
            assertFalse(
                "no location is claimed: ${summaryTextOf(scenario)}",
                summaryTextOf(scenario).contains(app.getString(R.string.rs_where_chosen))
            )
        }
    }

    // ---- helpers ----------------------------------------------------------

    private val DOCX_MIME =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    private fun resultFile(name: String): File =
        File(app.cacheDir, "documents/$name").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(256) { (it % 23 + 2).toByte() })
        }

    private fun pickerUri(): Uri = Uri.parse("content://$AUTH/document/chosen")

    /** Opens the result's Save as… picker and returns the type it was asked for. */
    private fun pickerType(intent: Intent): String {
        var type = ""
        ActivityScenario.launch<ToolResultActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                activity.findViewById<View>(R.id.toolResultMore).performClick()
                idle()
                activity.findViewById<View>(R.id.toolResultSave).performClick()
                idle()
                type = shadowOf(activity).nextStartedActivityForResult?.intent?.type.orEmpty()
            }
        }
        return type
    }

    private fun openPicker(scenario: ActivityScenario<ToolResultActivity>, target: Uri) {
        var asked: Intent? = null
        scenario.onActivity { activity ->
            idle()
            activity.findViewById<View>(R.id.toolResultMore).performClick()
            idle()
            activity.findViewById<View>(R.id.toolResultSave).performClick()
            idle()
            asked = shadowOf(activity).nextStartedActivityForResult?.intent
        }
        assertNotNull("the system picker opens", asked)
        scenario.onActivity { activity ->
            shadowOf(activity).receiveResult(
                asked!!,
                android.app.Activity.RESULT_OK,
                Intent().setData(target)
            )
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun pollUntil(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun summaryTextOf(scenario: ActivityScenario<ToolResultActivity>): String {
        var text = ""
        scenario.onActivity { activity ->
            val location = activity.findViewById<TextView>(R.id.rsMoreLocation)
            text = location?.text?.toString().orEmpty()
        }
        return text
    }

    private fun errorTextOf(scenario: ActivityScenario<ToolResultActivity>): String {
        var text = ""
        scenario.onActivity { activity ->
            text = activity.findViewById<TextView>(R.id.toolResultError)?.text?.toString().orEmpty()
        }
        return text
    }

    /**
     * A DocumentsProvider-shaped picker destination: reports a DISPLAY_NAME (the provider may
     * adjust the name, or the person may rename there), holds the written bytes, and records
     * every removal so a failed copy can be proven gone.
     */
    class FakeSaveAsProvider : ContentProvider() {

        /** What OpenableColumns.DISPLAY_NAME answers; null means "the provider says nothing". */
        @Volatile var displayName: String? = null

        /** False = the provider keeps none of what was written (a copy that cannot be verified). */
        @Volatile var keepWrites = true

        @Volatile var deletes = 0
        @Volatile var documentDeletes = 0

        private val created = HashSet<String>()
        private val lock = Any()

        fun existing(uri: Uri): Boolean = synchronized(lock) { created.contains(idOf(uri)) }

        fun bytesOf(uri: Uri): ByteArray = synchronized(lock) {
            val file = fileFor(idOf(uri))
            if (file.exists()) file.readBytes() else ByteArray(0)
        }

        private fun idOf(uri: Uri): String = uri.pathSegments.lastOrNull().orEmpty()

        private fun forget(uri: Uri) = synchronized(lock) {
            created.remove(idOf(uri))
            fileFor(idOf(uri)).delete()
        }

        private fun fileFor(id: String): File = File(context!!.cacheDir, "f7save-picker-$id")

        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String = "application/zip"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deletes++
            val existed = existing(uri)
            forget(uri)
            return if (existed) 1 else 0
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            val name = displayName
            if (name != null) cursor.addRow(arrayOf<Any?>(name, bytesOf(uri).size.toLong()))
            return cursor
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (method == DELETE_DOCUMENT_METHOD) {
                documentDeletes++
                uriExtra(extras)?.let { forget(it) } ?: run { created.clear() }
                return Bundle()
            }
            return null
        }

        @Suppress("DEPRECATION")
        private fun uriExtra(extras: Bundle?): Uri? =
            extras?.getParcelable(EXTRA_DOCUMENT_URI) as? Uri

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            synchronized(lock) { created.add(idOf(uri)) }
            if (mode.startsWith("w")) {
                val target = if (keepWrites) {
                    fileFor(idOf(uri))
                } else {
                    // Discard everything: the document stays empty, so the read-back cannot match.
                    File.createTempFile("discard", ".bin", context!!.cacheDir)
                }
                return ParcelFileDescriptor.open(
                    target,
                    ParcelFileDescriptor.MODE_WRITE_ONLY or
                        ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE
                )
            }
            val file = fileFor(idOf(uri))
            if (!file.exists()) file.writeBytes(ByteArray(0))
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    private companion object {
        const val AUTH = "f7.saveas.test"

        /** The DocumentsProvider delete call and its bundle key (hidden from the public SDK). */
        const val DELETE_DOCUMENT_METHOD = "android:deleteDocument"
        const val EXTRA_DOCUMENT_URI = "uri"
    }
}
