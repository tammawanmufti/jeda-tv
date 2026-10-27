package com.sirkala.jedatv

import android.app.Activity
import android.app.AppOpsManager
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private enum class PinMode { NONE, ADMIN, NEW, CONFIRM }
    private lateinit var root: FrameLayout
    private lateinit var keypad: KeypadHelper
    private var pinMode = PinMode.NONE
    private var pinBuffer = ""
    private var firstPin = ""
    private var pinMessage = ""
    private var pinTitle: TextView? = null
    private var pinDisplay: TextView? = null
    private var pinPending = false
    private var pinGeneration = 0
    private val pinWorker = Executors.newSingleThreadExecutor()
    private var authenticated = false
    private var retainForChild = false
    private var authUntil = 0L
    private var section = 0
    private val prefs by lazy { Prefs(this) }
    private fun tr(id: String, en: String) = if (prefs.english()) en else id

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs.addNewLaunchableApps()
        root = FrameLayout(this)
        setContentView(root)
        keypad = KeypadHelper(this, root, { digit(it) }, { backspace() }, { clearPin() })
        render()
    }

    override fun onResume() {
        super.onResume()
        retainForChild = false
        if (authenticated && SystemClock.elapsedRealtime() > authUntil) authenticated = false
        if (hasUsageAccess()) {
            try { startForegroundService(Intent(this, LockPollService::class.java)) } catch (_: Exception) {}
            LockPollService.instance?.reloadPrefs()
        }
        if (prefs.hasPin() && !authenticated) beginPin(PinMode.ADMIN) else render()
    }

    override fun onStop() {
        if (retainForChild) { super.onStop(); return }
        pinGeneration++
        pinPending = false
        authenticated = false
        authUntil = 0L
        pinMode = PinMode.NONE
        pinBuffer = ""
        firstPin = ""
        keypad.hide()
        super.onStop()
    }

    override fun onDestroy() {
        pinWorker.shutdownNow()
        super.onDestroy()
    }

    private fun render() {
        root.removeAllViews()
        if (pinMode != PinMode.NONE) {
            root.setBackgroundColor(0xff171d21.toInt())
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val title = TextView(this).apply {
                text = pinMessage
                textSize = 33f
                setTextColor(TvUi.LIGHT)
                gravity = Gravity.CENTER
            }
            pinTitle = title
            content.addView(title)
            val hint = TextView(this).apply {
                text = tr("Pilih angka dengan remote TV.", "Choose digits with your TV remote.")
                textSize = 16f
                setTextColor(TvUi.MUTED)
                gravity = Gravity.CENTER
            }
            content.addView(hint, LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = TvUi.dp(this@MainActivity, 10)
            })
            pinDisplay = TextView(this).apply {
                text = "○ ○ ○ ○"
                textSize = 35f
                setTextColor(TvUi.LIGHT)
                gravity = Gravity.CENTER
            }
            content.addView(pinDisplay, LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = TvUi.dp(this@MainActivity, 22)
            })
            val keypadHost = FrameLayout(this)
            content.addView(keypadHost, LinearLayout.LayoutParams(-1, TvUi.dp(this, 240)).apply {
                topMargin = TvUi.dp(this@MainActivity, 13)
            })
            root.addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            keypad.setContainer(keypadHost)
            return
        }
        val (page, left, right) = TvUi.screen(this, tr("Jeda", "Jeda"))
        root.addView(page)
        val names = listOf(tr("Ringkasan", "Summary"), tr("Aplikasi terkunci", "Locked apps"),
            tr("PIN orang tua", "Parent PIN"), tr("Jeda PIN", "PIN pause"),
            tr("Izin penggunaan", "Usage access"), tr("Bahasa", "Language"))
        var chosen: TextView? = null
        val menuButtons = mutableListOf<TextView>()
        names.forEachIndexed { index, name ->
            val item = TvUi.action(this, left, name, section == index, onFocus = {
                if (section != index) {
                    section = index
                    menuButtons.forEachIndexed { row, button -> TvUi.select(button, row == index) }
                    right.removeAllViews()
                    renderSection(right)
                    linkFocus(menuButtons, right, index)
                }
            }) {
                section = index
                if (index == 1) openBlockedApps()
                else if (index == 2) beginPin(PinMode.NEW)
                else render()
            }
            menuButtons.add(item)
            if (section == index) chosen = item
        }
        TvUi.gap(this, left, 24)
        TvUi.label(this, left, tr("↑↓ Pilih menu  ·  OK Buka", "↑↓ Choose menu  ·  OK Open"), 15, TvUi.MUTED)
        renderSection(right)
        linkFocus(menuButtons, right, section)
        chosen?.requestFocus()
    }

    private fun openBlockedApps() {
        retainForChild = true
        startActivity(Intent(this, BlockedAppsActivity::class.java))
    }

    private fun linkFocus(menuButtons: List<TextView>, right: LinearLayout, active: Int) {
        val first = (0 until right.childCount).mapNotNull { right.getChildAt(it) as? TextView }
            .firstOrNull { it.isFocusable }
        if (first != null) {
            menuButtons.forEach { it.nextFocusRightId = first.id }
            (0 until right.childCount).mapNotNull { right.getChildAt(it) as? TextView }
                .filter { it.isFocusable }
                .forEach { it.nextFocusLeftId = menuButtons.getOrNull(active)?.id ?: -1 }
        }
    }

    private fun renderSection(right: LinearLayout) {
        when (section) {
            0 -> summary(right)
            1 -> {
                heading(right, "Aplikasi terkunci", "Locked apps",
                    "Pilih aplikasi dan atur kunci serta jadwal bebas PIN.",
                    "Choose an app and manage its lock and PIN-free schedule.")
                TvUi.label(this, right, tr("${prefs.blockedList().size} aplikasi terkunci",
                    "${prefs.blockedList().size} locked apps"), 21, TvUi.LIGHT, 0, 25)
                TvUi.action(this, right, tr("Buka daftar aplikasi  ›", "Open app list  ›")) { openBlockedApps() }
            }
            2 -> {
                heading(right, "PIN orang tua", "Parent PIN",
                    "Ubah PIN 4 digit yang digunakan untuk membuka aplikasi dan pengaturan.",
                    "Change the 4-digit PIN used to open apps and settings.")
                TvUi.action(this, right, tr("Buat atau ubah PIN  ›", "Create or change PIN  ›")) {
                    beginPin(PinMode.NEW)
                }
            }
            3 -> pause(right)
            4 -> permission(right)
            5 -> language(right)
        }
    }

    private fun heading(right: LinearLayout, titleId: String, titleEn: String,
                        subtitleId: String, subtitleEn: String) {
        TvUi.label(this, right, tr(titleId, titleEn), 32, TvUi.LIGHT, 0, 6)
        TvUi.label(this, right, tr(subtitleId, subtitleEn), 17, TvUi.MUTED, 0, 25)
    }

    private fun summary(right: LinearLayout) {
        heading(right, "Ringkasan", "Summary", "Status perlindungan keluarga di TV ini.",
            "Family protection status on this TV.")
        val pause = prefs.pauseUntil()
        val now = System.currentTimeMillis()
        val status = when {
            !prefs.hasPin() -> tr("Buat PIN orang tua dahulu", "Create a parent PIN first")
            pause > now -> tr("Jeda PIN sedang aktif", "PIN pause is active")
            else -> tr("Perlindungan aktif", "Protection is active")
        }
        TvUi.label(this, right, tr("Status", "Status"), 23, TvUi.LIGHT, 8)
        TvUi.label(this, right, status, 18, TvUi.MUTED, 5, 24)
        TvUi.label(this, right, tr("Aplikasi terkunci", "Locked apps"), 23, TvUi.LIGHT)
        TvUi.label(this, right, prefs.blockedList().size.toString(), 18, TvUi.MUTED, 5, 24)
        TvUi.label(this, right, tr("Jeda PIN", "PIN pause"), 23, TvUi.LIGHT)
        TvUi.label(this, right, pauseStatus(), 18, TvUi.MUTED, 5, 24)
        TvUi.label(this, right, tr("Bahasa", "Language"), 23, TvUi.LIGHT)
        TvUi.label(this, right, when (prefs.language()) {
            "en" -> "English"; "id" -> "Bahasa Indonesia"; else -> tr("Ikuti bahasa TV", "Follow TV language")
        }, 18, TvUi.MUTED, 5)
    }

    private fun pauseStatus(): String {
        val until = prefs.pauseUntil()
        val now = System.currentTimeMillis()
        return when {
            until <= now -> tr("Tidak aktif", "Inactive")
            until == Long.MAX_VALUE -> tr("Sampai TV di-restart", "Until TV restarts")
            else -> tr("Aktif, ${((until - now + 59999) / 60000)} menit lagi",
                "Active, ${((until - now + 59999) / 60000)} min left")
        }
    }

    private fun pause(right: LinearLayout) {
        heading(right, "Jeda PIN", "PIN pause",
            "Akses sementara tanpa PIN untuk semua aplikasi terkunci. Jadwal per aplikasi kembali saat jeda berakhir.",
            "Temporary access without a PIN for all locked apps. App schedules resume when the pause ends.")
        TvUi.label(this, right, tr("Status sekarang: ", "Current status: ") + pauseStatus(), 16, TvUi.MUTED, 0, 12)
        val options = (if (prefs.isPaused(System.currentTimeMillis()))
            listOf(0 to tr("Akhiri jeda", "End pause")) else emptyList()) + listOf(30 to tr("30 detik", "30 seconds"),
            60 to tr("1 menit", "1 minute"), 180 to tr("3 menit", "3 minutes"),
            300 to tr("5 menit", "5 minutes"), 600 to tr("10 menit", "10 minutes"),
            900 to tr("15 menit", "15 minutes"), 1800 to tr("30 menit", "30 minutes"),
            3600 to tr("60 menit", "60 minutes"), -1 to tr("Sampai TV di-restart", "Until TV restarts"))
        options.forEach { (seconds, label) ->
            TvUi.action(this, right, label, compact = true) {
                prefs.setPauseUntil(when {
                    seconds < 0 -> Long.MAX_VALUE
                    seconds == 0 -> 0L
                    else -> System.currentTimeMillis() + seconds * 1000L
                })
                LockPollService.instance?.reloadPrefs()
                render()
            }
        }
    }

    private fun permission(right: LinearLayout) {
        heading(right, "Izin penggunaan", "Usage access",
            "Izin ini membantu Jeda mengenali aplikasi yang sedang dibuka.",
            "This access helps Jeda identify the open app.")
        TvUi.label(this, right, tr("Status izin", "Access status"), 23)
        TvUi.label(this, right, if (hasUsageAccess()) tr("Diberikan", "Granted")
            else tr("Belum diberikan", "Not granted"), 19, TvUi.MUTED, 8, 28)
        TvUi.label(this, right, tr("Penguncian aplikasi dapat berjalan selama izin ini aktif.",
            "App locking can run while this access is enabled."), 17, TvUi.MUTED, 0, 25)
        TvUi.action(this, right, tr("Periksa di pengaturan TV  ›", "Check in TV settings  ›")) {
            try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            catch (_: Exception) { Toast.makeText(this, R.string.no_usage_settings, Toast.LENGTH_LONG).show() }
        }
    }

    private fun language(right: LinearLayout) {
        heading(right, "Bahasa", "Language", "Pilih bahasa antarmuka Jeda.",
            "Choose the language for Jeda.")
        TvUi.label(this, right, tr("Bahasa saat ini: ", "Current language: ") +
            if (prefs.english()) "English" else "Bahasa Indonesia", 17, TvUi.MUTED, 0, 20)
        listOf("system" to tr("Ikuti bahasa TV", "Follow TV language"),
            "id" to "Bahasa Indonesia", "en" to "English").forEach { (code, label) ->
            TvUi.action(this, right, (if (prefs.language() == code) "✓  " else "   ") + label) {
                prefs.setLanguage(code)
                render()
            }
        }
        TvUi.label(this, right, tr("Perubahan diterapkan langsung dan disimpan untuk Jeda.",
            "Changes take effect immediately and are saved for Jeda."), 17, TvUi.MUTED, 25)
    }

    private fun hasUsageAccess(): Boolean = try {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) ==
            AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { false }

    private fun beginPin(mode: PinMode) {
        pinGeneration++
        pinPending = false
        pinMode = mode
        pinBuffer = ""
        firstPin = ""
        pinMessage = when (mode) {
            PinMode.ADMIN -> tr("Masukkan PIN orang tua", "Enter parent PIN")
            else -> tr("Buat PIN baru", "Create a new PIN")
        }
        render()
        keypad.show()
    }

    private fun updatePinDisplay() {
        pinDisplay?.text = "● ".repeat(pinBuffer.length) + "○ ".repeat(4 - pinBuffer.length)
    }

    private fun digit(value: String) {
        if (pinMode == PinMode.NONE || pinPending || pinBuffer.length >= 4) return
        pinBuffer += value
        updatePinDisplay()
        if (pinBuffer.length == 4) confirmPin()
    }

    private fun backspace() {
        if (pinPending) return
        if (pinBuffer.isNotEmpty()) { pinBuffer = pinBuffer.dropLast(1); updatePinDisplay() }
    }

    private fun clearPin() {
        if (pinPending) return
        pinBuffer = ""
        updatePinDisplay()
    }

    private fun confirmPin() {
        when (pinMode) {
            PinMode.ADMIN -> {
                val candidate = pinBuffer
                runPinWork(tr("Memeriksa PIN…", "Checking PIN…"), PinMode.ADMIN,
                    { prefs.checkPin(candidate) }) { correct ->
                    if (correct) {
                        authenticated = true; authUntil = SystemClock.elapsedRealtime() + 5 * 60_000L
                        pinMode = PinMode.NONE; keypad.hide(); render()
                    } else {
                        pinBuffer = ""
                        pinMessage = if (prefs.pinWaitSeconds() > 0)
                            tr("Tunggu ${prefs.pinWaitSeconds()} detik", "Wait ${prefs.pinWaitSeconds()} seconds")
                        else tr("PIN salah", "Wrong PIN")
                        render(); keypad.show()
                    }
                }
            }
            PinMode.NEW -> {
                firstPin = pinBuffer; pinBuffer = ""; pinMode = PinMode.CONFIRM
                pinMessage = tr("Ulangi PIN baru", "Repeat the new PIN")
                render(); keypad.show()
            }
            PinMode.CONFIRM -> {
                if (pinBuffer == firstPin) {
                    val candidate = pinBuffer
                    runPinWork(tr("Menyimpan PIN…", "Saving PIN…"), PinMode.CONFIRM,
                        { prefs.setPin(candidate); true }) { saved ->
                        if (saved) {
                            authenticated = true
                            authUntil = SystemClock.elapsedRealtime() + 5 * 60_000L
                            pinMode = PinMode.NONE
                            keypad.hide()
                            LockPollService.instance?.reloadPrefs()
                            render()
                            Toast.makeText(this, R.string.pin_saved, Toast.LENGTH_SHORT).show()
                        } else {
                            pinBuffer = ""
                            pinMessage = tr("Gagal menyimpan PIN. Coba lagi.", "Could not save PIN. Try again.")
                            render(); keypad.show()
                        }
                    }
                } else {
                    pinBuffer = ""; firstPin = ""; pinMode = PinMode.NEW
                    pinMessage = tr("PIN berbeda. Coba lagi.", "PINs differ. Try again.")
                    render(); keypad.show()
                }
            }
            PinMode.NONE -> Unit
        }
    }

    private fun runPinWork(message: String, mode: PinMode, work: () -> Boolean,
                           complete: (Boolean) -> Unit) {
        pinPending = true
        pinTitle?.text = message
        val generation = ++pinGeneration
        val started = SystemClock.uptimeMillis()
        pinWorker.execute {
            val result = runCatching(work).getOrDefault(false)
            runOnUiThread {
                val remaining = (80L - (SystemClock.uptimeMillis() - started)).coerceAtLeast(0L)
                root.postDelayed({
                    if (generation != pinGeneration || pinMode != mode || isFinishing || isDestroyed) return@postDelayed
                    pinPending = false
                    complete(result)
                }, remaining)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (pinMode != PinMode.NONE) {
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
            if (digit != null) { digit(digit); return true }
            if (keyCode == KeyEvent.KEYCODE_DEL) { backspace(); return true }
            if (keyCode == KeyEvent.KEYCODE_BACK && keypad.onBackPressed()) return true
        }
        keypad.onKeyDown(keyCode)
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Handled for TV remotes that dispatch Back through the activity callback")
    override fun onBackPressed() {
        if (keypad.onBackPressed()) return
        super.onBackPressed()
    }
}
