package com.scanwhirl.app.ui.kit

import com.scanwhirl.app.R

/** v2 UI: 03_tools_hub — the seven catalogue groups, in mockup order. */
enum class ToolGroup { CREATE, ORGANIZE, OPTIMIZE, CONVERT, EDIT, SECURE, TEXT }

enum class ToolStatus { READY, BUILD, SOON }

/** What a tool does when it is tapped; implemented by the v2 shell. */
interface ToolHost {
    /** Open the real screen (or the closest real action) for a tool. */
    fun open(tool: SwTool)

    /** The honest non-dead-end: a "Coming soon" sheet with one line. */
    fun comingSoon(tool: SwTool)
}

/**
 * v2 UI: 03_tools_hub — one catalogue entry per tool in 70_APP_DESIGN.md §3.
 *
 * Other lanes change only their own tools' [status] and [launch] lines.
 */
data class SwTool(
    val id: String,
    val group: ToolGroup,
    val title: Int,
    val icon: Int,
    val pro: Boolean,
    val status: ToolStatus,
    val keywords: String,
    val soonNote: Int? = null,
    val launch: (ToolHost, SwTool) -> Unit = { host, tool -> host.open(tool) }
)

object ToolRegistry {

    private fun ready(
        id: String,
        group: ToolGroup,
        title: Int,
        icon: Int,
        keywords: String,
        pro: Boolean = false
    ) = SwTool(id, group, title, icon, pro, ToolStatus.READY, keywords)

    private fun build(
        id: String,
        group: ToolGroup,
        title: Int,
        icon: Int,
        keywords: String,
        pro: Boolean = false,
        note: Int = R.string.soon_generic
    ) = SwTool(id, group, title, icon, pro, ToolStatus.BUILD, keywords, note)

    private fun soon(
        id: String,
        group: ToolGroup,
        title: Int,
        icon: Int,
        keywords: String,
        pro: Boolean = false,
        note: Int = R.string.soon_generic
    ) = SwTool(
        id, group, title, icon, pro, ToolStatus.SOON, keywords, note,
        launch = { host, tool -> host.comingSoon(tool) }
    )

