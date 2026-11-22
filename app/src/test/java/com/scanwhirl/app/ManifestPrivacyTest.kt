package com.scanwhirl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * P lane (source-side companion to the merged-manifest CI gate). Transitive Play/ML Kit
 * libraries declare INTERNET and ACCESS_NETWORK_STATE, so the shipped manifest must strip
 * them with tools:node="remove"; no other forbidden permission may be declared at all.
 * tools/release/check_release_manifest.py checks the AGP-merged release manifest in CI;
 * this test guards the source file so a regression is caught in the fast unit suite too.
 */
class ManifestPrivacyTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val toolsNs = "http://schemas.android.com/tools"

    private val mustBeRemoved = listOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
    )

    @Test
    fun `network permissions are explicitly stripped from the merged manifest`() {
        val declared = permissionNodes()
        for (name in mustBeRemoved) {
            val node = checkNotNull(declared[name]) {
                "$name must be declared with tools:node=remove"
            }
            assertEquals(
                "$name must be stripped with tools:node=remove",
                "remove",
                node.getAttributeNS(toolsNs, "node")
            )
        }
    }

    @Test
    fun `no forbidden permission is declared without a remove marker`() {
        for ((name, node) in permissionNodes()) {
            if (name.startsWith("android.permission.FOREGROUND_SERVICE")) {
                fail("FOREGROUND_SERVICE family needs an owner decision, not a 1.3.x permission: $name")
            }
            if (name in mustBeRemoved) continue // covered by the explicit remove test above
            if (name == "android.permission.AD_ID" ||
                name == "com.google.android.gms.permission.AD_ID" ||
                name == "android.permission.QUERY_ALL_PACKAGES"
            ) {
                assertEquals(
                    "$name must be stripped with tools:node=remove",
                    "remove",
                    node.getAttributeNS(toolsNs, "node")
                )
            }
        }
    }

    private fun permissionNodes(): Map<String, Element> {
        val file = File("src/main/AndroidManifest.xml")
        assertTrue("${file.path} is missing", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(file)
        val out = LinkedHashMap<String, Element>()
        for (tag in listOf("uses-permission", "uses-permission-sdk-23")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as Element
                val name = element.getAttributeNS(androidNs, "name")
                assertTrue("$tag without android:name", name.isNotEmpty())
                out[name] = element
            }
        }
        return out
    }
}
