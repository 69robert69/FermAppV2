package ro.fermapp.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import ro.fermapp.app.Navigator
import ro.fermapp.app.data.Api
import ro.fermapp.app.data.AppJson
import ro.fermapp.app.data.Queries
import ro.fermapp.app.data.SendResult
import ro.fermapp.app.data.Toast
import ro.fermapp.app.data.body
import ro.fermapp.app.data.errorMessage
import ro.fermapp.app.data.newOperationId
import ro.fermapp.app.features.CalendarList
import ro.fermapp.app.features.PlanChecklist
import ro.fermapp.app.features.PlanReport
import ro.fermapp.app.features.stepsToCalendar
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.ui.Field
import ro.fermapp.app.ui.Panel
import ro.fermapp.app.ui.ProgressTrack
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import ro.fermapp.domain.CROP_CODES
import ro.fermapp.domain.CropCode
import ro.fermapp.domain.OTHER_CROP
import ro.fermapp.domain.ParcelPlanData
import ro.fermapp.domain.PolygonGeometry
import ro.fermapp.domain.profile
import io.ktor.http.HttpMethod

private const val TOTAL_STEPS = 6
private const val SHOWN_RECOMMENDATIONS = 5

private fun cropLabel(code: CropCode) = if (code == CropCode.BEET) "Sugar beet" else code.profile.name

private val LAST_CROPS: List<Pair<String, String>> =
    listOf(CropCode.WHEAT, CropCode.CORN, CropCode.SUNFLOWER, CropCode.SOY, CropCode.RAPESEED, CropCode.BARLEY, CropCode.ALFALFA, CropCode.POTATO, CropCode.BEET)
        .map { it.name to cropLabel(it) } + (OTHER_CROP to "Other crop")

private val TITLES = listOf(
    "What was the last crop grown on this parcel?",
    "Which crop do you want to plant next season?",
    "Recommended crops",
    "Technology plan",
    "Farm calendar",
    "Parcel report",
)

private fun scoreColor(score: Int): Color = if (score >= 70) FarmColors.leaf else if (score >= 50) FarmColors.amber else FarmColors.rust

@Composable
private fun OptionList(options: List<Pair<String, String>>, value: String?, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        options.forEach { (key, label) ->
            val selected = key == value
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (selected) FarmColors.ridge else FarmColors.furrow)
                    .border(1.dp, if (selected) FarmColors.wheat else FarmColors.line, RoundedCornerShape(6.dp))
                    .clickable { onChange(key) }
                    .padding(horizontal = Space.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Icon(if (selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked, null, tint = if (selected) FarmColors.wheat else FarmColors.straw, modifier = Modifier.size(22.dp))
                Txt(label, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Recommendations(plan: ParcelPlanData, selected: CropCode, onSelect: (CropCode) -> Unit) {
    val top = plan.recommendations.take(SHOWN_RECOMMENDATIONS)
    val chosen = plan.recommendations.firstOrNull { it.crop == selected }
    val shown = if (chosen != null && chosen !in top) top + chosen else top
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Txt("The score combines soil, climate, rainfall and rotation. Tap a crop to choose it.", TextVariant.Small)
        shown.forEach { item ->
            val active = item.crop == selected
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (active) FarmColors.ridge else FarmColors.furrow)
                    .border(1.dp, if (active) FarmColors.wheat else FarmColors.line, RoundedCornerShape(6.dp))
                    .clickable { onSelect(item.crop) }.padding(Space.md),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(cropLabel(item.crop), TextVariant.Subtitle, modifier = Modifier.weight(1f))
                    Txt("${item.score}%", TextVariant.Figure, color = scoreColor(item.score))
                }
                ProgressTrack(item.score.toDouble(), scoreColor(item.score))
                Txt(item.reason, TextVariant.Small)
            }
        }
    }
}

