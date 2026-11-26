package ro.fermapp.domain.copilot

import ro.fermapp.domain.CROP_CALENDARS
import ro.fermapp.domain.CropCode
import ro.fermapp.domain.CropFamily
import ro.fermapp.domain.CropHistoryEntry
import ro.fermapp.domain.CropProgressStatus
import ro.fermapp.domain.PlanSoil
import ro.fermapp.domain.RankedCrop
import ro.fermapp.domain.SoilTexture
import ro.fermapp.domain.TillageSystem
import ro.fermapp.domain.evaluateRotation
import ro.fermapp.domain.jsNum
import ro.fermapp.domain.jsRound
import ro.fermapp.domain.profile
import ro.fermapp.domain.rankCropsForParcel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private fun clamp(value: Double, min: Double, max: Double) = max(min, min(max, value))

internal fun <T> MutableList<T>.replaceWith(items: List<T>) {
    clear()
    addAll(items)
}

// ------------------------------------------------------------------ estimates

data class YieldEstimate(val low: Double, val expected: Double, val high: Double, val factors: List<String>)

fun estimateYield(facts: ParcelFacts, crop: CropCode, ctx: Context): YieldEstimate {
    val profile = crop.profile
    val factors = mutableListOf<String>()
    var expected = profile.referenceYieldTHa
    val same = facts.yields.filter { it.crop == crop }
    if (same.isNotEmpty()) {
        val mean = same.sumOf { it.tHa } / same.size
        expected = mean * 0.65 + expected * 0.35
        factors.add("${same.size} recorded ${cropName(crop)} season${if (same.size > 1) "s" else ""} on this parcel averaged ${t(mean)} t/ha")
    } else if (facts.productivity != null) {
        val index = clamp(facts.productivity, 0.7, 1.3)
        expected *= index
        factors.add("the parcel’s yields run at ${rounded(index * 100)}% of regional reference yields")
    }
    val ph = facts.parcel.soil?.ph
    if (ph != null) {
        val (min, max) = profile.phRange
        if (ph < min || ph > max) {
            expected *= 0.9
            factors.add("pH ${jsNum(ph)} is outside the ${jsNum(min)}–${jsNum(max)} range ${cropName(crop)} prefers (−10%)")
        }
    }
    val texture = facts.texture
    if (texture != null && texture !in profile.textures) {
        expected *= 0.9
        factors.add("${TEXTURE_LABELS.getValue(texture)} soil is not ideal for ${cropName(crop)} (−10%)")
    }
    val thirsty = profile.waterMm.min >= 450
    val past30 = ctx.weather?.past30RainMm
    if (facts.irrigated && thirsty) {
        expected *= 1.08
        factors.add("irrigation secures water in the critical period (+8%)")
    } else if (!facts.irrigated && thirsty && past30 != null && past30 < 25 && facts.crop == crop) {
        expected *= 0.94
        factors.add("only ${rounded(past30)} mm of rain in the last 30 days (−6%)")
    }
    val spread = if (same.size >= 2) 0.1 else 0.15
    fun round(value: Double) = jsRound(value * 10) / 10
    return YieldEstimate(round(expected * (1 - spread)), round(expected), round(expected * (1 + spread)), factors)
}

data class ProfitEstimate(
    val pricePerT: Double,
    val costPerHa: Double,
    val revenuePerHa: Double,
    val profitPerHa: Double,
    /** True when the cost comes from the parcel's own records. */
    val recordedCost: Boolean,
)

fun estimateProfit(facts: ParcelFacts, crop: CropCode, yieldTHa: Double): ProfitEstimate {
    val calendar = CROP_CALENDARS.getValue(crop)
    val sameSeason = facts.season?.takeIf { it.crop == crop }
    val pricePerT = sameSeason?.actualPricePerT ?: sameSeason?.expectedPricePerT ?: calendar.priceEurPerT
    val economics = facts.parcel.economics
    val recorded = if (sameSeason != null && economics != null && economics.costPerHa > 0) economics.costPerHa else null
    val tillageShift = facts.parcel.tillage?.let { it.profile.costPerHa - TillageSystem.CONVENTIONAL.profile.costPerHa } ?: 0.0
    val costPerHa = recorded ?: max(200.0, calendar.costEurHa + tillageShift * 0.5)
    val revenuePerHa = yieldTHa * pricePerT
    return ProfitEstimate(pricePerT, costPerHa, revenuePerHa, revenuePerHa - costPerHa, recorded != null)
}

enum class ProfitBasis { ACTUAL, EXPECTED, ESTIMATE }

data class ParcelProfit(val value: Double, val basis: ProfitBasis, val costPerHa: Double)

/** Profit per hectare actually recorded, expected from the season data, or estimated. */
fun parcelProfitPerHa(facts: ParcelFacts, ctx: Context): ParcelProfit? {
    val economics = facts.parcel.economics
    economics?.actualProfitPerHa?.let { return ParcelProfit(it, ProfitBasis.ACTUAL, economics.costPerHa) }
    economics?.expectedProfitPerHa?.let { return ParcelProfit(it, ProfitBasis.EXPECTED, economics.costPerHa) }
    val crop = facts.crop ?: return null
    val estimate = estimateProfit(facts, crop, estimateYield(facts, crop, ctx).expected)
    return ParcelProfit(estimate.profitPerHa, ProfitBasis.ESTIMATE, estimate.costPerHa)
}

private fun historyOf(facts: ParcelFacts) = facts.parcel.seasons.map { CropHistoryEntry(it.crop, it.year) }

private fun rankFor(facts: ParcelFacts, year: Int): List<RankedCrop> {
    val ph = facts.parcel.soil?.ph
    val texture = facts.texture
    val soil = if (ph != null && texture != null) PlanSoil(ph, texture) else null
    return rankCropsForParcel(year, historyOf(facts), soil, null)
}

