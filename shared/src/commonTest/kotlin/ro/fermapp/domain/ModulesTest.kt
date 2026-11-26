package ro.fermapp.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The remaining TypeScript domain test files, ported one to one. */

private fun close(expected: Double, actual: Double, digits: Int = 2) =
    assertTrue(abs(expected - actual) < 0.5 * 10.0.pow(-digits), "expected $expected but was $actual")

private val local = TimeZone.currentSystemDefault()

/** `new Date(year, monthIndex, day, hour)` in local time. */
private fun localDate(year: Int, monthIndex: Int, day: Int, hour: Int = 0, minute: Int = 0): Instant {
    val first = LocalDate(year, monthIndex + 1, 1)
    val date = LocalDate.fromEpochDays(first.toEpochDays() + day - 1)
    return date.atTime(hour, minute).toInstant(local)
}

class EconomicsTest {
    @Test
    fun euroFormattingUsesDotsForThousandsAndACommaForDecimals() {
        assertEquals("€1.250", formatEuro(1250.0))
        assertEquals("€12.450,50", formatEuro(12450.5))
        assertEquals("€120/ha", formatEuroPerHa(120.0))
        assertEquals("€366,67", formatEuro(366.666, 2))
        assertEquals("-€40", formatEuro(-40.0))
        assertEquals("-", formatEuro(null))
    }

    @Test
    fun readsAmountsRecordedInLeiAsEuro() {
        assertEquals(100.0, toEuro(497.0, "RON"))
        assertEquals(100.0, toEuro(100.0, "EUR"))
    }

    @Test
    fun pricesSeedSoldByCountAndByWeight() {
        val corn = seedCost(SeedLine("a", "Pioneer", "P9911", unit = SeedUnit.SEEDS, bagSize = 80_000.0, ratePerHa = 70_000.0, pricePerBag = 180.0), 10.0)
        assertEquals(157.5, corn.costPerHa)
        assertEquals(8.8, corn.bagsNeeded)
        assertEquals(2.25, corn.costPer1000Seeds)
        val wheat = seedCost(SeedLine("b", "Own", "Glosa", unit = SeedUnit.KG, bagSize = 25.0, ratePerHa = 220.0, pricePerBag = 18.0, bagsUsed = 90.0), 10.0)
        assertEquals(0.72, wheat.costPerKg)
        assertEquals(1620.0, wheat.total)
    }

    @Test
    fun pricesFertiliserPerTonneAndCropProtectionPerLitre() {
        val urea = fertilizerCost(FertilizerLine("u", "Urea", 150.0, 430.0, applications = 2.0, n = 46.0), 10.0)
        assertEquals(129.0, urea.costPerHa)
        assertEquals(138, urea.nutrients.n)
        val herbicide = chemicalCost(ChemicalLine("h", "Adengo", ChemicalKind.HERBICIDE, 0.44, DoseUnit.L, 95.0), 10.0)
        assertEquals(41.8, herbicide.costPerHa)
        assertEquals(418.0, herbicide.total)
    }

    @Test
    fun turnsOwnershipAndUpkeepIntoACostPerHour() {
        val cost = machineHourlyCost(MachineCostProfile(150_000.0, 20.0, 10.0, 600.0, 3000.0, 1800.0))
        assertEquals(20.0, cost.depreciation)
        assertEquals(28.0, cost.perHour)
        val combine = machineHourlyCost(null, "COMBINE")
        assertEquals(120.0, combine.perHour)
        assertTrue(combine.estimated)
    }

    @Test
    fun derivesTheHourlyRateFromTheMonthlySalary() {
        assertEquals(10.0, laborHourlyRate(LaborProfile(monthlySalary = 1680.0)))
        assertEquals(14.0, laborHourlyRate(LaborProfile(monthlySalary = 1680.0, hourlyRate = 14.0)))
        assertEquals(9.0, laborHourlyRate(null, 9.0))
    }

