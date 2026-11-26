package ro.fermapp.domain.copilot

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import ro.fermapp.domain.CropCode
import ro.fermapp.domain.TillageSystem
import ro.fermapp.domain.toJsIsoString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** The TypeScript copilot tests, ported one to one. */

private const val NOW = "2026-06-10T08:00:00.000Z"

private fun farm(
    now: String = NOW,
    canSeeFinance: Boolean = true,
    hours: (List<CopilotHour>) -> List<CopilotHour> = { it },
): FarmSnapshot {
    val start = LocalDateTime(2026, 6, 10, 0, 0).toInstant(TimeZone.UTC)
    val days = (0 until 8).map { i ->
        CopilotDay((start + i.days).toJsIsoString().take(10), 13.0, 26.0, 0.0, 5.0, 10.0, 5.0)
    }
    val forecast = (0 until 96).map { i ->
        CopilotHour((start + (8 + i).hours).toJsIsoString().take(16), 18.0, 60.0, 0.0, 5.0, 7.0)
    }
    return FarmSnapshot(
        now = now,
        farmName = "Test Farm",
        canSeeFinance = canSeeFinance,
        parcels = listOf(
            CopilotParcel(
                id = "p12",
                name = "Field 12",
                code = "F12",
                areaHa = 40.0,
                tillage = TillageSystem.CONVENTIONAL,
                irrigatedHa = 40.0,
                seasons = listOf(
                    CopilotSeason(2026, CropCode.CORN, "2026-04-20", null, null, 9.0, 190.0, null),
                    CopilotSeason(2025, CropCode.SUNFLOWER, "2025-04-05", "2025-09-01", 120.0),
                    CopilotSeason(2024, CropCode.WHEAT, "2023-10-10", "2024-07-10", 260.0),
                ),
                soil = CopilotSoil(6.6, 2.8, 32.0, 25.0, 180.0, "Clay loam", "Chernozem"),
                economics = CopilotEconomics(2026, 1050.0, 42000.0, mapOf("Fertilizers" to 35.0, "Fuel" to 20.0, "Seeds" to 25.0), 660.0, null, 1710.0),
            ),
            CopilotParcel(
                id = "p7",
                name = "North Hill",
                code = "NH",
                areaHa = 25.0,
                tillage = TillageSystem.MINIMUM_TILL,
                seasons = listOf(
                    CopilotSeason(2026, CropCode.WHEAT, "2025-10-05", null, null, 6.0, 200.0, null),
                    CopilotSeason(2025, CropCode.SOY, "2025-04-25", "2025-09-15", 70.0),
                ),
                economics = CopilotEconomics(2026, 800.0, 20000.0, mapOf("Fertilizers" to 30.0, "Fuel" to 25.0), 400.0, null, 1200.0),
            ),
        ),
        tasks = listOf(
            CopilotTask("t1", "p12", "Herbicide application – Field 12", "HERBICIDE", CopilotPriority.MEDIUM, CopilotTaskStatus.TODO, "2026-06-05T00:00:00.000Z"),
            CopilotTask("t2", "p7", "Fertilization – North Hill", "FERTILIZATION", CopilotPriority.MEDIUM, CopilotTaskStatus.TODO, "2026-06-12T00:00:00.000Z", "u2", "m1"),
            CopilotTask("t3", "p12", "Seedbed preparation – Field 12", "SOIL_PREP", CopilotPriority.LOW, CopilotTaskStatus.TODO, "2026-06-11T00:00:00.000Z"),
        ),
        runs = emptyList(),
        machines = listOf(
            CopilotMachine("m1", "Fendt 724", "TRACTOR", 240.0),
            CopilotMachine("m2", "JD 6155", "TRACTOR", 155.0),
            CopilotMachine("m3", "Amazone UX", "SPRAYER", null),
        ),
        members = listOf(
            CopilotMember("u1", "Ana", MemberRole.FARM_MANAGER),
            CopilotMember("u2", "Ion", MemberRole.OPERATOR),
            CopilotMember("u3", "Mihai", MemberRole.OPERATOR),
        ),
        weather = CopilotWeather(days, hours(forecast), 45.0),
    )
}

private fun ask(question: String, snapshot: FarmSnapshot = farm(), memory: CopilotMemory = EMPTY_MEMORY) = askCopilot(question, snapshot, memory)