/** Agronomic risks of growing a crop on the parcel. */
private fun cropRisks(facts: ParcelFacts, crop: CropCode, year: Int, yieldTHa: Double): List<String> {
    val risks = mutableListOf<String>()
    val profile = crop.profile
    val calendar = CROP_CALENDARS.getValue(crop)
    val last = facts.parcel.seasons.firstOrNull { it.year == year - 1 } ?: facts.previous.firstOrNull()
    val nitrogen = roundInt(calendar.nPerT * yieldTHa)
    if (nitrogen >= 120) {
        val credit = if (last != null && last.crop.profile.family == CropFamily.LEGUME) 30 else 0
        risks.add(
            if (credit != 0) {
                "Nitrogen demand is about $nitrogen kg N/ha; the previous ${cropName(last!!.crop)} leaves roughly $credit kg N/ha, so plan ${nitrogen - credit} kg N/ha."
            } else {
                "Nitrogen deficiency risk: ${cropName(crop)} needs about $nitrogen kg N/ha for this yield${if (last != null) ", and ${cropName(last.crop)} leaves little residual nitrogen" else ""}."
            },
        )
    }
    val rotation = evaluateRotation(crop, year, historyOf(facts))
    if (rotation.score < 70) risks.add(rotation.explanation)
    if (!facts.irrigated && profile.waterMm.min >= 450) {
        risks.add("Drought sensitivity: water shortage in months ${calendar.criticalFrom}–${calendar.criticalTo} cuts yield the most, and the parcel is not irrigated.")
    }
    if (facts.texture == SoilTexture.CLAY && (crop == CropCode.SUNFLOWER || crop == CropCode.POTATO || crop == CropCode.BEET)) {
        risks.add("Heavy clay delays emergence and makes root and tuber crops harder to establish.")
    }
    val ph = facts.parcel.soil?.ph
    if (ph != null) {
        if (ph < profile.phRange.min) risks.add("Acidic soil (pH ${jsNum(ph)}): consider liming before sowing.")
        if (ph > profile.phRange.max) risks.add("Alkaline soil (pH ${jsNum(ph)}) can lock up phosphorus and micronutrients.")
    }
    return risks
}

// ------------------------------------------------------------------ builders

/** "What should I plant next year?" */
fun nextCrop(ctx: Context, parcel: CopilotParcel): AnswerBuilder {
    val facts = parcelFacts(ctx.snapshot, parcel)
    val year = if (mentions(ctx.text, listOf("this year", "this season", "anul acesta", "anul asta", "sezonul acesta"))) facts.year else facts.year + 1
    val ranking = rankFor(facts, year)
    val best = ranking.first()
    val answer = blank(CopilotTopic.NEXT_CROP, ctx, parcel.id)
    answer.crop = best.crop
    val estimate = estimateYield(facts, best.crop, ctx)
    val profit = estimateProfit(facts, best.crop, estimate.expected)
    // Planning a later season: the crop in the field now is the "previous" one.
    val last = if (year > facts.year) facts.season ?: facts.previous.firstOrNull() else facts.previous.firstOrNull() ?: facts.season
    val soil = facts.parcel.soil
    val finance = ctx.snapshot.canSeeFinance

    val basis = listOfNotNull(
        last?.let { "the previous ${describeSeason(it)}" },
        soil?.ph?.let { "soil pH ${jsNum(it)}" },
        facts.texture?.let { "${TEXTURE_LABELS.getValue(it)} texture" },
        if (facts.yields.isNotEmpty()) "the yield history of ${facts.yields.size} season${if (facts.yields.size > 1) "s" else ""}" else null,
        facts.parcel.tillage?.profile?.name,
    )
    answer.direct = "${best.crop.profile.name} is the best option for ${parcel.name} in $year" +
        (if (basis.isNotEmpty()) ", based on ${list(basis)}." else ".") +
        " Expected yield ${t(estimate.low)}–${t(estimate.high)} t/ha under normal rainfall" +
        (if (finance) ", with a profit of about ${euroHa(profit.profitPerHa)}." else ".")
    answer.reasoning.replaceWith(listOf(best.reason, "Rotation and soil score: ${best.score}/100.") + estimate.factors.map { "Yield: $it." })
    facts.soilScore?.let { answer.reasoning.add("Soil health score $it/100.") }
    answer.risks.replaceWith(cropRisks(facts, best.crop, year, estimate.expected))
    answer.alternatives.replaceWith(
        ranking.drop(1).take(2).map { option ->
            val other = estimateYield(facts, option.crop, ctx)
            val otherProfit = estimateProfit(facts, option.crop, other.expected)
            "${option.crop.profile.name} (score ${option.score}/100, about ${t(other.expected)} t/ha${if (finance) ", ${euroHa(otherProfit.profitPerHa)}" else ""}): ${option.reason}"
        },
    )
    val worst = ranking.lastOrNull()
    if (worst != null && worst.score < 40) answer.alternatives.add("Avoid ${cropName(worst.crop)}: ${worst.reason}")
    if (finance) {
        answer.economics.replaceWith(
            listOf(
                "Revenue ≈ ${t(estimate.expected)} t/ha × ${euro(profit.pricePerT)}/t = ${euroHa(profit.revenuePerHa)}; cost ≈ ${euroHa(profit.costPerHa)}${if (profit.recordedCost) " (your recorded cost)" else " (typical cost)"}.",
                "For the whole parcel (${t(parcel.areaHa)} ha): about ${euro(profit.profitPerHa * parcel.areaHa)} profit.",
            ),
        )
    }

    val sowing = usualSowingDate(best.crop, ctx.today)
    if (soil == null) {
        answer.proposals.add(
            propose(ctx.snapshot, ProposalInput("SOIL_SAMPLING", parcel.id, addDays(ctx.today, 7), CopilotPriority.MEDIUM, "A soil analysis confirms pH and nutrients before the crop choice is final.", 14)),
        )
    }
    val order = addDays(sowing, -45)
    answer.proposals.add(
        propose(
            ctx.snapshot,
            ProposalInput(
                "SEED_PURCHASE", parcel.id, if (order < ctx.today) addDays(ctx.today, 3) else order, CopilotPriority.MEDIUM,
                "Order ${cropName(best.crop)} seed early for the best hybrid choice and price.", 14, "Buy ${cropName(best.crop)} seed – ${parcel.name}",
            ),
        ),
    )
    answer.proposals.add(
        propose(
            ctx.snapshot,
            ProposalInput(
                "SOWING", parcel.id, sowing, CopilotPriority.HIGH,
                "The usual sowing window for ${cropName(best.crop)} starts on ${dayLabel(sowing, ctx.today)}.", 14, "Plant ${cropName(best.crop)} – ${parcel.name}",
            ),
        ),
    )
    answer.nextSteps.replaceWith(
        listOfNotNull(
            if (soil == null) "Run a soil analysis to confirm the choice." else null,
            "Order seed by ${dayLabel(order, ctx.today)}.",
            "Prepare the field for sowing around ${dayLabel(sowing, ctx.today)}.",
        ),
    )
    answer.dataGaps.replaceWith(parcelGaps(facts, ctx, GapNeeds(soil = true, history = true)))
    answer.confidence = confidenceOf(answer.dataGaps)
    return answer
}

