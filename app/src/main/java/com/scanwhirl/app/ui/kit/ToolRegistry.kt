package com.scanwhirl.app.ui.kit

import com.scanwhirl.app.R
import com.scanwhirl.app.ui.tools.ToolLaunch
import com.scanwhirl.app.util.Prefs
import java.util.Locale

/** v2 UI: 03_tools_hub — the seven catalogue groups, in mockup order. */
enum class ToolGroup { CREATE, ORGANIZE, OPTIMIZE, CONVERT, EDIT, SECURE, TEXT }

/**
 * B4-TOOLS: the seven jobs from UI_PLAN §0b, in the order a person reads them. The Tools tab
 * is organised by what the person wants done, not by this catalogue's internal groups; the
 * tools themselves stay in [ToolGroup] for the rest of the app.
 *
 * [pro] is true for a job whose tools are all Pro work (UI_PLAN writes "Sign and mark up
 * (Pro)"). The badge is only drawn in a Free build, like every other Pro marker.
 */
enum class ToolJob(val title: Int, val pro: Boolean = false) {
    SMALLER(R.string.tools_job_smaller),
    ORGANISE(R.string.tools_job_organise),
    MARKUP(R.string.tools_job_markup, pro = true),
    PRIVATE(R.string.tools_job_private),
    TEXT(R.string.tools_job_text),
    FORMAT(R.string.tools_job_format),
    NEW(R.string.tools_job_new)
}

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
 * screen, everything else runs its launch. Tools hub, its Recent row, Home
 * quick row and the Library create row all funnel here.
 *
 * T1 (owner-approved 2026-11-20): a tool that really launches is recorded as
 * recent here — the single choke point, so every door records the same way. A
 * Pro tool a Free person was gated away from is not recorded (it never ran),
 * and a Coming-soon entry has no row to tap.
 */
