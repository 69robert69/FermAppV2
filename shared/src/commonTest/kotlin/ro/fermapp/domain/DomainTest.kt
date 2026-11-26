package ro.fermapp.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** The TypeScript domain tests, ported one to one. */

private fun hour(time: String, temperatureC: Double = 16.0, precipitationMm: Double = 0.0, probability: Double = 0.0, windKmh: Double = 6.0, weatherCode: Int? = null) =
    HourlyWeather(time, temperatureC, 65.0, precipitationMm, probability, windKmh, weatherCode)

/** Vitest's toBeCloseTo: |expected - actual| < 10^-digits / 2. */
private fun assertClose(expected: Double, actual: Double, digits: Int = 2) =
    assertTrue(abs(expected - actual) < 0.5 * 10.0.pow(-digits), "expected $expected but was $actual")

private fun at(text: String): Instant = Instant.parse(text)

private val CROP = CropCode.entries.associateBy { it.name }

class GeoTest {
    // Square of about 1 km × 1 km at latitude 45°.
    private val dLat = 1000 / 111_195.0
    private val dLng = dLat / cos(45 * PI / 180)
    private val outline = listOf(Position(25.0, 45.0), Position(25 + dLng, 45.0), Position(25 + dLng, 45 + dLat), Position(25.0, 45 + dLat), Position(25.0, 45.0))
    private val square = PolygonGeometry(coordinates = listOf(outline))

    @Test
    fun computesHectaresPerimeterAndCentre() {
        val metrics = computeGeoMetrics(square)
        assertTrue(metrics.areaHa > 99 && metrics.areaHa < 101)
        assertTrue(metrics.perimeterM > 3980 && metrics.perimeterM < 4020)
        assertClose(45 + dLat / 2, metrics.center.lat, 5)
        assertClose(25 + dLng / 2, metrics.center.lng, 5)
    }

    @Test
    fun subtractsHolesFromTheArea() {
        val c = 0.25
        val hole = listOf(
            Position(25 + dLng * c, 45 + dLat * c),
            Position(25 + dLng * c, 45 + dLat * 0.75),
            Position(25 + dLng * 0.75, 45 + dLat * 0.75),
            Position(25 + dLng * 0.75, 45 + dLat * c),
            Position(25 + dLng * c, 45 + dLat * c),
        )
        val withHole = computeGeoMetrics(PolygonGeometry(coordinates = listOf(outline, hole)))
        assertTrue(withHole.areaHa > 74 && withHole.areaHa < 76)
    }

    @Test
    fun rejectsABowTieOutline() {
        val bowtie = listOf(Position(25.0, 45.0), Position(25.01, 45.01), Position(25.01, 45.0), Position(25.0, 45.01))
        assertTrue(isSelfIntersecting(bowtie))
        assertContains(validatePolygon(PolygonGeometry(coordinates = listOf(bowtie)))!!, "intersects")
        assertNull(validatePolygon(square))
    }
}

class RotationTest {
    private fun h(crop: String, year: Int) = CropHistoryEntry(CROP.getValue(crop), year)

    @Test
    fun reproducesTheExampleForSunflowerGrownTwoYearsAgo() {
        val result = evaluateRotation(CropCode.SUNFLOWER, 2026, listOf(h("WHEAT", 2025), h("SUNFLOWER", 2024)))
        assertEquals(RotationVerdict.NOT_RECOMMENDED, result.verdict)
        assertEquals(
            "Sunflower was grown 2 years ago on this parcel. A minimum break of 4 years is recommended. The risk of disease and yield loss is high.",
            result.explanation,
        )
    }

    @Test
    fun appliesTheExactMinimumBreakThreshold() {
        assertEquals(RotationVerdict.NOT_RECOMMENDED, evaluateRotation(CropCode.SUNFLOWER, 2026, listOf(h("SUNFLOWER", 2022))).verdict)
        assertNotEquals(RotationVerdict.NOT_RECOMMENDED, evaluateRotation(CropCode.SUNFLOWER, 2027, listOf(h("SUNFLOWER", 2022))).verdict)
    }

