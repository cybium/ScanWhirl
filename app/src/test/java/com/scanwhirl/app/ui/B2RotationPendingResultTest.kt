package com.scanwhirl.app.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * B2 (R10 F1/F2 + NEW-1): Android re-delivers a pending result to the recreated Activity, but the
 * screens used to keep the pending action only in a field. Rotate between the prompt and the
 * answer and the save vanished; cancel a camera "Insert pages" and its plaintext copies stayed in
 * the cache. These tests rotate with Robolectric `recreate()` *before* delivering the result.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B2RotationPendingResultTest {

    private companion object {
        const val OCR_TEXT = "B2 rotation proof: café acknowledges"
    }

    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()

    private lateinit var app: Application
    private var publishCount = 0

    @Suppress("DEPRECATION")
    private val jpegDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "${DownloadSaver.SUBDIR}/Resize"
    )

    @Suppress("DEPRECATION")
    private val txtDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        DownloadSaver.SUBDIR
    )

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        publishCount = 0
        // One PUBLISHING hit per completed DownloadSaver export: counts saves, not file names
        // (an export may dedupe or pick a "(1)" name, so counting files alone is not enough).
        DownloadSaver.faultAt = { stage ->
            if (stage == DownloadSaver.Stage.PUBLISHING) publishCount++
        }
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        jpegDir.deleteRecursively()
        txtDir.deleteRecursively()
        for (dir in insertDirs()) dir.deleteRecursively()
    }

    @After
    fun tearDown() {
        DownloadSaver.resetForTests()
        jpegDir.deleteRecursively()
        txtDir.deleteRecursively()
        for (dir in insertDirs()) dir.deleteRecursively()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun savedFiles(): List<File> =
        jpegDir.listFiles { f -> f.isFile && !f.name.startsWith(".") }?.toList().orEmpty()

    private fun insertDirs(): List<File> =
        app.cacheDir.listFiles { f -> f.isDirectory && f.name.startsWith("insert_") }?.toList().orEmpty()

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val out = ByteArrayOutputStream()
        assertTrue("a test JPEG is produced", bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out))
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun grantAfterRecreate(scenario: ActivityScenario<ExportResultActivity>, requestCode: Int) {
        scenario.onActivity { restored ->
            restored.onRequestPermissionsResult(
                requestCode,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                intArrayOf(PackageManager.PERMISSION_GRANTED)
            )
        }
        idle()
    }

    private fun exportIntent(source: File) = ExportResultActivity.intent(
        app, source.path, null, "image/jpeg", "B2 receipt",
        100L, 100L, 10, 10, null, null
    )

    private fun ocrIntent(title: String): Intent {
        val textFile = File(app.cacheDir, "b2_ocr_${title.replace(' ', '_')}.txt")
        textFile.writeText(OCR_TEXT, Charsets.UTF_8)
        return Intent(app, OcrEditorActivity::class.java)
            .putExtra(OcrEditorActivity.EXTRA_TEXT_PATH, textFile.path)
            .putExtra(OcrEditorActivity.EXTRA_TITLE, title)
    }

    private fun grantOcr(scenario: ActivityScenario<OcrEditorActivity>, requestCode: Int) {
        scenario.onActivity { editor ->
            editor.onRequestPermissionsResult(
                requestCode,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                intArrayOf(PackageManager.PERMISSION_GRANTED)
            )
        }
        idle()
    }

    private fun ocrRequestCode(scenario: ActivityScenario<OcrEditorActivity>): Int {
        var requestCode = -1
        scenario.onActivity { requestCode = shadowOf(it).lastRequestedPermission.requestCode }
        assertTrue("the OCR save asked for storage permission", requestCode > 0)
        return requestCode
    }

    private fun assertOcrSaved(scenario: ActivityScenario<OcrEditorActivity>, fileName: String) {
        val file = File(txtDir, fileName)
        assertTrue(
            "B2: the OCR .txt must exist after the grant",
            waitUntil { file.isFile && publishCount == 1 }
        )
        assertEquals(OCR_TEXT, file.readText(Charsets.UTF_8))
        scenario.onActivity { assertEquals("the write ran exactly once", 1, it.textWriteStartsForTest) }
    }

    // ---- F1 / NEW-1: the answer arrives on a different instance ----------------

    @Test(timeout = 60_000)
    @Config(sdk = [28])
    fun `a storage grant after rotation still saves the tool result exactly once`() {
        val source = File(app.cacheDir, "b2_export_source.jpg").apply { writeBytes(jpeg()) }
        ActivityScenario.launch<ExportResultActivity>(exportIntent(source)).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.btnSaveResult).performClick() }
            idle()
            var requestCode = -1
            scenario.onActivity { requestCode = shadowOf(it).lastRequestedPermission.requestCode }
            assertTrue("the save asked for storage permission", requestCode > 0)
            assertTrue("nothing is written before the answer", publishCount == 0)

            scenario.recreate()
            idle()
            grantAfterRecreate(scenario, requestCode)

            assertTrue(
                "B2: the answer after rotation must still publish the copy",
                waitUntil { publishCount == 1 && savedFiles().size == 1 }
            )
            idle()
            assertEquals("one save, not two", 1, publishCount)
            assertEquals("one saved file", 1, savedFiles().size)
        }
    }

    @Test(timeout = 60_000)
    @Config(sdk = [28])
    fun `the same grant works without rotation`() {
        val source = File(app.cacheDir, "b2_export_no_rotation.jpg").apply { writeBytes(jpeg()) }
        ActivityScenario.launch<ExportResultActivity>(exportIntent(source)).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.btnSaveResult).performClick() }
            idle()
            var requestCode = -1
            scenario.onActivity { requestCode = shadowOf(it).lastRequestedPermission.requestCode }
            assertTrue("the save asked for storage permission", requestCode > 0)
            grantAfterRecreate(scenario, requestCode)
            assertTrue("the normal path still saves", waitUntil { publishCount == 1 })
            assertEquals("and saves once", 1, publishCount)
        }
    }

    @Test(timeout = 60_000)
    @Config(sdk = [28])
    fun `a replayed grant does not save twice`() {
        val source = File(app.cacheDir, "b2_export_replayed.jpg").apply { writeBytes(jpeg()) }
        ActivityScenario.launch<ExportResultActivity>(exportIntent(source)).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.btnSaveResult).performClick() }
            idle()
            var requestCode = -1
            scenario.onActivity { requestCode = shadowOf(it).lastRequestedPermission.requestCode }
            scenario.recreate()
            idle()
            grantAfterRecreate(scenario, requestCode)
            assertTrue("the first delivery saves", waitUntil { publishCount == 1 })
            // A duplicate callback (e.g. a provider replay) must be a no-op.
            grantAfterRecreate(scenario, requestCode)
            idle()
            assertEquals("no double save", 1, publishCount)
            assertEquals("no duplicate file", 1, savedFiles().size)
        }
    }

    // ---- F2: the camera insert's plaintext copies ------------------------------

    @Test(timeout = 60_000)
    @Config(sdk = [35])
    fun `cancelling the camera insert position deletes its temporary pages`() {
        val repo = ScanRepository(app)
        val id = DocFixtures.saved(repo, app, pages = 1, name = "B2 insert")
        val pageUri = Uri.parse("content://b2/camera-page.jpg")
        val bytes = jpeg()
        shadowOf(app.contentResolver).registerInputStreamSupplier(pageUri) { ByteArrayInputStream(bytes) }

        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            assertTrue("the document loads", waitUntil {
                var count = 0
                scenario.onActivity {
                    count = it.findViewById<RecyclerView>(R.id.pageList).adapter?.itemCount ?: 0
                }
                count > 0
            })
            scenario.onActivity { it.onInsertScanUris(listOf(pageUri)) }
            assertTrue("the position is asked with a copied page", waitUntil {
                insertDirs().any { it.listFiles()?.isNotEmpty() == true } &&
                    ShadowDialog.getLatestDialog()?.findViewById<TextView>(R.id.swSheetTitle)?.text ==
                        app.getString(R.string.insert_position_title)
            })
            val dirs = insertDirs()
            assertEquals("one camera batch", 1, dirs.size)
            val dir = dirs.single()
            assertTrue("the copy exists before the answer", dir.exists())

            val sheet = requireNotNull(ShadowDialog.getLatestDialog()) { "the position sheet is open" }
            sheet.findViewById<View>(R.id.swSheetSecondary).performClick()
            idle()
            assertTrue("F2: cancelling deletes the plaintext copies at once", waitUntil { !dir.exists() })
        }
    }

    @Test(timeout = 60_000)
    @Config(sdk = [35])
    fun `a failed camera insert copy deletes its temporary folder too`() {
        val repo = ScanRepository(app)
        val id = DocFixtures.saved(repo, app, pages = 1, name = "B2 failed insert")
        // No input-stream supplier: the copy throws after the folder was created.
        val missing = Uri.parse("content://b2/not-there.jpg")
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            assertTrue("the document loads", waitUntil {
                var count = 0
                scenario.onActivity {
                    count = it.findViewById<RecyclerView>(R.id.pageList).adapter?.itemCount ?: 0
                }
                count > 0
            })
            scenario.onActivity { it.onInsertScanUris(listOf(missing)) }
            assertTrue("the failed batch leaves nothing behind", waitUntil { insertDirs().isEmpty() })
        }
    }

    // ---- NEW-1: OCR text exports ------------------------------------------------

    @Test(timeout = 60_000)
    @Config(sdk = [28])
    fun `ocr save as txt survives rotation and writes exactly once`() {
        ActivityScenario.launch<OcrEditorActivity>(ocrIntent("B2 save txt")).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.btnSave).performClick() }
            idle()
            val requestCode = ocrRequestCode(scenario)
            assertTrue("nothing is written before the answer", publishCount == 0)

            scenario.recreate()
            idle()
            grantOcr(scenario, requestCode)

            assertOcrSaved(scenario, "B2 save txt.txt")
        }
    }

    @Test(timeout = 60_000)
    @Config(sdk = [28])
    fun `ocr save all as txt survives rotation and writes exactly once`() {
        ActivityScenario.launch<OcrEditorActivity>(ocrIntent("B2 save all")).use { scenario ->
            scenario.onActivity { it.saveAllAsTxtForTest() }
            idle()
            val requestCode = ocrRequestCode(scenario)

            scenario.recreate()
            idle()
            grantOcr(scenario, requestCode)

            assertOcrSaved(scenario, "B2 save all.txt")
        }
    }

    @Test(timeout = 60_000)
    @Config(sdk = [28])
    fun `ocr save as txt works without rotation`() {
        ActivityScenario.launch<OcrEditorActivity>(ocrIntent("B2 no rotation")).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.btnSave).performClick() }
            idle()
            val requestCode = ocrRequestCode(scenario)
            grantOcr(scenario, requestCode)

            assertOcrSaved(scenario, "B2 no rotation.txt")
        }
    }
}
