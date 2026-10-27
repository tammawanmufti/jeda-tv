package com.sirkala.jedatv

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

class BlockedAppsActivity : Activity() {
    private data class AppRow(val pkg: String, val label: String)
    private val prefs by lazy { Prefs(this) }
    private lateinit var apps: List<AppRow>
    private lateinit var ui: AppsLockCanvas
    private var selected = 0
    private var editing = false
    private var editIndex = -1
    private var draftDays = 0
    private var draftStart: Int? = null
    private var draftEnd: Int? = null
    private var editorPage = 0
    private var savedNotice = false
    private val appRows = mutableMapOf<Int, FrameLayout>()
    private val editRows = mutableMapOf<Int, FrameLayout>()
    private var lockRow: FrameLayout? = null
    private var firstScheduleAction: FrameLayout? = null
    private var editorFirst: FrameLayout? = null
    private var editorSelectedRow: FrameLayout? = null
    private var footer: TextView? = null
    private var focusTag = "app"
    private fun tr(id: String, en: String) = if (prefs.english()) en else id

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (packageName.endsWith(".preview") && intent.getBooleanExtra("design_preview", false)) {
            openDesignPreview()
            return
        }
        val pm = packageManager
        val seen = mutableSetOf<String>()
        val launchers = mutableListOf<ResolveInfo>()
        listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER).forEach { cat ->
            launchers += pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)
        }
        val excluded = setOf(packageName, "com.sirkala.jedatv", "com.mediatek.tv.service",
            "com.gathin.tvhome", "com.google.android.tvlauncher",
            "com.mitv.tvhome.atv", "com.mitv.tvhome.mitvplus")
        apps = launchers.filter { it.activityInfo.packageName !in excluded &&
                seen.add(it.activityInfo.packageName) }
            .map { ri ->
                val pkg = ri.activityInfo.packageName
                val label = if (pkg == "com.android.vending") "Google Play Store" else
                    runCatching { ri.loadLabel(pm).toString() }.getOrNull()
                        ?.takeUnless { it == pkg || it.isBlank() }
                    ?: runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                        .getOrNull()?.takeUnless { it == pkg || it.isBlank() }
                    ?: pkg.substringAfterLast('.').replace(Regex("([a-z])([A-Z])"), "$1 $2")
                AppRow(pkg, label)
            }.sortedBy { it.label.lowercase() }
        selected = apps.indexOfFirst { it.label.equals("Netflix", true) }.takeIf { it >= 0 } ?: 0
        render("app")
    }

    private fun openDesignPreview() {
        val labels = listOf("Google Play Store", "Live TV", "Media Player", "Miracast",
            "Netflix", "Red Bull TV", "Settings", "SmartTube", "Spotify", "SPOTV NOW",
            "TV manager", "YouTube")
        apps = labels.mapIndexed { index, label -> AppRow("preview.app$index", label) }
        prefs.setLanguage("id")
        val state = intent.getStringExtra("preview_state") ?: "saved"
        prefs.setBlocked(apps.map { it.pkg }.toSet() - if (state == "disabled")
            setOf(apps[8].pkg) else emptySet())
        val sampleRules = listOf(
            FamilySchedule(0b0011111, 16 * 60, 19 * 60),
            FamilySchedule(0b1100000, 8 * 60, 20 * 60),
            FamilySchedule(0b0000010, 20 * 60, 21 * 60))
        prefs.setSchedules(apps[8].pkg, if (state == "saved") sampleRules else sampleRules.take(2))
        selected = if (state == "empty") 4 else 8
        savedNotice = state == "saved"
        if (state == "editor" || state == "new" || state == "conflict") {
            editing = true
            editIndex = if (state == "editor") 0 else -1
            loadDraft(editIndex)
            if (state == "conflict") {
                draftDays = 0b0011111
                draftStart = 17 * 60
                draftEnd = 18 * 60
            }
            render(when (state) {
                "editor" -> "start"
                "conflict" -> "end"
                else -> "day:0"
            })
        } else render("app")
    }

    private fun render(focus: String = focusTag) {
        focusTag = focus
        ui = AppsLockCanvas(this)
        appRows.clear()
        editRows.clear()
        lockRow = null
        firstScheduleAction = null
        editorFirst = null
        editorSelectedRow = null
        setContentView(ui.root)
        if (apps.isEmpty()) {
            ui.text(98, 62, 510, 50, tr("Aplikasi", "Apps"), 43, AppsLockCanvas.WHITE)
            ui.text(98, 190, 500, 29, tr("Tidak ada aplikasi yang ditemukan.", "No apps found."),
                23, AppsLockCanvas.MUTED)
            return
        }
        if (editing) renderEditor(focus) else renderOverview(focus)
    }

    private fun renderOverview(focus: String) {
        val app = apps[selected]
        val rules = prefs.schedules(app.pkg)
        val locked = app.pkg in prefs.blockedList()
        val page = selected / 6
        val first = page * 6
        val last = minOf(apps.lastIndex, first + 5)
        val pitch = if (page == 0) 70 else 62
        val rowTop = if (page == 0) 179 else 174
        val rowHeight = if (page == 0) 62 else 58

        ui.text(98, 62, 510, 50, tr("Aplikasi", "Apps"), 43, AppsLockCanvas.WHITE)
        for (index in first..last) {
            val row = ui.box(74, rowTop + (index - first) * pitch, 536, rowHeight,
                Color.TRANSPARENT, 8)
            val marker = ui.box(0, 0, 5, rowHeight, AppsLockCanvas.HEADING, parent = row)
            val label = ui.text(36, 0, 460, rowHeight, apps[index].label, 23,
                AppsLockCanvas.WHITE, parent = row)
            fun paint(hasFocus: Boolean) {
                row.background = ui.shape(when {
                    hasFocus -> AppsLockCanvas.FOCUS
                    index == selected -> AppsLockCanvas.SELECTED
                    else -> Color.TRANSPARENT
                }, 8)
                marker.visibility = if (index == selected && !hasFocus) View.VISIBLE else View.INVISIBLE
                label.setTextColor(if (hasFocus) AppsLockCanvas.DARK else AppsLockCanvas.WHITE)
            }
            ui.focusable(row, click = {
                if (selected != index) {
                    selected = index
                    savedNotice = false
                    render("lock")
                } else lockRow?.requestFocus()
            }, focus = { hasFocus ->
                paint(hasFocus)
                if (hasFocus) {
                    focusTag = "app"
                    if (selected != index) {
                        selected = index
                        savedNotice = false
                        render("app")
                    } else updateOverviewFooter(false)
                }
            })
            paint(false)
            appRows[index] = row
        }
        val indicator = when {
            apps.size <= 6 -> tr("${first + 1}–${last + 1} dari ${apps.size}",
                "${first + 1}–${last + 1} of ${apps.size}")
            page == 0 -> tr("${first + 1}–${last + 1} dari ${apps.size}  ·  ↓ aplikasi lain",
                "${first + 1}–${last + 1} of ${apps.size}  ·  ↓ more apps")
            last == apps.lastIndex -> tr("${first + 1}–${last + 1} dari ${apps.size}  ·  ↑ aplikasi lain",
                "${first + 1}–${last + 1} of ${apps.size}  ·  ↑ more apps")
            else -> tr("${first + 1}–${last + 1} dari ${apps.size}  ·  ↑↓ aplikasi lain",
                "${first + 1}–${last + 1} of ${apps.size}  ·  ↑↓ more apps")
        }
        ui.text(98, 612, 500, 21, indicator, 18, AppsLockCanvas.MUTED)
        footer = ui.text(98, 659, 1080, 21, "", 18, AppsLockCanvas.MUTED)
        updateOverviewFooter(focus != "app")
        drawOverviewDetail(app, rules, locked)
        val selectedRow = appRows[selected]
        lockRow?.nextFocusLeftId = selectedRow?.id ?: -1
        firstScheduleAction?.nextFocusLeftId = selectedRow?.id ?: -1
        appRows.values.forEach { it.nextFocusRightId = lockRow?.id ?: -1 }
        lockRow?.nextFocusDownId = firstScheduleAction?.id ?: -1
        firstScheduleAction?.nextFocusUpId = lockRow?.id ?: -1
        when (focus) {
            "lock" -> lockRow?.requestFocus()
            "schedule" -> firstScheduleAction?.requestFocus()
            else -> selectedRow?.requestFocus()
        }
    }

    private fun updateOverviewFooter(rightFocused: Boolean) {
        footer?.text = if (rightFocused) tr(
            "← Daftar aplikasi  ·  ↑↓ Pilih pengaturan  ·  OK Ubah",
            "← App list  ·  ↑↓ Choose settings  ·  OK Change")
        else if (selected / 6 == 0) tr(
            "Gunakan tombol arah  ·  OK untuk memilih  ·  Back untuk kembali",
            "Use arrow keys  ·  OK to select  ·  Back to return")
        else tr("↑↓ Pilih aplikasi  ·  → Pengaturan  ·  Back Kembali",
            "↑↓ Choose app  ·  → Settings  ·  Back Return")
    }

    private fun drawOverviewDetail(app: AppRow, rules: List<FamilySchedule>, locked: Boolean) {
        val c = AppsLockCanvas
        ui.text(715, 71, 490, 46, app.label, 40, c.HEADING)
        val empty = rules.isEmpty()
        val disabledRules = !locked && !empty
        val compact = !empty
        if (savedNotice) {
            ui.text(715, 135, 480, 24, tr("✓  Jadwal tersimpan", "✓  Schedule saved"),
                20, 0xffd2dec8.toInt(), true)
        } else if (!compact || disabledRules) {
            ui.text(715, 135, 480, 22, tr("Aturan akses untuk aplikasi ini.",
                "Access rules for this app."), 19, c.MUTED)
        }
        val lockY = if (compact) 220 else 237
        val lock = ui.box(696, lockY - 19, 506, 92, Color.TRANSPARENT, 8)
        val lockLabel = ui.text(19, 18, 380, 29, tr("Kunci aplikasi", "App lock"),
            25, c.WHITE, parent = lock)
        val lockStatus = ui.text(19, 55, 390, 22,
            if (locked) tr("PIN diperlukan di luar jadwal", "PIN required outside schedule")
            else tr("Nonaktif · ${app.label} tidak meminta PIN", "Off · ${app.label} does not require a PIN"),
            18, c.MUTED, parent = lock)
        ui.box(425, 20, 58, 30,
            if (locked) 0xff526069.toInt() else c.TILE, 15, lock)
        ui.box(if (locked) 452 else 429, 23, 24, 24,
            if (locked) c.WHITE else c.MUTED, 12, lock)
        ui.focusable(lock, click = {
            val blocked = prefs.blockedList().toMutableSet()
            if (locked) blocked.remove(app.pkg) else blocked.add(app.pkg)
            blocked.remove(packageName)
            prefs.setBlocked(blocked)
            LockPollService.instance?.reloadPrefs()
            savedNotice = false
            render("lock")
        }, focus = { hasFocus ->
            lock.background = ui.shape(if (hasFocus) c.FOCUS else Color.TRANSPARENT, 8)
            lockLabel.setTextColor(if (hasFocus) c.DARK else c.WHITE)
            lockStatus.setTextColor(if (hasFocus) c.DARK else c.MUTED)
            if (hasFocus) { focusTag = "lock"; updateOverviewFooter(true) }
        })
        lockRow = lock

        val scheduleY = when {
            empty -> 366
            disabledRules -> 355
            savedNotice || rules.size >= 3 -> 316
            else -> 330
        }
        ui.text(715, scheduleY, 475, 29, tr("Jadwal bebas PIN", "PIN-free schedule"),
            25, c.WHITE)
        when {
            empty -> {
                ui.text(715, 404, 475, 22, tr("Belum ada jadwal. PIN selalu diminta.",
                    "No schedule yet. PIN is always required."), 19, c.MUTED)
                firstScheduleAction = detailAction(507,
                    tr("+  Tambah jadwal", "+  Add schedule")) { beginEdit(-1) }
                ui.text(715, 546, 475, 21,
                    tr("Setiap aplikasi memiliki jadwalnya sendiri.",
                        "Each app has its own schedule."), 18, c.MUTED)
            }
            disabledRules -> {
                ui.text(715, 393, 475, 22,
                    tr("Jadwal tersimpan, tetapi belum berlaku.", "Schedules saved but inactive."),
                    19, c.MUTED)
                rules.take(3).forEachIndexed { i, rule ->
                    ui.text(715, 452 + i * 41, 475, 27,
                        "${ruleName(rule)}  ·  ${timeRange(rule.start, rule.end)}", 21, c.MUTED)
                }
                ui.text(715, 568, 475, 24,
                    tr("Nyalakan kunci untuk memakai jadwal kembali.",
                        "Turn on the lock to use these schedules again."), 18, c.MUTED)
            }
            rules.size >= 3 || savedNotice -> {
                rules.take(3).forEachIndexed { i, rule ->
                    ui.text(715, 357 + i * 69, 475, 27, ruleName(rule), 22, c.WHITE)
                    ui.text(715, 386 + i * 69, 475, 22,
                        timeRange(rule.start, rule.end), 18, c.MUTED)
                }
                if (rules.size > 3) ui.text(715, 552, 475, 21,
                    tr("+ ${rules.size - 3} jadwal lain", "+ ${rules.size - 3} more schedules"),
                    17, c.MUTED)
                firstScheduleAction = detailAction(581,
                    tr("Kelola jadwal  ›", "Manage schedule  ›")) { beginEdit(0) }
            }
            else -> {
                rules.take(2).forEachIndexed { i, rule ->
                    ui.text(715, 374 + i * 84, 475, 27, ruleName(rule), 22, c.WHITE)
                    ui.text(715, 403 + i * 84, 475, 22,
                        timeRange(rule.start, rule.end), 18, c.MUTED)
                }
                val manageY = if (rules.size == 1) 506 else 555
                firstScheduleAction = detailAction(manageY,
                    tr("Kelola jadwal  ›", "Manage schedule  ›")) { beginEdit(0) }
                if (rules.size == 2) detailAction(597,
                    tr("+  Jadwal baru", "+  New schedule")) { beginEdit(-1) }
            }
        }
    }

    private fun detailAction(y: Int, title: String, click: () -> Unit): FrameLayout {
        val c = AppsLockCanvas
        val row = ui.box(696, y - 15, 506, 57, Color.TRANSPARENT, 8)
        val label = ui.text(19, 15, 475, 27, title, 23, c.HEADING, parent = row)
        ui.focusable(row, click = click, focus = { hasFocus ->
            row.background = ui.shape(if (hasFocus) c.FOCUS else Color.TRANSPARENT, 8)
            label.setTextColor(if (hasFocus) c.DARK else c.HEADING)
            if (hasFocus) { focusTag = "schedule"; updateOverviewFooter(true) }
        })
        row.nextFocusLeftId = appRows[selected]?.id ?: -1
        return row
    }

    private fun beginEdit(index: Int) {
        editing = true
        editIndex = index
        editorPage = if (index < 0) 0 else index / 3
        loadDraft(index)
        render(if (index < 0) "day:0" else "start")
    }

    private fun loadDraft(index: Int) {
        val rule = prefs.schedules(apps[selected].pkg).getOrNull(index)
        draftDays = rule?.days ?: 0
        draftStart = rule?.start
        draftEnd = rule?.end
    }

    private fun renderEditor(focus: String) {
        val c = AppsLockCanvas
        val app = apps[selected]
        val rules = prefs.schedules(app.pkg)
        val error = validationError()
        val title = tr("Jadwal ", "Schedule ") + app.label
        ui.text(98, 62, 510, 50, title, 43, c.WHITE)
        footer = ui.text(98, 659, 1080, 21,
            if (editIndex < 0 && error?.startsWith("CONFLICT:") == true)
                tr("↑↓←→ Pindah fokus  ·  OK Ubah waktu  ·  Back Batal",
                    "↑↓←→ Move focus  ·  OK Change time  ·  Back Cancel")
            else if (editIndex < 0) tr("↑↓←→ Pindah fokus  ·  OK Pilih  ·  Back Batal",
                "↑↓←→ Move focus  ·  OK Select  ·  Back Cancel")
            else tr("Gunakan tombol arah  ·  OK untuk memilih  ·  Back untuk kembali",
                "Use arrow keys  ·  OK to select  ·  Back to return"), 18, c.MUTED)
        val pageStart = editorPage * 3
        rules.withIndex().filter { it.index in pageStart until pageStart + 3 }
            .forEach { (index, rule) ->
            val top = 206 + (index - pageStart) * 100
            val row = ui.box(74, top, 536, 80, Color.TRANSPARENT, 8)
            val label = ui.text(37, 16, 470, 29, ruleName(rule), 25,
                if (index == editIndex) c.WHITE else c.MUTED, parent = row)
            val time = ui.text(37, 53, 430, 21,
                timeRange(rule.start, rule.end), 18, c.MUTED, parent = row)
            fun paint(hasFocus: Boolean) {
                row.background = ui.shape(if (hasFocus) c.FOCUS else Color.TRANSPARENT, 8)
                label.setTextColor(if (hasFocus) c.DARK
                    else if (index == editIndex) c.WHITE else c.MUTED)
                time.setTextColor(if (hasFocus) c.DARK else c.MUTED)
            }
            ui.focusable(row, click = {
                editIndex = index
                loadDraft(index)
                render("start")
            }, focus = { hasFocus -> paint(hasFocus); if (hasFocus) focusTag = "edit-list:$index" })
            paint(false)
            editRows[index] = row
        }
        if (rules.size > 3) {
            val pageEnd = minOf(rules.size, pageStart + 3)
            val arrows = when {
                editorPage == 0 -> "↓"
                pageEnd == rules.size -> "↑"
                else -> "↑↓"
            }
            ui.text(98, 612, 500, 21,
                tr("${pageStart + 1}–$pageEnd dari ${rules.size}  ·  $arrows jadwal lain",
                    "${pageStart + 1}–$pageEnd of ${rules.size}  ·  $arrows more schedules"),
                18, c.MUTED)
        }
        if (rules.size < 3) {
            val newTop = if (editIndex < 0) 420 else 413
            val add = ui.box(74, newTop, 536, 64, Color.TRANSPARENT, 8)
            val marker = ui.box(0, 0, 5, 64, c.HEADING, parent = add)
            val addLabel = ui.text(37, 17, 460, 27,
                tr("+  Jadwal baru", "+  New schedule"), 23,
                if (editIndex < 0) c.WHITE else c.MUTED, parent = add)
            fun paintAdd(hasFocus: Boolean) {
                add.background = ui.shape(when {
                    hasFocus -> c.FOCUS
                    editIndex < 0 -> c.SELECTED
                    else -> Color.TRANSPARENT
                }, 8)
                marker.visibility = if (editIndex < 0 && !hasFocus) View.VISIBLE else View.INVISIBLE
                addLabel.setTextColor(if (hasFocus) c.DARK
                    else if (editIndex < 0) c.WHITE else c.MUTED)
            }
            ui.focusable(add, click = {
                editIndex = -1
                editorPage = 0
                loadDraft(-1)
                render("day:0")
            }, focus = { hasFocus -> paintAdd(hasFocus); if (hasFocus) focusTag = "edit-list:-1" })
            paintAdd(false)
            editRows[-1] = add
        }
        editorSelectedRow = editRows[editIndex] ?: editRows[pageStart]

        val name = if (editIndex < 0) tr("Jadwal baru", "New schedule")
            else rules.getOrNull(editIndex)?.let(::ruleName) ?: tr("Jadwal baru", "New schedule")
        ui.text(715, 71, 490, 46, name, 40, c.HEADING)
        ui.text(715, 135, 480, 22,
            if (editIndex < 0 && error?.startsWith("CONFLICT:") == true)
                tr("Atur waktu bebas PIN untuk ${app.label}.",
                    "Set PIN-free times for ${app.label}.")
            else if (editIndex < 0) tr("Pilih hari dan waktu bebas PIN untuk ${app.label}.",
                "Choose PIN-free days and times for ${app.label}.")
            else tr("Atur waktu ketika ${app.label} dapat dibuka tanpa PIN.",
                "Set when ${app.label} can open without a PIN."), 19, c.MUTED)
        ui.text(715, 224, 475, 29, tr("Hari berlaku", "Active days"), 25, c.WHITE)
        val dayNames = if (prefs.english()) listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            else listOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")
        val days = mutableListOf<FrameLayout>()
        dayNames.forEachIndexed { index, day ->
            val chosen = draftDays and (1 shl index) != 0
            val tile = ui.box(715 + index * 68, 264, 60, 48,
                if (chosen) 0xff455159.toInt() else c.TILE, 5)
            val text = ui.text(if (chosen) 5 else 11, 0, 55, 48,
                (if (chosen) "✓ " else "") + day, if (chosen) 16 else 19,
                if (chosen) c.WHITE else c.MUTED, parent = tile)
            ui.focusable(tile, click = {
                draftDays = draftDays xor (1 shl index)
                render("day:$index")
            }, focus = { hasFocus ->
                tile.background = ui.shape(if (hasFocus) c.FOCUS
                    else if (draftDays and (1 shl index) != 0) 0xff455159.toInt() else c.TILE, 5)
                text.setTextColor(if (hasFocus) c.DARK
                    else if (draftDays and (1 shl index) != 0) c.WHITE else c.MUTED)
                if (hasFocus) focusTag = "day:$index"
            })
            days += tile
        }
        ui.text(715, 342, 475, 29, tr("Waktu bebas PIN", "PIN-free time"), 25, c.WHITE)
        val start = timeTile(702, 220, tr("Mulai", "Start"), draftStart?.let(::time) ?: "--.--", "start") {
            chooseTime(true)
        }
        val end = timeTile(948, 235, tr("Selesai", "End"), draftEnd?.let(::time) ?: "--.--", "end") {
            chooseTime(false)
        }
        if (error == null) {
            ui.text(715, 484, 480, 22,
                tr("Di luar jadwal, PIN orang tua tetap diperlukan.",
                    "The parent PIN is still required outside this schedule."), 19, c.MUTED)
        } else if (error.startsWith("CONFLICT:")) {
            ui.text(715, 484, 480, 22, error.removePrefix("CONFLICT:"), 19,
                0xfff0b7a8.toInt())
            ui.text(715, 520, 480, 22,
                tr("Ubah hari atau jam agar tidak saling tumpang tindih.",
                    "Change the days or times so they do not overlap."), 18, c.MUTED)
        } else {
            ui.text(715, 484, 480, 22,
                tr("Pilih setidaknya satu hari, lalu isi jam mulai dan selesai.",
                    "Choose at least one day, then set start and end times."), 19, c.MUTED)
        }
        val save = editorAction(if (error?.startsWith("CONFLICT:") == true) 578 else 556,
            if (error == null) tr("Simpan jadwal  ›", "Save schedule  ›")
            else tr("Simpan jadwal", "Save schedule"), error == null) { save() }
        val delete = if (editIndex >= 0) editorAction(596,
            tr("Hapus jadwal", "Delete schedule"), true, true) { deleteSchedule() } else null
        val leftTarget = editorSelectedRow?.id ?: -1
        editRows.values.forEach { it.nextFocusRightId = days.first().id }
        days.first().nextFocusLeftId = leftTarget
        days.forEachIndexed { index, tile ->
            if (index > 0) tile.nextFocusLeftId = days[index - 1].id
            if (index < days.lastIndex) tile.nextFocusRightId = days[index + 1].id
            tile.nextFocusDownId = if (index < 4) start.id else end.id
        }
        start.nextFocusLeftId = leftTarget
        start.nextFocusRightId = end.id
        end.nextFocusLeftId = start.id
        start.nextFocusUpId = days[1].id
        end.nextFocusUpId = days[5].id
        start.nextFocusDownId = save.id
        end.nextFocusDownId = save.id
        save.nextFocusUpId = start.id
        save.nextFocusLeftId = leftTarget
        delete?.nextFocusLeftId = leftTarget
        editorFirst = days.first()
        when {
            focus.startsWith("day:") -> days.getOrNull(focus.substringAfter(':').toIntOrNull() ?: 0)?.requestFocus()
            focus == "start" -> start.requestFocus()
            focus == "end" -> end.requestFocus()
            focus == "save" && save.isFocusable -> save.requestFocus()
            focus == "delete" -> delete?.requestFocus()
            focus.startsWith("edit-list:") -> editRows[focus.substringAfter(':').toIntOrNull()]?.requestFocus()
            else -> editorSelectedRow?.requestFocus()
        }
    }

    private fun timeTile(x: Int, width: Int, label: String, value: String,
                         tag: String, click: () -> Unit): FrameLayout {
        val c = AppsLockCanvas
        val tile = ui.box(x, 386, width, 70, c.TILE, 8)
        val heading = ui.text(19, 7, width - 38, 21, label, 18, c.MUTED, parent = tile)
        val time = ui.text(19, 34, width - 38, 31, value, 27,
            if (value == "--.--") c.MUTED else c.WHITE, parent = tile)
        ui.focusable(tile, click = click, focus = { hasFocus ->
            tile.background = ui.shape(if (hasFocus) c.FOCUS else c.TILE, 8)
            heading.setTextColor(if (hasFocus) c.DARK else c.MUTED)
            time.setTextColor(if (hasFocus) c.DARK
                else if (value == "--.--") c.MUTED else c.WHITE)
            if (hasFocus) focusTag = tag
        })
        return tile
    }

    private fun editorAction(y: Int, value: String, enabled: Boolean, muted: Boolean = false,
                             click: () -> Unit): FrameLayout {
        val c = AppsLockCanvas
        val row = ui.box(696, y - 15, 506, 55, Color.TRANSPARENT, 8)
        val label = ui.text(19, 15, 475, 27, value, 23,
            if (enabled) { if (muted) c.MUTED else c.HEADING } else c.INACTIVE, parent = row)
        if (enabled) ui.focusable(row, click = click, focus = { hasFocus ->
            row.background = ui.shape(if (hasFocus) c.FOCUS else Color.TRANSPARENT, 8)
            label.setTextColor(if (hasFocus) c.DARK else if (muted) c.MUTED else c.HEADING)
            if (hasFocus) focusTag = if (value.contains(tr("Hapus", "Delete"))) "delete" else "save"
        })
        return row
    }

    private fun chooseTime(start: Boolean) {
        val current = (if (start) draftStart else draftEnd) ?: if (start) 16 * 60 else 19 * 60
        TimePickerDialog(this, R.style.JedaTimePicker, { _, hour, minute ->
            if (start) draftStart = hour * 60 + minute else draftEnd = hour * 60 + minute
            render(if (start) "start" else "end")
        }, current / 60, current % 60, true).show()
    }

    private fun validationError(): String? {
        val start = draftStart
        val end = draftEnd
        if (draftDays == 0 || start == null || end == null || start >= end) return "INCOMPLETE"
        val candidate = FamilySchedule(draftDays, start, end)
        val conflict = prefs.schedules(apps[selected].pkg).withIndex().firstOrNull { (index, rule) ->
            index != editIndex && candidate.overlaps(rule)
        }
        return conflict?.let { "CONFLICT:" + tr("Jadwal ini bentrok dengan ", "This overlaps ") +
            ruleName(it.value) + ", " + timeRange(it.value.start, it.value.end).replace(" – ", "–") + "." }
    }

    private fun save() {
        if (validationError() != null) { render("save"); return }
        val rule = FamilySchedule(draftDays, draftStart!!, draftEnd!!)
        val rules = prefs.schedules(apps[selected].pkg).toMutableList()
        if (editIndex < 0 && rules.size >= 3) return
        if (editIndex >= 0) rules[editIndex] = rule else rules.add(rule)
        prefs.setSchedules(apps[selected].pkg, rules)
        LockPollService.instance?.reloadPrefs()
        editing = false
        savedNotice = true
        render("app")
        Toast.makeText(this, tr("Jadwal tersimpan", "Schedule saved"), Toast.LENGTH_SHORT).show()
    }

    private fun deleteSchedule() {
        if (editIndex < 0) return
        val rules = prefs.schedules(apps[selected].pkg).toMutableList()
        if (editIndex >= rules.size) return
        rules.removeAt(editIndex)
        prefs.setSchedules(apps[selected].pkg, rules)
        LockPollService.instance?.reloadPrefs()
        editing = false
        savedNotice = false
        render("app")
    }

    private fun ruleName(rule: FamilySchedule): String {
        if (rule.days == 0b0011111) return tr("Senin–Jumat", "Monday–Friday")
        if (rule.days == 0b1100000) return tr("Sabtu–Minggu", "Saturday–Sunday")
        if (Integer.bitCount(rule.days) == 1) {
            val names = if (prefs.english()) listOf("Monday", "Tuesday", "Wednesday", "Thursday",
                "Friday", "Saturday", "Sunday")
            else listOf("Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu", "Minggu")
            return names[Integer.numberOfTrailingZeros(rule.days)]
        }
        val names = if (prefs.english()) listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            else listOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")
        return names.filterIndexed { index, _ -> rule.days and (1 shl index) != 0 }.joinToString(", ")
    }

    private fun time(minute: Int): String = "%02d.%02d".format(minute / 60, minute % 60)
    private fun timeRange(start: Int, end: Int) = "${time(start)} – ${time(end)}"

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && editing) {
            editing = false
            savedNotice = false
            render("app")
            return true
        }
        if (editing && (keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                    keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            val focusedIndex = editRows.entries.firstOrNull { it.value.hasFocus() }?.key
            if (focusedIndex != null && focusedIndex >= 0) {
                val next = focusedIndex + if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                if (next in prefs.schedules(apps[selected].pkg).indices && next / 3 != editorPage) {
                    editorPage = next / 3
                    render("edit-list:$next")
                    return true
                }
            }
        }
        if (!editing && (keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                    keyCode == KeyEvent.KEYCODE_DPAD_DOWN) && appRows[selected]?.hasFocus() == true) {
            val next = selected + if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
            if (next in apps.indices) {
                selected = next
                savedNotice = false
                render("app")
            }
            return true
        }
        if (!editing && keyCode == KeyEvent.KEYCODE_DPAD_LEFT &&
            (lockRow?.hasFocus() == true || firstScheduleAction?.hasFocus() == true)) {
            appRows[selected]?.requestFocus()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Handled for TV remotes that dispatch Back through the activity callback")
    override fun onBackPressed() {
        if (editing) {
            editing = false
            savedNotice = false
            render("app")
        } else super.onBackPressed()
    }
}
