package com.fermapp.api.modules.work;

import com.fermapp.api.common.util.Times;
import com.fermapp.api.domain.model.RunStatus;
import com.fermapp.api.domain.work.WorkMath;
import com.fermapp.api.domain.work.WorkMath.TimedProgress;
import com.fermapp.api.persistence.entity.TimedRun;
import java.time.Instant;

/**
 * Live progress of an operation with known duration (field work or irrigation).
 *
 * @param eta the ISO end estimate while the run is RUNNING, otherwise null
 */
public record RunProgress(double percent, long elapsedSeconds, long remainingSeconds, String eta, boolean finished) {

    public static RunProgress of(TimedRun run, Instant now) {
        double durationMs = run.getDurationHours() * 3_600_000;
        TimedProgress progress = WorkMath.timedProgress(run.getStartedAt(), durationMs, run.getPausedMs(), run.getPausedAt(), now);
        boolean active = run.getStatus() == RunStatus.RUNNING || run.getStatus() == RunStatus.PAUSED;
        return new RunProgress(
                active ? progress.percent() : run.getProgressPct(),
                Math.round(progress.elapsedMs() / 1000),
                active ? Math.round(progress.remainingMs() / 1000) : 0,
                run.getStatus() == RunStatus.RUNNING ? Times.iso(progress.eta()) : null,
                progress.finished());
    }
}
