package ro.fermapp.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import ro.fermapp.domain.copilot.TaskCategory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Tillage, weather brief, calendar and satellite tests, ported from TypeScript. */

class TillageTest {
    @Test
    fun estimatesCostFuelAndSavingsAgainstConventionalTillage() {
        val estimate = estimateParcelTillage(TillageSystem.NO_TILL, 10.0)
        assertEquals(2500.0, estimate.totalCost)
        assertEquals(2700.0, estimate.costSavings)
        assertEquals(120.0, estimate.seasonFuelL)
        assertEquals(480.0, estimate.annualFuelSavingsL)
        assertEquals(3, estimate.passesSaved)
    }

    @Test
    fun usesTheUsersOwnFiguresWhenTheyAreSet() {
        val settings = mapOf(TillageSystem.MINIMUM_TILL to TillageSetting(costPerHa = 400.0), TillageSystem.CONVENTIONAL to TillageSetting(fuelLPerHa = 70.0))
        val estimate = estimateParcelTillage(TillageSystem.MINIMUM_TILL, 10.0, settings)
        assertEquals(4000.0, estimate.totalCost)
        assertEquals(350.0, estimate.annualFuelSavingsL)
    }

    @Test
    fun countsEverySeasonOfTheYearInTheAnnualFigures() {
        val estimate = estimateParcelTillage(TillageSystem.STRIP_TILL, 10.0, emptyMap(), 2)
        assertEquals(250.0, estimate.seasonFuelL)
        assertEquals(500.0, estimate.annualFuelL)
    }

    @Test
    fun warnsAboutOperationsThatContradictTheTechnology() {
        assertFalse(checkWorkCompatibility(TillageSystem.NO_TILL, "PLOWING").compatible)
        assertFalse(checkWorkCompatibility(TillageSystem.MINIMUM_TILL, "PLOWING").compatible)
        assertTrue(checkWorkCompatibility(TillageSystem.MINIMUM_TILL, "DISCING").compatible)
        assertTrue(checkWorkCompatibility(TillageSystem.CONVENTIONAL, "PLOWING").compatible)
        assertTrue(checkWorkCompatibility(null, "PLOWING").compatible)
        assertContains(checkTaskCompatibility(TillageSystem.NO_TILL, "SOIL_PREP").message!!, "No Till")
    }

    @Test
    fun checksMachinesAgainstTheirOwnListFirstThenTheTypeDefaults() {
        assertEquals(
            CompatibilityCheck(false, "Drill is not recommended for No Till."),
            checkMachineCompatibility(TillageMachine("SEEDER", name = "Drill"), TillageSystem.NO_TILL),
        )
        assertTrue(checkMachineCompatibility(TillageMachine("SEEDER", listOf("NO_TILL")), TillageSystem.NO_TILL).compatible)
        assertEquals(TILLAGE_LIST.size, machineTillageSystems(TillageMachine("TRACTOR")).size)
    }

    @Test
    fun datesTheRecommendedOperationsFromTheFirstOne() {
        val plan = planTillageOperations(TillageSystem.CONVENTIONAL, Instant.parse("2026-03-01T12:00:00Z"))
        assertEquals(listOf("plowing", "discing", "seedbed", "sowing"), plan.map { it.operation.key })
        assertEquals("2026-03-22", plan[3].dueDate.toJsIsoString().take(10))
        assertEquals(1, planTillageOperations(TillageSystem.NO_TILL, Clock.System.now()).size)
    }

    @Test
    fun givesAdviceForEachTechnology() {
        assertContains(tillageRecommendations(TillageSystem.CONVENTIONAL).first().message, "compaction")
        assertTrue(tillageRecommendations(TillageSystem.STRIP_TILL, CropCode.WHEAT).any { it.tone == AdviceTone.WARNING })
    }

    @Test
    fun analysesTheFarmByTechnology() {
        val analysis = analyzeTillage(
            listOf(
                ParcelTillageInput(10.0, TillageSystem.CONVENTIONAL),
                ParcelTillageInput(30.0, TillageSystem.NO_TILL),
                ParcelTillageInput(5.0, null),
            ),
        )
        assertEquals(45.0, analysis.totalAreaHa)
        assertEquals(5.0, analysis.unassignedAreaHa)
        assertEquals(30.0, analysis.bySystem.first { it.system == TillageSystem.NO_TILL }.areaHa)
        assertEquals(30.0 * 48, analysis.fuelSavingsL)
        assertEquals(83.0, analysis.avgSoilHealth)
        assertEquals(56.0, analysis.passReductionPct)
    }

