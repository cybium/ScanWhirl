package com.scanwhirl.app.ui.protect

import android.content.res.ColorStateList
import android.text.InputType
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.scanwhirl.app.R

/**
 * v2 Protect PDF sheet — mockup `05_protect_pdf`, design `70` §4.2.
 *
 * The password is typed twice with a show toggle, a four-segment strength meter (minimum 6,
 * "Weak" below 10), the plain AES-256 sentence, and the two ways out: **Protect & share** or
 * **Save protected copy**. The Protect button only enables when the text is long enough and both
 * fields match, so the sheet never sends the repository a password the user mistyped.
 *
 * "Remember in Vault" is shown only when a Vault password store is actually wired in
 * ([rememberAvailable]); until L2's Vault API merges it stays hidden rather than pretending.
 */
object ProtectSheet {

    /** Material's disabled alpha; a disabled button at full alpha still looks tappable. */
    private const val DISABLED_ALPHA = 0.38f

    data class Doc(val name: String, val pages: Int, val sizeBytes: Long)

    /**
     * @param titleRes [R.string.protect_sheet_title] for Protect, [R.string.protect_change_title]
     *   when the same sheet changes an existing password.
     * @param rememberAvailable true only when the Vault can be given the password (L2 API).
     * @param onProtect receives the password, whether to remember it, and whether the user chose
     *   "Protect & share" (true) or "Save protected copy" (false).
     */
    fun show(
        activity: AppCompatActivity,
        doc: Doc,
        titleRes: Int = R.string.protect_sheet_title,
        primaryTextRes: Int = R.string.protect_action_share,
        secondaryVisible: Boolean = true,
        rememberAvailable: Boolean = false,
        onProtect: (password: String, remember: Boolean, share: Boolean) -> Unit
    ): BottomSheetDialog {
        val dialog = BottomSheetDialog(activity)
        val root = LayoutInflater.from(activity).inflate(R.layout.sheet_protect, null)
        dialog.setContentView(root)
        dialog.window?.setDimAmount(0.85f)
        com.scanwhirl.app.util.SecureView.apply(dialog.window)
        // H4: the app is edge-to-edge, so the window itself must resize for the keyboard as
        // well; the sheet then consumes any IME inset BELOW that resize as padding on the
        // scroll container, so the focused field and the primary button stay above the
        // keyboard on every API level (pre-30 only reports the IME through a resize).
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnShowListener {
            dialog.behavior.isFitToContents = false
            dialog.behavior.skipCollapsed = true
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            val scroll = root as? ViewGroup ?: return@setOnShowListener
            val baseBottom = scroll.paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                view.setPadding(
                    view.paddingLeft,
                    view.paddingTop,
                    view.paddingRight,
                    baseBottom + maxOf(ime, bars)
                )
                view.requestLayout()
                view.findFocus()?.let { focused ->
                    focused.post {
                        focused.requestRectangleOnScreen(
                            android.graphics.Rect(0, 0, focused.width, focused.height), true
                        )
                    }
                }
                insets
            }
            root.post { ViewCompat.requestApplyInsets(scroll) }
            ViewCompat.setAccessibilityPaneTitle(
                dialog.window?.decorView ?: root, activity.getString(titleRes)
            )
        }

        root.findViewById<TextView>(R.id.protectTitle).setText(titleRes)
        // TalkBack starts at the pane title instead of the first control (a11y audit, S3).
        ViewCompat.setAccessibilityPaneTitle(root, activity.getString(titleRes))
        val chip = root.findViewById<View>(R.id.protectDocChip)
        root.findViewById<TextView>(R.id.protectDocName).text = doc.name
        val pagesView = root.findViewById<TextView>(R.id.protectDocPages)
        val metaView = root.findViewById<TextView>(R.id.protectDocMeta)
        if (doc.name.isBlank()) {
            // A device PDF that has not been imported yet: there is no honest name, count or size.
            chip.visibility = View.GONE
        } else if (doc.pages > 0) {
            pagesView.text = doc.pages.toString()
            val size = Formatter.formatShortFileSize(activity, doc.sizeBytes)
            metaView.text = activity.resources.getQuantityString(
                R.plurals.protect_doc_meta, doc.pages, doc.pages, size
            )
        } else {
            pagesView.visibility = View.GONE
            metaView.visibility = View.GONE
        }
        root.findViewById<View>(R.id.protectClose).setOnClickListener { dialog.dismiss() }

        val password = root.findViewById<EditText>(R.id.protectPassword)
        val confirm = root.findViewById<EditText>(R.id.protectConfirm)
        val showButton = root.findViewById<ImageButton>(R.id.protectPasswordShow)
        val check = root.findViewById<ImageView>(R.id.protectConfirmCheck)
        val strength = root.findViewById<TextView>(R.id.protectStrength)
        val meters = listOf(
            root.findViewById<View>(R.id.protectMeter1),
            root.findViewById<View>(R.id.protectMeter2),
            root.findViewById<View>(R.id.protectMeter3),
            root.findViewById<View>(R.id.protectMeter4)
        )
        val shareButton = root.findViewById<MaterialButton>(R.id.protectShare)
        val saveButton = root.findViewById<MaterialButton>(R.id.protectSave)
        val rememberRow = root.findViewById<View>(R.id.protectRememberRow)
        val remember = root.findViewById<SwitchCompat>(R.id.protectRemember)