class CopilotLanguageTest {
    @Test
    fun detectsTheTopicOfTheSupportedQuestions() {
        val cases = listOf(
            "What should I plant next year?" to CopilotTopic.NEXT_CROP,
            "Is corn suitable for this field?" to CopilotTopic.CROP_SUITABILITY,
            "When should I fertilize?" to CopilotTopic.FERTILIZE,
            "When should I harvest?" to CopilotTopic.HARVEST,
            "Should I irrigate this week?" to CopilotTopic.IRRIGATE,
            "What yield can I expect?" to CopilotTopic.YIELD,
            "What operations are still pending?" to CopilotTopic.PENDING,
            "What is the profitability of this field?" to CopilotTopic.PROFITABILITY,
            "What machinery should I assign?" to CopilotTopic.MACHINERY,
            "How can I reduce costs?" to CopilotTopic.REDUCE_COSTS,
            "Is No-Till a better option here?" to CopilotTopic.TILLAGE,
            "Which field should I prioritize?" to CopilotTopic.PRIORITIZE,
            "Which field has the highest ROI?" to CopilotTopic.ROI_RANKING,
            "What operations are scheduled this week?" to CopilotTopic.WEEK_SCHEDULE,
            "How many hectares still need spraying?" to CopilotTopic.HECTARES_LEFT,
            "Which tractors are currently idle?" to CopilotTopic.IDLE_MACHINES,
            "What is the expected farm profit this season?" to CopilotTopic.FARM_PROFIT,
            "How many fields have overdue operations?" to CopilotTopic.OVERDUE,
            "Move spraying to Friday." to CopilotTopic.RESCHEDULE,
            "Delay fertilization by one week." to CopilotTopic.RESCHEDULE,
            "Cancel irrigation." to CopilotTopic.CANCEL,
            "Prioritize harvesting before cultivation." to CopilotTopic.REPRIORITIZE,
            "Why?" to CopilotTopic.WHY,
            "Ce să semănez anul viitor?" to CopilotTopic.NEXT_CROP,
            "Când să recoltez?" to CopilotTopic.HARVEST,
            "Can I spray later today?" to CopilotTopic.SPRAY,
        )
        for ((question, topic) in cases) assertEquals(topic, detectTopic(normalize(question)), question)
    }

    @Test
    fun readsDates() {
        val today = "2026-06-10" // a Wednesday
        assertEquals("2026-06-12", parseDate("move spraying to friday", today).date)
        assertEquals(7, parseDate("delay fertilization by one week", today).shiftDays)
        assertEquals("2026-06-20", parseDate("apply in 10 days", today).date)
        assertEquals("2026-09-15", parseDate("harvest on 15 september", today).date)
        assertEquals("2026-06-12", parseDate("muta pe vineri", today).date)
        assertEquals(14, parseDate("amana cu doua saptamani", today).shiftDays)
    }
}

class CopilotAnswersTest {
    @Test
    fun recommendsTheNextCropFromTheParcelsOwnDataWithEverySection() {
        val answer = ask("What should I plant on Field 12 next year?").answer
        assertEquals(CopilotTopic.NEXT_CROP, answer.topic)
        assertEquals("p12", answer.parcelId)
        assertContains(answer.direct, "Field 12")
        assertContains(answer.direct, "t/ha")
        assertContains(answer.direct, "€")
        assertTrue(answer.reasoning.isNotEmpty())
        assertTrue(answer.alternatives.isNotEmpty())
        assertTrue(answer.economics.isNotEmpty())
        assertTrue(answer.proposals.any { it.type == "SOWING" })
        // Corn after corn is a weak rotation, so it is not the recommendation.
        assertNotEquals(CropCode.CORN, answer.crop)
    }

    @Test
    fun givesDifferentAnswersToDifferentQuestionsAboutTheSameField() {
        val memory = ask("What should I plant on Field 12 next year?").memory
        val answers = listOf("When should I fertilize?", "When should I harvest?", "Should I irrigate this week?", "What yield can I expect?")
            .map { ask(it, farm(), memory).answer.direct }
        assertEquals(answers.size, answers.toSet().size)
        for (direct in answers) assertContains(direct, "Field 12")
    }

    @Test
    fun remembersTheFieldAndExplainsThePreviousAnswerOnWhy() {
        val first = ask("What should I plant on Field 12?")
        val why = ask("Why?", farm(), first.memory)
        assertEquals(CopilotTopic.WHY, why.answer.topic)
        assertContains(why.answer.direct, first.answer.direct)
        assertTrue(why.answer.reasoning.size >= first.answer.reasoning.size)
        val follow = ask("And North Hill?", farm(), first.memory)
        assertEquals(CopilotTopic.NEXT_CROP, follow.answer.topic)
        assertEquals("p7", follow.answer.parcelId)
    }

