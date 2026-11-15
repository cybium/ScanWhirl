package com.scanwhirl.app.eval

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Play Store graphics rendered from the app's real brand resources (the adaptive launcher icon
 * layers and the in-app brand mark / colours / heading typeface).
 *
 * Runs only when `…/.sw-qa/owner/L4_EVAL_ON` exists; writes PNGs to `…/.sw-qa/store/`:
 *  - icon_512.png (512×512, full-bleed adaptive background + foreground, opaque corners)
 *  - feature_1024x500.png (dark app background, cyan glow, brand mark, name + tagline)
 *  - icon_preview_rounded.png (512, the same icon under a launcher-style mask)
 *
 * Private QA output; the PNGs are never committed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PlayGraphicsRenderTest {

    private val qa = File("/mnt/c/Users/Cybium/Desktop/.sw-qa")
    private val flag = File(qa, "owner/L4_EVAL_ON")
    private val out = File(qa, "store")

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `render play graphics from the real brand assets`() {
        if (!flag.isFile) {
            println("EVAL SKIPPED: no ${flag.path}")
            return
        }
        out.mkdirs()

        val icon = renderIcon()
        val rounded = renderRounded(icon)
        writePng(File(out, "icon_512.png"), icon)
        val feature = renderFeature()
        writePng(File(out, "feature_1024x500.png"), feature)
        writePng(File(out, "icon_preview_rounded.png"), rounded)

        assertEquals(512, icon.width)
        assertEquals(512, icon.height)
        assertEquals(1024, feature.width)
        assertEquals(500, feature.height)
        // Opaque corners: Play's own mask must be the first rounding.
        listOf(0 to 0, 511 to 0, 0 to 511, 511 to 511).forEach { (x, y) ->
            assertEquals("icon corner alpha at $x,$y", 255, Color.alpha(icon.getPixel(x, y)))
        }
        listOf(0 to 0, 1023 to 0, 0 to 499, 1023 to 499).forEach { (x, y) ->
            assertEquals("feature corner alpha at $x,$y", 255, Color.alpha(feature.getPixel(x, y)))
        }
        icon.recycle()
        feature.recycle()
        rounded.recycle()
        val sizes = listOf("icon_512.png", "feature_1024x500.png", "icon_preview_rounded.png")
            .joinToString { name -> "$name=${File(out, name).length()}B" }
        println("EVAL RAN: 3 images -> ${out.absolutePath} ($sizes)")
        assertTrue(File(out, "icon_512.png").length() > 0)
    }

    // ------------------------------------------------------------------
    // Icon: adaptive layers exactly as the phone composes them
    // ------------------------------------------------------------------

    private fun renderIcon(): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // <background android:drawable="@color/sw_brand_ground" />
        canvas.drawColor(ContextCompat.getColor(app, R.color.sw_brand_ground))
        // <foreground android:drawable="@drawable/ic_launcher_foreground" /> is a 108dp
        // viewport with its content inside the 72dp safe zone; draw it over the full 108dp/
        // 512px canvas exactly as AdaptiveIconDrawable does.
        val foreground = requireNotNull(
            ContextCompat.getDrawable(app, R.drawable.ic_launcher_foreground)
        )
        foreground.setBounds(0, 0, size, size)
        foreground.draw(canvas)
        return bmp
    }

    /**
     * The platform's adaptive-icon mask, so the preview shows the shape a launcher would apply.
     * Falls back to Play's rounded-square radius if the platform mask is unavailable.
     */
    private fun renderRounded(icon: Bitmap): Bitmap {
        val size = icon.width
        val rounded = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(rounded)
        canvas.drawBitmap(icon, 0f, 0f, null)
        val adaptive = runCatching {
            ContextCompat.getDrawable(app, R.mipmap.ic_launcher) as? AdaptiveIconDrawable
        }.getOrNull()
        val maskPath = adaptive?.iconMask
        if (maskPath != null) {
            val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val lc = Canvas(layer)
            val scaled = Path(maskPath)
            val bounds = RectF()
            maskPath.computeBounds(bounds, true)
            if (bounds.width() > 0f && bounds.height() > 0f) {
                val s = size / maxOf(bounds.width(), bounds.height())
                val m = android.graphics.Matrix().apply {
                    setScale(s, s)
                    postTranslate(-bounds.left * s, -bounds.top * s)
                }
                scaled.transform(m)
            }
            lc.drawPath(scaled, Paint().apply { color = Color.WHITE })
            canvas.drawBitmap(
                layer, 0f, 0f,
                Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
            )
            layer.recycle()
        } else {
            val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val lc = Canvas(layer)
            val path = Path().apply {
                addRoundRect(
                    RectF(0f, 0f, size.toFloat(), size.toFloat()),
                    size * 0.225f, size * 0.225f, Path.Direction.CW
                )
            }
            lc.drawPath(path, Paint().apply { color = Color.WHITE })
            canvas.drawBitmap(
                layer, 0f, 0f,
                Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
            )
            layer.recycle()
        }
        return rounded
    }

    // ------------------------------------------------------------------
    // Feature graphic
    // ------------------------------------------------------------------

    private fun renderFeature(): Bitmap {
        val w = 1024
        val h = 500
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(ContextCompat.getColor(app, R.color.rv_bg))

        // Subtle cyan glow behind the mark / left third (brand accent, low alpha).
        val cyan = ContextCompat.getColor(app, R.color.cyan_primary)
        val glow = Paint().apply {
            shader = RadialGradient(
                235f, 250f, 470f,
                intArrayOf(
                    (cyan and 0x00FFFFFF) or (0x2E shl 24),
                    (cyan and 0x00FFFFFF) or (0x10 shl 24),
                    0x00000000
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), glow)

        // Real in-app brand mark (same vector as the Home header).
        val mark = requireNotNull(ContextCompat.getDrawable(app, R.drawable.ic_brand_mark))
        val markSize = 230
        val markLeft = 80
        val markTop = (h - markSize) / 2
        mark.setBounds(markLeft, markTop, markLeft + markSize, markTop + markSize)
        mark.draw(canvas)

        // Real heading typeface and ink colour from Home's app bar.
        val ink = ContextCompat.getColor(app, R.color.text_hologram_hi)
        val mid = ContextCompat.getColor(app, R.color.text_hologram_mid)
        val heading = Paint().apply {
            isAntiAlias = true
            typeface = SwType.extraBold(app)
            color = ink
            textSize = 96f
        }
        val textLeft = markLeft + markSize + 52f
        val safeRight = w - 72f
        canvas.drawText(app.getString(R.string.app_name), textLeft, 245f, heading)

        // Tagline: fit inside the central safe area without touching the right edge.
        val taglinePaint = Paint().apply {
            isAntiAlias = true
            typeface = SwType.regular(app)
            color = mid
            textSize = 32f
        }
        val tagline = "Scan, edit and protect PDFs — offline, no ads"
        val available = safeRight - textLeft
        while (taglinePaint.measureText(tagline) > available && taglinePaint.textSize > 20f) {
            taglinePaint.textSize -= 1f
        }
        canvas.drawText(tagline, textLeft, 310f, taglinePaint)
        return bmp
    }

    private fun writePng(file: File, bitmap: Bitmap) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // The icon/feature must be fully opaque; the rounded preview may be transparent outside.
        if (file.name != "icon_preview_rounded.png") {
            val check = android.graphics.BitmapFactory.decodeFile(file.path)
            try {
                assertEquals("${file.name} must be opaque", 255, Color.alpha(check.getPixel(0, 0)))
            } finally {
                check?.recycle()
            }
        }
    }
}
