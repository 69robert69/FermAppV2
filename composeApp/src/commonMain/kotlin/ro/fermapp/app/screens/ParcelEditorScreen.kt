package ro.fermapp.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import ro.fermapp.app.Navigator
import ro.fermapp.app.Routes
import ro.fermapp.app.data.Api
import ro.fermapp.app.data.AppJson
import ro.fermapp.app.data.Parcel
import ro.fermapp.app.data.body
import ro.fermapp.app.data.rememberActions
import ro.fermapp.app.data.rememberApi
import ro.fermapp.app.features.TillagePicker
import ro.fermapp.app.platform.FieldMap
import ro.fermapp.app.platform.MapCamera
import ro.fermapp.app.platform.MapLine
import ro.fermapp.app.platform.MapMarker
import ro.fermapp.app.platform.MapPolygon
import ro.fermapp.app.platform.currentLocation
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.ui.Field
import ro.fermapp.app.ui.IconAction
import ro.fermapp.app.ui.Stat
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import ro.fermapp.app.ui.number
import ro.fermapp.app.ui.parseNumber
import ro.fermapp.domain.PolygonGeometry
import ro.fermapp.domain.Position
import ro.fermapp.domain.TillageSystem
import ro.fermapp.domain.computeGeoMetrics
import ro.fermapp.domain.polygonFromPoints
import ro.fermapp.domain.toFixed
import ro.fermapp.domain.validatePolygon

/** A parcel drawn but not saved yet; the wizard saves it together with its plan. */
@Serializable
data class ParcelDraft(
    val name: String,
    val geometry: PolygonGeometry,
    val code: String? = null,
    val locality: String? = null,
    val county: String? = null,
    val notes: String? = null,
    val irrigatedAreaHa: Double? = null,
    val tillageSystem: TillageSystem? = null,
)

private fun argb(color: androidx.compose.ui.graphics.Color, alpha: Float = 1f) = color.copy(alpha = alpha).toArgb().toLong() and 0xFFFFFFFFL