    @Test
    fun keepsOneHistoryEntryPerYearAndReadsTheTechnologyOfAnyYear() {
        var history = withTillageYear(emptyList(), TillageHistoryEntry(2025, TillageSystem.MINIMUM_TILL))
        history = withTillageYear(history, TillageHistoryEntry(2024, TillageSystem.CONVENTIONAL))
        history = withTillageYear(history, TillageHistoryEntry(2025, TillageSystem.STRIP_TILL))
        assertEquals(listOf(TillageHistoryEntry(2024, TillageSystem.CONVENTIONAL), TillageHistoryEntry(2025, TillageSystem.STRIP_TILL)), history)
        val parcel = ParcelTillageInput(10.0, TillageSystem.STRIP_TILL, history)
        assertNull(tillageInYear(parcel, 2023))
        assertEquals(TillageSystem.STRIP_TILL, tillageInYear(parcel, 2027))
        val timeline = tillageTimeline(listOf(parcel), listOf(2024, 2025))
        assertEquals(60.0, timeline[0].avgSoilHealth)
        assertEquals(82.0, timeline[1].avgSoilHealth)
        val raw = Json.parseToJsonElement("""[{"year":2024,"system":"UNKNOWN"},{"year":2023,"system":"NO_TILL"}]""")
        assertEquals(listOf(TillageHistoryEntry(2023, TillageSystem.NO_TILL)), parseTillageHistory(raw))
    }
}

class WeatherBriefTest {
    private val now = "2026-06-10T10:00"

    /** Hours from 24 h before NOW to 5 days after, with calm, mild weather unless overridden. */
    private fun series(override: (BriefHour, Int) -> BriefHour = { h, _ -> h }): List<BriefHour> {
        val start = LocalDateTime(2026, 6, 9, 10, 0).toInstant(TimeZone.UTC)
        return (0 until 24 * 7).map { i ->
            val base = BriefHour(
                time = (start + i.hours).toJsIsoString().take(16),
                temperatureC = 18.0, humidityPct = 60.0, precipitationMm = 0.0, precipitationProbabilityPct = 5.0,
                windKmh = 8.0, weatherCode = 1, gustKmh = 15.0, et0Mm = 0.05,
            )
            override(base, i - 24)
        }
    }

    private val days = listOf("2026-06-10", "2026-06-11", "2026-06-12", "2026-06-13", "2026-06-14", "2026-06-15").map { BriefDay(it, 24.0, 11.0, 0.0) }

    private fun brief(hours: List<BriefHour> = series(), current: BriefCurrent? = null) = buildWeatherBrief(WeatherBriefInput(hours, days, current, now))!!

    @Test
    fun mapsWmoCodes() {
        assertEquals(ConditionKind.CLEAR, weatherCondition(0).kind)
        assertEquals(ConditionKind.HEAVY_RAIN, weatherCondition(65).kind)
        assertEquals(ConditionKind.HAIL, weatherCondition(96).kind)
        assertEquals("Cloudy", weatherCondition(null).label)
    }

    @Test
    fun describesTimesRelativeToNow() {
        assertEquals("now", relativeTime("2026-06-10T10:00", now))
        assertEquals("today at 15:00", relativeTime("2026-06-10T15:00", now))
        assertEquals("tomorrow at 03:00", relativeTime("2026-06-11T03:00", now))
        assertEquals("on Sat", relativeTime("2026-06-13T12:00", now))
    }

    @Test
    fun summarisesTodayAndTheNextFiveDays() {
        val brief = brief()
        assertEquals(18.0, brief.current.temperatureC)
        assertEquals(11.0, brief.today!!.tMinC)
        assertEquals(24.0, brief.today!!.tMaxC)
        assertEquals(listOf("Thu", "Fri", "Sat", "Sun", "Mon"), brief.days.map { it.weekday })
        assertEquals(emptyList(), brief.alerts)
    }

