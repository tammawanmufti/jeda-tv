package com.sirkala.jedatv

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** Family rule in WIB: YouTube is free on weekends 06:00–21:00. */
object LockPolicy {
    const val YOUTUBE = "com.google.android.youtube.tv"
    private val zone = ZoneId.of("Asia/Jakarta")

    fun youtubeFreeAt(epochMillis: Long): Boolean {
        val local = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val weekend = local.dayOfWeek == DayOfWeek.SATURDAY || local.dayOfWeek == DayOfWeek.SUNDAY
        val minutes = local.hour * 60 + local.minute
        return weekend && minutes >= 6 * 60 && minutes < 21 * 60
    }

    fun requiresPin(pkg: String, blocked: Set<String>, epochMillis: Long): Boolean =
        pkg in blocked && (pkg != YOUTUBE || !youtubeFreeAt(epochMillis))
}
