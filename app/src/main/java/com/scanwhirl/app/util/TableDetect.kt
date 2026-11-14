package com.scanwhirl.app.util

import com.scanwhirl.app.model.OcrPage
import kotlin.math.abs
import kotlin.math.max

/**
 * v2 UI: 03_tools_hub — finds a table in one page's stored OCR geometry.
 *
 * Lines are grouped into rows by their vertical centre (baseline tolerance) and their left edges
 * are clustered into columns by horizontal gaps. A page is a table only when it has at least
 * [MIN_COLUMNS] columns and [MIN_ROWS] rows; anything else is ordinary text and is exported one
 * line per cell by the caller.
 */
object TableDetect {

    const val MIN_COLUMNS = 2
    const val MIN_ROWS = 3

    data class Table(val rows: List<List<String>>) {
        val columns: Int get() = rows.maxOfOrNull { it.size } ?: 0
    }

    fun detect(page: OcrPage): Table? {
        val lines = page.lines.filter { it.text.isNotBlank() && it.right > it.left && it.bottom > it.top }
        if (lines.size < MIN_ROWS) return null

        val medianHeight = lines.map { (it.bottom - it.top).coerceAtLeast(1) }.sorted()
            .let { it[it.size / 2] }
        val rowTolerance = max(4, medianHeight / 2)
        val columnTolerance = max(24, (page.width.takeIf { it > 0 } ?: 1000) / 40)

        val rows = mutableListOf<MutableList<com.scanwhirl.app.model.OcrLine>>()
        for (line in lines.sortedWith(compareBy({ it.top }, { it.left }))) {
            val centre = (line.top + line.bottom) / 2
            val row = rows.firstOrNull { existing ->
                val rowCentre = existing.sumOf { (it.top + it.bottom) / 2 } / existing.size
                abs(rowCentre - centre) <= rowTolerance
            }
            if (row == null) rows += mutableListOf(line) else row += line
        }
        if (rows.size < MIN_ROWS) return null

        val lefts = lines.map { it.left }.sorted()
        val columns = mutableListOf<MutableList<Int>>()
        for (left in lefts) {
            val current = columns.lastOrNull()
            when {
                current == null -> columns += mutableListOf(left)
                left - current.last() <= columnTolerance -> current += left
                else -> columns += mutableListOf(left)
            }
        }
        // Anchor each column at its median left edge.
        val anchors = columns.map { group ->
            group.sorted().let { it[it.size / 2] }
        }
        if (anchors.size < MIN_COLUMNS) return null

        val grid = rows.sortedBy { row -> row.minOf { it.top } }.map { row ->
            val cells = MutableList(anchors.size) { "" }
            for (line in row.sortedBy { it.left }) {
                val column = anchors.indices.minByOrNull { abs(anchors[it] - line.left) } ?: 0
                cells[column] = if (cells[column].isEmpty()) line.text else "${cells[column]} ${line.text}"
            }
            cells
        }
        // A real table has at least two filled cells in most rows; a paragraph block does not.
        val filledRows = grid.count { cells -> cells.count { it.isNotEmpty() } >= 2 }
        if (filledRows < MIN_ROWS) return null
        return Table(grid)
    }
}
