package com.scanwhirl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * G2-HONESTY (F-03, lead decision): on Android 12+ `android:allowBackup="false"` alone can
 * still allow device-to-device transfer on some manufacturers. The app must therefore declare
 * `android:dataExtractionRules` and exclude every domain in BOTH sections (cloud-backup and
 * device-transfer); API 30 and older keep the equivalent `android:fullBackupContent` exclusions.
 * These guards parse the real files, so dropping an attribute or a domain fails the gate.
 */
class DataExtractionRulesTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private val domains = listOf("root", "file", "database", "sharedpref", "external")

    @Test
    fun `the manifest declares data extraction rules and keeps backups off`() {
        val app = applicationElement(File("src/main/AndroidManifest.xml"))
        assertEquals(
            "Android 12+ needs an explicit data extraction policy",
            "@xml/data_extraction_rules",
            app.getAttributeNS(androidNs, "dataExtractionRules")
        )
        assertEquals(
            "allowBackup must stay false",
            "false",
            app.getAttributeNS(androidNs, "allowBackup")
        )
        assertEquals(
            "API 30 and older need the matching full-backup exclusions",
            "@xml/backup_rules",
            app.getAttributeNS(androidNs, "fullBackupContent")
        )
    }

    @Test
    fun `cloud backup and device transfer exclude every app domain`() {
        val sections = sections(File("src/main/res/xml/data_extraction_rules.xml"))
        assertEquals(
            "both extraction sections must be present",
            setOf("cloud-backup", "device-transfer"),
            sections.keys
        )
        for ((section, excluded) in sections) {
            assertTrue(
                "$section must exclude every domain; missing: ${domains - excluded}",
                excluded.containsAll(domains)
            )
        }
    }

    @Test
    fun `the older full backup rules exclude every app domain`() {
        val file = File("src/main/res/xml/backup_rules.xml")
        assertTrue("${file.path} is missing", file.isFile)
        val root = parse(file).documentElement
        assertEquals("full-backup-content", root.tagName)
        val excluded = excludes(root)
        assertTrue("missing: ${domains - excluded}", excluded.containsAll(domains))
    }

    // ------------------------------------------------------------------ helpers

    private fun applicationElement(manifest: File): Element {
        val root = parse(manifest).documentElement
        val apps = root.getElementsByTagName("application")
        assertTrue("the manifest has no <application>", apps.length > 0)
        return apps.item(0) as Element
    }

    /** section tag -> set of excluded domains, for cloud-backup and device-transfer. */
    private fun sections(file: File): Map<String, Set<String>> {
        assertTrue("${file.path} is missing", file.isFile)
        val root = parse(file).documentElement
        assertEquals("data-extraction-rules", root.tagName)
        val out = linkedMapOf<String, Set<String>>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            if (el.tagName != "cloud-backup" && el.tagName != "device-transfer") continue
            assertFalse("$el must not include anything", hasInclude(el))
            out[el.tagName] = excludes(el)
        }
        return out
    }

    private fun excludes(section: Element): Set<String> {
        val out = mutableSetOf<String>()
        val children = section.childNodes
        for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            if (el.tagName != "exclude") continue
            val domain = el.getAttribute("domain")
            assertTrue("an <exclude> has no domain", domain.isNotEmpty())
            out.add(domain)
        }
        return out
    }

    private fun hasInclude(section: Element): Boolean {
        val children = section.childNodes
        for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            if (el.tagName == "include") return true
        }
        return false
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(file)
}
