package com.fermapp.api.domain.rotation

import com.fermapp.api.domain.crops.Crops
import com.fermapp.api.domain.crops.Crops.CropFamily
import com.fermapp.api.domain.model.CropCode

/** Crop rotation rules (rotation.ts). */
object Rotation {

    /** A past crop. [year] is the agricultural year of the harvest. */
    @JvmRecord
    data class CropHistoryEntry(val crop: CropCode, val year: Int)

    enum class RotationVerdict { RECOMMENDED, ACCEPTABLE, NOT_RECOMMENDED }

    @JvmRecord
    data class RotationFactor(val code: String, val impact: Int, val message: String)

    @JvmRecord
    data class RotationResult(
        val crop: CropCode,
        val year: Int,
        val score: Int,
        val verdict: RotationVerdict,
        val label: String,
        val explanation: String,
        val factors: List<RotationFactor>,
    )

    const val THRESHOLD_RECOMMENDED = 70
    const val THRESHOLD_ACCEPTABLE = 40

    @JvmField
    val VERDICT_LABELS: Map<RotationVerdict, String> = mapOf(
        RotationVerdict.RECOMMENDED to "✅ Recommended",
        RotationVerdict.ACCEPTABLE to "⚠️ Acceptable",
        RotationVerdict.NOT_RECOMMENDED to "❌ Not recommended",
    )

    private const val BASE_SCORE = 75

    /** Common diseases (Sclerotinia) make these preceding crops risky for one another. */
    private val SCLEROTINIA_HOSTS = setOf(CropCode.SUNFLOWER, CropCode.RAPESEED, CropCode.SOY)

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

    /**
     * Evaluates the proposed crop based on the parcel's history.
     * The break is measured in full years without the crop: 2022 and 2027 mean a 4-year break.
     */
    @JvmStatic
    fun evaluateRotation(proposed: CropCode, proposedYear: Int, history: List<CropHistoryEntry>): RotationResult {
        val profile = Crops.profile(proposed)
        val name = profile.name
        val past = history.filter { it.year < proposedYear }.sortedByDescending { it.year }
        val factors = mutableListOf<RotationFactor>()

        val previous = past.firstOrNull()?.takeIf { it.year == proposedYear - 1 }
        val lastSame = past.firstOrNull { it.crop == proposed }
        var breakViolated = false

        if (lastSame != null) {
            val yearsAgo = proposedYear - lastSame.year
            val breakYears = yearsAgo - 1
            if (breakYears < profile.minBreakYears) {
                breakViolated = true
                val missing = profile.minBreakYears - breakYears
                factors += RotationFactor(
                    "MIN_BREAK", -(35 + missing * 10),
                    "$name was grown ${agoPhrase(yearsAgo)} on this parcel. " +
                        "A minimum break of ${yearsWord(profile.minBreakYears)} is recommended. " +
                        "The risk of disease and yield loss is high.",
                )
            } else {
                factors += RotationFactor(
                    "BREAK_OK", 5,
                    "The ${yearsWord(breakYears)} break since the last ${name.lowercase()} crop is respected.",
                )
            }
        }

        if (previous != null && previous.crop != proposed) {
            val previousProfile = Crops.profile(previous.crop)
            val previousName = previousProfile.name
            if (previousProfile.family == profile.family && profile.family != CropFamily.VEGETABLE) {
                factors += RotationFactor(
                    "SAME_FAMILY", -15,
                    "$previousName and ${name.lowercase()} belong to the same family and share diseases and pests.",
                )
            }
            if (proposed == CropCode.CORN && previousProfile.family == CropFamily.LEGUME) {
                factors += RotationFactor(
                    "CORN_AFTER_LEGUME", 15,
                    "Corn uses the nitrogen left in the soil by ${previousName.lowercase()}.",
                )
            }
            if (proposed == CropCode.SOY && previousProfile.family == CropFamily.CEREAL) {
                factors += RotationFactor(
                    "SOY_AFTER_CEREAL", 15,
                    "Soybean is recommended after cereals, and ${previousName.lowercase()} is a good preceding crop.",
                )
            }
            if (previous.crop == CropCode.ALFALFA && proposed != CropCode.CORN) {
                factors += RotationFactor("ALFALFA_NITROGEN", 15, "Alfalfa has improved the soil nitrogen reserve.")
            }
            if (proposed != CropCode.CORN && previous.crop != CropCode.ALFALFA &&
                profile.family == CropFamily.CEREAL && previousProfile.family == CropFamily.LEGUME
            ) {
                factors += RotationFactor(
                    "CEREAL_AFTER_LEGUME", 10,
                    "Cereals benefit from the nitrogen fixed by ${previousName.lowercase()}.",
                )
            }
            if (proposed in SCLEROTINIA_HOSTS && previous.crop in SCLEROTINIA_HOSTS) {
                factors += RotationFactor(
                    "SCLEROTINIA", -20,
                    "$previousName and ${name.lowercase()} are both hosts of white mould (Sclerotinia).",
                )
            }
        }

        val lastDecade = past.filter { it.year >= proposedYear - 10 }
        val sameCount = lastDecade.count { it.crop == proposed }
        if (lastDecade.size >= 4 && sameCount.toDouble() / lastDecade.size > 0.4) {
            factors += RotationFactor(
                "HIGH_FREQUENCY", -10,
                "$name occupied the parcel in $sameCount of the last ${lastDecade.size} recorded years.",
            )
        }

        if (past.isEmpty()) {
            factors += RotationFactor("NO_HISTORY", -5, "The parcel has no crop history, so the assessment is indicative.")
        }

        val raw = BASE_SCORE + factors.sumOf { it.impact }
        val capped = if (breakViolated) minOf(raw, THRESHOLD_ACCEPTABLE - 1) else raw
        val score = capped.coerceIn(0, 100)
        val verdict = when {
            score >= THRESHOLD_RECOMMENDED -> RotationVerdict.RECOMMENDED
            score >= THRESHOLD_ACCEPTABLE -> RotationVerdict.ACCEPTABLE
            else -> RotationVerdict.NOT_RECOMMENDED
        }

        // A stable sort by absolute impact, like Array.prototype.sort.
        val ordered = factors.sortedByDescending { Math.abs(it.impact) }
        val explanation = ordered.firstOrNull()?.message ?: "$name has no rotation restrictions on this parcel."
        return RotationResult(proposed, proposedYear, score, verdict, VERDICT_LABELS.getValue(verdict), explanation, ordered)
    }

    @JvmStatic
    fun rankRotationOptions(proposedYear: Int, history: List<CropHistoryEntry>, crops: List<CropCode>): List<RotationResult> =
        crops.map { evaluateRotation(it, proposedYear, history) }.sortedByDescending { it.score }
}