/** "Is corn suitable for this field?" */
fun suitability(ctx: Context, parcel: CopilotParcel, crop: CropCode): AnswerBuilder {
    val facts = parcelFacts(ctx.snapshot, parcel)
    val season = facts.season
    val year = if (season != null && season.crop != crop && season.harvestDate == null) facts.year + 1 else facts.year + (if (season != null) 1 else 0)
    val ranking = rankFor(facts, year)
    val entry = ranking.first { it.crop == crop }
    val estimate = estimateYield(facts, crop, ctx)
    val profit = estimateProfit(facts, crop, estimate.expected)
    val answer = blank(CopilotTopic.CROP_SUITABILITY, ctx, parcel.id)
    answer.crop = crop
    val finance = ctx.snapshot.canSeeFinance
    val verdict = when {
        entry.score >= 70 -> "suitable"
        entry.score >= 40 -> "possible, with conditions"
        else -> "not recommended"
    }
    answer.direct = "${crop.profile.name} on ${parcel.name} in $year: $verdict (score ${entry.score}/100). Expected ${t(estimate.low)}–${t(estimate.high)} t/ha${if (finance) ", about ${euroHa(profit.profitPerHa)} profit" else ""}."
    answer.reasoning.replaceWith(listOf(entry.reason) + entry.reasons + estimate.factors.map { "Yield: $it." })
    answer.risks.replaceWith(cropRisks(facts, crop, year, estimate.expected))
    val better = ranking.filter { it.crop != crop && it.score > entry.score }.take(2)
    answer.alternatives.replaceWith(better.map { "${it.crop.profile.name} scores higher (${it.score}/100): ${it.reason}" })
    if (answer.alternatives.isEmpty()) answer.alternatives.add("${crop.profile.name} is among the best-scoring crops for this parcel.")
    if (finance) {
        answer.economics.replaceWith(
            listOf(
                "Revenue ≈ ${euroHa(profit.revenuePerHa)} at ${euro(profit.pricePerT)}/t; cost ≈ ${euroHa(profit.costPerHa)}; profit ≈ ${euroHa(profit.profitPerHa)} (${euro(profit.profitPerHa * parcel.areaHa)} for ${t(parcel.areaHa)} ha).",
            ),
        )
    }
    if (entry.score >= 40) {
        val sowing = usualSowingDate(crop, ctx.today)
        answer.proposals.add(
            propose(ctx.snapshot, ProposalInput("SOWING", parcel.id, sowing, CopilotPriority.HIGH, "Usual sowing window for ${cropName(crop)}.", 14, "Plant ${cropName(crop)} – ${parcel.name}")),
        )
        answer.nextSteps.replaceWith(listOf("Plan sowing around ${dayLabel(sowing, ctx.today)}.") + answer.risks.take(1).map { "Address: $it" })
    } else {
        answer.nextSteps.replaceWith(
            if (better.isNotEmpty()) listOf("Consider ${cropName(better.first().crop)} instead.") else listOf("Review the rotation before choosing this crop."),
        )
    }
    answer.dataGaps.replaceWith(parcelGaps(facts, ctx, GapNeeds(soil = true, history = true)))
    answer.confidence = confidenceOf(answer.dataGaps)
    return answer
}

data class NitrogenStatus(val due: String?, val done: String?)

/** Days from sowing of the next nitrogen pass, and whether it has been done. */
fun nitrogenStatus(facts: ParcelFacts): NitrogenStatus? {
    val sowing = facts.season?.sowingDate ?: return null
    val crop = facts.crop ?: return null
    val topDressing = CROP_CALENDARS.getValue(crop).topDressingDays ?: return NitrogenStatus(null, null)
    val due = addDays(sowing, topDressing)
    val after = sowing.take(10)
    val doneRun = facts.lastWork.entries.firstOrNull { (type, day) -> type == "FERTILIZING" && day >= after }?.value
    val doneTask = facts.done.firstOrNull { (it.type == "NITROGEN" || it.type == "FERTILIZATION") && it.dueDate.take(10) >= after }
    return NitrogenStatus(due, doneRun ?: doneTask?.dueDate?.take(10))
}

