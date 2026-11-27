package com.fermapp.api.persistence.entity

import com.fermapp.api.domain.model.RunStatus
import java.time.Instant

/** What field work and irrigation have in common: a known duration that pauses stop. */
interface TimedRun {

    val startedAt: Instant?

    val durationHours: Double

    val pausedMs: Long

    val pausedAt: Instant?

    val status: RunStatus

    val progressPct: Double

    val completedAt: Instant?
}
