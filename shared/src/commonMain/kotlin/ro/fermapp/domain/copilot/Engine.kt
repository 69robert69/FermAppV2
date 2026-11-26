package ro.fermapp.domain.copilot

import ro.fermapp.domain.CropCode
import ro.fermapp.domain.TillageSystem
import ro.fermapp.domain.jsNum
import ro.fermapp.domain.jsRound
import ro.fermapp.domain.profile

// ------------------------------------------------------------------ topics

private fun has(text: String, words: List<String>) = mentions(text, words)

private val WHY_START = Regex("^(why|de ce|explain|explica|how come|on what basis)\\b")
private val WHY_ONLY = Regex("^(why|de ce)\\??$")

/** The first matching rule wins, so specific questions come before broad ones. */
private val RULES: List<Pair<CopilotTopic, (String) -> Boolean>> = listOf(
    CopilotTopic.CREATE_TASK to { s ->
        has(
            s,
            listOf(
                "create task", "create the task", "create a task", "create them", "create these", "create all", "add task", "add the task",
                "add a task", "add them", "add these", "add it", "add all", "add to", "put them", "put it", "new task", "make a task",
                "remind me", "creeaza", "adauga", "pune-le", "pune-l", "pune in", "pune task", "baga", "introdu", "salveaza task",
                "programeaza", "plan a task", "fa task", "fa un task",
            ),
        )
    },
    CopilotTopic.RESCHEDULE to { s ->
        has(s, listOf("move$", "reschedule", "delay$", "postpone", "push back", "push it", "bring forward", "shift$", "muta$", "amana$", "amana-", "reprogrameaza", "decaleaza"))
    },
    CopilotTopic.CANCEL to { s -> has(s, listOf("cancel", "delete the task", "delete task", "remove the task", "remove task", "drop the", "anuleaza", "sterge")) },
    CopilotTopic.GROUP to { s -> has(s, listOf("group$", "combine the tasks", "batch", "grupeaza")) },
    CopilotTopic.REPRIORITIZE to { s ->
        has(s, listOf("prioritize", "prioritise", "make it urgent", "mark as urgent", "set priority", "high priority", "prioritizeaza", "urgent")) &&
            !has(s, listOf("which", "what", "care", "ce "))
    },
    CopilotTopic.WHY to { s -> WHY_START.containsMatchIn(s) || WHY_ONLY.containsMatchIn(s) },
    CopilotTopic.HECTARES_LEFT to { s -> has(s, listOf("how many hectares", "how many ha", "hectares still", "hectares left", "cate hectare", "ha left", "ha still")) },
    CopilotTopic.IDLE_MACHINES to { s ->
        has(s, listOf("idle", "available", "free", "unused", "liber", "libere", "disponibil", "nefolosit")) &&
            has(s, listOf("tractor", "machine", "machinery", "combine", "equipment", "utilaj", "combina", "masini"))
    },
    CopilotTopic.OVERDUE to { s -> has(s, listOf("overdue", "late$", "delayed", "behind schedule", "missed", "intarziat", "intarziere", "restant", "depasit")) },
    CopilotTopic.ROI_RANKING to { s ->
        has(s, listOf("roi", "most profitable", "least profitable", "highest profit", "lowest profit", "best return", "rank", "cea mai profitabila", "cel mai profitabil", "cea mai putin profitabila", "clasament"))
    },
    CopilotTopic.FARM_PROFIT to { s -> has(s, listOf("farm profit", "profit of the farm", "expected profit this season", "total profit", "profitul fermei", "profit total")) },
    CopilotTopic.REDUCE_COSTS to { s ->
        has(s, listOf("reduce cost", "reduce my cost", "cut cost", "lower cost", "save money", "cheaper", "savings", "reduce costuri", "reduc costuri", "economisi", "scad costuri", "reduce cheltuieli"))
    },
    CopilotTopic.SATELLITE to { s ->
        has(
            s,
            listOf(
                "satellite", "satelit", "ndvi", "ndre", "ndmi", "vegetation index", "vegetation", "crop health", "field health", "health score",
                "how healthy", "vigor", "vigour", "vigoare", "sanatatea", "starea culturii", "imagini", "problem area", "zone cu probleme",
            ),
        )
    },
    CopilotTopic.TILLAGE to { s -> findTillage(s) != null || has(s, listOf("tillage", "lucrarea solului", "lucrari ale solului")) },
    CopilotTopic.CROP_SUITABILITY to { s ->
        findCrop(s) != null && has(
            s,
            listOf(
                "suitable", "suit", "good for", "good idea", "ok for", "fit", "can i plant", "can i grow", "should i plant", "is it worth",
                "potrivit", "merge", "pot sa", "bun pentru", "best for", "cel mai bun",
            ),
        )
    },
    CopilotTopic.NEXT_CROP to { s ->
        has(
            s,
            listOf(
                "plant next", "next year", "next season", "what should i plant", "what to plant", "what should i grow", "what to sow", "rotation",
                "anul viitor", "sezonul viitor", "ce sa semanez", "ce sa cultiv", "ce sa pun", "ce cultura", "rotatie", "rotatia",
            ),
        )
    },
    CopilotTopic.FERTILIZE to { s -> has(s, CATEGORY_WORDS.getValue(TaskCategory.FERTILIZATION)) },
    CopilotTopic.HARVEST to { s -> has(s, CATEGORY_WORDS.getValue(TaskCategory.HARVEST)) },
    CopilotTopic.IRRIGATE to { s -> has(s, CATEGORY_WORDS.getValue(TaskCategory.IRRIGATION)) || has(s, listOf("water the", "watering")) },
    CopilotTopic.SPRAY to { s -> has(s, CATEGORY_WORDS.getValue(TaskCategory.CROP_PROTECTION)) },
    CopilotTopic.YIELD to { s -> has(s, listOf("yield", "production", "t/ha", "tons", "tonnes", "productie", "productia", "randament", "recolta estimata")) },
    CopilotTopic.MACHINERY to { s -> has(s, listOf("machine", "machinery", "tractor", "equipment", "assign", "operator", "utilaj", "utilaje", "combina", "aloc")) },
    CopilotTopic.PROFITABILITY to { s -> has(s, listOf("profit", "margin", "revenue", "economic", "cost", "earn", "money", "castig", "venit", "rentabil", "cheltuiel")) },
    CopilotTopic.WEEK_SCHEDULE to { s ->
        has(s, listOf("this week", "scheduled", "schedule", "agenda", "calendar", "saptamana aceasta", "saptamana asta", "programat", "planificat"))
    },
    CopilotTopic.PENDING to { s ->
        has(s, listOf("pending", "remaining", "still to do", "outstanding", "left to do", "open tasks", "what operations", "ramas", "de facut", "in asteptare", "neterminat"))
    },
    CopilotTopic.PRIORITIZE to { s ->
        has(s, listOf("prioritize", "prioritise", "priority", "first", "most urgent", "focus", "prioritate", "mai intai", "cel mai urgent", "prioritar"))
    },
    CopilotTopic.RISKS to { s ->
        has(
            s,
            listOf(
                "risk", "problem", "danger", "issue", "disease", "pest", "status", "overview", "summary", "how is", "how are",
                "riscuri", "risc", "probleme", "boli", "daunatori", "situatia", "rezumat",
            ),
        )
    },
)

fun detectTopic(text: String): CopilotTopic = RULES.firstOrNull { it.second(text) }?.first ?: CopilotTopic.GENERAL

/** Topics that need one parcel; asked without one they cover every parcel. */
private val PARCEL_TOPICS = setOf(
    CopilotTopic.NEXT_CROP, CopilotTopic.CROP_SUITABILITY, CopilotTopic.FERTILIZE, CopilotTopic.HARVEST, CopilotTopic.IRRIGATE,
    CopilotTopic.SPRAY, CopilotTopic.YIELD, CopilotTopic.TILLAGE, CopilotTopic.PROFITABILITY, CopilotTopic.SATELLITE,
)

private val TOPIC_CATEGORY: Map<CopilotTopic, TaskCategory> = mapOf(
    CopilotTopic.FERTILIZE to TaskCategory.FERTILIZATION,
    CopilotTopic.HARVEST to TaskCategory.HARVEST,
    CopilotTopic.IRRIGATE to TaskCategory.IRRIGATION,
    CopilotTopic.SPRAY to TaskCategory.CROP_PROTECTION,
    CopilotTopic.NEXT_CROP to TaskCategory.PLANTING,
    CopilotTopic.TILLAGE to TaskCategory.TILLAGE,
)

// ------------------------------------------------------------------ entry point

data class CopilotTurn(val answer: CopilotAnswer, val memory: CopilotMemory)

