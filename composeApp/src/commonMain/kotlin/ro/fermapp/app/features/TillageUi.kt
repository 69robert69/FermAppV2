package ro.fermapp.app.features

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.serialization.builtins.serializer
import ro.fermapp.app.data.Machine
import ro.fermapp.app.data.Prefs
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.hexColor
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import ro.fermapp.domain.DEFAULT_FUEL_PRICE_EUR
import ro.fermapp.domain.IMPLEMENT_CATALOG
import ro.fermapp.domain.TillageMachine
import ro.fermapp.domain.TillageSystem
import ro.fermapp.domain.fuelLitersPerHour
import ro.fermapp.domain.profile

/** The technology's short badge in its categorical colour, as tillage/components.tsx. */
@Composable
fun TillageBadge(system: TillageSystem?, small: Boolean = false) {
    if (system == null) return
    val profile = system.profile
    val color = hexColor(profile.color)
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.18f)).border(1.dp, color, RoundedCornerShape(12.dp))
            .heightIn(min = if (small) 20.dp else 24.dp).padding(horizontal = if (small) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(if (small) 6.dp else 8.dp).clip(CircleShape).background(color))
        Txt(profile.badge, TextVariant.Small, color = FarmColors.chaff)
    }
}

/** The diesel price, in euro per litre, stays saved on the phone between sessions. */
object CatalogPrefs {
    val fuelPrice = Prefs("catalog-fuel-price", Double.serializer(), DEFAULT_FUEL_PRICE_EUR)
}

@Composable
fun rememberFuelPrice(): Double {
    val price by CatalogPrefs.fuelPrice.flow.collectAsState()
    return price
}

/** Diesel per hour of a machine for an operation, from its data sheet range or its average. */
fun machineFuelLPerH(machine: Machine?, operation: String?): Double? {
    if (machine == null) return null
    val load = IMPLEMENT_CATALOG.firstOrNull { it.operation.name == operation }?.fuelLoad
    val min = machine.fuelConsumptionMin
    val max = machine.fuelConsumptionMax
    if (min != null && max != null) return if (load != null) fuelLitersPerHour(min, max, load) else fuelLitersPerHour(min, max)
    return machine.avgConsumptionLh
}

fun Machine.toTillageMachine() = TillageMachine(type, tillageSystems?.map { it.name }, name)