    @Test
    fun respectsTheThreeYearBreakForRapeseed() {
        assertEquals(RotationVerdict.NOT_RECOMMENDED, evaluateRotation(CropCode.RAPESEED, 2026, listOf(h("RAPESEED", 2023))).verdict)
        assertNotEquals(RotationVerdict.NOT_RECOMMENDED, evaluateRotation(CropCode.RAPESEED, 2026, listOf(h("RAPESEED", 2022))).verdict)
    }

    @Test
    fun favoursCornAfterLegumesAndSoybeanAfterCereals() {
        val cornAfterSoy = evaluateRotation(CropCode.CORN, 2026, listOf(h("SOY", 2025)))
        val soyAfterWheat = evaluateRotation(CropCode.SOY, 2026, listOf(h("WHEAT", 2025)))
        assertEquals(RotationVerdict.RECOMMENDED, cornAfterSoy.verdict)
        assertContains(cornAfterSoy.factors.map { it.code }, "CORN_AFTER_LEGUME")
        assertContains(soyAfterWheat.factors.map { it.code }, "SOY_AFTER_CEREAL")
    }

    @Test
    fun recognizesTheNitrogenContributionOfAlfalfa() {
        val result = evaluateRotation(CropCode.WHEAT, 2026, listOf(h("ALFALFA", 2025)))
        assertContains(result.factors.map { it.code }, "ALFALFA_NITROGEN")
        assertTrue(result.score <= 100)
    }

    @Test
    fun keepsTheScoreWithin0To100() {
        val history = listOf(2025, 2024, 2023, 2022, 2021).map { h("SUNFLOWER", it) }
        val score = evaluateRotation(CropCode.SUNFLOWER, 2026, history).score
        assertTrue(score in 0..39)
    }
}

class WorkTest {
    private val params = WorkParams(6.0, 8.0, 80.0)

    @Test
    fun appliesTheCapacityFormula() = assertClose(3.84, workCapacityHaH(params))

    @Test
    fun estimatesDurationAndEta() {
        val result = estimateWork(38.4, params, at("2026-05-01T08:00:00Z"))
        assertEquals(10.0, result.durationHours)
        assertEquals("2026-05-01T18:00:00.000Z", result.eta.toJsIsoString())
    }

    @Test
    fun stopsProgressWhilePaused() {
        val progress = timedProgress(
            TimedProgressInput(at("2026-05-01T08:00:00Z"), 4 * 3_600_000L, pausedAt = at("2026-05-01T09:00:00Z"), now = at("2026-05-01T11:00:00Z")),
        )
        assertEquals(25.0, progress.percent)
        assertFalse(progress.finished)
    }

    @Test
    fun computesIrrigationFromRouteAndSpeed() {
        val result = estimateIrrigation(400.0, 25.0, at("2026-06-01T06:00:00Z"))
        assertEquals(16.0, result.durationHours)
        assertEquals("2026-06-01T22:00:00.000Z", result.eta.toJsIsoString())
    }
}

class WaterTest {
    @Test
    fun computesTheDeficitForCorn() {
        val balance = waterBalance(WaterBalanceInput(CropCode.CORN, 325.0))
        assertEquals(625.0, balance.requiredMm)
        assertEquals(300.0, balance.deficitMm)
        assertEquals(10, balance.irrigationPasses)
        assertEquals("Corn: deficit of 300 mm. 10 irrigation passes of 30 mm are recommended.", balance.suggestion)
    }

    @Test
    fun doesNotAskForIrrigationWhenTheRequirementIsCovered() {
        val balance = waterBalance(WaterBalanceInput(CropCode.WHEAT, 600.0))
        assertEquals(0.0, balance.deficitMm)
        assertEquals(0, balance.irrigationPasses)
    }
}

class WorkWindowsTest {
    @Test
    fun findsTheSprayingWindowAndPhrasesItInEnglish() {
        // 18 November 2026 is a Wednesday.
        val hours = (0 until 12).map { i ->
            val h = (i + 4).toString().padStart(2, '0')
            val windy = i < 2 || i >= 7
            hour("2026-11-18T$h:00", windKmh = if (windy) 24.0 else 6.0)
        }
        val windows = findWorkWindows(FieldOperation.SPRAYING, hours)
        assertEquals(1, windows.size)
        assertEquals("Wednesday 06:00-11:00", windows.first().label)
        assertEquals("Best window for spraying: Wednesday 06:00-11:00", bestWindowMessage(FieldOperation.SPRAYING, windows))
    }