/** Answers a question from the farm data, remembering the parcel and topic for follow-ups. */
fun askCopilot(question: String, snapshot: FarmSnapshot, memory: CopilotMemory): CopilotTurn {
    val text = normalize(question)
    val today = snapshot.now.take(10)
    val named = findParcel(text, snapshot.parcels)
    val remembered = memory.parcelId?.let { id -> snapshot.parcels.firstOrNull { it.id == id } }
    val farmWords = asksAboutFarm(text) && !refersToCurrentParcel(text)
    val only = snapshot.parcels.singleOrNull()
    val parcel = named ?: (if (farmWords) null else remembered) ?: only
    // "Yes" right after suggested tasks approves them.
    var topic = if (isConfirmation(text) && !memory.last?.proposals.isNullOrEmpty()) CopilotTopic.CREATE_TASK else detectTopic(text)
    var crop = findCrop(text)

    // Short follow-ups ("and Field 7?", "what about wheat?") continue the previous topic.
    val previousTopic = memory.topic
    if (topic == CopilotTopic.GENERAL && previousTopic != null && previousTopic != CopilotTopic.WHY &&
        text.split(" ").size <= 7 && (named != null || crop != null || farmWords)
    ) {
        topic = if (crop != null && (previousTopic == CopilotTopic.NEXT_CROP || previousTopic == CopilotTopic.CROP_SUITABILITY)) {
            CopilotTopic.CROP_SUITABILITY
        } else {
            previousTopic
        }
    }
    if (crop == null && (topic == CopilotTopic.YIELD || topic == CopilotTopic.CROP_SUITABILITY) && memory.crop != null && named == null) crop = memory.crop

    val ctx = Context(
        text = text,
        snapshot = snapshot,
        today = today,
        parcel = parcel,
        crop = crop,
        memory = memory,
        farmScope = named == null && (farmWords || parcel == null),
        weather = weatherFacts(snapshot),
        date = parseDate(text, today),
    )

    val answer = when {
        snapshot.parcels.isEmpty() && topic != CopilotTopic.WHY -> noParcels(ctx)
        topic == CopilotTopic.WHY -> why(ctx)
        topic in PARCEL_TOPICS && (ctx.farmScope || parcel == null) -> farmWide(topic, ctx)
        else -> route(topic, ctx, parcel)
    }.build()

    return CopilotTurn(
        answer = answer,
        memory = CopilotMemory(
            parcelId = answer.parcelId ?: if (ctx.farmScope) null else parcel?.id ?: memory.parcelId,
            topic = answer.topic,
            crop = answer.crop ?: crop ?: memory.crop,
            last = if (answer.topic == CopilotTopic.WHY) memory.last else answer,
        ),
    )
}

private fun route(topic: CopilotTopic, ctx: Context, parcel: CopilotParcel?): AnswerBuilder {
    val finance = ctx.snapshot.canSeeFinance
    return when (topic) {
        CopilotTopic.NEXT_CROP -> nextCrop(ctx, parcel!!)
        CopilotTopic.CROP_SUITABILITY -> ctx.crop?.let { suitability(ctx, parcel!!, it) } ?: nextCrop(ctx, parcel!!)
        CopilotTopic.FERTILIZE -> fertilize(ctx, parcel!!)
        CopilotTopic.HARVEST -> harvest(ctx, parcel!!)
        CopilotTopic.IRRIGATE -> irrigate(ctx, parcel!!)
        CopilotTopic.SPRAY -> spray(ctx, parcel!!)
        CopilotTopic.YIELD -> yieldAnswer(ctx, parcel!!)
        CopilotTopic.TILLAGE -> tillageAnswer(ctx, parcel!!, findTillage(ctx.text) ?: TillageSystem.NO_TILL)
        CopilotTopic.PROFITABILITY -> if (finance) profitability(ctx, parcel!!, farmAverageProfit(ctx)) else financeHidden(ctx)
        CopilotTopic.PENDING -> pending(ctx)
        CopilotTopic.OVERDUE -> overdue(ctx)
        CopilotTopic.WEEK_SCHEDULE -> weekSchedule(ctx)
        CopilotTopic.HECTARES_LEFT -> hectaresLeft(ctx)
        CopilotTopic.IDLE_MACHINES -> idleMachines(ctx)
        CopilotTopic.MACHINERY -> machinery(ctx)
        CopilotTopic.ROI_RANKING -> if (finance) roiRanking(ctx) else financeHidden(ctx)
        CopilotTopic.FARM_PROFIT -> if (finance) farmProfit(ctx) else financeHidden(ctx)
        CopilotTopic.REDUCE_COSTS -> if (finance) reduceCosts(ctx) else financeHidden(ctx)
        CopilotTopic.PRIORITIZE -> prioritize(ctx)
        CopilotTopic.RISKS -> risks(ctx)
        CopilotTopic.SATELLITE -> satelliteAnswer(ctx, parcel!!)
        CopilotTopic.RESCHEDULE, CopilotTopic.CANCEL, CopilotTopic.REPRIORITIZE, CopilotTopic.GROUP -> changeTasks(topic, ctx)
        CopilotTopic.CREATE_TASK -> createTask(ctx)
        else -> general(ctx)
    }
}

// ------------------------------------------------------------------ helpers

private fun facts(ctx: Context): List<ParcelFacts> =
    (if (ctx.farmScope || ctx.parcel == null) ctx.snapshot.parcels else listOf(ctx.parcel)).map { parcelFacts(ctx.snapshot, it) }

private fun parcelName(ctx: Context, id: String?) = ctx.snapshot.parcels.firstOrNull { it.id == id }?.name ?: "farm-wide"
private fun machineName(ctx: Context, id: String?) = ctx.snapshot.machines.firstOrNull { it.id == id }?.name
private fun memberName(ctx: Context, id: String?) = ctx.snapshot.members.firstOrNull { it.id == id }?.name
private fun scopeLabel(ctx: Context) = if (ctx.farmScope || ctx.parcel == null) "the farm" else ctx.parcel.name

private fun farmAverageProfit(ctx: Context): Double? {
    val all = ctx.snapshot.parcels.mapNotNull { parcel -> parcelProfitPerHa(parcelFacts(ctx.snapshot, parcel), ctx)?.let { parcel to it } }
    val area = all.sumOf { it.first.areaHa }
    return if (all.size >= 2 && area > 0) all.sumOf { it.second.value * it.first.areaHa } / area else null
}

private fun noParcels(ctx: Context): AnswerBuilder {
    val answer = blank(CopilotTopic.GENERAL, ctx, null)
    answer.direct = "The farm has no parcels yet. Draw or import your fields on the map and I will analyse crops, weather, work and economics for each of them."
    answer.nextSteps.replaceWith(listOf("Open the map and tap “New parcel”, or import a KML / Shapefile / LPIS file."))
    answer.confidence = Confidence.LOW
    return answer
}

private fun financeHidden(ctx: Context): AnswerBuilder {
    val answer = blank(CopilotTopic.PROFITABILITY, ctx)
    answer.direct = "Costs and profit are visible only to managers and agronomists on this farm."
    answer.confidence = Confidence.HIGH
    return answer
}

private fun why(ctx: Context): AnswerBuilder {
    val last = ctx.memory.last
    val answer = blank(CopilotTopic.WHY, ctx, last?.parcelId)
    if (last == null) {
        answer.direct = "Ask me something first, for example “What should I plant on this field next year?”, and then I can explain the reasoning."
        answer.confidence = Confidence.LOW
        return answer
    }
    answer.crop = last.crop
    answer.direct = "Here is the reasoning behind: “${last.direct}”"
    answer.reasoning.replaceWith(last.reasoning + last.dataGaps.map { "Limitation: $it" })
    answer.risks.replaceWith(last.risks)
    answer.alternatives.replaceWith(last.alternatives)
    answer.economics.replaceWith(last.economics)
    answer.nextSteps.replaceWith(last.nextSteps)
    answer.confidence = last.confidence
    return answer
}

// ------------------------------------------------------------------ several parcels

private val SCORE = Regex("score (\\d+)")

