package com.scanwhirl.app.tools

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.appcompat.app.AppCompatActivity
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.OcrLanguage
import com.scanwhirl.app.util.OcrLanguageSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * O1 defect 3: the one-shot "Hindi text" tool must not leave the app's global Text reading
 * language switched to Hindi without the person's confirmation (the OcrEditor's confirmed
 * re-read is a different, explicit flow).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class O1OfficeHindiTextTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        OcrLanguageSettings.set(app, OcrLanguage.ENGLISH)
        OcrEngine.rawReader = { _, _ -> throw IllegalStateException("text model is missing") }
    }

    @After
    fun tearDown() {
        OcrEngine.rawReader = null
        OcrLanguageSettings.set(app, OcrLanguage.ENGLISH)
    }

    @Test
    fun `one shot hindi read leaves the global reading language unchanged`() {
        val repo = ScanRepository(app)
        val id = DocFixtures.saved(repo, app, pages = 1, name = "One shot")
        val run = HindiTextLauncher::class.java.declaredMethods.firstOrNull {
            it.name.startsWith("run") && it.parameterTypes.size == 3
        } ?: error("HindiTextLauncher.run is missing")
        run.isAccessible = true

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                run.invoke(HindiTextLauncher, activity, repo, id)
                repeat(50) { shadowOf(Looper.getMainLooper()).idle() }
            }
        }

        assertEquals(
            "the one-shot tool must not change the global Text reading language",
            OcrLanguage.ENGLISH,
            OcrLanguageSettings.get(app)
        )
    }
}