    val all: List<SwTool> = listOf(
        // Create (8)
        ready("scan", ToolGroup.CREATE, R.string.tool_scan, R.drawable.ic_sym_document_scanner, "scan camera capture photo"),
        build("id_card", ToolGroup.CREATE, R.string.tool_id_card, R.drawable.ic_sym_badge, "id card front back aadhaar licence"),
        soon("book_pages", ToolGroup.CREATE, R.string.tool_book_pages, R.drawable.ic_sym_menu_book, "book spread split two page", pro = true, note = R.string.soon_book_pages),
        ready("photos_pdf", ToolGroup.CREATE, R.string.tool_photos_pdf, R.drawable.ic_sym_photo_library, "photos gallery images jpg png"),
        ready("import_pdf", ToolGroup.CREATE, R.string.tool_import_pdf, R.drawable.ic_sym_upload_file, "import pdf file open"),
        build("passport", ToolGroup.CREATE, R.string.tool_passport, R.drawable.ic_sym_travel_explore, "passport crop preset size"),
        soon("qr_code", ToolGroup.CREATE, R.string.tool_qr_code, R.drawable.ic_sym_qr_code_scanner, "qr code reader scan", pro = true, note = R.string.soon_qr_code),
        soon("business_card", ToolGroup.CREATE, R.string.tool_business_card, R.drawable.ic_sym_contacts, "business card contact email phone", pro = true, note = R.string.soon_business_card),

        // Organize (8)
        ready("merge", ToolGroup.ORGANIZE, R.string.tool_merge, R.drawable.ic_sym_call_merge, "merge join combine together"),
        build("split", ToolGroup.ORGANIZE, R.string.tool_split, R.drawable.ic_sym_call_split, "split range separate", pro = true),
        ready("extract", ToolGroup.ORGANIZE, R.string.tool_extract, R.drawable.ic_sym_content_cut, "extract pages pick remove"),
        build("organize_pages", ToolGroup.ORGANIZE, R.string.tool_organize_pages, R.drawable.ic_sym_view_module, "organize reorder pages move"),
        soon("insert_pages", ToolGroup.ORGANIZE, R.string.tool_insert_pages, R.drawable.ic_sym_add_photo_alternate, "insert add pages into pdf"),
        ready("duplicate", ToolGroup.ORGANIZE, R.string.tool_duplicate, R.drawable.ic_sym_content_copy, "duplicate copy clone"),
        soon("folders", ToolGroup.ORGANIZE, R.string.tool_folders, R.drawable.ic_sym_create_new_folder, "folder tag group", pro = true, note = R.string.soon_folders),
        soon("batch_rename", ToolGroup.ORGANIZE, R.string.tool_batch_rename, R.drawable.ic_sym_drive_file_rename, "batch rename many files", pro = true, note = R.string.soon_batch_rename),

        // Optimize (4)
        ready("compress", ToolGroup.OPTIMIZE, R.string.tool_compress, R.drawable.ic_sym_compress, "compress smaller size reduce mb छोटा"),
        ready("resize", ToolGroup.OPTIMIZE, R.string.tool_resize, R.drawable.ic_sym_aspect_ratio, "resize dimensions pixels"),
        ready("enhance", ToolGroup.OPTIMIZE, R.string.tool_enhance, R.drawable.ic_sym_auto_fix_high, "enhance clean clearer filter"),
        ready("bw", ToolGroup.OPTIMIZE, R.string.tool_bw, R.drawable.ic_sym_tonality, "black white scan document"),

        // Convert (8)
        build("pdf_to_images", ToolGroup.CONVERT, R.string.tool_pdf_to_images, R.drawable.ic_sym_image, "pdf to jpg png images zip"),
        build("long_image", ToolGroup.CONVERT, R.string.tool_long_image, R.drawable.ic_sym_view_day, "long image stitch pages"),
        build("to_word", ToolGroup.CONVERT, R.string.tool_to_word, R.drawable.ic_sym_description, "word docx document text", pro = true),
        soon("to_excel", ToolGroup.CONVERT, R.string.tool_to_excel, R.drawable.ic_sym_table_chart, "excel xlsx table sheet", pro = true, note = R.string.soon_to_excel),
        ready("to_text", ToolGroup.CONVERT, R.string.tool_to_text, R.drawable.ic_sym_notes, "text txt export ocr"),
        ready("zip", ToolGroup.CONVERT, R.string.tool_zip, R.drawable.ic_sym_archive, "zip archive export batch"),
        ready("searchable_pdf", ToolGroup.CONVERT, R.string.tool_searchable_pdf, R.drawable.ic_sym_picture_as_pdf, "searchable text layer ocr pdf"),
        soon("pdfa", ToolGroup.CONVERT, R.string.tool_pdfa, R.drawable.ic_sym_approval, "pdf a archive long term", pro = true, note = R.string.soon_pdfa),

        // Edit & sign (8)
        build("sign", ToolGroup.EDIT, R.string.tool_sign, R.drawable.ic_sym_signature, "sign signature ink", pro = true),
        build("add_text", ToolGroup.EDIT, R.string.tool_add_text, R.drawable.ic_sym_text_fields, "add text type write", pro = true),
        build("draw", ToolGroup.EDIT, R.string.tool_draw, R.drawable.ic_sym_highlight, "draw freehand pen", pro = true),
        build("highlight", ToolGroup.EDIT, R.string.tool_highlight, R.drawable.ic_sym_format_list_numbered, "highlight marker yellow", pro = true),
        build("watermark", ToolGroup.EDIT, R.string.tool_watermark, R.drawable.ic_sym_watermark, "watermark diagonal draft", pro = true),
        build("page_numbers", ToolGroup.EDIT, R.string.tool_page_numbers, R.drawable.ic_sym_notes_lines, "page numbers footer header", pro = true),
        build("stamp", ToolGroup.EDIT, R.string.tool_stamp, R.drawable.ic_sym_approval, "stamp paid approved date badge", pro = true),
        build("redact", ToolGroup.EDIT, R.string.tool_redact, R.drawable.ic_sym_ink_eraser, "redact black box hide remove", pro = true),

        // Secure (4)
        build("protect", ToolGroup.SECURE, R.string.tool_protect, R.drawable.ic_sym_encrypted, "protect password encrypt aes"),
        build("unlock", ToolGroup.SECURE, R.string.tool_unlock, R.drawable.ic_sym_lock_open, "unlock password remove protection"),
        build("vault", ToolGroup.SECURE, R.string.tool_vault, R.drawable.ic_sym_shield_lock, "vault private encrypted hidden"),
        build("remove_metadata", ToolGroup.SECURE, R.string.tool_remove_metadata, R.drawable.ic_sym_shield_lock, "metadata remove exif author"),

        // Text (3)
        ready("read_text", ToolGroup.TEXT, R.string.tool_read_text, R.drawable.ic_sym_text_fields, "read text ocr recognise copy"),
        soon("hindi_text", ToolGroup.TEXT, R.string.tool_hindi_text, R.drawable.ic_sym_translate, "hindi devanagari text", note = R.string.soon_hindi_text),
        soon("translate", ToolGroup.TEXT, R.string.tool_translate, R.drawable.ic_sym_translate, "translate language", pro = true, note = R.string.soon_translate)
    )

    private val byId: Map<String, SwTool> = all.associateBy { it.id }

    private val searchIndex: Map<String, String> = all.associate { tool ->
        tool.id to buildString {
            append(tool.id.replace('_', ' '))
            append(' ')
            append(tool.keywords)
        }.lowercase()
    }

    fun byId(id: String): SwTool? = byId[id]

    fun byGroup(group: ToolGroup): List<SwTool> = all.filter { it.group == group }

    /** Grouped catalogue in mockup order, including empty groups only if they ever appear. */
    fun grouped(): List<Pair<ToolGroup, List<SwTool>>> =
        ToolGroup.entries.map { group -> group to byGroup(group) }.filter { it.second.isNotEmpty() }

    /** Title + keyword search; a blank query returns nothing (the hub shows all). */
    fun search(query: String): List<SwTool> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return all.filter { searchIndex[it.id]?.contains(q) == true }
    }
}
