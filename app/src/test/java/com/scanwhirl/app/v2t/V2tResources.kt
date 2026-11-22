package com.scanwhirl.app.v2t

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Reads the lane's dedicated EN/HI resource files from disk (no Android compile). */
internal object V2tResources {
    fun en(): Map<String, String> = read("src/main/res/values/strings_v2t.xml")
    fun hi(): Map<String, String> = read("src/main/res/values-hi/strings_v2t.xml")

    private fun read(path: String): Map<String, String> {
        val file = File(path)
        check(file.isFile) { "Missing lane resource file: $path" }
        val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
            .newDocumentBuilder().parse(file).documentElement
        val out = LinkedHashMap<String, String>()
        val nodes = root.childNodes
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? org.w3c.dom.Element ?: continue
            if (el.tagName == "string" && el.getAttribute("name").isNotEmpty()) {
                out[el.getAttribute("name")] = el.textContent.trim()
            }
        }
        return out
    }
}
