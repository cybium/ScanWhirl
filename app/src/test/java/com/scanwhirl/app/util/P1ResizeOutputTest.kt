package com.scanwhirl.app.util

import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.HomeHost
import com.scanwhirl.app.tools.ResizeFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Lane P1 defects 2 and 3: resized photos used to land in filesDir/exports,
 * which the cold-start sweep never touches, under a deterministic name that a
 * second run silently overwrote. Each run must get its own folder inside the
 * FileProvider-exposed, swept cache documents tree.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class P1ResizeOutputTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun resetFileProviderCache() {
        val f = FileProvider::class.java.getDeclaredField("sCache")
        f.isAccessible = true
        (f.get(null) as MutableMap<*, *>).clear()
        File(app.cacheDir, "documents").deleteRecursively()
    }

    private fun flow(): ResizeFlow {
        val activity = Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java)
            .setup().get()
        val host = object : HomeHost {
            override fun refreshList() = Unit
            override fun showProgress(titleRes: Int): com.scanwhirl.app.ui.kit.SwProgress =
                throw IllegalStateException("no progress sheet in this host test")
            override fun toast(res: Int, duration: Int) = Unit
            override fun toast(message: CharSequence, duration: Int) = Unit
        }
        return ResizeFlow(activity, ScanRepository(app), host)
    }

    /** Reflection by name keeps this test compiling against the unfixed base. */
    private fun outputDir(flow: ResizeFlow): File {
        val method = ResizeFlow::class.java.getDeclaredMethod("resizeOutputDir", File::class.java)
        method.isAccessible = true
        return method.invoke(flow, app.cacheDir) as File
    }

    @Test fun `each resize run gets its own folder under the swept cache documents tree`() {
        val first = outputDir(flow())
        val second = outputDir(flow())

        assertNotEquals("two runs must never share an output folder", first, second)
        val coveredRoot = File(app.cacheDir, "documents").canonicalPath + File.separator
        assertTrue("first run escaped the FileProvider cache tree: $first",
            first.canonicalPath.startsWith(coveredRoot))
        assertTrue("second run escaped the FileProvider cache tree: $second",
            second.canonicalPath.startsWith(coveredRoot))
        assertTrue("the per-run folder must exist", first.isDirectory && second.isDirectory)

        val source = File("src/main/java/com/scanwhirl/app/tools/ResizeFlow.kt").readText()
        assertFalse("resized photos must not be written to filesDir/exports",
            source.contains("File(activity.filesDir, \"exports\")"))
    }

    @Test fun `a resize result opens through FileProvider and is swept at cold start`() {
        val dir = outputDir(flow())
        val file = File(dir, "receipt_120x80.jpg").apply { writeBytes(ImportFixtures.image()) }

        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        assertArrayEquals("Open/Share from the result screen must still read the bytes",
            file.readBytes(), app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })

        PrivacySweep.run(app)
        assertFalse("plaintext resized photo must not survive a cold start: ${file.path}", file.exists())
    }
}
