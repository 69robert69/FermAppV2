package ro.fermapp.app.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.serialization.Serializable
import ro.fermapp.app.Navigator
import ro.fermapp.app.Routes
import ro.fermapp.app.data.Prefs
import ro.fermapp.app.data.Role
import ro.fermapp.app.data.rememberAuth
import ro.fermapp.app.data.rememberParcels
import ro.fermapp.app.features.ParcelPicker
import ro.fermapp.app.platform.rememberFilePicker
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.ui.Choice
import ro.fermapp.app.ui.ChoiceOption
import ro.fermapp.app.ui.Empty
import ro.fermapp.app.ui.Field
import ro.fermapp.app.ui.InfoRow
import ro.fermapp.app.ui.Panel
import ro.fermapp.app.ui.ProgressTrack
import ro.fermapp.app.ui.Screen
import ro.fermapp.app.ui.Section
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import ro.fermapp.app.ui.isPositive
import ro.fermapp.app.ui.number
import ro.fermapp.app.ui.parseNumber
import ro.fermapp.domain.CROP_CODES
import ro.fermapp.domain.CatalogLevel
import ro.fermapp.domain.CropCode
import ro.fermapp.domain.CropHistoryEntry
import ro.fermapp.domain.IMPLEMENT_CATALOG
import ro.fermapp.domain.RowResult
import ro.fermapp.domain.SEED_CATALOG
import ro.fermapp.domain.SeedEntry
import ro.fermapp.domain.SeedFilters
import ro.fermapp.domain.SeedSort
import ro.fermapp.domain.SeedType
import ro.fermapp.domain.SoilTexture
import ro.fermapp.domain.TRACTOR_CATALOG
import ro.fermapp.domain.TractorEntry
import ro.fermapp.domain.TractorSort
import ro.fermapp.domain.createSeedEntry
import ro.fermapp.domain.createTractorEntry
import ro.fermapp.domain.filterSeeds
import ro.fermapp.domain.filterTractors
import ro.fermapp.domain.jsNum
import ro.fermapp.domain.parseCsv
import ro.fermapp.domain.profile
import ro.fermapp.domain.rankSeedsForParcel
import ro.fermapp.domain.seedFromRow
import ro.fermapp.domain.tractorFromRow

/**
 * The seed and tractor catalog of features/catalog. The server has no catalog, so the
 * built-in products are combined with the farm's own entries, which are kept on the phone.
 */

@Serializable
private data class CatalogEdits(
    val seeds: List<SeedEntry> = emptyList(),
    val tractors: List<TractorEntry> = emptyList(),
    /** Built-in products the farm removed. */
    val hidden: List<String> = emptyList(),
)

object CatalogStore {
    private val store = Prefs("catalog-entries", CatalogEdits.serializer(), CatalogEdits())

    private fun CatalogEdits.seedList() = (SEED_CATALOG.filter { builtIn -> builtIn.id !in hidden && seeds.none { it.id == builtIn.id } } + seeds)
    private fun CatalogEdits.tractorList() = (TRACTOR_CATALOG.filter { builtIn -> builtIn.id !in hidden && tractors.none { it.id == builtIn.id } } + tractors)

    @Composable
    fun rememberSeeds(): List<SeedEntry> {
        val edits by store.flow.collectAsState()
        return remember(edits) { edits.seedList() }
    }

    @Composable
    fun rememberTractors(): List<TractorEntry> {
        val edits by store.flow.collectAsState()
        return remember(edits) { edits.tractorList() }
    }

    val seeds: List<SeedEntry> get() = store.value.seedList()

    fun saveSeed(entry: SeedEntry) = store.update { edits -> edits.copy(seeds = edits.seeds.filter { it.id != entry.id } + entry, hidden = edits.hidden - entry.id) }

    fun saveTractor(entry: TractorEntry) = store.update { edits -> edits.copy(tractors = edits.tractors.filter { it.id != entry.id } + entry, hidden = edits.hidden - entry.id) }

