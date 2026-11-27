package com.fermapp.api.modules.work

import com.fermapp.api.common.util.Times
import com.fermapp.api.domain.model.RunStatus
import com.fermapp.api.domain.work.WorkMath
import com.fermapp.api.persistence.entity.TimedRun
import java.time.Instant

/**
 * Live progress of an operation with known duration (field work or irrigation).
 *
 * @param eta the ISO end estimate while the run is RUNNING, otherwise null
 */
@JvmRecord
data class RunProgress(val percent: Double, val elapsedSeconds: Long, val remainingSeconds: Long, val eta: String?, val finished: Boolean) {

    companion object {
        @JvmStatic
        fun of(run: TimedRun, now: Instant): RunProgress {
            val durationMs = run.durationHours * 3_600_000
            val progress = WorkMath.timedProgress(run.startedAt, durationMs, run.pausedMs.toDouble(), run.pausedAt, now)
            val active = run.status == RunStatus.RUNNING || run.status == RunStatus.PAUSED
            return RunProgress(
                if (active) progress.percent else run.progressPct,
                Math.round(progress.elapsedMs / 1000),
                if (active) Math.round(progress.remainingMs / 1000) else 0,
                if (run.status == RunStatus.RUNNING) Times.iso(progress.eta) else null,
                progress.finished,
            )
        }
    }
}
