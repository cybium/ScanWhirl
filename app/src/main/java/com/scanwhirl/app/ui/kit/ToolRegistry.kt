package com.scanwhirl.app.ui.kit

import com.scanwhirl.app.R
import com.scanwhirl.app.ui.tools.ToolLaunch
import java.util.Locale

/** v2 UI: 03_tools_hub — the seven catalogue groups, in mockup order. */
enum class ToolGroup { CREATE, ORGANIZE, OPTIMIZE, CONVERT, EDIT, SECURE, TEXT }

enum class ToolStatus { READY, BUILD, SOON }

/** What a tool does when it is tapped; implemented by the v2 shell. */
interface ToolHost {
    /** Open the real screen (or the closest real action) for a tool. */
    fun open(tool: SwTool)

    /** The honest non-dead-end: a "Coming soon" sheet with one line. */
    fun comingSoon(tool: SwTool)

    /**
     * v2 UI: 06_pro — a Pro tool tapped in a Free build. Return true when the
     * host handled it (the shell shows the Pro screen); false lets it run.
     */
    fun proGate(tool: SwTool): Boolean = false
}

/**
 * The one place a tool tap is dispatched: Free + Pro tool goes to the Pro
 * screen, everything else runs its launch. Tools hub, Home quick row and the
 * Library create row all funnel here.
 */
