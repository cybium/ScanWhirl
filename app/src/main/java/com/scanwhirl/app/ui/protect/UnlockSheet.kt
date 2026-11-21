package com.scanwhirl.app.ui.protect

import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/**
 * v2 Unlock sheet — mockup `09_unlock_sheet`, design `70` §4.2.
 *
 * A password field with the tries-left error, then five wrong tries start a 30-second pause that
 * doubles ([TryCooldown]). [verify] is a suspending check: the sheet runs it on
 * [Dispatchers.Default] (the lock's PBKDF2 must never block the main thread), shows a progress
 * state, accepts only one check at a time and drops a result whose sheet was dismissed meanwhile.
 * A right password closes the sheet and hands the password to [onUnlocked]. "Remove protection…"
 * asks the same password, then shows a second confirmation before [onRemoveProtection] runs —
 * never one tap.
 *
 * "Use password saved in Vault" is present but hidden until L2's Vault can answer
 * ([vaultAvailable] / [onUseVault]); a button that cannot work is not drawn.
 */
object UnlockSheet {

    fun show(
        activity: AppCompatActivity,
        documentName: String,
        verify: suspend (String) -> Boolean,
        cooldown: TryCooldown = TryCooldown(),
        vaultAvailable: Boolean = false,
        onUnlocked: (password: String) -> Unit,
        onRemoveProtection: ((password: String) -> Unit)? = null,
        onUseVault: (() -> Unit)? = null
    ): BottomSheetDialog = showChecked(
        activity, documentName, { candidate -> if (verify(candidate)) candidate else null },
        cooldown, vaultAvailable, onUnlocked, onRemoveProtection, onUseVault
    )

    /** Library callers get the proof, not the typed password, for the follow-up action. */
    internal fun showWithGrant(
        activity: AppCompatActivity,
        documentName: String,
        verify: suspend (String) -> ScanRepository.PdfPasswordGrant?,
        onUnlocked: (ScanRepository.PdfPasswordGrant) -> Unit,
        onRemoveProtection: ((ScanRepository.PdfPasswordGrant) -> Unit)? = null
    ): BottomSheetDialog = showChecked(
        activity, documentName, verify, TryCooldown(), false, onUnlocked, onRemoveProtection, null
    )

    private fun <T : Any> showChecked(
        activity: AppCompatActivity,
        documentName: String,
        verify: suspend (String) -> T?,
        cooldown: TryCooldown,
        vaultAvailable: Boolean,
        onUnlocked: (T) -> Unit,
        onRemoveProtection: ((T) -> Unit)?,
        onUseVault: (() -> Unit)?
    ): BottomSheetDialog {
        val dialog = BottomSheetDialog(activity)
        val root = LayoutInflater.from(activity).inflate(R.layout.sheet_unlock, null)
        dialog.setContentView(root)
        dialog.window?.setDimAmount(0.85f)
        com.scanwhirl.app.util.SecureView.apply(dialog.window)

        root.findViewById<TextView>(R.id.unlockTitle).text =
            activity.getString(R.string.unlock_sheet_title, documentName)
        dialog.setOnShowListener {
            androidx.core.view.ViewCompat.setAccessibilityPaneTitle(
                dialog.window?.decorView ?: root,
                activity.getString(R.string.unlock_sheet_title, documentName)
            )
        }
        val field = root.findViewById<EditText>(R.id.unlockPassword)
        // The XML field already says no to autofill; repeat it at runtime with the saved-state
        // opt-out so a restored window can never carry the typed password.
        field.isSaveEnabled = false
        field.freezesText = false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            field.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            field.setAutofillHints()
        }
        val showButton = root.findViewById<ImageButton>(R.id.unlockPasswordShow)
        val error = root.findViewById<TextView>(R.id.unlockError)
        val open = root.findViewById<MaterialButton>(R.id.unlockOpen)
        val vault = root.findViewById<MaterialButton>(R.id.unlockVault)
        val cancel = root.findViewById<MaterialButton>(R.id.unlockCancel)
        val remove = root.findViewById<MaterialButton>(R.id.unlockRemove)

        val fieldIdle = R.drawable.bg_protect_field
        val fieldError = R.drawable.bg_protect_field_error

