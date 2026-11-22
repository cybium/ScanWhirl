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

    @Test
    fun oneGlyphPerConcept() {
        val duplicates = CANONICAL_GLYPHS.entries
            .groupBy { it.value }
            .filter { it.value.size > 1 }
            .map { "${it.key}: ${it.value.map { entry -> entry.key }}" }
        assertTrue(
            "One glyph must not stand for two different concepts: $duplicates",
            duplicates.isEmpty()
        )
    }

    /** Council §2: the owned call sites must use the canonical glyph for each concept. */
    @Test
    fun ownedCallSitesFollowCouncilIconMap() {
        val appRoot = findResRoot().parentFile.parentFile.parentFile
        val actions = File(appRoot, "src/main/java/com/scanwhirl/app/ui/DocumentActionsSheet.kt").readText()
        assertTrue(
            "Actions 'Read text' must use ic_sym_ocr, not the legacy ic_ocr",
            actions.contains("R.drawable.ic_sym_ocr") && !actions.contains("R.drawable.ic_ocr")
        )
        assertTrue(
            "Actions Protect/Unlock must use lock/lock_open, not encrypted",
            actions.contains("R.drawable.ic_sym_lock_open") &&
                actions.contains("R.drawable.ic_sym_lock") &&
                !actions.contains("R.drawable.ic_sym_encrypted")
        )

        val stamp = File(
            appRoot, "src/main/java/com/scanwhirl/app/ui/stamp/StampEditorActivity.kt"
        ).readText()
        assertTrue(
            "Stamp Draw must use ic_sym_draw",
            stamp.lines().any { it.contains("toolDraw") && it.contains("ic_sym_draw") }
        )
        assertTrue(
            "Stamp Highlight must use ic_sym_ink_highlighter",
            stamp.lines().any { it.contains("toolHighlight") && it.contains("ic_sym_ink_highlighter") }
        )
        assertTrue(
            "Stamp Page numbers must use ic_sym_format_list_numbered",
            stamp.lines().any {
                it.contains("toolPageNumbers") && it.contains("ic_sym_format_list_numbered")
            }
        )

        val dock = File(appRoot, "src/main/res/layout/view_reader_dock.xml").readText()
        assertTrue(
            "Reader dock Text must use the OCR glyph, not Add text",
            dock.contains("@drawable/ic_sym_ocr") && !dock.contains("@drawable/ic_sym_text_fields")
        )

        val legacyMenuIcons = File(appRoot, "src/main/res/menu")
            .listFiles { it.isFile && it.name.endsWith(".xml") }
            .orEmpty()
            .flatMap { Regex("@drawable/(ic_[A-Za-z0-9_]+)").findAll(it.readText()).map { m -> m.value }.toList() }
            .filter { !it.startsWith("@drawable/ic_sym_") }
        assertTrue(
            "Menus must be migrated to ic_sym_*, still legacy: $legacyMenuIcons",
            legacyMenuIcons.isEmpty()
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

        /** Council §2 canonical map: each concept owns exactly one Material Symbols glyph. */
        private val CANONICAL_GLYPHS = mapOf(
            "ocr" to "ic_sym_ocr",
            "protect" to "ic_sym_lock",
            "unlock" to "ic_sym_lock_open",
            "vault" to "ic_sym_shield_lock",
            "batch" to "ic_sym_apps",
            "pages" to "ic_sym_view_module",
            "pdfa" to "ic_sym_archive",
            "stamp" to "ic_sym_approval",
            "form_photo" to "ic_sym_photo_camera",
            "import_pdf" to "ic_sym_file_open",
            "clear_metadata" to "ic_sym_visibility_off",
            "word" to "ic_sym_description",
            "save_txt" to "ic_sym_notes",
            "qr_call" to "ic_sym_call",
            "qr_mail" to "ic_sym_mail",
            "draw" to "ic_sym_draw",
            "highlight" to "ic_sym_ink_highlighter",
            "page_numbers" to "ic_sym_format_list_numbered"
        )
    }
}
