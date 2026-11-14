package com.scanwhirl.app.ui.protect

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.scanwhirl.app.R
import com.scanwhirl.app.store.vault.VaultLockedException
import com.scanwhirl.app.store.vault.VaultPasswords
import com.scanwhirl.app.ui.vault.VaultActivity

/**
 * L3's use of L2's remembered PDF passwords (design §4.2).
 *
 * `VaultPasswords` only works while the Vault is unlocked. When it is locked, [remember] and
 * [readForUnlock] open [VaultActivity] (the real unlock screen) and retry once the owner comes
 * back. Protection itself never waits for the Vault: callers protect first and remember after.
 *
 * The password is never written here: [VaultPasswords] encrypts it with the Vault key. The
 * launcher keeps it only in this call's closure, for the moment the unlock screen is open.
 */
internal object VaultPasswordsFlow {

    /** Opens the Vault screen and calls [onReturn] when this activity is in front again. */
    private val defaultLauncher: (AppCompatActivity, () -> Unit) -> Unit = { activity, onReturn ->
        openVault(activity, onReturn)
    }

    /** Test seam: L2's Robolectric tests drive the store directly, so L3's tests do the same. */
    @Volatile
    internal var unlockLauncher: (AppCompatActivity, () -> Unit) -> Unit = defaultLauncher

    internal fun useDefaultLauncher() {
        unlockLauncher = defaultLauncher
    }

    /**
     * True when a password is saved for [documentId] **and** the Vault can be read right now.
     * A locked or damaged Vault answers false, so the button is simply not drawn.
     */
    fun has(context: Context, documentId: String): Boolean =
        runCatching { VaultPasswords.of(context).has(documentId) }.getOrDefault(false)

    /**
     * Saves (or replaces) the password for [documentId]; when the Vault is locked it opens the
     * Unlock screen and retries on return. [onResult] is false when the user cancelled the
     * unlock or the entry could not be written — the PDF is already protected either way.
     */
    fun remember(
        activity: AppCompatActivity,
        documentId: String,
        password: String,
        onResult: (Boolean) -> Unit = {}
    ) {
        val store = VaultPasswords.of(activity)
        try {
            store.save(documentId, password)
            onResult(true)
            return
        } catch (e: VaultLockedException) {
            Toast.makeText(activity, R.string.vault_unlock_to_save, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            onResult(false)
            return
        }
        unlockLauncher(activity) {
            val saved = runCatching { store.save(documentId, password) }.isSuccess
            onResult(saved)
        }
    }

    /** Forgets the saved password (protection removed or "Remember in Vault" turned off). */
    fun forget(context: Context, documentId: String) {
        runCatching { VaultPasswords.of(context).remove(documentId) }
    }

    /**
     * Reads the saved password for the Unlock sheet. A locked Vault opens the Unlock screen first;
     * a missing entry, a locked Vault the user backed out of, or a damaged blob gives null so the
     * sheet can fall back to typing.
     */
    fun readForUnlock(
        activity: AppCompatActivity,
        documentId: String,
        onPassword: (String?) -> Unit
    ) {
        try {
            onPassword(VaultPasswords.of(activity).get(documentId))
            return
        } catch (e: VaultLockedException) {
            // Ask for the Vault passcode below.
        } catch (e: Exception) {
            onPassword(null)
            return
        }
        unlockLauncher(activity) {
            onPassword(runCatching { VaultPasswords.of(activity).get(documentId) }.getOrNull())
        }
    }

    /**
     * Starts the Vault screen and reports the next resume of the calling activity. The Vault's
     * own unlock sheet is the entry point; no result code is involved (the Vault screen stays
     * open after unlocking, so Back is the return).
     */
    private fun openVault(activity: AppCompatActivity, onReturn: () -> Unit) {
        val application = activity.application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(resumed: Activity) {
                if (resumed === activity) {
                    application.unregisterActivityLifecycleCallbacks(this)
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        activity.window?.decorView?.post(onReturn)
                    }
                }
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(destroyed: Activity) {
                if (destroyed === activity) application.unregisterActivityLifecycleCallbacks(this)
            }
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        val opened = runCatching { activity.startActivity(VaultActivity.intent(activity)) }
        if (opened.isFailure) {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            onReturn()
        }
    }
}
