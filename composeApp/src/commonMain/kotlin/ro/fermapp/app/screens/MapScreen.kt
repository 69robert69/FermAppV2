package ro.fermapp.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Pentagon
import androidx.compose.material.icons.outlined.SensorsOff
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import ro.fermapp.app.Navigator
import ro.fermapp.app.Routes
import ro.fermapp.app.data.Api
import ro.fermapp.app.data.AppJson
import ro.fermapp.app.data.Parcel
import ro.fermapp.app.data.Queries
import ro.fermapp.app.data.RunStatus
import ro.fermapp.app.data.Toast
import ro.fermapp.app.data.WorkSession
import ro.fermapp.app.data.canManage
import ro.fermapp.app.data.errorMessage
import ro.fermapp.app.data.rememberAuth
import ro.fermapp.app.data.rememberFieldStatuses
import ro.fermapp.app.data.rememberMapOverview
import ro.fermapp.app.data.rememberParcels
import ro.fermapp.app.features.IMPLEMENT_COLOR
import ro.fermapp.app.features.PanelActions
import ro.fermapp.app.features.ParcelIntelligencePanel
import ro.fermapp.app.features.WeatherWidget
import ro.fermapp.app.features.colour
import ro.fermapp.app.platform.FieldMap
import ro.fermapp.app.platform.MapCamera
import ro.fermapp.app.platform.MapLine
import ro.fermapp.app.platform.MapMarker
import ro.fermapp.app.platform.MapPolygon
import ro.fermapp.app.platform.PickedFile
import ro.fermapp.app.platform.currentLocation
import ro.fermapp.app.platform.rememberFilePicker
import ro.fermapp.app.platform.shareFile
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.ui.IconAction
import ro.fermapp.app.ui.OfflineBanner
import ro.fermapp.app.ui.Panel
import ro.fermapp.app.ui.ProgressTrack
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import ro.fermapp.app.ui.WORK_TYPES
import ro.fermapp.app.ui.hoursMinutes
import ro.fermapp.app.ui.isRunActive
import ro.fermapp.app.ui.rememberNow
import ro.fermapp.app.ui.runProgressAt
import ro.fermapp.domain.FieldPathOptions
import ro.fermapp.domain.Position
import ro.fermapp.domain.buildFieldPath
import ro.fermapp.domain.buildFieldPlan
import ro.fermapp.domain.fieldCoverageAt
import ro.fermapp.domain.fieldPositionAt
import ro.fermapp.domain.pointInRing
import ro.fermapp.domain.positionOnPath
import kotlin.math.floor

private val ROMANIA = MapCamera(Position(25.0, 45.9), 6f)
private val FORMATS = linkedMapOf("geojson" to "GeoJSON", "kml" to "KML", "kmz" to "KMZ", "shp" to "Shapefile", "lpis" to "APIA LPIS")
private const val DEFAULT_WIDTH_M = 6.0
/** Agricultural green for worked ground. */
private val WORKED_GREEN = Color(0xFF58A23A)
/** From this width (tablets, desktop windows) the weather widget starts open. */
private const val WIDE_LAYOUT_MIN_WIDTH = 768

private fun detectFormat(name: String): String {
    val lower = name.lowercase()
    return when {
        lower.endsWith(".kmz") -> "kmz"
        lower.endsWith(".kml") -> "kml"
        lower.endsWith(".zip") -> "shp"
        else -> "geojson"
    }
}

private fun Color.argb(alpha: Float = 1f): Long = copy(alpha = alpha).toArgb().toLong() and 0xFFFFFFFFL

@Serializable
private data class ImportProblem(val name: String, val reason: String)

@Serializable
private data class ImportPreview(val parcels: List<kotlinx.serialization.json.JsonElement>, val problems: List<ImportProblem> = emptyList())

private data class PendingImport(val file: PickedFile, val format: String, val valid: Int, val skipped: Int)

/** The machine being followed on the map. */
private data class Tracked(val work: WorkSession, val label: String)

