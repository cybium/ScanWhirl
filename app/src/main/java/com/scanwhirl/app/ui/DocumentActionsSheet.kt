package com.scanwhirl.app.ui

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.CompressFlow
import com.scanwhirl.app.tools.HomeHost
import com.scanwhirl.app.tools.LockFlow
import com.scanwhirl.app.tools.OcrFlow
import com.scanwhirl.app.ui.home.DocThumbBinder
import com.scanwhirl.app.ui.kit.ProOffer
import com.scanwhirl.app.ui.kit.SwProgress
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.stamp.StampEditorActivity
import com.scanwhirl.app.util.PdfProtection
import com.scanwhirl.app.util.ShareUtils
import com.scanwhirl.app.util.StatusLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * V2V V1: one localized, actionable mapping for failures in the reader/editor
 * screens. A raw JVM or library message is never shown: it can carry a private
 * document path or token, and it tells the person nothing they can do next.
 */
object V2vFailureCopy {
    @JvmStatic
    fun message(context: android.content.Context, e: Throwable): String {
        val raw = e.localizedMessage.orEmpty().lowercase(Locale.ROOT)
        return when {
            e is SecurityException || raw.contains("password") || raw.contains("encrypt") ||
                raw.contains("denied") || raw.contains("permission") ||
                raw.contains("corrupt") || raw.contains("malformed") ||
                raw.contains("damaged") ->
                context.getString(R.string.v2v_failure_unreadable)
            e is OutOfMemoryError || raw.contains("space") || raw.contains("enospc") ||
                raw.contains("quota") || raw.contains("memory") ->
                context.getString(R.string.v2v_failure_no_space)
            else -> context.getString(R.string.v2v_failure_generic)
        }
    }
}

/**
 * B2 (GPT-6.2 critique #3 and its "Two-tap contract"): ONE named Actions sheet, bound to ONE
 * document id â€” the same sheet from the document viewer's dock and (in the next lane) from a
 * Library row's overflow.
 *
 * The header repeats the document the sheet is bound to: its own page thumbnail (never one for
 * a locked or Vault document â€” [DocThumbBinder] decides) and its name. Rows then run, in this
 * order:
 *
 *  1. the document-linked work: Compress, Sign Ã‚Â·Pro, Read text, Share;
 *  2. file management: Rename, Move to category, Duplicate, Protect/Unlock PDF, (Vault);
 *  3. the destructive row last: Move to Trash, or Delete permanently for a legacy Vault item.
 *
 * Every row calls the EXISTING entry point for THAT document, with the source already pinned:
 * [CompressFlow.startWith] (no source chooser), the reader's own Sign route ([ProOffer] gate,
 * then [StampEditorActivity.intent] with this id â€” never StampToolLauncher's generic picker),
 * [OcrFlow.runOcrForScan] for this doc, and [ShareUtils] behind the protection guard.
 *
 * A password-locked or Vault document cannot hand its pages to Compress, Sign or Read text:
 * those rows stay visible but disabled with the reason, and no handler is reachable from them.
 * Share keeps working because [ProtectionDialogs.guardExport] already owns that decision.
 *
 * The sheet never holds viewer state. The host may pass [onChange] (reload itself after a
 * rename/duplicate/protect/unlock/Vault change) and [onGone] (the host closes when the shown
 * document leaves â€” the viewer); with no [onGone] the sheet shows the move's own Undo bar.
 */
object DocumentActionsSheet {

    /** The one document-linked action a row runs (also the test seam's vocabulary). */
    enum class Action { COMPRESS, SIGN, READ_TEXT, SHARE, RENAME, MOVE, DUPLICATE, PROTECT, UNLOCK, VAULT, TRASH }

    /**
     * Test seam: records (action, document) just before the real handler would run; returning
     * true stands in for that handler. Null in production, where every row runs its flow.
     */
    @VisibleForTesting
    internal var interceptForTests: ((Action, ScanDocument) -> Boolean)? = null

    /** View tags the tests and the Library lane use to find a row without a new layout id. */
    private const val TAG_PREFIX = "docActions:"

