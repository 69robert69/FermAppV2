package ro.fermapp.domain.copilot

import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import ro.fermapp.domain.CROP_CODES
import ro.fermapp.domain.CropCode
import ro.fermapp.domain.TillageSystem
import ro.fermapp.domain.stripDiacritics

/*
 * Light language understanding for farm questions, in English and Romanian.
 * Everything is matched on lower-case text without diacritics.
 */

private val SPACES = Regex("\\s+")

fun normalize(text: String): String =
    stripDiacritics(text.lowercase())
        .replace('’', '\'')
        .replace('`', '\'')
        .replace(SPACES, " ")
        .trim()

private val keywordPatterns = mutableMapOf<String, Regex>()

private fun keywordPattern(keyword: String): Regex = keywordPatterns.getOrPut(keyword) {
    val whole = keyword.endsWith("$")
    val body = Regex.escape(if (whole) keyword.dropLast(1) else keyword)
    Regex("(^|[^a-z0-9])$body${if (whole) "(?![a-z0-9])" else ""}")
}

/**
 * True when any keyword starts a word or phrase in the text ("fertiliz" matches
 * "fertilize"). A keyword ending in `$` must match a whole word ("late$" does not match "later").
 */
fun mentions(text: String, keywords: List<String>): Boolean = keywords.any { keywordPattern(it).containsMatchIn(text) }

private val CROP_WORDS: Map<CropCode, List<String>> = mapOf(
    CropCode.WHEAT to listOf("wheat", "grau", "griu"),
    CropCode.BARLEY to listOf("barley", "orz"),
    CropCode.CORN to listOf("corn", "maize", "porumb"),
    CropCode.SUNFLOWER to listOf("sunflower", "floarea soarelui", "floarea-soarelui", "floare"),
    CropCode.RAPESEED to listOf("rapeseed", "rape", "canola", "rapita"),
    CropCode.SOY to listOf("soy", "soya", "soybean", "soia"),
    CropCode.ALFALFA to listOf("alfalfa", "lucerne", "lucerna"),
    CropCode.OAT to listOf("oat", "oats", "ovaz"),
    CropCode.POTATO to listOf("potato", "potatoes", "cartof", "cartofi"),
    CropCode.BEET to listOf("beet", "sugar beet", "sfecla"),
    CropCode.VEGETABLES to listOf("vegetable", "vegetables", "legume"),
)

fun findCrop(text: String): CropCode? = CROP_CODES.firstOrNull { mentions(text, CROP_WORDS.getValue(it)) }

private val TILLAGE_WORDS: List<Pair<TillageSystem, List<String>>> = listOf(
    TillageSystem.NO_TILL to listOf("no-till", "no till", "notill", "semanat direct", "direct sowing"),
    TillageSystem.STRIP_TILL to listOf("strip-till", "strip till", "strip"),
    TillageSystem.MINIMUM_TILL to listOf("minimum till", "minimum-till", "min-till", "min till", "minimum tillage", "lucrari minime"),
    TillageSystem.CONVENTIONAL to listOf("conventional", "plough", "plow", "arat$", "aratura"),
)

fun findTillage(text: String): TillageSystem? = TILLAGE_WORDS.firstOrNull { mentions(text, it.second) }?.first

private val FIELD_WORDS = listOf("field", "fields", "parcel", "parcels", "parcela", "parcele", "parcelei", "tarla", "tarlaua", "sola", "camp", "campul", "lot")
private val THIS_FIELD = listOf(
    "this field", "this parcel", "that field", "that parcel", "here", "it",
    "aceasta parcela", "parcela asta", "campul asta", "tarlaua asta", "aici", "acolo",
)
private val FIELD_NUMBER = Regex("(?:${FIELD_WORDS.joinToString("|")})\\s*(?:no\\.?|nr\\.?|#)?\\s*([a-z]?\\d+[a-z]?)\\b")