    fun remove(kind: String, id: String) = store.update { edits ->
        if (kind == "seed") edits.copy(seeds = edits.seeds.filter { it.id != id }, hidden = edits.hidden + id)
        else edits.copy(tractors = edits.tractors.filter { it.id != id }, hidden = edits.hidden + id)
    }

    /** Filters stay saved between visits, like the original catalog preferences. */
    internal val seedFilters = Prefs("catalog-seed-filters", SeedFilterPrefs.serializer(), SeedFilterPrefs())
}

@Serializable
internal data class SeedFilterPrefs(
    val search: String = "",
    val crop: CropCode? = null,
    val brand: String? = null,
    val faoMin: Double? = null,
    val faoMax: Double? = null,
    val waterRequirement: CatalogLevel? = null,
    val droughtTolerance: CatalogLevel? = null,
    val yieldPotential: CatalogLevel? = null,
    val sort: SeedSort = SeedSort.NAME,
) {
    fun toFilters() = SeedFilters(search, crop, brand, droughtTolerance, waterRequirement, yieldPotential, faoMin, faoMax, sort)
}

private const val MISSING = "Not filled in"
private val SOIL_LABELS = mapOf(SoilTexture.SANDY to "Sandy", SoilTexture.LOAMY to "Loam", SoilTexture.CLAY_LOAM to "Clay loam", SoilTexture.CLAY to "Clay")

private fun levelOptions(empty: String) = listOf(ChoiceOption<CatalogLevel?>(null, empty)) + CatalogLevel.entries.map { ChoiceOption<CatalogLevel?>(it, it.label) }
private fun level(value: CatalogLevel?) = value?.label ?: MISSING
private fun text(value: Double?) = value?.let(::jsNum) ?: ""
private fun toNumber(value: String) = if (isPositive(value)) parseNumber(value) else null
private fun validOptional(value: String) = value.isBlank() || isPositive(value)

@Composable
private fun SeedsTab(navigator: Navigator) {
    val seeds = CatalogStore.rememberSeeds()
    val filters by CatalogStore.seedFilters.flow.collectAsState()
    var showFilters by remember { mutableStateOf(false) }
    val crops = remember(seeds) { seeds.map { it.crop }.distinct() }
    val brands = remember(seeds) { seeds.mapNotNull { it.brand }.distinct().sorted() }
    val visible = remember(seeds, filters) { filterSeeds(seeds, filters.toFilters()) }
    fun set(transform: (SeedFilterPrefs) -> SeedFilterPrefs) = CatalogStore.seedFilters.update(transform)

    Field(filters.search, { value -> set { it.copy(search = value) } }, "Search hybrid, variety or manufacturer")
    Choice(listOf(ChoiceOption<CropCode?>(null, "All crops")) + crops.map { ChoiceOption<CropCode?>(it, it.profile.name) }, filters.crop, { crop -> set { it.copy(crop = crop) } })
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        OutlinedButton(onClick = { showFilters = !showFilters }, modifier = Modifier.weight(1f)) { Text(if (showFilters) "Hide filters" else "Filters") }
        TextButton(onClick = { set { SeedFilterPrefs() } }, modifier = Modifier.weight(1f)) { Text("Reset") }
    }
    if (showFilters) {
        Panel {
            Choice(listOf(ChoiceOption<String?>(null, "All")) + brands.map { ChoiceOption<String?>(it, it) }, filters.brand, { brand -> set { it.copy(brand = brand) } }, "Manufacturer")
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                var faoMin by remember { mutableStateOf(text(filters.faoMin)) }
                var faoMax by remember { mutableStateOf(text(filters.faoMax)) }
                Field(faoMin, { value -> faoMin = value; set { it.copy(faoMin = if (value.isBlank()) null else parseNumber(value)) } }, "Min FAO", Modifier.weight(1f), KeyboardType.Number)
                Field(faoMax, { value -> faoMax = value; set { it.copy(faoMax = if (value.isBlank()) null else parseNumber(value)) } }, "Max FAO", Modifier.weight(1f), KeyboardType.Number)
            }
            Choice(levelOptions("Any"), filters.waterRequirement, { value -> set { it.copy(waterRequirement = value) } }, "Water requirement")
            Choice(levelOptions("Any"), filters.droughtTolerance, { value -> set { it.copy(droughtTolerance = value) } }, "Drought tolerance")
            Choice(levelOptions("Any"), filters.yieldPotential, { value -> set { it.copy(yieldPotential = value) } }, "Yield potential")
            Choice(
                listOf(ChoiceOption(SeedSort.NAME, "Name"), ChoiceOption(SeedSort.FAO, "FAO"), ChoiceOption(SeedSort.YIELD, "Yield")),
                filters.sort, { sort -> set { it.copy(sort = sort) } }, "Sort by",
            )
        }
    }
    if (visible.isEmpty()) {
        Empty(Icons.Outlined.SearchOff, "No products found", "Change the filters or search for another name.")
    }
    visible.forEach { seed ->
        Panel(Modifier.clickable { navigator.open(Routes.CatalogDetail("seed", seed.id)) }) {
            Txt(seed.name, TextVariant.Subtitle)
            Txt(listOfNotNull(seed.crop.profile.name, seed.type.label, seed.brand, seed.fao?.let { "FAO ${jsNum(it)}" }).joinToString(", "), TextVariant.Small)
        }
    }
}