    /**
     * Opens the sheet for [docId].
     *
     * @param onChange the host changed (a rename, a copy, a protection change): reload.
     * @param onGone the shown document left the library (moved to Trash / deleted): close.
     */
    fun show(
        activity: AppCompatActivity,
        docId: String,
        onChange: () -> Unit = {},
        onGone: (() -> Unit)? = null,
        /** W1: a real output ran for this document (a fresh import must survive Back). */
        onUsed: () -> Unit = {},
        /**
         * W1: the user chose "Finish editing first". Null opens the document workspace so the
         * edits can be finished there; a host that already shows the document passes a no-op.
         */
        onFinishEditing: (() -> Unit)? = null
    ) {
        val repo = ScanWhirlApp.from(activity).repository
        val doc = repo.getDocument(docId)
        if (doc == null) {
            Toast.makeText(activity, R.string.doc_actions_missing, Toast.LENGTH_SHORT).show()
            return
        }
        show(activity, repo, doc, onChange, onGone, onUsed, onFinishEditing)
    }

    private fun show(
        activity: AppCompatActivity,
        repo: ScanRepository,
        doc: ScanDocument,
        onChange: () -> Unit,
        onGone: (() -> Unit)?,
        onUsed: () -> Unit,
        onFinishEditing: (() -> Unit)?
    ) {
        val finishEditing: () -> Unit = onFinishEditing ?: {
            activity.startActivity(DocumentViewerActivity.intent(activity, doc.id))
        }
        val host = SheetHost(activity, onChange)
        val rows = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_PREFIX + "rows"
            // Owner add-on (atlas 007_actions_sheet_2.0): the sheet's Close row is fixed below the
            // scroll, so the content carries its own bottom inset. Without it the last row (and,
            // at 200 %, a half-row) ran flush into the footer with no way to sit clear of it.
            setPadding(0, 0, 0, SwStyle.dp(activity, 12f))
        }
        rows.addView(header(activity, doc))

        var sheet: SwSheet? = null
        fun dismissThen(action: Action, run: () -> Unit) {
            sheet?.dismiss()
            val hook = interceptForTests
            if (hook != null && hook(action, doc)) return
            run()
        }

        // --- 1. the document-linked work ------------------------------------
        val protected = doc.isLocked || doc.isVaulted
        val reason = when {
            doc.isLocked -> activity.getString(R.string.locked_source_refused, doc.name)
            doc.isVaulted -> activity.getString(R.string.doc_actions_vault_reason)
            else -> null
        }
        rows.addView(row(
            activity = activity,
            icon = R.drawable.ic_sym_compress,
            label = activity.getString(R.string.tool_compress),
            key = "compress",
            id = R.id.rowMoreCompress,
            enabled = !protected,
            reason = reason,
            onRun = {
                dismissThen(Action.COMPRESS) {
                    // W1: the id is re-read at the tap; a draft asks before the saved pages are used.
                    DocumentActionDispatcher.dispatch(
                        activity = activity,
                        repo = repo,
                        docId = doc.id,
                        action = DocumentActionDispatcher.Action.COMPRESS,
                        onUsed = { _ -> onUsed() },
                        onFinishEditing = { _ -> finishEditing() }
                    ) { bound ->
                        CompressFlow(
                            activity = activity,
                            repo = repo,
                            host = host,
                            pickPdf = {},
                            docs = { listOf(bound) }
                        ).startWith(bound)
                    }
                }
            }
        ))
        rows.addView(row(
            activity = activity,
            icon = R.drawable.ic_sym_signature,
            label = activity.getString(R.string.tool_sign),
            key = "sign",
            enabled = !protected,
            reason = reason,
            pro = true,
            onRun = {
                dismissThen(Action.SIGN) {
                    // The reader's own route (PdfViewerActivity dock Sign): the Pro gate first,
                    // then the editor armed with Sign for THIS document. Free never gets here.
                    DocumentActionDispatcher.dispatch(
                        activity = activity,
                        repo = repo,
                        docId = doc.id,
                        action = DocumentActionDispatcher.Action.SIGN,
                        onUsed = { _ -> onUsed() },
                        onFinishEditing = { _ -> finishEditing() }
                    ) { bound ->
                        if (ProOffer.require(activity)) {
                            activity.startActivity(
                                StampEditorActivity.intent(
                                    activity, bound.id, StampEditorActivity.TOOL_SIGN
                                )
                            )
                        }
                    }
                }
            }
        ))
        rows.addView(row(
            activity = activity,
            icon = R.drawable.ic_ocr,
            label = activity.getString(R.string.btn_ocr_text),
            key = "read_text",
            enabled = !protected,
            reason = reason,
            onRun = {
                dismissThen(Action.READ_TEXT) {
                    OcrFlow(activity = activity, repo = repo, host = host).runOcrForScan(doc)
                }
            }
        ))
        rows.addView(row(
            activity = activity,
            icon = R.drawable.ic_sym_share,
            label = activity.getString(R.string.share_pdf),
            key = "share",
            onRun = {
                dismissThen(Action.SHARE) {
                    ProtectionDialogs.guardExport(
                        activity, repo, doc, onResolved = onChange
                    ) {
                        // W1: bound id + draft question; the saved version is what leaves.
                        DocumentActionDispatcher.dispatch(
                            activity = activity,
                            repo = repo,
                            docId = doc.id,
                            action = DocumentActionDispatcher.Action.SHARE,
                            onUsed = { _ -> onUsed() },
                            onFinishEditing = { _ -> finishEditing() }
                        ) { bound -> ShareUtils.share(activity, bound) }
                    }
                }
            }
        ))

