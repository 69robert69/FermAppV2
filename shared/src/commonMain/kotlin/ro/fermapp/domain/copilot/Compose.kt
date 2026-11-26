package ro.fermapp.domain.copilot

import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import ro.fermapp.domain.CropCode
import ro.fermapp.domain.formatEuro
import ro.fermapp.domain.jsNum
import ro.fermapp.domain.jsRound
import ro.fermapp.domain.profile

/** Everything an answer builder needs. */
data class Context(
    /** The normalized question. */
    val text: String,
    val snapshot: FarmSnapshot,
    val today: String,
    val parcel: CopilotParcel?,
    val crop: CropCode?,
    val memory: CopilotMemory,
    /** The question is about several parcels or the whole farm. */
    val farmScope: Boolean,
    val weather: WeatherFacts?,
    val date: DateExpression,
)

/**
 * A mutable answer under construction. Builders fill the lists and fields,
 * then [build] gives the immutable [CopilotAnswer].
 */
class AnswerBuilder(var topic: CopilotTopic, var parcelId: String?, var crop: CropCode?) {
    var direct: String = ""
    val reasoning = mutableListOf<String>()
    val risks = mutableListOf<String>()
    val alternatives = mutableListOf<String>()
    val economics = mutableListOf<String>()
    val nextSteps = mutableListOf<String>()
    val proposals = mutableListOf<TaskProposal>()
    val changes = mutableListOf<TaskChange>()
    val dataGaps = mutableListOf<String>()
    var confidence: Confidence = Confidence.MEDIUM
    var execute: Boolean = false

    fun build() = CopilotAnswer(
        topic = topic,
        parcelId = parcelId,
        crop = crop,
        direct = direct,
        reasoning = reasoning.toList(),
        risks = risks.toList(),
        alternatives = alternatives.toList(),
        economics = economics.toList(),
        nextSteps = nextSteps.toList(),
        proposals = proposals.toList(),
        changes = changes.toList(),
        dataGaps = dataGaps.toList(),
        confidence = confidence,
        execute = execute,
    )
}

fun blank(topic: CopilotTopic, ctx: Context, parcelId: String? = ctx.parcel?.id) = AnswerBuilder(topic, parcelId, ctx.crop)

fun euro(value: Double) = formatEuro(jsRound(value))
fun euroHa(value: Double) = "${formatEuro(jsRound(value))}/ha"
fun ha(value: Double) = "${jsNum(jsRound(value * 100) / 100)} ha"
fun t(value: Double) = jsNum(jsRound(value * 10) / 10)
fun cropName(crop: CropCode) = crop.profile.name.lowercase()
fun signedPct(value: Double) = "${if (value > 0) "+" else ""}${jsNum(jsRound(value))}%"

/** A whole number as JavaScript prints Math.round(x). */
fun rounded(value: Double) = jsNum(jsRound(value))

private val WEEKDAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "today", "tomorrow", "Fri 14 Jun". */
fun dayLabel(day: String, today: String): String {
    val offset = daysBetween(today, day)
    if (offset == 0) return "today"
    if (offset == 1) return "tomorrow"
    val date = LocalDate.parse(day.take(10))
    return "${WEEKDAYS[date.dayOfWeek.isoDayNumber % 7]} ${date.day} ${MONTHS[date.month.ordinal]}"
}

/** "in 3 days", "2 days ago". */
fun relativeDays(day: String, today: String): String {
    val offset = daysBetween(today, day)
    return when {
        offset == 0 -> "today"
        offset == 1 -> "tomorrow"
        offset == -1 -> "yesterday"
        offset > 0 -> "in $offset days"
        else -> "${-offset} days ago"
    }
}

fun list(items: List<String>): String =
    if (items.size < 2) items.firstOrNull() ?: "" else "${items.dropLast(1).joinToString(", ")} and ${items.last()}"

data class GapNeeds(val soil: Boolean = false, val history: Boolean = false, val weather: Boolean = false, val costs: Boolean = false)

/** Missing data that limits an answer about a parcel. */
fun parcelGaps(facts: ParcelFacts, ctx: Context, needs: GapNeeds = GapNeeds()): List<String> {
    val gaps = mutableListOf<String>()
    val name = facts.parcel.name
    if (needs.soil && facts.parcel.soil == null) gaps.add("No soil analysis for $name: open the parcel to load it from SoilGrids, or record a lab analysis.")
    if (needs.history && facts.yields.isEmpty()) gaps.add("No recorded yields for $name: estimates use regional reference yields.")
    if (needs.weather && ctx.weather == null) gaps.add("The forecast is not available (no connection): weather advice is limited.")
    if (needs.costs && ctx.snapshot.canSeeFinance && facts.parcel.economics == null) {
        gaps.add("No costs recorded for $name this season: economic figures are estimates from typical costs.")
    }
    return gaps
}

fun confidenceOf(gaps: List<String>): Confidence = when {
    gaps.isEmpty() -> Confidence.HIGH
    gaps.size <= 1 -> Confidence.MEDIUM
    else -> Confidence.LOW
}
