package com.scanwhirl.app.ui.kit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.scanwhirl.app.R
import com.scanwhirl.app.util.ImageTools
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One side of a comparison. [imagePath] is optional: when absent we show [emptyText] instead. */
data class ComparePane(
    val imagePath: String? = null,
    val caption: String,
    val detail: List<String> = emptyList(),
    val emptyText: String? = null
)

/** The whole comparison. [outcome] is the sentence said before the Before | After control. */
data class CompareModel(
    val outcome: String,
    val before: ComparePane,
    val after: ComparePane,
    val pageLabel: String? = null,
    val showToggle: Boolean = true
)

/**
 * Lane C: one Before | After surface for every result screen (council §6).
 *
 * - segmented control, default After, 48dp targets, one shared frame;
 * - the page/paper keeps its own white but the canvas behind it is [R.color.sw_cc_canvas]
 *   with a 1dp [R.color.sw_cc_page_edge] edge — never a white host;
 * - both page images are decoded bounded and OFF the main thread, then swapped in place;
 * - when the original (or the result) image is unavailable, the honest empty text is
 *   shown in that pane instead of a blank white rectangle;
 * - captions and tabs carry TalkBack labels and the caption is a polite live region.
 *
 * The draggable divider was considered and deferred (accessibility cost); the segmented
 * control gives the same before/after proof with two labeled 48dp targets.
 */