private fun farmWide(topic: CopilotTopic, ctx: Context): AnswerBuilder {
    val all = ctx.snapshot.parcels
    val answer = blank(topic, ctx, null)
    when (topic) {
        CopilotTopic.NEXT_CROP -> {
            val results = all.map { it to nextCrop(ctx, it) }
            answer.direct = "Best crop for next season on each parcel: ${results.joinToString("; ") { (parcel, result) -> "${parcel.name} → ${result.crop?.let(::cropName) ?: "?"}" }}."
            answer.reasoning.replaceWith(results.map { (parcel, result) -> "${parcel.name}: ${result.reasoning.firstOrNull() ?: result.direct}" })
            answer.nextSteps.replaceWith(listOf("Ask about one parcel (for example “What should I plant on ${all.first().name}?”) for yields, profit and tasks."))
        }
        CopilotTopic.CROP_SUITABILITY -> {
            val crop = ctx.crop ?: CropCode.CORN
            answer.crop = crop
            data class Ranked(val parcel: CopilotParcel, val result: AnswerBuilder, val score: Int)
            val ranked = all.map { parcel ->
                val result = suitability(ctx, parcel, crop)
                Ranked(parcel, result, SCORE.find(result.direct)?.groupValues?.get(1)?.toInt() ?: 0)
            }.sortedByDescending { it.score }
            answer.direct = "Best parcel for ${cropName(crop)}: ${ranked.first().parcel.name} (score ${ranked.first().score}/100)."
            answer.reasoning.replaceWith(ranked.map { "${it.parcel.name}: ${it.score}/100 – ${it.result.reasoning.firstOrNull() ?: ""}" })
            answer.alternatives.replaceWith(ranked.drop(1).take(2).map { "${it.parcel.name} (${it.score}/100)" })
        }
        CopilotTopic.FERTILIZE -> {
            val horizon = if (mentions(ctx.text, listOf("this week", "saptamana"))) 7 else 14
            val due = facts(ctx).filter { item ->
                val status = nitrogenStatus(item)
                (status?.due != null && status.done == null && daysBetween(ctx.today, status.due) <= horizon) ||
                    item.open.any { taskCategory(it.type) == TaskCategory.FERTILIZATION && daysBetween(ctx.today, it.dueDate) <= horizon }
            }
            if (due.isEmpty()) {
                answer.direct = "No parcel needs fertilization in the next $horizon days."
            } else {
                answer.direct = "${due.size} parcel${if (due.size > 1) "s need" else " needs"} fertilization in the next $horizon days: ${list(due.map { it.parcel.name })}."
                for (item in due) {
                    val result = fertilize(ctx, item.parcel)
                    answer.reasoning.add("${item.parcel.name}: ${result.direct}")
                    answer.proposals.addAll(result.proposals.filter { proposal -> item.open.none { it.type == proposal.type } })
                }
            }
        }
        CopilotTopic.HARVEST -> {
            fun shatter(item: ParcelFacts) = if (item.crop == CropCode.RAPESEED || item.crop == CropCode.SUNFLOWER) -3 else 0
            val ready = facts(ctx)
                .mapNotNull { item -> expectedHarvest(item)?.takeIf { item.season?.harvestDate == null }?.let { item to it } }
                .sortedBy { (item, date) -> daysBetween(ctx.today, date) + shatter(item) }
            if (ready.isEmpty()) {
                answer.direct = "No parcel has a crop waiting for harvest."
            } else {
                val (firstItem, firstDate) = ready.first()
                answer.direct = "Harvest ${firstItem.parcel.name} first: ${cropName(firstItem.crop!!)} is expected ready ${relativeDays(firstDate, ctx.today)}."
                answer.reasoning.replaceWith(
                    ready.map { (item, date) ->
                        "${item.parcel.name}: ${cropName(item.crop!!)}, ${item.progress?.percent?.toString() ?: "?"}% of season, ready ${dayLabel(if (date < ctx.today) ctx.today else date, ctx.today)}."
                    },
                )
                answer.reasoning.add("Shatter-prone crops (rapeseed, sunflower) are moved up the order.")
                val plan = harvest(ctx.copy(parcel = firstItem.parcel), firstItem.parcel)
                answer.proposals.replaceWith(plan.proposals)
                answer.risks.replaceWith(plan.risks)
                answer.parcelId = firstItem.parcel.id
            }
        }
        CopilotTopic.IRRIGATE -> {
            val irrigated = all.filter { (it.irrigatedHa ?: 0.0) > 0 }
            if (irrigated.isEmpty()) {
                answer.direct = "No parcel has irrigation configured."
            } else {
                val results = irrigated.map { it to irrigate(ctx, it) }
                val needs = results.filter { (_, result) -> result.proposals.any { it.type == "IRRIGATION_START" } }
                answer.direct = if (needs.isNotEmpty()) "Irrigate ${list(needs.map { it.first.name })} this week." else "None of the irrigated parcels needs water this week."
                answer.reasoning.replaceWith(results.map { (parcel, result) -> "${parcel.name}: ${result.direct}" })
                answer.proposals.replaceWith(needs.flatMap { it.second.proposals })
            }
        }
        CopilotTopic.SPRAY -> {
            val results = all.map { it to spray(ctx, it) }
            val first = results.first().second
            answer.direct = first.direct.replaceFirst(" ${all.first().name}", " your parcels")
            answer.reasoning.replaceWith(first.reasoning)
            answer.risks.replaceWith(first.risks)
            answer.proposals.replaceWith(results.flatMap { it.second.proposals })
            answer.changes.replaceWith(results.flatMap { it.second.changes })
            if (answer.proposals.isNotEmpty()) answer.nextSteps.replaceWith(listOf("Treatments due by crop stage: ${list(answer.proposals.map { it.title })}."))
        }
        CopilotTopic.YIELD -> {
            val rows = facts(ctx).filter { it.crop != null }.map { it to estimateYield(it, it.crop!!, ctx) }
            if (rows.isEmpty()) {
                answer.direct = "No parcel has a crop recorded this season."
            } else {
                answer.direct = "Expected production this season: ${rows.joinToString("; ") { (item, estimate) -> "${item.parcel.name} ${t(estimate.expected * item.parcel.areaHa)} t of ${cropName(item.crop!!)}" }}."
                answer.reasoning.replaceWith(rows.map { (item, estimate) -> "${item.parcel.name}: ${t(estimate.low)}–${t(estimate.high)} t/ha. ${estimate.factors.firstOrNull() ?: ""}" })
            }
        }
        CopilotTopic.TILLAGE -> {
            val target = findTillage(ctx.text) ?: TillageSystem.NO_TILL
            val ranked = facts(ctx).map { it to tillageFit(it, target) }.sortedByDescending { it.second.score }
            answer.direct = "Best parcel for ${target.profile.name}: ${ranked.first().first.parcel.name} (suitability ${ranked.first().second.score}/100)."
            answer.reasoning.replaceWith(
                ranked.map { (item, fit) ->
                    "${item.parcel.name}: ${fit.score}/100${fit.pros.firstOrNull()?.let { " – $it" } ?: ""}${fit.cons.firstOrNull()?.let { "; but $it" } ?: ""}."
                },
            )
        }
        CopilotTopic.SATELLITE -> return satelliteFarm(ctx)
        CopilotTopic.PROFITABILITY -> return if (ctx.snapshot.canSeeFinance) roiRanking(ctx) else financeHidden(ctx)
        else -> return general(ctx)
    }
    answer.confidence = Confidence.MEDIUM
    return answer
}

// ------------------------------------------------------------------ operations

private fun taskLine(ctx: Context, task: CopilotTask): String {
    val who = listOfNotNull(memberName(ctx, task.assigneeId), machineName(ctx, task.machineId)).filter { it.isNotEmpty() }.joinToString(", ")
    val place = if (task.parcelId != null && !task.title.contains(parcelName(ctx, task.parcelId))) " (${parcelName(ctx, task.parcelId)})" else ""
    return "${task.title}$place – ${dayLabel(task.dueDate, ctx.today)}${if (who.isNotEmpty()) ", $who" else ""}"
}

/** The first day, from [from], suitable for the kind of work. */
private fun suitableDay(ctx: Context, type: String, from: String): String {
    val weather = ctx.weather ?: return from
    if (taskIsSpray(type)) {
        val window = weather.sprayWindows.filter { it.start.take(10) >= from }.minByOrNull { it.start }
        if (window != null) return window.start.take(10)
    }
    val days = ctx.snapshot.weather?.days ?: emptyList()
    return days.firstOrNull { it.date >= from && it.precipitationMm < 2 && (it.windMaxKmh ?: 0.0) < 30 }?.date ?: from
}

private fun pending(ctx: Context): AnswerBuilder {
    val answer = blank(CopilotTopic.PENDING, ctx)
    val open = facts(ctx).flatMap { it.open }
    val unassigned = if (ctx.farmScope) ctx.snapshot.tasks.filter { it.parcelId == null && it.status != CopilotTaskStatus.DONE } else emptyList()
    val all = (open + unassigned).sortedBy { it.dueDate }
    if (all.isEmpty()) {
        answer.direct = "No pending operations on ${scopeLabel(ctx)}."
        answer.nextSteps.replaceWith(listOf("Ask what to do next, for example “When should I fertilize?”."))
        answer.confidence = Confidence.HIGH
        return answer
    }
    val late = all.filter { it.dueDate.take(10) < ctx.today }
    val running = facts(ctx).flatMap { it.activeRuns }
    answer.direct = "${all.size} operation${if (all.size > 1) "s are" else " is"} pending on ${scopeLabel(ctx)}" +
        (if (late.isNotEmpty()) ", ${late.size} of them overdue" else "") +
        (if (running.isNotEmpty()) "; ${running.size} running now" else "") + "."
    answer.reasoning.replaceWith(all.take(12).map { "${if (it.dueDate.take(10) < ctx.today) "⚠ " else ""}${taskLine(ctx, it)}" })
    if (all.size > 12) answer.reasoning.add("…and ${all.size - 12} more in the To-Do list.")
    answer.changes.replaceWith(rescheduleOverdue(ctx, late))
    if (answer.changes.isNotEmpty()) answer.nextSteps.add("Reschedule the overdue work to the next suitable days (below).")
    answer.confidence = Confidence.HIGH
    return answer
}

private fun rescheduleOverdue(ctx: Context, late: List<CopilotTask>): List<TaskChange> = late.take(8).map { task ->
    val to = suitableDay(ctx, task.type, ctx.today)
    TaskChange(
        key = "c-${task.id}",
        kind = ChangeKind.RESCHEDULE,
        taskId = task.id,
        title = task.title,
        parcelId = task.parcelId,
        fromDate = task.dueDate.take(10),
        toDate = to,
        fromPriority = task.priority,
        reason = "Overdue by ${daysBetween(task.dueDate, ctx.today)} days; ${if (to == ctx.today) "today" else dayLabel(to, ctx.today)} is the next suitable day.",
    )
}