        shareButton.setText(primaryTextRes)
        saveButton.visibility = if (secondaryVisible) View.VISIBLE else View.GONE
        rememberRow.visibility = if (rememberAvailable) View.VISIBLE else View.GONE
        remember.isChecked = rememberAvailable

        var revealed = false
        showButton.setOnClickListener {
            revealed = !revealed
            applyVisibility(password, revealed)
            applyVisibility(confirm, revealed)
            showButton.setImageResource(
                if (revealed) R.drawable.ic_protect_visibility_off else R.drawable.ic_visibility
            )
            showButton.contentDescription = activity.getString(
                if (revealed) R.string.protect_hide_password else R.string.protect_show_password
            )
            password.setSelection(password.text.length)
        }

        fun refresh() {
            renderStrength(activity, password.text.toString(), meters, strength)
            val typed = password.text.toString()
            val repeated = confirm.text.toString()
            val matches = ProtectPassword.matches(typed, repeated)
            check.visibility = if (matches) View.VISIBLE else View.GONE
            // H3 (found on device): two different passwords made the action do nothing at all.
            // The mismatch is now explained under the confirm field while the person types.
            confirm.error = if (repeated.isNotEmpty() && !matches) {
                activity.getString(R.string.protect_passwords_mismatch)
            } else {
                null
            }
            val enabled = ProtectPassword.accepts(typed) && matches
            shareButton.isEnabled = enabled
            saveButton.isEnabled = enabled
            // A disabled MaterialButton keeps full alpha and still looks tappable; grey it out
            // (DeadButtonAuditTest, owner rule).
            shareButton.alpha = if (enabled) 1f else DISABLED_ALPHA
            saveButton.alpha = if (enabled) 1f else DISABLED_ALPHA
        }

        val watcher = SimpleTextWatcher { refresh() }
        password.addTextChangedListener(watcher)
        confirm.addTextChangedListener(watcher)
        confirm.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                submit(password, confirm, remember, rememberAvailable, false, dialog, onProtect)
                true
            } else {
                false
            }
        }

        shareButton.setOnClickListener {
            submit(password, confirm, remember, rememberAvailable, true, dialog, onProtect)
        }
        saveButton.setOnClickListener {
            submit(password, confirm, remember, rememberAvailable, false, dialog, onProtect)
        }
        refresh()

        dialog.show()
        password.requestFocus()
        return dialog
    }

    private fun submit(
        password: EditText,
        confirm: EditText,
        remember: SwitchCompat,
        rememberAvailable: Boolean,
        share: Boolean,
        dialog: BottomSheetDialog,
        onProtect: (String, Boolean, Boolean) -> Unit
    ) {
        val text = password.text.toString()
        if (!ProtectPassword.matches(text, confirm.text.toString())) {
            // H3: say why nothing happened instead of silently returning.
            confirm.error = confirm.context.getString(R.string.protect_passwords_mismatch)
            return
        }
        if (!ProtectPassword.accepts(text)) return
        val rememberIt = rememberAvailable && remember.isChecked
        dialog.dismiss()
        onProtect(text, rememberIt, share)
    }

    /** The meter and the one line under it (sw.css .meter / .ok), including the check icon. */
    private fun renderStrength(
        activity: AppCompatActivity,
        password: String,
        meters: List<View>,
        label: TextView
    ) {
        val strength = ProtectPassword.of(password)
        val onColor = ContextCompat.getColor(
            activity,
            if (strength.isWarning) R.color.protect_warn else R.color.protect_ok
        )
        val offColor = ContextCompat.getColor(activity, R.color.protect_meter_off)
        meters.forEachIndexed { index, segment ->
            segment.backgroundTintList = ColorStateList.valueOf(
                if (index < strength.filledSegments) onColor else offColor
            )
        }

        when (strength) {
            PasswordStrength.EMPTY -> {
                label.visibility = View.GONE
                label.text = ""
            }
            PasswordStrength.TOO_SHORT -> {
                label.visibility = View.VISIBLE
                label.setText(R.string.protect_meter_too_short)
                label.setTextColor(onColor)
                label.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            }
            PasswordStrength.WEAK -> {
                label.visibility = View.VISIBLE
                label.setText(R.string.protect_meter_weak)
                label.setTextColor(onColor)
                label.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            }
            PasswordStrength.GOOD -> {
                label.visibility = View.VISIBLE
                label.text = activity.getString(
                    R.string.protect_meter_good,
                    ProtectPassword.length(password)
                )
                label.setTextColor(onColor)
                label.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_check, 0, 0, 0)
                label.compoundDrawableTintList = ColorStateList.valueOf(onColor)
            }
            PasswordStrength.STRONG -> {
                label.visibility = View.VISIBLE
                label.text = activity.getString(
                    R.string.protect_meter_strong,
                    ProtectPassword.length(password)
                )
                label.setTextColor(onColor)
                label.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_check, 0, 0, 0)
                label.compoundDrawableTintList = ColorStateList.valueOf(onColor)
            }
        }
    }

    private fun applyVisibility(field: EditText, visible: Boolean) {
        field.inputType = if (visible) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        field.setSelection(field.text.length)
    }
}

/** Minimal TextWatcher so the sheet does not need an abstract class per listener. */
internal class SimpleTextWatcher(private val onChanged: () -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = onChanged()
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}
