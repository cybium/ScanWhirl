package com.scanwhirl.app.ui.protect
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * X1 P2: ImportUnlockSheet cleared the password only in the caller's onDismiss, which the kit
 * suppresses on Activity destruction. A rotation during an in-flight import left the old field
 * (still held by LockFlow's app-scoped import callback) with the password.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportUnlockSheetTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `a rotation while an import is in flight wipes the password field`() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.Theme_ScanWhirl)
        controller.setup()
        val activity = controller.get()

        var capturedDone: ((ImportUnlockSheet.Result) -> Unit)? = null
        ImportUnlockSheet.show(activity, "Encrypted.pdf") { _password, done ->
            capturedDone = done
            // The decrypt is deliberately still in flight when the Activity is destroyed.
        }
        idle()

        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        val field = requireNotNull(dialog.findViewById<EditText>(R.id.unlockPassword)) { "unlock field missing" }
        field.setText("secret123")
        assertEquals("the password is typed before rotation", "secret123", field.text.toString())

        // Trigger the submit so the app-scoped callback holds a reference to this field and its done().
        dialog.findViewById<View>(R.id.swSheetPrimary)?.performClick()
        idle()
        assertEquals("the submit is in flight", true, capturedDone != null)

        // Rotation: the kit dismisses the sheet silently, so no caller onDismiss callback runs.
        controller.pause().stop().destroy()
        idle()
        assertEquals("the password must not survive its owner", "", field.text.toString())

        // The app-scoped import can still finish after the Activity is gone.
        capturedDone?.invoke(ImportUnlockSheet.Result.OK)
        idle()
        assertEquals("a late result cannot leave the secret behind", "", field.text.toString())
    }
}
