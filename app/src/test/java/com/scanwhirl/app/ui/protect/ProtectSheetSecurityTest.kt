package com.scanwhirl.app.ui.protect

import android.app.Dialog
import android.os.Parcelable
import android.util.SparseArray
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.scanwhirl.app.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * K1 defect 1: a Protect PDF password is a secret, so Android's default view state must never
 * carry it. Rotation or process death restores an empty sheet, and dismissing it leaves no text
 * behind in the view hierarchy.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProtectSheetSecurityTest {

    private var controller: ActivityController<ProtectHostActivity>? = null

    private fun host(): AppCompatActivity {
        val c = Robolectric.buildActivity(ProtectHostActivity::class.java).setup()
        controller = c
        return c.get()
    }

    private fun sheet(activity: AppCompatActivity): Dialog = ProtectSheet.show(
        activity = activity,
        doc = ProtectSheet.Doc("Rent agreement", 1, 12_000L)
    ) { _, _, _ -> }

    private fun <T : View> Dialog.view(id: Int): T {
        val found = findViewById<View>(id) ?: throw AssertionError("the sheet has no view $id")
        @Suppress("UNCHECKED_CAST")
        return found as T
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
    }

    @Test
    fun `password fields are excluded from saved state and never autofilled`() {
        val activity = host()
        val dialog = sheet(activity)
        val password = dialog.view<EditText>(R.id.protectPassword)
        val confirm = dialog.view<EditText>(R.id.protectConfirm)

        assertFalse("a password must not be saved in instance state", password.isSaveEnabled)
        assertFalse("a password must not be saved in instance state", confirm.isSaveEnabled)
        // freezesText is also opted out in code and in the layout; the proof that matters is the
        // saved state below, which is empty even if a platform shadow reports the getter as true.
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, password.importantForAutofill)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, confirm.importantForAutofill)

        password.setText("a-secret-password")
        confirm.setText("a-secret-password")
        val state = SparseArray<Parcelable>()
        dialog.view<View>(R.id.protectSheet).saveHierarchyState(state)
        assertNull("the password is not in the saved bundle", state.get(R.id.protectPassword))
        assertNull("the confirmation is not in the saved bundle", state.get(R.id.protectConfirm))

        // A recreated sheet restores that same state: both fields must still be empty.
        val recreated = sheet(activity)
        recreated.view<View>(R.id.protectSheet).restoreHierarchyState(state)
        assertEquals("", recreated.view<EditText>(R.id.protectPassword).text.toString())
        assertEquals("", recreated.view<EditText>(R.id.protectConfirm).text.toString())
        recreated.dismiss()
        dialog.dismiss()
    }

    @Test
    fun `dismissing the sheet clears both password fields`() {
        val activity = host()
        val dialog = sheet(activity)
        val password = dialog.view<EditText>(R.id.protectPassword)
        val confirm = dialog.view<EditText>(R.id.protectConfirm)
        password.setText("hunter22")
        confirm.setText("hunter22")

        dialog.dismiss()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals("the password leaves the view on dismiss", "", password.text.toString())
        assertEquals("the confirmation leaves the view on dismiss", "", confirm.text.toString())
    }

    @Test
    fun `the layout opts out of saving and autofill for both fields`() {
        val xml = File("src/main/res/layout/sheet_protect.xml").readText(Charsets.UTF_8)
        val fields = Regex("<EditText[\\s\\S]*?/>").findAll(xml).map { it.value }.toList()
        for (id in listOf("protectPassword", "protectConfirm")) {
            val field = fields.firstOrNull {
                it.contains("id=\"@+id/$id\"") || it.contains("id=\"@id/$id\"")
            } ?: throw AssertionError("no $id field in sheet_protect.xml")
            assertTrue("saveEnabled=false for $id", field.contains("saveEnabled=\"false\""))
            assertTrue("freezesText=false for $id", field.contains("freezesText=\"false\""))
            assertTrue("importantForAutofill=no for $id", field.contains("importantForAutofill=\"no\""))
        }
    }
}