    @Test
    fun recommendsSprayingInCalmDryWeather() {
        val brief = brief()
        assertContains(brief.insights.map { it.code }, InsightCode.SPRAY_GOOD)
        assertEquals("Low rain probability. Good conditions for spraying.", brief.insights.first().message)
    }

    @Test
    fun advisesAgainstSprayingInStrongWind() {
        val brief = brief(current = BriefCurrent(now, 18.0, 60.0, 24.0, 35.0, 2, true))
        assertEquals(InsightCode.SPRAY_WIND, brief.insights.first().code)
        assertEquals(InsightTone.WARNING, brief.insights.first().tone)
        assertTrue(brief.insights.none { it.code == InsightCode.SPRAY_GOOD })
    }

    @Test
    fun announcesRainWithin24Hours() {
        val brief = brief(series { h, offset -> if (offset == 12) h.copy(precipitationMm = 3.0, precipitationProbabilityPct = 80.0, weatherCode = 63) else h })
        assertEquals("Rain expected within 24 hours (about 3 mm).", brief.insights.first { it.code == InsightCode.RAIN_SOON }.message)
    }

    @Test
    fun flagsWetSoilsAfterHeavyRain() {
        val brief = brief(series { h, offset -> if (offset < 0 && offset >= -6) h.copy(precipitationMm = 3.0) else h.copy(humidityPct = 90.0, et0Mm = 0.02) })
        assertTrue(brief.insights.any { it.code == InsightCode.WET_SOIL })
    }

    @Test
    fun reportsGoodSoilDryingConditions() {
        assertTrue(brief(series { h, _ -> h.copy(et0Mm = 0.2) }).insights.any { it.code == InsightCode.SOIL_DRYING })
    }

    @Test
    fun raisesStormFrostWindHeatAndHeavyRainAlerts() {
        val storm = brief(series { h, o -> if (o == 30) h.copy(weatherCode = 95) else h }).alerts.first()
        assertEquals(FieldAlertType.STORM, storm.type)
        assertEquals(FieldAlertSeverity.SEVERE, storm.severity)
        assertEquals("Thunderstorm expected tomorrow at 16:00.", storm.message)

        val frost = brief(series { h, o -> if (o == 18) h.copy(temperatureC = -3.0) else h }).alerts.first()
        assertEquals(FieldAlertType.FROST, frost.type)
        assertEquals(FieldAlertSeverity.SEVERE, frost.severity)

        val wind = brief(series { h, o -> if (o == 5) h.copy(windKmh = 35.0, gustKmh = 65.0) else h }).alerts.first()
        assertEquals(FieldAlertType.WIND, wind.type)
        assertEquals(FieldAlertSeverity.WARNING, wind.severity)
        assertEquals("Gusts up to 65 km/h today at 15:00.", wind.message)

        val heat = brief(series { h, o -> if (o == 50) h.copy(temperatureC = 36.0) else h }).alerts.first()
        assertEquals(FieldAlertType.HEAT, heat.type)
        assertEquals(FieldAlertSeverity.SEVERE, heat.severity)

        val rain = brief(series { h, o -> if (o in 20 until 26) h.copy(precipitationMm = 5.0) else h }).alerts.first()
        assertEquals(FieldAlertType.HEAVY_RAIN, rain.type)
        assertEquals(FieldAlertSeverity.WARNING, rain.severity)
        assertContains(rain.message, "30 mm")
    }

    @Test
    fun doesNotPaintAWholeDayStormyForOneThunderHour() {
        val brief = brief(series { h, o -> if (o == 28) h.copy(weatherCode = 95) else if (o == 29) h.copy(weatherCode = 61) else h })
        assertEquals(ConditionKind.RAIN, brief.days.first().condition.kind)
    }
}

class CalendarTest {
    private fun task(
        id: String, day: String, type: String = "HERBICIDE", parcelId: String? = "p1", priority: CalendarPriority = CalendarPriority.MEDIUM,
        assigneeId: String? = null, machineId: String? = null,
    ) = CalendarTask(id, id, type, parcelId, priority, TaskStatus.TODO, day, assigneeId, machineId)

