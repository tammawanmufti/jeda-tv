package com.sirkala.jedatv

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * Foreground service that polls UsageStats for the top app.
 * No accessibility service → launcher key handling stays untouched.
 *
 * Robustness rules:
 *  - the gate is raised ONLY on a real foreground CHANGE into a blocked app
 *  - a raised gate that did not actually appear within 2.5 s (silent BAL deny,
 *    race, crash) is re-armed immediately: a stuck gateUp must never kill the lock
 *  - grace state lives in prefs, so a process restart cannot widen it invisibly
 */
class LockPollService : Service() {

    private lateinit var handler: Handler
    private lateinit var usm: UsageStatsManager

    private var blocked = setOf<String>()
    private var lastTop: String? = null
    private var lastRestricted: Boolean? = null
    private var seeded = false
    private var gateUp = false
    private var gateRaisedAt = 0L
    // Cooldown start for the watchdog recovery re-raise, set when the gate was
    // confirmed visible. 0 = first ever raise always allowed.
    private var recoveryReadyAt = 0L
    private var targetPkg: String? = null
    private var pendingConsume: String? = null
    private var pendingUntil = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        FileLogger.init(this)
        FileLogger.log("service onCreate")
        handler = Handler(Looper.getMainLooper())
        usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        LockNotification.makeChannel(this)
        startForeground(1, LockNotification.build(this))
        reloadPrefs()
        handler.postDelayed(poller, POLL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scheduleKeepAlive()
        return START_STICKY
    }