        // --- 2. file management ---------------------------------------------
        rows.addView(groupHeader(activity, R.string.group_organize, R.id.hdrOrganize, "organize"))
        rows.addView(row(
            activity = activity,
            icon = R.drawable.ic_sym_edit,
            label = activity.getString(R.string.rename),
            key = "rename",
            id = R.id.rowMoreRename,
            onRun = { dismissThen(Action.RENAME) { rename(activity, repo, doc, onChange) } }
        ))
        rows.addView(row(
            activity = activity,
            icon = R.drawable.ic_sym_folder_open,
            label = activity.getString(R.string.move_to_category),
            key = "move",
            id = R.id.rowMoreMove,
            onRun = {
                dismissThen(Action.MOVE) {
                    CategoryPickerSheet.show(activity, doc.category) { category ->
                        activity.lifecycleScope.launch {
                            withContext(Dispatchers.IO) { repo.setCategory(doc.id, category) }
                            onChange()
                        }
                    }
                }
            }
        ))
        rows.addView(row(
            activity = activity,
            icon = R.drawable.ic_sym_content_copy,
            label = activity.getString(R.string.duplicate),
            key = "duplicate",
            id = R.id.rowMoreDuplicate,
            onRun = { dismissThen(Action.DUPLICATE) { duplicate(activity, repo, doc, host) } }
        ))