/** "When should I fertilize?" */
fun fertilize(ctx: Context, parcel: CopilotParcel): AnswerBuilder {
    val facts = parcelFacts(ctx.snapshot, parcel)
    val answer = blank(CopilotTopic.FERTILIZE, ctx, parcel.id)
    val weather = ctx.weather
    val crop = facts.crop ?: ctx.crop
    answer.crop = crop
    if (crop == null) {
        answer.direct = "${parcel.name} has no crop recorded for this season, so there is no nitrogen timing to follow yet. Base fertilization (P and K) goes in before sowing, adjusted to a soil analysis."
        answer.nextSteps.replaceWith(listOf("Record the planned crop, or ask “What should I plant next year?”."))
        if (parcel.soil == null) {
            answer.proposals.add(propose(ctx.snapshot, ProposalInput("SOIL_SAMPLING", parcel.id, addDays(ctx.today, 7), CopilotPriority.MEDIUM, "The fertilization plan needs the soil’s P, K and pH.", 14)))
        }
        answer.dataGaps.replaceWith(parcelGaps(facts, ctx, GapNeeds(soil = true)))
        answer.confidence = Confidence.LOW
        return answer
    }
    val calendar = CROP_CALENDARS.getValue(crop)
    val estimate = estimateYield(facts, crop, ctx)
    val nPerT = calendar.nPerT
    val previous = facts.previous.firstOrNull()
    val legumeCredit = if (previous != null && previous.crop.profile.family == CropFamily.LEGUME) 30 else 0
    val om = parcel.soil?.organicMatterPct
    val omCredit = if (om != null) roundInt(min(40.0, om * 10)) else 0
    val nRate = max(0, roundInt(nPerT * estimate.expected - legumeCredit - omCredit))
    val status = if (facts.season?.sowingDate != null) nitrogenStatus(facts) else null

    if (facts.season?.sowingDate == null) {
        val sowing = usualSowingDate(crop, ctx.today)
        val target = addDays(sowing, -10)
        answer.direct = "Apply base fertilization on ${parcel.name} around ${dayLabel(target, ctx.today)}, about 10 days before ${cropName(crop)} sowing: roughly ${rounded(calendar.pPerT * estimate.expected)} kg P₂O₅ and ${rounded(calendar.kPerT * estimate.expected)} kg K₂O per ha, plus part of the nitrogen (${rounded(nRate * 0.4)} of $nRate kg N/ha)."
        answer.proposals.add(
            propose(
                ctx.snapshot,
                ProposalInput(
                    "FERTILIZATION", parcel.id, if (target < ctx.today) addDays(ctx.today, 2) else target, CopilotPriority.MEDIUM,
                    "Base P and K before ${cropName(crop)} sowing, sized for ${t(estimate.expected)} t/ha.", 7,
                ),
            ),
        )
    } else if (status?.due == null) {
        answer.direct = "${crop.profile.name} on ${parcel.name} does not need top-dressing nitrogen: it fixes its own. If growth looks pale, a foliar feed with micronutrients is enough."
        answer.proposals.add(propose(ctx.snapshot, ProposalInput("CROP_SCOUTING", parcel.id, addDays(ctx.today, 7), CopilotPriority.LOW, "Check nodulation and leaf colour before deciding on a foliar feed.")))
    } else if (status.done != null) {
        answer.direct = "Nitrogen was already applied on ${parcel.name} on ${dayLabel(status.done, ctx.today)}. No further top-dressing is due; scout the crop to confirm it responded."
        answer.proposals.add(propose(ctx.snapshot, ProposalInput("CROP_SCOUTING", parcel.id, addDays(ctx.today, 5), CopilotPriority.LOW, "Confirm the response to the nitrogen applied.")))
    } else {
        val due = status.due
        val late = due < ctx.today
        var target = if (late) ctx.today else due
        // Avoid heavy rain (leaching and runoff) and strong wind; light rain after spreading is welcome.
        if (weather != null && weather.rain48h >= 15 && daysBetween(ctx.today, target) <= 2) {
            target = weather.firstDryDay?.takeIf { it > ctx.today } ?: addDays(ctx.today, 3)
        }
        val window = weather?.fertilizeWindows?.firstOrNull()
        val dose = rounded(nRate * 0.6)
        answer.direct = if (late) {
            "Nitrogen on ${parcel.name} is overdue: the top-dressing for ${cropName(crop)} was due ${relativeDays(due, ctx.today)}. Apply about $dose kg N/ha (of a $nRate kg N/ha season total) as soon as possible, ideally ${dayLabel(target, ctx.today)}."
        } else {
            "Apply nitrogen on ${parcel.name} around ${dayLabel(target, ctx.today)} (${relativeDays(target, ctx.today)}), at the ${cropName(crop)} top-dressing stage: about $dose kg N/ha of a $nRate kg N/ha season total."
        }
        if (window != null) answer.reasoning.add("Best spreading window in the forecast: ${window.label}.")
        answer.proposals.add(
            propose(
                ctx.snapshot,
                ProposalInput(
                    type = "NITROGEN",
                    parcelId = parcel.id,
                    dueDate = target,
                    priority = if (late || daysBetween(ctx.today, target) <= 7) CopilotPriority.HIGH else CopilotPriority.MEDIUM,
                    reason = "${crop.profile.name} top-dressing, $dose kg N/ha${if (late) " (overdue)" else ""}.",
                    windowDays = 5,
                    title = "Apply nitrogen fertilizer – ${parcel.name}",
                ),
            ),
        )
    }
    answer.reasoning.add(
        0,
        "Nitrogen need ≈ ${jsNum(nPerT)} kg N/t × ${t(estimate.expected)} t/ha = ${rounded(nPerT * estimate.expected)} kg N/ha" +
            (if (legumeCredit != 0) ", minus $legumeCredit kg from the previous legume" else "") +
            (if (omCredit != 0) ", minus $omCredit kg mineralised from ${jsNum(parcel.soil!!.organicMatterPct!!)}% organic matter" else "") + ".",
    )
    if (weather != null) {
        if (weather.rain48h >= 15) {
            answer.risks.add("${jsNum(weather.rain48h)} mm of rain is expected within 48 hours: spreading now risks leaching and runoff.")
        } else if (weather.rain48h >= 2) {
            answer.reasoning.add("Light rain (${jsNum(weather.rain48h)} mm) in the next 48 hours will help dissolve granular nitrogen.")
        }
        if (weather.maxWind48h > 25) answer.risks.add("Wind up to ${jsNum(weather.maxWind48h)} km/h makes spreading uneven.")
        if ((weather.minTemp7d ?: 10.0) <= 0) answer.risks.add("Frost is forecast: avoid spreading on frozen soil.")
    }
    if (nRate >= 150) answer.risks.add("High nitrogen rate ($nRate kg N/ha): split it into two passes to limit losses.")
    answer.alternatives.replaceWith(
        listOf("Split the dose into two passes 2–3 weeks apart for better uptake.", "Use a stabilised (inhibitor) urea if rain is uncertain, to reduce volatilisation."),
    )
    if (ctx.snapshot.canSeeFinance) {
        answer.economics.replaceWith(
            listOf("At about 1.1 €/kg N, $nRate kg N/ha costs ≈ ${euroHa(nRate * 1.1)} (${euro(nRate * 1.1 * parcel.areaHa)} for the parcel). Under-fertilizing by 30 kg N/ha can cost 0.5–1 t/ha."),
        )
    }
    answer.nextSteps.replaceWith(
        if (answer.proposals.isNotEmpty()) {
            listOf("Create the task for ${dayLabel(answer.proposals.first().dueDate, ctx.today)}.", "Check the spreader calibration before the pass.")
        } else {
            emptyList()
        },
    )
    answer.dataGaps.replaceWith(parcelGaps(facts, ctx, GapNeeds(soil = true, weather = true)))
    answer.confidence = confidenceOf(answer.dataGaps)
    return answer
}

