package com.sirkala.jedatv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var graceBtn: Button
    private lateinit var keypad: KeypadHelper
    private var pinBuffer = ""
    private enum class PinMode { NONE, NEW, NEW_CONFIRM, ADMIN }
    private var pinMode = PinMode.NONE
    private var adminAuthenticated = false
    private var newPinFirst = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.status_text)
        graceBtn = findViewById(R.id.btn_grace)

        findViewById<Button>(R.id.btn_setup_pin).setOnClickListener { showPinEntry() }
        graceBtn.setOnClickListener { cycleGrace() }
        findViewById<Button>(R.id.btn_blocked).setOnClickListener {
            startActivity(Intent(this, BlockedAppsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_service).setOnClickListener {
            // request usage-stats access — the only permission the guardian needs
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.no_usage_settings, Toast.LENGTH_LONG).show()
            }
        }
        findViewById<TextView>(R.id.version_text).text =
            "jeda-tv  v " + (try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (_: Exception) { "?" })
        keypad = KeypadHelper(this, findViewById(R.id.main_root),
            onDigit = { kPinDigit(it) }, onBackspace = { kPinBack() }, onClear = { kPinClear() })
        Prefs(this).addNewLaunchableApps()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        if (hasUsageAccess()) {
            startServiceIfAllowed()
            LockPollService.instance?.reloadPrefs()
        }
        if (Prefs(this).hasPin() && !adminAuthenticated) showAdminPinEntry()
        else updateStatus()
        statusText.postDelayed({ if (pinMode == PinMode.NONE) updateStatus() }, 800)
    }

    override fun onStop() {
        adminAuthenticated = false
        pinMode = PinMode.NONE
        pinBuffer = ""
        newPinFirst = ""
        keypad.hide()
        super.onStop()
    }

    private fun setSettingsVisible(visible: Boolean) {
        for (id in listOf(R.id.btn_setup_pin, R.id.btn_grace, R.id.btn_blocked, R.id.btn_service)) {
            findViewById<Button>(id).visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun showAdminPinEntry() {
        pinMode = PinMode.ADMIN
        pinBuffer = ""
        setSettingsVisible(false)
        statusText.text = "Masukkan PIN orang tua untuk membuka pengaturan"
        keypad.show()
    }

    private fun hasUsageAccess(): Boolean {
        return try {
            val appops = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
            // checkOpNoThrow is API 19+; unsafeCheckOpNoThrow is API 29+ and threw an
            // uncaught NoSuchMethodError on Android 9 (issue #1) — catch Throwable too,
            // an Error is not an Exception
            val mode = appops.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName)
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) { false }
    }

    private fun startServiceIfAllowed() {
        try { startForegroundService(Intent(this, LockPollService::class.java)) }
        catch (_: Exception) {}
    }

    private fun cycleGrace() {
        val p = Prefs(this)
        val cur = p.graceMinutes()
        val idx = Prefs.GRACE_CYCLE.indexOf(cur).let { if (it < 0) 3 else it }
        val next = Prefs.GRACE_CYCLE[(idx + 1) % Prefs.GRACE_CYCLE.size]
        p.setGraceMinutes(next)
        LockPollService.instance?.reloadPrefs()
        Toast.makeText(this, getString(R.string.grace_set, graceLabel(next)), Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    private fun graceLabel(v: Int): String = when {
        v < 0 -> getString(R.string.grace_reboot)
        v == 0 -> getString(R.string.grace_always)
        else -> "$v menit"
    }

    private fun updateStatus() {
        val p = Prefs(this)
        val running = LockPollService.instance != null
        val until = p.unlockUntil()
        val now = System.currentTimeMillis()
        val lockLine = when {
            !p.hasPin() -> getString(R.string.lock_needs_pin)
            until <= now -> getString(R.string.lock_armed)
            until == Long.MAX_VALUE -> getString(R.string.lock_open_now)
            else -> getString(R.string.lock_open_fmt,
                ((until - now + 59_999) / 60_000).toInt())
        }
        statusText.text = getString(
            R.string.status_fmt,
            if (!p.hasPin()) "belum dibuat ⚠️" else "sudah dibuat ✅",
            p.blockedList().size.toString(),
            graceLabel(p.graceMinutes()),
            if (!hasUsageAccess()) "izin penggunaan belum ada ⚠️"
            else if (running) "aktif ✅" else "tidak aktif ⚠️",
            lockLine)
    }

    private fun showPinEntry() {
        if (Prefs(this).hasPin() && !adminAuthenticated) return
        pinBuffer = ""
        newPinFirst = ""
        pinMode = PinMode.NEW
        currentFocus?.clearFocus() // button must not eat the OK key
        statusText.text = getString(R.string.enter_new_pin)
        keypad.show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (pinMode != PinMode.NONE) {
            val digit = keyToDigit(keyCode)
            if (digit != null) { kPinDigit(digit); return true }
            if (keyCode == KeyEvent.KEYCODE_DEL) { kPinBack(); return true }
            if (keyCode == KeyEvent.KEYCODE_BACK && keypad.onBackPressed()) return true
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                if (!keypad.visible) { confirmPin(); return true }
            }
            keypad.onKeyDown(keyCode)
            return super.onKeyDown(keyCode, event)
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && keypad.onBackPressed()) return true
        keypad.onKeyDown(keyCode)
        return super.onKeyDown(keyCode, event)
    }

    private fun kPinDigit(d: String) {
        if (pinBuffer.length < 4) {
            pinBuffer += d
            statusText.text = getString(R.string.pin_typing_fmt, "•".repeat(pinBuffer.length))
            if (pinBuffer.length == 4) confirmPin()
        }
    }

    private fun kPinBack() {
        if (pinBuffer.isNotEmpty()) {
            pinBuffer = pinBuffer.dropLast(1)
            statusText.text = getString(R.string.pin_typing_fmt, "•".repeat(pinBuffer.length))
        }
    }

    private fun kPinClear() {
        pinBuffer = ""
        statusText.text = when (pinMode) {
            PinMode.ADMIN -> "Masukkan PIN orang tua"
            PinMode.NEW_CONFIRM -> "Ulangi PIN baru"
            else -> getString(R.string.enter_new_pin)
        }
    }

    private fun keyToDigit(k: Int): String? = when (k) {
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

    private fun confirmPin() {
        if (pinBuffer.length != 4) {
            Toast.makeText(this, R.string.pin_need_4, Toast.LENGTH_LONG).show()
            return
        }
        val p = Prefs(this)
        if (pinMode == PinMode.ADMIN) {
            if (p.checkPin(pinBuffer)) {
                adminAuthenticated = true
                pinMode = PinMode.NONE
                pinBuffer = ""
                keypad.hide()
                setSettingsVisible(true)
                updateStatus()
            } else {
                pinBuffer = ""
                val wait = p.pinWaitSeconds()
                statusText.text = if (wait > 0) "Tunggu $wait detik" else "PIN salah"
            }
        } else if (pinMode == PinMode.NEW) {
            newPinFirst = pinBuffer
            pinBuffer = ""
            pinMode = PinMode.NEW_CONFIRM
            statusText.text = "Ulangi PIN baru untuk konfirmasi"
        } else if (pinMode == PinMode.NEW_CONFIRM) {
            if (pinBuffer != newPinFirst) {
                pinBuffer = ""
                newPinFirst = ""
                pinMode = PinMode.NEW
                statusText.text = "PIN berbeda. Masukkan PIN baru lagi"
                return
            }
            p.setPin(pinBuffer)
            adminAuthenticated = true
            Toast.makeText(this, R.string.pin_saved, Toast.LENGTH_LONG).show()
            pinBuffer = ""
            newPinFirst = ""
            pinMode = PinMode.NONE
            keypad.hide()
            setSettingsVisible(true)
            updateStatus()
            LockPollService.instance?.reloadPrefs()
        }
    }
}
