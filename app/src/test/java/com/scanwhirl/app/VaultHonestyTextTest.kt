package com.scanwhirl.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * G2-HONESTY (D9 5f): the Vault screen used to say "Locks itself when you leave", which is not
 * what the app does — [com.scanwhirl.app.store.vault.VaultSessionPolicy] relocks 2 minutes after
 * the app goes to the background — and it never told a reader that a fingerprint or passcode
 * opens it. The device report (qa/device_build9 item 5f) saw exactly that on the phone.
 *
 * These are resource guards, not layout tests: the tab renders these exact strings
 * (ui/shell/VaultScreen.kt), so a wording edit cannot quietly bring the false promise back.
 */
class VaultHonestyTextTest {

    private val resDir = File("src/main/res")

    @Test
    fun `the vault tab promises fingerprint or passcode and the two minute relock`() {
        val en = strings("values/strings_l1.xml")
        val hi = strings("values-hi/strings_l1.xml")

        val enText = en["vault_tab_relock"] ?: error("vault_tab_relock is missing from values")
        val hiText = hi["vault_tab_relock"] ?: error("vault_tab_relock is missing from values-hi")
        val enBare = enText.lowercase()
        for (word in listOf("fingerprint", "passcode", "2 min")) {
            assertTrue("the relock line must name [$word], got: [$enText]", enBare.contains(word))
        }
        assertFalse(
            "the old false promise must be gone, got: [$enText]",
            enBare.contains("locks itself when you leave")
        )
        for (word in listOf("उँगली", "गुप्त नंबर", "2 मिनट")) {
            assertTrue("हिन्दी पंक्ति में [$word] होना चाहिए, मिला: [$hiText]", hiText.contains(word))
        }
        assertFalse(
            "पुराना गलत वादा नहीं लौटना चाहिए, मिला: [$hiText]",
            hiText.contains("ऐप छोड़ने पर अपने आप ताला")
        )
    }

    @Test
    fun `no vault description claims an instant lock or a secure chip`() {
        val offenders = mutableListOf<String>()
        val forbidden = listOf(
            "locks itself when you leave",
            "lock itself when you leave",
            "secure chip",
            "hardware security module",
            "सुरक्षा चिप",
            "तुरंत ताला",
        )
        files().forEach { file ->
            for ((name, text) in entries(file)) {
                val bare = text.lowercase()
                for (term in forbidden) {
                    if (bare.contains(term)) offenders.add("${file.name}:$name = [$text]")
                }
            }
        }
        assertTrue("instant-lock / secure-chip claims came back:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    // ------------------------------------------------------------------ helpers

    private fun strings(relative: String): Map<String, String> = entries(File(resDir, relative)).toMap()

    /** (name, text) for every string and plurals item in [file]. */
    private fun entries(file: File): List<Pair<String, String>> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
        }
        val root = factory.newDocumentBuilder().parse(file).documentElement
        val out = mutableListOf<Pair<String, String>>()
        val nodes = root.childNodes
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            when (el.tagName) {
                "string" -> out.add(el.getAttribute("name") to el.textContent)
                "plurals" -> {
                    val items = el.getElementsByTagName("item")
                    for (j in 0 until items.length) {
                        out.add(el.getAttribute("name") to (items.item(j) as Element).textContent)
                    }
                }
            }
        }
        return out
    }

    private fun files(): List<File> = resDir.listFiles()
        .orEmpty()
        .filter { it.isDirectory && it.name.startsWith("values") }
        .flatMap { dir ->
            dir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("strings") && it.name.endsWith(".xml") }
        }
        .sortedBy { it.invariantSeparatorsPath }
}
