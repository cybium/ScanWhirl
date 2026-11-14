package com.scanwhirl.app.ui.protect

import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.scanwhirl.app.R
import kotlin.math.ceil

/**
 * v2 Unlock sheet — mockup `09_unlock_sheet`, design `70` §4.2.
 *
 * A password field with the tries-left error, then five wrong tries start a 30-second pause that
 * doubles ([TryCooldown]). Open calls [verify]; a right password closes the sheet and hands the
 * password to [onUnlocked]. "Remove protection…" asks the same password, then shows a second
 * confirmation before [onRemoveProtection] runs — never one tap.
 *
 * "Use password saved in Vault" is present but hidden until L2's Vault can answer
 * ([vaultAvailable] / [onUseVault]); a button that cannot work is not drawn.
 */
object UnlockSheet {

    fun show(
        activity: AppCompatActivity,
        documentName: String,
        verify: (String) -> Boolean,
        cooldown: TryCooldown = TryCooldown(),
        vaultAvailable: Boolean = false,
        onUnlocked: (password: String) -> Unit,
        onRemoveProtection: ((password: String) -> Unit)? = null,
        onUseVault: (() -> Unit)? = null
    ): BottomSheetDialog {
        val dialog = BottomSheetDialog(activity)
        val root = LayoutInflater.from(activity).inflate(R.layout.sheet_unlock, null)
        dialog.setContentView(root)
        dialog.window?.setDimAmount(0.85f)

        root.findViewById<TextView>(R.id.unlockTitle).text =
            activity.getString(R.string.unlock_sheet_title, documentName)
        val field = root.findViewById<EditText>(R.id.unlockPassword)
        val showButton = root.findViewById<ImageButton>(R.id.unlockPasswordShow)
        val error = root.findViewById<TextView>(R.id.unlockError)
        val open = root.findViewById<MaterialButton>(R.id.unlockOpen)
        val vault = root.findViewById<MaterialButton>(R.id.unlockVault)
        val cancel = root.findViewById<MaterialButton>(R.id.unlockCancel)
        val remove = root.findViewById<MaterialButton>(R.id.unlockRemove)

        val fieldIdle = R.drawable.bg_protect_field
        val fieldError = R.drawable.bg_protect_field_error

        val handler = Handler(Looper.getMainLooper())
        dialog.setOnDismissListener { handler.removeCallbacksAndMessages(null) }

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
                open.isEnabled = true
                remove.isEnabled = onRemoveProtection != null
                field.setBackgroundResource(fieldIdle)
                error.visibility = View.GONE
                return
            }
            open.isEnabled = false
            remove.isEnabled = false
            val seconds = ceil(left / 1000.0).toInt()
            showError(activity.getString(R.string.unlock_wrong_paused, seconds))
            handler.postDelayed({ tickPause() }, 1000L)
        }

        fun openWith(text: String) {
            cooldown.reset()
            dialog.dismiss()
            onUnlocked(text)
        }

        fun attempt(removeProtection: Boolean) {
            if (!cooldown.canTry()) {
                tickPause()
                return
            }
            val text = field.text.toString()
            if (text.isEmpty()) {
                showError(
                    activity.getString(R.string.unlock_wrong_tries, cooldown.triesLeft, 30)
                )
                return
            }
            if (verify(text)) {
                if (!removeProtection) {
                    openWith(text)
                } else {
                    confirmRemove(activity) {
                        dialog.dismiss()
                        onRemoveProtection?.invoke(text)
                    }
                }
                return
            }
            val pause = cooldown.registerFailure()
            if (pause > 0L) {
                showError(activity.getString(R.string.unlock_wrong_paused, pause / 1000))
                tickPause()
            } else {
                showError(
                    activity.getString(R.string.unlock_wrong_tries, cooldown.triesLeft, 30)
                )
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
        parent.addView(cancel, android.widget.LinearLayout.LayoutParams(-1, dp(44)))
        dialog.show()
    }
}
