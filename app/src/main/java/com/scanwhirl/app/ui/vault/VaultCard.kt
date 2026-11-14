package com.scanwhirl.app.ui.vault

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.scanwhirl.app.R
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.ui.kit.SwStyle

/**
 * v2 UI: 04_vault — one private-file row: paper thumbnail with the page count,
 * name, meta and a ⋯ menu. The kit's DocCard is typed to a library ScanDocument;
 * a Vault entry has no readable file until it is opened, so this row is the
 * Vault-specific equivalent of the same card.
 */
class VaultCard(context: Context) : LinearLayout(context) {

    val nameView: TextView
    val metaView: TextView
    val pageBadge: TextView
    val moreButton: ImageButton

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
            background = SwStyle.rounded(context, SwStyle.color(context, R.color.sw_paper_thumb), radiusDp = 9f)
            clipToOutline = true
        }
        thumbFrame.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_sym_encrypted)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    SwStyle.color(context, R.color.sw_thumb_ink)
                )
                scaleType = ImageView.ScaleType.CENTER
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        pageBadge = SwStyle.text(context, 10f, Color.WHITE, bold = true).apply {
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
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        info.addView(nameView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        metaView = SwStyle.text(context, 12.5f, SwStyle.color(context, R.color.text_hologram_dim)).apply {
            id = R.id.vaultEntryMeta
            maxLines = 2
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
            layoutParams = LayoutParams(SwStyle.dp(context, 36f), SwStyle.dp(context, 36f))
        }
        addView(moreButton)
    }

    fun bind(entry: VaultStore.Entry, meta: String, onOpen: () -> Unit, onMore: () -> Unit) {
        this.entry = entry
        nameView.text = entry.name
        metaView.text = meta
        pageBadge.text = entry.pageCount.toString()
        pageBadge.visibility = if (entry.pageCount > 0) VISIBLE else GONE
        setOnClickListener { onOpen() }
        moreButton.setOnClickListener { onMore() }
    }
}
