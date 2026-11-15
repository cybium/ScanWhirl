package com.scanwhirl.app.ui.kit

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.scanwhirl.app.R
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate

/**
 * v2 UI: Pro accent theme — Cyan (default) or Gold.
 *
 * Every kit colour goes through [SwStyle.color], so mapping the cyan tokens here
 * recolours the whole kit at once. Gold is Pro-only; the stored choice stays
 * harmless for a Free build (the gate falls back to Cyan).
 */
object SwTheme {

    const val CYAN = "cyan"
    const val GOLD = "gold"

    fun isGold(context: Context): Boolean =
        ProGate.isPro(context) && Prefs.accent(context) == Prefs.ACCENT_GOLD

    /** The active accent for a token, or the token itself when it is not cyan. */
    fun accent(context: Context, res: Int): Int = when (res) {
        R.color.cyan_primary -> gold(context)
        R.color.cyan_dim -> ColorUtils.setAlphaComponent(gold(context), 0xB8)
        R.color.cyan_border_hair, R.color.sw_cyan_hair -> color(context, R.color.sw_gold_hair)
        R.color.cyan_border_idle -> ColorUtils.setAlphaComponent(gold(context), 0x38)
        R.color.cyan_border_active, R.color.sw_cyan_hair_strong ->
            color(context, R.color.protect_gold_hair)
        R.color.cyan_glow_soft, R.color.cyan_pressed_fill, R.color.sw_cyan_soft ->
            color(context, R.color.sw_gold_soft)
        else -> color(context, res)
    }

    fun gold(context: Context): Int = color(context, R.color.gold_pro_primary)

    private fun color(context: Context, res: Int): Int = ContextCompat.getColor(context, res)

    // ---- launcher icon alias (cyan default, gold when chosen) ----------------

    private const val CYAN_ALIAS = "com.scanwhirl.app.ui.shell.ShellActivityCyan"
    private const val GOLD_ALIAS = "com.scanwhirl.app.ui.shell.ShellActivityGold"

    /**
     * Shows exactly one launcher icon. The new alias is ENABLED FIRST, then the old
     * one disabled: if the process dies between the two calls the phone briefly shows
     * two icons (fixed on the next call) instead of none (the app would be unreachable
     * from the launcher). [DONT_KILL_APP] keeps this process alive.
     */
    fun applyLauncherIcon(context: Context, gold: Boolean) {
        val pm = context.packageManager
        val cyan = ComponentName(context.packageName, CYAN_ALIAS)
        val goldName = ComponentName(context.packageName, GOLD_ALIAS)
        val enabled = ComponentName(context.packageName, if (gold) GOLD_ALIAS else CYAN_ALIAS)
        val disabled = ComponentName(context.packageName, if (gold) CYAN_ALIAS else GOLD_ALIAS)
        runCatching {
            pm.setComponentEnabledSetting(
                enabled, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                disabled, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    /** The component currently enabled (test hook), or null when neither is set. */
    fun enabledLauncherAlias(context: Context): String? {
        val pm = context.packageManager
        val cyan = pm.getComponentEnabledSetting(
            ComponentName(context.packageName, CYAN_ALIAS)
        )
        val gold = pm.getComponentEnabledSetting(
            ComponentName(context.packageName, GOLD_ALIAS)
        )
        return when {
            gold == PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> GOLD_ALIAS
            cyan == PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> CYAN_ALIAS
            else -> null
        }
    }
}
