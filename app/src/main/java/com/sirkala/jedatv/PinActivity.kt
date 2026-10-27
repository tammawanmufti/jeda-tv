package com.sirkala.jedatv

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class PinActivity : Activity() {

    companion object {
        const val EXTRA_TARGET_PACKAGE = "target_package"
        var instance: PinActivity? = null
    }

    private lateinit var display: TextView
    private lateinit var hint: TextView
    private lateinit var keypad: KeypadHelper
    private var entered = ""
    private var verifying = false
    private var verificationGeneration = 0
    private var active = false
    private val pinWorker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        setContentView(R.layout.activity_pin)
        display = findViewById(R.id.pin_display)
        hint = findViewById(R.id.pin_hint_text)
        updateTargetTitle()
        if (Prefs(this).english()) {
            findViewById<TextView>(R.id.pin_subtitle).text = "Enter the parent PIN"
            hint.text = "Enter the 4-digit PIN with your remote"
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        keypad = KeypadHelper(this, findViewById(R.id.pin_keypad_host),
            onDigit = { feed(it) }, onBackspace = { back() }, onClear = { clear() })
        keypad.show()
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        if (intent != null) setIntent(intent)
        updateTargetTitle()
    }

    private fun updateTargetTitle() {
        val pkg = intent?.getStringExtra(EXTRA_TARGET_PACKAGE)
        val name = pkg?.let {
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }
                .getOrNull()?.takeUnless { label -> label == it } ?: it.substringAfterLast('.')
        }
        findViewById<TextView>(R.id.pin_title).text = if (name == null) {
            if (Prefs(this).english()) "App locked" else "Aplikasi terkunci"
        } else if (Prefs(this).english()) "$name is locked" else "$name terkunci"
    }

    private fun updateDisplay() {
        display.text = "● ".repeat(entered.length) + "○ ".repeat(4 - entered.length)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Launch-shortcut hardware keys must be consumed while the gate is up,
        // otherwise the system relaunches the blocked app over the PIN screen
        // and sustains the swap storm that deadlocks change-only gating.
        if (isShortcutLaunchKey(keyCode)) return true
        val digit = when (keyCode) {
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> "0"
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> "1"
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> "2"
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> "3"
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> "4"
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> "5"
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> "6"
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> "7"
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> "8"
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> "9"
            else -> null
        }
        if (digit != null) { feed(digit); return true }
        if (keyCode == KeyEvent.KEYCODE_BACK && keypad.onBackPressed()) return true
        if (keyCode == KeyEvent.KEYCODE_DEL && entered.isNotEmpty()) { back(); return true }
        keypad.onKeyDown(keyCode)
        return super.onKeyDown(keyCode, event)
    }

    private fun feed(d: String) {
        if (verifying) return
        if (entered.length < 4) {
            entered += d
            updateDisplay()
            if (entered.length == 4) verifyPin()
        }
    }

    private fun back() {
        if (verifying) return
        if (entered.isNotEmpty()) {
            entered = entered.dropLast(1)
            updateDisplay()
        }
    }

    private fun clear() {
        if (verifying) return
        entered = ""
        updateDisplay()
    }

    private fun verifyPin() {
        verifying = true
        val prefs = Prefs(this)
        val candidate = entered
        val generation = ++verificationGeneration
        val started = SystemClock.uptimeMillis()
        hint.text = if (prefs.english()) "Checking PIN…" else "Memeriksa PIN…"
        pinWorker.execute {
            val correct = runCatching { prefs.checkPin(candidate) }.getOrDefault(false)
            runOnUiThread {
                val remaining = (80L - (SystemClock.uptimeMillis() - started)).coerceAtLeast(0L)
                display.postDelayed({
                    if (generation != verificationGeneration || !active || isFinishing || isDestroyed) return@postDelayed
                    verifying = false
                    if (correct) {
                        accepted = true
                        hint.text = if (prefs.english()) "Resuming app…" else "Melanjutkan aplikasi…"
                        LockPollService.instance?.onUnlocked()
                        Toast.makeText(this, if (prefs.english()) "Unlocked" else getString(R.string.unlocked),
                            Toast.LENGTH_SHORT).show()
                        display.postDelayed({ if (!isFinishing && !isDestroyed) finish() }, 250L)
                    } else {
                        entered = ""
                        val wait = prefs.pinWaitSeconds()
                        updateDisplay()
                        hint.text = if (wait > 0) {
                            if (prefs.english()) "Wait $wait seconds" else "Tunggu $wait detik"
                        } else if (prefs.english()) "Wrong PIN. Try again" else "PIN salah. Coba lagi"
                        display.postDelayed({
                            if (entered.isEmpty() && !verifying) {
                                updateDisplay()
                                hint.text = if (prefs.english()) "Enter the 4-digit PIN with your remote"
                                    else getString(R.string.pin_hint)
                            }
                        }, 1500L)
                    }
                }, remaining)
            }
        }
    }

    fun dismissForFreeWindow() {
        accepted = true
        finish()
    }

    private var accepted = false

    override fun onResume() {
        super.onResume()
        active = true
    }

    override fun onPause() {
        active = false
        verificationGeneration++
        verifying = false
        if (!accepted) clear()
        super.onPause()
        if (!accepted) LockPollService.instance?.onGateDismissed()
    }

    override fun onDestroy() {
        pinWorker.shutdownNow()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // Launch-shortcut class of the RT-G2 remote.
    //   268  = the dedicated YouTube button. There is NO public named constant for
    //          it in android.view.KeyEvent (KEYCODE_YOUTUBE is not a real SDK symbol;
    //          it is the raw int the button emits).
    //   177  = the dedicated Netflix/SmartTube button = KeyEvent.KEYCODE_MENU
    //          (stock AOSP names 177 MENU; forensics 20.09: it relaunches the
    //          blocked app over the gate).
    // DPAD / navigation-DPAD keys are deliberately NOT consumed — the PIN UI still
    // runs on the D-pad, 3×DPAD_UP within 2 s summons the hidden keypad, and BACK
    // hides it without leaving the screen.
    private val LAUNCH_SHORTCUTS: Set<Int> = setOf(
        268,                       // RT-G2 YouTube button (raw keycode)
        KeyEvent.KEYCODE_MENU,     // 177 — RT-G2 Netflix/SmartTube button
    )

    private fun isShortcutLaunchKey(keyCode: Int): Boolean = LAUNCH_SHORTCUTS.contains(keyCode)
}