@Composable
private fun TractorsTab(navigator: Navigator) {
    val tractors = CatalogStore.rememberTractors()
    var search by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(TractorSort.NAME) }
    val brands = remember(tractors) { tractors.map { it.brand }.distinct().sorted() }
    val visible = remember(tractors, search, brand, sort) { filterTractors(tractors, search, brand, sort) }

    Field(search, { search = it }, "Search brand or model")
    Choice(listOf(ChoiceOption<String?>(null, "All brands")) + brands.map { ChoiceOption<String?>(it, it) }, brand, { brand = it })
    Choice(listOf(ChoiceOption(TractorSort.NAME, "Name"), ChoiceOption(TractorSort.POWER, "Power")), sort, { sort = it }, "Sort by")
    visible.forEach { tractor ->
        Panel(Modifier.clickable { navigator.open(Routes.CatalogDetail("tractor", tractor.id)) }) {
            Txt("${tractor.brand} ${tractor.model}", TextVariant.Subtitle)
            Txt(
                "${jsNum(tractor.horsePower)} HP, ${number(tractor.fuelConsumptionMin)}-${number(tractor.fuelConsumptionMax)} l/hour${if (tractor.fuelEstimated) " (estimated)" else ""}, ${tractor.category}",
                TextVariant.Small,
            )
        }
    }
}

@Composable
private fun ImplementsTab() {
    Txt("Indicative values, used to estimate duration and consumption.", TextVariant.Small)
    IMPLEMENT_CATALOG.forEach { item ->
        Panel {
            Txt(item.name, TextVariant.Subtitle)
            Txt("Widths ${item.widthsM.joinToString(", ") { jsNum(it) }} m, ${jsNum(item.speedKmh)} km/h, ${jsNum(item.hpPerMeter)} HP/m", TextVariant.Small)
        }
    }
}

