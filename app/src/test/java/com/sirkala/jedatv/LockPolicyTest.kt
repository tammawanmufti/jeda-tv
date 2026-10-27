package com.sirkala.jedatv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class LockPolicyTest {
    private fun at(day: Int, hour: Int, minute: Int) =
        LocalDateTime.of(2026, 10, day, hour, minute)
            .atZone(ZoneId.of("Asia/Jakarta")).toInstant().toEpochMilli()

    @Test fun youtubeUsesOnlyPersistedSchedules() {
        val blocked = setOf(LockPolicy.YOUTUBE, "org.smarttube.stable")
        val migrated = listOf(FamilySchedule.YOUTUBE_WEEKEND)
        assertTrue(LockPolicy.requiresPin(LockPolicy.YOUTUBE, blocked, at(24, 12, 0), emptyList(), false))
        assertTrue(LockPolicy.requiresPin(LockPolicy.YOUTUBE, blocked, at(24, 5, 59), migrated, false))
        assertFalse(LockPolicy.requiresPin(LockPolicy.YOUTUBE, blocked, at(24, 6, 0), migrated, false))
        assertFalse(LockPolicy.requiresPin(LockPolicy.YOUTUBE, blocked, at(25, 20, 59), migrated, false))
        assertTrue(LockPolicy.requiresPin(LockPolicy.YOUTUBE, blocked, at(25, 21, 0), migrated, false))
        assertTrue(LockPolicy.requiresPin("org.smarttube.stable", blocked, at(25, 12, 0), emptyList(), false))
    }

    @Test fun configurableScheduleRespectsBoundariesAndApp() {
        val spotify = "com.spotify.tv.android"
        val weekday = FamilySchedule(0b0011111, 16 * 60, 19 * 60)
        val blocked = setOf(spotify)
        assertTrue(LockPolicy.requiresPin(spotify, blocked, at(26, 15, 59), listOf(weekday), false))
        assertFalse(LockPolicy.requiresPin(spotify, blocked, at(26, 16, 0), listOf(weekday), false))
        assertFalse(LockPolicy.requiresPin(spotify, blocked, at(26, 18, 59), listOf(weekday), false))
        assertTrue(LockPolicy.requiresPin(spotify, blocked, at(26, 19, 0), listOf(weekday), false))
        assertTrue(LockPolicy.requiresPin(spotify, blocked, at(25, 17, 0), listOf(weekday), false))
    }

    @Test fun overlapRequiresSharedDayAndTime() {
        val weekdays = FamilySchedule(0b0011111, 16 * 60, 19 * 60)
        assertTrue(weekdays.overlaps(FamilySchedule(1, 17 * 60, 18 * 60)))
        assertFalse(weekdays.overlaps(FamilySchedule(1, 19 * 60, 20 * 60)))
        assertFalse(weekdays.overlaps(FamilySchedule(0b1100000, 17 * 60, 18 * 60)))
    }

    @Test fun pauseOverridesLockedAppAndSchedule() {
        assertFalse(LockPolicy.requiresPin("app", setOf("app"), at(26, 12, 0), emptyList(), true))
        assertTrue(LockPolicy.requiresPin("app", setOf("app"), at(26, 12, 0), emptyList(), false))
    }

}
