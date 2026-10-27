package com.sirkala.jedatv

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/** The 1280 × 720 Pencil artboard, scaled as one canvas for other TV resolutions. */
internal class AppsLockCanvas(private val activity: Activity) {
    companion object {
        const val WHITE = 0xfff0f1f0.toInt()
        const val FOCUS = 0xffeaf0f1.toInt()
        const val DARK = 0xff253039.toInt()
        const val MUTED = 0xff98a1a5.toInt()
        const val HEADING = 0xffc9cdce.toInt()
        const val INACTIVE = 0xff747e83.toInt()
        const val TILE = 0xff2d363c.toInt()
        const val SELECTED = 0xff354149.toInt()
    }

    private val width = activity.resources.displayMetrics.widthPixels
    private val height = activity.resources.displayMetrics.heightPixels
    val scale = minOf(width / 1280f, height / 720f)
    private val offsetX = ((width - 1280 * scale) / 2f).toInt()
    private val offsetY = ((height - 720 * scale) / 2f).toInt()
    val root = FrameLayout(activity).apply {
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xff22272b.toInt(), 0xff101418.toInt()))
    }

    fun px(value: Int): Int = (value * scale + .5f).toInt()

    fun shape(color: Int, radius: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = px(radius).toFloat()
    }

    fun place(parent: FrameLayout, child: View, x: Int, y: Int, w: Int, h: Int) {
        parent.addView(child, FrameLayout.LayoutParams(px(w), px(h)).apply {
            leftMargin = px(x) + if (parent === root) offsetX else 0
            topMargin = px(y) + if (parent === root) offsetY else 0
        })
    }

    fun box(x: Int, y: Int, w: Int, h: Int, color: Int, radius: Int = 0,
            parent: FrameLayout = root): FrameLayout = FrameLayout(activity).also {
        it.background = shape(color, radius)
        place(parent, it, x, y, w, h)
    }

    fun text(x: Int, y: Int, w: Int, h: Int, content: String, size: Int, color: Int,
             medium: Boolean = false, parent: FrameLayout = root): TextView =
        TextView(activity).also {
            it.text = content
            it.setTextColor(color)
            it.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale)
            it.typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            it.includeFontPadding = false
            it.gravity = Gravity.CENTER_VERTICAL
            it.isFocusable = false
            it.maxLines = 1
            it.ellipsize = android.text.TextUtils.TruncateAt.END
            place(parent, it, x, y, w, h)
        }

    fun focusable(frame: FrameLayout, click: () -> Unit, focus: ((Boolean) -> Unit)? = null) {
        frame.id = View.generateViewId()
        frame.isFocusable = true
        frame.isClickable = true
        frame.setOnClickListener { click() }
        frame.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> focus?.invoke(hasFocus) }
    }
}