    @Test
    fun excludesTheHoursRightAfterRain() {
        val hours = listOf(
            hour("2026-11-18T06:00", precipitationMm = 4.0, probability = 90.0),
            hour("2026-11-18T07:00"),
            hour("2026-11-18T08:00"),
            hour("2026-11-18T09:00"),
            hour("2026-11-18T10:00"),
            hour("2026-11-18T11:00"),
        )
        assertEquals("2026-11-18T10:00", findWorkWindows(FieldOperation.SPRAYING, hours).first().start)
    }

    @Test
    fun crossesMidnightCorrectly() {
        val hours = listOf(hour("2026-11-18T22:00"), hour("2026-11-18T23:00"))
        val window = findWorkWindows(FieldOperation.SPRAYING, hours).first()
        assertEquals("2026-11-19T00:00", window.end)
        assertEquals("Wednesday 22:00-00:00", window.label)
    }

    @Test
    fun showsBothDaysForAnIntervalLongerThanADay() {
        val start = LocalDateTime(2026, 11, 18, 22, 0).toInstant(TimeZone.UTC)
        val hours = (0 until 24).map { i -> hour((start + i.hours).toJsIsoString().take(16)) }
        assertEquals("Wednesday 22:00 - Thursday 22:00", findWorkWindows(FieldOperation.SPRAYING, hours).first().label)
    }
}

class SoilTest {
    @Test
    fun classifiesAClayChernozem() {
        val soil = classifySoil(SoilInput(6.8, 42.0, 20.0, 22.6))
        assertEquals("Chernozem (clay)", soil.label)
        assertClose(3.9, soil.organicMatterPct, 1)
    }

    @Test
    fun computesAvailableWaterFromRetentionValues() {
        val soil = classifySoil(SoilInput(7.0, 30.0, 30.0, 15.0, 34.0, 18.0))
        assertEquals(160, soil.waterHoldingMmPerM)
    }
}

class RecommendationsTest {
    @Test
    fun penalizesTheCropThatBreaksTheRotation() {
        val ranked = recommendCrops(
            RecommendationInput(
                2026,
                listOf(CropHistoryEntry(CropCode.SUNFLOWER, 2024), CropHistoryEntry(CropCode.WHEAT, 2025)),
                SoilSummary(6.8, SoilTexture.CLAY_LOAM),
                ClimateSummary(19.0, 560.0),
            ),
        )
        val sunflower = ranked.first { it.crop == CropCode.SUNFLOWER }
        val soy = ranked.first { it.crop == CropCode.SOY }
        assertTrue(soy.score > sunflower.score)
        assertContains(sunflower.reasons.first(), "minimum break")
    }

    @Test
    fun marksCriteriaWithoutData() {
        val first = recommendCrops(RecommendationInput(2026, emptyList(), crops = listOf(CropCode.WHEAT))).first()
        assertEquals(listOf("soil", "climate"), first.missingData)
    }
}

class GddTest {
    @Test
    fun capsTemperaturesAtTheThreshold() {
        assertEquals(10.0, dailyGdd(35.0, 5.0, 10.0, 30.0))
        assertEquals(0.0, dailyGdd(8.0, 2.0, 10.0))
    }

    @Test
    fun estimatesTheCropStage() {
        val days = (0 until 60).map { DailyTemperature("d$it", 30.0, 18.0) }
        val development = cropDevelopment(CropCode.CORN, days)
        assertEquals(840, development.accumulatedGdd)
        assertEquals("Grain fill", development.stage)
    }
}

class VegetationTest {
    @Test
    fun coloursParcelsByCropCondition() {
        assertEquals(HealthColor.GREEN, vegetationHealth(0.75, 0.4).color)
        assertEquals(HealthColor.YELLOW, vegetationHealth(0.42).color)
        assertEquals(HealthColor.RED, vegetationHealth(0.18).color)
    }
}

