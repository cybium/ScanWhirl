package com.scanwhirl.app.store.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultPasscodeGate
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * G2-HONESTY (F-05): every restore warning the person reads must be a resource sentence in the
 * app's own language, never a hard-coded English string and never raw exception text. The
 * no-Vault-key case is covered end to end; the other two warnings are guarded in the source so
 * they cannot drift back to literals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RestoreWarningLocalizationTest {

    private lateinit var app: Application
    private val password = "correct horse battery"

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        cleanFiles()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        VaultSession.close()
        cleanFiles()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    @Test
    fun `a backup without the vault key reports it with the app string, not an English literal`() {
        val report = restoreArchiveWithoutVaultKey()
        val expected = app.getString(R.string.backup_vault_unverifiable_skipped)
        assertTrue(
            "the person must read the app's own warning; got: ${report.warnings}",
            report.warnings.contains(expected)
        )
        assertNoInternals(report.warnings)
    }

    /** The same restore reported while the app is in Hindi must be Hindi. */
    @Test
    @Config(sdk = [35], qualifiers = "hi")
    fun `the restore warning is Hindi when the app is Hindi`() {
        val report = restoreArchiveWithoutVaultKey()
        val expected = app.getString(R.string.backup_vault_unverifiable_skipped)
        assertTrue("the Hindi string has Devanagari: [$expected]", expected.any { it.code in 0x0900..0x097F })
        assertTrue(
            "the person must read the Hindi warning; got: ${report.warnings}",
            report.warnings.contains(expected)
        )
        assertTrue(
            "no English warning may leak into the Hindi report: ${report.warnings}",
            report.warnings.none { it.contains("cannot be checked") }
        )
        assertNoInternals(report.warnings)
    }

    @Test
    fun `every restore warning comes from a localized resource`() {
        val source = File("src/main/java/com/scanwhirl/app/store/backup/FullBackup.kt").readText()
        val literal = Regex("warnings\\.add\\(\"")
        assertFalse(
            "a hard-coded English warning came back to FullBackup.kt",
            literal.containsMatchIn(source)
        )
        for (key in listOf(
            "backup_restore_vault_entry_failed",
            "backup_restore_settings_failed",
        )) {
            assertTrue("FullBackup.kt does not resolve R.string.$key", source.contains("R.string.$key"))
        }
    }

    @Test
    fun `the two languages define the same restore warning keys`() {
        val en = keys(File("src/main/res/values/strings_g2honest.xml"))
        val hi = keys(File("src/main/res/values-hi/strings_g2honest.xml"))
        assertTrue("missing from values-hi: ${en - hi}", (en - hi).isEmpty())
        assertTrue("missing from values: ${hi - en}", (hi - en).isEmpty())
        val hiText = File("src/main/res/values-hi/strings_g2honest.xml").readText(Charsets.UTF_8)
        for (banned in listOf("बैकअप", "पासकोड", "फ़िंगरप्रिंट", "वॉल्ट")) {
            assertFalse("glossary term [$banned] is banned in Hindi UI", hiText.contains(banned))
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun assertNoInternals(warnings: List<String>) {
        for (warning in warnings) {
            assertFalse(
                "raw internals must never be shown: [$warning]",
                Regex("Exception|java\\.|/data/|\\.bin|C:\\\\").containsMatchIn(warning)
            )
        }
    }

    /** An archive made on a phone whose Vault was locked: the password backup carries no key. */
    private fun restoreArchiveWithoutVaultKey(): FullBackup.Report {
        val old = vaultStore()
        old.setup("246810")
        old.markConfirmationPassed()
        doc("doc_vault", "Insurance")
        old.addDocument("doc_vault")
        val archive = File(app.cacheDir, "source-for-restore.swbackup")
        VaultSession.close()
        FullBackup(app, old).write(archive, password, FullBackup.Wrap.PASSWORD)
        assertFalse(
            "this archive must have no Vault key, or the branch under test never runs",
            FullBackup(app).header(archive).hasVaultKey
        )

        // The phone that restores it already has a finished Vault of its own.
        cleanFiles()
        VaultSession.close()
        val target = vaultStore()
        target.setup("135790")
        target.markConfirmationPassed()
        val report = FullBackup(app, target).restore(archive, password)
        archive.delete()
        return report
    }

    private fun vaultStore(): VaultStore =
        VaultStore(app, ScanRepository(app), FakeVaultKeys(), VaultPasscodeGate(), 2_000)

    private fun doc(id: String, name: String) {
        val dir = File(File(app.filesDir, "documents"), id).apply { mkdirs() }
        File(dir, "meta.txt").writeText("$name\n${System.currentTimeMillis()}\nOther\n")
        File(dir, "scan.pdf").writeBytes(byteArrayOf(37, 80, 68, 70))
        File(dir, "page_000.jpg").writeBytes(ByteArray(256) { it.toByte() })
    }

    private fun cleanFiles() {
        listOf("documents", "vault2", "signatures", "stamps", "drafts").forEach {
            File(app.filesDir, it).deleteRecursively()
        }
        File(app.cacheDir, "fullbackup").deleteRecursively()
        File(app.cacheDir, "vault_open").deleteRecursively()
    }

    private fun keys(file: File): Set<String> =
        Regex("<string\\s+name=\"([^\"]+)\"").findAll(file.readText(Charsets.UTF_8))
            .map { it.groupValues[1] }
            .toSet()
}
