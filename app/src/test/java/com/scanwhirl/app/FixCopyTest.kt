package com.scanwhirl.app
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule
import org.junit.rules.Timeout

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultPasscodeGate
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.ui.vault.VaultActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * UI critique 2026-11-18, S1 + C (lane fixcopy).
 *
 * S1: the Vault recovery code must never reach the clipboard (a phone keyboard keeps its own
 * clipboard history and ignores the sensitive flag), so the recovery screen offers
 * "Save as file" through ACTION_CREATE_DOCUMENT instead of "Copy code".
 *
 * C: the compression levels read as file size (Low used to make the LARGEST file) and four
 * settings/trash summaries say what they do.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FixCopyTest {



    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var store: VaultStore
    private var now = 0L
    private var controller: ActivityController<VaultActivity>? = null

    private val recoveryFile = "content://com.scanwhirl.app.test/recovery.txt"

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        store = VaultStore(
            context = app,
            repo = repo,
            keys = FakeVaultKeys(),
            gate = VaultPasscodeGate(clock = { now }),
            iterations = 2_000
        )
        VaultActivity.storeOverride = store
        VaultActivity.biometricStatusOverride =
            androidx.biometric.BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
        VaultActivity.fingerprintStarter = null
        File(app.filesDir, "vault2").deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        controller?.close()
        controller = null
        VaultActivity.storeOverride = null
        VaultActivity.biometricStatusOverride = null
        VaultActivity.fingerprintStarter = null
        VaultSession.close()
        File(app.filesDir, "vault2").deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            idle()
            if (condition()) return true
            if (System.nanoTime() >= deadline) {
                idle()
                return condition()
            }
            Thread.sleep(10)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> View.need(id: Int): T = findViewById<View>(id) as T

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> BottomSheetDialog.need(id: Int): T = findViewById<View>(id) as T

    /** Drives the real setup sheet until the one-time recovery code is on screen. */
    private fun reachRecoveryCode(activity: VaultActivity): BottomSheetDialog {        idle()
        val setupSheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
        setupSheet.need<EditText>(R.id.vaultPasscodeInput).setText("246810")
        setupSheet.need<EditText>(R.id.vaultPasscodeConfirm).setText("246810")
        setupSheet.need<View>(R.id.swSheetPrimary).performClick()
        assertTrue("setup finishes on a background thread", await { !setupSheet.isShowing })
        idle()
        val recovery = ShadowDialog.getLatestDialog() as BottomSheetDialog
        assertEquals(
            "Save this recovery code",
            recovery.need<TextView>(R.id.swSheetTitle).text.toString()
        )
        return recovery
    }

    private fun sheet(): BottomSheetDialog {
        idle()
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("a sheet should be showing", dialog)
        return dialog as BottomSheetDialog
    }

    /** The message of the top-most sheet, or null when no sheet carries one. */
    private fun latestSheetMessage(): String? {
        val dialog = ShadowDialog.getLatestDialog() as? BottomSheetDialog ?: return null
        val view = dialog.findViewById<View>(R.id.swSheetMessage) as? TextView ?: return null
        return view.text?.toString()
    }

    /** Taps "Save as file" and returns the picker's request code with its intent. */
    private fun tapSave(activity: VaultActivity): Pair<Int, Intent> {
        sheet().need<TextView>(R.id.vaultRecoveryCopy).performClick()
        idle()
        val started = shadowOf(activity).peekNextStartedActivityForResult()
        assertNotNull("the system picker starts", started)
        return started!!.requestCode to started.intent
    }

    @Test(timeout = 60_000)
    fun `the recovery screen offers Save as file and never puts the code on the clipboard`() {
        controller = Robolectric.buildActivity(VaultActivity::class.java).setup()
        val activity = controller!!.get()
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("before", "SENTINEL"))

        val recovery = reachRecoveryCode(activity)
        val code = recovery.need<TextView>(R.id.vaultRecoveryCode).text.toString()
        assertTrue("the code is shown", code.isNotBlank())

        val action = recovery.need<TextView>(R.id.vaultRecoveryCopy)
        assertEquals("Save as file", action.text.toString())
        assertFalse(
            "the copy action must be gone for good",
            action.text.toString().contains("copy", ignoreCase = true)
        )
        // The warning stays on the sheet.
        assertTrue(
            "the offline warning stays",
            activity.getString(R.string.vault_recovery_copy_warning).contains("Keep it offline")
        )
        assertTrue(
            "the recovery sheet has its own window, so it needs FLAG_SECURE too",
            com.scanwhirl.app.util.SecureView.isSecure(recovery.window!!)
        )

        action.performClick()
        idle()

        assertEquals(
            "the recovery code never reaches the clipboard",
            "SENTINEL",
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString()
        )

        val started = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("Save as file opens the system document picker", started)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.action)
        assertEquals("text/plain", started.intent.type)
        assertEquals(
            "ScanWhirl Vault recovery code.txt",
            started.intent.getStringExtra(Intent.EXTRA_TITLE)
        )
    }

    @Test(timeout = 60_000)
    fun `saving the recovery code writes the code and one instruction line`() {
        controller = Robolectric.buildActivity(VaultActivity::class.java).setup()
        val activity = controller!!.get()
        val recovery = reachRecoveryCode(activity)
        val code = recovery.need<TextView>(R.id.vaultRecoveryCode).text.toString()

        recovery.need<TextView>(R.id.vaultRecoveryCopy).performClick()
        idle()
        val started = shadowOf(activity).nextStartedActivityForResult
        assertNotNull(started)

        val out = ByteArrayOutputStream()
        val uri = Uri.parse(recoveryFile)
        shadowOf(app.contentResolver).registerOutputStream(uri, out)
        shadowOf(activity).receiveResult(
            started.intent,
            Activity.RESULT_OK,
            Intent().setData(uri)
        )
        assertTrue("the chosen file is written", await { out.size() > 0 })

        val text = out.toString("UTF-8")
        val instruction = app.getString(R.string.vault_recovery_copy_warning)
        assertEquals("$code\n$instruction\n", text)
        assertEquals("only the code and one line of instruction", 2, text.trimEnd('\n').lines().size)
        assertFalse("no long dashes in the saved file", text.contains("\u2014") || text.contains("\u2013"))
    }

    @Test(timeout = 60_000)
    fun `the save control is a 48 dp focusable target like the other Vault rows`() {
        controller = Robolectric.buildActivity(VaultActivity::class.java).setup()
        val activity = controller!!.get()
        val recovery = reachRecoveryCode(activity)

        val action = recovery.need<TextView>(R.id.vaultRecoveryCopy)
        val density = activity.resources.displayMetrics.density
        assertTrue(
            "the save control needs a 48 dp touch target, was ${action.minimumHeight}px",
            action.minimumHeight >= (48 * density).toInt()
        )
        assertTrue("the save control must be focusable", action.isFocusable)
        assertTrue("the save control must be clickable", action.isClickable)
    }

    @Test(timeout = 60_000)
    fun `a rotation while the picker is open still writes the code`() {
        controller = Robolectric.buildActivity(VaultActivity::class.java).setup()
        val activity = controller!!.get()
        val recovery = reachRecoveryCode(activity)
        val code = recovery.need<TextView>(R.id.vaultRecoveryCode).text.toString()
        val request = tapSave(activity)

        // Rotation: the activity is recreated, the system picker stays open.
        controller!!.recreate()
        idle()
        val recreated = controller!!.get()

        val out = ByteArrayOutputStream()
        val uri = Uri.parse(recoveryFile)
        shadowOf(app.contentResolver).registerOutputStream(uri, out)
        shadowOf(recreated).callOnActivityResult(
            request.first,
            Activity.RESULT_OK,
            Intent().setData(uri)
        )
        assertTrue("the code survives the rotation and is written", await { out.size() > 0 })
        assertEquals(
            "$code\n${app.getString(R.string.vault_recovery_copy_warning)}\n",
            out.toString("UTF-8")
        )
    }

    @Test(timeout = 60_000)
    fun `process death while the picker is open deletes the empty file and says so`() {
        controller = Robolectric.buildActivity(VaultActivity::class.java).setup()
        val activity = controller!!.get()
        reachRecoveryCode(activity)
        val request = tapSave(activity)

        // Process death: a fresh activity from the saved state, with no in-memory holder.
        val state = Bundle()
        controller!!.saveInstanceState(state).pause().stop().destroy()
        controller = Robolectric.buildActivity(VaultActivity::class.java).create(state).start().resume()
        val fresh = controller!!.get()
        idle()

        val provider = Robolectric.setupContentProvider(
            RecordingDocumentsProvider::class.java,
            "com.scanwhirl.app.test"
        ) as RecordingDocumentsProvider
        val out = ByteArrayOutputStream()
        val uri = Uri.parse(recoveryFile)
        shadowOf(app.contentResolver).registerOutputStream(uri, out)

        shadowOf(fresh).callOnActivityResult(
            request.first,
            Activity.RESULT_OK,
            Intent().setData(uri)
        )

        val deleted = await {
            provider.calls.any { it.first == "android:deleteDocument" && it.second == uri }
        }
        assertTrue(
            "the empty document the picker created is deleted: ${provider.calls}",
            deleted
        )
        assertEquals("nothing is written without the code", 0, out.size())
        assertTrue(
            "the user is told the code was not saved, was: ${latestSheetMessage()}",
            await {
                latestSheetMessage() == app.getString(R.string.vault_recovery_code_lost)
            }
        )
    }

    @Test(timeout = 60_000)
    fun `cancelling the picker writes nothing and says nothing`() {
        controller = Robolectric.buildActivity(VaultActivity::class.java).setup()
        val activity = controller!!.get()
        val recovery = reachRecoveryCode(activity)
        val request = tapSave(activity)

        val out = ByteArrayOutputStream()
        shadowOf(app.contentResolver).registerOutputStream(Uri.parse(recoveryFile), out)

        shadowOf(activity).callOnActivityResult(request.first, Activity.RESULT_CANCELED, null)
        idle()

        assertEquals("a cancelled picker writes nothing", 0, out.size())
        val latest = ShadowDialog.getLatestDialog() as BottomSheetDialog
        assertEquals(
            "no error sheet for a cancel",
            "Save this recovery code",
            latest.need<TextView>(R.id.swSheetTitle).text.toString()
        )
        assertTrue("the recovery sheet is still there", recovery.isShowing)
    }

    @Test(timeout = 60_000)
    fun `a write failure shows a clear message and does not crash`() {
        controller = Robolectric.buildActivity(VaultActivity::class.java).setup()
        val activity = controller!!.get()
        reachRecoveryCode(activity)
        val request = tapSave(activity)

        // The stream itself fails (a full disk, a revoked provider): the write must fail cleanly.
        val target = Uri.parse("content://nothing/written.txt")
        shadowOf(app.contentResolver).registerOutputStream(
            target,
            object : java.io.OutputStream() {
                override fun write(b: Int) {
                    throw java.io.IOException("no space left")
                }
            }
        )
        shadowOf(activity).callOnActivityResult(
            request.first,
            Activity.RESULT_OK,
            Intent().setData(target)
        )

        val expected = app.getString(R.string.vault_recovery_file_failed)
        assertTrue(
            "the failure message comes from the string resource, was: ${latestSheetMessage()}",
            await { latestSheetMessage() == expected }
        )
        assertFalse("no raw exception text", expected.contains("Exception") || expected.contains(":"))
        assertFalse("the activity is still there", activity.isFinishing)
    }

    @Test(timeout = 60_000)
    fun `compression levels read best quality first and each level is smaller`() {
        val source = File("src/main/java/com/scanwhirl/app/tools/CompressFlow.kt").readText(Charsets.UTF_8)
        val levels = Regex(
            "Level\\(R\\.string\\.compress_level_(low|medium|high|ultra), (\\d+), (null|\\d+), ([\\d.]+)f\\)"
        ).findAll(source).map { it.groupValues }.toList()
        assertEquals("four levels stay in file-size order", 4, levels.size)
        assertEquals(listOf("low", "medium", "high", "ultra"), levels.map { it[1] })
        val quality = levels.map { it[2].toInt() }
        assertTrue("quality drops as the file gets smaller: $quality", quality.zipWithNext().all { it.first > it.second })
        // CP1: Best quality is capped at 3000 px, the same ceiling import uses, so a device PDF
        // cannot OOM the phone while the other levels stay smaller.
        assertEquals("Best quality is capped at 3000 px", "3000", levels[0][3])

        val en = englishStrings()
        assertEquals("Best quality", en["compress_level_low"])
        assertEquals("Balanced", en["compress_level_medium"])
        assertEquals("Smaller", en["compress_level_high"])
        assertEquals("Smallest", en["compress_level_ultra"])
    }

    @Test(timeout = 60_000)
    fun `the eight critique wordings are plain in English and Hindi`() {
        val expected = mapOf(
            "compress_level_low" to "Best quality",
            "compress_level_medium" to "Balanced",
            "compress_level_high" to "Smaller",
            "compress_level_ultra" to "Smallest",
            // SH2 COPY_DIET: OLD "Custom size (optional)" -> NEW "Maximum file size".
            "compress_target_label" to "Maximum file size",
            "settings_auto_enhance_summary" to
                "Cleans up scans. Off keeps the photo as taken.",
            "settings_searchable_pdf_summary" to
                "Adds invisible text so other apps can search your PDFs.",
            "trash_header_note" to "Restore brings back every page."
        )
        val en = englishStrings()
        val hi = hindiStrings()
        for ((name, wording) in expected) {
            assertEquals(name, wording, en[name])
            val hindi = hi[name]
            assertNotNull("$name needs a Hindi string", hindi)
            // v2 wording: JUDGE_HINDI.md final glossary — these wordings are reviewed now,
            // so the "awaiting native review" marker must be gone.
            assertFalse(
                "$name in Hindi is reviewed and must not carry a review marker",
                hindi!!.startsWith("<!-- review -->")
            )
            for ((lang, text) in listOf("en" to wording, "hi" to hindi)) {
                assertFalse(
                    "$name ($lang) must not use a long dash",
                    text.contains("\u2014") || text.contains("\u2013")
                )
            }
        }
        assertFalse("the old vendor sentence is gone", en["settings_auto_enhance_summary"]!!.contains("Google"))
        assertFalse("the old hiding sentence is gone", en["settings_searchable_pdf_summary"]!!.contains("Hides"))
    }

    private fun englishStrings(): Map<String, String> = parse(File("src/main/res/values/strings.xml"))

    private fun hindiStrings(): Map<String, String> = parse(File("src/main/res/values-hi/strings.xml"))

    private fun parse(file: File): Map<String, String> {
        val xml = file.readText(Charsets.UTF_8)
        return Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    /** Records what `DocumentsContract.deleteDocument` asks the provider to do. */
    class RecordingDocumentsProvider : ContentProvider() {

    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
        val calls = mutableListOf<Pair<String, Uri?>>()

        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?
        ): Cursor? = null

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
            calls += "delete" to uri
            return 1
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<String>?
        ): Int = 0

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            val target = extras?.keySet()?.firstNotNullOfOrNull { key ->
                @Suppress("DEPRECATION")
                extras.get(key) as? Uri
            }
            calls += method to target
            return Bundle().apply { putBoolean("result", true) }
        }
    }
}