/** "When should I harvest?" */
fun harvest(ctx: Context, parcel: CopilotParcel): AnswerBuilder {
    val facts = parcelFacts(ctx.snapshot, parcel)
    val answer = blank(CopilotTopic.HARVEST, ctx, parcel.id)
    val crop = facts.crop
    answer.crop = crop
    val season = facts.season
    if (crop == null || season == null) {
        answer.direct = "${parcel.name} has no crop recorded this season, so there is nothing to harvest."
        answer.confidence = Confidence.LOW
        return answer
    }
    val harvested = season.harvestDate
    if (harvested != null && harvested.take(10) <= ctx.today) {
        answer.direct = "${crop.profile.name} on ${parcel.name} was harvested on ${dayLabel(harvested, ctx.today)}."
        answer.nextSteps.replaceWith(listOf("Plan soil sampling and the tillage for the next crop."))
        answer.proposals.replaceWith(propose(ctx.snapshot, ProposalInput("HARVEST", parcel.id, harvested, CopilotPriority.LOW, "")).followUps)
        return answer
    }
    val expected = expectedHarvest(facts)
    if (expected == null) {
        answer.direct = "The sowing date of ${cropName(crop)} on ${parcel.name} is not recorded, so the harvest date cannot be estimated. Record it in the parcel’s season."
        answer.confidence = Confidence.LOW
        return answer
    }
    var start = if (expected < ctx.today) ctx.today else expected
    val weather = ctx.weather
    val soon = daysBetween(ctx.today, start) <= 7
    val window = weather?.harvestWindows?.firstOrNull()
    if (soon && window != null) start = if (window.start.take(10) >= ctx.today) window.start.take(10) else start
    val progress = facts.progress
    answer.direct = "Harvest ${cropName(crop)} on ${parcel.name} around ${dayLabel(start, ctx.today)} (${relativeDays(start, ctx.today)})${if (progress != null) ": the crop is at ${progress.percent}% of its season" else ""}."
    answer.reasoning.replaceWith(
        listOf(
            season.sowingDate?.let {
                "Sown on ${dayLabel(it, ctx.today)}; ${cropName(crop)} usually needs about ${CROP_CALENDARS.getValue(crop).harvestDays} days to harvest."
            } ?: "Estimated from the crop’s growth progress.",
        ),
    )
    if (window != null && soon) answer.reasoning.add("Dry harvesting window in the forecast: ${window.label}.")
    if (weather != null && soon && weather.rain48h >= 5) answer.risks.add("${jsNum(weather.rain48h)} mm of rain in the next 48 hours: grain moisture will rise and drying costs with it.")
    if (crop == CropCode.RAPESEED || crop == CropCode.SUNFLOWER) answer.risks.add("${crop.profile.name} shatters or lodges when left too long: do not delay once it is ripe.")
    if (crop == CropCode.CORN) answer.risks.add("Harvesting corn above 25% moisture raises drying costs; below 18% field losses grow.")
    val combines = ctx.snapshot.machines.filter { it.type == "COMBINE" }
    if (combines.isEmpty()) answer.risks.add("There is no combine in the fleet: book a contractor in time.")
    val sameDay = ctx.snapshot.tasks.filter {
        it.type == "HARVEST" && it.status != CopilotTaskStatus.DONE && it.parcelId != parcel.id && abs(daysBetween(it.dueDate, start)) <= 2
    }
    if (combines.isNotEmpty() && sameDay.size >= combines.size) {
        answer.risks.add("Machinery conflict: ${sameDay.size} other harvest task(s) fall within 2 days with ${combines.size} combine(s).")
    }
    val estimate = estimateYield(facts, crop, ctx)
    answer.alternatives.replaceWith(listOf("Harvest earlier at higher moisture and dry the grain, if rain threatens a ripe crop."))
    if (ctx.snapshot.canSeeFinance) {
        val profit = estimateProfit(facts, crop, estimate.expected)
        answer.economics.replaceWith(
            listOf(
                "Expected ${t(estimate.expected)} t/ha × ${t(parcel.areaHa)} ha ≈ ${t(estimate.expected * parcel.areaHa)} t, worth about ${euro(profit.revenuePerHa * parcel.areaHa)}. Each week of delay on a ripe crop can lose 1–3% to shattering and lodging.",
            ),
        )
    }
    val existing = facts.open.firstOrNull { it.type == "HARVEST" }
    if (existing != null) {
        answer.nextSteps.add("A harvest task is already planned for ${dayLabel(existing.dueDate, ctx.today)}.")
        if (abs(daysBetween(existing.dueDate, start)) >= 3) {
            answer.changes.add(
                TaskChange(
                    "c-${existing.id}", ChangeKind.RESCHEDULE, existing.id, existing.title, parcel.id, existing.dueDate.take(10),
                    toDate = start, fromPriority = existing.priority, reason = "Aligned with the estimated maturity and the forecast.",
                ),
            )
        }
    } else {
        if (daysBetween(ctx.today, start) >= 5) {
            answer.proposals.add(
                propose(
                    ctx.snapshot,
                    ProposalInput("HARVEST_PREP", parcel.id, addDays(start, -5), CopilotPriority.MEDIUM, "Service the combine and prepare trailers and storage before harvest.", 3, withFollowUps = false),
                ),
            )
        }
        answer.proposals.add(
            propose(
                ctx.snapshot,
                ProposalInput(
                    "HARVEST", parcel.id, start, if (soon) CopilotPriority.HIGH else CopilotPriority.MEDIUM,
                    "Estimated maturity of ${cropName(crop)}${if (window != null && soon) " and a dry window in the forecast" else ""}.", 7, "Harvest ${parcel.name}",
                ),
            ),
        )
        answer.nextSteps.replaceWith(listOf("Check grain moisture a few days before.", "Arrange trailers and storage."))
    }
    answer.dataGaps.replaceWith(parcelGaps(facts, ctx, GapNeeds(weather = soon)))
    answer.confidence = if (season.sowingDate != null) confidenceOf(answer.dataGaps) else Confidence.LOW
    return answer
}

private fun cropCoefficient(percent: Int) = when {
    percent < 25 -> 0.5
    percent < 75 -> 1.1
    else -> 0.75
}

