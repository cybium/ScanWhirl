package com.scanwhirl.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lane P1 defect 4. Source-level checks are used deliberately: the defect is a
 * main-thread, unbounded read whose allocation cannot be forced deterministically
 * in a host test. The copier itself is covered by BoundedCopyTest; these checks
 * pin that the resize flow actually routes through it and handles a dead heap.
 */
class P1FlowSafetyTest {

    private val flowUtil = File("src/main/java/com/scanwhirl/app/tools/FlowUtil.kt").readText()
    private val resize = File("src/main/java/com/scanwhirl/app/tools/ResizeFlow.kt").readText()

    @Test fun `a picked image is copied with a bound off the main thread`() {
        assertTrue("FlowUtil must use the bounded copier", flowUtil.contains("BoundedCopy.copy"))
        assertTrue("the picked image must be capped", flowUtil.contains("BoundedCopy.IMAGE_CAP"))
        assertFalse("the unbounded main-thread read must be gone from the resize flow",
            resize.contains("FlowUtil.readUriBytes"))
    }

    @Test fun `an exhausted heap becomes the localized too-large reason`() {
        assertTrue("OutOfMemoryError must be caught in the resize flow",
            resize.contains("catch (e: OutOfMemoryError)"))
        assertTrue("the too-large reason must be shown",
            resize.contains("R.string.f8_reason_too_large"))
    }
}
