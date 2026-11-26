package com.fermapp.api.persistence.entity;

import com.fermapp.api.domain.model.RunStatus;
import java.time.Instant;

/** What field work and irrigation have in common: a known duration that pauses stop. */
public interface TimedRun {

    Instant getStartedAt();

    double getDurationHours();

    long getPausedMs();

    Instant getPausedAt();

    RunStatus getStatus();

    double getProgressPct();

    Instant getCompletedAt();
}