/** "Should I irrigate this week?" */
fun irrigate(ctx: Context, parcel: CopilotParcel): AnswerBuilder {
    val facts = parcelFacts(ctx.snapshot, parcel)
    val answer = blank(CopilotTopic.IRRIGATE, ctx, parcel.id)
    val weather = ctx.weather
    val crop = facts.crop
    answer.crop = crop
    val running = facts.activeRuns.firstOrNull { it.kind == RunKind.IRRIGATION }
    if (weather == null) {
        answer.direct = "I cannot judge irrigation on ${parcel.name} without the forecast. Check soil moisture in the field."
        answer.proposals.add(propose(ctx.snapshot, ProposalInput("MOISTURE_CHECK", parcel.id, ctx.today, CopilotPriority.MEDIUM, "No forecast available.")))
        answer.dataGaps.replaceWith(parcelGaps(facts, ctx, GapNeeds(weather = true)))
        answer.confidence = Confidence.LOW
        return answer
    }
    val progress = facts.progress
    val kc = if (progress != null && progress.status == CropProgressStatus.GROWING) cropCoefficient(progress.percent) else 0.4
    val need = roundInt(weather.et0_7d * kc)
    val deficit = roundInt(need - weather.rain7d)
    val critical = crop != null && run {
        val month = ctx.today.substring(5, 7).toInt()
        val calendar = CROP_CALENDARS.getValue(crop)
        month >= calendar.criticalFrom && month <= calendar.criticalTo
    }
    answer.reasoning.replaceWith(
        listOf(
            "Crop water use over 7 days ≈ ET₀ ${jsNum(weather.et0_7d)} mm × Kc ${jsNum(kc)} = $need mm; forecast rain ${jsNum(weather.rain7d)} mm; balance ${if (deficit > 0) "−$deficit" else "+${-deficit}"} mm.",
        ),
    )
    weather.past30RainMm?.let { answer.reasoning.add("Rain in the last 30 days: ${rounded(it)} mm.") }
    if (critical && crop != null) answer.reasoning.add("${crop.profile.name} is in its critical water period: shortage now costs the most yield.")
    when {
        running != null -> {
            answer.direct = "Irrigation is already running on ${parcel.name}. ${if (deficit > 0) "The 7-day deficit is $deficit mm, so keep it going." else "Rain will cover the need, so you can stop after this pass."}"
            if (deficit <= 0) {
                answer.proposals.add(
                    propose(ctx.snapshot, ProposalInput("IRRIGATION_STOP", parcel.id, ctx.today, CopilotPriority.MEDIUM, "Forecast rain (${jsNum(weather.rain7d)} mm) covers the crop’s need.", withFollowUps = false)),
                )
            }
        }
        !facts.irrigated -> {
            answer.direct = "${parcel.name} has no irrigation configured. ${if (deficit > 15) "The crop faces a $deficit mm water deficit this week; consider moisture-saving measures." else "Rain should cover the crop’s needs this week."}"
            answer.alternatives.replaceWith(
                listOf("Set the irrigated area in the parcel settings if part of it can be watered.", "Reduce evaporation by keeping residue on the surface (minimum or no tillage)."),
            )
        }
        deficit > 20 && weather.rain48h < 10 -> {
            val dose = min(35, max(20, roundInt(deficit * 0.8)))
            val start = if (weather.rain48h >= 3) addDays(ctx.today, 2) else ctx.today
            answer.direct = "Yes, irrigate ${parcel.name} this week: the crop will be $deficit mm short. Apply about $dose mm starting ${dayLabel(start, ctx.today)}."
            answer.proposals.add(
                propose(
                    ctx.snapshot,
                    ProposalInput("IRRIGATION_START", parcel.id, start, if (critical) CopilotPriority.HIGH else CopilotPriority.MEDIUM, "$deficit mm deficit over 7 days; dose $dose mm.", 2),
                ),
            )
        }
        weather.rain48h >= 10 -> {
            answer.direct = "No, wait: ${jsNum(weather.rain48h)} mm of rain is expected within 48 hours on ${parcel.name}. Re-check after the rain."
            answer.proposals.add(propose(ctx.snapshot, ProposalInput("MOISTURE_CHECK", parcel.id, addDays(ctx.today, 3), CopilotPriority.LOW, "Check soil moisture after the forecast rain.")))
        }
        else -> {
            answer.direct = "Not needed this week on ${parcel.name}: the water balance is ${if (deficit > 0) "only $deficit mm short" else "${-deficit} mm in surplus"}."
        }
    }
    if ((weather.maxTemp7d ?: 0.0) >= 32) answer.risks.add("Heat up to ${rounded(weather.maxTemp7d!!)} °C will raise water use above the estimate.")
    if (weather.maxWind48h > 30) answer.risks.add("Wind up to ${jsNum(weather.maxWind48h)} km/h reduces sprinkler uniformity: irrigate at night or early morning.")
    if (ctx.snapshot.canSeeFinance && facts.irrigated) {
        answer.economics.replaceWith(listOf("Each 25 mm pass costs roughly 25–45 €/ha in energy and labour; in the critical period it protects 10–30% of yield."))
    }
    answer.nextSteps.replaceWith(if (answer.proposals.isNotEmpty()) listOf("Irrigate at night or early morning to cut evaporation losses.") else emptyList())
    answer.dataGaps.replaceWith(parcelGaps(facts, ctx))
    answer.confidence = if (facts.progress != null) Confidence.MEDIUM else Confidence.LOW
    return answer
}

data class Treatment(val type: String, val reason: String)

/** Which treatment the crop stage calls for. */
fun dueTreatment(facts: ParcelFacts): Treatment? {
    val crop = facts.crop ?: return null
    val sowing = facts.season?.sowingDate ?: return null
    if (facts.progress?.status != CropProgressStatus.GROWING) return null
    val day = daysBetween(sowing, facts.today)
    val calendar = CROP_CALENDARS.getValue(crop)
    if (day <= 45) return Treatment("HERBICIDE", "Early post-emergence weed control (day $day after sowing).")
    if (abs(day - calendar.diseaseDays) <= 14) return Treatment("FUNGICIDE", "The disease-risk stage of ${cropName(crop)} (around day ${calendar.diseaseDays}).")
    if (abs(day - calendar.pestDays) <= 14) return Treatment("INSECTICIDE", "The pest-risk stage of ${cropName(crop)} (around day ${calendar.pestDays}).")
    return null
}

/** "Can I spray?" */
fun spray(ctx: Context, parcel: CopilotParcel): AnswerBuilder {
    val facts = parcelFacts(ctx.snapshot, parcel)
    val answer = blank(CopilotTopic.SPRAY, ctx, parcel.id)
    val weather = ctx.weather
    val treatment = dueTreatment(facts)
    val planned = facts.open.firstOrNull { taskIsSpray(it.type) }
    val window = weather?.sprayWindows?.firstOrNull()
    val blocked = weather != null && (weather.rain48h >= 2 || weather.rainChance48h >= 60 || (weather.hoursToRain != null && weather.hoursToRain < 6))
    val windy = weather != null && weather.maxWind48h > 15
    answer.direct = when {
        weather == null -> "I cannot check spraying conditions on ${parcel.name} without the forecast."
        blocked ->
            "Rain is expected within 48 hours (${jsNum(weather.rain48h)} mm, up to ${jsNum(weather.rainChance48h)}% chance). Spraying ${parcel.name} is not recommended now${if (window != null) "; the next good window is ${window.label}" else ""}."
        window != null -> "Good spraying conditions on ${parcel.name}: best window ${window.label} (score ${window.score}/100)."
        else -> "No suitable spraying window on ${parcel.name} in the forecast${if (windy) ": wind reaches ${jsNum(weather.maxWind48h)} km/h" else ""}."
    }
    answer.reasoning.replaceWith(
        if (weather != null) {
            listOf(
                "Spraying needs wind under 15 km/h, no rain for a few hours after the pass, 8–25 °C and humidity above 45%.",
                "Next 48 hours: ${jsNum(weather.rain48h)} mm of rain, wind up to ${jsNum(weather.maxWind48h)} km/h.",
            )
        } else {
            emptyList()
        },
    )
    if (treatment != null) answer.reasoning.add("Crop stage: ${treatment.reason}")
    if (windy) answer.risks.add("Drift risk: wind up to ${jsNum(weather!!.maxWind48h)} km/h.")
    if (blocked) answer.risks.add("Rain shortly after the pass washes off contact products and wastes the treatment.")
    if ((weather?.maxTemp7d ?: 0.0) > 28) answer.risks.add("Heat above 28 °C increases evaporation and crop injury: spray early morning or evening.")
    answer.alternatives.replaceWith(listOf("Spray in the early morning, when wind and temperature are lowest.", "Use drift-reducing nozzles if the wind is borderline."))
    val day = window?.start?.take(10) ?: weather?.firstDryDay ?: addDays(ctx.today, 1)
    if (planned != null) {
        answer.nextSteps.add("“${planned.title}” is planned for ${dayLabel(planned.dueDate, ctx.today)}.")
        if (window != null && planned.dueDate.take(10) != day && (blocked || planned.dueDate.take(10) < ctx.today)) {
            answer.changes.add(
                TaskChange(
                    "c-${planned.id}", ChangeKind.RESCHEDULE, planned.id, planned.title, parcel.id, planned.dueDate.take(10),
                    toDate = day, fromPriority = planned.priority, reason = "Moved to the best spraying window (${window.label}).",
                ),
            )
        }
    } else if (treatment != null) {
        answer.proposals.add(
            propose(
                ctx.snapshot,
                ProposalInput(treatment.type, parcel.id, day, CopilotPriority.HIGH, "${treatment.reason}${if (window != null) " Best window: ${window.label}." else ""}", 3),
            ),
        )
    }
    if (ctx.snapshot.canSeeFinance) {
        answer.economics.replaceWith(listOf("A treatment typically costs 30–80 €/ha in product plus 10–15 €/ha to apply; a washed-off pass doubles that cost."))
    }
    answer.nextSteps.add("Follow the product label for dose and pre-harvest interval.")
    answer.dataGaps.replaceWith(parcelGaps(facts, ctx, GapNeeds(weather = true)))
    answer.confidence = confidenceOf(answer.dataGaps)
    return answer
}

