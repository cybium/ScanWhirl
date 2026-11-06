package com.scanwhirl.app.ui.globe

import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.util.CategoryRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The globe scene is pure math, so almost everything about the new home
 * screen can be asserted without rendering a single pixel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GlobeSceneTest {

    private lateinit var scene: GlobeScene

    private fun doc(
        id: String,
        name: String,
        category: String,
        locked: Boolean = false,
        vaulted: Boolean = false,
        created: Long = 0L
    ) = ScanDocument(
        id = id, name = name, createdAt = created, pageCount = 1,
        pdfFile = File("/tmp/$id.pdf"), pageFiles = emptyList(),
        isLocked = locked, isVaulted = vaulted, category = category
    )

    @Before
    fun setUp() {
        val mask = ApplicationProvider.getApplicationContext<android.content.Context>()
            .assets.open("land_mask.bin").use { it.readBytes() }
        scene = GlobeScene(mask)
    }

    @Test
    fun `fibonacci sphere is fully built with a plausible land count`() {
        assertEquals(GlobeScene.DOTS, scene.dotX.size)
        assertEquals(GlobeScene.DOTS, scene.dotLand.size)
        assertTrue("land mask must classify dots", scene.landCount in 300..1100)
        // Unit-length positions (a real sphere, not a flat circle).
        for (i in 0 until 50) {
            val len = kotlin.math.sqrt(
                scene.dotX[i] * scene.dotX[i] +
                    scene.dotY[i] * scene.dotY[i] +
                    scene.dotZ[i] * scene.dotZ[i]
            )
            assertTrue("dot $i should be on the unit sphere", kotlin.math.abs(len - 1f) < 0.01f)
        }
    }

    @Test
    fun `land mask agrees with known geography`() {
        assertTrue("Sahara", scene.isLand(10f, 20f))
        assertTrue("India", scene.isLand(78f, 22f))
        assertFalse("mid-Atlantic", scene.isLand(-30f, 0f))
        assertFalse("mid-Pacific", scene.isLand(-140f, 0f))
    }

    @Test
    fun `documents land on their category ring`() {
        scene.setDocs(
            listOf(
                doc("1", "Notes", CategoryRules.STUDY),
                doc("2", "Bill", CategoryRules.RECEIPTS),
                doc("3", "Passport", CategoryRules.IDS),
                doc("4", "Form", CategoryRules.FORMS),
                doc("5", "Misc", CategoryRules.OTHER)
            )
        )
        val ringOf = scene.cards.associate { it.docId to it.ringIndex }
        assertEquals(0, ringOf["1"]) // Study = innermost ring
        assertEquals(1, ringOf["2"])
        assertEquals(2, ringOf["3"])
        assertEquals(3, ringOf["4"])
        assertEquals(4, ringOf["5"])
        assertTrue("nothing hidden here", scene.cards.none { it.hidden })
    }

    @Test
    fun `locked and vaulted cards are hidden and never expose a title`() {
        scene.setDocs(
            listOf(
                // Both names contain the query: only the public one may light up.
                doc("pub", "Public salary guide", CategoryRules.STUDY),
                doc("lock", "Salary negotiation", CategoryRules.STUDY, locked = true),
                doc("vault", "Therapy notes", CategoryRules.STUDY, vaulted = true)
            )
        )
        val locked = scene.cards.first { it.docId == "lock" }
        val vaulted = scene.cards.first { it.docId == "vault" }
        assertTrue(locked.hidden)
        assertTrue(vaulted.hidden)
        assertNull(locked.visibleTitle)
        assertNull(vaulted.visibleTitle)
        assertNull("search must not leak the name", locked.visibleTitle?.takeIf { it.contains("Salary") })
        // A search for the exact name must not light a hidden card up.
        scene.query = "Salary"
        assertFalse("hidden cards never match", scene.matches(locked))
        assertTrue("public cards still match", scene.matches(scene.cards.first { it.docId == "pub" }))
    }

    @Test
    fun `hidden cards only ever sit on the far side of the ring`() {
        scene.setDocs(
            listOf(doc("lock", "Secret", CategoryRules.IDS, locked = true))
        )
        val card = scene.cards.first()
        // Sweep the ring through several rotations; z must never go positive.
        repeat(24) {
            scene.ringAngles[card.ringIndex] = it * 0.7f
            val pos = scene.cardPosition(card, 1080f, 1920f)
            assertTrue("hidden card went to the front: z=${pos[2]}", pos[2] <= 0.0001f)
        }
    }

    @Test
    fun `search dims non-matching cards to twelve percent`() {
        scene.setDocs(
            listOf(
                doc("a", "Biology notes", CategoryRules.STUDY),
                doc("b", "Rent receipt", CategoryRules.RECEIPTS)
            )
        )
        scene.query = "biology"
        val matched = scene.cards.first { it.docId == "a" }
        val missed = scene.cards.first { it.docId == "b" }
        assertTrue(scene.matches(matched))
        assertFalse(scene.matches(missed))
        assertEquals(1f, scene.alphaFor(matched, z = 1f), 0.001f)
        assertEquals(GlobeScene.DIM_ALPHA, scene.alphaFor(missed, z = 1f), 0.001f)
        // Far side dims further.
        assertEquals(
            GlobeScene.DIM_ALPHA * GlobeScene.BACK_ALPHA,
            scene.alphaFor(missed, z = -1f), 0.001f
        )
    }

    @Test
    fun `ocr matches from the shelf light the card up`() {
        scene.setDocs(listOf(doc("a", "Scan 42", CategoryRules.OTHER)))
        scene.query = "photosynthesis"
        assertFalse(scene.matches(scene.cards.first()))
        scene.extraMatchIds = setOf("a")
        assertTrue("OCR hit must light the card", scene.matches(scene.cards.first()))
    }

    @Test
    fun `tapping the earth eases from home to rings and back`() {
        assertEquals(GlobeScene.STATE_HOME, scene.state)
        scene.toggleState()
        assertEquals(GlobeScene.STATE_RINGS, scene.state)
        repeat(90) { scene.update(1f / 30f) }
        assertEquals("stateT should settle at 1", 1f, scene.stateT, 0.01f)
        scene.toggleState()
        repeat(90) { scene.update(1f / 30f) }
        assertEquals("stateT should settle back at 0", 0f, scene.stateT, 0.01f)
    }

    @Test
    fun `earth keeps rotating slowly on its own`() {
        val before = scene.earthRotY
        repeat(30) { scene.update(1f / 30f) }
        assertTrue("earth must auto-rotate", scene.earthRotY > before)
    }

    @Test
    fun `five rings fit inside a portrait screen`() {
        val w = 1080f
        val h = 1920f
        for (r in 0 until GlobeScene.RING_COUNT) {
            val g = scene.ringGeometry(r, w, h)
            assertTrue("ring $r must stay on screen", g.cx + g.rx <= w + 1f)
            assertTrue("ring $r must stay on screen", g.cx - g.rx >= -1f)
            assertTrue("ring $r must stay on screen", g.cy + g.ry <= h)
        }
        // Ring order: radii grow outward.
        assertTrue(scene.ringGeometry(4, w, h).rx > scene.ringGeometry(0, w, h).rx)
    }

    @Test
    fun `recent public docs ride the home orbit`() {
        scene.setDocs(
            listOf(
                doc("1", "Newest", CategoryRules.STUDY, created = 300L),
                doc("2", "Old", CategoryRules.STUDY, created = 100L),
                doc("3", "Locked orbit", CategoryRules.STUDY, locked = true, created = 400L)
            )
        )
        assertEquals(listOf("1", "2"), scene.orbitCards.map { it.docId })
        assertTrue(scene.orbitCards.none { it.hidden })
    }
}
