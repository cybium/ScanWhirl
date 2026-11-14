package com.scanwhirl.app.util

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Real textbook photos, never committed: runs only when `SW_REALWORLD_DIR` is set, or when the
 * QA folder carries an `EVAL_ON` file (the gate runs inside WSL, where a Windows env var cannot
 * be exported). Without either, it returns early and the normal gate passes.
 *
 * Deletes `EVAL_ON` after the run so a later full gate does not re-run the photos.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BookSplitRealWorldTest {

    @Test
    fun `real world book photos match the README`() {
        val qa = File(QA_DIR)
        val env = System.getenv("SW_REALWORLD_DIR")?.let { File(it) }
        val sentinel = File(qa, "EVAL_ON")
        val dir = env ?: qa.takeIf { sentinel.isFile }
        if (dir == null || !dir.isDirectory) {
            println("BookSplitRealWorldTest: no SW_REALWORLD_DIR and no EVAL_ON; nothing to do")
            return
        }

        val out = System.getenv("SW_REALWORLD_OUT")?.let { File(it) } ?: File(dir, "out")
        val results = BookSplitEval.run(dir, out)
        sentinel.delete()

        assertTrue("no real-world photo was found in ${dir.absolutePath}", results.isNotEmpty())
        val failed = results.filter { !it.pass }
        assertTrue(
            "README expectations not met: " + failed.joinToString { "${it.name}=${it.ratio}" },
            failed.isEmpty()
        )
    }

    private companion object {
        const val QA_DIR = "/mnt/c/Users/Cybium/Desktop/.sw-qa/realworld"
    }
}
