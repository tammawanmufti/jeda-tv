package com.sirkala.jedatv

/** Rules supplied by the parent; legacy YouTube rules are migrated into preferences. */
object LockPolicy {
    const val YOUTUBE = "com.google.android.youtube.tv"
    private val SYSTEM_SETTINGS = setOf(
        "com.android.tv.settings", "com.android.settings", "com.google.android.tv.settings")

    /** A trip to native TV Settings is still part of the current app visit. */
    fun keepsZeroGraceSession(unlockedPkg: String, foregroundPkg: String,
                              ownPkg: String, returningFromPin: Boolean): Boolean =
        foregroundPkg == unlockedPkg || foregroundPkg in SYSTEM_SETTINGS ||
            (foregroundPkg == ownPkg && returningFromPin)

    fun requiresPin(pkg: String, blocked: Set<String>, epochMillis: Long,
                    schedules: List<FamilySchedule>, paused: Boolean): Boolean =
        pkg in blocked && !paused && schedules.none { it.activeAt(epochMillis) }
}