class CompareView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val outcomeView: TextView
    private val beforeTab: TextView
    private val afterTab: TextView
    private val pageChip: TextView
    private val frame: FrameLayout
    private val pageFrame: FrameLayout
    private val image: ImageView
    private val caption: TextView
    private val detail: TextView

    private var model: CompareModel? = null
    private var showingAfter = true
    private var beforeBitmap: Bitmap? = null
    private var afterBitmap: Bitmap? = null

    /** The one in-flight decode; cancelled on a new bind and on detach. */
    private var decodeJob: Job? = null
    /** Invalidates results from a decode that was cancelled mid-flight. */
    private var decodeGeneration = 0

    /** Test seam: called on the decode (IO) thread just before each bounded decode. */
    internal var decodeProbe: ((String) -> Unit)? = null
    /** Test seam: called after both images (or honest empty states) are rendered. */
    internal var onDecodeComplete: (() -> Unit)? = null
    /** Test seam: the bitmaps this view freed (after the ImageView was cleared). */
    internal var onBitmapsReleased: ((Bitmap?, Bitmap?) -> Unit)? = null

    init {
        orientation = VERTICAL
        background = SwStyle.rounded(
            context, canvasColor(), 16f,
            stroke = ContextCompat.getColor(context, R.color.sw_cc_boundary), strokeDp = 1f
        )
        contentDescription = context.getString(R.string.cc_compare_label)

        // The outcome sentence is the headline; the Before | After control sits below it.
        outcomeView = SwStyle.text(
            context, 15f, ContextCompat.getColor(context, R.color.text_hologram_hi), bold = true
        ).apply {
            id = R.id.ccOutcome
            maxLines = Int.MAX_VALUE
        }
        addView(outcomeView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val tabs = LinearLayout(context).apply { orientation = HORIZONTAL }
        beforeTab = newTab(R.string.cc_before, R.string.cc_show_before).apply { id = R.id.ccBeforeTab }
        afterTab = newTab(R.string.cc_after, R.string.cc_show_after).apply { id = R.id.ccAfterTab }
        val tabH = SwStyle.dp(context, 48f)
        tabs.addView(beforeTab, LayoutParams(0, tabH, 1f))
        tabs.addView(afterTab, LayoutParams(0, tabH, 1f))
        addView(tabs, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        pageChip = SwStyle.text(
            context, 12f, ContextCompat.getColor(context, R.color.sw_cc_boundary),
            bold = true, metadata = true
        ).apply {
            id = R.id.ccPageChip
            gravity = Gravity.CENTER
            visibility = GONE
        }
        addView(pageChip, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val inset = SwStyle.dp(context, 8f)
        frame = FrameLayout(context).apply {
            id = R.id.ccPageFrame
            // The HOST is the dark canvas; the white lives only in the page below.
            background = ColorDrawable(canvasColor())
            setPadding(inset, inset, inset, inset)
        }
        pageFrame = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(ContextCompat.getColor(context, R.color.sw_cc_page))
                cornerRadius = SwStyle.dp(context, 4f).toFloat()
                setStroke(
                    SwStyle.dp(context, 1f).coerceAtLeast(1),
                    ContextCompat.getColor(context, R.color.sw_cc_page_edge)
                )
            }
            clipToOutline = true
        }
        image = ImageView(context).apply {
            id = R.id.ccPageImage
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = context.getString(R.string.cc_compare_label)
        }
        pageFrame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(pageFrame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(
            frame,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(context, 200f)).apply { topMargin = SwStyle.dp(context, 8f) }
        )

        caption = SwStyle.text(
            context, 14f, ContextCompat.getColor(context, R.color.text_hologram_hi), bold = true
        ).apply {
            id = R.id.ccCaption
            maxLines = Int.MAX_VALUE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        addView(
            caption,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = SwStyle.dp(context, 10f) }
        )
        detail = SwStyle.text(
            context, 12f, ContextCompat.getColor(context, R.color.text_hologram_mid),
            metadata = true
        ).apply {
            id = R.id.ccDetail
            maxLines = Int.MAX_VALUE
        }
        addView(
            detail,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = SwStyle.dp(context, 4f) }
        )

        beforeTab.setOnClickListener { select(false) }
        afterTab.setOnClickListener { select(true) }
        select(true)
    }

    private fun canvasColor(): Int = ContextCompat.getColor(context, R.color.sw_cc_canvas)

    private fun newTab(labelRes: Int, a11yRes: Int): TextView =
        SwStyle.asButton(
            SwStyle.text(
                context, 13f, ContextCompat.getColor(context, R.color.text_hologram_hi),
                bold = true, metadata = true
            ).apply {
                text = context.getString(labelRes)
                contentDescription = context.getString(a11yRes)
                gravity = Gravity.CENTER
                minimumHeight = SwStyle.dp(context, 48f)
            }
        )

    /**
     * Bind new data and (when paths are present) decode both panes off the main thread.
     * [mainDispatcher] is a test seam; production callers keep the default Main dispatcher.
     */
    fun bind(
        model: CompareModel,
        scope: CoroutineScope,
        mainDispatcher: CoroutineDispatcher = Dispatchers.Main
    ) {
        // A new result invalidates any decode still running and frees what we held before.
        decodeGeneration++
        decodeJob?.cancel()
        decodeJob = null
        releaseBitmaps()

        this.model = model
        outcomeView.text = model.outcome
        visibility = if (model.showToggle || model.outcome.isNotBlank()) VISIBLE else GONE
        pageChip.text = model.pageLabel
        pageChip.visibility = if (model.pageLabel.isNullOrBlank()) GONE else VISIBLE
        beforeTab.visibility = if (model.showToggle) VISIBLE else GONE
        afterTab.visibility = if (model.showToggle) VISIBLE else GONE
        select(true)

        val beforePath = model.before.imagePath?.takeIf { it.isNotBlank() }
        val afterPath = model.after.imagePath?.takeIf { it.isNotBlank() }
        if (beforePath == null && afterPath == null) {
            onDecodeComplete?.invoke()
            return
        }
        val generation = decodeGeneration
        decodeJob = scope.launch {
            val before = decode(beforePath)
            val after = decode(afterPath)
            withContext(mainDispatcher) {
                if (generation != decodeGeneration) {
                    // Rebound or detached while decoding: never display a stale page.
                    recycleDecoded(before, after)
                    onBitmapsReleased?.invoke(before, after)
                    return@withContext
                }
                beforeBitmap = before
                afterBitmap = after
                render()
                onDecodeComplete?.invoke()
            }
        }
    }

    /**
     * Detach ends the surface's ownership of its decoded pages. Cancel first so an in-flight
     * decode cannot publish a bitmap into a dead view, then clear and free what we hold.
     */
    public override fun onDetachedFromWindow() {
        decodeGeneration++
        decodeJob?.cancel()
        decodeJob = null
        releaseBitmaps()
        super.onDetachedFromWindow()
    }

    /** Detach the drawable BEFORE recycling, so no frame can draw a freed bitmap. */
    private fun releaseBitmaps() {
        image.setImageDrawable(null)
        val before = beforeBitmap
        val after = afterBitmap
        beforeBitmap = null
        afterBitmap = null
        if (before == null && after == null) return
        recycleDecoded(before, after)
        onBitmapsReleased?.invoke(before, after)
    }

    /** Only bitmaps this view decoded are ever recycled; [image] is already cleared. */
    private fun recycleDecoded(before: Bitmap?, after: Bitmap?) {
        if (before != null && !before.isRecycled) before.recycle()
        if (after != null && !after.isRecycled && after !== before) after.recycle()
    }

    private suspend fun decode(path: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (path == null) return@withContext null
        decodeProbe?.invoke(path)
        try {
            val file = File(path)
            if (!file.isFile || file.length() == 0L) return@withContext null
            // Bounded by header sample: the full encoded bytes are never held in memory.
            ImageTools.decodeSampledFile(file, 1200)
        } catch (_: Exception) {
            null
        }
    }

    private fun select(after: Boolean) {
        showingAfter = after
        beforeTab.isSelected = !after
        afterTab.isSelected = after
        render()
    }

    private fun render() {
        val current = model ?: return
        val pane = if (showingAfter) current.after else current.before
        val bitmap = if (showingAfter) afterBitmap else beforeBitmap

        caption.text = pane.caption
        caption.contentDescription = context.getString(
            R.string.cc_caption_a11y, current.outcome, pane.caption
        )

        val lines = mutableListOf<String>()
        if (bitmap != null) {
            image.visibility = VISIBLE
            image.setImageBitmap(bitmap)
            image.contentDescription = context.getString(
                if (showingAfter) R.string.cc_after_image_a11y else R.string.cc_before_image_a11y,
                pane.caption
            )
            lines.addAll(pane.detail)
        } else {
            // No image: an honest line instead of a blank white rectangle.
            image.visibility = GONE
            image.setImageDrawable(null)
            pane.emptyText?.let { lines.add(it) }
            lines.addAll(pane.detail)
        }
        detail.text = lines.joinToString("\n")
        detail.visibility = if (lines.isEmpty()) GONE else VISIBLE
    }

    // ---- test seams -------------------------------------------------------

    internal fun showBefore() = select(false)
    internal fun showAfter() = select(true)
    internal fun isShowingAfter(): Boolean = showingAfter
    internal fun captionText(): String = caption.text.toString()
    internal fun outcomeText(): String = outcomeView.text.toString()
    internal fun emptyLabel(): TextView = detail
    internal fun displayedBitmap(): Bitmap? = (image.drawable as? BitmapDrawable)?.bitmap
    internal fun heldBitmaps(): Pair<Bitmap?, Bitmap?> = beforeBitmap to afterBitmap
    internal fun decodeGeneration(): Int = decodeGeneration
    internal fun pageImageView(): ImageView = image
    internal fun compareFrame(): FrameLayout = frame
    internal fun pageFrameView(): FrameLayout = pageFrame
    internal fun beforeButton(): TextView = beforeTab
    internal fun afterButton(): TextView = afterTab
}