@Composable
fun ParcelWizardScreen(parcelId: String?, draftJson: String?, navigator: Navigator) {
    val draft = remember(draftJson) { draftJson?.let { AppJson.decodeFromString(ParcelDraft.serializer(), it) } }
    var step by remember { mutableIntStateOf(1) }
    var lastCrop by remember { mutableStateOf<String?>(null) }
    var lastCropName by remember { mutableStateOf("") }
    var plannedCrop by remember { mutableStateOf<CropCode?>(null) }
    var plan by remember { mutableStateOf<ParcelPlanData?>(null) }
    var analysing by remember { mutableStateOf(false) }
    var analysisError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val operationId = remember { newOperationId() }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    fun choice(): JsonObject = body(
        "lastCrop" to lastCrop,
        "lastCropName" to (if (lastCrop == OTHER_CROP && lastCropName.isNotBlank()) lastCropName.trim() else null),
        "plannedCrop" to plannedCrop,
    )

    fun target(): Map<String, kotlinx.serialization.json.JsonElement> =
        if (parcelId != null) mapOf("parcelId" to kotlinx.serialization.json.JsonPrimitive(parcelId))
        else mapOf(
            "geometry" to AppJson.encodeToJsonElement(PolygonGeometry.serializer(), draft!!.geometry),
            "name" to kotlinx.serialization.json.JsonPrimitive(draft.name),
        )

    // The analysis follows every change of the chosen crops once the plan steps are reached.
    LaunchedEffect(step >= 3, lastCrop, lastCropName, plannedCrop) {
        if (step < 3 || lastCrop == null || plannedCrop == null) return@LaunchedEffect
        analysing = true
        analysisError = null
        try {
            val result = Api.sendJson(HttpMethod.Post, "/parcels/wizard/analyze", JsonObject(choice() + target()), offline = false, idempotent = false)
            if (result is SendResult.Done) plan = AppJson.decodeFromJsonElement(ParcelPlanData.serializer(), result.value)
        } catch (error: Throwable) {
            analysisError = errorMessage(error)
        } finally {
            analysing = false
        }
    }

    fun finish() {
        scope.launch { Queries.invalidate("parcels", "map-overview", "tasks", "dashboard", "intelligence") }
        navigator.back()
        if (parcelId == null) navigator.back()
    }

    fun save() {
        saving = true
        scope.launch {
            try {
                val where = if (parcelId != null) mapOf("parcelId" to kotlinx.serialization.json.JsonPrimitive(parcelId))
                else mapOf("draft" to AppJson.encodeToJsonElement(ParcelDraft.serializer(), draft!!))
                Api.sendJson(HttpMethod.Post, "/parcels/wizard/complete", JsonObject(choice() + where), offline = false, operationId = operationId)
                finish()
            } catch (error: Throwable) {
                Toast.show("The action failed: ${errorMessage(error)}")
            } finally {
                saving = false
            }
        }
    }

    // Without a connection, the drawn parcel stays saved in the offline queue.
    fun saveWithoutPlan() {
        saving = true
        scope.launch {
            try {
                Api.post("/parcels", AppJson.encodeToJsonElement(ParcelDraft.serializer(), draft!!))
                finish()
            } catch (error: Throwable) {
                Toast.show("The action failed: ${errorMessage(error)}")
            } finally {
                saving = false
            }
        }
    }

    fun go(next: Int) {
        step = next
        scope.launch { scroll.scrollTo(0) }
    }

    val canContinue = when (step) {
        1 -> lastCrop != null
        2 -> plannedCrop != null
        else -> plan != null && !analysing
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = Space.lg, end = Space.lg, top = Space.lg, bottom = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Txt("Step $step of $TOTAL_STEPS", TextVariant.Small)
            ProgressTrack(step * 100.0 / TOTAL_STEPS)
        }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.lg)) {
            Txt(TITLES[step - 1], TextVariant.Title)
            when (step) {
                1 -> {
                    OptionList(LAST_CROPS, lastCrop) { lastCrop = it }
                    if (lastCrop == OTHER_CROP) Field(lastCropName, { lastCropName = it }, "Crop name (optional)")
                }
                2 -> OptionList(CROP_CODES.map { it.name to cropLabel(it) }, plannedCrop?.name) { plannedCrop = CropCode.valueOf(it) }
                else -> {
                    if (analysing) Txt("Analysing soil, climate and crop rotation.", TextVariant.Small)
                    analysisError?.let { Txt(it, TextVariant.Small, color = FarmColors.rust) }
                    plan?.let { current ->
                        when (step) {
                            3 -> plannedCrop?.let { crop -> Recommendations(current, crop) { plannedCrop = it } }
                            4 -> Panel {
                                Txt(cropLabel(current.crop), TextVariant.Subtitle)
                                PlanChecklist(current.steps)
                            }
                            5 -> Panel {
                                Txt(cropLabel(current.crop), TextVariant.Subtitle)
                                CalendarList(stepsToCalendar(current.steps))
                                Txt("Activities are added automatically to To Do, the calendar and the parcel panel.", TextVariant.Small)
                            }
                            else -> PlanReport(current.report)
                        }
                    }
                    if (analysisError != null && draft != null) {
                        OutlinedButton(onClick = { saveWithoutPlan() }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("Save parcel without a plan") }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(FarmColors.furrow).padding(horizontal = Space.lg, vertical = Space.md).navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            OutlinedButton(onClick = { if (step == 1) navigator.back() else go(step - 1) }, modifier = Modifier.weight(1f)) { Text("Back") }
            if (step < TOTAL_STEPS) {
                Button(onClick = { go(step + 1) }, enabled = canContinue, modifier = Modifier.weight(1f)) { Text("Continue") }
            } else {
                Button(onClick = { save() }, enabled = canContinue && !saving, modifier = Modifier.weight(1f)) { Text(if (parcelId != null) "Save plan" else "Save parcel") }
            }
        }
    }
}