    @Test
    fun sharesTheTechnologysDieselBetweenItsOperations() {
        assertTrue(fuelPerHaFor("PLOWING", TillageSystem.CONVENTIONAL) > fuelPerHaFor("SOWING", TillageSystem.CONVENTIONAL))
        assertEquals(12.0, fuelPerHaFor("SOWING", TillageSystem.NO_TILL))
    }

    private val sheet = EconomicSheet(
        seeds = listOf(SeedLine("s", "Pioneer", "P9911", unit = SeedUnit.SEEDS, bagSize = 80_000.0, ratePerHa = 70_000.0, pricePerBag = 180.0)),
        fertilizers = listOf(FertilizerLine("f", "Urea", 150.0, 430.0, applications = null)),
        chemicals = listOf(ChemicalLine("c", "Adengo", ChemicalKind.HERBICIDE, 0.44, DoseUnit.L, 95.0, applications = null)),
        misc = listOf(MiscLine("m", "Insurance", 15.0, MiscBasis.PER_HA)),
        revenue = RevenueInputs(expectedYieldTHa = 9.0, pricePerT = 220.0),
    )

    private val ploughing = OperationInput(
        id = "w1", type = "PLOWING", status = OperationStatus.COMPLETED, areaHa = 12.0, progressPct = 100.0, hours = 10.0, fuelLiters = 240.0,
        machine = OperationMachine("t1", "JD 6155R", "TRACTOR", 24.0), operator = OperationOperator("u1", "Ion"), startedAt = "2026-03-01T08:00:00Z",
    )

    private val result = computeParcelEconomics(
        ParcelEconomicsInput(12.0, sheet, listOf(ploughing), defaultSettings(), TillageSystem.CONVENTIONAL, CropCode.CORN),
    )

    @Test
    fun addsInputsLoggedWorkAndTheOperationsStillToCome() {
        assertEquals(1890.0, result.toDate.byCategory[EconomicCategory.SEEDS])
        assertEquals(348.0, result.toDate.byCategory[EconomicCategory.FUEL])
        assertEquals(100.0, result.toDate.byCategory[EconomicCategory.LABOR])
        assertEquals(350.0, result.toDate.byCategory[EconomicCategory.MACHINERY])
        assertTrue("PLOWING" !in result.projected.map { it.type })
        assertContains(result.projected.map { it.type }, "HARVESTING")
        assertTrue(result.season.total > result.toDate.total)
        close(result.season.total / 12, result.season.perHa, 1)
    }

    @Test
    fun computesRevenueProfitPerHectareAndMargin() {
        assertEquals(1980.0, result.expected!!.revenuePerHa)
        close(1980 - result.season.perHa, result.expected!!.profitPerHa, 1)
        assertNull(result.actual)
    }

    @Test
    fun matchesTheProfitabilityExample() {
        val simple = computeParcelEconomics(
            ParcelEconomicsInput(
                1.0,
                EconomicSheet(misc = listOf(MiscLine("all", "All costs", 620.0, MiscBasis.TOTAL)), revenue = RevenueInputs(expectedYieldTHa = 9.0, pricePerT = 220.0)),
                listOf(ploughing.copy(type = "SOWING", areaHa = 1.0, hours = 0.0, fuelLiters = 0.0, machine = null, operator = null)),
                defaultSettings().copy(defaultLaborRate = 0.0),
            ),
        )
        val operationsCost = (simple.operations + simple.projected).sumOf { it.total }
        assertEquals(1980.0, simple.expected!!.revenuePerHa)
        close(620 + operationsCost, simple.season.perHa, 1)
        close(1360 - operationsCost, simple.expected!!.profitPerHa, 1)
    }

    @Test
    fun explainsWhereTheMoneyGoesAndWhatIsCheaper() {
        val insights = economicInsights(result, TillageSystem.CONVENTIONAL)
        assertTrue(insights.any { it.message.contains("Minimum Till") && it.message.contains("%") })
        assertTrue(insights.any { it.message.contains("most expensive operation") })
    }

