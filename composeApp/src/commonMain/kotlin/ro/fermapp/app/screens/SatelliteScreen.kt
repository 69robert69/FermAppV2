package ro.fermapp.app.screens

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import ro.fermapp.app.Navigator
import ro.fermapp.app.Routes
import ro.fermapp.app.data.canPlan
import ro.fermapp.app.data.rememberAuth
import ro.fermapp.app.ui.Screen

/**
 * Satellite monitoring of one parcel, opened from the map. It shows the Sentinel images
 * the server downloads (NDVI map, health, comparison and history) on a full screen.
 */
@Composable
fun SatelliteScreen(parcelId: String, name: String, navigator: Navigator) {
    val auth = rememberAuth()
    Screen(title = "Satellite") {
        SatelliteTab(parcelId, canPlan(auth.role))
        OutlinedButton(onClick = { navigator.open(Routes.ParcelDetail(parcelId, name)) }, modifier = Modifier.fillMaxWidth()) { Text("Open parcel") }
    }
}
