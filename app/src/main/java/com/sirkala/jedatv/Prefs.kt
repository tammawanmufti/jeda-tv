package com.sirkala.jedatv

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class Prefs(private val context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("tvlock", Context.MODE_PRIVATE)

    fun hasPin(): Boolean = sp.contains("pin_hash")

    fun setPin(pin: String) {
        require(pin.length == 4 && pin.all(Char::isDigit))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt)
        sp.edit()
            .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pin_hash", Base64.encodeToString(hash, Base64.NO_WRAP))
            .remove("pin")
            .putInt("pin_fail_count", 0)
            .putLong("pin_locked_until", 0L)
            .remove("unlocked_pkg")
            .putLong("unlock_until", 0L)
            .apply()
    }

    /** Returns false during the cooldown as well as for an incorrect PIN. */
    fun checkPin(pin: String): Boolean {
        val now = System.currentTimeMillis()
        if (!hasPin() || now < sp.getLong("pin_locked_until", 0L)) return false
        val salt = Base64.decode(sp.getString("pin_salt", ""), Base64.NO_WRAP)
        val expected = Base64.decode(sp.getString("pin_hash", ""), Base64.NO_WRAP)
        val actual = derive(pin, salt)
        val ok = java.security.MessageDigest.isEqual(expected, actual)
        if (ok) {
            sp.edit().putInt("pin_fail_count", 0).putLong("pin_locked_until", 0L).apply()
        } else {
            val failures = sp.getInt("pin_fail_count", 0) + 1
            val cooldown = if (failures >= 5) now + 30_000L else 0L
            sp.edit().putInt("pin_fail_count", failures).putLong("pin_locked_until", cooldown).apply()
        }
        return ok
    }

    fun pinWaitSeconds(): Long =
        ((sp.getLong("pin_locked_until", 0L) - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 100_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    fun blockedList(): Set<String> =
        sp.getStringSet("blocked", emptySet())?.toSet() ?: emptySet()

    fun setBlocked(pkgs: Set<String>) = sp.edit().putStringSet("blocked", pkgs.toSet()).apply()

    /** Keep the parent's selections while locking newly installed TV apps by default. */
    fun addNewLaunchableApps() {
        val pm = context.packageManager
        val launchers = listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)
            .flatMap { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(it), 0) }
            .map { it.activityInfo.packageName }
            .toSet()
        val excluded = setOf(context.packageName, "com.gathin.tvhome", "com.google.android.tvlauncher",
            "com.mitv.tvhome.atv", "com.mitv.tvhome.mitvplus", "com.mediatek.tv.service")
        val known = sp.getStringSet("known_apps", emptySet())?.toSet() ?: emptySet()
        val newApps = launchers - known - excluded
        if (newApps.isNotEmpty() || !sp.contains("known_apps")) {
            sp.edit()
                .putStringSet("known_apps", launchers)
                .putStringSet("blocked", blockedList() + newApps)
                .apply()
        }
    }

    /** -1 = until reboot, 0 = ask every time (with a brief same-app launch grace). */
    fun graceMinutes(): Int = sp.getInt("grace", 0)
    fun setGraceMinutes(v: Int) = sp.edit().putInt("grace", v).apply()

    fun unlockUntil(): Long = sp.getLong("unlock_until", 0L)
    fun unlockedPackage(): String? = sp.getString("unlocked_pkg", null)
    fun isUnlocked(pkg: String, now: Long): Boolean =
        unlockedPackage() == pkg && now < unlockUntil()
    fun setUnlock(pkg: String, until: Long) = sp.edit()
        .putString("unlocked_pkg", pkg).putLong("unlock_until", until).apply()
    fun closeGrace() = sp.edit().remove("unlocked_pkg").putLong("unlock_until", 0L).apply()

    companion object {
        val GRACE_CYCLE = listOf(0, 5, 15, 30, 60, -1)
    }
}