    @Test
    fun buildsWeekMonthAndSeasonRanges() {
        assertEquals(listOf("2026-06-08", "2026-06-09", "2026-06-10", "2026-06-11", "2026-06-12", "2026-06-13", "2026-06-14"), weekDays("2026-06-10"))
        val grid = monthGrid("2026-06-10")
        assertEquals("2026-06-01", grid[0][0])
        assertTrue(grid.all { it.size == 7 })
        assertContains(grid.flatten(), "2026-06-30")
        assertEquals(SeasonRange("2025-09-01", "2026-08-31", "2025/26"), seasonRange("2026-06-10"))
    }

    @Test
    fun coloursOperationsByCategory() {
        assertEquals(TaskCategory.TILLAGE, categoryOf("PLOWING"))
        assertEquals(TaskCategory.PLANTING, categoryOf("SOWING"))
        assertEquals(TaskCategory.CROP_PROTECTION, categoryOf("FUNGICIDE"))
        assertEquals(TaskCategory.HARVEST, categoryOf("HARVESTING"))
        assertEquals(TaskCategory.IRRIGATION, categoryOf("PIVOT"))
    }

    @Test
    fun detectsDoubleBookedMachinesAndOperatorsAndMissingMachineTypes() {
        val conflicts = detectConflicts(
            tasks = listOf(
                task("a", "2026-06-10", machineId = "m1", assigneeId = "u1", parcelId = "p1"),
                task("b", "2026-06-10", machineId = "m1", assigneeId = "u1", parcelId = "p2"),
                task("c", "2026-06-11", type = "SOWING", parcelId = "p1"),
                task("d", "2026-06-11", type = "SOWING", parcelId = "p2"),
            ),
            machines = listOf(Resource("m1", "Fendt", "SPRAYER"), Resource("m2", "Seeder 1", "SEEDER")),
            members = listOf(Resource("u1", "Ion", "OPERATOR")),
        )
        assertEquals(listOf(ConflictKind.MACHINE, ConflictKind.OPERATOR, ConflictKind.CAPACITY), conflicts.map { it.kind })
        assertContains(conflicts[2].reason, "2 fields need a seeder")
    }

    @Test
    fun warnsAboutWeatherForTheOperation() {
        val days = listOf(
            CalendarDay("2026-06-10", 12.0, 24.0, 0.0, 5.0, 30.0),
            CalendarDay("2026-06-11", 12.0, 24.0, 6.0, 80.0, 8.0),
            CalendarDay("2026-06-12", 12.0, 24.0, 0.0, 5.0, 8.0),
            CalendarDay("2026-06-13", 12.0, 24.0, 14.0, 90.0, 8.0),
        )
        assertEquals("Spraying not recommended", weatherWarnings("HERBICIDE", "2026-06-10", days).first().title)
        assertEquals("Delay herbicide application", weatherWarnings("HERBICIDE", "2026-06-11", days).first().title)
        assertEquals("Harvest risk", weatherWarnings("HARVEST", "2026-06-13", days).first().title)
        assertEquals("2026-06-12", bestDayFor("HERBICIDE", "2026-06-10", days))
    }

    @Test
    fun summarisesThePlanningDashboardAndTheFieldTimeline() {
        val tasks = listOf(task("late", "2026-06-08"), task("now", "2026-06-10", machineId = "m1", priority = CalendarPriority.HIGH), task("later", "2026-06-12"))
        val dashboard = calendarDashboard(tasks, emptyList(), listOf(Resource("m1", "A", "TRACTOR"), Resource("m2", "B", "TRACTOR")), emptyList(), "2026-06-10")
        assertEquals(listOf("late"), dashboard.delayed.map { it.id })
        assertEquals(listOf("now"), dashboard.today.map { it.id })
        assertEquals(listOf("m2"), dashboard.availableMachines.map { it.id })
        assertEquals(listOf("now"), dashboard.critical.map { it.id })
        val timeline = fieldTimeline(
            "p1", tasks, emptyList(), listOf(TimelineSeason(2026, "Corn", "2026-04-20", null)),
            from = "2026-01-01", to = "2026-12-31", today = "2026-06-10",
        )
        assertEquals(listOf(TimelineState.DONE, TimelineState.OVERDUE, TimelineState.PLANNED, TimelineState.PLANNED), timeline.map { it.state })
    }
}

class SatelliteTest {
    private val square = listOf(Position(26.3, 44.55), Position(26.31, 44.55), Position(26.31, 44.56), Position(26.3, 44.56), Position(26.3, 44.55))

