package com.scanwhirl.app.ui.globe

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.ActivityGlobeHomeBinding
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.MainActivity
import com.scanwhirl.app.ui.PdfViewerActivity
import com.scanwhirl.app.ui.SettingsActivity
import com.scanwhirl.app.ui.SwTransitions
import com.scanwhirl.app.util.CategoryStyle
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.SearchMatcher
import com.scanwhirl.app.util.SwMotion
import com.scanwhirl.app.ui.showSw
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
class GlobeHomeActivity : BaseActivity(), HolographicEarthView.Listener {

    private lateinit var binding: ActivityGlobeHomeBinding
    private lateinit var repo: ScanRepository
    private var docs: List<ScanDocument> = emptyList()
    private var searchOpen = false

    /**
     * test25 Phase 4: the FAB's breathing rings, built in code (the plan
     * allows no new resources). Both are **infinite**, so they may only ever
     * start behind `SwMotion.ambientAllowed` — see [startPulse].
     */
    private val pulse = mutableListOf<Animator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGlobeHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = ScanRepository(this)

        // Settings escape hatch: globe off → straight to the Shelf.
        if (!Prefs.globeHomeEnabled(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            SwTransitions.warpToShelf(this)
            finish()
            return
        }

        binding.globeView.listener = this
        setHint(R.string.globe_hint_home)

        // Park the search panel above the screen until it is pulled/tapped.
        binding.searchPanel.post {
            binding.searchPanel.translationY = -binding.searchPanel.height.toFloat() * 1.5f
        }

        // test25: the HUD now measures the top band the globe geometry
        // must stay below (title + status + three buttons).
        binding.globeHud.post {
            binding.globeView.topReserve = binding.globeHud.bottom.toFloat()
        }

        binding.fabGlobeScan.setOnClickListener { startShelfForScan() }
        binding.btnGlobeSearch.setOnClickListener { openSearch() }
        binding.btnSearchDone.setOnClickListener { closeSearch() }

        // test25: HUD navigation (List → Shelf, gear → Settings per D5) and
        // the orbit dock (tap = focus a category ring, tap again = clear).
        binding.btnGlobeList.setOnClickListener { openShelf() }
        binding.btnGlobeSettings.setOnClickListener {
            startActivity(SettingsActivity.intent(this))
        }
        wireDock()

        binding.globeSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = runQuery(s?.toString()?.trim().orEmpty())
        })

        updateStatus()
        loadDocs()
    }

    private fun chips(): List<android.widget.TextView> = listOf(
        binding.globeChipStudy,
        binding.globeChipReceipts,
        binding.globeChipIds,
        binding.globeChipForms,
        binding.globeChipOther
    )

    /** One tap focuses that ring (expanding the rings first), a second clears. */
    private fun wireDock() {
        chips().forEachIndexed { ring, chip ->
            chip.setOnClickListener {
                val scene = binding.globeView.scene
                setFocusRing(if (scene.focusRing == ring) -1 else ring)
            }
        }
    }

    private fun setFocusRing(ring: Int) {
        val scene = binding.globeView.scene
        scene.focusRing = ring
        chips().forEachIndexed { i, chip ->
            chip.isSelected = i == ring
            chip.setTextColor(
                if (i == ring) CategoryStyle.colorForRing(i)
                else ContextCompat.getColor(this, R.color.text_hologram_hi)
            )
        }
        // Focus only means something on the rings — show what was picked.
        if (ring >= 0 && scene.state == GlobeScene.STATE_HOME) scene.toggleState()
        binding.globeView.invalidate()
        updateStatus()
    }

    /**
     * HUD status readout: "AWAITING TRANSMISSION" when empty, the focused
     * ring's tag + count while a chip is active, otherwise documents · orbits.
     */
    private fun updateStatus() {
        val scene = binding.globeView.scene
        val focus = scene.focusRing
        binding.globeStatus.text = when {
            docs.isEmpty() -> getString(R.string.globe_status_empty)
            focus >= 0 -> {
                val n = scene.categoryCounts()[focus]
                "${CategoryStyle.tagForRing(focus)} · " +
                    resources.getQuantityString(R.plurals.globe_status_docs, n, n)
            }
            else -> {
                val total = docs.count { !it.isVaulted }
                val orbits = scene.orbitCards.size
                resources.getQuantityString(R.plurals.globe_status_docs, total, total) +
                    " · " +
                    resources.getQuantityString(R.plurals.globe_status_orbits, orbits, orbits)
            }
        }
        binding.globeStatus.contentDescription =
            getString(R.string.a11y_globe_summary, docs.count { !it.isVaulted })
    }

    /**
     * test25: the hint moved off the canvas into the [R.id.globeHint] pill,
     * but [HolographicEarthView.hintText] stays the source tests read —
     * always write both.
     */
    private fun setHint(resId: Int) {
        val text = getString(resId)
        binding.globeView.hintText = text
        binding.globeHint.text = text
    }

    override fun onResume() {
        super.onResume()
        // Phase 4: mirror the ambient gate into the scene (rotation, drift,
        // twinkle, shimmer) — also covers returning from system Settings with
        // animations switched off.
        binding.globeView.syncAmbientMotion()
        binding.globeView.setAnimating(true)
        // Any wash left over from a completed launch belongs to a window
        // transition that has already happened — drop it on return.
        binding.scanReveal.visibility = View.GONE
        startPulse()
        // Scans made on the Shelf (or new folders) must show up on return.
        loadDocs()
    }

    override fun onPause() {
        stopPulse()
        // A launch keeps its wash up (the Shelf must fade in over it);
        // anything else — home button, incoming call — drops it.
        SwTransitions.cancelReveal(binding.scanReveal)
        binding.globeView.setAnimating(false)
        super.onPause()
    }

    // ------------------------------------------------------------------
    // Phase 4: FAB pulse (infinite → gated on SwMotion.ambientAllowed)
    // ------------------------------------------------------------------

    /**
     * Start the two breathing rings. **Never call `start()` without the
     * gate** — an ungated infinite animator hangs the Robolectric suite
     * (the shadow Choreographer serves vsync eagerly inside `idle()`), and
     * with animations disabled the pulse must stay off entirely.
     */
    private fun startPulse() {
        if (pulse.isNotEmpty()) return // already running (defensive)
        if (!SwMotion.ambientAllowed(this)) return
        pulse += pulseOf(binding.fabPulse1, 0.55f, 0.14f, 1800L, 0L)
        pulse += pulseOf(binding.fabPulse2, 0.30f, 0.08f, 2200L, 900L)
        pulse.forEach { it.start() }
    }

    /** Cancel the pulse and restore the static XML alphas/scales (0.55/0.30). */
    private fun stopPulse() {
        pulse.forEach { it.cancel() }
        pulse.clear()
        binding.fabPulse1.alpha = 0.55f
        binding.fabPulse1.scaleX = 1f
        binding.fabPulse1.scaleY = 1f
        binding.fabPulse2.alpha = 0.30f
        binding.fabPulse2.scaleX = 1f
        binding.fabPulse2.scaleY = 1f
    }

    /**
     * One ring's loop: alpha + scale breathing, reversed so it eases back.
     * `AnimatorSet` has no `repeatCount` — the repeat lives on each child.
     */
    private fun pulseOf(
        view: View, fromAlpha: Float, toAlpha: Float, durationMs: Long, delayMs: Long
    ): AnimatorSet {
        fun loop(prop: android.util.Property<View, Float>, a: Float, b: Float) =
            ObjectAnimator.ofFloat(view, prop, a, b).apply {
                duration = durationMs
                startDelay = delayMs
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = SW_PATH
            }
        return AnimatorSet().apply {
            playTogether(
                loop(View.ALPHA, fromAlpha, toAlpha),
                loop(View.SCALE_X, 1f, 1.14f),
                loop(View.SCALE_Y, 1f, 1.14f)
            )
        }
    }

    private fun loadDocs() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                repo.listDocuments(includeVaulted = true)
            }
            docs = loaded
            binding.globeView.bindDocs(loaded)
            binding.globeView.contentDescription =
                getString(R.string.a11y_globe_summary, loaded.count { !it.isVaulted })
            binding.globeEmpty.visibility =
                if (loaded.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            // runQuery first: it owns the test-locked match counter and must
            // not depend on the status readout succeeding.
            runQuery(binding.globeSearchInput.text?.toString()?.trim().orEmpty())
            updateStatus()
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
        setHint(
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
        SwTransitions.warpToShelf(this)
    }

    /**
     * The scan chooser lives on the Shelf; open it right away. Phase 4 wraps
     * the launch in the radial reveal — the wash covers the globe first, then
     * the Shelf warps in on top. When ambient motion is off (Robolectric, or
     * the user disabled animations) `SwTransitions` runs [launch] in the same
     * frame, so behaviour is unchanged for tests and accessibility users.
     */
    private fun startShelfForScan() {
        // Re-entry guard: a double-tap while the reveal is up must not
        // launch the Shelf twice.
        if (binding.scanReveal.visibility == View.VISIBLE) return
        SwTransitions.revealScanLaunch(this, binding.fabGlobeScan, binding.scanReveal) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_SCANNER, true)
            )
            SwTransitions.warpToShelf(this)
        }
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
        val (builder, input) = com.scanwhirl.app.ui.SwDialog.input(
            this,
            hint = getString(R.string.vault_pin_hint),
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        )
        input.filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        builder
            .setTitle(R.string.vault_enter_pin_title)
            .setMessage(R.string.vault_enter_pin_msg)
            .setPositiveButton(R.string.unlock_pdf_open) { _, _ ->
                val pin = input.text?.toString()?.trim().orEmpty()
                if (Prefs.verifyVaultPin(this, pin)) {
                    onOk()
                } else {
                    Toast.makeText(this, R.string.vault_pin_wrong, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

    private fun promptUnlock(doc: ScanDocument, onOk: () -> Unit) {
        val (builder, input) = com.scanwhirl.app.ui.SwDialog.input(
            this,
            hint = getString(R.string.protect_hint),
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        builder
            .setTitle(R.string.unlock_pdf_title)
            .setMessage(getString(R.string.unlock_pdf_msg, doc.name))
            .setPositiveButton(R.string.unlock_pdf_open) { _, _ ->
                val pw = input.text?.toString().orEmpty()
                if (repo.verifyPdfPassword(doc.id, pw)) {
                    onOk()
                } else {
                    Toast.makeText(this, R.string.unlock_pdf_wrong, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

    companion object {
        /** M3 standard curve; `FastOutSlowInInterpolator` is not on the classpath. */
        private val SW_PATH = PathInterpolator(0.4f, 0f, 0.2f, 1f)

        fun intent(context: android.content.Context): Intent =
            Intent(context, GlobeHomeActivity::class.java)
    }
}
