package ro.fermapp.app.features

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.theme.hexColor
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import ro.fermapp.domain.AdviceTone
import ro.fermapp.domain.ECONOMIC_CATEGORIES
import ro.fermapp.domain.EconomicCategory
import ro.fermapp.domain.EconomicInsight
import ro.fermapp.domain.formatEuro
import ro.fermapp.domain.jsRoundInt
import kotlin.math.abs

/**
 * Cost categories in a fixed order with a categorical palette validated for
 * colour-blind separation on the app's dark surfaces. Every bar also carries a
 * legend with names and amounts, so colour is never the only cue.
 */
val CATEGORY_COLORS: Map<EconomicCategory, Color> = mapOf(
    EconomicCategory.SEEDS to hexColor("#3987e5"),
    EconomicCategory.FERTILIZERS to hexColor("#d95926"),
    EconomicCategory.CHEMICALS to hexColor("#199e70"),
    EconomicCategory.FUEL to hexColor("#c98500"),
    EconomicCategory.MACHINERY to hexColor("#d55181"),
    EconomicCategory.LABOR to hexColor("#0ca30c"),
    EconomicCategory.OTHER to hexColor("#9085e9"),
)

private fun pct(value: Double, total: Double) = if (total > 0) jsRoundInt((value / total) * 100) else 0

/** The structure of a cost: one bar split by category, then each category with amount, per hectare and share. */
@Composable
fun CostBreakdown(byCategory: Map<EconomicCategory, Double>, total: Double, areaHa: Double) {
    var focus by remember { mutableStateOf<EconomicCategory?>(null) }
    val present = ECONOMIC_CATEGORIES.filter { (byCategory[it] ?: 0.0) > 0 }
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Row(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(5.dp)).background(FarmColors.ridge), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            present.forEach { category ->
                Box(
                    Modifier.weight(byCategory.getValue(category).toFloat()).fillMaxHeight().background(CATEGORY_COLORS.getValue(category))
                        .alpha(if (focus != null && focus != category) 0.4f else 1f)
                        .clickable { focus = if (focus == category) null else category },
                )
            }
        }
        ECONOMIC_CATEGORIES.forEach { category ->
            val value = byCategory[category] ?: 0.0
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(6.dp))
                    .then(if (focus == category) Modifier.background(FarmColors.ridge) else Modifier)
                    .clickable { focus = if (focus == category) null else category }
                    .padding(horizontal = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(CATEGORY_COLORS.getValue(category)))
                Txt(category.label, color = FarmColors.chaff, modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Txt(formatEuro(value, 0), color = FarmColors.chaff)
                    Txt(if (value > 0) "${formatEuro(if (areaHa > 0) value / areaHa else 0.0, 2)}/ha, ${pct(value, total)}%" else "-", TextVariant.Small)
                }
            }
        }
    }
}

data class SignedItem(val key: String, val label: String, val value: Double?)

/** Profit can be negative: bars grow right for profit and left for loss, with the sign in the label. */
@Composable
fun SignedBars(data: List<SignedItem>, format: (Double) -> String) {
    val known = data.filter { it.value != null }
    if (known.isEmpty()) {
        Txt("Enter yields and prices to see the profit.", TextVariant.Small)
        return
    }
    val max = known.maxOf { abs(it.value!!) }.coerceAtLeast(1e-9)
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        known.forEach { item ->
            val value = item.value!!
            val positive = value >= 0
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Txt(item.label, TextVariant.Small, color = FarmColors.chaff, modifier = Modifier.weight(1f), maxLines = 1)
                    Icon(if (positive) Icons.AutoMirrored.Outlined.TrendingUp else Icons.AutoMirrored.Outlined.TrendingDown, null, tint = if (positive) FarmColors.leaf else FarmColors.rust, modifier = Modifier.size(14.dp))
                    Txt("${if (positive) "+" else "−"}${format(abs(value))}", TextVariant.Small, color = FarmColors.chaff)
                }
                BoxWithConstraints(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(4.dp)).background(FarmColors.ridge)) {
                    val half = maxWidth / 2
                    val width = half * (abs(value) / max).toFloat()
                    Box(Modifier.offset(x = if (positive) half else half - width).width(width).fillMaxHeight().background(if (positive) FarmColors.leaf else FarmColors.rust))
                    Box(Modifier.offset(x = half).width(1.dp).fillMaxHeight().background(FarmColors.straw))
                }
            }
        }
    }
}

@Composable
fun Kpi(icon: ImageVector, label: String, value: String, detail: String? = null, tone: Color = FarmColors.chaff, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(FarmColors.furrow).border(1.dp, FarmColors.chaff.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(icon, null, tint = FarmColors.straw, modifier = Modifier.size(18.dp))
        Txt(value, TextVariant.Figure, color = tone, maxLines = 1)
        Txt(label, TextVariant.Small, maxLines = 2)
        detail?.let { Txt(it, TextVariant.Small, color = FarmColors.chaff, maxLines = 1) }
    }
}

private fun toneIcon(tone: AdviceTone): Pair<ImageVector, Color> = when (tone) {
    AdviceTone.INFO -> Icons.Outlined.Lightbulb to FarmColors.water
    AdviceTone.WARNING -> Icons.Outlined.WarningAmber to FarmColors.amber
    AdviceTone.SUCCESS -> Icons.Outlined.CheckCircleOutline to FarmColors.leaf
}

@Composable
fun InsightList(insights: List<EconomicInsight>) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        insights.forEach { insight ->
            val (icon, color) = toneIcon(insight.tone)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(FarmColors.furrow).padding(Space.md),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Txt(insight.message, TextVariant.Small, color = FarmColors.chaff)
                    insight.savings?.takeIf { it != 0.0 }?.let { Txt("Possible saving: ${formatEuro(it, 0)} this season", TextVariant.Small, color = FarmColors.leaf) }
                }
            }
        }
    }
}