fun ToolHost.launchTool(tool: SwTool) {
    if (tool.pro && proGate(tool)) return
    tool.launch(this, tool)
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
        pro: Boolean = false,
        launch: ((ToolHost, SwTool) -> Unit)? = null
    ) = SwTool(
        id, group, title, icon, pro, ToolStatus.READY, keywords,
        launch = launch ?: { host, tool -> host.open(tool) }
    )

    private fun build(
        id: String,
        group: ToolGroup,
        title: Int,
        icon: Int,
        keywords: String,
        pro: Boolean = false,
        note: Int = R.string.soon_generic,
        launch: ((ToolHost, SwTool) -> Unit)? = null
    ) = SwTool(
        id, group, title, icon, pro, ToolStatus.BUILD, keywords, note,
        launch = launch ?: { host, tool -> host.open(tool) }
    )

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
        ready("id_card", ToolGroup.CREATE, R.string.tool_id_card, R.drawable.ic_sym_badge, "id card front back aadhaar licence", launch = { host, tool -> ToolLaunch.start(host, tool.id) }),
        // L3: built tool — the launch starts the Book pages screen (status + launch only).
        SwTool(
            id = "book_pages",
            group = ToolGroup.CREATE,
            title = R.string.tool_book_pages,
            icon = R.drawable.ic_sym_menu_book,
            pro = true,
            status = ToolStatus.READY,
            keywords = "book spread split two page",
            launch = { host, tool ->
                val context = host as? android.content.Context
                if (context != null) {
                    context.startActivity(com.scanwhirl.app.ui.book.BookPagesActivity.intent(context))
                } else {
                    host.open(tool)
                }
            }
        ),
        ready("photos_pdf", ToolGroup.CREATE, R.string.tool_photos_pdf, R.drawable.ic_sym_photo_library, "photos gallery images jpg png"),
        ready("import_pdf", ToolGroup.CREATE, R.string.tool_import_pdf, R.drawable.ic_sym_upload_file, "import pdf file open"),
        ready("passport", ToolGroup.CREATE, R.string.tool_passport, R.drawable.ic_sym_travel_explore, "passport crop preset size", launch = { host, tool -> ToolLaunch.start(host, tool.id) }),
        SwTool(
            "qr_code", ToolGroup.CREATE, R.string.tool_qr_code, R.drawable.ic_sym_qr_code_scanner,
            true, ToolStatus.READY, "qr code reader scan link wifi contact phone email text",
            launch = { host, tool ->
                val activity = host as? android.app.Activity
                if (activity != null) {
                    com.scanwhirl.app.ui.qr.QrToolLauncher.open(activity)
                } else {
                    host.open(tool)
                }
            }
        ),
        ready("business_card", ToolGroup.CREATE, R.string.tool_business_card, R.drawable.ic_sym_contacts, "business card contact email phone", pro = true, launch = { host, tool -> ToolLaunch.start(host, tool.id) }),

        // Organize (8)
        ready("merge", ToolGroup.ORGANIZE, R.string.tool_merge, R.drawable.ic_sym_call_merge, "merge join combine together", launch = { host, tool -> ToolLaunch.start(host, tool.id) }),
        ready("split", ToolGroup.ORGANIZE, R.string.tool_split, R.drawable.ic_sym_call_split, "split range separate", pro = true, launch = { host, tool -> ToolLaunch.start(host, tool.id) }),
        ready("extract", ToolGroup.ORGANIZE, R.string.tool_extract, R.drawable.ic_sym_content_cut, "extract pages pick remove", launch = { host, tool -> ToolLaunch.start(host, tool.id) }),
        build("organize_pages", ToolGroup.ORGANIZE, R.string.tool_organize_pages, R.drawable.ic_sym_view_module, "organize reorder pages move"),
        soon("insert_pages", ToolGroup.ORGANIZE, R.string.tool_insert_pages, R.drawable.ic_sym_note_add, "insert add pages into pdf"),
        ready("duplicate", ToolGroup.ORGANIZE, R.string.tool_duplicate, R.drawable.ic_sym_content_copy, "duplicate copy clone", launch = { host, tool -> ToolLaunch.start(host, tool.id) }),
        ready("folders", ToolGroup.ORGANIZE, R.string.tool_folders, R.drawable.ic_sym_folder_open, "folder tag group", pro = true),
        ready("batch_rename", ToolGroup.ORGANIZE, R.string.tool_batch_rename, R.drawable.ic_sym_drive_file_rename, "batch rename many files", pro = true),

        // Optimize (4)
        ready("compress", ToolGroup.OPTIMIZE, R.string.tool_compress, R.drawable.ic_sym_compress, "compress smaller size reduce mb छोटा", launch = { host, tool -> ToolLaunch.start(host, tool.id) }),
        ready("resize", ToolGroup.OPTIMIZE, R.string.tool_resize, R.drawable.ic_sym_aspect_ratio, "resize dimensions pixels"),
        ready("enhance", ToolGroup.OPTIMIZE, R.string.tool_enhance, R.drawable.ic_sym_auto_fix_high, "enhance clean clearer filter"),
        ready("bw", ToolGroup.OPTIMIZE, R.string.tool_bw, R.drawable.ic_sym_tonality, "black white scan document"),

        // Convert (8)
        ready("pdf_to_images", ToolGroup.CONVERT, R.string.tool_pdf_to_images, R.drawable.ic_sym_image, "pdf to jpg png images zip", launch = { host, tool -> ToolLaunch.start(host, tool.id) }),
        ready("long_image", ToolGroup.CONVERT, R.string.tool_long_image, R.drawable.ic_sym_view_day, "long image stitch pages", launch = { host, tool -> ToolLaunch.start(host, tool.id) }),
        ready("to_word", ToolGroup.CONVERT, R.string.tool_to_word, R.drawable.ic_sym_description, "word docx document text", pro = true, launch = { host, tool -> ToolLaunch.start(host, tool.id) }),
        ready("to_powerpoint", ToolGroup.CONVERT, R.string.tools_tool_to_powerpoint, R.drawable.ic_sym_slideshow, "powerpoint pptx slides presentation deck", pro = true, launch = { host, tool -> ToolLaunch.start(host, tool.id) }),
        soon("to_excel", ToolGroup.CONVERT, R.string.tool_to_excel, R.drawable.ic_sym_table_chart, "excel xlsx table sheet", pro = true, note = R.string.soon_to_excel),
        ready("to_text", ToolGroup.CONVERT, R.string.tool_to_text, R.drawable.ic_sym_notes, "text txt export ocr"),
        ready("zip", ToolGroup.CONVERT, R.string.tool_zip, R.drawable.ic_sym_archive, "zip archive export batch", launch = { host, tool -> ToolLaunch.start(host, tool.id) }),
        ready("searchable_pdf", ToolGroup.CONVERT, R.string.tool_searchable_pdf, R.drawable.ic_sym_picture_as_pdf, "searchable text layer ocr pdf"),
        soon("pdfa", ToolGroup.CONVERT, R.string.tool_pdfa, R.drawable.ic_sym_approval, "pdf a archive long term", pro = true, note = R.string.soon_pdfa),

        // Edit & sign (8) — L5: built in ui/stamp; a document is picked, then the editor opens armed.
        ready("sign", ToolGroup.EDIT, R.string.tool_sign, R.drawable.ic_sym_signature, "sign signature ink", pro = true).copy(
            launch = { host, tool -> (host as? android.app.Activity)?.let { com.scanwhirl.app.ui.stamp.StampToolLauncher.open(it, "sign") } ?: host.comingSoon(tool) }
        ),
        ready("add_text", ToolGroup.EDIT, R.string.tool_add_text, R.drawable.ic_sym_text_fields, "add text type write", pro = true).copy(
            launch = { host, tool -> (host as? android.app.Activity)?.let { com.scanwhirl.app.ui.stamp.StampToolLauncher.open(it, "add_text") } ?: host.comingSoon(tool) }
        ),
        ready("draw", ToolGroup.EDIT, R.string.tool_draw, R.drawable.ic_sym_draw, "draw freehand pen", pro = true).copy(
            launch = { host, tool -> (host as? android.app.Activity)?.let { com.scanwhirl.app.ui.stamp.StampToolLauncher.open(it, "draw") } ?: host.comingSoon(tool) }
        ),
        ready("highlight", ToolGroup.EDIT, R.string.tool_highlight, R.drawable.ic_sym_ink_highlighter, "highlight marker yellow", pro = true).copy(
            launch = { host, tool -> (host as? android.app.Activity)?.let { com.scanwhirl.app.ui.stamp.StampToolLauncher.open(it, "highlight") } ?: host.comingSoon(tool) }
        ),
        ready("watermark", ToolGroup.EDIT, R.string.tool_watermark, R.drawable.ic_sym_watermark, "watermark diagonal draft", pro = true).copy(
            launch = { host, tool -> (host as? android.app.Activity)?.let { com.scanwhirl.app.ui.stamp.StampToolLauncher.open(it, "watermark") } ?: host.comingSoon(tool) }
        ),
        ready("page_numbers", ToolGroup.EDIT, R.string.tool_page_numbers, R.drawable.ic_sym_format_list_numbered, "page numbers footer header", pro = true).copy(
            launch = { host, tool -> (host as? android.app.Activity)?.let { com.scanwhirl.app.ui.stamp.StampToolLauncher.open(it, "page_numbers") } ?: host.comingSoon(tool) }
        ),
        ready("stamp", ToolGroup.EDIT, R.string.tool_stamp, R.drawable.ic_sym_approval, "stamp paid approved date badge", pro = true).copy(
            launch = { host, tool -> (host as? android.app.Activity)?.let { com.scanwhirl.app.ui.stamp.StampToolLauncher.open(it, "stamp") } ?: host.comingSoon(tool) }
        ),
        ready("redact", ToolGroup.EDIT, R.string.tool_redact, R.drawable.ic_sym_ink_eraser, "redact black box hide remove", pro = true).copy(
            launch = { host, tool -> (host as? android.app.Activity)?.let { com.scanwhirl.app.ui.stamp.StampToolLauncher.open(it, "redact") } ?: host.comingSoon(tool) }
        ),

        // Secure (4)
        build("protect", ToolGroup.SECURE, R.string.tool_protect, R.drawable.ic_sym_encrypted, "protect password encrypt aes"),
        build("unlock", ToolGroup.SECURE, R.string.tool_unlock, R.drawable.ic_sym_lock_open, "unlock password remove protection"),
        SwTool(
            "vault", ToolGroup.SECURE, R.string.tool_vault, R.drawable.ic_sym_shield_lock,
            false, ToolStatus.READY, "vault private encrypted hidden",
            launch = { host, tool ->
                // A real host is the shell (a Context): open L2's Vault screen. Any other
                // host (tests) still gets a real action through the normal open path.
                val context = host as? android.content.Context
                if (context != null) {
                    context.startActivity(com.scanwhirl.app.ui.vault.VaultActivity.intent(context))
                } else {
                    host.open(tool)
                }
            }
        ),
        ready("remove_metadata", ToolGroup.SECURE, R.string.tool_remove_metadata, R.drawable.ic_sym_visibility_off, "metadata remove exif author", launch = { host, tool -> ToolLaunch.start(host, tool.id) }),

        // Text (2)
        ready("read_text", ToolGroup.TEXT, R.string.tool_read_text, R.drawable.ic_sym_notes_lines, "read text ocr recognise copy"),
        soon("hindi_text", ToolGroup.TEXT, R.string.tool_hindi_text, R.drawable.ic_sym_language, "hindi devanagari text", note = R.string.soon_hindi_text)
    )

    private val byId: Map<String, SwTool> = all.associateBy { it.id }

    fun byId(id: String): SwTool? = byId[id]

    fun byGroup(group: ToolGroup): List<SwTool> = all.filter { it.group == group }

    /** Grouped catalogue in mockup order, including empty groups only if they ever appear. */
    fun grouped(): List<Pair<ToolGroup, List<SwTool>>> =
        ToolGroup.entries.map { group -> group to byGroup(group) }.filter { it.second.isNotEmpty() }

    /** Title + keyword search; a blank query returns nothing (the hub shows all). */
    fun search(query: String): List<SwTool> = rankedSearch(query) { null }

    /**
     * v2 UI: 03_tools_hub — the hub's search with the real (localised) titles.
     * Ranking: title starts-with, then a title word starts-with, then a keyword
     * word starts-with, then any substring; ties keep catalogue order.
     */
    fun search(context: android.content.Context, query: String): List<SwTool> =
        rankedSearch(query) { context.getString(it.title) }

    private fun rankedSearch(query: String, titleOf: (SwTool) -> String?): List<SwTool> {
        val q = query.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) return emptyList()
        val hits = all.withIndex().mapNotNull { (index, tool) ->
            val title = (titleOf(tool) ?: tool.id.replace('_', ' ')).lowercase(Locale.getDefault())
            val idWords = tool.id.replace('_', ' ')
            val keywords = tool.keywords.lowercase(Locale.getDefault())
            val rank = when {
                title.startsWith(q) -> 0
                title.split(' ').any { it.startsWith(q) } -> 1
                (keywords + " " + idWords).split(' ').any { it.startsWith(q) } -> 2
                title.contains(q) || keywords.contains(q) || idWords.contains(q) -> 3
                else -> null
            }
            rank?.let { index to it }
        }
        return hits.sortedWith(compareBy({ it.second }, { it.first })).map { all[it.first] }
    }
}
