package com.sirkala.jedatv

/** Rules supplied by the parent; legacy YouTube rules are migrated into preferences. */
object LockPolicy {
    const val YOUTUBE = "com.google.android.youtube.tv"

    fun requiresPin(pkg: String, blocked: Set<String>, epochMillis: Long,
                    schedules: List<FamilySchedule>, paused: Boolean): Boolean =
        pkg in blocked && !paused && schedules.none { it.activeAt(epochMillis) }
}
