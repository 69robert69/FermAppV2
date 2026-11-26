package ro.fermapp.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ro.fermapp.app.data.QueryHandle
import ro.fermapp.app.data.Sync
import ro.fermapp.app.data.errorMessage
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.FarmText
import ro.fermapp.app.theme.Space

/** The building blocks of components/ui.tsx. */

enum class TextVariant(val style: TextStyle) {
    Display(FarmText.display),
    Title(FarmText.title),
    Subtitle(FarmText.subtitle),
    Body(FarmText.body),
    Small(FarmText.small),
    Figure(FarmText.figure),
}

@Composable
fun Txt(
    text: String,
    variant: TextVariant = TextVariant.Body,
    color: Color? = null,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
) {
    Text(
        text = text,
        style = if (color != null) variant.style.copy(color = color) else variant.style,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
    )
}

@Composable
fun OfflineBanner() {
    val sync by Sync.flow.collectAsState()
    if (sync.online && sync.pending == 0 && sync.failed == 0) return
    val message = when {
        !sync.online -> "Working offline" + (if (sync.pending > 0) ". ${sync.pending} changes are waiting for a connection" else ". The data shown is the last saved version") + "."
        sync.syncing -> "Sending ${sync.pending} changes."
        sync.failed > 0 -> "${sync.failed} changes were rejected by the server. See the details in Settings."
        else -> "${sync.pending} changes are waiting to be sent."
    }
    Row(
        Modifier.fillMaxWidth().background(FarmColors.wheat).padding(horizontal = Space.lg, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(if (sync.online) Icons.Outlined.CloudSync else Icons.Outlined.CloudOff, null, tint = FarmColors.wheatInk, modifier = Modifier.size(16.dp))
        Text(message, style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, color = FarmColors.wheatInk), modifier = Modifier.weight(1f))
    }
}

/**
 * A screen with an optional large title and action, pull to refresh and
 * vertical scrolling, on the soil-coloured background.
 */
@Composable
fun Screen(
    title: String? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    refreshing: Boolean = false,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(FarmColors.loam).then(if (title != null) Modifier.statusBarsPadding() else Modifier)) {
        OfflineBanner()
        val header: @Composable () -> Unit = {
            if (title != null) {
                Row(Modifier.fillMaxWidth().padding(top = Space.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Txt(title, TextVariant.Display, modifier = Modifier.weight(1f), maxLines = 1)
                    action?.invoke(this)
                }
            }
        }
        val body: @Composable () -> Unit = {
            if (scroll) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = Space.lg, end = Space.lg, top = Space.lg, bottom = Space.xxl),
                    verticalArrangement = Arrangement.spacedBy(Space.lg),
                ) {
                    header()
                    content()
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(horizontal = Space.lg)) { header() }
                    content()
                }
            }
        }
        if (onRefresh != null) {
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) { body() }
        } else {
            body()
        }
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(FarmColors.furrow).padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md),
        content = content,
    )
}

@Composable
fun Section(title: String, action: (@Composable RowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Txt(title, TextVariant.Title, modifier = Modifier.weight(1f))
            action?.invoke(this)
        }
        content()
    }
}

@Composable
fun Stat(value: String, label: String, unit: String? = null, color: Color? = null, modifier: Modifier = Modifier) {
    Column(modifier.widthIn(min = 130.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = color ?: FarmColors.chaff)) { append(value) }
                if (unit != null) withStyle(SpanStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, color = FarmColors.straw)) { append(" $unit") }
            },
            style = FarmText.figure,
        )
        Txt(label, TextVariant.Small)
    }
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        Txt(label, TextVariant.Small, modifier = Modifier.weight(1f))
        Txt(value, color = valueColor, align = TextAlign.End)
    }
}

@Composable
fun ProgressTrack(percent: Double, color: Color = FarmColors.wheat) {
    val animated by animateFloatAsState((percent.coerceIn(0.0, 100.0) / 100).toFloat(), tween(600))
    Box(
        Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(FarmColors.ridge)
            .semantics { contentDescription = "${percent.toInt()}%" },
    ) {
        Box(Modifier.fillMaxWidth(animated).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
    }
}

data class ChoiceOption<T>(val value: T, val label: String)

/** Horizontally scrolling chips; one is selected. */
@Composable
fun <T> Choice(options: List<ChoiceOption<T>>, value: T?, onChange: (T) -> Unit, label: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (label != null) Txt(label, TextVariant.Small)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            options.forEach { option ->
                val selected = option.value == value
                Box(
                    Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp))
                        .then(if (selected) Modifier.background(FarmColors.wheat) else Modifier.border(1.dp, FarmColors.line, RoundedCornerShape(20.dp)))
                        .clickable { onChange(option.value) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(option.label, style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, color = if (selected) FarmColors.wheatInk else FarmColors.chaff))
                }
            }
        }
    }
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    singleLine: Boolean = true,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        supportingText = supporting?.let { { Text(it) } },
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = FarmColors.furrow, unfocusedContainerColor = FarmColors.furrow),
    )
}

@Composable
fun Empty(icon: ImageVector, title: String, hint: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = Space.xxl, horizontal = Space.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(icon, null, tint = FarmColors.straw, modifier = Modifier.size(36.dp))
        Txt(title, TextVariant.Subtitle, align = TextAlign.Center)
        if (hint != null) Txt(hint, TextVariant.Small, align = TextAlign.Center)
        action?.invoke()
    }
}

/** Loading and error states for any query. */
@Composable
fun <T> QueryContent(query: QueryHandle<T>, content: @Composable (T) -> Unit) {
    val data = query.data
    when {
        data != null -> content(data)
        query.state.isError -> Empty(
            Icons.Outlined.ErrorOutline,
            "The data could not be loaded",
            errorMessage(query.state.error!!),
        ) { TextButton(onClick = { query.refetch() }) { Text("Try again") } }
        else -> Box(Modifier.fillMaxWidth().padding(vertical = Space.xxl), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FarmColors.wheat)
        }
    }
}

@Composable
fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = FarmColors.chaff) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(icon, contentDescription = label, tint = tint)
    }
}

/** A small rounded label, used for statuses and categories. */
@Composable
fun Badge(text: String, color: Color, onColor: Color = FarmColors.loam) {
    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(color).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = onColor))
    }
}

/** Two or more stats side by side, wrapping on narrow screens. */
@Composable
fun StatRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md), content = content)
}

/** A tappable list row with a title, a subtitle and optional trailing content. */
@Composable
fun ListItemRow(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(FarmColors.furrow)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(PaddingValues(horizontal = Space.lg, vertical = Space.md)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(title, TextVariant.Subtitle, maxLines = 2)
            if (subtitle != null) Txt(subtitle, TextVariant.Small, maxLines = 3)
        }
        trailing?.invoke()
    }
}
