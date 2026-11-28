package com.scanwhirl.app.model

enum class ContentKind { RASTER, DIGITAL }
enum class PageKind { DIGITAL, IMAGE, REDACTED_IMAGE }

/** PDF coordinates in points, before display rotation. */
data class PageBox(val left: Double, val bottom: Double, val right: Double, val top: Double)

/** Stable page identity independent of preview filenames or current position. */
data class PageRef(
    val id: String,
    val index: Int,
    val mediaBox: PageBox,
    val cropBox: PageBox,
    val rotation: Int,
    val kind: PageKind,
    val originIndex: Int? = null
)
