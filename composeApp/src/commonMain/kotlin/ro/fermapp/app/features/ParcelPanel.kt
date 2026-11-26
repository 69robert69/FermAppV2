package ro.fermapp.app.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddTask
import androidx.compose.material.icons.outlined.Agriculture
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Satellite
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ro.fermapp.app.data.Api
import ro.fermapp.app.data.ParcelIntelligence
import ro.fermapp.app.data.Toast
import ro.fermapp.app.data.WorkSession
import ro.fermapp.app.data.canManage
import ro.fermapp.app.data.canOperate
import ro.fermapp.app.data.canPlan
import ro.fermapp.app.data.errorMessage
import ro.fermapp.app.data.intelligenceKey
import ro.fermapp.app.data.rememberApi
import ro.fermapp.app.data.rememberAuth
import ro.fermapp.app.data.rememberFieldStatuses
import ro.fermapp.app.data.rememberParcels
import ro.fermapp.app.platform.openUrl
import ro.fermapp.app.platform.shareFile
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.ui.QueryContent
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt

/** What the parcel panel can open, so the map screen decides where each one goes. */
class PanelActions(
    val startWork: (String) -> Unit,
    val startIrrigation: (String) -> Unit,
    val addTask: (String) -> Unit,
    val fullHistory: (String, String) -> Unit,
    val trackWork: (WorkSession) -> Unit,
    val configurePlan: (String) -> Unit,
    val openSatellite: (String, String) -> Unit,
)

@Composable
private fun QuickAction(icon: ImageVector, label: String, busy: Boolean = false, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.heightIn(min = 72.dp).clickable(enabled = !busy, onClick = onClick).padding(vertical = Space.sm, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Icon(icon, label, tint = if (busy) FarmColors.straw else FarmColors.wheat, modifier = Modifier.size(22.dp))
        Txt(label, TextVariant.Small, color = FarmColors.chaff, maxLines = 2, align = TextAlign.Center)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelContent(data: ParcelIntelligence, actions: PanelActions, dismiss: () -> Unit) {
    val auth = rememberAuth()
    val role = auth.role
    val parcelId = data.header.id
    val parcel = rememberParcels().data?.firstOrNull { it.id == parcelId }
    val statuses = rememberFieldStatuses()
    val scope = rememberCoroutineScope()
    var reporting by remember { mutableStateOf(false) }

    fun leave(action: () -> Unit) {
        dismiss()
        action()
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = Space.lg, end = Space.lg, bottom = Space.xxl * 2).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.xl),
    ) {
        HeaderSection(data)

        FlowRow(maxItemsInEachRow = 4) {
            val cell = Modifier.weight(1f)
            if (canOperate(role)) QuickAction(Icons.Outlined.Agriculture, "Start operation", modifier = cell) { leave { actions.startWork(parcelId) } }
            if (canOperate(role)) QuickAction(Icons.Outlined.WaterDrop, "Start irrigation", modifier = cell) { leave { actions.startIrrigation(parcelId) } }
            if (canPlan(role)) QuickAction(Icons.Outlined.AddTask, "Add task", modifier = cell) { leave { actions.addTask(parcelId) } }
            QuickAction(Icons.Outlined.Assessment, "Generate report", busy = reporting, modifier = cell) {
                reporting = true
                scope.launch {
                    try {
                        shareFile(Api.download("/parcels/$parcelId/report", "parcel-${data.header.code}.pdf"), "application/pdf")
                    } catch (error: Throwable) {
                        Toast.show("The report was not generated: ${errorMessage(error)}")
                    } finally {
                        reporting = false
                    }
                }
            }
            QuickAction(Icons.Outlined.Satellite, "Satellite", modifier = cell) { leave { actions.openSatellite(parcelId, data.header.name) } }
            QuickAction(Icons.Outlined.History, "Full history", modifier = cell) { leave { actions.fullHistory(parcelId, data.header.name) } }
            QuickAction(Icons.Outlined.Navigation, "Navigate", modifier = cell) {
                openUrl("https://www.google.com/maps/dir/?api=1&destination=${data.header.lat},${data.header.lng}")
            }
        }

        FieldStatusSection(statuses[parcelId])
        CropSection(data)
        if (parcel != null) TillageSection(parcel, canManage(role), data.crop?.code)
        NextActionSection(data)
        PlanSection(data, canPlan(role)) { leave { actions.configurePlan(parcelId) } }
        ActiveWorkSection(data) { work -> leave { actions.trackWork(work) } }
        ActiveIrrigationSection(data)
        OperationHistorySection(parcelId)
        RotationSection(data)
        IrrigationCoverageSection(data)
        TasksSection(data)
        WeatherSection(data)
        SoilSection(data)
        RecommendationsSection(parcelId)
        if (canPlan(role) || canManage(role)) EconomicSummarySection(parcelId) { leave { actions.fullHistory(parcelId, data.header.name) } }
        SatelliteSection(data, canPlan(role))
        PhotosSection(data)
    }
}

/** The parcel intelligence sheet over the map, as ParcelIntelligencePanel.tsx. */
@Composable
fun ParcelIntelligencePanel(parcelId: String, actions: PanelActions, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    val query = rememberApi<ParcelIntelligence>(intelligenceKey(parcelId), "/parcels/$parcelId/intelligence")
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = FarmColors.loam,
        scrimColor = FarmColors.loam.copy(alpha = 0.15f),
    ) {
        QueryContent(query) { data ->
            PanelContent(data, actions) { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } }
        }
    }
}