/** The parcel named in the question: by name, by code, or "field 12" style numbers. */
fun findParcel(text: String, parcels: List<CopilotParcel>): CopilotParcel? {
    val named = parcels
        .filter { it.name.trim().length >= 2 }
        .sortedByDescending { it.name.length }
        .firstOrNull { parcel ->
            val name = normalize(parcel.name)
            if (name.length >= 3) text.contains(name) else mentions(text, listOf(name))
        }
    if (named != null) return named
    val coded = parcels.firstOrNull { parcel ->
        val code = parcel.code?.let(::normalize)
        code != null && code.length >= 3 && text.contains(code)
    }
    if (coded != null) return coded
    val number = FIELD_NUMBER.find(text)?.groupValues?.get(1)
    if (number != null) {
        val token = Regex("(^|[^a-z0-9])${Regex.escape(number)}([^a-z0-9]|$)")
        return parcels.firstOrNull { parcel ->
            token.containsMatchIn(normalize(parcel.name)) || (parcel.code?.let { token.containsMatchIn(normalize(it)) } ?: false)
        }
    }
    return null
}

fun refersToCurrentParcel(text: String) = mentions(text, THIS_FIELD)

/** "which field", "all fields", "the farm": the question is about several parcels. */
fun asksAboutFarm(text: String) = mentions(
    text,
    listOf(
        "which field", "which parcel", "which fields", "which parcels", "all fields", "all parcels", "farm", "every field",
        "fields", "parcels", "ce parcela", "care parcela", "care parcele", "ce parcele", "toate parcelele", "ferma", "fermei",
        "parcelele", "tarlalele", "how many fields", "cate parcele", "tractors", "tractoare", "utilaje",
    ),
)

// ------------------------------------------------------------------ dates

private val WEEKDAYS: List<List<String>> = listOf(
    listOf("sunday", "duminica"),
    listOf("monday", "luni"),
    listOf("tuesday", "marti"),
    listOf("wednesday", "miercuri"),
    listOf("thursday", "joi"),
    listOf("friday", "vineri"),
    listOf("saturday", "sambata"),
)

private val MONTH_WORDS: List<List<String>> = listOf(
    listOf("january", "jan", "ianuarie", "ian"),
    listOf("february", "feb", "februarie"),
    listOf("march", "mar", "martie"),
    listOf("april", "apr", "aprilie"),
    listOf("may", "mai"),
    listOf("june", "jun", "iunie"),
    listOf("july", "jul", "iulie"),
    listOf("august", "aug"),
    listOf("september", "sep", "sept", "septembrie"),
    listOf("october", "oct", "octombrie"),
    listOf("november", "nov", "noiembrie"),
    listOf("december", "dec", "decembrie"),
)

private val NUMBER_WORDS: Map<String, Int> = mapOf(
    "one" to 1, "a" to 1, "an" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
    "eight" to 8, "nine" to 9, "ten" to 10, "fourteen" to 14,
    "o" to 1, "un" to 1, "unu" to 1, "una" to 1, "doua" to 2, "doi" to 2, "trei" to 3, "patru" to 4, "cinci" to 5,
    "sase" to 6, "sapte" to 7, "opt" to 8, "noua" to 9, "zece" to 10,
)

private fun day(text: String): LocalDate = LocalDate.parse(text.take(10))

fun addDays(day: String, days: Int): String = LocalDate.fromEpochDays(day(day).toEpochDays() + days).toString()

fun daysBetween(from: String, to: String): Int = (day(to).toEpochDays() - day(from).toEpochDays()).toInt()

/** 0 = Sunday, as JavaScript's getUTCDay. */
fun weekdayIndex(day: String): Int = day(day).dayOfWeek.isoDayNumber % 7

private val DIGITS = Regex("^\\d+$")

private fun amount(token: String): Int? = if (DIGITS.matches(token)) token.toInt() else NUMBER_WORDS[token]

data class DateExpression(
    /** An absolute target date, YYYY-MM-DD. */
    val date: String? = null,
    /** A shift of the current date, in days ("by one week"). */
    val shiftDays: Int? = null,
)

private val SPAN = Regex("(?:by|with|cu|for|pentru)\\s+(\\d+|[a-z]+)\\s+(day|days|zi|zile|week|weeks|saptamana|saptamani)")
private val RELATIVE = Regex("(?:in|peste|within|over the next|next|\\+)\\s*(\\d+|[a-z]+)\\s+(day|days|zi|zile|week|weeks|saptamana|saptamani)")
private val NUMERIC_DATE = Regex("\\b(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?\\b")
private val WEEK_UNIT = Regex("week|sapt")