    @Test
    fun timesNitrogenFromTheSowingDateAndPlansTheTask() {
        val answer = ask("When should I fertilize Field 12?").answer
        assertTrue(Regex("nitrogen", RegexOption.IGNORE_CASE).containsMatchIn(answer.direct))
        assertContains(answer.direct, "kg N/ha")
        val task = answer.proposals.firstOrNull { it.type == "NITROGEN" }
        assertNotNull(task)
        assertEquals("Apply nitrogen fertilizer – Field 12", task.title)
        assertNotNull(task.machineId)
        assertContains(task.followUps.map { it.type }, "CROP_SCOUTING")
    }

    @Test
    fun advisesAgainstSprayingWhenRainIsComing() {
        val rainy = farm(hours = { hours -> hours.mapIndexed { i, h -> if (i in 5 until 15) h.copy(precipitationMm = 2.0, precipitationProbabilityPct = 90.0) else h } })
        val answer = ask("Can I spray Field 12?", rainy).answer
        assertContains(answer.direct, "Rain is expected within 48 hours")
        assertContains(answer.direct, "not recommended")
    }

    @Test
    fun plansHarvestWithFollowUpTasksMatchedToTheTillageSystem() {
        val answer = ask("When should I harvest Field 12?", farm(now = "2026-10-27T08:00:00.000Z")).answer
        val task = answer.proposals.first { it.type == "HARVEST" }
        assertEquals(CopilotPriority.HIGH, task.priority)
        assertEquals(listOf("GRAIN_TRANSPORT", "SOIL_SAMPLING", "PLOWING"), task.followUps.map { it.type })
    }

    @Test
    fun answersFarmWideQuestions() {
        assertContains(ask("Which field has the highest ROI?").answer.direct, "Field 12")
        assertContains(ask("What is the expected farm profit this season?").answer.direct, "€")
        assertContains(ask("How many fields have overdue operations?").answer.direct, "1 field has overdue")
        val idle = ask("Which tractors are currently idle?").answer
        assertContains(idle.direct, "JD 6155")
        assertFalse(idle.direct.contains("Fendt 724"))
        assertContains(ask("How many hectares still need spraying?").answer.direct, "40 ha")
        assertContains(ask("Which field should I prioritize?").answer.direct, "Field 12")
    }

    @Test
    fun reschedulesCancelsAndReordersTasks() {
        val move = ask("Move spraying to Friday").answer
        assertEquals(1, move.changes.size)
        assertEquals(ChangeKind.RESCHEDULE, move.changes[0].kind)
        assertEquals("t1", move.changes[0].taskId)
        assertEquals("2026-06-12", move.changes[0].toDate)
        val delay = ask("Delay fertilization by one week").answer
        assertEquals("t2", delay.changes[0].taskId)
        assertEquals("2026-06-19", delay.changes[0].toDate)
        val order = ask("Prioritize cultivation before spraying").answer
        assertTrue(order.changes.any { it.kind == ChangeKind.PRIORITY && it.taskId == "t3" && it.toPriority == CopilotPriority.HIGH })
        assertTrue(order.changes.any { it.kind == ChangeKind.RESCHEDULE && it.taskId == "t1" && it.toDate == "2026-06-12" })
        val cancel = ask("Cancel the fertilization on North Hill").answer.changes[0]
        assertEquals(ChangeKind.CANCEL, cancel.kind)
        assertEquals("t2", cancel.taskId)
    }

    @Test
    fun createsATaskFromARequest() {
        val proposal = ask("Create a task to apply fungicide on North Hill next week, high priority").answer.proposals[0]
        assertEquals("FUNGICIDE", proposal.type)
        assertEquals("p7", proposal.parcelId)
        assertEquals("2026-06-15", proposal.dueDate)
        assertEquals(CopilotPriority.HIGH, proposal.priority)
        assertEquals(TaskCategory.CROP_PROTECTION, proposal.category)
    }

    @Test
    fun hidesMoneyFromOperators() {
        assertContains(ask("Which field has the highest ROI?", farm(canSeeFinance = false)).answer.direct, "only to managers")
    }
}