        // Progress state for the off-main check: a thin indeterminate line above Open.
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            contentDescription = activity.getString(R.string.unlock_checking)
        }
        val column = (root as? android.view.ViewGroup)?.getChildAt(0) as? android.widget.LinearLayout
        column?.addView(
            progress,
            column.indexOfChild(open),
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (3 * activity.resources.displayMetrics.density).toInt()
            )
        )

        var checking = false
        var check: Job? = null

        val handler = Handler(Looper.getMainLooper())
        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            // Dismissed mid-check: the derivation is cancelled and its result is never applied.
            check?.cancel()
            check = null
            checking = false
            // Closed for any reason: the typed password leaves the view.
            field.setText("")
        }

        fun setChecking(value: Boolean) {
            checking = value
            progress.visibility = if (value) View.VISIBLE else View.GONE
            val allowed = !value && cooldown.canTry()
            open.isEnabled = allowed
            remove.isEnabled = allowed && onRemoveProtection != null
            vault.isEnabled = allowed && vaultAvailable && onUseVault != null
        }

        var revealed = false
        showButton.setOnClickListener {
            revealed = !revealed
            field.inputType = if (revealed) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            field.setSelection(field.text.length)
            showButton.setImageResource(
                if (revealed) R.drawable.ic_protect_visibility_off else R.drawable.ic_visibility
            )
            showButton.contentDescription = activity.getString(
                if (revealed) R.string.protect_hide_password else R.string.protect_show_password
            )
        }

        fun clearError() {
            error.visibility = View.GONE
            field.setBackgroundResource(fieldIdle)
        }
        field.addTextChangedListener(SimpleTextWatcher { clearError() })

        fun showError(message: String) {
            error.text = message
            error.visibility = View.VISIBLE
            field.setBackgroundResource(fieldError)
        }

        fun tickPause() {
            val left = cooldown.remainingPauseMs()
            if (left <= 0L && cooldown.canTry()) {
                if (!checking) {
                    open.isEnabled = true
                    remove.isEnabled = onRemoveProtection != null
                    // The pause is over: every action returns, not just Open and Remove.
                    vault.isEnabled = vaultAvailable && onUseVault != null
                }
                field.setBackgroundResource(fieldIdle)
                error.visibility = View.GONE
                return
            }
            open.isEnabled = false
            remove.isEnabled = false
            vault.isEnabled = false
            val seconds = ceil(left / 1000.0).toInt()
            showError(activity.getString(R.string.unlock_wrong_paused, seconds))
            handler.postDelayed({ tickPause() }, 1000L)
        }

        fun openWith(checked: T) {
            cooldown.reset()
            dialog.dismiss()
            onUnlocked(checked)
        }

        fun attempt(removeProtection: Boolean) {
            // One check at a time: a second tap while the KDF runs is ignored, not queued.
            if (checking) return
            if (!cooldown.canTry()) {
                tickPause()
                return
            }
            val text = field.text.toString()
            // An empty password is a real password: an owner-only document (empty user password)
            // opens with it. [verify] decides — it must never be pre-empted here, or such a file
            // becomes impossible to open.
            setChecking(true)
            check = activity.lifecycleScope.launch {
                val checked = try {
                    withContext(Dispatchers.Default) { verify(text) }
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    null
                }
                // Dismissed while the derivation ran: nothing is decided and nothing is applied.
                if (!dialog.isShowing) return@launch
                setChecking(false)
                if (checked != null) {
                    if (!removeProtection) {
                        openWith(checked)
                    } else {
                        confirmRemove(activity) {
                            if (dialog.isShowing) {
                                dialog.dismiss()
                                onRemoveProtection?.invoke(checked)
                            }
                        }
                    }
                    return@launch
                }
                val pause = cooldown.registerFailure()
                if (pause > 0L) {
                    showError(activity.getString(R.string.unlock_wrong_paused, pause / 1000))
                    tickPause()
                } else {
                    showError(
                        activity.getString(
                            R.string.unlock_wrong_tries, cooldown.triesLeft, cooldown.nextPauseSeconds()
                        )
                    )
                }
            }
        }

        open.setOnClickListener { attempt(removeProtection = false) }
        remove.setOnClickListener { attempt(removeProtection = true) }
        remove.visibility = if (onRemoveProtection != null) View.VISIBLE else View.GONE
        cancel.setOnClickListener { dialog.dismiss() }
        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                attempt(removeProtection = false)
                true
            } else {
                false
            }
        }

        vault.visibility = if (vaultAvailable && onUseVault != null) View.VISIBLE else View.GONE
        vault.setOnClickListener { onUseVault?.invoke() }

        // A cooldown can still be running from an earlier sheet (the pacing survives dismissal).
        if (!cooldown.canTry()) tickPause()

        dialog.show()
        field.requestFocus()
        return dialog
    }

    /** The second confirmation before a lock is removed ("Remove protection…" is never one tap). */
    private fun confirmRemove(
        activity: AppCompatActivity,
        onConfirmed: () -> Unit
    ) {
        val dialog = BottomSheetDialog(activity)
        val root = LayoutInflater.from(activity).inflate(R.layout.sheet_protect, null)
        dialog.setContentView(root)
        dialog.window?.setDimAmount(0.85f)
        com.scanwhirl.app.util.SecureView.apply(dialog.window)
        // Keep only the shell: title + message + two buttons.
        val parent = (root as? android.view.ViewGroup)?.getChildAt(0) as? android.widget.LinearLayout
            ?: return
        parent.removeAllViews()
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val title = TextView(activity).apply {
            text = activity.getString(R.string.unlock_remove_title)
            setTextColor(activity.getColor(R.color.rv_ink))
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val message = TextView(activity).apply {
            text = activity.getString(R.string.unlock_remove_msg)
            setTextColor(activity.getColor(R.color.rv_ink_2))
            textSize = 13f
            setPadding(0, dp(8), 0, dp(18))
        }
        val confirm = MaterialButton(activity).apply {
            text = activity.getString(R.string.unlock_remove_confirm)
            setTextColor(activity.getColor(R.color.rv_danger))
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                activity.getColor(R.color.laser_danger_surface)
            )
            cornerRadius = dp(16)
            minHeight = dp(54)
            setOnClickListener {
                dialog.dismiss()
                onConfirmed()
            }
        }
        val cancel = MaterialButton(activity).apply {
            text = activity.getString(R.string.unlock_cancel)
            setTextColor(activity.getColor(R.color.rv_ink_2))
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                activity.getColor(android.R.color.transparent)
            )
            setOnClickListener { dialog.dismiss() }
        }
        parent.addView(title)
        parent.addView(message)
        parent.addView(confirm, android.widget.LinearLayout.LayoutParams(-1, dp(54)))
        cancel.minHeight = dp(48)
        parent.addView(cancel, android.widget.LinearLayout.LayoutParams(-1, -2))
        dialog.show()
    }
}
