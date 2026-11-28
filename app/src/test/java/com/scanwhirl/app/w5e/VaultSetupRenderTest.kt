package com.scanwhirl.app.w5e

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.ui.vault.VaultActivity
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.FileOutputStream

/**
 * VQA V046: the emulator cannot capture the Vault setup sheet because SecureView sets
 * FLAG_SECURE on its window (and the activity). The flag is intentional and must NOT be
 * removed. This test renders the REAL sheet's view tree to PNG under Robolectric instead,
 * so the lead can see the layout in light, dark and 2.0 font. No passcode or recovery code
 * is ever entered or written: the setup sheet is captured empty.
 *
 * Artifacts: PNG files in app/build/test-results/testDebugUnitTest/w5e-renders/ (+ manifest.txt).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class VaultSetupRenderTest {

    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private val outDir = File("build/test-results/testDebugUnitTest/w5e-renders")
    private var controller: ActivityController<VaultActivity>? = null

    @Before
    fun setUp() {
        outDir.mkdirs()
        VaultSession.close()
    }

    @After
    fun tearDown() {
        controller?.close()
        controller = null
        VaultActivity.storeOverride = null
        VaultActivity.biometricStatusOverride = null
        VaultSession.close()
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun render(name: String, qualifiers: String, fontScale: Float): File {
        RuntimeEnvironment.setQualifiers(qualifiers)
        RuntimeEnvironment.setFontScale(fontScale)
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val store = VaultStore(
            context = app,
            repo = ScanRepository(app),
            keys = FakeVaultKeys(),
            iterations = 2_000
        )
        VaultActivity.storeOverride = store
        VaultActivity.biometricStatusOverride = BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
        VaultActivity.fingerprintStarter = null
        controller = Robolectric.buildActivity(VaultActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller!!.get()
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue("$name: the real setup sheet did not open", dialog?.isShowing == true)
        val sheet = dialog!!.findViewById<View>(R.id.swSheetRoot)
        assertTrue("$name: the sheet has no renderable root", sheet != null)

        val w = 1080
        val h = 2400
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(ContextCompat.getColor(activity, R.color.tok_bg))
        val host = activity.window.decorView
        host.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        host.layout(0, 0, w, h)
        host.draw(canvas)

        val margin = 24
        val sheetW = w - margin * 2
        sheet!!.measure(
            View.MeasureSpec.makeMeasureSpec(sheetW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST)
        )
        val sheetH = sheet.measuredHeight.coerceIn(1, h - 24)
        canvas.save()
        canvas.translate(margin.toFloat(), (h - sheetH).toFloat())
        canvas.clipRect(0, 0, sheetW, sheetH)
        sheet.layout(0, 0, sheetW, sheetH)
        sheet.draw(canvas)
        canvas.restore()

        val colors = HashSet<Int>()
        for (y in 0 until h step 16) for (x in 0 until w step 16) colors.add(bmp.getPixel(x, y))
        assertTrue("$name: the render is blank (only ${colors.size} colours)", colors.size > 4)

        val file = File(outDir, "$name.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file
    }

    @Test
    fun `vaultSetupSheetRendersInLightDarkAndTwoTimesFont`() {
        val files = mutableListOf<File>()
        files += render("vault-setup-light", "en-rUS-w360dp-h800dp-notnight-xhdpi", 1f)
        files += render("vault-setup-dark", "en-rUS-w360dp-h800dp-night-xhdpi", 1f)
        files += render("vault-setup-font200", "en-rUS-w360dp-h800dp-notnight-xhdpi", 2f)
        File(outDir, "manifest.txt").writeText(
            files.joinToString("\n") { "${it.name}  ${it.length()} bytes" } +
                "\nFLAG_SECURE stays on in production; these are Robolectric view renders.\n"
        )
        assertEqualsNames(files)
    }

    private fun assertEqualsNames(files: List<File>) {
        assertTrue(files.size == 3)
        assertTrue(files.all { it.exists() && it.length() > 0 })
    }
}