        rows.addView(groupHeader(activity, R.string.bar_tools, R.id.hdrTools, "tools"))
        rows.addView(row(
            activity = activity,
            icon = R.drawable.ic_sym_encrypted,
            label = activity.getString(
                if (doc.isLocked) R.string.btn_unlock_pdf else R.string.menu_protect_pdf
            ),
            labelViewId = R.id.labelLock,
            key = "lock",
            id = R.id.rowMoreLock,
            pillId = R.id.pillLock,
            onRun = {
                dismissThen(if (doc.isLocked) Action.UNLOCK else Action.PROTECT) {
                    if (doc.isLocked) {
                        unlock(activity, repo, doc, host, onChange)
                    } else {
                        // v2 UI: 05_protect_pdf â€” the same Protect sheet as Tools â†’ Protect PDF.
                        LockFlow(activity, repo, host, { listOf(doc) }, { _ -> }).protectDocument(doc)
                    }
                }
            }
        ))
        rows.addView(row(
            activity = activity,
            icon = R.drawable.ic_sym_shield_lock,
            label = activity.getString(
                if (doc.isVaulted) R.string.btn_vault_unhide else R.string.btn_vault_hide
            ),
            labelViewId = R.id.labelVault,
            key = "vault",
            id = R.id.rowMoreVault,
            pillId = R.id.pillVault,
            visible = doc.isVaulted,
            onRun = {
                dismissThen(Action.VAULT) {
                    if (doc.isVaulted) {
                        // Removing from the Vault is always free: the user's own file.
                        repo.setVaulted(doc.id, false)
                        Toast.makeText(activity, R.string.vault_moved_out, Toast.LENGTH_SHORT).show()
                        onChange()
                    } else {
                        // New Vault additions wait for encrypted Private (WP-14).
                        Toast.makeText(
                            activity, R.string.vault_new_unavailable, Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        ))

        // --- 3. destructive, last -------------------------------------------
        rows.addView(groupHeader(activity, R.string.group_danger, R.id.hdrDanger, "danger"))
        rows.addView(row(
            activity = activity,
            icon = R.drawable.ic_sym_delete,
            label = activity.getString(
                if (doc.isVaulted) R.string.delete_document else R.string.trash_move
            ),
            labelViewId = R.id.labelMoreDelete,
            key = "trash",
            id = R.id.rowMoreDelete,
            destructive = true,
            onRun = {
                dismissThen(Action.TRASH) {
                    if (doc.isVaulted) {
                        permanentDelete(activity, repo, doc, onChange, onGone)
                    } else {
                        confirmTrash(activity, repo, doc, onChange, onGone)
                    }
                }
            }
        ))

        val scroll = CappedScrollView(activity).apply {
            maxFraction = 0.62f
            addView(rows)
        }
        val built = SwSheet.builder(activity)
            .setTitle(R.string.doc_actions)
            .setContent(scroll)
            .setSecondary(R.string.sheet_close)
            .build()
        sheet = built
        built.show()
    }

    // ------------------------------------------------------------------ header

    /** The bound document's own thumbnail and name: the sheet can never be about another doc. */
    private fun header(activity: AppCompatActivity, doc: ScanDocument): View {
        val d = activity.resources.displayMetrics.density
        val dp = { v: Float -> (v * d).toInt() }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(6f), dp(10f), dp(12f))
        }
        val thumbSize = dp(48f)
        val stack = FrameLayout(activity).apply {
            tag = TAG_PREFIX + "thumb"
            layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize)
                .apply { marginEnd = dp(14f) }
        }
        val thumb = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setBackgroundResource(R.drawable.bg_thumb_paper)
        }
        val glyph = ImageView(activity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
            val g = dp(18f)
            layoutParams = FrameLayout.LayoutParams(g, g, Gravity.CENTER)
        }
        stack.addView(thumb, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        stack.addView(glyph)
        // The one privacy rule for page images: locked and Vault documents never decode one.
        DocThumbBinder.bind(thumb, glyph, doc)
        header.addView(stack)

        val textColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(activity).apply {
            tag = TAG_PREFIX + "name"
            text = doc.name
            setTextColor(SwStyle.color(activity, R.color.text_hologram_hi))
            textSize = 16f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        })
        val bytes = if (doc.pdfFile.isFile && doc.pdfFile.length() > 0L) {
            doc.pdfFile.length()
        } else {
            doc.pageFiles.sumOf { it.length() }
        }
        textColumn.addView(TextView(activity).apply {
            tag = TAG_PREFIX + "meta"
            text = activity.resources.getQuantityString(
                R.plurals.viewer_meta, doc.pageCount, doc.pageCount, StatusLine.formatBytes(bytes)
            )
            setTextColor(SwStyle.color(activity, R.color.text_hologram_mid))
            textSize = 12f
            includeFontPadding = false
            setPadding(0, (2 * d).toInt(), 0, 0)
        })
        header.addView(textColumn)
        header.tag = TAG_PREFIX + "header"
        return header
    }

    // ------------------------------------------------------------------ rows

    private fun groupHeader(
        activity: AppCompatActivity,
        @StringRes title: Int,
        id: Int?,
        key: String,
        after: Boolean = true
    ): TextView = TextView(activity).apply {
        if (id != null) this.id = id
        text = activity.getString(title)
        isAllCaps = true
        textSize = 12f
        letterSpacing = 0.08f
        setTextColor(
            SwStyle.color(
                activity,
                if (key == "danger") R.color.laser_danger else R.color.text_hologram_mid
            )
        )
        setPadding(SwStyle.dp(activity, 10f), SwStyle.dp(activity, if (after) 12f else 4f), 0, 0)
        tag = TAG_PREFIX + "hdr:" + key
    }

    /**
     * One 56 dp row: 24 dp glyph, the label, an optional PRO pill and, for a row that cannot
     * run, the second-line reason. The row carries the words TalkBack reads (a clickable parent
     * with a child label is otherwise announced as a nameless box â€” M7).
     */
    private fun row(
        activity: AppCompatActivity,
        @DrawableRes icon: Int,
        label: CharSequence,
        key: String,
        labelViewId: Int? = null,
        id: Int? = null,
        pillId: Int? = null,
        enabled: Boolean = true,
        reason: CharSequence? = null,
        pro: Boolean = false,
        destructive: Boolean = false,
        visible: Boolean = true,
        onRun: () -> Unit
    ): LinearLayout {
        val d = activity.resources.displayMetrics.density
        val dp = { v: Float -> (v * d).toInt() }
        val ripple = android.util.TypedValue().also {
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }
        val row = LinearLayout(activity).apply {
            if (id != null) this.id = id
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56f)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            setBackgroundResource(ripple.resourceId)
            tag = TAG_PREFIX + key
            isClickable = enabled
            // Focusable even when disabled: TalkBack must be able to read the reason.
            isFocusable = true
            this.isEnabled = enabled
            visibility = if (visible) View.VISIBLE else View.GONE
            if (!enabled) alpha = 0.45f
        }
        row.addView(ImageView(activity).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(
                SwStyle.color(
                    activity, if (destructive) R.color.laser_danger else R.color.cyan_primary
                )
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { marginEnd = dp(14f) })

        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        texts.addView(TextView(activity).apply {
            if (labelViewId != null) this.id = labelViewId
            text = label
            textSize = 15f
            setTextColor(
                SwStyle.color(
                    activity, if (destructive) R.color.laser_danger else R.color.text_hologram_hi
                )
            )
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        if (reason != null) {
            texts.addView(TextView(activity).apply {
                text = reason
                textSize = 12f
                setTextColor(SwStyle.color(activity, R.color.text_hologram_mid))
                includeFontPadding = false
                setPadding(0, dp(2f), 0, 0)
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (pro) {
            row.addView(TextView(activity).apply {
                tag = TAG_PREFIX + key + "_pro"
                text = activity.getString(R.string.badge_pro)
                textSize = 10f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(SwStyle.color(activity, R.color.pro_gold))
                setBackgroundResource(R.drawable.bg_lock_badge)
                setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
                includeFontPadding = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8f) })
        }
        if (pillId != null) {
            // The Protect and Vault rows are Free (WP-22-5): the pill exists for layout
            // parity with the old sheet but is never shown.
            row.addView(TextView(activity).apply {
                this.id = pillId
                text = activity.getString(R.string.badge_pro)
                isVisible = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }

        row.contentDescription = buildString {
            append(label)
            if (pro) append(", ").append(activity.getString(R.string.badge_pro))
            if (reason != null) append(". ").append(reason)
        }
        if (enabled) row.setOnClickListener { onRun() }
        return row
    }

    // ------------------------------------------------------------------ handlers

    private fun rename(
        activity: AppCompatActivity,
        repo: ScanRepository,
        doc: ScanDocument,
        onChange: () -> Unit
    ) {
        val sheet = SwSheet.builder(activity)
            .setTitle(R.string.rename_title)
            .setInput(initial = doc.name, hint = activity.getString(R.string.rename_title))
            .setSecondary(android.R.string.cancel)
            .build()
        sheet.setPrimary(R.string.save) {
            val newName = sheet.inputView?.text?.toString()
            if (!newName.isNullOrBlank()) {
                repo.renameDocument(doc.id, newName.trim())
                onChange()
            }
        }
        sheet.show()
    }

    private fun duplicate(
        activity: AppCompatActivity,
        repo: ScanRepository,
        doc: ScanDocument,
        host: HomeHost
    ) {
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repo.duplicateDocument(doc.id, "${doc.name} Copy")
                }
                host.toast(R.string.saved_as_new, Toast.LENGTH_LONG)
            } catch (e: Exception) {
                host.toast(activity.getString(R.string.page_op_failed, V2vFailureCopy.message(activity, e)), Toast.LENGTH_LONG)
            }
        }
    }

    /** Unlock keeps the viewer's PM-3.1b behaviour: the current password is asked first. */
    private fun unlock(
        activity: AppCompatActivity,
        repo: ScanRepository,
        doc: ScanDocument,
        host: HomeHost,
        onChange: () -> Unit
    ) {
        LockFlow.promptCurrentPassword(activity, doc.name) { password ->
            activity.lifecycleScope.launch {
                val progress = host.showProgress()
                try {
                    withContext(Dispatchers.IO) { repo.unlockPdf(doc.id, password) }
                    progress.dismiss()
                    Toast.makeText(activity, R.string.unlock_pdf_done, Toast.LENGTH_LONG).show()
                    onChange()
                } catch (e: PdfProtection.WrongPassword) {
                    progress.dismiss()
                    Toast.makeText(activity, R.string.unlock_pdf_wrong, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    progress.dismiss()
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.page_op_failed, V2vFailureCopy.message(activity, e)),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** Move to Trash always asks first; the move is reversible from Trash (10 seconds to Undo). */
    private fun confirmTrash(
        activity: AppCompatActivity,
        repo: ScanRepository,
        doc: ScanDocument,
        onChange: () -> Unit,
        onGone: (() -> Unit)?
    ) {
        SwSheet.builder(activity)
            .setTitle(activity.getString(R.string.shell_trash_title, doc.name))
            .setMessage(R.string.shell_trash_note)
            .setPrimary(R.string.shell_move_to_trash) {
                activity.lifecycleScope.launch {
                    val moved = withContext(NonCancellable + Dispatchers.IO) {
                        TrashActions.moveAll(repo, listOf(doc.id))
                    }
                    if (moved.keys.isEmpty()) {
                        val root = activity.findViewById<View>(android.R.id.content)
                            ?: activity.window.decorView
                        Snackbar.make(root, R.string.trash_move_failed, Snackbar.LENGTH_LONG).show()
                        return@launch
                    }
                    if (onGone != null) {
                        // The document screen closes; Home shows the Undo bar on its next resume.
                        TrashActions.post(moved)
                        onChange()
                        onGone()
                    } else {
                        val root = activity.findViewById<View>(android.R.id.content)
                            ?: activity.window.decorView
                        TrashActions.showMoved(activity, root, null, moved, onChange)
                        onChange()
                    }
                }
            }
            .setSecondary(R.string.sheet_cancel)
            .build()
            .show()
    }

    /**
     * A legacy Vault item never enters Trash: its delete is permanent, and the copies offered
     * are only the ones this document provably made (M07). The result says what was removed.
     */
    private fun permanentDelete(
        activity: AppCompatActivity,
        repo: ScanRepository,
        doc: ScanDocument,
        onChange: () -> Unit,
        onGone: (() -> Unit)?
    ) {
        val copies = DeleteExports.copiesFor(repo, doc.id)
        val body = DeleteExports.dialogBody(activity, copies, DeleteExports.unprovenFor(repo, doc.id))
        val box: android.widget.CheckBox? = body.findViewById(R.id.deleteCopiesBox)
        SwSheet.builder(activity)
            .setTitle(R.string.delete_title)
            .setMessage(activity.getString(R.string.delete_vault_message, doc.name))
            .setContent(body)
            .setSecondary(android.R.string.cancel)
            .setPrimary(R.string.delete) {
                repo.deleteDocument(doc.id) // never blocked by copy removal
                if (box?.isChecked == true && copies.isNotEmpty()) {
                    activity.lifecycleScope.launch {
                        val (removed, failed) = withContext(NonCancellable + Dispatchers.IO) {
                            DeleteExports.removeCopies(activity.applicationContext, copies)
                        }
                        val message = DeleteExports.message(activity, removed, failed)
                        val root = activity.findViewById<View>(android.R.id.content)
                            ?: activity.window.decorView
                        val bar = Snackbar.make(root, message, Snackbar.LENGTH_LONG)
                        if (onGone != null) {
                            bar.addCallback(object : Snackbar.Callback() {
                                override fun onDismissed(
                                    transientBottomBar: Snackbar?,
                                    event: Int
                                ) {
                                    onGone()
                                }
                            })
                            bar.show()
                        } else {
                            bar.show()
                            onChange()
                        }
                    }
                } else {
                    onChange()
                    onGone?.invoke()
                }
            }
            .build()
            .show()
    }

    // ------------------------------------------------------------------ host

    /** What the shared tool flows may ask of the sheet: reload the host, progress, toasts. */
    private class SheetHost(
        private val activity: AppCompatActivity,
        private val onChange: () -> Unit
    ) : HomeHost {
        override fun refreshList() = onChange()

        override fun showProgress(titleRes: Int): SwProgress =
            SwProgress.show(activity, activity.getString(titleRes))

        override fun toast(res: Int, duration: Int) {
            Toast.makeText(activity, res, duration).show()
        }

        override fun toast(message: CharSequence, duration: Int) {
            Toast.makeText(activity, message, duration).show()
        }
    }
}