/**
 * JavaScript's Date.UTC rolls over invalid days (31 April is 1 May); this does the same,
 * so "31.04" means what it meant in the original.
 */
private fun rollingDate(year: Int, monthIndex: Int, dayOfMonth: Int): String {
    val yearShift = monthIndex.floorDiv(12)
    val month = monthIndex.mod(12) + 1
    val first = LocalDate(year + yearShift, month, 1)
    return LocalDate.fromEpochDays(first.toEpochDays() + dayOfMonth - 1).toString()
}

/** Reads "Friday", "tomorrow", "next week", "in 10 days", "by one week", "15 September", "15.09". */
fun parseDate(text: String, today: String): DateExpression {
    var date: String? = null
    var shiftDays: Int? = null
    val span = SPAN.find(text)
    if (span != null) {
        val n = amount(span.groupValues[1])
        if (n != null && n != 0) shiftDays = if (WEEK_UNIT.containsMatchIn(span.groupValues[2])) n * 7 else n
    } else if (mentions(text, listOf("a week", "one week", "o saptamana")) &&
        mentions(text, listOf("delay", "postpone", "push", "amana", "amanare", "intarzie"))
    ) {
        shiftDays = 7
    }

    val relative = RELATIVE.find(text)
    if (relative != null && span == null) {
        val n = amount(relative.groupValues[1])
        if (n != null && n != 0) date = addDays(today, if (WEEK_UNIT.containsMatchIn(relative.groupValues[2])) n * 7 else n)
    }

    when {
        mentions(text, listOf("day after tomorrow", "poimaine")) -> date = addDays(today, 2)
        mentions(text, listOf("tomorrow", "maine")) -> date = addDays(today, 1)
        mentions(text, listOf("today", "azi", "astazi")) -> if (date == null) date = today
    }
    if (mentions(text, listOf("next week", "saptamana viitoare", "saptamana urmatoare")) && date == null) {
        val toMonday = ((8 - weekdayIndex(today)) % 7).takeIf { it != 0 } ?: 7
        date = addDays(today, toMonday)
    }

    WEEKDAYS.forEachIndexed { index, names ->
        if (date != null || !mentions(text, names)) return@forEachIndexed
        val ahead = ((index - weekdayIndex(today) + 7) % 7).takeIf { it != 0 } ?: 7
        date = addDays(today, ahead)
    }

    if (date == null) {
        val year = today.take(4).toInt()
        fun pick(dayOfMonth: Int, monthIndex: Int): String? {
            if (dayOfMonth < 1 || dayOfMonth > 31 || monthIndex < 0 || monthIndex > 11) return null
            val candidate = rollingDate(year, monthIndex, dayOfMonth)
            return if (candidate < today) rollingDate(year + 1, monthIndex, dayOfMonth) else candidate
        }
        val numeric = NUMERIC_DATE.find(text)
        if (numeric != null) {
            val yearText = numeric.groupValues[3]
            val explicitYear = if (yearText.isNotEmpty()) (if (yearText.length == 2) "20$yearText" else yearText).toInt() else null
            val dayOfMonth = numeric.groupValues[1].toInt()
            val month = numeric.groupValues[2].toInt()
            date = if (explicitYear != null) rollingDate(explicitYear, month - 1, dayOfMonth) else pick(dayOfMonth, month - 1)
        } else {
            MONTH_WORDS.forEachIndexed { month, names ->
                if (date != null) return@forEachIndexed
                val pattern = names.joinToString("|")
                val dayFirst = Regex("\\b(\\d{1,2})\\s+(?:$pattern)\\b").find(text)
                val monthFirst = Regex("\\b(?:$pattern)\\s+(\\d{1,2})\\b").find(text)
                val dayText = dayFirst?.groupValues?.get(1) ?: monthFirst?.groupValues?.get(1)
                if (dayText != null) date = pick(dayText.toInt(), month)
            }
        }
    }
    return DateExpression(date, shiftDays)
}