class WeatherAlertsTest {
    @Test
    fun reproducesTheRequiredMessages() {
        val hours = listOf(
            hour("2026-11-18T09:00", windKmh = 22.0),
            hour("2026-11-18T10:00"),
            hour("2026-11-18T11:00"),
            hour("2026-11-18T12:00", precipitationMm = 2.0),
        )
        val messages = evaluateWeatherAlerts("South-1", hours).map { it.message }
        assertContains(messages, "Rain expected in 3 hours on parcel South-1.")
        assertContains(messages, "Wind 22 km/h. Herbicide spraying is not recommended.")
    }

    @Test
    fun detectsHailAndFrost() {
        val types = evaluateWeatherAlerts(
            "North",
            listOf(hour("2026-04-18T03:00", temperatureC = -2.0), hour("2026-04-18T04:00", weatherCode = 96)),
        ).map { it.type }
        assertTrue(types.containsAll(listOf(AlertType.HAIL, AlertType.FROST)))
    }
}

class ParcelIntelligenceTest {
    private val now = at("2027-07-16T12:00:00Z")
    private val weather = WeatherSummary(60.0, 20.0, 12.0, 0, 15.0, 20.0)

    @Test
    fun computesTheVegetationDayAndProgress() {
        val progress = cropProgress(CropCode.CORN, sowingDate = at("2027-04-12T12:00:00Z"), expectedHarvestDate = at("2027-09-29T12:00:00Z"), now = now)
        assertEquals(CropProgressStatus.GROWING, progress.status)
        assertEquals(96, progress.vegetationDay)
        assertEquals(170, progress.totalDays)
        assertEquals(56, progress.percent)
    }

    @Test
    fun marksTheUnsownAndTheHarvestedCrop() {
        assertEquals(CropProgressStatus.NOT_SOWN, cropProgress(CropCode.WHEAT, now = now).status)
        val done = cropProgress(CropCode.WHEAT, sowingDate = at("2026-10-10T00:00:00Z"), harvestDate = at("2027-07-01T00:00:00Z"), now = now)
        assertEquals(CropProgressStatus.HARVESTED, done.status)
        assertEquals(100, done.percent)
    }

    @Test
    fun computesSoilIndices() {
        val good = soilIndices(SoilProperties(6.8, 4.0, 30.0, 30.0, 180.0))
        val poor = soilIndices(SoilProperties(4.8, 0.8, 5.0, 85.0, 60.0))
        assertEquals(100, good.fertilityIndex)
        assertTrue(good.soilScore > poor.soilScore)
    }

    @Test
    fun appliesTheSprayingThresholds() {
        assertEquals(emptyList(), sprayingAlerts(listOf(SprayHour(15.0, 60.0))))
        assertEquals(
            listOf("Wind 22 km/h. Do not spray.", "Rain probability 75%. Postpone spraying."),
            sprayingAlerts(listOf(SprayHour(22.0, 75.0))).map { it.message },
        )
    }

    @Test
    fun recommendsFungicideAtHighHumidity() {
        val action = nextRecommendedAction(
            NextActionInput(CropCode.WHEAT, ProgressSnapshot(CropProgressStatus.GROWING, 55), weather.copy(meanHumidityPct = 84.0, rainyDays = 3), now = now),
        )
        assertEquals("FUNGICIDE", action.code)
        assertEquals(ActionPriority.HIGH, action.priority)
        assertEquals(4, action.dueInDays)
    }

    @Test
    fun doesNotRepeatARecentlyAppliedTreatment() {
        val action = nextRecommendedAction(
            NextActionInput(
                CropCode.SOY, ProgressSnapshot(CropProgressStatus.GROWING, 55), weather.copy(meanHumidityPct = 84.0, rainyDays = 3),
                recentWork = listOf(RecentWork("SPRAYING", at("2027-07-12T00:00:00Z"))), now = now,
            ),
        )
        assertEquals("MONITOR", action.code)
    }

    @Test
    fun prioritizesIrrigationWhenTheSoilIsDry() {
        val action = nextRecommendedAction(NextActionInput(CropCode.CORN, ProgressSnapshot(CropProgressStatus.GROWING, 50), weather, 22.0, now = now))
        assertEquals("IRRIGATE", action.code)
        assertEquals(90, action.confidencePct)
    }

