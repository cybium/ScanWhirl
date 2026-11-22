package com.scanwhirl.app.tools

import android.net.Uri
import android.view.WindowManager
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.ui.protect.LockToolsActivity
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.PdfAes
import com.scanwhirl.app.util.PdfGenerator
import com.scanwhirl.app.util.PdfVerifier
import com.scanwhirl.app.util.PrivacySweep
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExternalPdfUnlockRecoveryTest {
    private val app get() = ApplicationProvider.getApplicationContext<ScanWhirlApp>()

    @Test(timeout = 30_000) fun `external password sheet is secure and destruction clears secret`() {
        val controller = Robolectric.buildActivity(LockToolsActivity::class.java,
            LockToolsActivity.intent(app, LockToolsActivity.MODE_UNLOCK)).setup()
        var destroyed = false
        try {
            val activity = controller.get()
            LockFlow(activity, app.repository, activity, { emptyList() }, {}).unlockDevicePdfFile(
                Uri.fromFile(File(app.cacheDir, "fictional-external.pdf"))
            ) {}
            val sheet = ShadowDialog.getLatestDialog()
            val field = sheet.findViewById<EditText>(R.id.unlockPassword)
            assertNotNull(field)
            assertTrue(sheet.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            field.setText("fictional-known")
            controller.pause().stop().destroy()
            destroyed = true
            assertEquals("", field.text.toString())
        } finally {
            if (!destroyed) controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000) fun `protect unlock own file round trip and process kill recovery`() {
        val image = File(app.cacheDir, "ffu-fictional.jpg").apply {
            writeBytes(ImportFixtures.image(width = 120, height = 180))
        }
        val source = File(app.cacheDir, "ffu-protected.pdf")
        try {
            PdfGenerator.imagesToPdf(listOf(image, image, image), source,
                encryption = PdfAes.create("fictional-known"))
            val hash = com.scanwhirl.app.util.Sha256.of(source)
            val output = source.inputStream().use {
                ExternalPdfUnlock(app.cacheDir).unlock(it, source.length(), "fictional-known", { file ->
                    PdfVerifier.verify(file, expectedImages = listOf(image, image, image))
                    3
                })
            }
            assertEquals(hash, com.scanwhirl.app.util.Sha256.of(source))
            assertTrue(output.file.exists())
            // A kill can leave either the fully written staging file or returned output.
            val partial = File(app.cacheDir, "decrypted-ffu-killed.part").apply {
                writeBytes(output.file.readBytes())
            }
            PrivacySweep.run(app)
            assertFalse(partial.exists())
            assertFalse(output.file.exists())
            assertTrue(source.exists())
        } finally {
            image.delete()
            source.delete()
        }
    }
}
