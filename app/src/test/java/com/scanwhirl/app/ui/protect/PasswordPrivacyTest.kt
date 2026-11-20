package com.scanwhirl.app.ui.protect

import android.app.Dialog
import android.util.SparseArray
import android.os.Parcelable
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.PasswordField
import com.scanwhirl.app.util.PdfDecryptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * S3-PASSWORD-FIELDS: a typed PDF/Vault password is a secret the user did not ask to keep.
 *
 *  - Every password field opts out of view saved-state (so a rotation/process-death Bundle never
 *    carries the password) and of autofill (so no service is offered the secret), and the sheet
 *    clears it when dismissed.
 *  - An empty password is handed to the verifier instead of being rejected up front: owner-only
 *    PDFs (empty user password, owner password set) open, while a file with a real user password
 *    still refuses blank with the ordinary tries-left message. The decryptor, not the sheet,
 *    decides — [PdfDecryptor] verifies the empty string like any other password.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PasswordPrivacyTest {

    private var controller: ActivityController<ProtectHostActivity>? = null

    private fun host(): AppCompatActivity {
        val c = Robolectric.buildActivity(ProtectHostActivity::class.java).setup()
        controller = c
        return c.get()
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        ShadowDialog.reset()
    }

    private fun <T : View> Dialog.view(id: Int): T {
        val found = findViewById<View>(id) ?: throw AssertionError("the sheet has no view $id")
        @Suppress("UNCHECKED_CAST")
        return found as T
    }

    private fun idle() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/pdf_encrypted/$name")!!.use { it.readBytes() }

    /** The saved-state container a restored view would read; empty means nothing was retained. */
    private fun savedState(field: EditText): SparseArray<Parcelable> {
        val container = SparseArray<Parcelable>()
        field.saveHierarchyState(container)
        return container
    }

    private fun assertSecretFieldLocked(field: EditText) {
        assertFalse("the password must not be written to saved state", field.isSaveEnabled)
        // EditText.getFreezesText() always reports true (editables are preserved by design), so
        // the opt-out is proven by the empty saved state below, not by that getter.
        assertEquals(
            "autofill must never be offered the password",
            View.IMPORTANT_FOR_AUTOFILL_NO,
            field.importantForAutofill
        )
        // The framework appends its own "passwordAuto" marker to every password input type, so
        // "no hints" means no explicit credential hint is advertised alongside it.
        val hints = field.autofillHints?.filter { it != "passwordAuto" }.orEmpty()
        assertTrue(
            "no explicit autofill hint may advertise a password (was ${field.autofillHints?.joinToString()})",
            hints.isEmpty()
        )
    }

    @Test
    fun `a kit password field keeps the typed password out of saved state and autofill`() {
        val activity = host()
        val field = PasswordField(activity)
        field.editText.id = R.id.unlockPassword
        assertSecretFieldLocked(field.editText)

        field.editText.setText("correct horse battery")
        val state = savedState(field.editText)
        assertEquals("nothing of the password may be saved", 0, state.size())

        val restored = EditText(activity).apply { id = R.id.unlockPassword }
        restored.onRestoreInstanceState(state.get(R.id.unlockPassword))
        assertEquals("a restored field starts empty", "", restored.text.toString())

        // Accessibility is unchanged: the field and its eye still describe themselves.
        assertTrue(field.editText.contentDescription == null)
        assertTrue(field.editText.hint.isNotEmpty())
        assertEquals(
            activity.getString(R.string.pw_show),
            field.toggleButton.contentDescription.toString()
        )
    }

    @Test
    fun `the import unlock sheet never saves, autofills or keeps the password after dismissal`() {
        val activity = host()
        var submitted: String? = null
        ImportUnlockSheet.show(activity, documentName = "Book.pdf") { password, done ->
            submitted = password
            done(ImportUnlockSheet.Result.WRONG)
        }
        val dialog = ShadowDialog.getLatestDialog()!!
        val field = dialog.view<EditText>(R.id.unlockPassword)
        assertSecretFieldLocked(field)

        field.setText("import-secret")
        assertEquals("nothing of the password may be saved", 0, savedState(field).size())

        dialog.dismiss()
        idle()
        assertEquals("a dismissed sheet keeps no password in its field", "", field.text.toString())
        assertEquals("dismissal is not a password attempt", null, submitted)
    }

    @Test
    fun `the unlock sheet never saves, autofills or keeps the password after dismissal`() {
        val activity = host()
        val dialog = UnlockSheet.show(
            activity = activity,
            documentName = "Rent agreement",
            verify = { it == "1234" },
            cooldown = TryCooldown(clock = { 1_000L }),
            onUnlocked = {}
        )
        val field = dialog.view<EditText>(R.id.unlockPassword)
        assertSecretFieldLocked(field)

        field.setText("vault-secret")
        assertEquals("nothing of the password may be saved", 0, savedState(field).size())

        dialog.dismiss()
        idle()
        assertEquals("a dismissed sheet keeps no password in its field", "", field.text.toString())
    }

    @Test
    fun `blank opens an owner-only PDF through the import sheet`() {
        val activity = host()
        val ownerOnly = fixture("aes256_owner_only.pdf")
        val submitted = mutableListOf<String>()
        ImportUnlockSheet.show(activity, documentName = "Owner only.pdf") { password, done ->
            submitted.add(password)
            val ok = runCatching { PdfDecryptor.decrypt(ownerOnly, password) }.isSuccess
            done(if (ok) ImportUnlockSheet.Result.OK else ImportUnlockSheet.Result.WRONG)
        }
        val dialog = ShadowDialog.getLatestDialog()!!
        val field = dialog.view<EditText>(R.id.unlockPassword)
        assertEquals("the field starts empty", "", field.text.toString())

        dialog.findViewById<View>(R.id.swSheetPrimary).performClick()

        assertEquals("the empty password is verified, not refused", listOf(""), submitted)
        assertFalse("an owner-only PDF closes the sheet", dialog.isShowing)
    }

    @Test
    fun `blank is refused for a PDF with a real user password`() {
        val activity = host()
        val userPassword = fixture("aes128_invoice_legal.pdf")
        val submitted = mutableListOf<String>()
        ImportUnlockSheet.show(activity, documentName = "Invoice.pdf") { password, done ->
            submitted.add(password)
            val ok = runCatching { PdfDecryptor.decrypt(userPassword, password) }.isSuccess
            done(if (ok) ImportUnlockSheet.Result.OK else ImportUnlockSheet.Result.WRONG)
        }
        val dialog = ShadowDialog.getLatestDialog()!!
        val field = dialog.view<EditText>(R.id.unlockPassword)

        dialog.findViewById<View>(R.id.swSheetPrimary).performClick()

        assertEquals("the empty password reached the verifier", listOf(""), submitted)
        assertTrue("the sheet stays open", dialog.isShowing)
        val error = dialog.view<TextView>(R.id.swPasswordError)
        assertEquals(View.VISIBLE, error.visibility)
        assertTrue(
            "the ordinary wrong-password line is shown",
            error.text.contains("tries left")
        )
    }

    @Test
    fun `blank opens an owner-only document in the unlock sheet`() {
        val activity = host()
        val unlocked = mutableListOf<String>()
        var verifyCalls = 0
        val dialog = UnlockSheet.show(
            activity = activity,
            documentName = "Owner only.pdf",
            verify = { password ->
                verifyCalls++
                runCatching { PdfDecryptor.decrypt(fixture("aes256_owner_only.pdf"), password) }
                    .isSuccess
            },
            cooldown = TryCooldown(clock = { 1_000L }),
            onUnlocked = { unlocked.add(it) }
        )
        assertEquals("", dialog.view<EditText>(R.id.unlockPassword).text.toString())
        dialog.findViewById<MaterialButton>(R.id.unlockOpen)!!.performClick()

        assertEquals("the verifier decided, not the sheet", 1, verifyCalls)
        assertEquals("the empty password is handed over", listOf(""), unlocked)
        assertFalse("the sheet closes", dialog.isShowing)
    }

    @Test
    fun `blank still counts as a wrong try for a document with a user password`() {
        val activity = host()
        val unlocked = mutableListOf<String>()
        val dialog = UnlockSheet.show(
            activity = activity,
            documentName = "Invoice.pdf",
            verify = { password ->
                runCatching { PdfDecryptor.decrypt(fixture("aes128_invoice_legal.pdf"), password) }
                    .isSuccess
            },
            cooldown = TryCooldown(clock = { 1_000L }),
            onUnlocked = { unlocked.add(it) }
        )
        dialog.findViewById<MaterialButton>(R.id.unlockOpen)!!.performClick()

        assertTrue("nothing opened with a blank password", unlocked.isEmpty())
        val error = dialog.view<TextView>(R.id.unlockError)
        assertEquals(View.VISIBLE, error.visibility)
        assertTrue("the tries-left line is shown", error.text.contains("tries left"))
        assertNotNull("the sheet stays open", ShadowDialog.getLatestDialog())
    }

    @Test
    fun `the fixtures still separate an owner-only file from a user password`() {
        // The decryptor verifies "" like any password; the sheet must not pre-empt that decision.
        val ownerOnly = fixture("aes256_owner_only.pdf")
        assertTrue(
            "an owner-only file opens with its empty user password",
            runCatching { PdfDecryptor.decrypt(ownerOnly, "") }.isSuccess
        )
        val userPassword = fixture("aes128_invoice_legal.pdf")
        assertTrue(
            "a file with a user password refuses blank",
            runCatching { PdfDecryptor.decrypt(userPassword, "") }
                .exceptionOrNull() is PdfDecryptor.WrongPassword
        )
        assertTrue(
            "and still opens with its own password",
            runCatching { PdfDecryptor.decrypt(userPassword, "legal-2026") }.isSuccess
        )
    }
}