@Composable
fun MapScreen(navigator: Navigator) {
    val auth = rememberAuth()
    val parcels = rememberParcels()
    val overview = rememberMapOverview()
    val statuses = rememberFieldStatuses()
    val list = parcels.data.orEmpty()
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    var tracked by remember { mutableStateOf<Tracked?>(null) }
    var fitTo by remember { mutableStateOf<List<Position>?>(null) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    val infoByParcel = overview.data.orEmpty().associateBy { it.parcelId }
    val hasLiveRuns = overview.data.orEmpty().any { it.work.isNotEmpty() || it.irrigation.isNotEmpty() }
    // Machines move along their planned passes; one frame a second keeps the map light.
    val now = rememberNow(1000, hasLiveRuns)
    val activeMachines = overview.data.orEmpty().sumOf { item -> item.work.count { isRunActive(it, now) } }

    // The first time the parcels arrive, the map frames all of them.
    val allPoints = remember(list) { list.flatMap { it.geometry.coordinates.firstOrNull().orEmpty() } }
    if (fitTo == null && allPoints.isNotEmpty()) fitTo = allPoints

    val plans = remember(list, overview.data) {
        overview.data.orEmpty().flatMap { info ->
            val parcel = list.firstOrNull { it.id == info.parcelId } ?: return@flatMap emptyList()
            info.work.map { work -> work.id to buildFieldPlan(parcel.geometry.coordinates.firstOrNull().orEmpty(), work.workingWidthM ?: DEFAULT_WIDTH_M) }
        }.toMap()
    }

    fun parcelAt(point: Position): Parcel? = list.filter { parcel ->
        val rings = parcel.geometry.coordinates
        val outer = rings.firstOrNull().orEmpty()
        pointInRing(point, outer) && rings.drop(1).none { pointInRing(point, it) }
    }.minByOrNull { it.areaHa }

    fun openParcel(parcel: Parcel) {
        selected = parcel.id
        fitTo = parcel.geometry.coordinates.firstOrNull()
    }

    val polygons = list.map { parcel ->
        val base = statuses[parcel.id]?.state?.colour ?: FarmColors.wheat
        val active = parcel.id == selected
        MapPolygon(parcel.id, parcel.geometry.coordinates, base.argb(if (active) 0.69f else 0.33f), (if (active) FarmColors.chaff else base).argb(), parcel.name)
    }.toMutableList()
    val markers = mutableListOf<MapMarker>()
    val lines = mutableListOf<MapLine>()

    // Field work: worked ground, the rows behind the machine, and the machine itself.
    overview.data.orEmpty().forEach { info ->
        val parcel = list.firstOrNull { it.id == info.parcelId } ?: return@forEach
        info.work.filter { isRunActive(it, now) }.forEach { work ->
            val plan = plans[work.id] ?: return@forEach
            val percent = runProgressAt(work, now).percent
            val coverage = fieldCoverageAt(plan, percent)
            coverage.worked.forEachIndexed { i, ring -> polygons.add(MapPolygon("worked-${work.id}-$i", listOf(ring), WORKED_GREEN.argb(0.55f), WORKED_GREEN.argb(0.9f))) }
            if (coverage.trail.size >= 2) lines.add(MapLine("trail-${work.id}", coverage.trail, FarmColors.chaff.argb(0.8f), 4f))
            val gps = work.lastLocation?.let { Position(it.lng, it.lat) }?.takeIf { pointInRing(it, parcel.geometry.coordinates.firstOrNull().orEmpty()) }
            val position = fieldPositionAt(plan, percent)
            markers.add(
                MapMarker(
                    "machine-${work.id}",
                    gps ?: Position(position.lng, position.lat),
                    "${work.machine?.name ?: "Machine"}, ${WORK_TYPES[work.type] ?: work.type} ${floor(percent).toInt()}%",
                    (IMPLEMENT_COLOR[work.type] ?: FarmColors.wheat).argb(),
                    position.headingDeg.toFloat(),
                ),
            )
        }
        info.irrigation.filter { it.status == RunStatus.RUNNING || it.status == RunStatus.PAUSED }.forEach { run ->
            val path = buildFieldPath(parcel.geometry.coordinates.firstOrNull().orEmpty(), FieldPathOptions(passes = 1))
            val progress = runProgressAt(run, now)
            val point = positionOnPath(path, progress.percent)
            markers.add(
                MapMarker(
                    "irrigation-${run.id}",
                    point?.let { Position(it.lng, it.lat) } ?: Position(parcel.centerLng, parcel.centerLat),
                    "${run.label ?: "Irrigation"}: ${floor(progress.percent).toInt()}%, ${hoursMinutes(progress.remainingSeconds)} left",
                    FarmColors.water.argb(),
                ),
            )
        }
    }

    val importPicker = rememberFilePicker(listOf("*/*")) { file ->
        val format = detectFormat(file.name)
        scope.launch {
            try {
                val preview = AppJson.decodeFromJsonElement(
                    ImportPreview.serializer(),
                    Api.upload("/parcels/import?format=$format&dryRun=true", file.name, file.bytes, file.mimeType),
                )
                pendingImport = PendingImport(file, format, preview.parcels.size, preview.problems.size)
            } catch (error: Throwable) {
                Toast.show("The import failed: ${errorMessage(error)}")
            }
        }
    }

    fun exportFile(format: String) {
        menuOpen = false
        scope.launch {
            try {
                val extension = if (format == "shp" || format == "lpis") "$format.zip" else format
                shareFile(Api.download("/parcels/export?format=$format", "parcels.$extension"), "application/octet-stream")
            } catch (error: Throwable) {
                Toast.show("The export failed: ${errorMessage(error)}")
            }
        }
    }

    pendingImport?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Import parcels") },
            text = { Text("The file contains ${pending.valid} valid parcels." + if (pending.skipped > 0) "\n${pending.skipped} elements will be skipped." else "") },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    scope.launch {
                        try {
                            Api.upload("/parcels/import?format=${pending.format}", pending.file.name, pending.file.bytes, pending.file.mimeType)
                            Queries.invalidate("parcels", "dashboard", "map-overview")
                        } catch (error: Throwable) {
                            Toast.show("The import failed: ${errorMessage(error)}")
                        }
                    }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(FarmColors.loam)) {
        val wide = maxWidth.value >= WIDE_LAYOUT_MIN_WIDTH
        FieldMap(
            modifier = Modifier.fillMaxSize(),
            camera = ROMANIA,
            polygons = polygons,
            markers = markers,
            lines = lines,
            onTap = { point ->
                val parcel = parcelAt(point)
                if (parcel != null) openParcel(parcel) else selected = null
            },
            onPolygonTap = { id -> list.firstOrNull { it.id == id }?.let(::openParcel) },
            fitTo = fitTo,
        )

        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            OfflineBanner()
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f).padding(start = Space.md, top = Space.md)) {
                    // Hidden while a parcel panel is open: the parcel is framed above it.
                    if (selected == null) WeatherWidget(wide)
                }
                Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Tool { IconAction(Icons.Outlined.MyLocation, "Go to my location", { scope.launch { currentLocation()?.let { fitTo = listOf(it) } ?: Toast.show("Location unavailable. Allow location access in the phone settings.") } }) }
                    Box {
                        Tool(active = live) {
                            IconAction(
                                if (live) Icons.Outlined.SensorsOff else Icons.Outlined.Sensors,
                                if (live) "Turn off Live Operations" else "Turn on Live Operations",
                                {
                                    live = !live
                                    if (live) {
                                        // Live Operations frames every parcel with a running machine.
                                        val working = overview.data.orEmpty().filter { it.work.isNotEmpty() }.map { it.parcelId }.toSet()
                                        list.filter { it.id in working }.flatMap { it.geometry.coordinates.firstOrNull().orEmpty() }.takeIf { it.isNotEmpty() }?.let { fitTo = it }
                                    }
                                },
                                tint = if (live) FarmColors.wheatInk else FarmColors.chaff,
                            )
                        }
                        if (activeMachines > 0) {
                            Box(
                                Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(FarmColors.leaf),
                                contentAlignment = Alignment.Center,
                            ) { Txt(activeMachines.toString(), TextVariant.Small, color = FarmColors.loam) }
                        }
                    }
                    Box {
                        Tool { IconAction(Icons.Outlined.SwapVert, "Import or export parcels", { menuOpen = true }) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (canManage(auth.role)) DropdownMenuItem(text = { Text("Import a file") }, onClick = { menuOpen = false; importPicker() })
                            FORMATS.forEach { (format, label) -> DropdownMenuItem(text = { Text("Export $label") }, onClick = { exportFile(format) }) }
                        }
                    }
                }
            }
        }

        val follow = tracked
        when {
            follow != null -> Panel(Modifier.align(Alignment.BottomCenter).padding(Space.md)) {
                Txt(follow.label, TextVariant.Subtitle)
                val position = follow.work.lastLocation
                Txt(
                    if (position != null) "Updated at ${ro.fermapp.app.ui.clock(position.recordedAt)}${position.speedKmh?.let { ", ${ro.fermapp.domain.jsNum(ro.fermapp.domain.jsRound(it))} km/h" } ?: ""}"
                    else "Shown on its planned route across the parcel until the operator’s phone sends a position from the field.",
                    TextVariant.Small,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    OutlinedButton(onClick = { tracked = null }, modifier = Modifier.weight(1f)) { Text("Close") }
                    Button(onClick = {
                        tracked = null
                        list.firstOrNull { it.id == follow.work.parcel.id }?.let(::openParcel)
                    }, modifier = Modifier.weight(1f)) { Text("Open parcel") }
                }
            }
            live -> LiveOperationsPanel(list, overview.data.orEmpty().flatMap { it.work }, now, Modifier.align(Alignment.BottomCenter), { parcel -> fitTo = parcel.geometry.coordinates.firstOrNull() }) { live = false }
            canManage(auth.role) && selected == null -> ExtendedFloatingActionButton(
                onClick = { navigator.open(Routes.ParcelEditor()) },
                icon = { Icon(Icons.Outlined.Pentagon, null) },
                text = { Text("New parcel") },
                containerColor = FarmColors.wheat,
                contentColor = FarmColors.wheatInk,
                modifier = Modifier.align(Alignment.BottomEnd).padding(Space.lg),
            )
        }
    }

    selected?.let { parcelId ->
        ParcelIntelligencePanel(
            parcelId,
            PanelActions(
                startWork = { navigator.selectTab(2) },
                startIrrigation = { navigator.selectTab(5) },
                addTask = { navigator.selectTab(3) },
                fullHistory = { id, name -> navigator.open(Routes.ParcelDetail(id, name)) },
                trackWork = { work ->
                    tracked = Tracked(work, "${work.machine?.name ?: "Machine"}, ${WORK_TYPES[work.type]}")
                    list.firstOrNull { it.id == work.parcel.id }?.let { fitTo = it.geometry.coordinates.firstOrNull() }
                },
                configurePlan = { id -> navigator.open(Routes.ParcelWizard(parcelId = id)) },
                openSatellite = { id, name -> navigator.open(Routes.SatelliteMonitoring(id, name)) },
            ),
        ) { selected = null }
    }
}