private fun overdue(ctx: Context): AnswerBuilder {
    val answer = blank(CopilotTopic.OVERDUE, ctx)
    val rows = facts(ctx).filter { it.overdue.isNotEmpty() }
    if (rows.isEmpty()) {
        answer.direct = "No overdue operations on ${scopeLabel(ctx)}."
        answer.confidence = Confidence.HIGH
        return answer
    }
    val total = rows.sumOf { it.overdue.size }
    answer.direct = if (ctx.farmScope) {
        "${rows.size} field${if (rows.size > 1) "s have" else " has"} overdue operations ($total task${if (total > 1) "s" else ""}): ${list(rows.map { it.parcel.name })}."
    } else {
        "$total overdue operation${if (total > 1) "s" else ""} on ${ctx.parcel!!.name}."
    }
    answer.reasoning.replaceWith(rows.flatMap { item -> item.overdue.map { "${taskLine(ctx, it)} (${daysBetween(it.dueDate, ctx.today)} days late)" } })
    answer.risks.replaceWith(
        if (rows.any { item -> item.overdue.any { taskCategory(it.type) == TaskCategory.CROP_PROTECTION } }) {
            listOf("Late crop protection lets weeds and disease get ahead; efficacy drops as they grow.")
        } else {
            emptyList()
        },
    )
    answer.changes.replaceWith(rescheduleOverdue(ctx, rows.flatMap { it.overdue }))
    answer.nextSteps.replaceWith(listOf("Apply the proposed new dates, or ask “Prioritize harvesting before cultivation” to reorder."))
    answer.confidence = Confidence.HIGH
    return answer
}

private fun weekSchedule(ctx: Context): AnswerBuilder {
    val answer = blank(CopilotTopic.WEEK_SCHEDULE, ctx)
    val end = addDays(ctx.today, 6)
    val tasks = ctx.snapshot.tasks
        .filter { it.status != CopilotTaskStatus.DONE && it.dueDate.take(10) >= ctx.today && it.dueDate.take(10) <= end && (ctx.farmScope || it.parcelId == ctx.parcel?.id) }
        .sortedBy { it.dueDate }
    val running = ctx.snapshot.runs.filter { (it.status == RunStatus.RUNNING || it.status == RunStatus.PAUSED) && (ctx.farmScope || it.parcelId == ctx.parcel?.id) }
    answer.direct = if (tasks.isNotEmpty()) {
        "${tasks.size} operation${if (tasks.size > 1) "s are" else " is"} scheduled on ${scopeLabel(ctx)} this week${if (running.isNotEmpty()) ", and ${running.size} running now" else ""}."
    } else {
        "Nothing is scheduled on ${scopeLabel(ctx)} this week${if (running.isNotEmpty()) ", but ${running.size} operation${if (running.size > 1) "s are" else " is"} running" else ""}."
    }
    val days = ctx.snapshot.weather?.days ?: emptyList()
    fun dayOf(task: CopilotTask) = days.firstOrNull { it.date == task.dueDate.take(10) }
    fun rough(day: CopilotDay?) = day != null && (day.precipitationMm >= 2 || (day.windMaxKmh ?: 0.0) > 25)
    answer.reasoning.replaceWith(
        tasks.map { task ->
            val day = dayOf(task)
            val caution = if (rough(day)) {
                " ⚠ ${if (day!!.precipitationMm >= 2) "${rounded(day.precipitationMm)} mm rain" else "wind ${rounded(day.windMaxKmh!!)} km/h"}"
            } else {
                ""
            }
            "${taskLine(ctx, task)}$caution"
        },
    )
    answer.changes.replaceWith(
        tasks
            .filter { task ->
                (taskIsSpray(task.type) || taskCategory(task.type) == TaskCategory.FERTILIZATION || taskCategory(task.type) == TaskCategory.HARVEST) && rough(dayOf(task))
            }
            .map { task ->
                val to = suitableDay(ctx, task.type, addDays(task.dueDate, 1))
                moveChange(task, to, "Rain or wind on ${dayLabel(task.dueDate, ctx.today)}; ${dayLabel(to, ctx.today)} looks suitable.")
            }
            .filter { it.toDate != it.fromDate },
    )
    if (answer.changes.isNotEmpty()) answer.risks.add("Some operations fall on rainy or windy days (see the proposed moves).")
    answer.confidence = Confidence.HIGH
    return answer
}

private fun categoriesIn(text: String): List<TaskCategory> =
    CATEGORY_WORDS.keys
        .map { category ->
            val positions = CATEGORY_WORDS.getValue(category).mapNotNull { word -> Regex("(^|[^a-z])$word").find(text)?.range?.first }
            category to (positions.minOrNull() ?: -1)
        }
        .filter { it.second >= 0 }
        .sortedBy { it.second }
        .map { it.first }

private fun hectaresLeft(ctx: Context): AnswerBuilder {
    val category = categoriesIn(ctx.text).firstOrNull() ?: TaskCategory.CROP_PROTECTION
    val answer = blank(CopilotTopic.HECTARES_LEFT, ctx)
    val open = ctx.snapshot.tasks.filter {
        it.status != CopilotTaskStatus.DONE && taskCategory(it.type) == category && it.parcelId != null && (ctx.farmScope || it.parcelId == ctx.parcel?.id)
    }
    val parcels = open.mapNotNull { it.parcelId }.distinct().mapNotNull { id -> ctx.snapshot.parcels.firstOrNull { it.id == id } }
    val workType = mapOf(
        TaskCategory.CROP_PROTECTION to "SPRAYING",
        TaskCategory.FERTILIZATION to "FERTILIZING",
        TaskCategory.HARVEST to "HARVESTING",
        TaskCategory.PLANTING to "SOWING",
    )
    val runningArea = ctx.snapshot.runs.filter { it.type == workType[category] && it.status == RunStatus.RUNNING }.sumOf { it.areaHa }
    val total = parcels.sumOf { it.areaHa }
    val label = CATEGORY_LABELS.getValue(category).lowercase()
    answer.direct = if (total > 0) {
        "${t(total)} ha still need $label on ${parcels.size} parcel${if (parcels.size > 1) "s" else ""}: ${list(parcels.map { "${it.name} (${t(it.areaHa)} ha)" })}."
    } else {
        "No hectares are waiting for $label: there are no open $label tasks."
    }
    if (runningArea > 0) answer.reasoning.add("${t(runningArea)} ha are being worked right now.")
    answer.reasoning.add("Counted from open $label tasks in the To-Do list.")
    val window = ctx.weather?.sprayWindows?.firstOrNull()
    if (category == TaskCategory.CROP_PROTECTION && window != null) answer.nextSteps.replaceWith(listOf("Next good spraying window: ${window.label}."))
    answer.confidence = Confidence.HIGH
    return answer
}

private data class MachineLoad(val machine: CopilotMachine, val running: Boolean, val planned: List<CopilotTask>)

private fun machineBusy(ctx: Context, machine: CopilotMachine, days: Int): MachineLoad {
    val running = ctx.snapshot.runs.any { it.machineId == machine.id && (it.status == RunStatus.RUNNING || it.status == RunStatus.PAUSED) }
    val planned = ctx.snapshot.tasks.filter {
        it.machineId == machine.id && it.status != CopilotTaskStatus.DONE && daysBetween(ctx.today, it.dueDate) >= 0 && daysBetween(ctx.today, it.dueDate) < days
    }
    return MachineLoad(machine, running, planned)
}

private fun idleMachines(ctx: Context): AnswerBuilder {
    val answer = blank(CopilotTopic.IDLE_MACHINES, ctx, null)
    if (ctx.snapshot.machines.isEmpty()) {
        answer.direct = "There are no machines in the fleet yet. Add them in the Machines tab."
        return answer
    }
    val rows = ctx.snapshot.machines.map { machineBusy(ctx, it, 3) }
    val idle = rows.filter { !it.running && it.planned.isEmpty() }
    val wantsTractors = mentions(ctx.text, listOf("tractor", "tractoare"))
    val shown = if (wantsTractors) idle.filter { it.machine.type == "TRACTOR" } else idle
    val noun = if (wantsTractors) "tractor" else "machine"
    answer.direct = if (shown.isNotEmpty()) {
        "${shown.size} $noun${if (shown.size > 1) "s are" else " is"} idle now and in the next 2 days: ${list(shown.map { it.machine.name })}."
    } else {
        "No $noun is idle: everything is running or planned."
    }
    answer.reasoning.replaceWith(
        rows.map { row ->
            "${row.machine.name}: ${when {
                row.running -> "working now"
                row.planned.isNotEmpty() -> "planned – ${row.planned.joinToString(", ") { it.title }}"
                else -> "idle"
            }}"
        },
    )
    val unassigned = ctx.snapshot.tasks.filter {
        it.status != CopilotTaskStatus.DONE && it.machineId == null && (COPILOT_TASK_TYPES[it.type]?.machines?.size ?: 0) > 0 && daysBetween(ctx.today, it.dueDate) <= 7
    }
    if (unassigned.isNotEmpty() && idle.isNotEmpty()) {
        answer.nextSteps.replaceWith(
            listOf("${unassigned.size} task${if (unassigned.size > 1) "s" else ""} this week ${if (unassigned.size > 1) "have" else "has"} no machine: ask “What machinery should I assign?”."),
        )
    }
    answer.confidence = Confidence.HIGH
    return answer
}

