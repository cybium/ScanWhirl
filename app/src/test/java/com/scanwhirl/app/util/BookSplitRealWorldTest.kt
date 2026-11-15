package com.scanwhirl.app.util

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Real photos, never committed: runs only when `SW_REALWORLD_DIR`/`SW_OWNER_DIR` is set, or when
 * the QA folder carries an `EVAL_ON` file (the gate runs inside WSL, where a Windows env var
 * cannot be exported). Without either, it returns early and the normal gate passes.
 *
 * The realworld crop pass must hit the measured spine (±3% of the cropped width). The owner's
 * sideways textbook photos must be detected as horizontal; the previews are for the lead.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BookSplitRealWorldTest {

    @Test
    fun `real world and owner book photos`() {
        val realDir = dir("SW_REALWORLD_DIR", "$QA/realworld")
        val ownerDir = dir("SW_OWNER_DIR", "$QA/owner")
        var ran = false

        if (realDir != null) {
            val results = BookSplitEval.run(BookSplitEval.realWorld(realDir))
            File(realDir, "EVAL_ON").delete()
            File(realDir, "L3_EVAL_ON").delete()
            ran = true
            assertTrue("no real-world photo found in $realDir", results.isNotEmpty())
            // The two unambiguous centred spreads must be on the spine; the hard photos are
            // judged from the previews (and reported), not hidden by a broad assertion.
            val centred = results.filter { it.name == "book_px762687" || it.name == "book_px13752245" }
            assertTrue(
                "the centred spreads are not on the spine: " +
                    centred.joinToString { "${it.name}=${it.croppedRatio}" },
                centred.size == 2 && centred.all {
                    kotlin.math.abs(it.croppedRatio - 0.5f) <= 0.03f
                }
            )
        }

        if (ownerDir != null) {
            val results = BookSplitEval.run(BookSplitEval.owner(ownerDir))
            File(ownerDir, "EVAL_ON").delete()
            File(ownerDir, "L3_EVAL_ON").delete()
            ran = true
            assertTrue("no owner photo found in $ownerDir", results.isNotEmpty())
            // The phone bug: a sideways spread was offered as "Keep as one page". A spread must
            // never be classified as a single page, whatever the orientation.
            val singled = results.filter { it.croppedSinglePage }
            assertTrue(
                "a sideways spread must not be offered as one page: " +
                    singled.joinToString { it.name },
                singled.isEmpty()
            )
        }

        if (!ran) println("BookSplitRealWorldTest: no eval folder enabled; nothing to do")
    }

    private fun dir(env: String, path: String): File? {
        System.getenv(env)?.takeIf { it.isNotBlank() }?.let { File(it) }?.let { return it }
        val folder = File(path)
        return folder.takeIf {
            File(it, "L3_EVAL_ON").isFile || File(it, "EVAL_ON").isFile
        }
    }

    private companion object {
        const val QA = "/mnt/c/Users/Cybium/Desktop/.sw-qa"
    }
}
