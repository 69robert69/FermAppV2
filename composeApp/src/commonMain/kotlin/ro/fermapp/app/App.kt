package ro.fermapp.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Agriculture
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import ro.fermapp.app.data.Api
import ro.fermapp.app.data.Auth
import ro.fermapp.app.data.AuthStatus
import ro.fermapp.app.data.Queries
import ro.fermapp.app.data.Sync
import ro.fermapp.app.data.Toast
import ro.fermapp.app.screens.AssistantScreen
import ro.fermapp.app.screens.AuthScreen
import ro.fermapp.app.screens.CalendarScreen
import ro.fermapp.app.screens.CatalogDetailScreen
import ro.fermapp.app.screens.CatalogEditorScreen
import ro.fermapp.app.screens.CatalogScreen
import ro.fermapp.app.screens.DashboardScreen
import ro.fermapp.app.screens.EconomicSettingsScreen
import ro.fermapp.app.screens.FinancialDashboardScreen
import ro.fermapp.app.screens.IrrigationScreen
import ro.fermapp.app.screens.MachinesScreen
import ro.fermapp.app.screens.MapScreen
import ro.fermapp.app.screens.ParcelDetailScreen
import ro.fermapp.app.screens.ParcelEditorScreen
import ro.fermapp.app.screens.ParcelWizardScreen
import ro.fermapp.app.screens.ReportsScreen
import ro.fermapp.app.screens.SatelliteScreen
import ro.fermapp.app.screens.SettingsScreen
import ro.fermapp.app.screens.TasksScreen
import ro.fermapp.app.screens.TillageAnalysisScreen
import ro.fermapp.app.screens.WorkScreen
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.FarmTheme

/** The stack screens of navigation/RootNavigator.tsx, as type-safe routes. */
object Routes {
    @Serializable object Tabs
    @Serializable data class ParcelDetail(val parcelId: String, val name: String)
    @Serializable data class ParcelEditor(val parcelId: String? = null)
    @Serializable data class ParcelWizard(val parcelId: String? = null, val draftJson: String? = null)
    @Serializable object Machines
    @Serializable object Settings
    @Serializable object Catalog
    @Serializable data class CatalogDetail(val kind: String, val id: String)
    @Serializable data class CatalogEditor(val kind: String, val id: String? = null)
    @Serializable object TillageAnalysis
    @Serializable object FinancialDashboard
    @Serializable object EconomicSettings
    @Serializable data class SatelliteMonitoring(val parcelId: String, val name: String)
}

/** What screens use to move around, instead of passing the NavController everywhere. */
class Navigator(private val controller: NavHostController, val selectTab: (Int) -> Unit) {
    fun open(route: Any) = controller.navigate(route)
    fun back() = controller.popBackStack()
}

private data class TabItem(val label: String, val icon: ImageVector)

private val TABS = listOf(
    TabItem("Dashboard", Icons.Outlined.Dashboard),
    TabItem("Map", Icons.Outlined.Map),
    TabItem("Work", Icons.Outlined.Agriculture),
    TabItem("To Do", Icons.Outlined.TaskAlt),
    TabItem("Calendar", Icons.Outlined.CalendarMonth),
    TabItem("Irrigation", Icons.Outlined.WaterDrop),
    TabItem("Reports", Icons.Outlined.Assessment),
    TabItem("AI", Icons.Outlined.Spa),
)

/** Core data reaches the phone even if the user does not open every screen. */
private val OFFLINE_QUERIES = listOf(
    "dashboard" to "/dashboard",
    "parcels" to "/parcels",
    "work" to "/work",
    "tasks" to "/tasks",
    "irrigation" to "/irrigation",
    "machines" to "/machines",
    "team" to "/team",
)

@Composable
fun App() {
    FarmTheme {
        LaunchedEffect(Unit) {
            Auth.restore()
            Sync.start()
        }
        val auth by Auth.flow.collectAsState()
        Box(Modifier.fillMaxSize().background(FarmColors.loam)) {
            when (auth.status) {
                AuthStatus.LOADING -> Unit
                AuthStatus.SIGNED_OUT -> AuthScreen()
                AuthStatus.SIGNED_IN -> SignedIn()
            }
        }
    }
}

