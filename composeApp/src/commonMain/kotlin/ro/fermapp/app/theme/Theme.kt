package ro.fermapp.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The palette starts from the field: loose soil, straw, ripe wheat, leaf, water and rust. */
object FarmColors {
    val loam = Color(0xFF181B13)
    val furrow = Color(0xFF22261B)
    val ridge = Color(0xFF2D3224)
    val line = Color(0xFF414835)
    val chaff = Color(0xFFF0ECDB)
    val straw = Color(0xFFACAE96)
    val wheat = Color(0xFFE2B23D)
    val wheatInk = Color(0xFF2A2106)
    val leaf = Color(0xFF8DBB5E)
    val water = Color(0xFF66ADCB)
    val rust = Color(0xFFE06E48)
    val amber = Color(0xFFE9A23B)
}

object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Text styles of the original app: display, title, subtitle, body, small and the condensed figure. */
object FarmText {
    val display = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, color = FarmColors.chaff)
    val title = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp, color = FarmColors.chaff)
    val subtitle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, color = FarmColors.chaff)
    val body = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, color = FarmColors.chaff)
    val small = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, color = FarmColors.straw)
    val figure = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 34.sp, color = FarmColors.chaff, letterSpacing = (-0.5).sp)
}

private val colorScheme = darkColorScheme(
    primary = FarmColors.wheat,
    onPrimary = FarmColors.wheatInk,
    primaryContainer = FarmColors.ridge,
    onPrimaryContainer = FarmColors.chaff,
    secondary = FarmColors.leaf,
    secondaryContainer = FarmColors.ridge,
    onSecondaryContainer = FarmColors.chaff,
    background = FarmColors.loam,
    onBackground = FarmColors.chaff,
    surface = FarmColors.furrow,
    onSurface = FarmColors.chaff,
    surfaceVariant = FarmColors.ridge,
    onSurfaceVariant = FarmColors.straw,
    surfaceContainer = FarmColors.furrow,
    surfaceContainerHigh = FarmColors.ridge,
    surfaceContainerHighest = FarmColors.ridge,
    outline = FarmColors.line,
    outlineVariant = FarmColors.line,
    error = FarmColors.rust,
)

@Composable
fun FarmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(bodyLarge = FarmText.body, bodyMedium = FarmText.body, bodySmall = FarmText.small),
        shapes = Shapes(small = RoundedCornerShape(4.dp), medium = RoundedCornerShape(6.dp), large = RoundedCornerShape(8.dp)),
        content = content,
    )
}

fun healthColor(healthClass: String?): Color = when (healthClass) {
    "GOOD" -> FarmColors.leaf
    "MODERATE" -> FarmColors.amber
    "PROBLEM" -> FarmColors.rust
    else -> FarmColors.straw
}

/** A colour written as #RRGGBB in the domain, as a Compose colour. */
fun hexColor(hex: String): Color {
    val value = hex.removePrefix("#").toLong(16)
    return Color(0xFF000000 or value)
}
