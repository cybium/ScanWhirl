package com.scanwhirl.app.ui.protect

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.HomeHost
import com.scanwhirl.app.tools.LockFlow
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.SwDialog
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.ui.kit.SwProgress
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.showSw

/**
 * The Tools/Home "Protect PDF" and "Unlock PDF" entries: a document chooser, then the real sheet.
 *
 * Protect lists the unprotected library documents and opens [ProtectSheet] on the one tapped.
 * Unlock lists the protected documents plus "From a PDF file", which runs the same device-PDF
 * unlock path as the reader's Lock/Unlock screen. Both are free tools (no Pro gate).
 */
class LockToolsActivity : BaseActivity(), HomeHost {

    private val repo by lazy { ScanWhirlApp.from(this).repository }
    private val flow by lazy {
        LockFlow(
            activity = this,
            repo = repo,
            host = this,
            docs = { runCatching { repo.listDocuments() }.getOrDefault(emptyList()) },
            // The picker lives in this activity; LockFlow's seam is unused here.
            pickPdf = { _ -> }
        )
    }
    private var mode = MODE_PROTECT

    private val pickPdf = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        flow.unlockDevicePdfFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_PROTECT
        setContentView(buildRoot())
        showChooser()
    }

    private fun buildRoot(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(32), dp(24), dp(32))
        // T-FIX-INSETS-BACKUP: the chooser title was the other screen drawn under the status
        // bar (BaseActivity turns decor fitting off). The original 24/32 padding is kept and
        // the system bars are added on top and bottom.
        applySystemBarsPadding()
        addView(
            SwStyle.text(
                this@LockToolsActivity,
                22f,
                SwStyle.color(this@LockToolsActivity, R.color.text_hologram_hi),
                bold = true
            ).apply {
                text = getString(
                    if (mode == MODE_PROTECT) R.string.tool_protect else R.string.tool_unlock
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
    }

    private fun showChooser() {
        val all = runCatching { repo.listDocuments() }.getOrDefault(emptyList())
        var chosen = false
        if (mode == MODE_PROTECT) {
            val protectable = all.filter { !it.isLocked }
            if (protectable.isEmpty()) {
                showEmpty()
                return
            }
            SwDialog.sheet(this)
                .setTitle(R.string.lock_choose_document)
                .setItems(protectable.map { it.name }.toTypedArray()) { _, which ->
                    chosen = true
                    flow.protectDocument(protectable[which])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener { if (!chosen) finish() }
                .showSw(isSheet = true)
        } else {
            val locked = all.filter { it.isLocked }
            val labels = locked.map { it.name } + getString(R.string.lock_from_pdf)
            SwDialog.sheet(this)
                .setTitle(R.string.lock_choose_document)
                .setItems(labels.toTypedArray()) { _, which ->
                    chosen = true
                    if (which < locked.size) {
                        flow.unlockDocument(locked[which])
                    } else {
                        pickPdf.launch("application/pdf")
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener { if (!chosen) finish() }
                .showSw(isSheet = true)
        }
    }

    private fun showEmpty() {
        SwDialog.card(this)
            .setTitle(if (mode == MODE_PROTECT) R.string.tool_protect else R.string.tool_unlock)
            .setMessage(R.string.lock_no_documents)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setOnDismissListener { finish() }
            .showSw(isSheet = false)
    }

    private fun dp(value: Int): Int = SwStyle.dp(this, value.toFloat())

    // ---- HomeHost ---------------------------------------------------------

    override fun refreshList() = Unit

    override fun showProgress(titleRes: Int): SwProgress = SwProgress.show(this, getString(titleRes))

    override fun toast(res: Int, duration: Int) {
        Toast.makeText(this, res, duration).show()
    }

    override fun toast(message: CharSequence, duration: Int) {
        Toast.makeText(this, message, duration).show()
    }

    companion object {
        const val EXTRA_MODE = "lock_mode"
        const val MODE_PROTECT = "protect"
        const val MODE_UNLOCK = "unlock"

        fun intent(context: Context, mode: String): Intent =
            Intent(context, LockToolsActivity::class.java).putExtra(EXTRA_MODE, mode)
    }
}
