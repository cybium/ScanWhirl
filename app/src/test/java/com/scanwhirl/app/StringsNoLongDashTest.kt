package com.scanwhirl.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner rule: no long dashes (em dash U+2014, en dash U+2013) in anything a user reads. They read
 * as machine-written. XML comments are not user-visible and are ignored; only string and plurals
 * item text (English and Hindi) is checked.
 */
class StringsNoLongDashTest {

    private val dirs = listOf(File("src/main/res/values"), File("src/main/res/values-hi"))

    @Test
    fun `no user-visible string contains a long dash`() {
        val offenders = mutableListOf<String>()
        val factory = DocumentBuilderFactory.newInstance().apply { isIgnoringComments = true }
        for (dir in dirs) {
            val files = dir.listFiles { f -> f.name.endsWith(".xml") }.orEmpty()
            assertTrue("no string files found in ${dir.absolutePath}", files.isNotEmpty() || dir.name != "values")
            for (file in files) {
                val root = factory.newDocumentBuilder().parse(file).documentElement
                val nodes = root.childNodes
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i)
                    if (node.nodeName != "string" && node.nodeName != "plurals") continue
                    val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue
                    val text = node.textContent
                    if (text.contains('—') || text.contains('–')) {
                        offenders += "${dir.name}/${file.name}: $name"
                    }
                }
            }
        }
        assertTrue("long dash in user-visible strings: $offenders", offenders.isEmpty())
    }
}
