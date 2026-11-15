package com.scanwhirl.app.tools

import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.SwDialog
import com.scanwhirl.app.ui.showSw
import com.scanwhirl.app.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * test26 T2.5 — Hidden Vault (PIN gate, add scans/PDFs), moved verbatim out
 * of `MainActivity`. `setVaultMode` stays on the Activity (it drives the
 * Home UI and is visible to tests).
 *
 * @param pickPdf seam for the Pro device-PDF picker (launcher stays in the
 *   Activity via `pendingUriAction` + `pickProPdf`).
 * @param setVaultMode enters/leaves vault mode on the Home screen.
 * @param onNewVaultUnavailable tells the user new vault setup / additions are
 *   unavailable (interim, until encrypted Private ships). Not a Pro upsell.
 */
class VaultFlow(
    private val activity: AppCompatActivity,
    private val repo: ScanRepository,
    private val host: HomeHost,
    private val pickPdf: ((Uri) -> Unit) -> Unit,
    private val setVaultMode: (Boolean) -> Unit,
    private val onNewVaultUnavailable: () -> Unit
) {

    fun openVaultWithPin() {
        // WP-22-6 (interim): opening an existing vault is free; new vault
        // setup is disabled until encrypted Private ships (WP-14).
        if (!Prefs.hasVaultPin(activity)) {
            onNewVaultUnavailable()
            return
        }
        val sheet = com.scanwhirl.app.ui.kit.SwSheet.builder(activity)
            .setTitle(R.string.vault_enter_pin_title)
            .setMessage(R.string.vault_enter_pin_msg)
            .setInput(
                hint = activity.getString(R.string.vault_pin_hint),
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            )
            .setSecondary(android.R.string.cancel)
            .build()
        sheet.inputView?.filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        sheet.setPrimary(R.string.unlock_pdf_open) {
            val pin = sheet.inputView?.text?.toString()?.trim().orEmpty()
            if (Prefs.verifyVaultPin(activity, pin)) {
                setVaultMode(true)
            } else {
                Toast.makeText(activity, R.string.vault_pin_wrong, Toast.LENGTH_LONG).show()
            }
        }
        sheet.show()
    }

    fun promptSetVaultPin(onSuccess: () -> Unit) {
        val sheet = com.scanwhirl.app.ui.kit.SwSheet.builder(activity)
            .setTitle(R.string.vault_set_pin_title)
            .setMessage(R.string.vault_set_pin_msg)
            .setInput(
                hint = activity.getString(R.string.vault_pin_hint),
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            )
            .setSecondary(android.R.string.cancel)
            .build()
        sheet.inputView?.filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        sheet.setPrimary(R.string.save) {
            val pin = sheet.inputView?.text?.toString()?.trim().orEmpty()
            if (pin.length != 4 || !pin.all { it.isDigit() }) {
                Toast.makeText(activity, R.string.vault_pin_invalid, Toast.LENGTH_LONG).show()
            } else {
                Prefs.setVaultPin(activity, pin)
                Toast.makeText(activity, R.string.vault_pin_saved, Toast.LENGTH_SHORT).show()
                onSuccess()
            }
        }
        sheet.show()
    }

    fun addToVault() {
        // WP-22-6 (interim): new vault additions disabled until WP-14 encrypted
        // Private ships. Existing vault items remain readable and removable.
        onNewVaultUnavailable()
    }

    private fun moveDocToVault(doc: ScanDocument) {
        val progress = host.showProgress()
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { repo.setVaulted(doc.id, true) }
                progress.dismiss()
                Toast.makeText(activity, R.string.vault_moved_in, Toast.LENGTH_SHORT).show()
                host.refreshList()
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun importPdfToVault(uri: Uri) {
        var job: kotlinx.coroutines.Job? = null
        var sheet: com.scanwhirl.app.ui.kit.SwSheet? = null
        job = activity.lifecycleScope.launch {
            try {
                val name = FlowUtil.devicePdfName(repo, uri)
                val doc = repo.importPdfIntoVaultPlanned(
                    uri = uri,
                    name = name,
                    onPlan = { plan ->
                        val decision = com.scanwhirl.app.ui.tools.DevicePdfGate.decide(activity, plan)
                        if (decision != ScanRepository.ImportDecision.Cancel) {
                            sheet = withContext(Dispatchers.Main) {
                                com.scanwhirl.app.ui.tools.DevicePdfGate
                                    .progress(activity) { job?.cancel() }
                                    .also { it.show() }
                            }
                        }
                        decision
                    },
                    onPage = { done, total ->
                        activity.runOnUiThread {
                            sheet?.setMessage(
                                com.scanwhirl.app.ui.tools.DevicePdfGate
                                    .progressText(activity, done, total)
                            )
                        }
                    }
                )
                sheet?.dismiss()
                if (doc == null) return@launch
                Toast.makeText(activity, R.string.vault_imported, Toast.LENGTH_SHORT).show()
                host.refreshList()
            } catch (e: kotlinx.coroutines.CancellationException) {
                sheet?.dismiss()
                throw e
            } catch (e: Exception) {
                sheet?.dismiss()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