private fun machinery(ctx: Context): AnswerBuilder {
    val answer = blank(CopilotTopic.MACHINERY, ctx)
    val scope = ctx.snapshot.tasks.filter {
        it.status != CopilotTaskStatus.DONE && (COPILOT_TASK_TYPES[it.type]?.machines?.size ?: 0) > 0 &&
            (ctx.farmScope || it.parcelId == ctx.parcel?.id) && daysBetween(ctx.today, it.dueDate) <= 14
    }
    if (ctx.snapshot.machines.isEmpty()) {
        answer.direct = "There are no machines in the fleet yet. Add them in the Machines tab and I will assign them to tasks."
        return answer
    }
    if (scope.isEmpty()) {
        answer.direct = "No upcoming field work on ${scopeLabel(ctx)} needs a machine in the next 2 weeks."
        answer.reasoning.replaceWith(ctx.snapshot.machines.map { "${it.name} (${it.type.lowercase()}${it.powerHp?.takeIf { hp -> hp != 0.0 }?.let { hp -> ", ${jsNum(hp)} hp" } ?: ""})" })
        return answer
    }
    val lines = mutableListOf<String>()
    val shortages = linkedSetOf<String>()
    for (task in scope) {
        val suggested = task.machineId ?: suggestFor(ctx, task)
        if (suggested == null) shortages.add(COPILOT_TASK_TYPES.getValue(task.type).machines.joinToString(" or ").lowercase())
        lines.add("${taskLine(ctx, task)} → ${if (suggested != null) machineName(ctx, suggested) else "no suitable machine free"}${if (task.assigneeId != null) "" else ", no operator yet"}")
    }
    answer.direct = "Machine assignments for the next 2 weeks on ${scopeLabel(ctx)}: ${scope.count { it.machineId == null }} task${if (scope.size > 1) "s" else ""} still need a machine."
    answer.reasoning.replaceWith(lines)
    if (shortages.isNotEmpty()) answer.risks.add("Machinery shortage: no free ${list(shortages.toList())} on some days. Consider a contractor or moving the work.")
    answer.nextSteps.replaceWith(listOf("Open each task in the To-Do list to set the machine and operator."))
    answer.confidence = Confidence.MEDIUM
    return answer
}

private fun suggestFor(ctx: Context, task: CopilotTask): String? =
    propose(ctx.snapshot, ProposalInput(task.type, task.parcelId, task.dueDate, task.priority, "", withFollowUps = false)).machineId

private fun basisLabel(basis: ProfitBasis) = basis.name.lowercase()

private fun roiRanking(ctx: Context): AnswerBuilder {
    val answer = blank(CopilotTopic.ROI_RANKING, ctx, null)
    data class Row(val parcel: CopilotParcel, val profit: ParcelProfit, val roi: Double)
    val rows = ctx.snapshot.parcels
        .mapNotNull { parcel -> parcelProfitPerHa(parcelFacts(ctx.snapshot, parcel), ctx)?.let { parcel to it } }
        .map { (parcel, profit) -> Row(parcel, profit, if (profit.costPerHa > 0) (profit.value / profit.costPerHa) * 100 else 0.0) }
    if (rows.isEmpty()) {
        answer.direct = "No parcel has enough crop and cost data to rank profitability. Record crops and expected yields and prices first."
        answer.confidence = Confidence.LOW
        return answer
    }
    val byRoi = rows.sortedByDescending { it.roi }
    val byProfit = rows.sortedByDescending { it.profit.value }
    val asksLeast = mentions(ctx.text, listOf("least", "lowest", "worst", "cea mai putin", "cel mai putin"))
    val pick = when {
        asksLeast -> byRoi.last()
        mentions(ctx.text, listOf("roi", "return")) -> byRoi.first()
        else -> byProfit.first()
    }
    answer.parcelId = pick.parcel.id
    answer.direct = "${if (asksLeast) "Least" else "Most"} profitable: ${pick.parcel.name} – ${euroHa(pick.profit.value)} profit, ROI ${signedPct(pick.roi)}${if (pick.profit.basis == ProfitBasis.ESTIMATE) " (estimate)" else ""}."
    answer.reasoning.replaceWith(byRoi.map { "${it.parcel.name}: ${euroHa(it.profit.value)}, ROI ${signedPct(it.roi)} (${basisLabel(it.profit.basis)})" })
    val estimates = rows.count { it.profit.basis == ProfitBasis.ESTIMATE }
    if (estimates > 0) answer.dataGaps.add("$estimates parcel${if (estimates > 1) "s use" else " uses"} estimated figures: record costs and prices for exact results.")
    answer.economics.replaceWith(listOf("Total expected profit: ${euro(rows.sumOf { it.profit.value * it.parcel.areaHa })}."))
    answer.confidence = if (estimates > 0) Confidence.MEDIUM else Confidence.HIGH
    return answer
}

private fun farmProfit(ctx: Context): AnswerBuilder {
    val answer = blank(CopilotTopic.FARM_PROFIT, ctx, null)
    val rows = ctx.snapshot.parcels.map { parcel -> parcel to parcelProfitPerHa(parcelFacts(ctx.snapshot, parcel), ctx) }
    val known = rows.mapNotNull { (parcel, profit) -> profit?.let { parcel to it } }
    val total = known.sumOf { it.second.value * it.first.areaHa }
    val area = known.sumOf { it.first.areaHa }
    val cost = known.sumOf { it.second.costPerHa * it.first.areaHa }
    if (known.isEmpty()) {
        answer.direct = "There is not enough crop and cost data to estimate the farm profit yet."
        answer.confidence = Confidence.LOW
        return answer
    }
    answer.direct = "Expected farm profit this season: about ${euro(total)} on ${t(area)} ha (${euroHa(if (area != 0.0) total / area else 0.0)}), for a production cost of ${euro(cost)}."
    answer.reasoning.replaceWith(
        known.sortedByDescending { it.second.value }.map { (parcel, profit) ->
            "${parcel.name}: ${euro(profit.value * parcel.areaHa)} (${euroHa(profit.value)}, ${basisLabel(profit.basis)})"
        },
    )
    val missing = rows.size - known.size
    if (missing > 0) answer.dataGaps.add("$missing parcel${if (missing > 1) "s have" else " has"} no crop or cost data and ${if (missing > 1) "are" else "is"} not included.")
    answer.economics.replaceWith(listOf("Return on cost: ${signedPct(if (cost > 0) (total / cost) * 100 else 0.0)}."))
    answer.confidence = if (known.any { it.second.basis == ProfitBasis.ESTIMATE }) Confidence.MEDIUM else Confidence.HIGH
    return answer
}

private val FUEL_NAME = Regex("fuel", RegexOption.IGNORE_CASE)

private fun reduceCosts(ctx: Context): AnswerBuilder {
    val answer = blank(CopilotTopic.REDUCE_COSTS, ctx)
    val rows = facts(ctx)
    val shares = linkedMapOf<String, Double>()
    var costed = 0
    for (item in rows) {
        val economics = item.parcel.economics ?: continue
        costed++
        for ((name, share) in economics.costShares) shares[name] = (shares[name] ?: 0.0) + share * economics.totalCost / 100
    }
    val top = shares.entries.sortedByDescending { it.value }.map { it.key to it.value }
    val conventional = TillageSystem.CONVENTIONAL.profile
    val minimum = TillageSystem.MINIMUM_TILL.profile
    data class Saving(val item: ParcelFacts, val saving: Double, val fit: TillageFit)
    val tillageSavings = rows
        .filter { it.parcel.tillage == TillageSystem.CONVENTIONAL }
        .map { Saving(it, (conventional.costPerHa - minimum.costPerHa) * it.parcel.areaHa, tillageFit(it, TillageSystem.MINIMUM_TILL)) }
        .filter { it.fit.score >= 55 }
    val levers = mutableListOf<String>()
    if (tillageSavings.isNotEmpty()) {
        val fuel = tillageSavings.sumOf { (conventional.fuelLPerHa - minimum.fuelLPerHa) * it.item.parcel.areaHa }
        levers.add("Move ${list(tillageSavings.map { it.item.parcel.name })} from conventional to Minimum Till: about ${euro(tillageSavings.sumOf { it.saving })} and ${rounded(fuel)} l of diesel per season.")
    }
    val noSoil = rows.filter { it.parcel.soil == null }
    if (noSoil.isNotEmpty()) levers.add("Fertilize to a soil analysis: on ${list(noSoil.map { it.parcel.name })} it typically saves 10–20% of fertilizer cost.")
    top.firstOrNull()?.let { (name, amount) ->
        val then = top.getOrNull(1)?.let { ", then ${it.first} ${euro(it.second)}" } ?: ""
        levers.add(0, "$name is your biggest cost (${euro(amount)}$then): start there.")
    }
    if (top.any { FUEL_NAME.containsMatchIn(it.first) }) levers.add("Cut fuel by combining passes (for example spreading with the herbicide pass) and checking tyre pressure.")
    val idle = ctx.snapshot.machines.filter { machine -> ctx.snapshot.runs.none { it.machineId == machine.id } && ctx.snapshot.tasks.none { it.machineId == machine.id } }
    if (idle.isNotEmpty()) {
        levers.add(
            "${list(idle.map { it.name })} ${if (idle.size > 1) "have" else "has"} no work or tasks recorded: if that is accurate, renting ${if (idle.size > 1) "them" else "it"} out would cover fixed costs.",
        )
    }
    answer.direct = if (levers.isNotEmpty()) {
        "The biggest savings on ${scopeLabel(ctx)}: ${levers.first()}"
    } else {
        "Record costs for ${scopeLabel(ctx)} in the economics tab and I will point out where the money goes."
    }
    answer.reasoning.replaceWith(levers.drop(1))
    answer.risks.replaceWith(if (tillageSavings.isNotEmpty()) listOf("Reduced tillage can lower yields slightly in the first transition years.") else emptyList())
    answer.economics.replaceWith(if (costed > 0) listOf("Based on recorded costs of $costed parcel${if (costed > 1) "s" else ""}.") else emptyList())
    answer.proposals.replaceWith(
        noSoil.take(3).map {
            propose(ctx.snapshot, ProposalInput("SOIL_SAMPLING", it.parcel.id, addDays(ctx.today, 7), CopilotPriority.LOW, "A soil analysis lets fertilizer be applied only where it is needed.", 21))
        },
    )
    if (costed == 0) answer.dataGaps.add("No costs are recorded this season.")
    answer.confidence = if (costed > 0) Confidence.MEDIUM else Confidence.LOW
    return answer
}

