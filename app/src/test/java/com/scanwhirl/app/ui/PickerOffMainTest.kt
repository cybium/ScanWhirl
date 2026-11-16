package com.scanwhirl.app.ui

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultPasscodeGate
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.ui.vault.VaultActivity
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * C08: the Vault "Add files" list and the viewer's "Insert pages → From document" chooser both
 * listed the whole library on the main thread from a click.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PickerOffMainTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setTestOverride(app, null)
        File(app.filesDir, "vault2").deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        ScanRepository.readProbe = null
        VaultActivity.storeOverride = null
        VaultActivity.biometricStatusOverride = null
        VaultSession.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(timeoutMs: Long = 8000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }

    @Test
    fun `vault add files lists the library off the main thread`() {
        val store = VaultStore(
            context = app,
            repo = repo,
            keys = FakeVaultKeys(),
            gate = VaultPasscodeGate(clock = { 0L }),
            iterations = 2_000
        )
        VaultActivity.storeOverride = store
        VaultActivity.biometricStatusOverride = BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
        store.setup("246810")
        DocFixtures.saved(repo, app, pages = 1, name = "Vault pick")

        val mainName = Thread.currentThread().name
        val threads = java.util.Collections.synchronizedList(mutableListOf<String>())
        val controller = Robolectric.buildActivity(VaultActivity::class.java).setup()
        try {
            val activity = controller.get()
            idle()
            ScanRepository.readProbe = { threads += it }
            activity.findViewById<View>(R.id.vaultAddFiles).performClick()

            val shown = await {
                val dialog = ShadowDialog.getLatestDialog()
                dialog?.findViewById<View>(R.id.vaultAddRow) != null
            }
            assertTrue("the add-files sheet opens when the list arrives", shown)
            assertTrue("the library was listed", threads.isNotEmpty())
            assertFalse(
                "listDocuments ran on the main thread: $threads",
                threads.contains(mainName)
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `insert from document lists the library off the main thread`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Target")
        DocFixtures.saved(repo, app, pages = 1, name = "Source")

        val mainName = Thread.currentThread().name
        val threads = java.util.Collections.synchronizedList(mutableListOf<String>())
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            val loaded = await {
                var ready = false
                scenario.onActivity { a ->
                    ready = a.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true
                }
                ready
            }
            assertTrue("the viewer loads", loaded)

            scenario.onActivity { activity ->
                ScanRepository.readProbe = { threads += it }
                activity.startInsertPages()
                idle()
                val sources = ShadowDialog.getLatestDialog()
                assertNotNull("the insert-source sheet opens", sources)
                val items = sources.findViewById<android.view.ViewGroup>(R.id.swSheetItems)
                items.getChildAt(2).performClick() // "From document"
            }

            val shown = await {
                val dialog = ShadowDialog.getLatestDialog()
                dialog != null && dialog.findViewById<View>(R.id.swDocPickerList) != null
            }
            assertTrue("the document chooser opens when the list arrives", shown)
            assertTrue("the library was listed", threads.isNotEmpty())
            assertFalse(
                "listDocuments ran on the main thread: $threads",
                threads.contains(mainName)
            )
        }
    }
}
