package com.scanwhirl.app.model

import java.io.File

data class ScanDocument(
    val id: String,
    val name: String,
    val createdAt: Long,
    val pageCount: Int,
    val pdfFile: File,
    val pageFiles: List<File>,
    val isLocked: Boolean = false,
    val isVaulted: Boolean = false,
    /** Globe ring this scan belongs to: Study / Receipts / IDs / Forms / Other. */
    val category: String = com.scanwhirl.app.util.CategoryRules.OTHER,
    /** Library folder this scan is in, or null for "All". */
    val folder: String? = null,
    /** Free-form library tags (any number), stored in meta.txt. */
    val tags: List<String> = emptyList(),
    /** Revision: max lastModified of meta.txt, scan.pdf, locked.meta, vault.flag, page files. */
    val revision: Long = 0L,
    /** True when text_layer.flag exists in the document directory. */
    val isSearchable: Boolean = false,
    /** Bytes of the PDF (or of all pages when there is no PDF): cached while listing. */
    val cachedSize: Long = 0L,
    /** Newest content modification time (the [revision]): cached while listing. */
    val cachedModified: Long = 0L,
    val contentKind: ContentKind = ContentKind.RASTER,
    val pageRefs: List<PageRef> = emptyList(),
    val contentGeneration: String? = null,
    val contentSha256: String? = null,
    val storageBytes: Long = cachedSize
) {
    val authoritativePdf: File get() = pdfFile
}
