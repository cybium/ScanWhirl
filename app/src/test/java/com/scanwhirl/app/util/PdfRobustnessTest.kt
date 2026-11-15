package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Host sweep over the owner's hostile PDF corpus. Only when the QA folder is enabled
 * (`SW_OWNER_DIR` or `owner/PDFA_EVAL_ON`): the normal gate never touches the 204 Isartor files.
 * Every PDF must end in a classification, never in an escaped exception.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfRobustnessTest {

    @Test
    fun `hostile PDFs are classified without escaping exceptions`() {
        val owner = ownerDir() ?: run {
            println("PdfRobustnessTest: no owner corpus enabled")
            return
        }
        val downloads = File(owner, "downloads")
        val results = PdfRobustnessEval.run(downloads, File(owner, "out/L3"))
        File(owner, "PDFA_EVAL_ON").delete()
        File(owner, "L3_PDF_EVAL_ON").delete()

        assertTrue("no PDF was found in $downloads", results.isNotEmpty())
        val crashes = results.filter { it.crash != null }
        assertTrue(
            "escaped exceptions: " + crashes.joinToString { "${it.path}: ${it.crash}" },
            crashes.isEmpty()
        )
        assertTrue(
            "broken.pdf must be NOT_A_PDF",
            results.any {
                it.path.endsWith("broken.pdf") && it.kind == PdfRobustnessEval.Kind.NOT_A_PDF
            }
        )
        assertTrue(
            "our protected export must be ENCRYPTED",
            results.any {
                it.path.endsWith("bookprotected.pdf") &&
                    it.kind == PdfRobustnessEval.Kind.ENCRYPTED
            }
        )
    }

    private fun ownerDir(): File? {
        System.getenv("SW_OWNER_DIR")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        val dir = File("/mnt/c/Users/Cybium/Desktop/.sw-qa/owner")
        return dir.takeIf {
            File(it, "PDFA_EVAL_ON").isFile || File(it, "L3_PDF_EVAL_ON").isFile
        }
    }
}
