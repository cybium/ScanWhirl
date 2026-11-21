package com.scanwhirl.app.ux130.l3

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * L3 lane tests read the dedicated lane resource files from disk. This keeps the
 * EN/HI contract checks free of Android resource compilation, so they fail with a
 * clear assertion (RED) before the lane files exist and pass (GREEN) once added.
 * Nothing here parses shared strings: the lane owns only strings_ux130_l3.xml.
 */
internal object L3Resources {
    const val BASE = "strings_ux130_l3"

    fun en(): Map<String, String> = read("src/main/res/values/$BASE.xml")

    fun hi(): Map<String, String> = read("src/main/res/values-hi/$BASE.xml")

    fun ids(): Set<String> = parse("src/main/res/values/ids_ux130_l3.xml") { node, _ ->
        node.getAttribute("name").takeIf { it.startsWith("ux130_l3_") }.orEmpty()
    }.values.filter { it.isNotEmpty() }.toSet()

    private fun read(path: String): Map<String, String> = parse(path) { _, text ->
        text.trim()
    }.filter { it.key.isNotEmpty() }

    private fun parse(path: String, value: (org.w3c.dom.Element, String) -> String): Map<String, String> {
        val file = File(path)
        check(file.isFile) { "Missing lane resource file: $path" }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        val root = factory.newDocumentBuilder().parse(file).documentElement
        val out = LinkedHashMap<String, String>()
        val nodes = root.childNodes
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? org.w3c.dom.Element ?: continue
            when (el.tagName) {
                "string" -> {
                    val name = el.getAttribute("name")
                    if (name.isNotEmpty()) out[name] = value(el, el.textContent)
                }
                "plurals" -> {
                    val items = el.getElementsByTagName("item")
                    for (j in 0 until items.length) {
                        val item = items.item(j) as org.w3c.dom.Element
                        val name = "${el.getAttribute("name")}[${item.getAttribute("quantity")}]"
                        if (el.getAttribute("name").isNotEmpty()) out[name] = value(item, item.textContent)
                    }
                }
                "item" -> {
                    val name = el.getAttribute("name")
                    if (name.startsWith("ux130_l3_")) out[name] = value(el, el.textContent)
                }
            }
        }
        return out
    }
}
