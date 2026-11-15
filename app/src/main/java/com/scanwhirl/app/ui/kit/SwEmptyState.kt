package com.scanwhirl.app.ui.kit

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.scanwhirl.app.R

/**
 * v2 UI: 74_motion / 01_home_A — the kit empty state: brand line-art at 30%
 * opacity, one short line of copy and one primary action. Every empty surface
 * (Library, Trash, Vault, Folders, QR history) reuses this instead of a void.
 */
class SwEmptyState(context: Context) : LinearLayout(context) {

    private val art: ImageView
    private val titleView: TextView
    private val bodyView: TextView
    private val actionView: TextView

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        id = R.id.swEmptyState
        setPadding(
            SwStyle.dp(context, 24f), SwStyle.dp(context, 28f),
            SwStyle.dp(context, 24f), SwStyle.dp(context, 28f)
        )

        art = SwMotion.brandArt(context)
        addView(art, LayoutParams(SwStyle.dp(context, 96f), SwStyle.dp(context, 96f)))

        titleView = SwStyle.text(
            context, 18f, SwStyle.color(context, R.color.text_hologram_hi), bold = true
        ).apply {
            id = R.id.swEmptyTitle
            gravity = Gravity.CENTER
        }
        addView(
            titleView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 14f) }
        )

        bodyView = SwStyle.text(context, 13.5f, SwStyle.color(context, R.color.text_hologram_mid)).apply {
            id = R.id.swEmptyBody
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }
        addView(
            bodyView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 6f) }
        )

        actionView = SwStyle.text(
            context, 15f, SwStyle.color(context, R.color.sw_disc_ink), bold = true
        ).apply {
            id = R.id.swEmptyAction
            gravity = Gravity.CENTER
            background = SwStyle.rounded(
                context, SwStyle.color(context, R.color.cyan_primary), radiusDp = 16f
            )
            minimumHeight = SwStyle.dp(context, 48f)
        }
    }

    /** One short line, optional body, optional primary action (added on first use). */
    fun bind(
        title: CharSequence,
        body: CharSequence? = null,
        action: CharSequence? = null,
        onAction: (() -> Unit)? = null
    ): SwEmptyState = apply {
        titleView.text = title
        bodyView.text = body?.toString().orEmpty()
        bodyView.isVisible = !body.isNullOrBlank()
        if (!action.isNullOrBlank()) {
            if (actionView.parent == null) {
                addView(
                    actionView,
                    LayoutParams(LayoutParams.MATCH_PARENT, SwStyle.dp(context, 48f))
                        .apply { topMargin = SwStyle.dp(context, 18f) }
                )
            }
            actionView.text = action
            SwMotion.press(actionView)
            actionView.setOnClickListener { onAction?.invoke() }
        }
    }

    val action: View get() = actionView
}