class ProactiveInsightsTest {
    @Test
    fun flagsOverdueWorkUnprofitableParcelsAndMissingSoilData() {
        val insights = farmInsights(farm())
        assertTrue(insights.any { it.kind == InsightKind.OVERDUE && it.parcelId == "p12" })
        assertTrue(insights.any { it.kind == InsightKind.PROFIT && it.message.contains("North Hill") })
        assertTrue(insights.any { it.kind == InsightKind.DATA })
    }

    @Test
    fun warnsAboutRainBeforePlannedFieldWork() {
        val wet = farm(hours = { hours -> hours.mapIndexed { i, h -> if (i < 30) h.copy(precipitationMm = 1.0) else h } })
        assertTrue(farmInsights(wet).any { it.kind == InsightKind.WEATHER && it.message.contains("Heavy rainfall") })
    }
}

class SuggestedTasksTest {
    @Test
    fun createsThePreviousSuggestionsWhenAskedInEnglishOrRomanian() {
        val advice = ask("When should I fertilize Field 12?")
        assertTrue(advice.answer.proposals.isNotEmpty())
        for (request in listOf("Add them to the to-do list", "Adaugă-le în to-do", "Da", "yes", "Creează task-urile recomandate")) {
            val turn = ask(request, farm(), advice.memory)
            assertEquals(CopilotTopic.CREATE_TASK, turn.answer.topic, request)
            assertTrue(turn.answer.execute, request)
            assertEquals(advice.answer.proposals.map { it.type }, turn.answer.proposals.map { it.type }, request)
        }
    }

    @Test
    fun doesNotAddATaskTwice() {
        val advice = ask("When should I fertilize Field 12?")
        val proposal = advice.answer.proposals.first()
        val base = farm()
        val existing = base.copy(
            tasks = base.tasks + CopilotTask("ai1", proposal.parcelId, proposal.title, proposal.type, proposal.priority, CopilotTaskStatus.TODO, proposal.dueDate, source = TaskSource.AI),
        )
        val turn = ask("add them to to-do", existing, advice.memory)
        assertEquals(emptyList(), turn.answer.proposals)
        assertContains(turn.answer.direct, "already in the To-Do list")
    }

    @Test
    fun createsANamedTaskDirectly() {
        val answer = ask("Creează un task de erbicidare pe North Hill vineri").answer
        assertTrue(answer.execute)
        assertEquals("HERBICIDE", answer.proposals[0].type)
        assertEquals("p7", answer.proposals[0].parcelId)
        assertEquals("2026-06-12", answer.proposals[0].dueDate)
    }

    @Test
    fun doesNotCreateTasksForQuestionsAboutTheList() {
        assertFalse(ask("What is on my to-do list this week?").answer.execute)
    }
}

class SatelliteAndCalendarTest {
    @Test
    fun answersFromTheLatestSatelliteReadingAndProposesZoneTasks() {
        val base = farm()
        val satellite = CopilotSatellite(
            date = "2026-06-08", ndvi = 0.62, ndre = 0.31, ndmi = 0.05, healthScore = 64, healthLabel = "Moderate", change7d = -0.09, change30d = 0.12,
            findings = listOf(
                SatelliteFindingSummary(
                    Severity.WARNING, "Reduced vigor – north", "North section of Field 12 shows reduced vegetation vigor.", "Lower NDVI means less biomass.",
                    SatelliteTask("FIELD_INSPECTION", "Inspect north zone – Field 12", "NDVI 0.45 vs 0.62", TaskPriority3.HIGH),
                ),
            ),
        )
        val snapshot = base.copy(parcels = listOf(base.parcels[0].copy(satellite = satellite), base.parcels[1]))
        val answer = askCopilot("What does the satellite show on Field 12?", snapshot, EMPTY_MEMORY).answer
        assertEquals(CopilotTopic.SATELLITE, answer.topic)
        assertContains(answer.direct, "64/100")
        assertEquals("FIELD_INSPECTION", answer.proposals[0].type)
        assertEquals("Inspect north zone – Field 12", answer.proposals[0].title)
        assertTrue(farmInsights(snapshot).any { it.kind == InsightKind.SATELLITE })
    }

    @Test
    fun suggestsCalendarEntriesThatAreNotPlannedYet() {
        val suggestions = calendarSuggestions(farm())
        assertTrue(suggestions.any { it.type == "NITROGEN" && it.parcelId == "p12" })
        // North Hill already has a fertilization task, so no fertilizer suggestion is repeated there.
        assertFalse(suggestions.any { it.parcelId == "p7" && it.category == TaskCategory.FERTILIZATION })
    }
}
