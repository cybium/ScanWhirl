package com.scanwhirl.app.ui.shell

import android.app.Application
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.VaultSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * H10 (found on device, 1.2.2): after the Vault was created and the owner came back,
 * the Vault tab still said "Not set up yet" until the app was relaunched. The tab now
 * re-reads the Vault whenever it is shown again, not only when the nav bar is tapped.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class VaultScreenFixTextTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        File(app.filesDir, "vault2").deleteRecursively()
    }

    @After
    fun tearDown() {
        VaultSession.close()
        File(app.filesDir, "vault2").deleteRecursively()
    }

    private fun createVault() {
        val dir = File(app.filesDir, "vault2").apply { mkdirs() }
        File(dir, "recovery.bin").writeBytes(byteArrayOf(1, 2, 3, 4))
    }

    private fun status(screen: VaultScreen): String =
        screen.findViewById<TextView>(R.id.swVaultStatus).text.toString()

    @Test
    fun `the tab re-reads the Vault when it is shown again`() {
        val screen = VaultScreen(app)
        assertEquals(
            "before the first show the tab is still checking",
            app.getString(R.string.vault_tab_status_loading),
            status(screen)
        )

        // The tab is on screen while the Vault does not exist yet.
        screen.dispatchWindowVisibilityChanged(View.VISIBLE)
        assertEquals(
            app.getString(R.string.vault_tab_not_setup),
            status(screen)
        )

        // The owner creates the Vault in VaultActivity and comes back to this tab.
        createVault()
        screen.dispatchWindowVisibilityChanged(View.VISIBLE)
        assertNotEquals(
            "the tab must not keep saying the Vault is missing",
            app.getString(R.string.vault_tab_not_setup),
            status(screen)
        )
        assertEquals(
            "the created, locked Vault is reported honestly",
            app.getString(R.string.vault_tab_locked),
            status(screen)
        )
    }

    @Test
    fun `becoming visible refreshes the status as well`() {
        val screen = VaultScreen(app)
        screen.onVisibilityAggregated(true)
        assertEquals(app.getString(R.string.vault_tab_not_setup), status(screen))

        createVault()
        screen.onVisibilityAggregated(true)
        assertEquals(app.getString(R.string.vault_tab_locked), status(screen))
    }
}
