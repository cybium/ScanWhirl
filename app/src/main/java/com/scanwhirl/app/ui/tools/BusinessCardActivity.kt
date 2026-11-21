package com.scanwhirl.app.ui.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.launchGalleryCrop
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.util.ContactParser
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ShareUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v2 UI: 03_tools_hub — Business card (Pro).
 *
 * Capture one card with the same on-device ML Kit scanner Scan uses (also a gallery picker), read
 * it with the app's existing on-device OCR, parse it with the pure [ContactParser], then show an
 * editable kit form. Actions: Save to contacts (`ACTION_INSERT`, no permission), Share as vCard
 * (a `.vcf` through FileProvider) and Save card image (a normal document through the publish
 * path). Nothing is written until one of those actions is used; leaving creates nothing.
 */
class BusinessCardActivity : BaseActivity() {

    private lateinit var repo: ScanRepository
    private var cardBytes: ByteArray? = null
    /** Cache path of the captured card; the Bundle only carries this string. */
    @Volatile
    private var imagePath: String? = null
    private var busy = false

    private lateinit var preview: ImageView
    private lateinit var form: LinearLayout
    private lateinit var hintView: TextView
    private lateinit var errorView: TextView
    private lateinit var progress: View

    private var nameField: EditText? = null
    private var companyField: EditText? = null
    private var jobField: EditText? = null
    private var phoneField: EditText? = null
    private var emailField: EditText? = null
    private var websiteField: EditText? = null
    private var addressField: EditText? = null

