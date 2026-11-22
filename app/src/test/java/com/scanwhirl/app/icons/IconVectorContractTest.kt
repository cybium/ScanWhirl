package com.scanwhirl.app.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ICON0 fail-closed contract for the Material Symbols Rounded family.
 *
 * Every ic_sym_* vector is a 960x900-style Material Symbols export whose path coordinates are
 * expressed in the *negative* Y half-plane. It is only visible inside the 960x960 viewport when
 * the path is wrapped in <group android:translateY="960">. Two vectors shipped without that
 * group (ic_sym_language, ic_sym_note_add) and rendered as blank slots on the phone.
 *
 * These tests guard both halves of the defect:
 *  1. the XML contract (negative-Y paths => translateY=960 group, 960 viewport);
 *  2. the runtime result (inflate with Robolectric's VectorDrawable, draw to a bitmap, and
 *     require at least one non-transparent pixel).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IconVectorContractTest {

    private data class SymbolVector(
        val name: String,
        val viewportWidth: Float,
        val viewportHeight: Float,
        val hasNegativeCoordinates: Boolean,
        val translateY: Float?
    )

    @Test
    fun negativeYMaterialSymbolsMustHaveTranslate960Group() {
        val broken = vectors().filter {
            it.viewportHeight == VIEWPORT && it.hasNegativeCoordinates && it.translateY != VIEWPORT
        }
        assertTrue(
            "Material Symbols with negative-Y paths must be wrapped in " +
                "<group android:translateY=\"960\">. Broken: " +
                broken.joinToString { "${it.name} (translateY=${it.translateY})" },
            broken.isEmpty()
        )
    }

    @Test
    fun everyMaterialSymbolUsesThe960Viewport() {
        val wrong = vectors().filter {
            it.viewportWidth != VIEWPORT || it.viewportHeight != VIEWPORT
        }
        assertTrue(
            "Every ic_sym_* vector must use the Material Symbols 960x960 viewport. Wrong: " +
                wrong.joinToString { "${it.name} (${it.viewportWidth}x${it.viewportHeight})" },
            wrong.isEmpty()
        )
    }

    @Test
    fun everyMaterialSymbolRendersVisiblePixels() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val resources = context.resources
        val packageName = context.packageName
        val blank = mutableListOf<String>()

        for (symbol in vectors()) {
            val id = resources.getIdentifier(symbol.name, "drawable", packageName)
            if (id == 0) throw AssertionError("No drawable resource found for ${symbol.name}")
            val drawable = resources.getDrawable(id, null)
                ?: throw AssertionError("Could not inflate ${symbol.name} as a drawable")

            val size = 96
            drawable.setBounds(0, 0, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawable.draw(Canvas(bitmap))

            var nonTransparent = 0
            for (y in 0 until size) {
                for (x in 0 until size) {
                    if ((bitmap.getPixel(x, y) ushr 24) != 0) nonTransparent++
                }
            }
            if (nonTransparent == 0) blank += symbol.name
        }

        assertTrue(
            "These ic_sym_* vectors render no pixels (blank icon slots): " +
                blank.joinToString(", "),
            blank.isEmpty()
        )
    }

    /** Discovers the real vectors from either the Windows checkout or the WSL gate mirror. */
    private fun vectors(): List<SymbolVector> {
        val resRoot = findResRoot()
        val files = resRoot.listFiles { it.isDirectory && it.name.startsWith("drawable") }
            .orEmpty()
            .flatMap { dir ->
                dir.listFiles { it.isFile && it.name.startsWith("ic_sym_") && it.name.endsWith(".xml") }
                    .orEmpty()
                    .asList()
            }
            .sortedBy { it.name }
        assertTrue(
            "No ic_sym_* vectors found under ${resRoot.absolutePath}; refusing a vacuous pass",
            files.size >= MIN_EXPECTED_ICONS
        )
        return files.map(::parse)
    }

    private fun findResRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        for (ignored in 0..9) {
            val moduleRes = File(dir, "src/main/res")
            val appRes = File(dir, "app/src/main/res")
            if (moduleRes.isDirectory) return moduleRes
            if (appRes.isDirectory) return appRes
            dir = dir.parentFile ?: break
        }
        fail("Cannot locate src/main/res walking up from ${dir.absolutePath}")
        return dir // unreachable; keeps the compiler happy after fail()
    }

    private fun parse(file: File): SymbolVector {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(file)
        val root = document.documentElement

        val viewportWidth = root.attr("viewportWidth")
        val viewportHeight = root.attr("viewportHeight")

        val paths = root.getElementsByTagName("path")
        val negative = (0 until paths.length).any { index ->
            val data = paths.item(index).attributes
                .getNamedItemNS(ANDROID_NS, "pathData")?.nodeValue.orEmpty()
            NUMBER.findAll(data).any { it.value.toFloatOrNull()?.let { n -> n < 0f } == true }
        }

        val groups = root.getElementsByTagName("group")
        val translateY = (0 until groups.length).mapNotNull { index ->
            groups.item(index).attributes
                .getNamedItemNS(ANDROID_NS, "translateY")?.nodeValue?.toFloatOrNull()
        }.firstOrNull()

        return SymbolVector(
            name = file.name.removeSuffix(".xml"),
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            hasNegativeCoordinates = negative,
            translateY = translateY
        )
    }

    private fun org.w3c.dom.Element.attr(localName: String): Float =
        getAttributeNS(ANDROID_NS, localName).toFloatOrNull() ?: 0f

    private companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val VIEWPORT = 960f
        private const val MIN_EXPECTED_ICONS = 80
        private val NUMBER = Regex("-?\\d+(?:\\.\\d+)?")
    }
}
