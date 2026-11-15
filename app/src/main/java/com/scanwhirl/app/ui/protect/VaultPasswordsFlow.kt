package com.scanwhirl.app.ui.protect

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.scanwhirl.app.store.vault.VaultLockedException
import com.scanwhirl.app.store.vault.VaultPasswords
import com.scanwhirl.app.ui.vault.VaultActivity

/**
 * L3's use of L2's remembered PDF passwords (design §4.2).
 *
 * `VaultPasswords` only works while the Vault is unlocked. When it is locked, [readForUnlock]
 * opens [VaultActivity] with a reason line in its unlock sheet and retries once the owner comes
 * back. Protecting and saving never wait for the Vault: [saveIfUnlocked] is silent, and the
 * owner can unlock it later.
 *
 * The password is never written here: [VaultPasswords] encrypts it with the Vault key. The
 * launcher keeps it only in this call's closure, for the moment the unlock screen is open.
 */
internal object VaultPasswordsFlow {

    /** Opens the Vault screen with [reason] and calls [onReturn] when this activity is in front. */
    private val defaultLauncher: (AppCompatActivity, String?, () -> Unit) -> Unit =
        { activity, reason, onReturn ->
            openVault(activity, reason, onReturn)
        }

    /** Test seam: L2's Robolectric tests drive the store directly, so L3's tests do the same. */
    @Volatile
    internal var unlockLauncher: (AppCompatActivity, String?, () -> Unit) -> Unit = defaultLauncher

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
     * Saves the password only when the Vault is unlocked. The Protect/save action always
     * completes first; a locked Vault must never delay it or replace its screen with the Vault,
     * so this returns false ("not saved") instead of opening anything. The owner can unlock the
     * Vault later; a fresh Protect will then remember the password.
     */
    fun saveIfUnlocked(context: Context, documentId: String, password: String): Boolean =
        runCatching { VaultPasswords.of(context).save(documentId, password) }.isSuccess

    /** Forgets the saved password (protection removed or "Remember in Vault" turned off). */
    fun forget(context: Context, documentId: String) {
        runCatching { VaultPasswords.of(context).remove(documentId) }
    }

    /**
     * The owner chose "Unlock" in the "Save the password in Vault?" prompt: open L2's Vault
     * (with the reason line) when it is locked, then save on return.
     */
    fun rememberWithUnlock(
        activity: AppCompatActivity,
        documentId: String,
        password: String,
        onResult: (Boolean) -> Unit
    ) {
        val store = VaultPasswords.of(activity)
        if (runCatching { store.save(documentId, password) }.isSuccess) {
            onResult(true)
            return
        }
        unlockLauncher(
            activity,
            activity.getString(com.scanwhirl.app.R.string.vault_reason_save_password)
        ) {
            onResult(runCatching { store.save(documentId, password) }.isSuccess)
        }
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
        unlockLauncher(
            activity,
            activity.getString(com.scanwhirl.app.R.string.vault_reason_use_password)
        ) {
            onPassword(runCatching { VaultPasswords.of(activity).get(documentId) }.getOrNull())
        }
    }

    /**
     * Starts the Vault screen and reports the next resume of the calling activity. The Vault's
     * own unlock sheet is the entry point; no result code is involved (the Vault screen stays
     * open after unlocking, so Back is the return). [reason] is shown inside that sheet so the
     * owner knows why the Vault opened.
     */
    private fun openVault(activity: AppCompatActivity, reason: String?, onReturn: () -> Unit) {
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
        val opened = runCatching {
            activity.startActivity(VaultActivity.intent(activity, reason))
        }
        if (opened.isFailure) {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            onReturn()
        }
    }
}
