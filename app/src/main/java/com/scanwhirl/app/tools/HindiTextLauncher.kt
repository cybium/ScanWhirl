package com.scanwhirl.app.tools

import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.store.OcrReindex
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.OcrEditorActivity
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.OcrLanguage
import com.scanwhirl.app.util.OcrLanguageSettings
import kotlinx.coroutines.launch

/**
 * v2 UI: 03_tools_hub — the "Hindi text" tool (Text group). Pick a document, read it with the
 * Devanagari recognizer, then open the Text screen on the result.
 *
 * This is an explicit one-shot choice, so it also switches the OCR language setting to Hindi:
 * the next page the user reads stays in the same script. Unreadable pages are explained with
 * the same honest wording as the editor (model unavailable vs nothing found).
 */
object HindiTextLauncher {

    fun open(activity: AppCompatActivity, repo: ScanRepository) {
        val docs = repo.listDocuments().filter { !it.isLocked && !it.isVaulted }
        FlowUtil.pickScan(activity, R.string.ocr_hindi_pick, docs) { doc ->
            run(activity, repo, doc.id)
        }
    }

    private fun run(activity: AppCompatActivity, repo: ScanRepository, docId: String) {
        OcrLanguageSettings.set(activity, OcrLanguage.HINDI)
        Toast.makeText(activity, R.string.ocr_hindi_reading, Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            try {
                val stored = OcrReindex(repo).reRecognise(docId, OcrLanguage.HINDI)
                if (stored <= 0) {
                    Toast.makeText(activity, R.string.ocr_hindi_nothing, Toast.LENGTH_LONG).show()
                    return@launch
                }
                activity.startActivity(
                    Intent(activity, OcrEditorActivity::class.java).apply {
                        putExtra(OcrEditorActivity.EXTRA_DOC_ID, docId)
                    }
                )
            } catch (e: Exception) {
                Toast.makeText(
                    activity,
                    if (OcrEngine.isUnavailable(e)) {
                        activity.getString(R.string.ocr_unavailable_toast)
                    } else {
                        activity.getString(R.string.page_op_failed, e.message)
                    },
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
