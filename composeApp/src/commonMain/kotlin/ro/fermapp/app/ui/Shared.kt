package ro.fermapp.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ro.fermapp.app.data.LiveProgress
import ro.fermapp.app.data.RunStatus
import ro.fermapp.app.data.TimedRun
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.domain.PolygonGeometry
import ro.fermapp.domain.TimedProgressInput
import ro.fermapp.domain.jsRound
import ro.fermapp.domain.parseJsDate
import ro.fermapp.domain.timedProgress
import ro.fermapp.domain.toJsIsoString
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.time.Clock
import kotlin.time.Instant

/** The current moment, ticking every [intervalMs] while [active]. */
@Composable
fun rememberNow(intervalMs: Long = 1000, active: Boolean = true): Instant {
    var now by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(intervalMs, active) {
        while (active) {
            delay(intervalMs)
            now = Clock.System.now()
        }
    }
    return now
}

/** Progress of an operation at the given moment, computed on the phone. */
fun runProgressAt(run: TimedRun, now: Instant): LiveProgress {
    if (run.status != RunStatus.RUNNING && run.status != RunStatus.PAUSED) return run.progress
    val running = run.status == RunStatus.RUNNING
    val progress = timedProgress(
        TimedProgressInput(
            startedAt = parseJsDate(run.startedAt),
            durationMs = (run.durationHours * 3_600_000).toLong(),
            pausedMs = run.pausedMs.toLong(),
            pausedAt = run.pausedAt?.let(::parseJsDate),
            now = now,
        ),
    )
    return LiveProgress(
        percent = progress.percent,
        elapsedSeconds = jsRound(progress.elapsedMs / 1000.0),
        remainingSeconds = jsRound(progress.remainingMs / 1000.0),
        eta = if (running) progress.eta.toJsIsoString() else null,
        finished = progress.finished,
    )
}

/** A run is active only while it is running or paused and has not reached 100%. */
fun isRunActive(run: TimedRun, now: Instant = Clock.System.now()) =
    (run.status == RunStatus.RUNNING || run.status == RunStatus.PAUSED) && !runProgressAt(run, now).finished

/** Local timer, so progress advances between server updates too. */
@Composable
fun liveProgress(run: TimedRun): LiveProgress {
    val now = rememberNow(1000, run.status == RunStatus.RUNNING)
    return runProgressAt(run, now)
}

/** The real outline of the parcel, used as a recognition mark throughout the app. */
@Composable
fun ParcelShape(geometry: PolygonGeometry, size: Dp = 44.dp, color: Color = FarmColors.wheat, filled: Boolean = true) {
    val ring = geometry.coordinates.firstOrNull().orEmpty()
    Canvas(Modifier.size(size)) {
        if (ring.isEmpty()) return@Canvas
        val minLat = ring.minOf { it.lat }
        val maxLat = ring.maxOf { it.lat }
        val minLng = ring.minOf { it.lng }
        val maxLng = ring.maxOf { it.lng }
        val stretch = cos(((minLat + maxLat) / 2) * PI / 180)
        val width = ((maxLng - minLng) * stretch).takeIf { it != 0.0 } ?: 1e-9
        val height = (maxLat - minLat).takeIf { it != 0.0 } ?: 1e-9
        val box = this.size.minDimension
        val scale = box * 0.88 / maxOf(width, height)
        val offsetX = (box - width * scale) / 2
        val offsetY = (box - height * scale) / 2
        val path = Path()
        ring.forEachIndexed { index, point ->
            val x = (offsetX + (point.lng - minLng) * stretch * scale).toFloat()
            val y = (offsetY + (maxLat - point.lat) * scale).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        if (filled) drawPath(path, color.copy(alpha = 0.22f), style = Fill)
        drawPath(path, color, style = Stroke(width = box * 0.05f, join = StrokeJoin.Round))
    }
}

enum class RunAction { PAUSE, RESUME, COMPLETE }

/** Active operation: timer, percent done and ETA, updated every second. */
@Composable
fun RunCard(
    run: TimedRun,
    title: String,
    subtitle: String,
    color: Color = FarmColors.wheat,
    canControl: Boolean = false,
    busy: Boolean = false,
    onAction: ((RunAction) -> Unit)? = null,
) {
    val progress = liveProgress(run)
    val paused = run.status == RunStatus.PAUSED
    Panel {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            Column(Modifier.weight(1f)) {
                Txt(title, TextVariant.Subtitle)
                Txt(subtitle, TextVariant.Small)
            }
            Txt("${floor(progress.percent).toInt()}%", TextVariant.Figure, color = color)
        }
        ProgressTrack(progress.percent, if (paused) FarmColors.straw else color)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Txt("Elapsed ${duration(progress.elapsedSeconds)}", TextVariant.Small)
            Txt(if (paused) "Paused" else progress.eta?.let { "Finishes at ${clock(it)}" } ?: "", TextVariant.Small)
        }
        if (canControl && onAction != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                OutlinedButton(onClick = { onAction(if (paused) RunAction.RESUME else RunAction.PAUSE) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(if (paused) "Resume" else "Pause")
                }
                Button(onClick = { onAction(RunAction.COMPLETE) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Complete") }
            }
        }
    }
}
