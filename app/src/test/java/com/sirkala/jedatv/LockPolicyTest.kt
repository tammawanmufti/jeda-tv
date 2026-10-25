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

    @Test fun youtubeOnlyFreeDuringWeekendWindow() {
        val blocked = setOf(LockPolicy.YOUTUBE, "org.smarttube.stable")
        assertTrue(LockPolicy.requiresPin(LockPolicy.YOUTUBE, blocked, at(23, 12, 0))) // Friday
        assertTrue(LockPolicy.requiresPin(LockPolicy.YOUTUBE, blocked, at(24, 5, 59)))
        assertFalse(LockPolicy.requiresPin(LockPolicy.YOUTUBE, blocked, at(24, 6, 0)))
        assertFalse(LockPolicy.requiresPin(LockPolicy.YOUTUBE, blocked, at(25, 20, 59)))
        assertTrue(LockPolicy.requiresPin(LockPolicy.YOUTUBE, blocked, at(25, 21, 0)))
        assertTrue(LockPolicy.requiresPin(LockPolicy.YOUTUBE, blocked, at(26, 6, 0))) // Monday
        assertTrue(LockPolicy.requiresPin("org.smarttube.stable", blocked, at(25, 12, 0)))
    }
}