@Composable
fun CatalogScreen(navigator: Navigator) {
    val auth = rememberAuth()
    var tab by remember { mutableStateOf("seeds") }
    var message by remember { mutableStateOf<String?>(null) }
    val importFile = rememberFilePicker(listOf("text/csv", "text/comma-separated-values", "text/plain")) { file ->
        val rows = parseCsv(file.bytes.decodeToString())
        val problems = mutableListOf<String>()
        var imported = 0
        rows.forEach { row ->
            if (tab == "tractors") {
                when (val result = tractorFromRow(row)) {
                    is RowResult.Ok -> { CatalogStore.saveTractor(result.value); imported++ }
                    is RowResult.Error -> problems.add(result.message)
                }
            } else {
                when (val result = seedFromRow(row)) {
                    is RowResult.Ok -> { CatalogStore.saveSeed(result.value); imported++ }
                    is RowResult.Error -> problems.add(result.message)
                }
            }
        }
        val skipped = if (problems.isNotEmpty()) "\n${problems.size} rows were skipped. First reason: ${problems.first()}" else ""
        message = "$imported products were added.$skipped"
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("Import complete") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }

    Screen(title = "Catalog") {
        Choice(listOf(ChoiceOption("seeds", "Seeds"), ChoiceOption("tractors", "Tractors"), ChoiceOption("implements", "Implements")), tab, { tab = it })
        if (auth.role == Role.ADMIN && tab != "implements") {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Button(onClick = { navigator.open(Routes.CatalogEditor(if (tab == "seeds") "seed" else "tractor")) }, modifier = Modifier.weight(1f)) { Text("Add") }
                OutlinedButton(onClick = importFile, modifier = Modifier.weight(1f)) { Text("Import CSV") }
            }
        }
        when (tab) {
            "seeds" -> SeedsTab(navigator)
            "tractors" -> TractorsTab(navigator)
            else -> ImplementsTab()
        }
    }
}

@Composable
private fun SeedDetails(seed: SeedEntry) {
    val parcels = rememberParcels().data.orEmpty()
    var parcelId by remember { mutableStateOf<String?>(null) }
    val parcel = parcels.firstOrNull { it.id == parcelId }
    val ranking = remember(parcel, seed) {
        parcel?.let {
            rankSeedsForParcel(
                CatalogStore.seeds, seed.crop, currentYear(),
                it.seasons.orEmpty().map { season -> CropHistoryEntry(season.crop, season.year) },
                irrigated = (it.irrigatedAreaHa ?: 0.0) > 0,
            )
        }
    }
    val match = ranking?.firstOrNull { it.seed.id == seed.id }
    val position = ranking?.indexOfFirst { it.seed.id == seed.id }?.plus(1) ?: 0

    Panel {
        Txt(seed.name, TextVariant.Title)
        InfoRow("Type", seed.type.label)
        InfoRow("Crop", seed.crop.profile.name)
        InfoRow("Manufacturer", seed.brand ?: MISSING)
        InfoRow("FAO group", seed.fao?.let { "${jsNum(it)}${seed.maturityGroup?.let { group -> ", ${group.lowercase()}" } ?: ""}" } ?: MISSING)
        InfoRow("Growing days", seed.vegetationDays?.let(::jsNum) ?: MISSING)
        InfoRow("Drought resistance", level(seed.droughtTolerance))
        InfoRow(
            "Expected yield",
            if (seed.expectedYieldMin != null && seed.expectedYieldMax != null) "${number(seed.expectedYieldMin!!)}-${number(seed.expectedYieldMax!!)} t/ha" else MISSING,
        )
        InfoRow("Recommended regions", seed.recommendedRegions.joinToString(", ").ifEmpty { MISSING })
        InfoRow("Water requirement", level(seed.waterRequirement))
        InfoRow("Soil compatibility", seed.soilPreference.joinToString(", ") { SOIL_LABELS.getValue(it) }.ifEmpty { MISSING })
        seed.notes?.let { Txt(it, TextVariant.Small) }
    }
    Section("Compatibility with the parcel") {
        Panel {
            ParcelPicker(parcelId, { parcelId = it })
            if (match != null) {
                InfoRow("Rank $position of ${ranking.size}", "${match.score}%")
                ProgressTrack(match.score.toDouble(), FarmColors.leaf)
                Txt(match.reason, TextVariant.Small)
            }
        }
    }
}