fun taskIsSpray(type: String) = type == "HERBICIDE" || type == "FUNGICIDE" || type == "INSECTICIDE" || type == "FOLIAR_FEEDING"

/** "What yield can I expect?" */
fun yieldAnswer(ctx: Context, parcel: CopilotParcel): AnswerBuilder {
    val facts = parcelFacts(ctx.snapshot, parcel)
    val crop = ctx.crop ?: facts.crop
    val answer = blank(CopilotTopic.YIELD, ctx, parcel.id)
    answer.crop = crop
    if (crop == null) {
        answer.direct = "${parcel.name} has no crop recorded this season. Name a crop (for example “What yield can I expect from corn here?”)."
        answer.confidence = Confidence.LOW
        return answer
    }
    val estimate = estimateYield(facts, crop, ctx)
    answer.direct = "Expected ${cropName(crop)} yield on ${parcel.name}: ${t(estimate.low)}–${t(estimate.high)} t/ha (most likely ${t(estimate.expected)} t/ha), about ${t(estimate.expected * parcel.areaHa)} t in total."
    answer.reasoning.replaceWith(
        listOf("Regional reference yield for ${cropName(crop)}: ${jsNum(crop.profile.referenceYieldTHa)} t/ha.") +
            estimate.factors.map { "${it.replaceFirstChar { c -> c.uppercaseChar() }}." },
    )
    val history = facts.yields.filter { it.crop == crop }
    if (history.isNotEmpty()) answer.reasoning.add("History: ${history.joinToString(", ") { "${it.year}: ${jsNum(it.tHa)} t/ha" }}.")
    answer.risks.replaceWith(cropRisks(facts, crop, facts.season?.year ?: facts.year, estimate.expected).take(3))
    if (ctx.snapshot.canSeeFinance) {
        val profit = estimateProfit(facts, crop, estimate.expected)
        val low = estimateProfit(facts, crop, estimate.low)
        answer.economics.replaceWith(
            listOf("At ${euro(profit.pricePerT)}/t: revenue ≈ ${euroHa(profit.revenuePerHa)}, profit ≈ ${euroHa(profit.profitPerHa)} (${euroHa(low.profitPerHa)} in the low case)."),
        )
    }
    answer.nextSteps.replaceWith(listOf("Record the actual yield after harvest: future estimates use your own history."))
    answer.alternatives.replaceWith(if (facts.irrigated) emptyList() else listOf("Irrigation in the critical period is the biggest lever on yield for water-demanding crops."))
    answer.dataGaps.replaceWith(parcelGaps(facts, ctx, GapNeeds(soil = true, history = true)))
    answer.confidence = confidenceOf(answer.dataGaps)
    return answer
}

data class TillageFit(val score: Int, val pros: List<String>, val cons: List<String>)

/** How well a parcel suits a tillage system, 0-100, with the reasons. */
fun tillageFit(facts: ParcelFacts, system: TillageSystem): TillageFit {
    var score = 60.0
    val pros = mutableListOf<String>()
    val cons = mutableListOf<String>()
    val reduced = system == TillageSystem.NO_TILL || system == TillageSystem.STRIP_TILL
    when (facts.texture) {
        SoilTexture.LOAMY -> {
            score += 15
            pros.add("loamy soil keeps its structure without tillage")
        }
        SoilTexture.SANDY -> {
            score += if (reduced) 10 else 0
            pros.add("sandy soil gains the most water retention from residue cover")
        }
        SoilTexture.CLAY -> {
            score -= if (reduced) 15 else 0
            cons.add("heavy clay stays cold and wet in spring and compacts without loosening")
        }
        SoilTexture.CLAY_LOAM -> score += 5
        null -> Unit
    }
    val om = facts.parcel.soil?.organicMatterPct
    if (om != null) {
        if (om >= 2.5) {
            score += 10
            pros.add("organic matter of ${jsNum(om)}% supports biological soil structure")
        } else if (reduced) {
            score -= 5
            cons.add("low organic matter (${jsNum(om)}%) means a slower transition")
        }
    }
    val crops = facts.parcel.seasons.take(4).map { it.crop }.toSet()
    if (crops.size >= 3) {
        score += 10
        pros.add("a diverse rotation (${crops.size} crops in 4 years) limits weeds and disease under residue")
    } else if (reduced && facts.parcel.seasons.size >= 2) {
        score -= 10
        cons.add("a narrow rotation raises weed and disease pressure under residue")
    }
    val weeds = facts.parcel.observations.any { mentions(normalize(it.text), listOf("weed", "buruien", "samulastra", "pir", "costrei")) }
    if (weeds && reduced) {
        score -= 10
        cons.add("field notes mention weed problems, which tillage currently helps to control")
    }
    if (!reduced && system == TillageSystem.CONVENTIONAL) {
        score -= 5
        cons.add("ploughing costs the most fuel and breaks down organic matter")
    }
    return TillageFit(roundInt(score).coerceIn(0, 100), pros, cons)
}

