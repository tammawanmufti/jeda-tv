package com.sirkala.jedatv

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal object TvUi {
    const val LIGHT = 0xfff0f1f0.toInt()
    const val MUTED = 0xff98a1a5.toInt()
    const val DARK = 0xff253039.toInt()
    private const val LEFT = 0xff20262a.toInt()
    private const val RIGHT = 0xff14191d.toInt()

    fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density + .5f).toInt()

    fun column(activity: Activity): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        clipToPadding = false
    }

    fun screen(activity: Activity, title: String): Triple<FrameLayout, LinearLayout, LinearLayout> {
        val root = FrameLayout(activity)
        val columns = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val leftScroll = ScrollView(activity).apply { isFillViewport = true; setBackgroundColor(LEFT) }
        val rightScroll = ScrollView(activity).apply { isFillViewport = true; setBackgroundColor(RIGHT) }
        val left = column(activity).apply { setPadding(dp(activity, 72), dp(activity, 42), dp(activity, 46), dp(activity, 32)) }
        val right = column(activity).apply { setPadding(dp(activity, 54), dp(activity, 48), dp(activity, 72), dp(activity, 32)) }
        leftScroll.addView(left)
        rightScroll.addView(right)
        columns.addView(leftScroll, LinearLayout.LayoutParams(0, -1, 1f))
        columns.addView(rightScroll, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(columns, FrameLayout.LayoutParams(-1, -1))
        label(activity, left, title, 34, LIGHT, 0, 38)
        return Triple(root, left, right)
    }

    fun label(activity: Activity, parent: LinearLayout, value: String, size: Int = 19,
              color: Int = LIGHT, top: Int = 0, bottom: Int = 0): TextView {
        val tv = TextView(activity).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
        }
        parent.addView(tv, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(activity, top); bottomMargin = dp(activity, bottom)
        })
        return tv
    }

    fun action(activity: Activity, parent: LinearLayout, value: String,
               selected: Boolean = false, onFocus: (() -> Unit)? = null,
               compact: Boolean = false, onClick: () -> Unit): TextView {
        val tv = TextView(activity).apply {
            id = View.generateViewId()
            isSelected = selected
            text = value
            textSize = if (compact) 17f else 19f
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(activity, 14), dp(activity, if (compact) 3 else 8), dp(activity, 12), dp(activity, if (compact) 3 else 8))
            minHeight = dp(activity, if (compact) 37 else 46)
            setOnClickListener { onClick() }
        }
        fun paint(focused: Boolean) {
            tv.setTextColor(if (focused) DARK else LIGHT)
            tv.background = GradientDrawable().apply {
                setColor(if (focused) 0xffe7eef0.toInt() else if (tv.isSelected) 0xff334047.toInt() else Color.TRANSPARENT)
                cornerRadius = dp(activity, 3).toFloat()
            }
        }
        tv.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            paint(focused)
            if (focused) onFocus?.invoke()
        }
        paint(false)
        parent.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, if (compact) 1 else 5) })
        return tv
    }

    fun select(view: TextView, selected: Boolean) {
        view.isSelected = selected
        view.onFocusChangeListener?.onFocusChange(view, view.hasFocus())
    }

    fun gap(activity: Activity, parent: LinearLayout, height: Int) {
        parent.addView(View(activity), LinearLayout.LayoutParams(1, dp(activity, height)))
    }
}
