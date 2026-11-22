package com.scanwhirl.app.ui.vault

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.scanwhirl.app.R
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.ui.kit.SwStyle

/**
 * v2 UI: 04_vault — one private-file row: a page thumbnail on the shared dark
 * canvas (never a near-white tile), the name (two whole-word lines), one meta
 * line and a ⋯ menu. The kit's DocCard is typed to a library ScanDocument;
 * a Vault entry has no readable file until it is opened, so this row is the
 * Vault-specific equivalent of the same card.
 */
class VaultCard(context: Context) : LinearLayout(context) {

    val nameView: TextView
    val metaView: TextView
    val pageBadge: TextView
    val moreButton: ImageButton

    /** Council §3: private state is an overlay on the thumb, never a name badge. */
    val lockOverlay: ImageView

    var entry: VaultStore.Entry? = null
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = SwStyle.card(context, 18f)
        val d = resources.displayMetrics.density
        setPadding((10 * d).toInt(), (10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt())
        isClickable = true
        isFocusable = true

        val thumbFrame = FrameLayout(context).apply {
            // Council §4 / UI_100 §G1.7: sw_canvas with a 3dp inset and a 1dp
            // paper edge. The old near-white tile made a white sheet white-on-white.
            background = SwStyle.rounded(
                context, SwStyle.color(context, R.color.sw_canvas), 4f,
                stroke = SwStyle.color(context, R.color.sw_canvas_edge)
            )
            setForeground(
                SwStyle.rounded(
                    context, Color.TRANSPARENT, 4f,
                    stroke = SwStyle.color(context, R.color.sw_canvas_edge)
                )
            )
            setPadding((3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt())
            clipToOutline = true
        }
        thumbFrame.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_sym_encrypted)
                imageTintList = ColorStateList.valueOf(
                    SwStyle.color(context, R.color.sw_thumb_ink)
                )
                scaleType = ImageView.ScaleType.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        lockOverlay = ImageView(context).apply {
            id = R.id.vaultEntryLock
            setImageResource(R.drawable.ic_sym_lock)
            imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.gold_pro_primary))
            background = SwStyle.rounded(context, 0xCC0E1316.toInt(), 999f)
            scaleType = ImageView.ScaleType.CENTER
            setPadding((2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        thumbFrame.addView(
            lockOverlay,
            FrameLayout.LayoutParams((16 * d).toInt(), (16 * d).toInt()).apply {
                gravity = Gravity.TOP or Gravity.END
                rightMargin = (2 * d).toInt()
                topMargin = (2 * d).toInt()
            }
        )
        pageBadge = SwStyle.text(context, 12f, Color.WHITE, bold = true, metadata = true).apply {
            background = SwStyle.rounded(context, 0xCC0E1316.toInt(), radiusDp = 5f)
            setPadding((5 * d).toInt(), 0, (5 * d).toInt(), 0)
        }
        thumbFrame.addView(
            pageBadge,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                leftMargin = (4 * d).toInt()
                bottomMargin = (4 * d).toInt()
            }
        )
        addView(
            thumbFrame,
            LayoutParams(SwStyle.dp(context, 54f), SwStyle.dp(context, 70f)).apply {
                marginEnd = (14 * d).toInt()
            }
        )

        val info = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameView = SwStyle.text(context, 15.5f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
            id = R.id.vaultEntryName
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            //noinspection WrongConstant
            breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this,
                (12f * d).toInt(), (15.5f * d).toInt(), 1,
                android.util.TypedValue.COMPLEX_UNIT_PX
            )
        }
        info.addView(nameView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        metaView = SwStyle.text(
            context, 12.5f, SwStyle.color(context, R.color.text_hologram_dim), metadata = true
        ).apply {
            id = R.id.vaultEntryMeta
            // One meta line at 100%; more lines are only a large-font safety valve.
            maxLines = when {
                resources.configuration.fontScale >= 1.6f -> 3
                resources.configuration.fontScale >= 1.15f -> 2
                else -> 1
            }
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.2f)
        }
        info.addView(
            metaView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (4 * d).toInt() }
        )
        addView(info, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        moreButton = SwStyle.iconButton(
            context, R.drawable.ic_sym_more_vert, SwStyle.color(context, R.color.text_hologram_mid)
        ).apply {
            id = R.id.vaultEntryMore
            contentDescription = context.getString(R.string.more)
            // UX5-4/UX5-11: a 36dp overflow is too small. Keep the 24dp glyph, grow the target.
            layoutParams = LayoutParams(
                SwStyle.dp(context, 48f), SwStyle.dp(context, 48f)
            )
        }
        addView(moreButton)
    }

    fun bind(entry: VaultStore.Entry, meta: String, onOpen: () -> Unit, onMore: () -> Unit) {
        this.entry = entry
        nameView.text = entry.name
        metaView.text = meta
        moreButton.contentDescription = context.getString(R.string.ux130_l5_vault_more, entry.name)
        pageBadge.text = entry.pageCount.toString()
        pageBadge.visibility = if (entry.pageCount > 0) VISIBLE else GONE
        // Every Vault row is private: the lock is an overlay on the thumb, so a
        // name can never be squeezed by a badge. TalkBack reads the row label.
        lockOverlay.isVisible = true
        setOnClickListener { onOpen() }
        moreButton.setOnClickListener { onMore() }
    }
}
