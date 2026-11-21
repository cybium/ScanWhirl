package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * S2 item 3: the stale-marker branch of pruneRecoveryStaging used `work.deleteRecursively()`
 * unchecked. When that delete fails the leftover plaintext must be exposed as incomplete recovery
 * instead of silently claimed as a clean pass. Tested in isolation because the earlier unlock /
 * password-swap steps consume a stale marker before this branch would see it in a full pass.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class S2PruneStaleStagingTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @After
    fun tearDown() = Faults.resetAll()

    @Test
    fun `a failed stale staging folder delete marks recovery incomplete`() {
        val work = OpStaging.create(app.filesDir)
        File(work, "plain.pdf").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        File(work, ".unlock").writeText(
            "scanwhirl-unlock-v1\nid=missing0001\nold=oldverifier\npdf=${"0".repeat(64)}\nnewPdf=${"1".repeat(64)}\n"
        )

        val prune = ScanRepository::class.java.getDeclaredMethod(
            "pruneRecoveryStaging", File::class.java
        )
        prune.isAccessible = true

        ScanRepository.recoveryFileDelete = { false }
        ScanRepository.recoveryIncomplete = false
        prune.invoke(repo, work)

        assertTrue(
            "an unchecked stale-folder delete must not be reported as a clean pass",
            ScanRepository.recoveryIncomplete
        )
    }
}