    private fun obs(date: String, ndvi: Double, ndmi: Double? = 0.1, zones: List<ZoneObservation> = emptyList()) =
        FieldObservation(date, date, 0.0, IndexStats(ndvi, 0.05, ndvi - 0.1, ndvi + 0.1), 0.4, ndmi, zones)

    @Test
    fun splitsAFieldIntoNamedZonesClippedToItsOutline() {
        val zones = fieldZones(square, 80.0)
        assertEquals(9, zones.size)
        assertContains(zones.map { it.name }, "north-west")
        assertTrue(abs(1 - zones.sumOf { it.share }) < 0.005)
        val triangle = fieldZones(listOf(Position(26.3, 44.55), Position(26.31, 44.55), Position(26.3, 44.56), Position(26.3, 44.55)), 40.0)
        assertTrue(triangle.none { it.name == "north-east" })
    }

    @Test
    fun scoresFieldHealthAndColoursNdvi() {
        assertEquals(HealthLevel.EXCELLENT, fieldHealth(obs("2026-06-10", 0.8), true).level)
        assertEquals(HealthLevel.CRITICAL, fieldHealth(obs("2026-06-10", 0.45, ndmi = -0.25), true).level)
        assertEquals(HealthLevel.NO_CROP, fieldHealth(obs("2026-06-10", 0.15), false).level)
        assertEquals(listOf("#5FB35A", "#E2C23D", "#E9883B", "#E0584A"), listOf(ndviColor(0.7), ndviColor(0.5), ndviColor(0.3), ndviColor(0.1)))
    }

    @Test
    fun explainsWeakZonesNutrientAndWaterStressAndProposesTasks() {
        val zones = fieldZones(square, 80.0)
        val north = zones.first { it.name == "north" }
        val west = zones.first { it.name == "west" }
        val east = zones.first { it.name == "east" }
        val current = obs(
            "2026-06-10", 0.7,
            zones = listOf(
                ZoneObservation(north.id, 0.5, 0.35, 0.1),
                ZoneObservation(west.id, 0.69, 0.3, 0.1),
                ZoneObservation(east.id, 0.7, 0.4, -0.05),
            ),
        )
        val findings = analyzeField(FieldContext("Field 12", "corn", 50.0, true), current, emptyList(), zones)
        assertContains(findings.first { it.kind == FindingKind.LOW_VIGOR }.message, "North section of Field 12 shows reduced vegetation vigor")
        assertEquals("NITROGEN", findings.first { it.kind == FindingKind.NUTRIENT }.task!!.type)
        assertEquals("IRRIGATION_CHECK", findings.first { it.kind == FindingKind.WATER_STRESS }.task!!.type)
    }

    @Test
    fun separatesDeclineFromRipeningAndReportsBareFields() {
        val earlier = listOf(obs("2026-06-01", 0.75))
        val decline = analyzeField(FieldContext("F", "corn", 50.0, false), obs("2026-06-12", 0.55), earlier, emptyList())
        assertEquals(FindingKind.DECLINE, decline.first().kind)
        val ripe = analyzeField(FieldContext("F", "wheat", 92.0, false), obs("2026-06-12", 0.55), earlier, emptyList())
        assertEquals(listOf(FindingKind.RIPENING), ripe.map { it.kind })
        assertEquals(FindingKind.NO_CROP, analyzeField(FieldContext("F", null, null, false), obs("2026-06-12", 0.18), emptyList(), emptyList()).first().kind)
    }

    @Test
    fun comparesWith7_30_90DaysAndLastYear() {
        val comparison = compareHistory(
            listOf(
                HistoryPoint("2026-06-10", 0.7),
                HistoryPoint("2026-06-03", 0.65),
                HistoryPoint("2026-05-12", 0.5),
                HistoryPoint("2026-03-15", 0.3),
                HistoryPoint("2025-06-08", 0.6),
            ),
        )
        assertEquals("2026-06-03", comparison.d7!!.date)
        assertEquals(0.05, comparison.d7!!.delta)
        assertEquals("2026-05-12", comparison.d30!!.date)
        assertEquals("2026-03-15", comparison.d90!!.date)
        assertEquals("2025-06-08", comparison.yearAgo!!.date)
        assertEquals(0.1, comparison.yearAgo!!.delta)
    }
}
