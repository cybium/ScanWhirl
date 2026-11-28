package com.scanwhirl.app.ui.backup

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultPasscodeGate
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.FileOutputStream

/**
 * V047: the device atlas blanks Backup because the screen is FLAG_SECURE (by design).
 * FLAG_SECURE must stay; these Robolectric view-to-bitmap renders draw the same real
 * views to PNGs in light/dark at 1.0 and 2.0 font, screen plus every sheet, so the
 * lead can review the copy and layout without weakening the security flag.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class W5dBackupRenderTest {

    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()

    private lateinit var app: Application
    private lateinit var outDir: File
    private val names = mutableListOf<String>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        VaultSession.close()
        outDir = File(System.getProperty("user.dir") ?: ".", "app/build/w5d-renders").apply { mkdirs() }
        names.clear()
    }

    @After
    fun tearDown() {
        BackupActivity.vaultOverride = null
        VaultSession.close()
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-xxhdpi")
        File(outDir, "manifest.txt").writeText(names.joinToString("\n") + "\n")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun clean() {
        listOf("vault2", "documents", "drafts").forEach { File(app.filesDir, it).deleteRecursively() }
        File(app.cacheDir, "fullbackup").deleteRecursively()
    }

    private fun readyVault(): VaultStore {
        clean()
        return VaultStore(
            app, ScanRepository(app), FakeVaultKeys(), VaultPasscodeGate(), 2_000
        ).also {
            it.setup("246810")
            it.markConfirmationPassed()
        }
    }

    private fun exactly(px: Int) = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)
    private fun atMost(px: Int) = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.AT_MOST)

    private fun dispatchInsets(root: View) {
        ViewCompat.dispatchApplyWindowInsets(
            root,
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 108, 0, 72))
                .build()
        )
    }

    private fun render(activity: BackupActivity, name: String) {
        val w = 1080
        val h = 2400
        val host = activity.window.decorView
        repeat(2) {
            host.measure(exactly(w), exactly(h))
            host.layout(0, 0, w, h)
        }
        dispatchInsets(host)
        idle()

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF0A1016.toInt())
        host.draw(canvas)

        val dialog = ShadowDialog.getLatestDialog()
        if (dialog != null && dialog.isShowing) {
            canvas.drawColor(0x8F000000.toInt())
            val sheet = dialog.window!!.decorView
            val margin = 24
            sheet.measure(exactly(w - margin * 2), atMost(h))
            val sh = sheet.measuredHeight.coerceIn(1, h - 24)
            sheet.layout(0, 0, w - margin * 2, sh)
            canvas.save()
            canvas.translate(margin.toFloat(), (h - sh).toFloat())
            canvas.clipRect(0, 0, sheet.width, sheet.height)
            sheet.draw(canvas)
            canvas.restore()
        }

        val file = File(outDir, "$name.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        names += file.name
        // A FLAG_SECURE screen is black in the device atlas; it must not be black here.
        val colours = HashSet<Int>()
        for (y in 0 until h step 40) {
            for (x in 0 until w step 40) colours += bmp.getPixel(x, y)
        }
        assertTrue("$name rendered blank (FLAG_SECURE leaked into the Robolectric draw)", colours.size > 2)
    }

    /**
     * V047: one fresh instance per state so the newest ShadowDialog is always the one
     * being rendered. No sheet is submitted and no secret is ever typed.
     */
    private fun renderState(tag: String, dark: Boolean, scale: Float, action: (BackupActivity) -> Unit) {
        ShadowDialog.reset()
        RuntimeEnvironment.setQualifiers(
            "en-rUS-w360dp-h800dp-${if (dark) "night" else "notnight"}-xxhdpi"
        )
        RuntimeEnvironment.setFontScale(scale)
        BackupActivity.vaultOverride = readyVault()
        ActivityScenario.launch(BackupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                action(activity)
                idle()
                render(activity, "backup-$tag-${if (dark) "dark" else "light"}-${if (scale == 2f) "font200" else "font100"}")
            }
        }
    }

    @Test(timeout = 120_000)
    fun rendersBackupScreenAndEverySheetInLightDarkAndFont200() {
        val looks = listOf(false to 1f, false to 2f, true to 1f, true to 2f)
        for ((dark, scale) in looks) {
            renderState("screen", dark, scale) { }
            renderState("choice-sheet", dark, scale) {
                it.findViewById<View>(R.id.backupNow).performClick()
            }
            renderState("more-sheet", dark, scale) {
                it.findViewById<View>(R.id.backupNow).performClick()
                idle()
                ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.backupMore).performClick()
                idle()
                assertEquals("More opens the honest detail", it.getString(R.string.backup_explain),
                    ShadowDialog.getLatestDialog()!!.findViewById<TextView>(R.id.swSheetMessage).text.toString())
            }
            renderState("code-sheet", dark, scale) {
                it.findViewById<View>(R.id.backupNow).performClick()
                idle()
                ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.swSheetPrimary).performClick()
                idle()
                assertEquals("the primary choice is the recovery code", it.getString(R.string.backup_code_title),
                    ShadowDialog.getLatestDialog()!!.findViewById<TextView>(R.id.swSheetTitle).text.toString())
            }
            renderState("password-sheet", dark, scale) {
                it.findViewById<View>(R.id.backupNow).performClick()
                idle()
                ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.swSheetSecondary).performClick()
                idle()
                assertEquals("the secondary choice is a password", it.getString(R.string.backup_password_title),
                    ShadowDialog.getLatestDialog()!!.findViewById<TextView>(R.id.swSheetTitle).text.toString())
            }
        }
        assertEquals16Renders()
    }

    private fun assertEquals16Renders() {
        assertTrue("20 renders expected (screen + four sheets x four looks), got ${names.size}",
            names.size == 20)
        for (name in names) assertTrue("$name is present", File(outDir, "$name.png").length() > 0L)
    }
}
