package com.scanwhirl.app.ui.qr

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.util.ClipboardPrivacy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2 UI: 03_tools_hub / QR code reader — the scan result screen (design §3, Create group).
 *
 * Tapping the tile opens this screen and the Google code scanner straight away. The scan is
 * pure data until it succeeds: a cancelled scan saves nothing. A result shows its type, the
 * value, the type's actions (open link, add to contacts, call, compose, open map, copy,
 * share) and a local history of the last 20 codes with a Clear button. Every scanner failure
 * (module not downloaded yet, no Play services) is a SwSheet, never a crash.
 *
 * DEVICE-UNPROVEN: the Google code scanner UI and its first-use module download.
 */
class QrActivity : BaseActivity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, QrActivity::class.java)

        private const val STATE_VALUE = "qr_value"
        private const val STATE_KIND = "qr_kind"
        private const val SCAN_TIMEOUT_MS = 60_000L
    }

    private lateinit var history: QrHistory
    private lateinit var contentHost: LinearLayout
    private lateinit var titleBar: TextView

    private var current: QrContent? = null
    private var scanning = false
    private var scanToken = 0
    private var scanAwaitingResume = false
    private var pausedDuringScan = false
    private val scanTimeout = Handler(Looper.getMainLooper())
    private var lastCopiedSecret: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        history = QrHistory(this)
        savedInstanceState?.let { state ->
            val value = state.getString(STATE_VALUE)
            val kind = state.getString(STATE_KIND)
            if (value != null) {
                current = QrContent.parse(
                    value,
                    runCatching { QrKind.valueOf(kind.orEmpty()) }.getOrDefault(QrKind.TEXT),
                    getString(R.string.qr_contact_fallback)
                )
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SwStyle.gradient(this@QrActivity, 0x1200F0FF, SwStyle.color(this@QrActivity, R.color.rv_bg), radiusDp = 0f)
        }
        root.addView(appBar())
        root.addView(header())
        val scroll = ScrollView(this).apply { isFillViewport = true }
        contentHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = SwStyle.dp(this@QrActivity, 18f)
            setPadding(pad, 0, pad, SwStyle.dp(this@QrActivity, 32f))
        }
        scroll.addView(
            contentHost,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        root.applySystemBarsPadding()

        render()
        if (current == null && savedInstanceState == null) scanNow()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        current?.let { content ->
            outState.putString(STATE_VALUE, content.raw)
            outState.putString(STATE_KIND, content.kind.name)
        }
    }

    // ------------------------------------------------------------------
    // Chrome
    // ------------------------------------------------------------------

    private fun appBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(
            SwStyle.dp(this@QrActivity, 18f), SwStyle.dp(this@QrActivity, 12f),
            SwStyle.dp(this@QrActivity, 10f), SwStyle.dp(this@QrActivity, 4f)
        )
        addView(
            SwStyle.text(this@QrActivity, 24f, SwStyle.color(this@QrActivity, R.color.text_hologram_hi), bold = true).apply {
                text = getString(R.string.qr_title)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        addView(
            SwStyle.iconButton(
                this@QrActivity, R.drawable.ic_sym_close, SwStyle.color(this@QrActivity, R.color.text_hologram_dim)
            ).apply {
                id = R.id.qrClose
                contentDescription = getString(R.string.qr_close)
                setOnClickListener { finish() }
            }
        )
    }

    private fun header(): TextView {
        titleBar = SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_dim), bold = true).apply {
            id = R.id.qrStatus
            setPadding(
                SwStyle.dp(this@QrActivity, 18f), 0,
                SwStyle.dp(this@QrActivity, 18f), SwStyle.dp(this@QrActivity, 8f)
            )
        }
        return titleBar
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private fun scanNow() {
        if (scanning) return
        scanning = true
        val token = ++scanToken
        // A scanner that never reports back must not leave Scan as a dead tap. But its Play-
        // services sheet may still be on screen, so keep Scan blocked until onResume proves
        // that UI has gone; otherwise a second tap opens two concurrent scanner sheets.
        val timeout = Runnable {
            if (scanning && token == scanToken) scanAwaitingResume = true
        }
        scanTimeout.postDelayed(timeout, SCAN_TIMEOUT_MS)
        QrScanners.get(this).scan(this) { outcome ->
            // A stale callback must touch NOTHING: an older scan's late answer may arrive while
            // a newer scan is already waiting for its own resume. Checking the token only after
            // clearing the flag used to leave the newer scan permanently disabled.
            if (isFinishing || isDestroyed || token != scanToken) return@scan
            scanTimeout.removeCallbacks(timeout)
            scanAwaitingResume = false
            scanning = false
            onScanOutcome(outcome)
        }
    }

    override fun onPause() {
        super.onPause()
        // A Play-services scanner is a separate activity: leaving for it while a scan is in
        // flight means this screen will be resumed when that UI is gone.
        if (scanning) pausedDuringScan = true
    }

    override fun onResume() {
        super.onResume()
        // Safety release: whenever no scanner UI is in front any more, Scan must work again,
        // even if a late callback from an older scan failed to arrive or was ignored.
        if (scanAwaitingResume || pausedDuringScan) {
            scanAwaitingResume = false
            pausedDuringScan = false
            scanning = false
        }
    }

    private fun onScanOutcome(outcome: QrScanOutcome) {
        if (isFinishing || isDestroyed) return
        when (outcome) {
            is QrScanOutcome.Found -> {
                val content = QrContent.parse(
                    outcome.rawValue, outcome.kind, getString(R.string.qr_contact_fallback)
                )
                if (content.raw.isBlank()) {
                    showError(getString(R.string.qr_empty_code))
                    render()
                    return
                }
                history.add(QrContent.historyValue(content.raw, content.kind), content.kind)
                current = content
                render()
            }
            QrScanOutcome.Cancelled -> {
                if (current == null) {
                    finish()
                } else {
                    render()
                }
            }
            is QrScanOutcome.Failed -> {
                showError(outcome.message)
                render()
            }
        }
    }

    private fun render() {
        titleBar.text = getString(
            if (current == null) R.string.qr_scan_title else R.string.qr_result_title
        )
        contentHost.removeAllViews()
        val content = current
        if (content == null) {
            contentHost.addView(emptyCard())
        } else {
            contentHost.addView(resultCard(content))
        }
        val items = history.list()
        if (items.isNotEmpty()) contentHost.addView(historyCard(items))
    }

    // ------------------------------------------------------------------
    // Result
    // ------------------------------------------------------------------

    private fun emptyCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = SwStyle.card(this@QrActivity, 26f)
            val pad = SwStyle.dp(this@QrActivity, 22f)
            setPadding(pad, pad, pad, pad)
            id = R.id.qrEmpty
        }
        card.addView(
            com.scanwhirl.app.ui.kit.SwMotion.brandArt(this),
            LinearLayout.LayoutParams(SwStyle.dp(this, 64f), SwStyle.dp(this, 64f))
        )
        card.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_sym_qr_code_scanner)
                imageTintList = ColorStateList.valueOf(SwStyle.color(this@QrActivity, R.color.cyan_primary))
            },
            LinearLayout.LayoutParams(SwStyle.dp(this, 56f), SwStyle.dp(this, 56f))
        )
        card.addView(
            SwStyle.text(this, 18f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                text = getString(R.string.qr_scan_title)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(this@QrActivity, 12f) }
        )
        card.addView(
            SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
                text = getString(R.string.qr_scan_body)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.3f)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(this@QrActivity, 6f) }
        )
        card.addView(
            primaryButton(R.string.qr_scan_button).apply {
                id = R.id.qrScan
                setOnClickListener { scanNow() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this, 54f))
                .apply { topMargin = SwStyle.dp(this@QrActivity, 18f) }
        )
        return card
    }

    private fun resultCard(content: QrContent): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SwStyle.card(this@QrActivity, 26f)
            val pad = SwStyle.dp(this@QrActivity, 18f)
            setPadding(pad, pad, pad, pad)
        }

        val kindRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        kindRow.addView(
            ImageView(this).apply {
                setImageResource(iconFor(content.kind))
                imageTintList = ColorStateList.valueOf(SwStyle.color(this@QrActivity, R.color.cyan_primary))
            },
            LinearLayout.LayoutParams(SwStyle.dp(this, 20f), SwStyle.dp(this, 20f))
        )
        kindRow.addView(
            SwStyle.text(this, 12f, SwStyle.color(this, R.color.cyan_dim), bold = true, letterSpacing = 0.06f).apply {
                id = R.id.qrKind
                text = getString(labelFor(content.kind)).uppercase(Locale.getDefault())
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = SwStyle.dp(this@QrActivity, 8f) }
        )
        card.addView(kindRow)

        card.addView(
            SwStyle.text(this, 20f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                id = R.id.qrTitle
                text = content.title
                setLineSpacing(0f, 1.2f)
                setTextIsSelectable(true)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(this@QrActivity, 10f) }
        )

        content.fields.forEach { field ->
            card.addView(fieldRow(field))
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            id = R.id.qrActions
        }
        content.actions.forEachIndexed { index, action ->
            val button = if (index == 0) {
                primaryButton(labelFor(action)).apply { id = R.id.qrPrimaryAction }
            } else {
                outlineButton(labelFor(action), iconFor(action))
            }
            button.setOnClickListener { runAction(action, content) }
            actions.addView(
                button,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this, 50f))
                    .apply { topMargin = SwStyle.dp(this@QrActivity, if (index == 0) 16f else 8f) }
            )
        }
        card.addView(actions)

        card.addView(
            outlineButton(R.string.qr_scan_again, R.drawable.ic_sym_qr_code_scanner).apply {
                id = R.id.qrScanAgain
                setOnClickListener { scanNow() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this, 46f))
                .apply { topMargin = SwStyle.dp(this@QrActivity, 12f) }
        )
        return card
    }

    private fun fieldRow(field: QrField): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@QrActivity, 12f) }
        addView(
            SwStyle.text(this@QrActivity, 11.5f, SwStyle.color(this@QrActivity, R.color.cyan_dim), bold = true, letterSpacing = 0.06f).apply {
                id = R.id.qrFieldLabel
                text = getString(field.labelRes).uppercase(Locale.getDefault())
            }
        )
        val valueView = SwStyle.text(this@QrActivity, 15f, SwStyle.color(this@QrActivity, R.color.text_hologram_hi)).apply {
            id = if (field.secret) R.id.qrSecretValue else R.id.qrFieldValue
            // A password is hidden until the user deliberately reveals it; Copy still works.
            text = if (field.secret) QrContent.MASK else field.value
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.25f)
        }
        addView(
            valueView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(this@QrActivity, 3f) }
        )
        if (field.secret) {
            addView(
                SwStyle.text(this@QrActivity, 14f, SwStyle.color(this@QrActivity, R.color.cyan_primary), bold = true).apply {
                    id = R.id.qrFieldToggle
                    text = getString(R.string.qr_show)
                    asButton(this)
                    setOnClickListener {
                        val reveal = text == getString(R.string.qr_show)
                        valueView.text = if (reveal) field.value else QrContent.MASK
                        text = getString(if (reveal) R.string.qr_hide else R.string.qr_show)
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, SwStyle.dp(this@QrActivity, 48f)
                ).apply { topMargin = SwStyle.dp(this@QrActivity, 4f) }
            )
        }
    }

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    private fun historyCard(items: List<QrHistory.Item>): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@QrActivity, 14f) }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            SwStyle.text(this, 16f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                text = getString(R.string.qr_recent)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(
            SwStyle.text(this, 14f, SwStyle.color(this, R.color.cyan_primary), bold = true).apply {
                id = R.id.qrHistoryClear
                text = getString(R.string.qr_clear)
                isClickable = true
                isFocusable = true
                asButton(this)
                setPadding(
                    SwStyle.dp(this@QrActivity, 8f), SwStyle.dp(this@QrActivity, 6f),
                    SwStyle.dp(this@QrActivity, 2f), SwStyle.dp(this@QrActivity, 6f)
                )
                setOnClickListener {
                    history.clear()
                    // A password copied from a result is a secret too: drop it with the history.
                    lastCopiedSecret?.let { ClipboardPrivacy.clearIfUnchanged(this@QrActivity, it) }
                    lastCopiedSecret = null
                    Toast.makeText(this@QrActivity, R.string.qr_cleared, Toast.LENGTH_SHORT).show()
                    render()
                }
            }
        )
        card.addView(header)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            id = R.id.qrHistoryList
        }
        val stamp = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
        items.forEach { item ->
            list.addView(
                historyRow(item, stamp.format(Date(item.at))).apply {
                    id = R.id.qrHistoryRow
                    setOnClickListener {
                        current = QrContent.parse(
                            item.value, item.kind, getString(R.string.qr_contact_fallback)
                        )
                        render()
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = SwStyle.dp(this@QrActivity, 8f) }
            )
        }
        card.addView(list)
        return card
    }

    private fun historyRow(item: QrHistory.Item, whenText: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = SwStyle.rounded(
            this@QrActivity,
            SwStyle.color(this@QrActivity, R.color.void_nebula),
            radiusDp = 14f,
            stroke = SwStyle.color(this@QrActivity, R.color.sw_field_border)
        )
        val pad = SwStyle.dp(this@QrActivity, 12f)
        setPadding(pad, pad, pad, pad)
        isClickable = true
        isFocusable = true
        asButton(this)
        addView(
            SwStyle.text(this@QrActivity, 11f, SwStyle.color(this@QrActivity, R.color.text_hologram_dim), bold = true).apply {
                text = getString(R.string.qr_history_meta, getString(labelFor(item.kind)), whenText)
            }
        )
        addView(
            SwStyle.text(this@QrActivity, 14f, SwStyle.color(this@QrActivity, R.color.text_hologram_hi)).apply {
                text = QrContent.parse(item.value, item.kind).title
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(this@QrActivity, 3f) }
        )
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private fun runAction(action: QrAction, content: QrContent) {
        when (action) {
            QrAction.OPEN_LINK, QrAction.OPEN_MAP -> {
                val uri = content.actionUri
                if (uri == null) showError(getString(R.string.qr_no_app)) else openUri(Intent.ACTION_VIEW, uri)
            }
            QrAction.CALL -> {
                val uri = content.actionUri
                if (uri == null) showError(getString(R.string.qr_no_app)) else openUri(Intent.ACTION_DIAL, uri)
            }
            QrAction.COMPOSE_EMAIL -> {
                val uri = content.actionUri
                if (uri == null) showError(getString(R.string.qr_no_app)) else openUri(Intent.ACTION_SENDTO, uri)
            }
            QrAction.ADD_CONTACT -> addContact(content)
            QrAction.COPY -> copy(content.copyText)
            QrAction.SHARE -> share(content.raw)
        }
    }

    private fun openUri(action: String, uri: String) {
        try {
            startActivity(Intent(action, Uri.parse(uri)))
        } catch (e: ActivityNotFoundException) {
            showError(getString(R.string.qr_no_app))
        } catch (e: Exception) {
            showError(getString(R.string.qr_no_app))
        }
    }

    /** [ContactsContract.Intents.Insert] asks the contacts app to add a person: no permission. */
    private fun addContact(content: QrContent) {
        val intent = Intent(Intent.ACTION_INSERT).setType(ContactsContract.Contacts.CONTENT_TYPE)
        content.fields.firstOrNull { it.labelRes == R.string.qr_field_name }?.let {
            intent.putExtra(ContactsContract.Intents.Insert.NAME, it.value)
        }
        content.fields.firstOrNull { it.labelRes == R.string.qr_field_phone }?.let {
            intent.putExtra(ContactsContract.Intents.Insert.PHONE, it.value)
        }
        content.fields.firstOrNull { it.labelRes == R.string.qr_field_email }?.let {
            intent.putExtra(ContactsContract.Intents.Insert.EMAIL, it.value)
        }
        content.fields.firstOrNull { it.labelRes == R.string.qr_field_org }?.let {
            intent.putExtra(ContactsContract.Intents.Insert.COMPANY, it.value)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            copy(content.raw)
            Toast.makeText(this, R.string.qr_no_contacts, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            showError(getString(R.string.qr_no_app))
        }
    }

    private fun copy(text: String) {
        // Only a Wi-Fi password is a secret: mark it sensitive, auto-clear it and say so.
        val secret = current?.kind == QrKind.WIFI
        ClipboardPrivacy.copy(this, getString(R.string.qr_title), text, sensitive = secret)
        lastCopiedSecret = if (secret) text else null
        Toast.makeText(
            this,
            if (secret) R.string.qr_copied_sensitive else R.string.qr_copied,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun share(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.qr_share)))
        } catch (e: Exception) {
            showError(getString(R.string.qr_no_app))
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private fun labelFor(kind: QrKind): Int = when (kind) {
        QrKind.URL -> R.string.qr_kind_url
        QrKind.WIFI -> R.string.qr_kind_wifi
        QrKind.CONTACT -> R.string.qr_kind_contact
        QrKind.PHONE -> R.string.qr_kind_phone
        QrKind.EMAIL -> R.string.qr_kind_email
        QrKind.SMS -> R.string.qr_kind_sms
        QrKind.GEO -> R.string.qr_kind_geo
        QrKind.TEXT -> R.string.qr_kind_text
    }

    private fun labelFor(action: QrAction): Int = when (action) {
        QrAction.OPEN_LINK -> R.string.qr_open_link
        QrAction.ADD_CONTACT -> R.string.qr_add_contact
        QrAction.CALL -> R.string.qr_call
        QrAction.COMPOSE_EMAIL -> R.string.qr_compose_email
        QrAction.OPEN_MAP -> R.string.qr_open_map
        QrAction.COPY -> R.string.qr_copy
        QrAction.SHARE -> R.string.qr_share
    }

    /** The kit has no phone/mail/wifi glyphs, so those types use the QR mark plus their label. */
    private fun iconFor(kind: QrKind): Int = when (kind) {
        QrKind.URL -> R.drawable.ic_sym_language
        QrKind.CONTACT -> R.drawable.ic_sym_person
        QrKind.GEO -> R.drawable.ic_sym_travel_explore
        QrKind.SMS -> R.drawable.ic_sym_notes_lines
        QrKind.TEXT -> R.drawable.ic_sym_notes
        QrKind.WIFI, QrKind.PHONE, QrKind.EMAIL -> R.drawable.ic_sym_qr_code_scanner
    }

    private fun iconFor(action: QrAction): Int = when (action) {
        QrAction.COPY -> R.drawable.ic_sym_content_copy
        QrAction.SHARE -> R.drawable.ic_sym_share
        QrAction.OPEN_LINK -> R.drawable.ic_sym_language
        QrAction.OPEN_MAP -> R.drawable.ic_sym_travel_explore
        QrAction.ADD_CONTACT -> R.drawable.ic_sym_person
        QrAction.CALL -> R.drawable.ic_sym_qr_code_scanner
        QrAction.COMPOSE_EMAIL -> R.drawable.ic_sym_qr_code_scanner
    }

    private fun primaryButton(textRes: Int): TextView =
        SwStyle.text(this, 15f, SwStyle.color(this, R.color.sw_disc_ink), bold = true).apply {
            text = getString(textRes)
            gravity = Gravity.CENTER
            background = SwStyle.rounded(this@QrActivity, SwStyle.color(this@QrActivity, R.color.cyan_primary), radiusDp = 16f)
            isClickable = true
            isFocusable = true
            asButton(this)
        }

    private fun outlineButton(textRes: Int, icon: Int): TextView =
        SwStyle.text(this, 14f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
            text = getString(textRes)
            gravity = Gravity.CENTER
            background = SwStyle.rounded(
                this@QrActivity,
                SwStyle.color(this@QrActivity, R.color.sw_cyan_soft),
                radiusDp = 14f,
                stroke = SwStyle.color(this@QrActivity, R.color.sw_cyan_hair_strong)
            )
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            compoundDrawablePadding = SwStyle.dp(this@QrActivity, 7f)
            compoundDrawableTintList = ColorStateList.valueOf(SwStyle.color(this@QrActivity, R.color.cyan_primary))
            isClickable = true
            isFocusable = true
            asButton(this)
        }

    /** Clickable rows and TextViews act as buttons: a role for TalkBack and a 48dp touch target. */
    private fun asButton(view: View) {
        view.minimumHeight = SwStyle.dp(this, 48f)
        ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        })
    }

    private fun showError(message: String) {
        SwSheet.builder(this)
            .setTitle(R.string.qr_title)
            .setMessage(message)
            .setPrimary(R.string.sheet_got_it)
            .build()
            .show()
    }
}
