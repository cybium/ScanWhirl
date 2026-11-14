package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.PageStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** L5 Phase A: saved signatures are vector paths in app storage, at most 5. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SignatureStoreTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private fun stroke(vararg points: Pair<Float, Float>) = PageStamp.Stroke(
        points.map { PageStamp.Point(it.first, it.second) }
    )

    private val sample = listOf(
        stroke(0.1f to 0.8f, 0.3f to 0.2f, 0.7f to 0.75f),
        stroke(0.2f to 0.5f, 0.8f to 0.4f)
    )

    @Test
    fun `a saved signature reloads with identical vector points`() {
        val store = SignatureStore(context)
        val saved = store.save("My signature", sample)

        val loaded = requireNotNull(store.load(saved.id))
        assertEquals("My signature", loaded.name)
        assertEquals(sample, loaded.strokes)
        assertEquals(1, store.list().size)
        assertTrue("the path is stored in app storage", File(context.filesDir, "signatures").list()!!.isNotEmpty())
    }

    @Test
    fun `at most five signatures are kept`() {
        val store = SignatureStore(context)
        repeat(5) { store.save("Signature ${it + 1}", sample) }
        assertEquals(5, store.list().size)

        try {
            store.save("Sixth", sample)
            fail("a sixth signature must be refused")
        } catch (_: IllegalStateException) {
        }
        assertEquals(5, store.list().size)

        val oldest = store.list().last()
        store.delete(oldest.id)
        assertEquals(4, store.list().size)
        store.save("Sixth", sample)
        assertEquals(5, store.list().size)
        assertEquals(1, store.list().count { it.name == "Sixth" })
    }

    @Test
    fun `empty ink is refused`() {
        val store = SignatureStore(context)
        try {
            store.save("Nothing", emptyList())
            fail("a signature needs at least one stroke")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `delete removes only the requested signature`() {
        val store = SignatureStore(context)
        val a = store.save("A", sample)
        val b = store.save("B", sample)
        store.delete(a.id)
        assertNull(store.load(a.id))
        assertNotNull(store.load(b.id))
        assertEquals(listOf("B"), store.list().map { it.name })
    }

    @Test
    fun `a damaged file is skipped and does not lock the user out`() {
        val store = SignatureStore(context)
        store.save("Good", sample)
        val dir = File(context.filesDir, "signatures").apply { mkdirs() }
        File(dir, "broken.sig").writeText("#scanwhirl-signature-v1\nnot a signature")
        assertEquals(1, store.list().size)
        assertNull(store.load("broken"))
    }

    @Test
    fun `ids cannot escape the signature folder`() {
        val store = SignatureStore(context)
        assertNull(store.load("../../databases/x"))
        store.delete("../../databases/x")
        assertTrue(store.list().isEmpty())
    }
}
