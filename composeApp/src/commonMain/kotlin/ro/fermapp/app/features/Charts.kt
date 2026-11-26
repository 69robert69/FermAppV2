package ro.fermapp.app.features

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.theme.hexColor
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import ro.fermapp.domain.TILLAGE_LIST
import ro.fermapp.domain.TillageSystem

data class BarItem(val key: String, val label: String, val color: Color, val value: Double, val detail: String? = null)

/** Horizontal bars, longest first, each with its label and value. */
@Composable
fun BarList(data: List<BarItem>, format: (Double) -> String) {
    val max = (data.maxOfOrNull { it.value } ?: 0.0).coerceAtLeast(1e-9)
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        data.forEach { item ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Txt(item.label, TextVariant.Small, color = FarmColors.chaff, modifier = Modifier.weight(1f), maxLines = 1)
                    Txt(format(item.value), TextVariant.Small, color = FarmColors.chaff)
                }
                Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(FarmColors.ridge)) {
                    Box(Modifier.fillMaxWidth((item.value / max).toFloat().coerceIn(0f, 1f)).height(10.dp).clip(RoundedCornerShape(5.dp)).background(item.color))
                }
                item.detail?.let { Txt(it, TextVariant.Small) }
            }
        }
    }
}

/** One toggle chip per tillage technology, as tillage/components.tsx TillageChips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TillageChips(value: List<TillageSystem>, onToggle: (TillageSystem) -> Unit, disabled: Boolean = false) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        TILLAGE_LIST.forEach { profile ->
            val on = profile.code in value
            val color = hexColor(profile.color)
            Row(
                Modifier.clip(RoundedCornerShape(14.dp))
                    .then(if (on) Modifier.background(color.copy(alpha = 0.18f)).border(1.dp, color, RoundedCornerShape(14.dp)) else Modifier.border(1.dp, FarmColors.line, RoundedCornerShape(14.dp)))
                    .clickable(enabled = !disabled) { onToggle(profile.code) }
                    .heightIn(min = 30.dp)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(if (on) Icons.Outlined.Check else Icons.Outlined.Close, null, tint = if (on) FarmColors.chaff else FarmColors.straw, modifier = Modifier.size(13.dp))
                Txt(profile.badge, TextVariant.Small, color = if (on) FarmColors.chaff else FarmColors.straw)
            }
        }
    }
}
