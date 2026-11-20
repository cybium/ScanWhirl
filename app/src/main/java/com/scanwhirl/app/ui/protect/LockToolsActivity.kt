package com.scanwhirl.app.ui.protect

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.HomeHost
import com.scanwhirl.app.tools.LockFlow
import com.scanwhirl.app.tools.FlowUtil
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.ui.kit.DocumentPicker
import com.scanwhirl.app.ui.kit.SwProgress
import com.scanwhirl.app.ui.kit.SwStyle

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
        flow.unlockDevicePdfFile(uri) { finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_PROTECT
        setContentView(buildRoot())
        if (savedInstanceState == null) showChooser()
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
        // The chooser closes before it opens the next sheet or the Android file picker.
        // Returning here (for example after cancelling Protect) must leave a usable screen.
        val choose = MaterialButton(this@LockToolsActivity).apply {
            setText(R.string.lock_choose_document)
            isAllCaps = false
            minimumHeight = dp(48)
            minHeight = dp(48)
            isFocusableInTouchMode = true
            setOnClickListener { showChooser() }
        }
        addView(
            choose,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(16) }
        )
        choose.requestFocus()
    }

    private fun showChooser() {
        val all = runCatching { repo.listDocuments() }.getOrDefault(emptyList())
        if (mode == MODE_PROTECT) {
            val protectable = all.filter { !it.isLocked && !it.isVaulted }
            // UI-W6: the shared picker replaces the name-only list, so two similar names can
            // never be confused. A protected/Vault file is excluded (Protect needs an open one).
            DocumentPicker.show(
                activity = this,
                title = getString(R.string.lock_choose_document),
                docs = protectable,
                noEligibleTitle = R.string.r3fit_no_protectable.takeIf { all.isNotEmpty() && protectable.isEmpty() },
                onEmptyScan = { FlowUtil.openHome(this) },
                onPick = { doc -> flow.protectDocument(doc) },
                onDismiss = { finish() }
            )
        } else {
            val locked = all.filter { it.isLocked && !it.isVaulted }
            DocumentPicker.show(
                activity = this,
                title = getString(R.string.lock_choose_document),
                docs = locked,
                noEligibleTitle = R.string.r3fit_no_locked.takeIf { all.isNotEmpty() && locked.isEmpty() },
                extraAction = if (all.isEmpty()) null else {
                    DocumentPicker.ExtraAction(getString(R.string.lock_from_pdf)) {
                        pickPdf.launch("application/pdf")
                    }
                },
                onEmptyScan = { FlowUtil.openHome(this) },
                onEmptyImport = { pickPdf.launch("application/pdf") },
                onPick = { doc -> flow.unlockDocument(doc) },
                onDismiss = { finish() }
            )
        }
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
