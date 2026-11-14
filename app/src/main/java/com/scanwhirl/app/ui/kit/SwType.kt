package com.scanwhirl.app.ui.kit

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.scanwhirl.app.R
import java.util.WeakHashMap

/**
 * v2 UI: 01_home_A — Manrope (SIL OFL, `docs/licenses/manrope-OFL.txt`).
 *
 * The kit asks for a weight, never for a platform family, so dropping the
 * bundle in one place changes every screen. If the font ever fails to load the
 * platform sans family keeps the text readable.
 */
object SwType {

    const val REGULAR = 400
    const val MEDIUM = 500
    const val SEMIBOLD = 600
    const val BOLD = 700
    const val EXTRABOLD = 800

    private val cache = WeakHashMap<Context, MutableMap<Int, Typeface>>()

    private fun fontRes(weight: Int): Int = when {
        weight >= EXTRABOLD -> R.font.manrope_extrabold
        weight >= BOLD -> R.font.manrope_bold
        weight >= SEMIBOLD -> R.font.manrope_semibold
        weight >= MEDIUM -> R.font.manrope_medium
        else -> R.font.manrope_regular
    }

    fun of(context: Context, weight: Int): Typeface {
        val perContext = cache.getOrPut(context) { mutableMapOf() }
        perContext[weight]?.let { return it }
        val loaded = runCatching {
            ResourcesCompat.getFont(context, fontRes(weight))
        }.getOrNull() ?: Typeface.create("sans-serif", if (weight >= BOLD) Typeface.BOLD else Typeface.NORMAL)
        perContext[weight] = loaded
        return loaded
    }

    fun regular(context: Context): Typeface = of(context, REGULAR)
    fun medium(context: Context): Typeface = of(context, MEDIUM)
    fun semibold(context: Context): Typeface = of(context, SEMIBOLD)
    fun bold(context: Context): Typeface = of(context, BOLD)
    fun extraBold(context: Context): Typeface = of(context, EXTRABOLD)
}