private fun prioritize(ctx: Context): AnswerBuilder {
    val answer = blank(CopilotTopic.PRIORITIZE, ctx, null)
    val weather = ctx.weather
    data class Row(val item: ParcelFacts, val score: Int, val reasons: List<String>)
    val rows = facts(ctx).map { item ->
        val reasons = mutableListOf<String>()
        var score = 0
        if (item.overdue.isNotEmpty()) {
            score += 30 * item.overdue.size
            reasons.add("${item.overdue.size} overdue")
        }
        val soon = item.upcoming.filter { daysBetween(ctx.today, it.dueDate) <= 3 }
        if (soon.isNotEmpty()) {
            score += 15 * soon.size
            reasons.add("${soon.size} due within 3 days")
        }
        val harvestDay = expectedHarvest(item)
        if (harvestDay != null && item.season?.harvestDate == null && daysBetween(ctx.today, harvestDay) <= 7) {
            score += 25
            reasons.add("crop ready to harvest")
        }
        val nitrogen = nitrogenStatus(item)
        if (nitrogen?.due != null && nitrogen.done == null && daysBetween(ctx.today, nitrogen.due) <= 3) {
            score += 15
            reasons.add("nitrogen window open")
        }
        if (weather != null && weather.rain48h >= 10 && item.upcoming.any { taskIsSpray(it.type) }) {
            score += 10
            reasons.add("spraying must happen before the rain")
        }
        Row(item, score, reasons)
    }.filter { it.score > 0 }.sortedByDescending { it.score }
    if (rows.isEmpty()) {
        answer.direct = "Nothing is urgent right now: no overdue work, no crops ready and no operations due in the next 3 days."
        answer.confidence = Confidence.HIGH
        return answer
    }
    val first = rows.first()
    answer.parcelId = first.item.parcel.id
    answer.direct = "Prioritize ${first.item.parcel.name}: ${list(first.reasons)}."
    answer.reasoning.replaceWith(rows.mapIndexed { index, row -> "${index + 1}. ${row.item.parcel.name} – ${list(row.reasons)}" })
    answer.changes.replaceWith(rescheduleOverdue(ctx, first.item.overdue))
    answer.confidence = Confidence.HIGH
    return answer
}

private fun risks(ctx: Context): AnswerBuilder {
    val answer = blank(CopilotTopic.RISKS, ctx)
    val insights = farmInsights(ctx.snapshot).filter { ctx.farmScope || it.parcelId == ctx.parcel?.id || it.parcelId == null }
    val agronomic = mutableListOf<String>()
    for (item in facts(ctx)) {
        val crop = item.crop
        if (crop != null && item.season != null) {
            val humid = (ctx.snapshot.weather?.hours ?: emptyList()).take(72).count { it.humidityPct >= 85 && it.temperatureC >= 15 && it.temperatureC <= 25 }
            if (humid >= 18 && item.progress?.status == ro.fermapp.domain.CropProgressStatus.GROWING) {
                agronomic.add("${item.parcel.name}: disease risk – $humid warm, humid hours in the next 3 days favour fungal infection of ${cropName(crop)}.")
            }
            val nitrogen = nitrogenStatus(item)
            if (nitrogen?.due != null && nitrogen.done == null && nitrogen.due < ctx.today) {
                agronomic.add("${item.parcel.name}: nitrogen deficiency risk – the top-dressing is ${daysBetween(nitrogen.due, ctx.today)} days late.")
            }
        }
        val ph = item.parcel.soil?.ph
        if (ph != null && ph < 5.8) agronomic.add("${item.parcel.name}: acidic soil (pH ${jsNum(ph)}) limits most crops.")
    }
    answer.risks.replaceWith(insights.filter { it.severity != Severity.INFO }.map { it.message } + agronomic)
    answer.reasoning.replaceWith(insights.filter { it.severity == Severity.INFO }.map { it.message })
    answer.direct = if (answer.risks.isNotEmpty()) {
        "${answer.risks.size} risk${if (answer.risks.size > 1) "s" else ""} on ${scopeLabel(ctx)}. The most important: ${answer.risks.first()}"
    } else {
        "No significant risks on ${scopeLabel(ctx)} right now."
    }
    answer.proposals.replaceWith(insights.mapNotNull { it.proposal }.take(4))
    answer.nextSteps.replaceWith(insights.mapNotNull { it.ask?.takeIf { ask -> ask.isNotEmpty() } }.take(3))
    answer.confidence = if (ctx.weather != null) Confidence.HIGH else Confidence.MEDIUM
    return answer
}

// ------------------------------------------------------------------ task changes

private val SPECIFIC_WORDS = listOf("herbicide", "fungicide", "insecticide", "nitrogen", "foliar", "plow", "disk", "subsoil", "chisel", "sowing", "transport", "scouting")

private fun targetTasks(ctx: Context, categories: List<TaskCategory>): List<CopilotTask> {
    val scoped = ctx.snapshot.tasks.filter { it.status != CopilotTaskStatus.DONE && (ctx.farmScope || ctx.parcel == null || it.parcelId == ctx.parcel.id) }
    val specific = SPECIFIC_WORDS.firstOrNull { ctx.text.contains(it) }
    return scoped
        .filter { (taskCategory(it.type) ?: TaskCategory.MONITORING) in categories }
        .filter { specific == null || normalize("${it.type} ${it.title}").contains(specific) }
        .sortedBy { it.dueDate }
}

private fun changeTasks(topic: CopilotTopic, ctx: Context): AnswerBuilder {
    val answer = blank(topic, ctx)
    val mentioned = categoriesIn(ctx.text)
    val remembered = ctx.memory.topic?.let { TOPIC_CATEGORY[it] }
    val categories = mentioned.ifEmpty { listOfNotNull(remembered) }
    if (categories.isEmpty()) {
        answer.direct = "Which work should I change? For example “Move spraying to Friday” or “Delay fertilization on Field 3 by one week”."
        answer.confidence = Confidence.LOW
        return answer
    }
    val label = list(categories.map { CATEGORY_LABELS.getValue(it).lowercase() })

    if (topic == CopilotTopic.REPRIORITIZE && categories.size >= 2) {
        // "Prioritize harvesting before cultivation."
        val (first, second) = categories
        val leading = targetTasks(ctx, listOf(first))
        val following = targetTasks(ctx, listOf(second))
        val lastLead = leading.fold("") { max, task -> if (task.dueDate > max) task.dueDate.take(10) else max }
        val firstLabel = CATEGORY_LABELS.getValue(first)
        for (task in leading) {
            if (task.priority != CopilotPriority.HIGH && task.priority != CopilotPriority.URGENT) answer.changes.add(priorityChange(task, CopilotPriority.HIGH, "$firstLabel goes first."))
        }
        for (task in following) {
            if (lastLead.isNotEmpty() && task.dueDate.take(10) <= lastLead) {
                answer.changes.add(moveChange(task, addDays(lastLead, 1), "After the last ${firstLabel.lowercase()} task (${dayLabel(lastLead, ctx.today)})."))
            }
        }
        val secondLabel = CATEGORY_LABELS.getValue(second).lowercase()
        answer.direct = if (answer.changes.isNotEmpty()) {
            "$firstLabel before $secondLabel: ${answer.changes.size} change${if (answer.changes.size > 1) "s" else ""} proposed."
        } else {
            "$firstLabel is already scheduled before $secondLabel."
        }
        answer.confidence = Confidence.HIGH
        return answer
    }

    val tasks = targetTasks(ctx, categories)
    if (tasks.isEmpty()) {
        val hint = if (topic == CopilotTopic.RESCHEDULE || topic == CopilotTopic.REPRIORITIZE) " Ask me to create one, for example “Create a task to spray on Friday”." else ""
        answer.direct = "There are no open $label tasks on ${scopeLabel(ctx)}.$hint"
        answer.confidence = Confidence.HIGH
        return answer
    }

    when (topic) {
        CopilotTopic.CANCEL -> {
            answer.changes.replaceWith(
                tasks.map { TaskChange("c-${it.id}", ChangeKind.CANCEL, it.id, it.title, it.parcelId, it.dueDate.take(10), fromPriority = it.priority, reason = "Cancelled on request.") },
            )
            answer.direct = "Cancel ${tasks.size} $label task${if (tasks.size > 1) "s" else ""} on ${scopeLabel(ctx)}: ${list(tasks.map { it.title })}."
            val weather = ctx.weather
            if (TaskCategory.IRRIGATION in categories && weather != null && weather.rain7d < 10) {
                answer.risks.add("Only ${jsNum(weather.rain7d)} mm of rain is forecast this week: check soil moisture before cancelling irrigation.")
            }
        }
        CopilotTopic.REPRIORITIZE -> {
            val level = when {
                mentions(ctx.text, listOf("urgent")) -> CopilotPriority.URGENT
                mentions(ctx.text, listOf("low", "scazut", "mica")) -> CopilotPriority.LOW
                else -> CopilotPriority.HIGH
            }
            answer.changes.replaceWith(tasks.filter { it.priority != level }.map { priorityChange(it, level, "Priority changed on request.") })
            val levelText = level.name.lowercase()
            answer.direct = if (answer.changes.isNotEmpty()) {
                "Set ${answer.changes.size} $label task${if (answer.changes.size > 1) "s" else ""} to $levelText priority."
            } else {
                "The $label tasks are already $levelText priority."
            }
        }
        CopilotTopic.GROUP -> {
            val anchor = tasks.first().dueDate.take(10)
            val day = suitableDay(ctx, tasks.first().type, if (anchor < ctx.today) ctx.today else anchor)
            answer.changes.replaceWith(
                tasks
                    .filter { daysBetween(anchor, it.dueDate) <= 10 && it.dueDate.take(10) != day }
                    .map { moveChange(it, day, "Grouped with the other $label work to save transport and setup time.") },
            )
            answer.direct = if (answer.changes.isNotEmpty()) {
                "Group ${answer.changes.size + 1} $label tasks on ${dayLabel(day, ctx.today)}, so the machine is set up once."
            } else {
                "The $label tasks are already grouped."
            }
            val area = tasks.sumOf { task -> ctx.snapshot.parcels.firstOrNull { it.id == task.parcelId }?.areaHa ?: 0.0 }
            if (area != 0.0) answer.reasoning.add("${t(area)} ha in total.")
        }
        else -> {
            val date = ctx.date.date
            val shiftDays = ctx.date.shiftDays
            if (date == null && (shiftDays == null || shiftDays == 0)) {
                answer.direct = "To when should I move the $label work? For example “to Friday”, “by one week” or “to 15 September”."
                answer.confidence = Confidence.LOW
                return answer
            }
            answer.changes.replaceWith(
                tasks.map {
                    moveChange(
                        it, date ?: addDays(it.dueDate, shiftDays!!),
                        if (date != null) "Moved to ${dayLabel(date, ctx.today)} on request." else "Delayed by $shiftDays days on request.",
                    )
                },
            )
            val target = if (date != null) dayLabel(date, ctx.today) else "$shiftDays days later"
            answer.direct = "Move ${tasks.size} $label task${if (tasks.size > 1) "s" else ""} on ${scopeLabel(ctx)} to $target."
            val day = (ctx.snapshot.weather?.days ?: emptyList()).firstOrNull { it.date == (date ?: "") }
            if (day != null && (day.precipitationMm >= 2 || (day.windMaxKmh ?: 0.0) > 25) &&
                categories.any { it == TaskCategory.CROP_PROTECTION || it == TaskCategory.FERTILIZATION }
            ) {
                val looks = if (day.precipitationMm >= 2) "rainy (${rounded(day.precipitationMm)} mm)" else "windy (${rounded(day.windMaxKmh!!)} km/h)"
                answer.risks.add("${dayLabel(day.date, ctx.today)} looks $looks: ${dayLabel(suitableDay(ctx, tasks.first().type, addDays(day.date, 1)), ctx.today)} would be better.")
            }
        }
    }
    answer.reasoning.addAll(0, tasks.map { taskLine(ctx, it) })
    answer.confidence = Confidence.HIGH
    return answer
}