@Composable
private fun Tool(active: Boolean = false, content: @Composable () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(if (active) FarmColors.wheat else FarmColors.furrow)) { content() }
}

/** Every running machine with its progress; tap one to frame its parcel. */
@Composable
private fun LiveOperationsPanel(parcels: List<Parcel>, work: List<WorkSession>, now: kotlin.time.Instant, modifier: Modifier, onFocus: (Parcel) -> Unit, onClose: () -> Unit) {
    val active = work.filter { isRunActive(it, now) }
    Panel(modifier.padding(Space.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("Live operations (${active.size})", TextVariant.Subtitle, modifier = Modifier.weight(1f))
            IconAction(Icons.Outlined.Close, "Close Live Operations", onClose)
        }
        if (active.isEmpty()) Txt("No machine is working right now.", TextVariant.Small)
        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            active.forEach { item ->
                val progress = runProgressAt(item, now)
                Column(
                    Modifier.fillMaxWidth().clickable { parcels.firstOrNull { it.id == item.parcel.id }?.let(onFocus) },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row {
                        Txt("${item.machine?.name ?: "Machine"} · ${WORK_TYPES[item.type] ?: item.type}", modifier = Modifier.weight(1f), maxLines = 1)
                        Txt("${floor(progress.percent).toInt()}%", color = IMPLEMENT_COLOR[item.type] ?: FarmColors.wheat)
                    }
                    ProgressTrack(progress.percent, IMPLEMENT_COLOR[item.type] ?: FarmColors.wheat)
                    Txt("${item.parcel.name}, ${if (item.status == RunStatus.PAUSED) "paused" else "${hoursMinutes(progress.remainingSeconds)} left"}", TextVariant.Small)
                }
            }
        }
    }
}