    @Test
    fun ranksParcelsCropsAndTechnologiesAcrossTheFarm() {
        val other = computeParcelEconomics(
            ParcelEconomicsInput(20.0, sheet.copy(revenue = RevenueInputs(expectedYieldTHa = 6.0, pricePerT = 210.0)), emptyList(), defaultSettings(), TillageSystem.NO_TILL, CropCode.WHEAT),
        )
        val farm = summarizeFarmEconomics(
            listOf(
                FarmParcelEconomics("a", "North", CropCode.CORN, "Corn", TillageSystem.CONVENTIONAL, result),
                FarmParcelEconomics("b", "South", CropCode.WHEAT, "Wheat", TillageSystem.NO_TILL, other),
            ),
        )
        assertEquals(32.0, farm.areaHa)
        assertEquals(2, farm.byTillage.size)
        assertEquals("North", farm.mostProfitableParcel?.label)
        assertEquals("Corn", farm.mostProfitableCrop?.label)
        assertNotNull(farm.mostEfficientTillage)
    }

    @Test
    fun storedDocumentsKeepOnlyWellFormedLinesAndFillTheDefaults() {
        val raw = Json.parseToJsonElement("""{"seeds":[{"supplier":"X","unit":"KG","bagSize":25,"ratePerHa":200,"pricePerBag":20},"bad"],"revenue":{"pricePerT":"x"}}""")
        val parsed = parseSheet(raw)
        assertEquals(1, parsed.seeds.size)
        assertNull(parsed.revenue.pricePerT)
        assertEquals(1.45, parseSettings(null).fuelPricePerL)
    }
}

class FieldCoverageTest {
    /** A rectangle of about widthM x heightM around a point in Romania. */
    private fun rectangle(widthM: Double, heightM: Double): List<Position> {
        val lat = 45.0
        val lng = 25.0
        val dLat = heightM / 110_540
        val dLng = widthM / (111_320 * cos(lat * PI / 180))
        return listOf(Position(lng, lat), Position(lng + dLng, lat), Position(lng + dLng, lat + dLat), Position(lng, lat + dLat), Position(lng, lat))
    }

    /** An L-shaped (concave) parcel. */
    private val lShape = listOf(
        Position(25.0, 45.0), Position(25.006, 45.0), Position(25.006, 45.002), Position(25.003, 45.002),
        Position(25.003, 45.004), Position(25.0, 45.004), Position(25.0, 45.0),
    )

    private val field = rectangle(500.0, 400.0)
    private val plan = buildFieldPlan(field, 6.0)!!

    @Test
    fun laysOutOnePassPerWorkingWidthLineAfterLine() {
        assertEquals(67, plan.passes.size)
        close(400.0 / 67, plan.stripM, 1)
        assertEquals(-sign(plan.passes[1].toX - plan.passes[1].fromX), sign(plan.passes[0].toX - plan.passes[0].fromX))
        close(67.0 * 500, plan.workingM, -2)
    }

    @Test
    fun coversTheParcelAreaInStepWithTheProgress() {
        for (percent in listOf(0.0, 25.0, 50.0, 75.0)) close(percent, fieldCoverageAt(plan, percent).coveredPct, 0)
        assertTrue(fieldCoverageAt(plan, 100.0).coveredPct > 99.5)
        assertEquals(0, fieldCoverageAt(plan, 0.0).worked.size)
    }

    @Test
    fun keepsTheMachineInsideTheParcelWhileWorkingAndTurnsItAtTheHeadland() {
        val working = fieldPositionAt(plan, 37.0)
        if (!working.turning) assertTrue(pointInRing(Position(working.lng, working.lat), field))
        val headings = listOf(10.0, 11.0, 12.0, 13.0).map { jsRoundInt(fieldPositionAt(plan, it).headingDeg / 90) % 4 }.toSet()
        assertTrue(headings.size > 1)
        assertTrue(fieldPositionAt(plan, 100.0).finished)
    }

    @Test
    fun clipsTheWorkedAreaToConcaveOutlines() {
        val lPlan = buildFieldPlan(lShape, 12.0)!!
        val half = fieldCoverageAt(lPlan, 50.0)
        assertTrue(half.coveredPct > 40 && half.coveredPct < 60)
        assertTrue(fieldCoverageAt(lPlan, 100.0).coveredPct > 98)
    }

