package com.scanwhirl.app.ui.kit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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

/** How much proof this result carries. NONE means no image at all (state-only tools). */
enum class CompareMode { ZOOM_STACKED, AFTER_ONLY, NONE }

/**
 * The whole comparison. [outcome] is kept for compatibility/a11y; the visible hero lives in the
 * result screen. [mode] decides whether we show a stacked zoom pair, one After page, or nothing.
 */
data class CompareModel(
    val outcome: String,
    val before: ComparePane,
    val after: ComparePane,
    val pageLabel: String? = null,
    val showToggle: Boolean = true,
    val mode: CompareMode = CompareMode.ZOOM_STACKED
)

/**
 * Lane RS: the one proof surface on a tool result.
 *
 * - Compress/Resize: Before ABOVE After, full width, the SAME top-left crop rectangle and the same
 *   scale on both, so text stays legible in portrait and sharpness loss is visible (lead decision,
 *   replacing the old segmented Before|After toggle).
 * - Stamp/Sign/Watermark/Page numbers: one After page on the dark canvas, no Before.
 * - Protect/Unlock/OCR/Clear metadata: no image at all.
 *
 * Every page is decoded bounded and OFF the main thread. Results are generation-guarded; both the
 * decoded source pages and the crops this view owns are recycled on rebind and on detach. A
 * locked/vaulted source is never passed here by the producer.
 */