    @Test
    fun computesMarginAndRoi() {
        val economics = parcelEconomics(
            CostSummaryInput(10.0, listOf(CostItem(CostCategory.SEED, 10000.0), CostItem(CostCategory.LABOR, 10000.0)), expectedYieldTHa = 5.0, expectedPricePerT = 1000.0),
        )
        assertEquals(10000.0, economics.laborCost)
        assertEquals(50000.0, economics.expectedIncome)
        assertEquals(30000.0, economics.expectedProfit)
        assertEquals(60.0, economics.profitMarginPct)
        assertEquals(150.0, economics.roiPct)
    }
}

class CostsTest {
    @Test
    fun computesCostPerHectareAndProfit() {
        val summary = summarizeCosts(
            CostSummaryInput(
                10.0, listOf(CostItem(CostCategory.SEED, 5000.0), CostItem(CostCategory.FUEL, 3000.0)),
                expectedYieldTHa = 6.0, expectedPricePerT = 1000.0, actualYieldT = 55.0, actualPricePerT = 1100.0,
            ),
        )
        assertEquals(800.0, summary.costPerHa)
        assertEquals(52000.0, summary.estimatedProfit)
        assertEquals(52500.0, summary.actualProfit)
    }

    @Test
    fun leavesProfitEmptyWithoutSalesData() {
        val summary = summarizeCosts(CostSummaryInput(5.0, emptyList()))
        assertNull(summary.estimatedProfit)
        assertNull(summary.actualProfit)
    }
}

class FieldPathTest {
    private val ring = listOf(Position(25.0, 45.0), Position(25.01, 45.0), Position(25.01, 45.004), Position(25.0, 45.004), Position(25.0, 45.0))
    private val path = buildFieldPath(ring, FieldPathOptions(workingWidthM = 6.0))

    @Test
    fun startsAtOneEdgePassesThroughTheCentreAndReachesTheOppositeEnd() {
        val start = positionOnPath(path, 0.0)!!
        val middle = positionOnPath(path, 50.0)!!
        val end = positionOnPath(path, 100.0)!!
        assertTrue(start.lat < 45.0005)
        assertClose(25.005, middle.lng, 3)
        assertClose(45.002, middle.lat, 3)
        assertTrue(end.lat > 45.0035)
    }

    @Test
    fun staysInsideTheParcel() {
        for (percent in listOf(5.0, 25.0, 50.0, 75.0, 95.0)) {
            val point = positionOnPath(path, percent)!!
            assertTrue(pointInRing(Position(point.lng, point.lat), ring))
        }
    }

    @Test
    fun recognizesAPointOutsideTheParcel() = assertFalse(pointInRing(Position(25.02, 45.002), ring))
}

class TechnologyPlanTest {
    private val now = at("2026-11-18T10:00:00Z")
    private val soil = PlanSoil(6.8, SoilTexture.CLAY_LOAM, "Chernozem (clay loam)")
    private val climate = ClimateSummary(20.0, 520.0)

    @Test
    fun generatesTheCornCalendarForNextSeason() {
        val schedule = planCalendar(CropCode.CORN, CalendarOptions(now = now))
        fun dateOf(key: PlanStepKey) = schedule.steps.first { it.key == key }.date.take(10)
        assertEquals(2027, schedule.seasonYear)
        assertEquals("2027-03-10", dateOf(PlanStepKey.SOIL_PREP))
        assertEquals("2027-03-25", dateOf(PlanStepKey.SEED_PURCHASE))
        assertEquals("2027-04-05", dateOf(PlanStepKey.SOWING))
        assertEquals("2027-05-15", dateOf(PlanStepKey.TOP_DRESSING))
        assertEquals("2027-08-01", dateOf(PlanStepKey.PEST_MONITORING))
        assertEquals("2027-10-15", dateOf(PlanStepKey.HARVEST))
    }

    @Test
    fun movesTheHarvestOfAutumnCropsToTheYearAfterSowing() {
        val schedule = planCalendar(CropCode.WHEAT, CalendarOptions(now = now))
        assertEquals("2027-10-05", schedule.sowingDate.take(10))
        assertEquals(2028, schedule.seasonYear)
    }

    @Test
    fun doesNotScheduleStepsInThePastForANewPlan() {
        val steps = planCalendar(CropCode.CORN, CalendarOptions(now = at("2027-03-20T10:00:00Z"))).steps
        assertTrue(steps.none { it.past })
        assertEquals("2027-03-20", steps.first().date.take(10))
    }