    @Test
    fun drawsAShortTrailBehindTheMachine() = assertTrue(fieldCoverageAt(plan, 30.0, 80.0).trail.size >= 2)

    @Test
    fun estimatesTheDurationFromAreaSpeedAndWorkingWidth() {
        val timing = workTiming(20.0, 10.0, 6.0)
        assertEquals(6.0, timing.capacityHaH)
        close(3.33, timing.totalHours, 2)
    }
}

class FieldStatusTest {
    private val now = localDate(2026, 4, 12, 10, 0)
    private fun day(offset: Int) = localDate(2026, 4, 12 + offset, 12).toJsIsoString()
    private fun task(id: String, offset: Int, status: TaskStatus = TaskStatus.TODO, type: String = "FERTILIZATION") = FieldTask(id, id, type, status, day(offset))

    @Test
    fun doesNotTurnGreenAfterOneOperationWhileMoreWorkIsScheduled() {
        val status = fieldStatus(listOf(task("fertilising", 0, TaskStatus.DONE), task("herbicide", 1), task("monitoring", 2)), 0, now)
        assertEquals(FieldState.SCHEDULED, status.state)
        assertEquals("2 scheduled activities", status.badge)
        assertEquals(33, status.completionScore)
    }

    @Test
    fun turnsGreenWhenEverythingPlannedIsDone() {
        val status = fieldStatus(listOf(task("a", -1, TaskStatus.DONE), task("b", 0, TaskStatus.DONE), task("c", 1, TaskStatus.DONE)), 0, now)
        assertEquals(FieldState.COMPLETE, status.state)
        assertEquals("All work completed", status.badge)
        assertEquals(100, status.completionScore)
    }

    @Test
    fun isGreenWithNothingPlannedInTheActivePeriod() {
        val status = fieldStatus(listOf(task("far", 30)), 0, now)
        assertEquals(FieldState.COMPLETE, status.state)
        assertEquals(1, status.later)
        assertNull(fieldStatus(emptyList(), 0, now).completionScore)
    }

    @Test
    fun ranksOverdueActiveDueTodayAndScheduled() {
        assertEquals(FieldState.OVERDUE, fieldStatus(listOf(task("late", -2), task("today", 0)), 1, now).state)
        assertEquals(FieldState.ACTIVE, fieldStatus(listOf(task("today", 0)), 1, now).state)
        assertEquals(FieldState.DUE_TODAY, fieldStatus(listOf(task("today", 0)), 0, now).state)
        assertEquals("Work scheduled tomorrow", fieldStatus(listOf(task("tomorrow", 1)), 0, now).badge)
    }

    @Test
    fun summarisesTheFarm() {
        val summary = summarizeFieldStatus(
            listOf(
                ParcelFieldStatus("a", "North", fieldStatus(listOf(task("x", 0, TaskStatus.DONE)), 0, now)),
                ParcelFieldStatus("b", "South", fieldStatus(listOf(task("y", -1), task("z", 3)), 0, now)),
            ),
        )
        assertEquals(1, summary.complete)
        assertEquals(1, summary.overdue)
        assertEquals(1, summary.next7Days)
        assertEquals(50, summary.averageScore)
        assertEquals("North", summary.mostAdvanced.first().name)
        assertEquals("South", summary.urgent.first().name)
    }

    @Test
    fun linksAFinishedOperationToTheTaskItCarriesOut() {
        data class T(val id: String, val status: String, val type: String, val dueDate: String)
        val tasks = listOf(T("later", "TODO", "FERTILIZATION", day(10)), T("soon", "TODO", "FERTILIZATION", day(2)), T("other", "TODO", "HARVEST", day(0)))
        fun find(work: String) = taskForFinishedWork(tasks, work, now, { it.status }, { parseJsDate(it.dueDate) }, { it.type })
        assertEquals("soon", find("FERTILIZING")?.id)
        assertNull(find("SOWING"))
    }
}

