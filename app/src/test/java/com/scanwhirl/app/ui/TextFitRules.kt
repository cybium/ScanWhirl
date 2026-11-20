package com.scanwhirl.app.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Layout
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView

/**
 * F1-FIT: the reading rules every dock/tool/chip row must satisfy, shared by
 * the screen tests (rule R10 and the 2026-11-20 atlas findings).
 *
 * The atlas rendered on 7334c41d showed four kinds of unreadable text that the
 * old "the view exists" assertions could not see:
 *
 *  - `Rotat / e`, `Save to Downlo / ads`, `Mer / ge` — a label broken **inside
 *    a word** (a two-line button whose cell is narrower than the word);
 *  - `Shar` — a label clipped by its cell, no ellipsis, silently cut;
 *  - a chip rail whose next chip is a sliver (`(`, `Ot…`) with no hint that the
 *    rail scrolls;
 *  - a page counter pill of 40 % black over a white page: grey-on-white.
 *
 * Each rule below is deliberately conservative (no false green): an empty or
 * not-laid-out view is reported, never skipped.
 */
object TextFitRules {

    /** Characters a typographic line break may fall after. */
    private val BREAK_AFTER = charArrayOf(
        ' ', '\u00A0', '\u2009', '\u2007', '\n',
        '-', '\u2010', '\u2011', '\u00AD', '\u200B',
        // Devanagari danda / double danda end a sentence like a full stop.
        '\u0964', '\u0965'
    )

    private const val MIN_CONTRAST = 4.5

