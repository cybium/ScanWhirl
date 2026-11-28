package com.scanwhirl.app.ui.tools

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.util.IdCard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * fixidcrop: the ID card result preview used to be a white page on a white box, so the paper
 * edges could not be seen. The card page is now shown on a dark backdrop with a thin line and a
 * little padding; every other tool result keeps the old white preview box.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class IdCardPreviewFixIdCropTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun cardDocument(): String {
        val bitmap = Bitmap.createBitmap(900, 560, Bitmap.Config.ARGB_8888)
        val bytes = try {
            Canvas(bitmap).drawColor(Color.RED)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
        return runBlocking {
            repo.createDocument("ID card", listOf(IdCard.composeSingle(bytes))).id
        }
    }

    private fun waitUntil(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun luminance(pixel: Int): Int =
        (Color.red(pixel) * 77 + Color.green(pixel) * 151 + Color.blue(pixel) * 28) shr 8

    @Test
    fun `the id card page is shown on a dark bordered backdrop with padding`() {
        val id = cardDocument()
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "id_card", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { activity ->
                val host = requireNotNull(activity.findViewById<View>(R.id.toolResultPreviewHost))
                val preview = requireNotNull(activity.findViewById<ImageView>(R.id.toolResultPreview))
                val padding = SwStyle.dp(activity, 8f)

                assertTrue(
                    "a little padding shows where the paper ends",
                    host.paddingStart >= padding && host.paddingTop >= padding &&
                        host.paddingEnd >= padding && host.paddingBottom >= padding
                )
                val backdrop = host.background
                assertTrue("a dark backdrop, not a glass box: $backdrop", backdrop is GradientDrawable)
                assertEquals(
                    "the backdrop is the ink colour",
                    SwStyle.color(activity, R.color.void_abyss),
                    (backdrop as GradientDrawable).color?.defaultColor
                )
                assertNull("no white box behind the page", preview.background)

                assertTrue(
                    "the page preview is loaded",
                    waitUntil { preview.drawable is BitmapDrawable }
                )

                val width = SwStyle.dp(activity, 320f)
                val height = SwStyle.dp(activity, 200f)
                host.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                )
                host.layout(0, 0, width, height)
                val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    host.draw(Canvas(rendered))
                    val middle = height / 2
                    // The 1dp line sits on the very edge; take the brightest of the edge pixels.
                    var border = 0
                    for (x in 0..SwStyle.dp(activity, 2f)) {
                        border = maxOf(border, luminance(rendered.getPixel(x, middle)))
                    }
                    val aroundPage = luminance(rendered.getPixel(SwStyle.dp(activity, 5f), middle))
                    val pageTop = luminance(rendered.getPixel(width / 2, SwStyle.dp(activity, 12f)))

                    // W5a: the backdrop is a theme token, so it is the light canvas when
                    // Follow system resolves to light. Compare pixels to the resolved token.
                    val expectedBackdrop = SwStyle.color(activity, R.color.void_abyss)
                    val cr = (expectedBackdrop shr 16 and 0xFF) * 0.2126 +
                        (expectedBackdrop shr 8 and 0xFF) * 0.7152 +
                        (expectedBackdrop and 0xFF) * 0.0722
                    assertTrue(
                        "the backdrop around the page uses the theme token ($aroundPage vs $cr)",
                        kotlin.math.abs(aroundPage - cr) < 12
                    )
                    assertTrue("the page is white ($pageTop)", pageTop > 200)
                    assertTrue(
                        "a thin line marks the preview edge ($border vs $aroundPage)",
                        border > aroundPage + 10
                    )
                } finally {
                    rendered.recycle()
                }
            }
        }
    }

    @Test
    fun `other tool results keep the white preview box`() {
        val id = cardDocument()
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "passport", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { activity ->
                val host = requireNotNull(activity.findViewById<View>(R.id.toolResultPreviewHost))
                val preview = requireNotNull(activity.findViewById<ImageView>(R.id.toolResultPreview))
                assertEquals("no dark padding for other tools", 0, host.paddingStart)
                assertEquals("no duplicate full-page preview card", View.GONE, host.visibility)
                assertNull("no white preview drawable", preview.drawable)
            }
        }
    }
}