    @Test
    fun doesNotAskForTopDressingNitrogenForLegumes() {
        assertTrue(planCalendar(CropCode.SOY, CalendarOptions(now = now)).steps.none { it.key == PlanStepKey.TOP_DRESSING })
    }

    @Test
    fun explainsTheScoreByTheLastCrop() {
        val ranked = rankCropsForParcel(2027, listOf(CropHistoryEntry(CropCode.WHEAT, 2026)), soil, climate)
        val soy = ranked.first { it.crop == CropCode.SOY }
        assertContains(soy.reason, "wheat last year")
        assertTrue(ranked.first { it.crop == CropCode.WHEAT }.score < soy.score)
    }

    @Test
    fun reportsDroughtAndIrrigationCyclesWhenWaterIsInsufficient() {
        val plan = buildParcelPlan(ParcelPlanInput(CropCode.CORN, "South-12", 42.7, listOf(CropHistoryEntry(CropCode.WHEAT, 2026)), soil = soil, climate = climate, now = now))
        assertTrue((plan.irrigation.deficitMm ?: 0) > 0)
        assertContains(plan.report.risks.first(), "July-August")
        assertContains(plan.report.recommendation, "irrigation cycles")
        assertEquals("Wheat", plan.report.lastCropName)
    }

    @Test
    fun marksMissingDataInsteadOfInventingValues() {
        val plan = buildParcelPlan(ParcelPlanInput(CropCode.SOY, "North", 5.0, emptyList(), soil = null, climate = null, now = now))
        assertNull(plan.irrigation.deficitMm)
        assertEquals(listOf("soil", "climate"), plan.report.missingData)
    }

    @Test
    fun determinesTheNextAgriculturalYear() {
        assertEquals(2027, nextSeasonYear(at("2026-11-18T10:00:00Z")))
        assertEquals(2026, nextSeasonYear(at("2026-03-01T10:00:00Z")))
    }
}

class FarmCatalogTest {
    private val tractors = TRACTOR_CATALOG.map { TractorOption(it.id, "${it.brand} ${it.model}", it.horsePower, it.fuelConsumptionMin, it.fuelConsumptionMax, true) }

    @Test
    fun containsTheRequiredProductsWithTheSuppliedData() {
        val seed = SEED_CATALOG.first { it.name == "Pioneer P0710" }
        assertEquals(410.0, seed.fao)
        assertEquals(160.0, seed.vegetationDays)
        assertEquals(CatalogLevel.HIGH, seed.droughtTolerance)
        assertEquals(10.0, seed.expectedYieldMin)
        assertEquals(16.0, seed.expectedYieldMax)
        assertEquals(23, SEED_CATALOG.size)
        assertEquals(18, TRACTOR_CATALOG.size)
    }

    @Test
    fun doesNotInventMissingDataAndMarksEstimatedConsumption() {
        assertNull(SEED_CATALOG.first { it.name == "Glosa" }.fao)
        val fendt = TRACTOR_CATALOG.first { it.model == "942 Vario" }
        assertTrue(fendt.fuelEstimated)
        assertNull(fendt.tankCapacity)
        assertFalse(TRACTOR_CATALOG.first { it.model == "8R 340" }.fuelEstimated)
    }

    @Test
    fun computesTheCostFromTheExample() {
        assertEquals(42.0, fuelLitersPerHour(35.0, 55.0))
        val cost = workCost(42.0, 5.0, 7.2, 40.0)
        assertEquals(210.0, cost.fuelLiters)
        assertEquals(1512.0, cost.fuelCost)
    }

    @Test
    fun recommendsATractorPowerfulEnoughForTheJob() {
        val result = recommendMachine(42.7, WorkOperation.PLOWING, tractors)!!
        assertTrue(result.workingWidthM * 90 <= tractors.first { it.id == result.tractorId }.horsePower)
        assertTrue(result.durationHours > 0)
        assertTrue(result.fuelCost > 0)
    }