private fun moveChange(task: CopilotTask, toDate: String, reason: String) =
    TaskChange("c-${task.id}", ChangeKind.RESCHEDULE, task.id, task.title, task.parcelId, task.dueDate.take(10), toDate = toDate, fromPriority = task.priority, reason = reason)

private fun priorityChange(task: CopilotTask, toPriority: CopilotPriority, reason: String) =
    TaskChange("p-${task.id}", ChangeKind.PRIORITY, task.id, task.title, task.parcelId, task.dueDate.take(10), fromPriority = task.priority, toPriority = toPriority, reason = reason)

/** The task type a request names, most specific first. */
private val TYPE_WORDS: List<Pair<String, List<String>>> = listOf(
    "HERBICIDE" to listOf("herbicide", "erbicid", "weed control"),
    "FUNGICIDE" to listOf("fungicide", "fungicid"),
    "INSECTICIDE" to listOf("insecticide", "insecticid"),
    "FOLIAR_FEEDING" to listOf("foliar"),
    "NITROGEN" to listOf("nitrogen", "urea", "azot", "uree"),
    "FERTILIZATION" to listOf("fertiliz", "fertilis", "npk", "ingrasa"),
    "SUBSOILING" to listOf("subsoil", "afanare"),
    "CHISELING" to listOf("chisel", "scarific"),
    "STRIP_TILL" to listOf("strip"),
    "DISKING" to listOf("disk", "disc", "discuit"),
    "PLOWING" to listOf("plow", "plough", "arat"),
    "SOIL_PREP" to listOf("seedbed", "pregatire"),
    "SOWING" to listOf("sow", "plant", "semana", "semanat"),
    "GRAIN_TRANSPORT" to listOf("transport"),
    "HARVEST_PREP" to listOf("harvest prep", "prepare harvest", "pregatire recolt"),
    "HARVEST" to listOf("harvest", "recolt", "treier"),
    "IRRIGATION_STOP" to listOf("stop irrigat", "opreste irig"),
    "IRRIGATION_CHECK" to listOf("irrigation check", "check irrigat", "verifica irig"),
    "IRRIGATION_START" to listOf("irrigat", "irig", "water"),
    "SOIL_SAMPLING" to listOf("soil sampl", "soil test", "probe de sol", "analiza sol"),
    "DISEASE_CHECK" to listOf("disease", "boli"),
    "WEED_MONITORING" to listOf("weed", "buruien"),
    "CROP_SCOUTING" to listOf("scout"),
    "FIELD_INSPECTION" to listOf("inspect", "check", "verific"),
    "HERBICIDE" to listOf("spray", "stropi"),
)

private val CONFIRMATION = Regex("^(yes|yep|ok|okay|sure|do it|go ahead|please do|da|bine|sigur|confirm|confirma|de acord|fa-o|fa asta)\\b")

/** "yes", "da", "ok" right after an answer with suggested tasks. */
fun isConfirmation(text: String) = CONFIRMATION.containsMatchIn(text) && text.split(" ").size <= 4

private val REFERS_TO_PREVIOUS = listOf(
    "these", "them", "those", "all", "recommended", "suggested", "proposed", "previous", "to-do", "todo", "to do", "toate", "acestea",
    "astea", "acelea", "le ", "-le", "recomandat", "sugerat", "propus", "lista", "de mai sus",
)

/** Creates the tasks the previous answer suggested, skipping any already in the To-Do list. */
private fun createSuggested(ctx: Context, type: String?): AnswerBuilder {
    val answer = blank(CopilotTopic.CREATE_TASK, ctx, ctx.memory.last?.parcelId)
    val suggested = ctx.memory.last?.proposals ?: emptyList()
    val category = type?.let { COPILOT_TASK_TYPES[it]?.category }
    val wanted = if (category != null && suggested.any { it.category == category }) suggested.filter { it.category == category } else suggested
    fun exists(proposal: TaskProposal) = ctx.snapshot.tasks.any {
        it.status != CopilotTaskStatus.DONE && it.parcelId == proposal.parcelId && normalize(it.title) == normalize(proposal.title)
    }
    val fresh = wanted.filter { !exists(it) }
    if (suggested.isEmpty()) {
        answer.direct = "My last answer had no tasks to add. Ask for advice first (for example “When should I fertilize Field 12?”), or name the task: “Create a task to spray Field 3 on Friday”."
        answer.confidence = Confidence.LOW
        return answer
    }
    if (fresh.isEmpty()) {
        answer.direct = "${if (wanted.size == 1) "That task is" else "Those tasks are"} already in the To-Do list."
        answer.confidence = Confidence.HIGH
        return answer
    }
    answer.proposals.replaceWith(fresh)
    answer.execute = true
    answer.direct = "Adding ${fresh.size} task${if (fresh.size > 1) "s" else ""} to the To-Do list: ${list(fresh.map { "${it.title} (${dayLabel(it.dueDate, ctx.today)})" })}."
    val skipped = wanted.size - fresh.size
    if (skipped > 0) answer.reasoning.add("$skipped suggested task${if (skipped > 1) "s were" else " was"} already in the list.")
    answer.confidence = Confidence.HIGH
    return answer
}

