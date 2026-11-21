package com.scanwhirl.app.ui.kit

import android.os.Build
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bind one expensive credential attempt at a time. Only a visible sheet receives its result. */
internal fun <T : Any> SwSheet.bindCredentialSubmit(
    activity: AppCompatActivity,
    field: EditText,
    @StringRes submitLabel: Int,
    @StringRes progressLabel: Int = R.string.progress_title,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    read: () -> String?,
    work: suspend (String) -> T?,
    onResult: (T?) -> Unit
) {
    secure()
    field.isSaveEnabled = false
    field.freezesText = false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        field.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        field.setAutofillHints()
    }

    val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
        isVisible = false
        contentDescription = activity.getString(progressLabel)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val root = primaryButton.parent as LinearLayout
    root.addView(
        progress, root.indexOfChild(primaryButton),
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, SwStyle.dp(activity, 4f))
    )

    var attempt: Job? = null
    fun setBusy(busy: Boolean) {
        progress.isVisible = busy
        primaryButton.isEnabled = !busy
        primaryButton.alpha = if (busy) 0.55f else 1f
        field.isEnabled = !busy
    }

    val observer = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            attempt?.cancel()
            dismiss()
            // KT1 P2: a lifecycle dismissal runs internal cleanup only, so the onDismiss
            // below may be skipped. The secret must never outlive its owner either way.
            field.text?.clear()
        }
    }
    activity.lifecycle.addObserver(observer)
    // Detach runs immediately on a window dismissal, ahead of Android's posted onDismiss callback.
    view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) { attempt?.cancel() }
    })
    setOnDismissListener {
        attempt?.cancel()
        activity.lifecycle.removeObserver(observer)
        field.text?.clear()
    }
    setPrimaryStayOpen(submitLabel) {
        if (attempt?.isActive == true || !isShowing || activity.isFinishing) return@setPrimaryStayOpen
        val secret = read() ?: return@setPrimaryStayOpen
        setBusy(true)
        attempt = activity.lifecycleScope.launch {
            val result = try {
                withContext(dispatcher) { work(secret) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (!isShowing || activity.isFinishing || activity.isDestroyed) return@launch
            setBusy(false)
            onResult(result)
        }
    }
}
