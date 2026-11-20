package com.scanwhirl.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.WindowManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.vault.VaultActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VaultReaderSessionTest {
    private lateinit var app: Application
    private lateinit var file: File
    private lateinit var factory: (ParcelFileDescriptor) -> PdfViewerActivity.PdfPages
    private var closed = false
    private var opens = 0
    private var descriptor: ParcelFileDescriptor? = null

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        file = File(app.cacheDir, "vault_open/fictional/document.pdf")
        file.parentFile!!.mkdirs()
        file.writeText("fictional PDF; fake renderer")
        factory = PdfViewerActivity.pagesFactory
        PdfViewerActivity.pagesFactory = {
            descriptor = it
            opens++
            object : PdfViewerActivity.PdfPages {
                override val pageCount = 1
                override fun render(index: Int, maxDim: Int) =
                    Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                override fun close() { closed = true }
            }
        }
        VaultSession.open(ByteArray(32) { 1 })
    }

    @After fun cleanup() {
        PdfViewerActivity.pagesFactory = factory
        VaultSession.close()
        VaultActivity.storeOverride = null
        file.delete()
    }

    private fun reader() = Robolectric.buildActivity(
        PdfViewerActivity::class.java,
        PdfViewerActivity.privateFileIntent(app, file.path, "Fictional")
    )

    @Test fun expiredResumeClearsContentAndClosesBeforeDestroy() {
        val c = reader().setup()
        val a = c.get()
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        @Suppress("UNCHECKED_CAST")
        val cache = field(a, "pageCache") as android.util.LruCache<Int, Bitmap>
        cache.put(0, bitmap)
        a.findViewById<android.widget.ImageView>(R.id.zoomImage).setImageBitmap(bitmap)
        a.findViewById<View>(R.id.zoomOverlay).visibility = View.VISIBLE
        a.findViewById<View>(R.id.readerDockPages).performClick()
        // Ensure the render finished and its UI delivery is queued before expiry.
        (field(a, "renderExecutor") as java.util.concurrent.ExecutorService)
            .submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
        c.pause().stop()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(3))
        c.start().resume()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(a.isFinishing)
        assertTrue(closed)
        assertNull(field(a, "renderer"))
        assertNull(field(a, "pfd"))
        assertEquals(0, (field(a, "pageCache") as android.util.LruCache<*, *>).size())
        assertFalse(descriptor!!.fileDescriptor.valid())
        assertFalse(file.exists())
        assertEquals(0, a.findViewById<RecyclerView>(R.id.pdfPager).adapter?.itemCount ?: 0)
        assertEquals(View.GONE, a.findViewById<View>(R.id.zoomOverlay).visibility)
        assertNull(a.findViewById<android.widget.ImageView>(R.id.zoomImage).drawable)
        assertEquals(0, a.findViewById<android.view.ViewGroup>(R.id.readerStripPages).childCount)
        assertEquals(View.INVISIBLE, a.findViewById<View>(android.R.id.content)
            .let { (it as android.view.ViewGroup).getChildAt(0) }.visibility)
        assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        c.pause().stop().destroy()
    }

    @Test fun shortBackgroundKeepsReader() {
        val c = reader().setup()
        c.pause().stop()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        c.start().resume()
        assertFalse(c.get().isFinishing)
        assertFalse(closed)
        assertTrue(file.exists())
        c.pause().stop().destroy()
    }

    @Test fun processWithoutSessionCannotOpenEvenWhenCopySurvives() {
        VaultSession.close()
        val c = reader().setup()
        assertTrue(c.get().isFinishing)
        assertEquals(0, opens)
        assertFalse(file.exists())
        c.pause().stop().destroy()
    }

    @Test fun validRecreationKeepsReader() {
        val c = reader().setup()
        c.recreate()
        assertFalse(c.get().isFinishing)
        assertEquals(1, c.get().findViewById<RecyclerView>(R.id.pdfPager).adapter!!.itemCount)
        c.pause().stop().destroy()
    }

    @Test fun navigationBetweenStartedActivitiesDoesNotStartGrace() {
        val store = VaultStore(app, ScanRepository(app), FakeVaultKeys(), iterations = 2_000)
        store.setup("246810")
        VaultActivity.storeOverride = store
        val first = Robolectric.buildActivity(VaultActivity::class.java).setup()
        first.pause()
        val second = reader().setup()
        first.stop()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(3))
        assertTrue(VaultSession.isUnlocked())
        second.pause().stop().destroy()
        first.destroy()
    }

    @Test fun recreationAfterSessionLossDoesNotReopen() {
        val c = reader().setup()
        VaultSession.close()
        c.recreate()
        assertTrue(c.get().isFinishing)
        assertEquals(1, opens)
        assertFalse(file.exists())
        c.pause().stop().destroy()
    }

    @Test fun coldStartSweepRemovesDecryptedCopies() {
        VaultSession.close()
        com.scanwhirl.app.util.PrivacySweep.run(app)
        assertFalse(file.exists())
    }

    @Test fun vaultLocationWithoutMarkerStillRequiresSession() {
        VaultSession.close()
        val c = Robolectric.buildActivity(
            PdfViewerActivity::class.java,
            PdfViewerActivity.fileIntent(app, file.path, "Fictional")
        ).setup()
        assertTrue(c.get().isFinishing)
        assertEquals(0, opens)
        assertFalse(file.exists())
        c.pause().stop().destroy()
    }

    @Test fun ordinaryPdfDoesNotRequireVaultSessionOrDeleteOriginal() {
        VaultSession.close()
        val ordinary = File(app.cacheDir, "fictional-ordinary.pdf").apply { writeText("fake PDF") }
        val c = Robolectric.buildActivity(
            PdfViewerActivity::class.java,
            PdfViewerActivity.fileIntent(app, ordinary.path, "Fictional")
        ).setup()
        assertFalse(c.get().isFinishing)
        assertEquals(1, opens)
        c.pause().stop().destroy()
        assertTrue(ordinary.exists())
        ordinary.delete()
    }

    private fun field(activity: PdfViewerActivity, name: String): Any? =
        PdfViewerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
}