    private val poller = object : Runnable {
        override fun run() {
            try { tick() } catch (e: Exception) {
                android.util.Log.e("TVLOCK", "tick error", e)
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val prefs = Prefs(this)
        // Never present a gate before a parent has created a private PIN.
        if (!prefs.hasPin()) return
        val gateTarget = targetPkg
        if (gateUp && gateTarget != null && !LockPolicy.requiresPin(
                gateTarget, blocked, now, prefs.schedules(gateTarget), prefs.isPaused(now))) {
            // A schedule window or parent pause began while the PIN screen was open.
            gateUp = false
            GateOverlay.hide()
            PinActivity.instance?.dismissForFreeWindow()
            targetPkg = null
            return
        }
        val from = if (seeded) now - 15_000 else now - 10 * 60_000L
        val events = usm.queryEvents(from, now)
        var evTop: String? = null
        var evTs = 0L
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if ((ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                 ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) && ev.timeStamp >= evTs) {
                evTs = ev.timeStamp
                evTop = ev.packageName
            }
        }
        val prevTop = lastTop
        val t = evTop ?: lastTop
        if (evTop != null || lastTop == null) lastTop = t
        val foregroundChanged = evTop != null && evTop != prevTop
        if (t == null) { seeded = true; return }
        if (prefs.graceMinutes() == 0) {
            val unlockedPkg = prefs.unlockedPackage()
            if (unlockedPkg != null) {
                if (!LockPolicy.keepsZeroGraceSession(unlockedPkg, t, packageName,
                        pendingConsume == unlockedPkg && now < pendingUntil)) {
                    prefs.closeGrace()
                } else if (prefs.isUnlocked(unlockedPkg, now) &&
                    (t == unlockedPkg || prevTop == unlockedPkg) &&
                    (t != prevTop || prefs.unlockUntil() - now < SETTINGS_RETURN_WINDOW_MS / 2)) {
                    // Keep a playing app's visit alive. On entering Settings,
                    // allow enough time to return without restarting playback.
                    prefs.setUnlock(unlockedPkg, now + SETTINGS_RETURN_WINDOW_MS)
                }
            }
        }
        val restricted = LockPolicy.requiresPin(t, blocked, now,
            prefs.schedules(t), prefs.isPaused(now))
        val locked = restricted && !prefs.isUnlocked(t, now)
        val scheduleJustClosed = t == prevTop && lastRestricted == false && restricted
        lastRestricted = restricted
        if (!seeded) {
            android.util.Log.i("TVLOCK", "seeded top=$t")
            seeded = true
        }

        // Hard cap: the blackout must never outlive gate logic under any
        // circumstance, even a bug — an unremovable black screen is a brick.
        // But the fuse may never drop the screen onto a BLOCKED app: there it
        // re-raises the gate instead (fresh 10 s budget each round).
        if (GateOverlay.isShown && now - GateOverlay.shownAt > OVERLAY_MAX_MS) {
            if (locked) {
                FileLogger.log("fuse: blocked top=$t under stale overlay — re-raising")
                gateUp = false
                raiseGate(t, now)
            } else {
                FileLogger.log("overlay HARD CAP 10s — forcing hide (gateUp=$gateUp top=$t)")
                GateOverlay.hide()
            }
        }

        // Release fuse for the onPause re-blackout: if the gate is down, an
        // overlay is up, and NEITHER the gate nor a blocked app is on top
        // (parent pressed HOME from the gate → launcher), nothing is being
        // guarded — let the screen live again right away.
        if (!gateUp && GateOverlay.isShown && t != packageName && !locked) {
            FileLogger.log("overlay release: top=$t, no gate, nothing blocked")
            GateOverlay.hide()
        }

        // watchdog: a gate we raised must actually be on screen
        if (gateUp) {
            if (t == packageName) {
                // gate is up — the blackout did its job, the PIN screen is
                // visible now; drop the overlay underneath it
                GateOverlay.hide()
                return
            }
            if (!locked) {
                // The parent left the PIN screen for Home or another free app.
                gateUp = false
                targetPkg = null
                GateOverlay.hide()
                return
            }
            if (now - gateRaisedAt > GATE_CONFIRM_MS) {
                // Gate vanished while a blocked app was on top: re-arm and give the
                // gate a recovery re-raise chance even without a new foreground
                // change (swap storm collapses inside one poll window, so
                // foregroundChanged never fires again). ONE re-raise, gated by a
                // cooldown — only here, and only because gateUp was true and the
                // gate is proven gone. Presence-gating is NOT used: if the blocked
                // app is merely staying foreground (never gated), nothing re-fires.
                android.util.Log.w("TVLOCK", "gate did not appear — re-arming")
                gateUp = false
                if (locked && now >= recoveryReadyAt) {
                    raiseGate(t, now)
                    return
                }
            } else {
                return
            }
        }

        if (t == packageName) return
        if (t == pendingConsume && now < pendingUntil) {
            pendingConsume = null
            return
        }
        // PRESENCE path: the blackout is up and a blocked app is underneath
        // while the gate is NOT (a spam-press shoved PinActivity aside and
        // onGateDismissed re-blacked instantly, so gateUp went false without a
        // new foreground-change). Waiting for another change is wrong — the app
        // just sits there. The overlay itself is the trigger: keep the gate up.
        // (A legit unlock closes the overlay in onUnlocked, so this can never
        // fight a parent who is inside the app during grace.)
        if (GateOverlay.isShown && locked) {
            if (now - gateRaisedAt > GATE_CONFIRM_MS) {
                FileLogger.log("presence: blocked $t under overlay, gate down — raising")
                raiseGate(t, now)
            }
            return
        }
        if (locked && (foregroundChanged || scheduleJustClosed)) {
            // evTs = the event's own timestamp; now-evTs = how late UsageStats
            // surfaced it to us. This is the detection gap the overlay has to
            // beat on a fast dedicated-button press.
            FileLogger.log("DETECT lag=${now - evTs}ms pkg=$t")
            raiseGate(t, now)
        }
    }

    private fun raiseGate(t: String, now: Long) {
        gateUp = true
        gateRaisedAt = now
        targetPkg = t
        // detection latency log: evTs->now tells us how late the UsageStats
        // event itself is; (now - gateRaisedAt) at the hide tells gate launch lag
        android.util.Log.i("TVLOCK", "gate raising for $t")
        try {
            // instant blackout FIRST: paints in <100 ms from the service and
            // swallows input, so the blocked app stops being visible/usable
            // regardless of how long the gate activity takes to launch below.
            GateOverlay.show(this, now)
            startActivity(Intent(this, PinActivity::class.java).apply {
                putExtra(PinActivity.EXTRA_TARGET_PACKAGE, t)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                         Intent.FLAG_ACTIVITY_CLEAR_TOP or
                         Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                         Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            // Cooldown window opens now (gate visible on the launch path / or when
            // the watchdog re-arms after seeing it on top, see tick()).
            recoveryReadyAt = now + RECOVERY_COOLDOWN_MS
        } catch (e: Exception) {
            // Fail CLOSED: keep gateUp true so the watchdog keeps re-raising
            // every GATE_CONFIRM_MS — the blackout (if shown) keeps the screen
            // dead meanwhile. Clearing gateUp here is what used to kill the
            // lock silently (pre-1.12 incident).
            android.util.Log.e("TVLOCK", "gate startActivity FAILED", e)
        }
    }

    fun reloadPrefs() {
        val p = Prefs(this)
        p.addNewLaunchableApps()
        val set = p.blockedList().toMutableSet()
        set.remove(packageName)
        blocked = set
        p.setBlocked(set)
        gateUp = false            // re-arm instantly on any config change
        GateOverlay.hide()        // parent touched settings: never trap a black screen
        seeded = false            // re-seed top app
        lastRestricted = null
    }

    /** Correct PIN entered: uncover the existing app task and preserve its state. */
    fun onUnlocked() {
        val p = Prefs(this)
        val grace = p.graceMinutes()
        val until = when {
            grace < 0 -> Long.MAX_VALUE
            grace == 0 -> System.currentTimeMillis() + SETTINGS_RETURN_WINDOW_MS
            else -> System.currentTimeMillis() + grace * 60_000L
        }
        val t = targetPkg
        if (t != null) p.setUnlock(t, until)
        gateUp = false
        GateOverlay.hide()
        targetPkg = null
        pendingConsume = t
        pendingUntil = System.currentTimeMillis() + 10_000
    }

    fun onGateDismissed() {
        gateUp = false
        // The gate was shoved aside (a dedicated-button relaunch lands on top
        // of it). onPause fires the same instant the shove happens — far ahead
        // of any poll detection — so re-blackout RIGHT NOW instead of exposing
        // the app for the 150-300 ms until the next DETECT. show() is a no-op
        // when the overlay is already up; the re-raise of the gate then hides
        // it again the moment the PIN is confirmed on top.
        GateOverlay.show(applicationContext, System.currentTimeMillis())
    }

    /** Self-heal if the ROM kills the FGS: re-arm every 5 min via AlarmManager. */
    private fun scheduleKeepAlive() {
        try {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getService(
                this, 99, Intent(this, LockPollService::class.java),
                PendingIntent.FLAG_IMMUTABLE)
            am.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 5 * 60_000L, 5 * 60_000L, pi)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val POLL_MS = 120L
        const val GATE_CONFIRM_MS = 2_500L
        // Cooldown window for the watchdog recovery re-raise, measured from the
        // last raise. The 2.5 s watchdog check paces it to >= 2.5 s anyway; this
        // is the explicit guard so a failed gate can't re-raise on every tick.
        const val RECOVERY_COOLDOWN_MS = 1_500L
        // Absolute ceiling for the blackout overlay regardless of gate state:
        // a stuck black screen is worse than a leaked frame. Well above
        // GATE_CONFIRM_MS + RECOVERY_COOLDOWN_MS so it never fires in normal
        // operation — it is the last-resort fuse.
        const val OVERLAY_MAX_MS = 10_000L
        const val SETTINGS_RETURN_WINDOW_MS = 30 * 60_000L
        var instance: LockPollService? = null
    }
}
