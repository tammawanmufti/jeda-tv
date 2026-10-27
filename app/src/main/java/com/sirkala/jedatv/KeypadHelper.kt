package com.sirkala.jedatv

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button

/**
 * Inline keypad for TV remotes. It starts visible on PIN screens; Back hides it,
 * and three Up presses within two seconds reveal it again.
 */
class KeypadHelper(
    private val activity: Activity,
    private var container: ViewGroup,
    private val onDigit: (String) -> Unit,
    private val onBackspace: () -> Unit,
    private val onClear: () -> Unit,
) {
    private var root: ViewGroup? = null
    private val upTimes = ArrayDeque<Long>()

    val visible: Boolean get() = root?.visibility == View.VISIBLE && root?.parent === container

    fun setContainer(host: ViewGroup) {
        container = host
    }

    /** true = gesture fired and keypad now open (host may still consume the key) */
    fun onKeyDown(keyCode: Int): Boolean {
        if (visible) return false
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            val now = System.currentTimeMillis()
            upTimes.addLast(now)
            while (upTimes.isNotEmpty() && now - upTimes.first() > GESTURE_WINDOW_MS) upTimes.removeFirst()
            if (upTimes.size >= GESTURE_TAPS) {
                upTimes.clear()
                show()
            }
        }
        return false
    }

    fun onBackPressed(): Boolean {
        if (!visible) return false
        hide()
        return true
    }

    fun show() {
        if (root == null) {
            root = activity.layoutInflater
                .inflate(R.layout.keypad, container, false) as ViewGroup
            container.addView(root)
            val digitIds = mapOf(
                R.id.k0 to "0", R.id.k1 to "1", R.id.k2 to "2", R.id.k3 to "3",
                R.id.k4 to "4", R.id.k5 to "5", R.id.k6 to "6", R.id.k7 to "7",
                R.id.k8 to "8", R.id.k9 to "9")
            digitIds.forEach { (id, label) ->
                root!!.findViewById<Button>(id).setOnClickListener { onDigit(label) }
            }
            root!!.findViewById<Button>(R.id.kc).setOnClickListener { onBackspace() }
            root!!.findViewById<Button>(R.id.kx).setOnClickListener { onClear() }
        }
        if (root!!.parent !== container) {
            (root!!.parent as? ViewGroup)?.removeView(root)
            container.addView(root)
        }
        root!!.visibility = View.VISIBLE
        root!!.findViewById<Button>(R.id.k1).requestFocus()
    }

    fun hide() {
        if (visible) {
            root!!.visibility = View.GONE
        }
    }

    companion object {
        const val GESTURE_WINDOW_MS = 2_000L
        const val GESTURE_TAPS = 3
    }
}
