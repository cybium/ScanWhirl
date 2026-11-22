package com.scanwhirl.app.tools

import com.scanwhirl.app.util.PageRanges
import java.util.Locale

/**
 * FF-J: the pure setup options for "PDF to JPG".
 *
 * The old sheet exported every page and made a ZIP whenever the document had more than one
 * page. These options keep the person's explicit page choice and make separate files the
 * default: a ZIP is an intentional bundle, never a side effect of selecting two pages.
 * No Android UI and no I/O here, so the rules are testable on their own.
 */
object PdfToJOptions {

    enum class Layout { SEPARATE, ZIP }

    /** 1-based user pages (`2`, `1,3`) -> sorted, unique, zero-based page indices. */
    fun parsePages(text: String, pageCount: Int): List<Int> =
        PageRanges.parse(text, pageCount).flatMap { it.indices() }.distinct().sorted()

    /** A named image per page, so "pages 1+3" gives two individually openable JPGs. */
    fun fileName(base: String, pageOneBased: Int, extension: String): String =
        "$base page $pageOneBased.$extension"

    fun zipName(base: String): String = "$base pages.zip"

    /**
     * "JPG · 630 × 810 · 142 KB". [template] comes from strings_ffj.xml so the receipt is
     * localised by the caller; KB is rounded from the bytes actually written, never estimated.
     */
    fun receipt(template: String, label: String, width: Int, height: Int, bytes: Long): String =
        String.format(Locale.ROOT, template, label, width, height, (bytes + 500) / 1000)
}