    @Test
    fun prefersFarmTractorsAndRejectsInsufficientPower() {
        val small = TractorOption("mic", "Small", 100.0, owned = true)
        val big = TractorOption("mare", "Large", 400.0, owned = false)
        assertEquals("mic", recommendMachine(10.0, WorkOperation.SOWING, listOf(small, big))!!.tractorId)
        assertNull(recommendMachine(10.0, WorkOperation.PLOWING, listOf(small.copy(horsePower = 50.0))))
        assertNull(recommendMachine(10.0, WorkOperation.HARVESTING, tractors))
    }

    @Test
    fun reducesAvailablePowerOnASlope() {
        val tractor = listOf(TractorOption("t", "T", 300.0, owned = true))
        val flat = recommendMachine(10.0, WorkOperation.PLOWING, tractor)!!
        val steep = recommendMachine(10.0, WorkOperation.PLOWING, tractor, slopePct = 15.0)!!
        assertTrue(steep.workingWidthM < flat.workingWidthM)
    }

    @Test
    fun ranksHybridsAndExplainsTheScore() {
        val ranked = rankSeedsForParcel(
            SEED_CATALOG, CropCode.CORN, 2027, listOf(CropHistoryEntry(CropCode.WHEAT, 2026)),
            SoilSummary(6.8, SoilTexture.CLAY_LOAM), ClimateSummary(20.0, 450.0, 1500.0),
        )
        assertEquals(4, ranked.size)
        assertEquals("Pioneer P0710", ranked[0].seed.name)
        assertContains(ranked[0].reason, "it tolerates drought well")
        assertContains(ranked[1].missingData, "drought tolerance")
    }

    @Test
    fun filtersAndSortsProducts() {
        val late = filterSeeds(SEED_CATALOG, SeedFilters(crop = CropCode.CORN, faoMin = 400.0)).map { it.fao }
        assertEquals(2, late.size)
        assertTrue(late.containsAll(listOf(410.0, 420.0)))
        assertEquals(1, filterSeeds(SEED_CATALOG, SeedFilters(search = "dekalb", crop = CropCode.CORN)).size)
        assertEquals(380.0, filterSeeds(SEED_CATALOG, SeedFilters(crop = CropCode.CORN, sort = SeedSort.FAO)).first().fao)
    }

    @Test
    fun importsCsvRowsAndReportsErrors() {
        val rows = parseCsv("Name;Crop;FAO;Tolerance\n\"Hybrid; new\";Corn;350;High\nNo crop;;;\n")
        val seed = assertIs<RowResult.Ok<SeedEntry>>(seedFromRow(rows[0])).value
        assertEquals("Hybrid; new", seed.name)
        assertEquals(CropCode.CORN, seed.crop)
        assertEquals(350.0, seed.fao)
        assertEquals(CatalogLevel.HIGH, seed.droughtTolerance)
        assertEquals(SeedType.HYBRID, seed.type)
        assertContains(assertIs<RowResult.Error>(seedFromRow(rows[1])).message, "the crop is not recognized")
        val tractor = assertIs<RowResult.Ok<TractorEntry>>(tractorFromRow(mapOf("Brand" to "Zetor", "Model" to "Forterra", "HP" to "140"))).value
        assertEquals("Zetor", tractor.brand)
        assertEquals(140.0, tractor.horsePower)
        assertTrue(tractor.fuelEstimated)
        assertContains(assertIs<RowResult.Error>(tractorFromRow(mapOf("Brand" to "Zetor", "Model" to "X"))).message, "power")
    }
}

class JsCompatTest {
    @Test
    fun roundsLikeJavaScript() {
        assertEquals(3.0, jsRound(2.5))
        assertEquals(-2.0, jsRound(-2.5))
        assertEquals("75", jsNum(75.0))
        assertEquals("2.5", jsNum(2.5))
        assertEquals("1.50", 1.5.toFixed(2))
        assertEquals("€12.450,50", formatEuro(12450.5))
        assertEquals("€1.250", formatEuro(1250.0))
        assertEquals("-€3", formatEuro(-3.0))
        assertEquals("€366,67/ha", formatEuroPerHa(366.666))
    }

    @Test
    fun stripsRomanianDiacritics() {
        assertEquals("grau si porumb in tarlaua", normalizedText("  Grâu și porumb în tarlaua "))
        assertEquals("kws-kamparis", slug("KWS Kamparis"))
    }
}