@Composable
private fun TractorDetails(tractor: TractorEntry) {
    Panel {
        Txt("${tractor.brand} ${tractor.model}", TextVariant.Title)
        InfoRow("Series", tractor.series ?: MISSING)
        InfoRow("Power", "${jsNum(tractor.horsePower)} HP")
        InfoRow("Consumption", "${number(tractor.fuelConsumptionMin)}-${number(tractor.fuelConsumptionMax)} l/hour")
        if (tractor.fuelEstimated) Txt("Consumption is estimated from power. Correct it using the data sheet.", TextVariant.Small)
        InfoRow("Tank", tractor.tankCapacity?.let { "${jsNum(it)} l" } ?: MISSING)
        InfoRow("Weight", tractor.weight?.let { "${jsNum(it)} kg" } ?: MISSING)
        InfoRow("Drive", tractor.driveSystem ?: MISSING)
        InfoRow("Category", tractor.category)
        Txt(tractor.recommendedUsage, TextVariant.Small)
    }
}

@Composable
fun CatalogDetailScreen(kind: String, id: String, navigator: Navigator) {
    val auth = rememberAuth()
    val seed = CatalogStore.rememberSeeds().firstOrNull { kind == "seed" && it.id == id }
    val tractor = CatalogStore.rememberTractors().firstOrNull { kind == "tractor" && it.id == id }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete from catalog") },
            text = { Text("The product will be removed from the farm catalog.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    CatalogStore.remove(kind, id)
                    navigator.back()
                }) { Text("Delete", color = FarmColors.rust) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    Screen(title = "Product sheet") {
        if (seed == null && tractor == null) {
            Empty(Icons.Outlined.SearchOff, "The product was not found")
            return@Screen
        }
        seed?.let { SeedDetails(it) }
        tractor?.let { TractorDetails(it) }
        if (auth.role == Role.ADMIN) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Button(onClick = { navigator.open(Routes.CatalogEditor(kind, id)) }, modifier = Modifier.weight(1f)) { Text("Edit") }
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) { Text("Delete", color = FarmColors.rust) }
            }
        }
    }
}

@Composable
private fun SeedForm(id: String?, onDone: () -> Unit) {
    val existing = CatalogStore.rememberSeeds().firstOrNull { it.id == id }
    var name by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var crop by remember { mutableStateOf<CropCode?>(null) }
    var type by remember { mutableStateOf(SeedType.HYBRID) }
    var fao by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("") }
    var yieldMin by remember { mutableStateOf("") }
    var yieldMax by remember { mutableStateOf("") }
    var water by remember { mutableStateOf<CatalogLevel?>(null) }
    var drought by remember { mutableStateOf<CatalogLevel?>(null) }
    var regions by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    LaunchedEffect(existing?.id) {
        val seed = existing ?: return@LaunchedEffect
        name = seed.name
        brand = seed.brand ?: ""
        crop = seed.crop
        type = seed.type
        fao = text(seed.fao)
        days = text(seed.vegetationDays)
        yieldMin = text(seed.expectedYieldMin)
        yieldMax = text(seed.expectedYieldMax)
        water = seed.waterRequirement
        drought = seed.droughtTolerance
        regions = seed.recommendedRegions.joinToString(", ")
        notes = seed.notes ?: ""
    }

    val numbersValid = listOf(fao, days, yieldMin, yieldMax).all(::validOptional)
    Panel {
        Field(name, { name = it }, "Hybrid or variety name")
        Field(brand, { brand = it }, "Manufacturer")
        Choice(CROP_CODES.map { ChoiceOption(it, it.profile.name) }, crop, { crop = it }, "Crop")
        Choice(SeedType.entries.map { ChoiceOption(it, it.label) }, type, { type = it }, "Type")
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Field(fao, { fao = it }, "FAO", Modifier.weight(1f), KeyboardType.Number)
            Field(days, { days = it }, "Growing days", Modifier.weight(1f), KeyboardType.Number)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Field(yieldMin, { yieldMin = it }, "Min yield (t/ha)", Modifier.weight(1f), KeyboardType.Decimal)
            Field(yieldMax, { yieldMax = it }, "Max yield (t/ha)", Modifier.weight(1f), KeyboardType.Decimal)
        }
        Choice(levelOptions(MISSING), water, { water = it }, "Water requirement")
        Choice(levelOptions(MISSING), drought, { drought = it }, "Drought tolerance")
        Field(regions, { regions = it }, "Recommended regions, comma-separated")
        Field(notes, { notes = it }, "Notes", singleLine = false)
        Button(
            onClick = {
                CatalogStore.saveSeed(
                    createSeedEntry(
                        name = name.trim(),
                        crop = crop!!,
                        type = type,
                        id = existing?.id,
                        brand = brand.trim().ifEmpty { null },
                        fao = toNumber(fao),
                        vegetationDays = toNumber(days),
                        expectedYieldMin = toNumber(yieldMin),
                        expectedYieldMax = toNumber(yieldMax),
                        waterRequirement = water,
                        droughtTolerance = drought,
                        diseaseResistance = existing?.diseaseResistance.orEmpty(),
                        soilPreference = existing?.soilPreference.orEmpty(),
                        recommendedRegions = regions.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        notes = notes.trim().ifEmpty { null },
                    ),
                )
                onDone()
            },
            enabled = name.isNotBlank() && crop != null && numbersValid,
        ) { Text("Save product") }
    }
}