/** "Is No-Till a better option here?" */
fun tillageAnswer(ctx: Context, parcel: CopilotParcel, target: TillageSystem): AnswerBuilder {
    val facts = parcelFacts(ctx.snapshot, parcel)
    val answer = blank(CopilotTopic.TILLAGE, ctx, parcel.id)
    val current = parcel.tillage
    val fit = tillageFit(facts, target)
    val profile = target.profile
    val from = (current ?: TillageSystem.CONVENTIONAL).profile
    val saving = (from.costPerHa - profile.costPerHa) * parcel.areaHa
    val fuel = (from.fuelLPerHa - profile.fuelLPerHa) * parcel.areaHa
    if (current == target) {
        answer.direct = "${parcel.name} already uses ${profile.name}. Suitability score ${fit.score}/100."
    } else {
        val verdict = when {
            fit.score >= 70 -> "a good option"
            fit.score >= 50 -> "possible, with care"
            else -> "not a good fit right now"
        }
        answer.direct = "${profile.name} is $verdict for ${parcel.name} (suitability ${fit.score}/100). Compared with ${from.name}, it ${if (saving >= 0) "saves" else "costs"} about ${euro(abs(saving))} and ${rounded(abs(fuel))} l of diesel per season on ${t(parcel.areaHa)} ha."
    }
    answer.reasoning.replaceWith(
        fit.pros.map { "+ $it" } + fit.cons.map { "− $it" } +
            "Soil health ${from.soilHealth} → ${profile.soilHealth}/100; water retention +${profile.waterGainPct - from.waterGainPct}%.",
    )
    answer.risks.replaceWith(fit.cons)
    val reduced = target == TillageSystem.NO_TILL || target == TillageSystem.STRIP_TILL
    if (reduced) {
        answer.risks.add("Yields can dip 5–10% in the first 2–3 transition years while the soil biology adapts.")
        answer.risks.add("Direct sowing needs a suitable drill (a conventional seed drill cannot cut through residue).")
    }
    if (reduced && ctx.snapshot.machines.none { it.type == "SEEDER" }) answer.risks.add("There is no seed drill in the fleet.")
    answer.alternatives.replaceWith(
        listOf(TillageSystem.MINIMUM_TILL, TillageSystem.STRIP_TILL, TillageSystem.NO_TILL, TillageSystem.CONVENTIONAL)
            .filter { it != target && it != current }
            .map { it to tillageFit(facts, it) }
            .sortedByDescending { it.second.score }
            .take(2)
            .map { (code, codeFit) -> "${code.profile.name}: suitability ${codeFit.score}/100, ${euroHa(code.profile.costPerHa)}." },
    )
    if (ctx.snapshot.canSeeFinance) {
        answer.economics.replaceWith(
            listOf(
                "Tillage cost ${euroHa(from.costPerHa)} → ${euroHa(profile.costPerHa)}: ${if (saving >= 0) "saving" else "extra"} ${euro(abs(saving))} per season.",
                "Fuel ${jsNum(from.fuelLPerHa)} → ${jsNum(profile.fuelLPerHa)} l/ha.",
            ),
        )
    }
    answer.nextSteps.replaceWith(
        if (current != target && fit.score >= 50) {
            listOf("Start on one parcel or part of it and compare yields for two seasons.", "Change the tillage system in the parcel settings when you decide.")
        } else {
            emptyList()
        },
    )
    if (parcel.soil == null) {
        answer.proposals.add(
            propose(ctx.snapshot, ProposalInput("SOIL_SAMPLING", parcel.id, addDays(ctx.today, 7), CopilotPriority.LOW, "Organic matter and texture decide how well reduced tillage will work.", 14)),
        )
    }
    answer.dataGaps.replaceWith(parcelGaps(facts, ctx, GapNeeds(soil = true)))
    answer.confidence = confidenceOf(answer.dataGaps)
    return answer
}

/** Profit of a parcel and how it compares with the farm. */
fun profitability(ctx: Context, parcel: CopilotParcel, farmAveragePerHa: Double?): AnswerBuilder {
    val facts = parcelFacts(ctx.snapshot, parcel)
    val answer = blank(CopilotTopic.PROFITABILITY, ctx, parcel.id)
    val profit = parcelProfitPerHa(facts, ctx)
    if (profit == null) {
        answer.direct = "${parcel.name} has no crop or cost data this season, so its profitability cannot be calculated."
        answer.nextSteps.replaceWith(listOf("Record the crop and the expected yield and price in the parcel’s economics tab."))
        answer.confidence = Confidence.LOW
        return answer
    }
    val basis = when (profit.basis) {
        ProfitBasis.ACTUAL -> "actual"
        ProfitBasis.EXPECTED -> "expected"
        ProfitBasis.ESTIMATE -> "estimated"
    }
    val versus = if (farmAveragePerHa != null && farmAveragePerHa != 0.0) ((profit.value - farmAveragePerHa) / abs(farmAveragePerHa)) * 100 else null
    answer.direct = "${parcel.name}: $basis profit ${euroHa(profit.value)} (${euro(profit.value * parcel.areaHa)} on ${t(parcel.areaHa)} ha)" +
        if (versus != null && abs(versus) >= 3) ", ${rounded(abs(versus))}% ${if (versus > 0) "more" else "less"} profitable than the farm average." else "."
    val economics = parcel.economics
    if (economics != null) {
        val shares = economics.costShares.entries.sortedByDescending { it.value }
        val biggest = if (shares.isNotEmpty()) "; biggest items: ${shares.take(3).joinToString(", ") { "${it.key} ${jsNum(it.value)}%" }}" else ""
        answer.reasoning.add("Production cost ${euroHa(economics.costPerHa)}$biggest.")
        economics.revenuePerHa?.let { answer.reasoning.add("Revenue ${euroHa(it)}.") }
    } else {
        answer.reasoning.add("No costs are recorded yet, so this uses a typical cost of ${euroHa(profit.costPerHa)} and the expected yield.")
    }
    val roi = if (profit.costPerHa > 0) (profit.value / profit.costPerHa) * 100 else null
    if (roi != null) answer.economics.add("Return on cost (ROI): ${signedPct(roi)}.")
    if (versus != null) answer.economics.add("Farm average: ${euroHa(farmAveragePerHa!!)}.")
    if (profit.value < 0) answer.risks.add("The parcel is losing money at current yields and prices.")
    if (versus != null && versus < -15) answer.risks.add("It earns ${rounded(abs(versus))}% less than the farm average: check costs, yield and the crop choice.")
    answer.alternatives.replaceWith(
        if (facts.parcel.tillage == TillageSystem.CONVENTIONAL) {
            listOf("Switching to Minimum Till would cut tillage cost by about ${euroHa(TillageSystem.CONVENTIONAL.profile.costPerHa - TillageSystem.MINIMUM_TILL.profile.costPerHa)}.")
        } else {
            emptyList()
        },
    )
    answer.nextSteps.replaceWith(listOf("Ask “How can I reduce costs here?” for concrete savings."))
    answer.dataGaps.replaceWith(parcelGaps(facts, ctx, GapNeeds(costs = true)))
    answer.confidence = if (profit.basis == ProfitBasis.ESTIMATE) Confidence.LOW else Confidence.HIGH
    return answer
}
