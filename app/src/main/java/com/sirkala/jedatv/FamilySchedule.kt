package com.sirkala.jedatv

import java.time.Instant
import java.time.ZoneId

data class FamilySchedule(val days: Int, val start: Int, val end: Int) {
    init {
        require(days in 1..127 && start in 0..1439 && end in 1..1440 && start < end)
    }

    fun activeAt(epochMillis: Long, zone: ZoneId = ZoneId.of("Asia/Jakarta")): Boolean {
        val time = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val bit = 1 shl (time.dayOfWeek.value - 1)
        val minute = time.hour * 60 + time.minute
        return days and bit != 0 && minute in start until end
    }

    fun overlaps(other: FamilySchedule): Boolean =
        days and other.days != 0 && start < other.end && other.start < end

    companion object {
        val YOUTUBE_WEEKEND = FamilySchedule(0b1100000, 6 * 60, 21 * 60)
    }
}