private fun createTask(ctx: Context): AnswerBuilder {
    val type = TYPE_WORDS.firstOrNull { (_, words) -> mentions(ctx.text, words) }?.first
    val previous = !ctx.memory.last?.proposals.isNullOrEmpty() && (mentions(ctx.text, REFERS_TO_PREVIOUS) || isConfirmation(ctx.text) || type == null)
    if (previous) return createSuggested(ctx, type)
    val answer = blank(CopilotTopic.CREATE_TASK, ctx)
    answer.execute = true
    if (type == null) {
        answer.execute = false
        answer.direct = "What kind of task? For example “Create a task to spray Field 3 on Friday” or “Add a harvest task for next week”."
        answer.confidence = Confidence.LOW
        return answer
    }
    val crop = ctx.crop
    val shift = ctx.date.shiftDays
    val due = ctx.date.date ?: if (shift != null && shift != 0) addDays(ctx.today, shift) else addDays(ctx.today, 1)
    val priority = when {
        mentions(ctx.text, listOf("urgent")) -> CopilotPriority.URGENT
        mentions(ctx.text, listOf("high", "important", "ridicat")) -> CopilotPriority.HIGH
        mentions(ctx.text, listOf("low", "scazut")) -> CopilotPriority.LOW
        else -> CopilotPriority.MEDIUM
    }
    val kind = COPILOT_TASK_TYPES.getValue(type)
    val place = ctx.parcel?.let { " – ${it.name}" } ?: ""
    val title = if (type == "SOWING" && crop != null) "Plant ${cropName(crop)}$place" else null
    answer.proposals.add(propose(ctx.snapshot, ProposalInput(type, ctx.parcel?.id, due, priority, "Requested in the chat.", title = title)))
    answer.direct = "${title ?: "${kind.label}$place"} on ${dayLabel(due, ctx.today)}, ${priority.name.lowercase()} priority."
    val day = (ctx.snapshot.weather?.days ?: emptyList()).firstOrNull { it.date == due }
    if (day != null && (taskIsSpray(type) || kind.category == TaskCategory.FERTILIZATION || type == "HARVEST") &&
        (day.precipitationMm >= 2 || (day.windMaxKmh ?: 0.0) > 25)
    ) {
        answer.risks.add("${dayLabel(due, ctx.today)} looks ${if (day.precipitationMm >= 2) "rainy" else "windy"}; ${dayLabel(suitableDay(ctx, type, addDays(due, 1)), ctx.today)} would be better.")
    }
    if (ctx.parcel == null && ctx.snapshot.parcels.size > 1) answer.dataGaps.add("No parcel named: the task is farm-wide. Name a parcel to attach it.")
    answer.confidence = Confidence.HIGH
    return answer
}

// ------------------------------------------------------------------ overview

private fun general(ctx: Context): AnswerBuilder {
    val answer = blank(CopilotTopic.GENERAL, ctx)
    val insights = farmInsights(ctx.snapshot).filter { ctx.farmScope || ctx.parcel == null || it.parcelId == ctx.parcel.id || it.parcelId == null }
    val items = facts(ctx)
    val area = items.sumOf { it.parcel.areaHa }
    val crops = items.mapNotNull { it.crop }.distinct()
    val open = items.sumOf { it.open.size }
    val late = items.sumOf { it.overdue.size }
    val tasksText = "$open open task${if (open == 1) "" else "s"}"
    answer.direct = if (ctx.parcel != null && !ctx.farmScope) {
        val first = items.first()
        val cropText = first.crop?.let { " of ${cropName(it)} (${first.progress?.percent ?: 0}% of season)" } ?: ", no crop this season"
        "${ctx.parcel.name}: ${t(ctx.parcel.areaHa)} ha$cropText, $tasksText${if (late > 0) " ($late overdue)" else ""}."
    } else {
        val growing = if (crops.isNotEmpty()) ", growing ${list(crops.map { it.profile.name.lowercase() })}" else ""
        "${ctx.snapshot.farmName}: ${items.size} parcels, ${t(area)} ha$growing; $tasksText${if (late > 0) ", $late overdue" else ""}."
    }
    answer.reasoning.replaceWith(insights.take(5).map { it.message })
    ctx.weather?.let {
        answer.reasoning.add("Weather: ${jsNum(it.rain7d)} mm of rain over 7 days, ${rounded(it.minTemp7d ?: 0.0)}–${rounded(it.maxTemp7d ?: 0.0)} °C.")
    }
    answer.proposals.replaceWith(insights.mapNotNull { it.proposal }.take(3))
    answer.nextSteps.replaceWith(
        listOf(
            "Try: “What should I plant on ${ctx.parcel?.name ?: ctx.snapshot.parcels.first().name} next year?”",
            "“Which field should I prioritize?”",
            "“What operations are scheduled this week?”",
        ),
    )
    answer.confidence = Confidence.MEDIUM
    return answer
}

// ------------------------------------------------------------------ satellite

private fun satelliteAnswer(ctx: Context, parcel: CopilotParcel): AnswerBuilder {
    val answer = blank(CopilotTopic.SATELLITE, ctx, parcel.id)
    val sat = parcel.satellite
    if (sat == null) {
        answer.direct = "No satellite image has been analysed for ${parcel.name} yet. Open the field and choose “Satellite monitoring” to load the latest Sentinel-2 image."
        answer.nextSteps.replaceWith(listOf("Turn on Satellite Monitoring in Settings if it is off."))
        answer.confidence = Confidence.LOW
        return answer
    }
    fun change(value: Double?, label: String) = value?.let { "${if (it >= 0) "+" else ""}${jsNum(jsRound(it * 100) / 100)} NDVI $label" }
    val trend = listOfNotNull(change(sat.change7d, "in a week"), change(sat.change30d, "in a month"))
    val ndvi = jsNum(sat.ndvi)
    val imageDay = dayLabel(sat.date, ctx.today)
    val firstFinding = sat.findings.firstOrNull()
    answer.direct = if (sat.healthScore == null) {
        "${parcel.name}: ${sat.healthLabel.lowercase()} on the $imageDay image (NDVI $ndvi)."
    } else {
        "${parcel.name}: field health ${sat.healthScore}/100 (${sat.healthLabel.lowercase()}) on the $imageDay Sentinel-2 image, NDVI $ndvi" +
            (if (trend.isNotEmpty()) " (${trend.joinToString(", ")})" else "") + "." +
            (if (firstFinding != null && firstFinding.severity != Severity.INFO) " ${firstFinding.message}" else "")
    }
    answer.reasoning.replaceWith(
        listOf(
            "NDVI $ndvi measures green biomass" +
                (sat.ndre?.let { "; NDRE ${jsNum(it)} measures chlorophyll (nitrogen status)" } ?: "") +
                (sat.ndmi?.let { "; the moisture index ${jsNum(it)} measures water in the leaves" } ?: "") + ".",
        ) + sat.findings.map { "${it.title}: ${it.explanation}" },
    )
    answer.risks.replaceWith(sat.findings.filter { it.severity != Severity.INFO }.map { it.message })
    answer.proposals.replaceWith(
        sat.findings.mapNotNull { finding ->
            finding.task?.let { task ->
                propose(
                    ctx.snapshot,
                    ProposalInput(
                        task.type, parcel.id, addDays(ctx.today, if (finding.severity == Severity.CRITICAL) 0 else 1),
                        CopilotPriority.valueOf(task.priority.name), task.reason, 3, task.title,
                    ),
                )
            }
        },
    )
    answer.alternatives.replaceWith(listOf("Compare with older images in the field’s Satellite monitoring screen (7, 30, 90 days and last year)."))
    answer.nextSteps.replaceWith(
        if (answer.proposals.isNotEmpty()) listOf("Visit the highlighted zones to confirm before treating.") else listOf("Nothing needs a visit; check again after the next clear image."),
    )
    val age = daysBetween(sat.date, ctx.today)
    if (age > 10) answer.dataGaps.add("The latest clear image is from ${sat.date}: clouds have hidden the field since.")
    answer.confidence = if (age > 10) Confidence.MEDIUM else Confidence.HIGH
    return answer
}

private fun satelliteFarm(ctx: Context): AnswerBuilder {
    val answer = blank(CopilotTopic.SATELLITE, ctx, null)
    val rows = ctx.snapshot.parcels.filter { it.satellite?.healthScore != null }.sortedBy { it.satellite!!.healthScore!! }
    if (rows.isEmpty()) {
        answer.direct = "No satellite image has been analysed yet. Open a field and choose “Satellite monitoring”, or turn on automatic refresh in Settings."
        answer.confidence = Confidence.LOW
        return answer
    }
    val worst = rows.first()
    val sat = worst.satellite!!
    answer.parcelId = worst.id
    answer.direct = "Weakest field on the latest images: ${worst.name}, health ${sat.healthScore}/100 (NDVI ${jsNum(sat.ndvi)})."
    answer.reasoning.replaceWith(rows.map { "${it.name}: ${it.satellite!!.healthScore}/100, NDVI ${jsNum(it.satellite.ndvi)} (${it.satellite.date})" })
    answer.risks.replaceWith(rows.flatMap { parcel -> parcel.satellite!!.findings.filter { it.severity != Severity.INFO }.map { it.message } }.take(5))
    answer.confidence = Confidence.MEDIUM
    return answer
}

// ------------------------------------------------------------------ calendar

private const val SUGGESTION_HORIZON_DAYS = 21

/**
 * Calendar entries the copilot proposes: spraying, fertilization, harvest and
 * irrigation windows that are not planned yet.
 */
fun calendarSuggestions(snapshot: FarmSnapshot): List<TaskProposal> {
    val today = snapshot.now.take(10)
    val weather = weatherFacts(snapshot)
    val suggestions = mutableListOf<TaskProposal>()
    val builders = listOf(::fertilize, ::harvest, ::irrigate, ::spray)
    for (parcel in snapshot.parcels) {
        val ctx = Context("", snapshot, today, parcel, null, EMPTY_MEMORY, false, weather, DateExpression())
        val open = snapshot.tasks.filter { it.parcelId == parcel.id && it.status != CopilotTaskStatus.DONE }
        for (build in builders) {
            for (proposal in build(ctx, parcel).proposals) {
                val days = daysBetween(today, proposal.dueDate)
                if (days < 0 || days > SUGGESTION_HORIZON_DAYS) continue
                fun sameKind(type: String) = type == proposal.type || (taskCategory(type) == proposal.category && proposal.category != TaskCategory.MONITORING)
                if (open.any { sameKind(it.type) }) continue
                if (suggestions.any { it.parcelId == parcel.id && it.type == proposal.type }) continue
                suggestions.add(proposal)
            }
        }
    }
    return suggestions.sortedBy { it.dueDate }
}