@Composable
private fun TractorForm(id: String?, onDone: () -> Unit) {
    val existing = CatalogStore.rememberTractors().firstOrNull { it.id == id }
    var brand by remember { mutableStateOf("") }
    var series by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var power by remember { mutableStateOf("") }
    var fuelMin by remember { mutableStateOf("") }
    var fuelMax by remember { mutableStateOf("") }
    var tank by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }

    LaunchedEffect(existing?.id) {
        val tractor = existing ?: return@LaunchedEffect
        brand = tractor.brand
        series = tractor.series ?: ""
        model = tractor.model
        power = jsNum(tractor.horsePower)
        fuelMin = if (tractor.fuelEstimated) "" else jsNum(tractor.fuelConsumptionMin)
        fuelMax = if (tractor.fuelEstimated) "" else jsNum(tractor.fuelConsumptionMax)
        tank = text(tractor.tankCapacity)
        weight = text(tractor.weight)
    }

    val numbersValid = listOf(fuelMin, fuelMax, tank, weight).all(::validOptional)
    Panel {
        Field(brand, { brand = it }, "Brand")
        Field(series, { series = it }, "Series")
        Field(model, { model = it }, "Model")
        Field(power, { power = it }, "Power (HP)", keyboard = KeyboardType.Decimal)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Field(fuelMin, { fuelMin = it }, "Min consumption (l/hour)", Modifier.weight(1f), KeyboardType.Decimal)
            Field(fuelMax, { fuelMax = it }, "Max consumption (l/hour)", Modifier.weight(1f), KeyboardType.Decimal)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Field(tank, { tank = it }, "Tank (l)", Modifier.weight(1f), KeyboardType.Decimal)
            Field(weight, { weight = it }, "Weight (kg)", Modifier.weight(1f), KeyboardType.Decimal)
        }
        Button(
            onClick = {
                CatalogStore.saveTractor(
                    createTractorEntry(
                        brand = brand.trim(),
                        model = model.trim(),
                        horsePower = parseNumber(power)!!,
                        id = existing?.id,
                        series = series.trim().ifEmpty { null },
                        fuelConsumptionMin = toNumber(fuelMin),
                        fuelConsumptionMax = toNumber(fuelMax),
                        tankCapacity = toNumber(tank),
                        weight = toNumber(weight),
                    ),
                )
                onDone()
            },
            enabled = brand.isNotBlank() && model.isNotBlank() && isPositive(power) && numbersValid,
        ) { Text("Save tractor") }
    }
}

@Composable
fun CatalogEditorScreen(kind: String, id: String?, navigator: Navigator) {
    Screen(title = if (id == null) "Add to catalog" else "Edit product") {
        if (kind == "seed") SeedForm(id) { navigator.back() } else TractorForm(id) { navigator.back() }
    }
}