    private val scanner = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            showCaptureSheet()
            return@registerForActivityResult
        }
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        val uri = scan?.pages?.firstOrNull()?.imageUri
        if (uri == null) showCaptureSheet() else readImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ScanWhirlApp.from(this).repository
        setContentView(buildRoot())
        if (savedInstanceState?.getString(KEY_IMAGE_PATH) != null) {
            // T1: restore the captured image and the edited form directly. Running OCR again here
            // would throw the person's corrections away.
            restoreState(savedInstanceState)
        } else {
            // v2 UI run4a: always start with the same camera/gallery choice as ID card; the camera is
            // one tap away, and people without a good camera angle can pick a photo instead.
            showCaptureSheet()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_IMAGE_PATH, imagePath)
        outState.putBoolean(KEY_HAS_FORM, form.childCount > 0)
        outState.putStringArray(KEY_FIELDS, currentFieldValues())
    }

    private fun currentFieldValues(): Array<String> = arrayOf(
        nameField?.text?.toString().orEmpty(),
        companyField?.text?.toString().orEmpty(),
        jobField?.text?.toString().orEmpty(),
        phoneField?.text?.toString().orEmpty(),
        emailField?.text?.toString().orEmpty(),
        websiteField?.text?.toString().orEmpty(),
        addressField?.text?.toString().orEmpty()
    )

    private fun restoreState(state: Bundle) {
        val path = state.getString(KEY_IMAGE_PATH) ?: return
        val values = state.getStringArray(KEY_FIELDS)
        progress.isVisible = true
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { File(path).takeIf { it.isFile }?.readBytes() }.getOrNull()
            }
            progress.isVisible = false
            if (bytes == null) {
                fail(getString(R.string.tools_setup_image_failed))
                showCaptureSheet()
                return@launch
            }
            cardBytes = bytes
            preview.setImageBitmap(runCatching { ImageTools.decodeSampled(bytes, 800) }.getOrNull())
            preview.isVisible = true
            hintView.isVisible = false
            if (state.getBoolean(KEY_HAS_FORM, false) && values != null && values.size == FORM_FIELDS) {
                buildForm(values)
            } else {
                showCaptureSheet()
            }
        }
    }

    private fun writeImageCache(bytes: ByteArray): String? = runCatching {
        val dir = File(cacheDir, "t1_business_card").apply { mkdirs() }
        File(dir, "card.jpg").apply { writeBytes(bytes) }.absolutePath
    }.getOrNull()

    // ---- screen -----------------------------------------------------------

    private fun buildRoot(): View {
        val root = FrameLayout(this)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = SwStyle.dp(this@BusinessCardActivity, 18f)
            setPadding(pad, SwStyle.dp(this@BusinessCardActivity, 10f), pad, SwStyle.dp(this@BusinessCardActivity, 30f))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(SwStyle.iconButton(
            this, R.drawable.ic_sym_arrow_back, SwStyle.color(this, R.color.text_hologram_hi)
        ).apply {
            contentDescription = getString(R.string.tools_setup_back)
            setOnClickListener { finish() }
        })
        bar.addView(
            SwStyle.text(this, 24f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                text = getString(R.string.tool_business_card)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = SwStyle.dp(this@BusinessCardActivity, 10f)
            }
        )
        column.addView(bar)

        hintView = SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
            text = getString(R.string.tools_bc_hint)
            setLineSpacing(0f, 1.25f)
            setPadding(0, SwStyle.dp(this@BusinessCardActivity, 8f), 0, SwStyle.dp(this@BusinessCardActivity, 12f))
        }
        column.addView(hintView)

        preview = ImageView(this).apply {
            id = R.id.bcPreview
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = SwStyle.rounded(this@BusinessCardActivity, Color.WHITE, radiusDp = 16f)
            clipToOutline = true
            isVisible = false
        }
        column.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@BusinessCardActivity, 170f)
        ).apply { bottomMargin = SwStyle.dp(this@BusinessCardActivity, 10f) })

        errorView = SwStyle.text(this, 13f, SwStyle.color(this, R.color.laser_danger)).apply {
            id = R.id.bcError
            isVisible = false
        }
        column.addView(errorView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = SwStyle.dp(this@BusinessCardActivity, 8f) })

        form = LinearLayout(this).apply {
            id = R.id.bcForm
            orientation = LinearLayout.VERTICAL
        }
        column.addView(form)

        scroll.addView(column)
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        progress = FrameLayout(this).apply {
            id = R.id.toolSetupProgress
            isVisible = false
            setBackgroundColor(0xCC0E1316.toInt())
            isClickable = true
            addView(
                LinearLayout(this@BusinessCardActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(ProgressBar(this@BusinessCardActivity))
                    addView(SwStyle.text(
                        this@BusinessCardActivity, 14f,
                        SwStyle.color(this@BusinessCardActivity, R.color.text_hologram_mid)
                    ).apply {
                        text = getString(R.string.tools_bc_reading)
                        gravity = Gravity.CENTER
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = SwStyle.dp(this@BusinessCardActivity, 12f) })
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER }
            )
        }
        root.addView(progress, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.applySystemBarsPadding()
        return root
    }

    // ---- capture ----------------------------------------------------------

    private fun scan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setScannerMode(
                if (Prefs.autoEnhanceScan(this)) {
                    GmsDocumentScannerOptions.SCANNER_MODE_FULL
                } else {
                    GmsDocumentScannerOptions.SCANNER_MODE_BASE
                }
            )
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setPageLimit(1)
            .build()
        try {
            GmsDocumentScanning.getClient(options)
                .getStartScanIntent(this)
                .addOnSuccessListener { sender -> scanner.launch(IntentSenderRequest.Builder(sender).build()) }
                .addOnFailureListener { showCaptureSheet() }
        } catch (e: Exception) {
            showCaptureSheet()
        }
    }

    private fun showCaptureSheet() {
        SwSheet.builder(this)
            .setTitle(R.string.tool_business_card)
            .setMessage(R.string.tools_bc_scan_msg)
            .setPrimary(R.string.tools_bc_scan) { scan() }
            .setSecondary(R.string.tools_id_pick_gallery) { pickFromGallery() }
            .build()
            .show()
    }

    /** Gallery import through the shared scanner crop (same path as ID card and Home → Photos). */
    private fun pickFromGallery() {
        launchGalleryCrop(
            activity = this,
            maxPages = 1,
            // S1 review P0: no captured Activity; the live receiver handles the result.
            onResult = { host, _, uris ->
                if (host is BusinessCardActivity) host.handleGalleryCrop(uris)
            },
            onFallback = { host, _, uris ->
                if (host is BusinessCardActivity) host.handleGalleryCrop(uris)
            }
        )
    }

    /** S1 review P0: runs on the activity that actually received the picker result. */
    private fun handleGalleryCrop(uris: List<Uri>) {
        val uri = uris.firstOrNull()
        if (uri == null) showCaptureSheet() else readImage(uri)
    }

    private fun readImage(uri: Uri) {
        progress.isVisible = true
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { ImageTools.readPageBytes(it) }
                }.getOrNull()
            }
            progress.isVisible = false
            if (bytes == null) {
                fail(getString(R.string.tools_setup_image_failed))
                showCaptureSheet()
            } else {
                acceptCapture(bytes)
            }
        }
    }

    /**
     * A captured card (scanner or gallery); also the seam the UI tests drive. Runs the app's
     * on-device OCR, then fills the form.
     */
    internal fun acceptCapture(bytes: ByteArray) {
        cardBytes = bytes
        preview.setImageBitmap(runCatching { ImageTools.decodeSampled(bytes, 800) }.getOrNull())
        preview.isVisible = true
        hintView.isVisible = false
        errorView.isVisible = false
        progress.isVisible = true
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    // T1: cache the captured card off the main thread so rotation can restore it
                    // without asking the person to capture (and re-read) it again.
                    imagePath = writeImageCache(bytes)
                    recognizeForTest?.invoke(bytes) ?: OcrEngine.recognizeBytes(bytes)
                }
                progress.isVisible = false
                if (text.isBlank()) {
                    fail(getString(R.string.tools_bc_no_text))
                    return@launch
                }
                buildForm(ContactParser.parse(text))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress.isVisible = false
                fail(getString(R.string.tools_bc_failed, e.message ?: ""))
            }
        }
    }

    // ---- form -------------------------------------------------------------

    private fun buildForm(contact: ContactParser.Contact) = buildForm(
        arrayOf(
            contact.name, contact.company, contact.jobTitle,
            contact.phones.joinToString(", "), contact.emails.joinToString(", "),
            contact.website, contact.address
        )
    )

    /** Builds the editable form from seven saved values, without re-running OCR. */
    private fun buildForm(values: Array<String>) {
        form.removeAllViews()
        nameField = field(R.string.tools_bc_name, R.drawable.ic_sym_person, R.id.bcName, values[0])
        companyField = field(R.string.tools_bc_company, R.drawable.ic_sym_contacts, R.id.bcCompany, values[1])
        jobField = field(R.string.tools_bc_job, R.drawable.ic_sym_badge, R.id.bcJob, values[2])
        phoneField = field(R.string.tools_bc_phone, R.drawable.bc_ic_phone, R.id.bcPhone, values[3])
        emailField = field(R.string.tools_bc_email, R.drawable.bc_ic_mail, R.id.bcEmail, values[4])
        websiteField = field(R.string.tools_bc_website, R.drawable.ic_sym_language, R.id.bcWebsite, values[5])
        addressField = field(
            R.string.tools_bc_address, R.drawable.bc_ic_place, R.id.bcAddress, values[6],
            multiLine = true
        )
        for (edit in listOfNotNull(nameField, companyField, jobField, phoneField, emailField, websiteField, addressField)) {
            form.addView(edit.tag as View, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = SwStyle.dp(this@BusinessCardActivity, 8f) })
        }

        val save = action(getString(R.string.tools_bc_save_contact), R.id.bcSaveContact, primary = true) {
            saveToContacts()
        }
        form.addView(save, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@BusinessCardActivity, 54f)
        ).apply { topMargin = SwStyle.dp(this@BusinessCardActivity, 14f) })
        val share = action(getString(R.string.tools_bc_share_vcard), R.id.bcShareVCard) { shareVCard() }
        form.addView(share, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@BusinessCardActivity, 50f)
        ).apply { topMargin = SwStyle.dp(this@BusinessCardActivity, 10f) })
        val image = action(getString(R.string.tools_bc_save_image), R.id.bcSaveImage) { saveCardImage() }
        form.addView(image, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@BusinessCardActivity, 50f)
        ).apply { topMargin = SwStyle.dp(this@BusinessCardActivity, 10f) })
    }

    private fun field(
        hintRes: Int,
        iconRes: Int,
        id: Int,
        value: String,
        multiLine: Boolean = false
    ): EditText {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = SwStyle.rounded(
                this@BusinessCardActivity,
                SwStyle.color(this@BusinessCardActivity, R.color.glass_surface),
                radiusDp = 14f,
                stroke = SwStyle.color(this@BusinessCardActivity, R.color.sw_field_border)
            )
            val pad = SwStyle.dp(this@BusinessCardActivity, 12f)
            setPadding(pad, pad, pad, pad)
        }
        row.addView(ImageView(this).apply {
            setImageResource(iconRes)
        }, LinearLayout.LayoutParams(
            SwStyle.dp(this@BusinessCardActivity, 22f), SwStyle.dp(this@BusinessCardActivity, 22f)
        ).apply { marginEnd = SwStyle.dp(this@BusinessCardActivity, 10f) })
        val edit = EditText(this).apply {
            this.id = id
            hint = getString(hintRes)
            setHintTextColor(SwStyle.color(this@BusinessCardActivity, R.color.text_hologram_dim))
            setTextColor(SwStyle.color(this@BusinessCardActivity, R.color.text_hologram_hi))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = null
            inputType = if (multiLine) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            isSingleLine = !multiLine
            minLines = if (multiLine) 2 else 1
            setText(value)
        }
        row.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // The row is what the form holds; the EditText keeps its id for tests and focus.
        return edit.apply { tag = row }
    }

    private fun action(text: String, id: Int, primary: Boolean = false, onClick: () -> Unit): TextView =
        SwStyle.text(this, 15f, SwStyle.color(
            this, if (primary) R.color.sw_disc_ink else R.color.text_hologram_hi
        ), bold = true).apply {
            this.id = id
            this.text = text
            gravity = Gravity.CENTER
            background = if (primary) {
                SwStyle.rounded(this@BusinessCardActivity, SwStyle.color(this@BusinessCardActivity, R.color.cyan_primary), radiusDp = 16f)
            } else {
                SwStyle.rounded(
                    this@BusinessCardActivity,
                    SwStyle.color(this@BusinessCardActivity, R.color.glass_surface),
                    radiusDp = 16f,
                    stroke = SwStyle.color(this@BusinessCardActivity, R.color.sw_cyan_hair)
                )
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun currentContact(): ContactParser.Contact {
        fun text(field: EditText?): String = field?.text?.toString()?.trim().orEmpty()
        fun list(value: String): List<String> =
            value.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        return ContactParser.Contact(
            name = text(nameField),
            company = text(companyField),
            jobTitle = text(jobField),
            phones = list(text(phoneField)),
            emails = list(text(emailField)),
            website = text(websiteField),
            address = text(addressField)
        )
    }

    // ---- actions ----------------------------------------------------------

    private fun saveToContacts() {
        val contact = currentContact()
        if (contact.name.isBlank()) return fail(getString(R.string.tools_bc_need_name))
        try {
            val intent = ContactIntents.insert(contact)
            val dropped = intent.getIntExtra(ContactIntents.EXTRA_DROPPED, 0)
            startActivity(intent)
            // T1: never silently lose phone numbers or emails that did not fit the Insert slots.
            if (dropped > 0) {
                Toast.makeText(
                    this, getString(R.string.tools_bc_contacts_dropped, dropped), Toast.LENGTH_LONG
                ).show()
            }
        } catch (_: ActivityNotFoundException) {
            fail(getString(R.string.tools_bc_contacts_failed))
        }
    }

    private fun shareVCard() {
        val contact = currentContact()
        if (contact.name.isBlank()) return fail(getString(R.string.tools_bc_need_name))
        progress.isVisible = true
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val safe = ShareUtils.sanitizeName(contact.name).ifBlank { "contact" }
                    val out = ShareUtils.newShareTarget(this@BusinessCardActivity, "$safe.vcf")
                    Durable.writeFile(out, ContactParser.vCard(contact).toByteArray(Charsets.UTF_8))
                    out
                }
                progress.isVisible = false
                ShareUtils.shareFile(this@BusinessCardActivity, file, "text/x-vcard")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress.isVisible = false
                fail(getString(R.string.tools_bc_vcard_failed, e.message ?: ""))
            }
        }
    }

    private fun saveCardImage() {
        if (busy) return
        val bytes = cardBytes ?: return fail(getString(R.string.tools_setup_image_failed))
        val contact = currentContact()
        val fallbackName = getString(R.string.tools_bc_document_name)
        busy = true
        progress.isVisible = true
        lifecycleScope.launch {
            try {
                val doc = withContext(Dispatchers.IO) {
                    val page = ImageTools.normalizePage(bytes)
                    val name = contact.name.ifBlank { fallbackName }
                    repo.createDocument(name, listOf(page))
                }
                progress.isVisible = false
                startActivity(
                    ToolResultActivity.intent(this@BusinessCardActivity, "business_card", docIds = listOf(doc.id))
                )
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress.isVisible = false
                busy = false
                fail(getString(R.string.tools_bc_failed, e.message ?: ""))
            }
        }
    }

    private fun fail(message: String) {
        errorView.text = message
        errorView.isVisible = true
    }

    companion object {
        /** Test seam: replaces the on-device OCR with a fixed text in UI tests. */
        internal var recognizeForTest: ((ByteArray) -> String)? = null

        private const val KEY_IMAGE_PATH = "t1_bc_image_path"
        private const val KEY_HAS_FORM = "t1_bc_has_form"
        private const val KEY_FIELDS = "t1_bc_fields"
        private const val FORM_FIELDS = 7

        fun intent(context: Context): Intent = Intent(context, BusinessCardActivity::class.java)
    }
}
