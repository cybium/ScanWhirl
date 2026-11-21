package com.scanwhirl.app.util

import java.io.File

/**
 * The single ordering rule for page image files, shared by every reader of a page list.
 *
 * Saved and exported pages are named `page_<n>.jpg|.jpeg`. Comparing those names as text puts
 * `page_1000.jpg` before `page_100.jpg`, so a reader that sorts lexicographically disagrees with
 * the PDF (built in numeric order) and permanently scrambles the copy when compression consumes
 * the list and renumbers it. Numeric names compare by their index; every other name keeps the old
 * stable lexicographic order and sorts after the real pages.
 */
internal object PageOrder {

    /** Numeric part of `page_001`; other names fall back to the old lexicographic order. */
    fun pageNumber(name: String): Long? {
        val dash = name.lastIndexOf('_')
        if (dash < 0 || dash == name.length - 1) return null
        for (i in dash + 1 until name.length) if (name[i] !in '0'..'9') return null
        return name.substring(dash + 1).toLongOrNull()
    }

    private fun compareNames(a: String, b: String): Int {
        val na = pageNumber(a)
        val nb = pageNumber(b)
        return when {
            na != null && nb != null -> na.compareTo(nb)   // page_1000 follows page_999
            na == null && nb == null -> a.compareTo(b)
            na != null -> -1                               // page_* first
            else -> 1
        }
    }

    /** Numeric index first, non-numeric names last in stable lexicographic order. */
    val comparator: Comparator<File> = Comparator { a, b ->
        compareNames(a.nameWithoutExtension, b.nameWithoutExtension)
    }

    /**
     * The same rule for page-name keys held as strings instead of files — OCR text maps and
     * the editor's page tabs copy their keys from page-file names, so they must follow document
     * order too (the "temp" key is non-numeric and stays last, as before).
     */
    val keyComparator: Comparator<String> = Comparator { a, b ->
        compareNames(a.substringBeforeLast('.'), b.substringBeforeLast('.'))
    }
}