class CompareView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val outcomeView: TextView
    private val beforeLabel: TextView
    private val afterLabel: TextView
    private val pageChip: TextView
    private val beforeFrame: FrameLayout
    private val afterFrame: FrameLayout
    private val beforePageFrame: FrameLayout
    private val afterPageFrame: FrameLayout
    private val beforeImage: ImageView
    private val afterImage: ImageView
    private val caption: TextView
    private val detail: TextView
    private val zoomCaption: TextView

    private var model: CompareModel? = null
    private var beforeBitmap: Bitmap? = null
    private var afterBitmap: Bitmap? = null
    private var beforeCrop: Rect? = null
    private var afterCrop: Rect? = null
    /** A page that decoded but carries no ink must not paint a white rectangle. */
    private var blankBefore = false
    private var blankAfter = false

    /**
     * Each image pane is capped from the available screen height (not a fixed 132dp): on a
     * 360x640dp phone both panes and the pinned action bar coexist at rest; on a taller phone
     * they grow up to 170dp. The two 48dp labels and the zoom caption stay out of this budget.
     */
    private val paneHeight: Int

    /** The one in-flight decode; cancelled on a new bind and on detach. */
    private var decodeJob: Job? = null
    /** Invalidates results from a decode that was cancelled mid-flight. */
    private var decodeGeneration = 0

    /** Test seam: called on the decode (IO) thread just before each bounded decode. */
    internal var decodeProbe: ((String) -> Unit)? = null
    /** Test seam: called with the identical crop rectangle chosen for each pane. */
    internal var cropProbe: ((Rect) -> Unit)? = null
    /** Test seam: the ONE normalized (0..1) content rect shared by both panes. */
    internal var relativeCropProbe: ((RectF) -> Unit)? = null
    /** Test seam: called after both images (or honest empty states) are rendered. */
    internal var onDecodeComplete: (() -> Unit)? = null
    /** Test seam: the bitmaps this view freed (after the ImageViews were cleared). */
    internal var onBitmapsReleased: ((Bitmap?, Bitmap?) -> Unit)? = null

    init {
        orientation = VERTICAL
        paneHeight = SwStyle.dp(context, paneHeightDp())
        background = SwStyle.rounded(
            context, canvasColor(), 16f,
            stroke = ContextCompat.getColor(context, R.color.sw_cc_boundary), strokeDp = 1f
        )
        contentDescription = context.getString(R.string.cc_compare_label)

        // Kept as an a11y node only: the outcome sentence is the result screen's hero now.
        outcomeView = SwStyle.text(
            context, 13f, ContextCompat.getColor(context, R.color.text_hologram_mid), bold = true
        ).apply {
            id = R.id.ccOutcome
            visibility = GONE
            maxLines = Int.MAX_VALUE
        }
        addView(outcomeView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        beforeLabel = newLabel(R.string.rs_before_size_label, R.string.cc_show_before).apply { id = R.id.ccBeforeTab }
        addView(beforeLabel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        beforeFrame = newCanvasFrame().apply { id = R.id.ccBeforeFrame }
        beforePageFrame = newPageFrame()
        val beforePair = newPage(beforeFrame, beforePageFrame, R.id.ccBeforeImage)
        beforeImage = beforePair.second
        addView(beforeFrame, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, paneHeight).apply { topMargin = SwStyle.dp(context, 4f) })

        afterLabel = newLabel(R.string.rs_after_size_label, R.string.cc_show_after).apply { id = R.id.ccAfterTab }
        addView(afterLabel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = SwStyle.dp(context, 8f) })

        afterFrame = newCanvasFrame().apply { id = R.id.ccPageFrame }
        afterPageFrame = newPageFrame()
        val afterPair = newPage(afterFrame, afterPageFrame, R.id.ccAfterImage)
        afterImage = afterPair.second
        addView(afterFrame, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, paneHeight).apply { topMargin = SwStyle.dp(context, 4f) })

        pageChip = SwStyle.text(
            context, 14f, ContextCompat.getColor(context, R.color.sw_text_primary),
            bold = true, metadata = true
        ).apply {
            id = R.id.ccPageChip
            gravity = Gravity.CENTER
            visibility = GONE
        }
        addView(pageChip, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        caption = SwStyle.text(
            context, 13f, ContextCompat.getColor(context, R.color.text_hologram_hi), bold = true
        ).apply {
            id = R.id.ccCaption
            maxLines = Int.MAX_VALUE
            visibility = GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        addView(caption, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        detail = SwStyle.text(
            context, 14f, ContextCompat.getColor(context, R.color.sw_text_secondary),
            metadata = true
        ).apply {
            id = R.id.ccDetail
            maxLines = Int.MAX_VALUE
            visibility = GONE
        }
        addView(detail, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = SwStyle.dp(context, 2f) })

        zoomCaption = SwStyle.text(
            context, 14f, ContextCompat.getColor(context, R.color.sw_text_secondary), metadata = true
        ).apply {
            id = R.id.rsZoomCaption
            text = context.getString(R.string.rs_zoom_caption)
            gravity = Gravity.CENTER
        }
        addView(zoomCaption, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = SwStyle.dp(context, 6f) })
    }

    private fun canvasColor(): Int = ContextCompat.getColor(context, R.color.sw_cc_canvas)

    private fun newLabel(captionRes: Int, a11yRes: Int): TextView =
        SwStyle.asButton(
            SwStyle.text(
                context, 14f, ContextCompat.getColor(context, R.color.sw_text_primary),
                bold = true, metadata = true
            ).apply {
                text = context.getString(captionRes)
                contentDescription = context.getString(a11yRes)
                minimumHeight = SwStyle.dp(context, 48f)
                gravity = Gravity.CENTER_VERTICAL
            }
        )

    private fun newCanvasFrame(): FrameLayout = FrameLayout(context).apply {
        background = ColorDrawable(canvasColor())
        setPadding(SwStyle.dp(context, 8f), SwStyle.dp(context, 8f), SwStyle.dp(context, 8f), SwStyle.dp(context, 8f))
    }

    private fun newPageFrame(): FrameLayout = FrameLayout(context).apply {
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

    private fun newPage(host: FrameLayout, page: FrameLayout, imageId: Int): Pair<FrameLayout, ImageView> {
        val image = ImageView(context).apply {
            id = imageId
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = context.getString(R.string.cc_compare_label)
        }
        page.addView(image, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        host.addView(page, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        return page to image
    }

    /** App bar + pinned dock + bars + hero/receipt + labels fit inside the screen budget. */
    private fun paneHeightDp(): Float {
        val dm = resources.displayMetrics
        val screenDp = dm.heightPixels / dm.density.coerceAtLeast(0.01f)
        val reservedDp = 56f + 176f + 24f + 132f + 138f
        return ((screenDp - reservedDp) / 2f).coerceIn(52f, 170f)
    }

    /** The legacy fallback, used only when neither page has detectable ink. */
    private fun fallbackRect(): RectF = RectF(0.06f, 0.08f, 0.46f, 0.42f)

    private fun hasInk(px: Int): Boolean {
        if (Color.alpha(px) < 200) return true
        return Color.red(px) < 235 || Color.green(px) < 235 || Color.blue(px) < 235
    }

    /**
     * Content-aware page box in normalized (0..1) coordinates. Near-white margins, the white
     * letterbox bars of a borders-mode resize, and a blank rendered page are ignored. Sampled on
     * a grid so a full page render never blocks for long. Null means "essentially blank".
     */
    internal fun contentBox(bitmap: Bitmap): RectF? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 8 || h < 8) return null
        val step = maxOf(1, minOf(w, h) / 100)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        var ink = 0
        var samples = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                samples++
                if (hasInk(bitmap.getPixel(x, y))) {
                    ink++
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
                x += step
            }
            y += step
        }
        if (ink < 4 || ink * 1000 < samples) return null
        return RectF(
            minX.toFloat() / w, minY.toFloat() / h,
            (maxX + 1).toFloat() / w, (maxY + 1).toFloat() / h
        )
    }

    /**
     * ONE relative crop for both panes: the union of their content boxes (or the one side that
     * has ink). Padding and a minimum size keep the zoom legible; letterbox bars never win.
     */
    internal fun sharedRect(before: RectF?, after: RectF?): RectF {
        // No ink on either side: keep the legacy fixed zoom exactly (no padding, no drift).
        if (before == null && after == null) return fallbackRect()
        val base = when {
            before != null && after != null -> RectF(
                minOf(before.left, after.left), minOf(before.top, after.top),
                maxOf(before.right, after.right), maxOf(before.bottom, after.bottom)
            )
            before != null -> RectF(before)
            after != null -> RectF(after)
            else -> fallbackRect()
        }
        val cx = (base.left + base.right) / 2f
        val cy = (base.top + base.bottom) / 2f
        val hw = ((base.right - base.left) / 2f + 0.03f).coerceIn(0.15f, 0.5f)
        val hh = ((base.bottom - base.top) / 2f + 0.03f).coerceIn(0.11f, 0.5f)
        var left = (cx - hw).coerceIn(0f, 1f - 2f * hw)
        var top = (cy - hh).coerceIn(0f, 1f - 2f * hh)
        if (left < 0f) left = 0f
        if (top < 0f) top = 0f
        return RectF(left, top, left + 2f * hw, top + 2f * hh)
    }

    internal fun rectFor(relative: RectF, width: Int, height: Int): Rect {
        val l = (relative.left * width).toInt().coerceIn(0, (width - 1).coerceAtLeast(0))
        val t = (relative.top * height).toInt().coerceIn(0, (height - 1).coerceAtLeast(0))
        val r = (relative.right * width).toInt().coerceIn((l + 1).coerceAtMost(width), width)
        val b = (relative.bottom * height).toInt().coerceIn((t + 1).coerceAtMost(height), height)
        return Rect(l, t, r, b)
    }

    /** Kept for callers/tests that assert the old fixed fallback on an all-white page. */
    internal fun zoomRect(width: Int, height: Int): Rect = rectFor(fallbackRect(), width, height)

    internal fun zoomRects(): Pair<Rect?, Rect?> = beforeCrop to afterCrop

    /**
     * Bind new data and (when paths are present) decode both panes off the main thread.
     * [mainDispatcher] is a test seam; production callers keep the default Main dispatcher.
     */
    fun bind(
        model: CompareModel,
        scope: CoroutineScope,
        mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
        canDecode: (String) -> Boolean = { true }
    ) {
        decodeGeneration++
        decodeJob?.cancel()
        decodeJob = null
        releaseBitmaps()
        beforeCrop = null
        afterCrop = null
        blankBefore = false
        blankAfter = false

        this.model = model
        outcomeView.text = model.outcome
        pageChip.text = model.pageLabel
        pageChip.visibility = if (model.pageLabel.isNullOrBlank()) GONE else VISIBLE

        when (model.mode) {
            CompareMode.NONE -> {
                visibility = GONE
                onDecodeComplete?.invoke()
                return
            }
            CompareMode.AFTER_ONLY -> {
                visibility = VISIBLE
                beforeLabel.visibility = GONE
                beforeFrame.visibility = GONE
                afterLabel.visibility = VISIBLE
                afterFrame.visibility = VISIBLE
                zoomCaption.visibility = GONE
            }
            CompareMode.ZOOM_STACKED -> {
                visibility = VISIBLE
                beforeLabel.visibility = VISIBLE
                beforeFrame.visibility = VISIBLE
                afterLabel.visibility = VISIBLE
                afterFrame.visibility = VISIBLE
                zoomCaption.visibility = VISIBLE
            }
        }

        beforeLabel.text = if (model.mode == CompareMode.ZOOM_STACKED) {
            context.getString(R.string.cc_before)
        } else {
            model.before.caption
        }
        afterLabel.text = if (model.mode == CompareMode.ZOOM_STACKED) {
            context.getString(R.string.rs_after_label)
        } else {
            model.after.caption.ifBlank { context.getString(R.string.rs_after_label) }
        }
        beforeLabel.contentDescription = context.getString(
            R.string.cc_caption_a11y, model.outcome, beforeLabel.text
        )
        afterLabel.contentDescription = context.getString(
            R.string.cc_caption_a11y, model.outcome, afterLabel.text
        )

        val beforePath = model.before.imagePath?.takeIf { it.isNotBlank() }
        val afterPath = model.after.imagePath?.takeIf { it.isNotBlank() }
        // Missing-source facts are known now, not after asynchronous image decoding.
        renderEmpty()
        if (beforePath == null && afterPath == null) {
            onDecodeComplete?.invoke()
            return
        }
        val generation = decodeGeneration
        decodeJob = scope.launch {
            val before = decode(beforePath, canDecode)
            val after = decode(afterPath, canDecode)
            val afterOnly = model.mode == CompareMode.AFTER_ONLY
            // Ink boxes are measured off Main; one relative rect is then shared by BOTH pages,
            // so a near-white margin or a resize letterbox never dominates the comparison.
            val beforeBox = before?.let { contentBox(it) }
            val afterBox = after?.let { contentBox(it) }
            val blankB = before != null && beforeBox == null
            val blankA = after != null && afterBox == null
            val shared = if (afterOnly) null else sharedRect(beforeBox, afterBox)
            shared?.let { relativeCropProbe?.invoke(it) }
            val bc = if (before != null && !afterOnly) {
                shared?.let { rectFor(it, before.width, before.height) }
            } else null
            val ac = when {
                after == null -> null
                afterOnly -> Rect(0, 0, after.width, after.height)
                else -> shared?.let { rectFor(it, after.width, after.height) }
            }
            val beforeCropBitmap = if (!blankB && before != null && bc != null) crop(before, bc) else null
            val afterCropBitmap = if (!blankA && after != null && ac != null) crop(after, ac) else null
            before?.recycle()
            if (after !== before) after?.recycle()
            withContext(mainDispatcher) {
                if (generation != decodeGeneration) {
                    recycleDecoded(beforeCropBitmap, afterCropBitmap)
                    onBitmapsReleased?.invoke(beforeCropBitmap, afterCropBitmap)
                    return@withContext
                }
                beforeBitmap = beforeCropBitmap
                afterBitmap = afterCropBitmap
                beforeCrop = bc
                afterCrop = ac
                blankBefore = blankB || (beforePath != null && before == null)
                blankAfter = blankA || (afterPath != null && after == null)
                bc?.let { cropProbe?.invoke(it) }
                ac?.let { cropProbe?.invoke(it) }
                render()
                onDecodeComplete?.invoke()
            }
        }
    }

    private fun renderEmpty() {
        val m = model ?: return
        val lines = mutableListOf<String>()
        if (m.mode == CompareMode.ZOOM_STACKED) {
            when {
                m.before.imagePath == null -> m.before.emptyText?.let { lines.add(it) }
                blankBefore -> lines.add(
                    context.getString(R.string.cc_blank_page_before, m.before.caption)
                )
            }
        }
        when {
            m.after.imagePath == null -> m.after.emptyText?.let { lines.add(it) }
            blankAfter -> lines.add(context.getString(R.string.cc_blank_page_after, m.after.caption))
        }
        detail.text = lines.joinToString("\n")
        detail.visibility = if (lines.isEmpty()) GONE else VISIBLE
        // A missing or blank side never keeps a white paper rectangle on screen. The honest
        // text above sits on the dark canvas surface instead.
        beforePageFrame.visibility = if (beforeBitmap == null) GONE else VISIBLE
        afterPageFrame.visibility = if (afterBitmap == null) GONE else VISIBLE
    }

    private fun crop(source: Bitmap, rect: Rect): Bitmap? = try {
        val w = (rect.right - rect.left).coerceAtLeast(1)
        val h = (rect.bottom - rect.top).coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, rect, Rect(0, 0, w, h), Paint())
        out
    } catch (_: Exception) {
        null
    }

    public override fun onDetachedFromWindow() {
        decodeGeneration++
        decodeJob?.cancel()
        decodeJob = null
        releaseBitmaps()
        super.onDetachedFromWindow()
    }

    /** Detach the drawables BEFORE recycling, so no frame can draw a freed bitmap. */
    private fun releaseBitmaps() {
        beforeImage.setImageDrawable(null)
        afterImage.setImageDrawable(null)
        val before = beforeBitmap
        val after = afterBitmap
        beforeBitmap = null
        afterBitmap = null
        if (before == null && after == null) return
        recycleDecoded(before, after)
        onBitmapsReleased?.invoke(before, after)
    }

    private fun recycleDecoded(before: Bitmap?, after: Bitmap?) {
        if (before != null && !before.isRecycled) before.recycle()
        if (after != null && !after.isRecycled && after !== before) after.recycle()
    }

    private suspend fun decode(path: String?, canDecode: (String) -> Boolean): Bitmap? = withContext(Dispatchers.IO) {
        if (path == null) return@withContext null
        try {
            if (!canDecode(path)) return@withContext null
            decodeProbe?.invoke(path)
            val file = File(path)
            if (!file.isFile || file.length() == 0L) return@withContext null
            ImageTools.decodeSampledFile(file, 1200)
        } catch (_: Exception) {
            null
        }
    }

    private fun render() {
        val current = model ?: return
        beforeImage.visibility = GONE
        afterImage.visibility = GONE
        beforeImage.setImageDrawable(null)
        afterImage.setImageDrawable(null)

        beforeBitmap?.let {
            beforeImage.visibility = VISIBLE
            beforeImage.setImageBitmap(it)
            beforeImage.contentDescription = context.getString(
                R.string.cc_before_image_a11y, current.before.caption
            )
        }
        afterBitmap?.let {
            afterImage.visibility = VISIBLE
            afterImage.setImageBitmap(it)
            afterImage.contentDescription = context.getString(
                R.string.cc_after_image_a11y, current.after.caption
            )
        }
        renderEmpty()
    }

    // ---- test seams -------------------------------------------------------

    internal fun outcomeText(): String = outcomeView.text.toString()
    internal fun captionText(): String = afterLabel.text.toString()
    internal fun beforeCaptionText(): String = beforeLabel.text.toString()
    internal fun emptyLabel(): TextView = detail
    internal fun displayedBitmap(): Bitmap? = (afterImage.drawable as? BitmapDrawable)?.bitmap
    internal fun heldBitmaps(): Pair<Bitmap?, Bitmap?> = beforeBitmap to afterBitmap
    internal fun decodeGeneration(): Int = decodeGeneration
    internal fun pageImageView(): ImageView = afterImage
    internal fun compareFrame(): FrameLayout = afterFrame
    internal fun pageFrameView(): FrameLayout = afterPageFrame
    internal fun beforeButton(): TextView = beforeLabel
    internal fun afterButton(): TextView = afterLabel
    internal fun beforeImageView(): ImageView = beforeImage
    internal fun paneHeightPx(): Int = paneHeight
    internal fun beforePageFrameView(): FrameLayout = beforePageFrame
    internal fun blankFlags(): Pair<Boolean, Boolean> = blankBefore to blankAfter
    internal fun isShowingAfter(): Boolean = true
    internal fun showBefore() = Unit
    internal fun showAfter() = Unit
}