@Composable
private fun SignedIn() {
    val controller = rememberNavController()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val navigator = remember(controller) { Navigator(controller) { tab = it } }
    val snackbar = remember { SnackbarHostState() }
    val toast by Toast.flow.collectAsState()

    LaunchedEffect(Unit) {
        for ((key, path) in OFFLINE_QUERIES) runCatching { Queries.prefetch(key) { Api.getJson(path) } }
        Sync.flush()
    }
    LaunchedEffect(toast) {
        val message = toast ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        Toast.hide()
    }

    Scaffold(containerColor = FarmColors.loam, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        NavHost(controller, startDestination = Routes.Tabs, modifier = Modifier.padding(padding)) {
            composable<Routes.Tabs> { Tabs(tab, { tab = it }, navigator) }
            composable<Routes.ParcelDetail> { entry ->
                val route = entry.toRoute<Routes.ParcelDetail>()
                Stacked(route.name, navigator) { ParcelDetailScreen(route.parcelId, navigator) }
            }
            composable<Routes.ParcelEditor> { entry ->
                Stacked("Parcel", navigator) { ParcelEditorScreen(entry.toRoute<Routes.ParcelEditor>().parcelId, navigator) }
            }
            composable<Routes.ParcelWizard> { entry ->
                val route = entry.toRoute<Routes.ParcelWizard>()
                Stacked("Parcel setup", navigator) { ParcelWizardScreen(route.parcelId, route.draftJson, navigator) }
            }
            composable<Routes.Machines> { Stacked("Machines", navigator) { MachinesScreen(navigator) } }
            composable<Routes.Settings> { Stacked("Settings", navigator) { SettingsScreen(navigator) } }
            composable<Routes.Catalog> { Stacked("Farm catalog", navigator) { CatalogScreen(navigator) } }
            composable<Routes.CatalogDetail> { entry ->
                val route = entry.toRoute<Routes.CatalogDetail>()
                Stacked("Product sheet", navigator) { CatalogDetailScreen(route.kind, route.id, navigator) }
            }
            composable<Routes.CatalogEditor> { entry ->
                val route = entry.toRoute<Routes.CatalogEditor>()
                Stacked("Edit catalog", navigator) { CatalogEditorScreen(route.kind, route.id, navigator) }
            }
            composable<Routes.TillageAnalysis> { Stacked("Tillage analysis", navigator) { TillageAnalysisScreen(navigator) } }
            composable<Routes.FinancialDashboard> { Stacked("Financial dashboard", navigator) { FinancialDashboardScreen(navigator) } }
            composable<Routes.EconomicSettings> { Stacked("Costs of diesel, labour and machines", navigator) { EconomicSettingsScreen(navigator) } }
            composable<Routes.SatelliteMonitoring> { entry ->
                val route = entry.toRoute<Routes.SatelliteMonitoring>()
                Stacked("Satellite · ${route.name}", navigator) { SatelliteScreen(route.parcelId, route.name, navigator) }
            }
        }
    }
}

@Composable
private fun Tabs(tab: Int, onTab: (Int) -> Unit, navigator: Navigator) {
    Scaffold(
        containerColor = FarmColors.loam,
        bottomBar = {
            NavigationBar(containerColor = FarmColors.furrow) {
                TABS.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { onTab(index) },
                        icon = { Icon(item.icon, contentDescription = if (item.label == "AI") "AI Assistant" else item.label) },
                        label = { Text(item.label, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = FarmColors.wheat,
                            selectedTextColor = FarmColors.wheat,
                            unselectedIconColor = FarmColors.straw,
                            unselectedTextColor = FarmColors.straw,
                            indicatorColor = FarmColors.ridge,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> DashboardScreen(navigator)
                1 -> MapScreen(navigator)
                2 -> WorkScreen(navigator)
                3 -> TasksScreen(navigator)
                4 -> CalendarScreen(navigator)
                5 -> IrrigationScreen(navigator)
                6 -> ReportsScreen(navigator)
                else -> AssistantScreen(navigator)
            }
        }
    }
}

/** A stack screen with the furrow-coloured header and a back arrow. */
@Composable
private fun Stacked(title: String, navigator: Navigator, content: @Composable () -> Unit) {
    Scaffold(
        containerColor = FarmColors.loam,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = FarmColors.furrow, titleContentColor = FarmColors.chaff, navigationIconContentColor = FarmColors.chaff),
            )
        },
    ) { padding -> Box(Modifier.padding(padding)) { content() } }
}
