package ro.fermapp.domain

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class CropHistoryEntry(
    val crop: CropCode,
    /** Agricultural year of the harvest. */
    val year: Int,
)

@Serializable
enum class RotationVerdict(val label: String) {
    RECOMMENDED("✅ Recommended"),
    ACCEPTABLE("⚠️ Acceptable"),
    NOT_RECOMMENDED("❌ Not recommended"),
}

@Serializable
data class RotationFactor(val code: String, val impact: Int, val message: String)

@Serializable
data class RotationResult(
    val crop: CropCode,
    val year: Int,
    val score: Int,
    val verdict: RotationVerdict,
    val label: String,
    val explanation: String,
    val factors: List<RotationFactor>,
)

object RotationThresholds {
    const val RECOMMENDED = 70
    const val ACCEPTABLE = 40
}

private const val BASE_SCORE = 75

private fun yearsWord(n: Int) = when (n) {
    1 -> "one year"
    2 -> "two years"
    else -> "$n years"
}

private fun agoPhrase(n: Int) = when (n) {
    0 -> "this year"
    1 -> "last year"
    else -> "$n years ago"
}

/** Common diseases (Sclerotinia) make these preceding crops risky for one another. */
private val SCLEROTINIA_HOSTS = setOf(CropCode.SUNFLOWER, CropCode.RAPESEED, CropCode.SOY)

/**
 * Evaluates the proposed crop based on the parcel's history.
 * The break is measured in full years without the crop: 2022 and 2027 mean a 4-year break.
 */
fun evaluateRotation(proposed: CropCode, proposedYear: Int, history: List<CropHistoryEntry>): RotationResult {
    val profile = proposed.profile
    val past = history.filter { it.year < proposedYear }.sortedByDescending { it.year }
    val factors = mutableListOf<RotationFactor>()
    fun add(code: String, impact: Int, message: String) = factors.add(RotationFactor(code, impact, message))

    val previous = past.firstOrNull()?.takeIf { it.year == proposedYear - 1 }
    val lastSame = past.firstOrNull { it.crop == proposed }
    var breakViolated = false

    if (lastSame != null) {
        val yearsAgo = proposedYear - lastSame.year
        val breakYears = yearsAgo - 1
        if (breakYears < profile.minBreakYears) {
            breakViolated = true
            val missing = profile.minBreakYears - breakYears
            add(
                "MIN_BREAK",
                -(35 + missing * 10),
                "${profile.name} was grown ${agoPhrase(yearsAgo)} on this parcel. " +
                    "A minimum break of ${yearsWord(profile.minBreakYears)} is recommended. " +
                    "The risk of disease and yield loss is high.",
            )
        } else {
            add("BREAK_OK", 5, "The ${yearsWord(breakYears)} break since the last ${profile.name.lowercase()} crop is respected.")
        }
    }

    if (previous != null && previous.crop != proposed) {
        val previousProfile = previous.crop.profile
        val prevName = previousProfile.name.lowercase()

        if (previousProfile.family == profile.family && profile.family != CropFamily.VEGETABLE) {
            add("SAME_FAMILY", -15, "${previousProfile.name} and ${profile.name.lowercase()} belong to the same family and share diseases and pests.")
        }
        if (proposed == CropCode.CORN && previousProfile.family == CropFamily.LEGUME) {
            add("CORN_AFTER_LEGUME", 15, "Corn uses the nitrogen left in the soil by $prevName.")
        }
        if (proposed == CropCode.SOY && previousProfile.family == CropFamily.CEREAL) {
            add("SOY_AFTER_CEREAL", 15, "Soybean is recommended after cereals, and $prevName is a good preceding crop.")
        }
        if (previous.crop == CropCode.ALFALFA && proposed != CropCode.CORN) {
            add("ALFALFA_NITROGEN", 15, "Alfalfa has improved the soil nitrogen reserve.")
        }
        if (proposed != CropCode.CORN && previous.crop != CropCode.ALFALFA &&
            profile.family == CropFamily.CEREAL && previousProfile.family == CropFamily.LEGUME
        ) {
            add("CEREAL_AFTER_LEGUME", 10, "Cereals benefit from the nitrogen fixed by $prevName.")
        }
        if (proposed in SCLEROTINIA_HOSTS && previous.crop in SCLEROTINIA_HOSTS) {
            add("SCLEROTINIA", -20, "${previousProfile.name} and ${profile.name.lowercase()} are both hosts of white mould (Sclerotinia).")
        }
    }

    val lastDecade = past.filter { it.year >= proposedYear - 10 }
    val sameCount = lastDecade.count { it.crop == proposed }
    if (lastDecade.size >= 4 && sameCount.toDouble() / lastDecade.size > 0.4) {
        add("HIGH_FREQUENCY", -10, "${profile.name} occupied the parcel in $sameCount of the last ${lastDecade.size} recorded years.")
    }

    if (past.isEmpty()) {
        add("NO_HISTORY", -5, "The parcel has no crop history, so the assessment is indicative.")
    }

    val raw = factors.fold(BASE_SCORE) { score, factor -> score + factor.impact }
    val capped = if (breakViolated) minOf(raw, RotationThresholds.ACCEPTABLE - 1) else raw
    val score = capped.coerceIn(0, 100)
    val verdict = when {
        score >= RotationThresholds.RECOMMENDED -> RotationVerdict.RECOMMENDED
        score >= RotationThresholds.ACCEPTABLE -> RotationVerdict.ACCEPTABLE
        else -> RotationVerdict.NOT_RECOMMENDED
    }

    // sortedByDescending is stable, like Array.prototype.sort.
    val ordered = factors.sortedByDescending { abs(it.impact) }
    return RotationResult(
        crop = proposed,
        year = proposedYear,
        score = score,
        verdict = verdict,
        label = verdict.label,
        explanation = ordered.firstOrNull()?.message ?: "${profile.name} has no rotation restrictions on this parcel.",
        factors = ordered,
    )
}

fun rankRotationOptions(proposedYear: Int, history: List<CropHistoryEntry>, crops: List<CropCode>): List<RotationResult> =
    crops.map { evaluateRotation(it, proposedYear, history) }.sortedByDescending { it.score }