@Composable
fun ParcelEditorScreen(parcelId: String?, navigator: Navigator) {
    val existing = if (parcelId != null) rememberApi<Parcel>("parcel/$parcelId", "/parcels/$parcelId").data else null
    val actions = rememberActions()
    var points by remember { mutableStateOf<List<Position>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var locality by remember { mutableStateOf("") }
    var county by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var irrigated by remember { mutableStateOf("") }
    var tillage by remember { mutableStateOf<TillageSystem?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var fitTo by remember { mutableStateOf<List<Position>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmArchive by remember { mutableStateOf(false) }

    LaunchedEffect(existing) {
        val parcel = existing ?: return@LaunchedEffect
        if (loaded) return@LaunchedEffect
        val open = parcel.geometry.coordinates.firstOrNull().orEmpty().dropLast(1)
        points = open
        name = parcel.name
        code = parcel.code
        locality = parcel.locality ?: ""
        county = parcel.county ?: ""
        notes = parcel.notes ?: ""
        irrigated = parcel.irrigatedAreaHa?.let { ro.fermapp.domain.jsNum(it) } ?: ""
        tillage = parcel.tillageSystem
        loaded = true
        fitTo = open
    }
    // A new parcel starts where the farmer is standing.
    LaunchedEffect(parcelId) {
        if (parcelId == null) currentLocation()?.let { fitTo = listOf(it) }
    }

    val polygon = if (points.size >= 3) polygonFromPoints(points) else null
    val problem = polygon?.let(::validatePolygon)
    val metrics = if (polygon != null && problem == null) computeGeoMetrics(polygon) else null

    fun draft() = ParcelDraft(
        name = name.trim(),
        geometry = polygon!!,
        code = code.trim().ifEmpty { null },
        locality = locality.trim().ifEmpty { null },
        county = county.trim().ifEmpty { null },
        notes = notes.trim().ifEmpty { null },
        irrigatedAreaHa = if (irrigated.isNotBlank()) parseNumber(irrigated) else null,
        tillageSystem = tillage,
    )

    if (confirmArchive) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text("Archive parcel") },
            text = { Text("The parcel disappears from lists, but its history is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmArchive = false
                    actions.run("parcels", "dashboard", "map-overview", onSuccess = { navigator.back(); navigator.back() }) { Api.post("/parcels/$parcelId/archive") }
                }) { Text("Archive", color = FarmColors.rust) }
            },
            dismissButton = { TextButton(onClick = { confirmArchive = false }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.48f).background(FarmColors.ridge)) {
            val stroke = if (problem != null) FarmColors.rust else FarmColors.wheat
            FieldMap(
                modifier = Modifier.fillMaxSize(),
                camera = MapCamera(Position(25.0, 45.9), 6f),
                polygons = if (points.size >= 3) listOf(MapPolygon("draft", listOf(points + points.first()), argb(stroke, 0.27f), argb(stroke))) else emptyList(),
                markers = points.mapIndexed { index, point -> MapMarker("vertex-$index", point, "Corner ${index + 1}", argb(FarmColors.wheat)) },
                lines = if (points.size == 2) listOf(MapLine("draft-line", points, argb(FarmColors.wheat), 4f)) else emptyList(),
                onTap = { point -> points = points + point },
                onPolygonTap = null,
                fitTo = fitTo,
            )
            Column(Modifier.align(Alignment.TopEnd).padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(FarmColors.furrow)) {
                    IconAction(Icons.AutoMirrored.Outlined.Undo, "Undo last point", { points = points.dropLast(1) })
                }
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(FarmColors.furrow)) {
                    IconAction(Icons.Outlined.Delete, "Delete polygon", { points = emptyList() }, tint = FarmColors.rust)
                }
            }
            if (points.size < 3) {
                Box(
                    Modifier.align(Alignment.BottomStart).padding(start = Space.md, end = 76.dp, bottom = Space.md)
                        .clip(RoundedCornerShape(6.dp)).background(FarmColors.loam.copy(alpha = 0.9f)).padding(Space.sm),
                ) {
                    Txt("Tap the map to mark the corners of the parcel. Undo removes the last corner.", TextVariant.Small, color = FarmColors.chaff)
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = Space.lg, end = Space.lg, top = Space.lg, bottom = Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Row {
                Stat(metrics?.let { number(it.areaHa, 2) } ?: "0", "Area", unit = "ha", modifier = Modifier.weight(1f))
                Stat(metrics?.let { number(it.perimeterM, 0) } ?: "0", "Perimeter", unit = "m", modifier = Modifier.weight(1f))
            }
            metrics?.let { Txt("GPS centre: ${it.center.lat.toFixed(5)}, ${it.center.lng.toFixed(5)}", TextVariant.Small) }
            problem?.let { Txt(it, TextVariant.Small, color = FarmColors.rust) }
            Field(name, { name = it }, "Parcel name")
            Field(code, { code = it.uppercase() }, "Parcel code (optional)")
            Field(locality, { locality = it }, "Locality")
            Field(county, { county = it }, "County")
            Field(irrigated, { irrigated = it }, "Irrigated area (ha)", keyboard = KeyboardType.Decimal)
            Field(notes, { notes = it }, "Notes", singleLine = false)
            Txt("Tillage technology", TextVariant.Small)
            TillagePicker(tillage, { tillage = it })
            Button(
                onClick = {
                    if (parcelId == null) {
                        navigator.open(Routes.ParcelWizard(draftJson = AppJson.encodeToString(ParcelDraft.serializer(), draft())))
                        return@Button
                    }
                    saving = true
                    val values = draft()
                    actions.run("parcels", "parcel/$parcelId", "dashboard", "map-overview", onSuccess = { navigator.back() }) {
                        try {
                            Api.patch(
                                "/parcels/$parcelId",
                                body(
                                    "name" to values.name,
                                    "geometry" to AppJson.encodeToJsonElement(PolygonGeometry.serializer(), values.geometry),
                                    "locality" to values.locality,
                                    "county" to values.county,
                                    "notes" to values.notes,
                                    "code" to values.code,
                                    "irrigatedAreaHa" to (values.irrigatedAreaHa ?: kotlinx.serialization.json.JsonNull),
                                ),
                            )
                            // The technology goes through its own endpoint, so this year's history entry is kept in step.
                            val chosen = values.tillageSystem
                            if (chosen != null && chosen != existing?.tillageSystem) {
                                Api.post("/parcels/$parcelId/tillage", body("system" to chosen, "year" to currentYear()))
                            }
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = metrics != null && name.isNotBlank() && !saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (parcelId != null) "Save parcel" else "Continue") }
            if (parcelId != null) {
                TextButton(onClick = { confirmArchive = true }, modifier = Modifier.fillMaxWidth()) { Text("Archive parcel", color = FarmColors.rust) }
            }
        }
    }
}
