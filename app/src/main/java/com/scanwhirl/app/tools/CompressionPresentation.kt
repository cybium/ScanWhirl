package com.scanwhirl.app.tools

import androidx.annotation.StringRes
import com.scanwhirl.app.R

/**
 * L3-03 — the one compression vocabulary for the two setup surfaces.
 *
 * CompressFlow's sheet and ToolSetupActivity's page used to own two separate
 * level lists. Both now read this ordered table, so "Best quality / Balanced /
 * Smaller / Smallest" can never drift between them. The already-judged shared
 * labels are reused (no duplicate EN/HI copy), and the two new lane labels name
 * the size aim and the external-file route in one place.
 *
 * Deliberately NOT here: quality, pixel caps and DPI. Those are engine
 * parameters; centralising them here would silently change output quality.
 */
object CompressionPresentation {

    /** Canonical, best → smallest order. Resource ids stay the judged shared strings. */
    @StringRes
    val levelLabels: List<Int> = listOf(
        R.string.compress_level_low,
        R.string.compress_level_medium,
        R.string.compress_level_high,
        R.string.compress_level_ultra
    )

    /** The explicit size aim, shown above the free-form size field. */
    @StringRes
    val aimForSize: Int = R.string.ux130_l3_compress_aim_size

    /** The final row of the one combined library picker. */
    @StringRes
    val fromPhoneStorage: Int = R.string.ux130_l3_compress_from_phone_storage

    /** V2T T2: one free, decimal-KB quick-target table for Compress and Resize. */
    val quickTargetKb: List<Int> = listOf(20, 50, 100, 200)

    @StringRes
    fun quickTargetLabel(kb: Int): Int = when (kb) {
        20 -> R.string.v2t_size_20
        50 -> R.string.v2t_size_50
        100 -> R.string.v2t_size_100
        else -> R.string.v2t_size_200
    }

    /**
     * J2 — the four "Aim for a size" chips on the Tools-hub Compress setup. These are
     * decimal PDF bytes (KB = 1000), matching UX_EVIDENCE J2 and the shared `SizeUnits`
     * parser. Deliberately a SEPARATE table from [quickTargetKb]: that one is also read
     * by Resize and by the viewer-dock sheet, whose pinned contracts stay unchanged.
     * Index order is the on-screen order: 200 KB, 500 KB, 1 MB, 2 MB.
     */
    val j2TargetBytes: List<Long> = listOf(200_000L, 500_000L, 1_000_000L, 2_000_000L)

    @StringRes
    fun j2TargetLabel(index: Int): Int = when (index) {
        0 -> R.string.ux130_l3_compress_target_200kb
        1 -> R.string.ux130_l3_compress_target_500kb
        2 -> R.string.ux130_l3_compress_target_1mb
        else -> R.string.ux130_l3_compress_target_2mb
    }
}