    /**
     * @param fixedBars dock / bottom-bar views that must never cover text.
     * @return a human-readable problem per offending view; empty = readable.
     */
    fun problems(root: View, label: String, fixedBars: List<View> = emptyList()): List<String> {
        val out = ArrayList<String>()

        fun scrollsUnderAppBar(v: View): Boolean =
            (v.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)
                ?.behavior is com.google.android.material.appbar.AppBarLayout.ScrollingViewBehavior

        fun inScrollChain(v: View): Boolean {
            if (scrollsUnderAppBar(v)) return true
            var p = v.parent as? View
            while (p != null && p !== root) {
                val n = p.javaClass.simpleName
                // A recycling/scrolling container crops its children on purpose
                // (below the fold), so its contents are not "clipped labels".
                if (n.contains("Scroll") || n.contains("ViewPager") || n.contains("Recycler") ||
                    scrollsUnderAppBar(p)
                ) {
                    return true
                }
                p = p.parent as? View
            }
            return false
        }

        fun isInside(v: View, ancestor: View): Boolean {
            var p = v.parent
            while (p != null) {
                if (p === ancestor) return true
                p = p.parent
            }
            return false
        }

        fun absTop(v: View): Int {
            var t = v.top
            var p = v.parent as? View
            while (p != null && p !== root) {
                t += p.top
                p = p.parent as? View
            }
            return t
        }

        fun absRight(v: View): Int {
            var r = v.right
            var p = v.parent as? View
            while (p != null && p !== root) {
                r += p.left
                p = p.parent as? View
            }
            return r
        }

        fun absBottom(v: View): Int {
            var b = v.bottom
            var p = v.parent as? View
            while (p != null && p !== root) {
                b += p.top
                p = p.parent as? View
            }
            return b
        }

        fun idOf(v: View): String = try {
            if (v.id == View.NO_ID) "no-id" else v.resources.getResourceEntryName(v.id)
        } catch (_: Exception) {
            "0x${Integer.toHexString(v.id)}"
        }

        fun describe(v: View): String {
            val tv = v as? TextView
            val text = tv?.text?.toString().orEmpty().ifEmpty { tv?.hint?.toString().orEmpty() }
            val shown = if (text.length > 40) text.take(40) + "…" else text
            return "[${v.javaClass.simpleName} id=${idOf(v)} \"$shown\"]"
        }

        fun walk(v: View) {
            if (v is HorizontalScrollView && v.isShown) {
                val content = v.getChildAt(0)
                if (content != null && content.measuredWidth > v.width + 0.5f &&
                    content.measuredWidth > 0
                ) {
                    if (!v.isHorizontalFadingEdgeEnabled) {
                        out += "$label: horizontal rail ${describe(v)} hides " +
                            "${content.measuredWidth - v.width}px of content with no fading edge — " +
                            "the next label looks cut (a sliver of a word)"
                    }
                }
            }
            if (v is TextView && v.isShown) checkText(v, label, out, ::describe, ::inScrollChain,
                ::absRight, ::absBottom, root, fixedBars, ::isInside, ::absTop)
            if (!inScrollChain(v)) {
                val parent = v.parent as? View
                if (parent != null && parent !== root && v.isShown) {
                    if (v.right > parent.width) {
                        out += "$label: ${describe(v)} right edge ${v.right}px exceeds parent " +
                            "width ${parent.width}px (${parent.javaClass.simpleName})"
                    }
                    if (v.bottom > parent.height) {
                        out += "$label: ${describe(v)} bottom edge ${v.bottom}px exceeds parent " +
                            "height ${parent.height}px (${parent.javaClass.simpleName})"
                    }
                }
                if (v.isShown && absRight(v) > root.width) {
                    out += "$label: ${describe(v)} right edge ${absRight(v)}px exceeds screen " +
                        "width ${root.width}px"
                }
                if (v.isShown && absBottom(v) > root.height) {
                    out += "$label: ${describe(v)} bottom edge ${absBottom(v)}px exceeds screen " +
                        "height ${root.height}px"
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }

        walk(root)
        return out
    }

    @Suppress("LongParameterList")
    private fun checkText(
        v: TextView,
        label: String,
        out: MutableList<String>,
        describe: (View) -> String,
        inScrollChain: (View) -> Boolean,
        absRight: (View) -> Int,
        absBottom: (View) -> Int,
        root: View,
        fixedBars: List<View>,
        isInside: (View, View) -> Boolean,
        absTop: (View) -> Int
    ) {
        val shown = v.text?.toString().orEmpty().ifEmpty { v.hint?.toString().orEmpty() }
        if (shown.isEmpty()) return
        // Two platform-owned cases are not action labels and are judged apart:
        //  - a Toolbar title is a document/file name (Material single-lines it);
        //    it may end with "…" but must not be hard-clipped, so the ellipsize
        //    attribute itself is required — that is checked below;
        //  - an EditText scrolls its own single line while you type.
        // A Toolbar title also measures to zero width when the bar hosts a custom
        // view (this screen's title field), which is layout, not a clipped label.
        var inToolbar = false
        var p = v.parent
        while (p != null) {
            if (p is androidx.appcompat.widget.Toolbar) { inToolbar = true; break }
            p = p.parent
        }
        if (inToolbar || v is android.widget.EditText) {
            checkBounds(v, label, out, describe, inScrollChain, absRight, absBottom, root, fixedBars,
                isInside, absTop)
            if (inToolbar && v.width > 0) {
                v.layout?.let { l ->
                    if (v.ellipsize == null && l.lineCount <= 1 &&
                        l.getLineWidth(0) >
                        v.width - v.totalPaddingLeft - v.totalPaddingRight + 0.5f
                    ) {
                        out += "$label: ${describe(v)} toolbar title is hard-clipped " +
                            "(no ellipsize set)"
                    }
                }
            }
            return
        }
        if (v.width <= 0) {
            out += "$label: ${describe(v)} is visible with text but has zero width"
            return
        }
        var layout = v.layout
        if (layout == null) {
            // A scroll listener can set the counter text between measure passes.
            // Lay the view out at its current width so the rules judge real text
            // instead of reporting a rig artefact (still reported if it stays null).
            val hSpec = if (v.height > 0) {
                View.MeasureSpec.makeMeasureSpec(v.height, View.MeasureSpec.EXACTLY)
            } else {
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            }
            v.measure(View.MeasureSpec.makeMeasureSpec(v.width, View.MeasureSpec.EXACTLY), hSpec)
            layout = v.layout
        }
        if (layout == null) {
            if (v.isLaidOut) {
                // The text was set after the last layout pass (a scroll listener
                // refreshing the page counter). No line can be judged from a stale
                // layout, but the pill contrast still counts.
                checkContrast(v, label, out, describe)
                checkBounds(v, label, out, describe, inScrollChain, absRight, absBottom, root,
                    fixedBars, isInside, absTop)
                return
            }
            out += "$label: ${describe(v)} has text but no Layout (never measured)"
            return
        }
        val availW = v.width - v.totalPaddingLeft - v.totalPaddingRight
        for (line in 0 until layout.lineCount) {
            // 1. breaks only between words.
            if (line < layout.lineCount - 1) {
                val end = layout.getLineEnd(line)
                if (end in 1 until layout.text.length) {
                    val brk = layout.text[end - 1]
                    if (BREAK_AFTER.none { it == brk }) {
                        out += "$label: ${describe(v)} breaks inside the word at line $line " +
                            "(after \"${layout.text.subSequence(layout.getLineStart(line), end)}\")"
                    }
                }
            }
            // 2. never silently truncated.
            if (layout.getEllipsisCount(line) > 0) {
                out += "$label: ${describe(v)} line $line is ellipsized " +
                    "(${layout.getEllipsisCount(line)} chars cut)"
            }
            // 3. every line inside the paddings. A StaticLayout line width
            //    includes the trailing space that caused the wrap (579 px vs
            //    576 px fit is not clipping), so measure the visible text.
            val lineStart = layout.getLineStart(line)
            val lineEnd = layout.getLineEnd(line)
            var visibleEnd = lineEnd
            while (visibleEnd > lineStart && layout.text[visibleEnd - 1].isWhitespace()) {
                visibleEnd--
            }
            val lineWidth = if (visibleEnd == lineEnd) {
                layout.getLineWidth(line)
            } else {
                v.paint.measureText(layout.text, lineStart, visibleEnd)
            }
            if (lineWidth > availW + 0.5f) {
                out += "$label: ${describe(v)} line $line is ${lineWidth.toInt()}px wide but " +
                    "only ${availW}px fit between paddings (clipped)"
            }
        }
        // 4. the text block fits the view's height.
        val top = maxOf(v.totalPaddingTop, v.compoundPaddingTop)
        val bottom = maxOf(v.totalPaddingBottom, v.compoundPaddingBottom)
        val viewHeight = if (v.height > 0) v.height else v.measuredHeight
        if (layout.height + top + bottom > viewHeight + 0.5f) {
            out += "$label: ${describe(v)} needs ${layout.height + top + bottom}px " +
                "(layout ${layout.height}px + pads $top/$bottom) but the view is ${viewHeight}px tall"
        }
        // 5. today's bug: a text pill of 40 % black over a white page.
        checkContrast(v, label, out, describe)
        // 6. text above any fixed bottom bar (a dock must never cover a line).
        checkBounds(v, label, out, describe, inScrollChain, absRight, absBottom, root, fixedBars,
            isInside, absTop)
    }

    /** WCAG contrast for a text view that draws its own coloured pill. */
    private fun checkContrast(
        v: TextView,
        label: String,
        out: MutableList<String>,
        describe: (View) -> String
    ) {
        val bg = v.background as? ColorDrawable ?: return
        val fg = v.currentTextColor
        if (Color.alpha(bg.color) < 255) {
            // Over an unknown photo/page: the worst case is a white and a black page.
            for (base in intArrayOf(Color.WHITE, Color.BLACK)) {
                val ratio = contrastRatio(fg, composeOver(bg.color, base))
                if (ratio < MIN_CONTRAST) {
                    out += "$label: ${describe(v)} contrast ${"%.2f".format(ratio)}:1 " +
                        "over ${if (base == Color.WHITE) "a white" else "a black"} " +
                        "background (needs $MIN_CONTRAST:1) — raise the pill opacity"
                }
            }
        } else if (contrastRatio(fg, bg.color) < MIN_CONTRAST) {
            out += "$label: ${describe(v)} contrast " +
                "${"%.2f".format(contrastRatio(fg, bg.color))}:1 on its own background"
        }
    }

    /** Bounds rules: inside the parent, inside the screen, never under a fixed bar. */
    @Suppress("LongParameterList", "UNUSED_PARAMETER")
    private fun checkBounds(
        v: TextView,
        label: String,
        out: MutableList<String>,
        describe: (View) -> String,
        inScrollChain: (View) -> Boolean,
        absRight: (View) -> Int,
        absBottom: (View) -> Int,
        root: View,
        fixedBars: List<View>,
        isInside: (View, View) -> Boolean,
        absTop: (View) -> Int
    ) {
        if (inScrollChain(v)) return
        for (bar in fixedBars) {
            if (bar === v || isInside(v, bar)) continue
            val barTop = absTop(bar)
            if (absBottom(v) > barTop + 0.5f && absTop(v) < absBottom(bar)) {
                out += "$label: ${describe(v)} is covered by the fixed bottom bar " +
                    "${describe(bar)} (text bottom ${absBottom(v)}px > bar top ${barTop}px)"
            }
        }
        if (absRight(v) > root.width) {
            out += "$label: ${describe(v)} text right edge ${absRight(v)}px exceeds the screen"
        }
    }

    fun composeOver(color: Int, base: Int): Int {
        val a = Color.alpha(color) / 255.0
        fun ch(c: Int, b: Int) = Math.round(c * a + b * (1 - a)).toInt().coerceIn(0, 255)
        return Color.rgb(
            ch(Color.red(color), Color.red(base)),
            ch(Color.green(color), Color.green(base)),
            ch(Color.blue(color), Color.blue(base))
        )
    }

    fun contrastRatio(fg: Int, bg: Int): Double {
        val l1 = luminance(fg)
        val l2 = luminance(bg)
        val hi = maxOf(l1, l2)
        val lo = minOf(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }

    /** True when the layout broke a line where the text has no break opportunity. */
    fun breaksInsideWord(layout: Layout): Boolean {
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            if (end in 1 until layout.text.length) {
                val brk = layout.text[end - 1]
                if (BREAK_AFTER.none { it == brk }) return true
            }
        }
        return false
    }
}