fun ToolHost.launchTool(tool: SwTool) {
    if (tool.pro && proGate(tool)) return
    (this as? android.content.Context)?.let { Prefs.recordRecentTool(it, tool.id) }
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
        // Create (5)
        ready("scan", ToolGroup.CREATE, R.string.tool_scan, R.drawable.ic_sym_document_scanner, "scan camera capture photo"),
        ready("id_card", ToolGroup.CREATE, R.string.tool_id_card, R.drawable.ic_sym_badge, "id card front back aadhaar licence", launch = { host, tool -> ToolLaunch.start(host, tool.id) }),
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

        // Organize (8)
        ready("merge", ToolGroup.ORGANIZE, R.string.tool_merge, R.drawable.ic_sym_call_merge, "merge join combine together", launch = { host, tool -> ToolLaunch.start(host, tool.id) }),
        ready("split", ToolGroup.ORGANIZE, R.string.tool_split, R.drawable.ic_sym_call_split, "split range separate", pro = true, launch = { host, tool -> ToolLaunch.start(host, tool.id) }),
        ready("extract", ToolGroup.ORGANIZE, R.string.tool_extract, R.drawable.ic_sym_content_cut, "extract pages pick remove", launch = { host, tool -> ToolLaunch.start(host, tool.id) }),
        // Owner 11-16: Organize pages ships its document grid, but page reorder is not reachable
        // there yet; the tile is Coming soon instead of a dead reorder promise.
        soon(
            "organize_pages", ToolGroup.ORGANIZE, R.string.tool_organize_pages,
            R.drawable.ic_sym_view_module, "organize reorder pages move",
            note = R.string.soon_organize_pages
        ),
        ready("insert_pages", ToolGroup.ORGANIZE, R.string.tool_insert_pages, R.drawable.ic_sym_add, "insert add pages into pdf", launch = { host, tool ->
            val activity = host as? android.app.Activity
            if (activity == null) {
                host.comingSoon(tool)
            } else {
                com.scanwhirl.app.ui.DocumentViewerActivity.openInsertPicker(activity)
            }
        }),
        // PRO_PLAN line 10-11 (accepted 2026-11-18): folder creation and move-to-folder are
        // FREE and stay free. Tags/batch rename remain Pro; this entry only opens folders.
        ready("folders", ToolGroup.ORGANIZE, R.string.tool_folders, R.drawable.ic_sym_folder_open, "folder tag group", pro = false),
        // Owner 11-16: the Batch rename tool is Coming soon. The pattern + preview + undo sheet
        // exists, but the owner asked for a Coming-soon tile until that entry point is finished.
        soon(
            "batch_rename", ToolGroup.ORGANIZE, R.string.tool_batch_rename,
            R.drawable.ic_sym_drive_file_rename, "batch rename many files",
            pro = true, note = R.string.soon_batch_rename
        ),
        ready("batch_tools", ToolGroup.ORGANIZE, R.string.tools_tool_batch, R.drawable.ic_sym_view_module, "batch many documents bulk apply", pro = true, launch = { host, tool -> ToolLaunch.start(host, tool.id) }),

        // Optimize (2)
        ready("compress", ToolGroup.OPTIMIZE, R.string.tool_compress, R.drawable.ic_sym_compress, "compress smaller size reduce mb छोटा", launch = { host, tool -> ToolLaunch.start(host, tool.id) }),
        ready("resize", ToolGroup.OPTIMIZE, R.string.tool_resize, R.drawable.ic_sym_aspect_ratio, "resize dimensions pixels"),

        // Convert (8)
        ready("pdf_to_images", ToolGroup.CONVERT, R.string.tool_pdf_to_images, R.drawable.ic_sym_image, "pdf to jpg png images zip", launch = { host, tool -> ToolLaunch.start(host, tool.id) }),
        ready("long_image", ToolGroup.CONVERT, R.string.tool_long_image, R.drawable.ic_sym_view_day, "long image stitch pages", launch = { host, tool -> ToolLaunch.start(host, tool.id) }),
        ready("to_word", ToolGroup.CONVERT, R.string.tool_to_word, R.drawable.ic_sym_description, "word docx document text", pro = true, launch = { host, tool -> ToolLaunch.start(host, tool.id) }),
        ready("to_powerpoint", ToolGroup.CONVERT, R.string.tools_tool_to_powerpoint, R.drawable.ic_sym_slideshow, "powerpoint pptx slides presentation deck", pro = true, launch = { host, tool -> ToolLaunch.start(host, tool.id) }),
        ready("to_excel", ToolGroup.CONVERT, R.string.tool_to_excel, R.drawable.ic_sym_table_chart, "excel xlsx table sheet", pro = true, launch = { host, tool -> ToolLaunch.start(host, tool.id) }),
        ready("to_text", ToolGroup.CONVERT, R.string.tool_to_text, R.drawable.ic_sym_notes, "text txt export ocr"),
        ready("zip", ToolGroup.CONVERT, R.string.tool_zip, R.drawable.ic_sym_archive, "zip archive export batch", launch = { host, tool -> ToolLaunch.start(host, tool.id) }),
        ready("searchable_pdf", ToolGroup.CONVERT, R.string.tool_searchable_pdf, R.drawable.ic_sym_picture_as_pdf, "searchable text layer ocr pdf"),
        SwTool(
            "pdfa", ToolGroup.CONVERT, R.string.tool_pdfa, R.drawable.ic_sym_approval,
            true, ToolStatus.READY, "pdf a archive long term pdfa 2b",
            launch = { host, tool ->
                val activity = host as? androidx.appcompat.app.AppCompatActivity
                if (activity != null) {
                    com.scanwhirl.app.tools.PdfAFlow.start(
                        activity,
                        com.scanwhirl.app.ScanWhirlApp.from(activity).repository
                    )
                } else {
                    host.open(tool)
                }
            }
        ),

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
        ready("redact", ToolGroup.EDIT, R.string.tool_redact, R.drawable.ic_sym_ink_eraser, "hide private info confidential masking redact aadhaar pan card phone email iban ssn account driving licence passport", pro = true).copy(
            launch = { host, tool -> (host as? android.app.Activity)?.let { com.scanwhirl.app.ui.stamp.StampToolLauncher.open(it, "redact") } ?: host.comingSoon(tool) }
        ),

        // Secure (4)
        // L3: both open a real document chooser, then the Protect/Unlock sheet. Free tools.
        ready(
            "protect", ToolGroup.SECURE, R.string.tool_protect, R.drawable.ic_sym_encrypted,
            "protect password encrypt aes",
            launch = { host, tool ->
                val activity = host as? android.app.Activity
                if (activity == null) {
                    host.comingSoon(tool)
                } else {
                    activity.startActivity(
                        com.scanwhirl.app.ui.protect.LockToolsActivity.intent(
                            activity,
                            com.scanwhirl.app.ui.protect.LockToolsActivity.MODE_PROTECT
                        )
                    )
                }
            }
        ),
        ready(
            "unlock", ToolGroup.SECURE, R.string.tool_unlock, R.drawable.ic_sym_lock_open,
            "unlock password remove protection",
            launch = { host, tool ->
                val activity = host as? android.app.Activity
                if (activity == null) {
                    host.comingSoon(tool)
                } else {
                    activity.startActivity(
                        com.scanwhirl.app.ui.protect.LockToolsActivity.intent(
                            activity,
                            com.scanwhirl.app.ui.protect.LockToolsActivity.MODE_UNLOCK
                        )
                    )
                }
            }
        ),
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

        // Text (1): Hindi is a language choice in the Text screen and Settings, not a tool.
        ready("read_text", ToolGroup.TEXT, R.string.tool_read_text, R.drawable.ic_sym_notes_lines, "read text ocr recognise copy")
    )

    private val byId: Map<String, SwTool> = all.associateBy { it.id }

    fun byId(id: String): SwTool? = byId[id]

    /**
     * The Pro tools that are actually on sale: Pro and shipped. A Pro tool that is still
     * [ToolStatus.SOON] is not counted (and must never be sold): "See all N Pro tools" and the
     * hub's PRO tags then agree.
     */
    fun proCount(): Int = all.count { it.pro && it.status != ToolStatus.SOON }

    fun byGroup(group: ToolGroup): List<SwTool> = all.filter { it.group == group }

    /** Grouped catalogue in mockup order, including empty groups only if they ever appear. */
    fun grouped(): List<Pair<ToolGroup, List<SwTool>>> =
        ToolGroup.entries.map { group -> group to byGroup(group) }.filter { it.second.isNotEmpty() }

    // ---- B4-TOOLS: the seven jobs ------------------------------------------

    /**
     * One line per tool id: the only place a tool is attached to a job. A list of pairs, not
     * a map, so a repeated id is a test failure instead of a silently overwritten key. A tool
     * added later still gets a job from [groupJob], so the hub can never silently drop it.
     */
    private val jobAssignments: List<Pair<String, ToolJob>> = listOf(
        // Make it smaller
        "compress" to ToolJob.SMALLER,
        "resize" to ToolJob.SMALLER,
        // Combine and organise
        "merge" to ToolJob.ORGANISE,
        "split" to ToolJob.ORGANISE,
        "extract" to ToolJob.ORGANISE,
        "organize_pages" to ToolJob.ORGANISE,
        "insert_pages" to ToolJob.ORGANISE,
        "folders" to ToolJob.ORGANISE,
        "batch_rename" to ToolJob.ORGANISE,
        "batch_tools" to ToolJob.ORGANISE,
        // Sign and mark up
        "sign" to ToolJob.MARKUP,
        "add_text" to ToolJob.MARKUP,
        "draw" to ToolJob.MARKUP,
        "highlight" to ToolJob.MARKUP,
        "watermark" to ToolJob.MARKUP,
        "page_numbers" to ToolJob.MARKUP,
        "stamp" to ToolJob.MARKUP,
        // Keep it private (Redact is the "hide private info" job, not a markup)
        "redact" to ToolJob.PRIVATE,
        "protect" to ToolJob.PRIVATE,
        "unlock" to ToolJob.PRIVATE,
        "vault" to ToolJob.PRIVATE,
        "remove_metadata" to ToolJob.PRIVATE,
        // Get the text
        "read_text" to ToolJob.TEXT,
        "to_text" to ToolJob.TEXT,
        "searchable_pdf" to ToolJob.TEXT,
        // Change the format
        "pdf_to_images" to ToolJob.FORMAT,
        "long_image" to ToolJob.FORMAT,
        "to_word" to ToolJob.FORMAT,
        "to_powerpoint" to ToolJob.FORMAT,
        "to_excel" to ToolJob.FORMAT,
        "zip" to ToolJob.FORMAT,
        "pdfa" to ToolJob.FORMAT,
        // Start something new
        "scan" to ToolJob.NEW,
        "id_card" to ToolJob.NEW,
        "import_pdf" to ToolJob.NEW,
        "passport" to ToolJob.NEW,
        "qr_code" to ToolJob.NEW
    )

    private val jobOfId: Map<String, ToolJob> = jobAssignments.toMap()

    /** The explicit assignments, so a test can prove none is stale or duplicated. */
    internal fun jobTable(): List<Pair<String, ToolJob>> = jobAssignments

    /**
     * The job a tool belongs to. Every catalogue tool has exactly one: the explicit table
     * first, then its catalogue group, so a new tool is never unreachable.
     */
    fun jobOf(tool: SwTool): ToolJob = jobOfId[tool.id] ?: groupJob(tool.group)

    private fun groupJob(group: ToolGroup): ToolJob = when (group) {
        ToolGroup.CREATE -> ToolJob.NEW
        ToolGroup.ORGANIZE -> ToolJob.ORGANISE
        ToolGroup.OPTIMIZE -> ToolJob.SMALLER
        ToolGroup.CONVERT -> ToolJob.FORMAT
        ToolGroup.EDIT -> ToolJob.MARKUP
        ToolGroup.SECURE -> ToolJob.PRIVATE
        ToolGroup.TEXT -> ToolJob.TEXT
    }

    /**
     * The shipping tools under each job, in catalogue order. Derived from [all]: there is no
     * hand-written membership list and no count anywhere. A Coming-soon tool has no row (the
     * hub never shows a Soon tile), so a job's list can never advertise what does not ship.
     */
    fun jobs(): List<Pair<ToolJob, List<SwTool>>> = ToolJob.entries.map { job ->
        job to all.filter { it.status != ToolStatus.SOON && jobOf(it) == job }
    }

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
