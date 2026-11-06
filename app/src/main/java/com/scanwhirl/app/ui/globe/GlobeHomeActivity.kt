package com.scanwhirl.app.ui.globe

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.ActivityGlobeHomeBinding
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.MainActivity
import com.scanwhirl.app.ui.PdfViewerActivity
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.SearchMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Test23 home: a slowly rotating holographic dotted Earth. Tap it to expand
 * the five category rings (Study, Receipts, IDs, Forms, Other) with document
 * cards spinning along them; pull down to search (shared with the Shelf,
 * including the Pro OCR fallback); pinch in to cross-fade into the existing
 * list — the Shelf. Locked/vaulted documents sit on the far side of the
 * globe as glyph-only cards and open through the existing PIN/password flow.
 */
class GlobeHomeActivity : AppCompatActivity(), HolographicEarthView.Listener {

    private lateinit var binding: ActivityGlobeHomeBinding
    private lateinit var repo: ScanRepository
    private var docs: List<ScanDocument> = emptyList()
    private var searchOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGlobeHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = ScanRepository(this)

        // Settings escape hatch: globe off → straight to the Shelf.
        if (!Prefs.globeHomeEnabled(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
            return
        }

        binding.globeView.listener = this
        binding.globeView.hintText = getString(R.string.globe_hint_home)

        // Park the search panel above the screen until it is pulled/tapped.
        binding.searchPanel.post {
            binding.searchPanel.translationY = -binding.searchPanel.height.toFloat() * 1.5f
        }

        binding.fabGlobeScan.setOnClickListener { startShelfForScan() }
        binding.btnGlobeSearch.setOnClickListener { openSearch() }
        binding.btnSearchDone.setOnClickListener { closeSearch() }
        binding.globeSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = runQuery(s?.toString()?.trim().orEmpty())
        })

        loadDocs()
    }

    override fun onResume() {
        super.onResume()
        binding.globeView.setAnimating(true)
        // Scans made on the Shelf (or new folders) must show up on return.
        loadDocs()
    }

    override fun onPause() {
        binding.globeView.setAnimating(false)
        super.onPause()
    }

    private fun loadDocs() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                repo.listDocuments(includeVaulted = true)
            }
            docs = loaded
            binding.globeView.bindDocs(loaded)
            binding.globeEmpty.visibility =
                if (loaded.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            runQuery(binding.globeSearchInput.text?.toString()?.trim().orEmpty())
        }
    }

    // ------------------------------------------------------------------
    // Search (shared matcher with the Shelf)
    // ------------------------------------------------------------------

    private fun openSearch() {
        searchOpen = true
        binding.searchPanel.visibility = android.view.View.VISIBLE
        binding.searchPanel.animate().translationY(0f).setDuration(220).start()
        binding.globeSearchInput.requestFocus()
        binding.globeSearchInput.animate().alpha(1f).setDuration(220).start()
        binding.globeMatchCount.alpha = 0f
        binding.globeMatchCount.animate().alpha(1f).setDuration(300).start()
    }

    private fun closeSearch() {
        searchOpen = false
        binding.searchPanel.animate()
            .translationY(-binding.searchPanel.height.toFloat().coerceAtLeast(1f) * 1.2f)
            .withEndAction {
                binding.searchPanel.visibility = android.view.View.GONE
            }
            .setDuration(200).start()
        binding.globeSearchInput.text?.clear()
        binding.globeSearchInput.clearFocus()
    }

    private fun runQuery(query: String) {
        binding.globeView.setQuery(query)
        val (shown, _) = SearchMatcher.match(query, docs, ProGate.isPro(this)) {
            repo.ocrDoc(it)
        }
        // OCR/name matches beyond the title itself still light the card up.
        binding.globeView.scene.extraMatchIds = shown.mapTo(HashSet()) { it.id }
        binding.globeView.invalidate()

        val total = docs.count { !it.isVaulted }
        val matched = shown.count { !it.isLocked }
        binding.globeMatchCount.text = getString(
            if (query.isBlank()) R.string.globe_match_all else R.string.globe_match_count,
            if (query.isBlank()) total else matched,
            total
        )
    }

    // ------------------------------------------------------------------
    // HolographicEarthView.Listener
    // ------------------------------------------------------------------

    override fun onGlobeTapped() {
        binding.globeView.scene.toggleState()
        binding.globeView.hintText = getString(
            if (binding.globeView.scene.state == GlobeScene.STATE_RINGS) {
                R.string.globe_hint_rings
            } else {
                R.string.globe_hint_home
            }
        )
        binding.globeView.invalidate()
    }

    override fun onCardTapped(docId: String) {
        val doc = docs.firstOrNull { it.id == docId } ?: return
        when {
            doc.isVaulted -> promptVaultPin { openEditor(doc) }
            doc.isLocked -> promptUnlock(doc) { openEditor(doc) }
            else -> openViewer(doc)
        }
    }

    override fun onPinchToShelf() {
        openShelf()
    }

    override fun onPullSearch() {
        openSearch()
    }

    // ------------------------------------------------------------------
    // Navigation: Shelf, scan, documents
    // ------------------------------------------------------------------

    private fun openShelf() {
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /** The scan chooser lives on the Shelf; open it right away. */
    private fun startShelfForScan() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_SCANNER, true)
        )
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun openEditor(doc: ScanDocument) {
        startActivity(DocumentViewerActivity.intent(this, doc.id))
    }

    /** Same viewer choice as the Shelf (system PDF viewer pref respected). */
    private fun openViewer(doc: ScanDocument) {
        if (Prefs.defaultViewer(this) == Prefs.VIEWER_SYSTEM) {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", doc.pdfFile)
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/pdf")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
                return
            } catch (_: Exception) {
                // No handler: fall through to the in-app viewer, honestly.
            }
        }
        startActivity(PdfViewerActivity.intent(this, doc.id, doc.name))
    }

    // ------------------------------------------------------------------
    // Existing PIN flows (vault PIN + lock password)
    // ------------------------------------------------------------------

    private fun promptVaultPin(onOk: () -> Unit) {
        if (!Prefs.hasVaultPin(this)) {
            onOk() // nothing to verify against; the doc is reachable anyway
            return
        }
        val input = EditText(this).apply {
            hint = getString(R.string.vault_pin_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.vault_enter_pin_title)
            .setMessage(R.string.vault_enter_pin_msg)
            .setView(input)
            .setPositiveButton(R.string.unlock_pdf_open) { _, _ ->
                val pin = input.text?.toString()?.trim().orEmpty()
                if (Prefs.verifyVaultPin(this, pin)) {
                    onOk()
                } else {
                    Toast.makeText(this, R.string.vault_pin_wrong, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptUnlock(doc: ScanDocument, onOk: () -> Unit) {
        val input = EditText(this).apply {
            hint = getString(R.string.protect_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.unlock_pdf_title)
            .setMessage(getString(R.string.unlock_pdf_msg, doc.name))
            .setView(input)
            .setPositiveButton(R.string.unlock_pdf_open) { _, _ ->
                val pw = input.text?.toString().orEmpty()
                if (repo.verifyPdfPassword(doc.id, pw)) {
                    onOk()
                } else {
                    Toast.makeText(this, R.string.unlock_pdf_wrong, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        fun intent(context: android.content.Context): Intent =
            Intent(context, GlobeHomeActivity::class.java)
    }
}
