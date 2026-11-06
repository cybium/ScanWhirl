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
    val category: String = com.scanwhirl.app.util.CategoryRules.OTHER
)