class ReportBuildersTest {
    private val now = localDate(2026, 4, 12, 10)
    private val parcels = listOf(
        ReportParcel("p1", "North", "P-1", 12.0, null, CropCode.CORN, TillageSystem.NO_TILL),
        ReportParcel("p2", "South", "P-2", 8.0, null, CropCode.WHEAT, null),
    )
    private val runs = listOf(
        ReportRun("p1", "m1", "SOWING", OperationStatus.COMPLETED, 12.0, 100.0, 4.0, 70.0, "2026-04-02T08:00:00Z"),
        ReportRun("p2", "m1", "SPRAYING", OperationStatus.RUNNING, 8.0, 40.0, 1.0, 10.0, "2026-05-12T07:00:00Z"),
    )

    private fun ReportRow.text(key: String) = (this[key] as JsonPrimitive).content

    @Test
    fun listsParcelsWithTheirPlanStatusAndCompletion() {
        val task = ParcelTask(FieldTask("t", "Spray", null, TaskStatus.TODO, localDate(2026, 4, 10).toJsIsoString()), "p1")
        val report = parcelsReport(2026, parcels, listOf(task), runs, now)
        assertEquals("North", report.rows[0].text("parcel"))
        assertEquals("Overdue work", report.rows[0].text("status"))
        assertEquals("1", report.rows[0].text("overdue"))
        assertEquals("0", report.rows[0].text("completion"))
        assertEquals("Work in progress", report.rows[1].text("status"))
        assertEquals(ChartPoint("North", 12.0), reportChartPoints(report).first())
    }

    @Test
    fun showsWhichMachinesWorkAndWhichAreAvailable() {
        val report = machinesReport(
            2026,
            listOf(ReportMachine("m1", "JD", "TRACTOR", "Ion"), ReportMachine("m2", "Claas", "COMBINE", null)),
            runs,
        ) { if (it == "p2") "South" else "North" }
        assertEquals("2", report.rows[0].text("operations"))
        assertEquals("5", report.rows[0].text("hours"))
        assertEquals("15.2", report.rows[0].text("area"))
        assertEquals("Working, South", report.rows[0].text("status"))
        assertEquals("Available", report.rows[1].text("status"))
    }

    @Test
    fun groupsCropsAndComputesTheYieldPerHectare() {
        val report = cropsReport(2026, parcels, listOf(ReportSeason("p1", CropCode.CORN, 108.0), ReportSeason("p2", CropCode.WHEAT, null)))
        val row = report.rows[0]
        assertEquals("Corn", row.text("crop"))
        assertEquals("12", row.text("area"))
        assertEquals("60", row.text("share"))
        assertEquals("9", row.text("perHa"))
    }

    @Test
    fun buildsTheProfitabilityAndFarmOverviewFromTheEconomics() {
        val result = computeParcelEconomics(
            ParcelEconomicsInput(
                12.0,
                EconomicSheet(misc = listOf(MiscLine("a", "All", 620.0, MiscBasis.PER_HA)), revenue = RevenueInputs(expectedYieldTHa = 9.0, pricePerT = 220.0)),
                emptyList(),
                defaultSettings(),
            ),
        )
        val item = FarmParcelEconomics("p1", "North", CropCode.CORN, "Corn", null, result)
        val summary = summarizeFarmEconomics(listOf(item))
        val row = profitabilityReport(2026, listOf(item), summary).rows[0]
        assertEquals("North", row.text("parcel"))
        assertEquals("1980", row.text("revenuePerHa"))
        assertEquals("Expected", row.text("basis"))
        val farm = farmReport(2026, "Test", parcels, runs, emptyList(), summary, now)
        assertEquals("2", farm.rows.first { it.text("metric") == "Operations carried out" }.text("value"))
        assertTrue(reportChartPoints(farm).isNotEmpty())
        // The table serializes, as the server and the PDF export read it.
        assertTrue(Json.encodeToJsonElement(ReportTable.serializer(), farm).jsonObject.containsKey("rows"))
    }
}

