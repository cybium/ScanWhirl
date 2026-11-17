package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.PageRanges
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Owner F9: a split writes "<document name> part 1", "part 2", ... in page order, not
 * "<name> - Pages 1-3". Hindi says "<name> भाग 1".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SplitNamingFixUiTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
    }

    @Test
    fun `split parts are numbered in page order`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 5, name = "Contract")
        val outputs = repo.splitByRange(id, PageRanges.parse("1-2, 4", pageCount = 5))

        assertEquals(listOf("Contract part 1", "Contract part 2"), outputs.map { it.name })
        assertEquals(listOf(2, 1), outputs.map { it.pageCount })
    }

    @Test
    fun `parts follow page order even when the ranges are typed unsorted`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 5, name = "Contract")
        val outputs = repo.splitByRange(id, PageRanges.parse("4, 1-2", pageCount = 5))

        assertEquals(listOf("Contract part 1", "Contract part 2"), outputs.map { it.name })
        assertEquals("part 1 is the earlier pages", listOf(2, 1), outputs.map { it.pageCount })
    }

    @Test
    fun `re-splitting a part never stacks the suffix`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Contract")
        val first = repo.splitByRange(id, PageRanges.parse("1-2", pageCount = 3)).single()
        assertEquals("Contract part 1", first.name)

        val again = repo.splitByRange(first.id, PageRanges.parse("1", pageCount = 2)).single()
        assertEquals("Contract part 1 (2)", again.name)
    }

    @Test
    @Config(qualifiers = "hi")
    fun `hindi split parts say भाग`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "अनुबंध")
        val outputs = repo.splitByRange(id, PageRanges.parse("1, 2-3", pageCount = 3))

        assertEquals(listOf("अनुबंध भाग 1", "अनुबंध भाग 2"), outputs.map { it.name })
    }
}
