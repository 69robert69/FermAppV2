package ro.fermapp.app.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import ro.fermapp.app.Navigator
import ro.fermapp.app.Routes
import ro.fermapp.app.data.Api
import ro.fermapp.app.data.AppJson
import ro.fermapp.app.data.Auth
import ro.fermapp.app.data.LocalStore
import ro.fermapp.app.data.OutboxItem
import ro.fermapp.app.data.Prefs
import ro.fermapp.app.data.Queries
import ro.fermapp.app.data.Role
import ro.fermapp.app.data.SendResult
import ro.fermapp.app.data.Sync
import ro.fermapp.app.data.Toast
import ro.fermapp.app.data.body
import ro.fermapp.app.data.errorMessage
import ro.fermapp.app.data.rememberActions
import ro.fermapp.app.data.rememberAuth
import ro.fermapp.app.data.rememberTeam
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.ui.Choice
import ro.fermapp.app.ui.ChoiceOption
import ro.fermapp.app.ui.Field
import ro.fermapp.app.ui.InfoRow
import ro.fermapp.app.ui.Panel
import ro.fermapp.app.ui.QueryContent
import ro.fermapp.app.ui.ROLE_LABELS
import ro.fermapp.app.ui.Screen
import ro.fermapp.app.ui.Section
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt

/** "Show Weather Widget" and its refresh interval, saved on the phone. */
@Serializable
data class WeatherPrefsState(val enabled: Boolean = true, val refreshMinutes: Int = 30)

object WeatherPrefs {
    val store = Prefs("weather-prefs", WeatherPrefsState.serializer(), WeatherPrefsState())
    val REFRESH_OPTIONS = listOf(ChoiceOption(15, "15 min"), ChoiceOption(30, "30 min"), ChoiceOption(60, "1 hour"), ChoiceOption(180, "3 hours"))
}

@Serializable
private data class SwitchFarmResult(val farmId: String, val role: Role, val accessToken: String, val refreshToken: String)

@Composable
fun SettingsScreen(navigator: Navigator) {
    val auth = rememberAuth()
    val session = auth.session
    val sync by Sync.flow.collectAsState()
    val weather by WeatherPrefs.store.flow.collectAsState()
    val team = rememberTeam()
    val actions = rememberActions()
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf<List<OutboxItem>>(emptyList()) }
    var email by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(Role.OPERATOR) }
    var inviting by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    LaunchedEffect(sync.failed) { failed = LocalStore.failedOperations() }

    fun switchFarm(farmId: String) {
        if (sync.pending > 0) {
            Toast.show("Sync first: the unsent changes belong to the current farm.")
            return
        }
        scope.launch {
            try {
                val result = Api.post("/auth/switch-farm", body("farmId" to farmId), offline = false)
                if (result !is SendResult.Done || session == null) return@launch
                val switched = AppJson.decodeFromJsonElement(SwitchFarmResult.serializer(), result.value)
                val farm = session.farms?.firstOrNull { it.id == farmId } ?: return@launch
                Auth.setSession(session.copy(farm = farm, role = switched.role, accessToken = switched.accessToken, refreshToken = switched.refreshToken))
                Queries.clear()
            } catch (error: Throwable) {
                Toast.show("The farm could not be switched: ${errorMessage(error)}")
            }
        }
    }

    fun signOut() {
        scope.launch {
            session?.refreshToken?.let { token -> runCatching { Api.post("/auth/logout", body("refreshToken" to token), offline = false) } }
            Auth.signOut()
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out") },
            text = { Text("The data saved on this phone will be deleted." + if (sync.pending > 0) " ${sync.pending} unsent changes will be lost." else "") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; signOut() }) { Text("Sign out", color = FarmColors.rust) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }

    Screen {
        Panel {
            Txt(session?.user?.fullName ?: "", TextVariant.Title)
            Txt(session?.user?.email ?: "", TextVariant.Small)
            InfoRow("Farm", session?.farm?.name ?: "")
            InfoRow("Role", auth.role?.let { ROLE_LABELS[it.name] } ?: "")
        }

        OutlinedButton(onClick = { navigator.open(Routes.Catalog) }, modifier = Modifier.fillMaxWidth()) { Text("Farm catalog") }

        val farms = session?.farms.orEmpty()
        if (farms.size > 1) {
            Section("Switch farm") {
                Choice(farms.map { ChoiceOption(it.id, it.name) }, session?.farm?.id, { switchFarm(it) })
            }
        }

        Section("Map") {
            Panel {
                Row(
                    Modifier.fillMaxWidth().clickable { WeatherPrefs.store.update { it.copy(enabled = !it.enabled) } },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    Column(Modifier.weight(1f)) {
                        Txt("Show Weather Widget", TextVariant.Subtitle)
                        Txt(
                            if (weather.enabled) "Current weather, a 5-day forecast, alerts and field advice on the map." else "Hidden. No weather data is loaded for the map.",
                            TextVariant.Small,
                        )
                    }
                    Switch(weather.enabled, { enabled -> WeatherPrefs.store.update { it.copy(enabled = enabled) } })
                }
                if (weather.enabled) {
                    Choice(WeatherPrefs.REFRESH_OPTIONS, weather.refreshMinutes, { minutes -> WeatherPrefs.store.update { it.copy(refreshMinutes = minutes) } }, "Refresh every")
                }
            }
        }

        Section("Sync") {
            Panel {
                InfoRow("Connection", if (sync.online) "Online" else "Offline")
                InfoRow("Pending changes", sync.pending.toString())
                OutlinedButton(onClick = { scope.launch { Sync.flush() } }, enabled = sync.online && sync.pending > 0 && !sync.syncing, modifier = Modifier.fillMaxWidth()) {
                    Text(if (sync.syncing) "Syncing…" else "Sync now")
                }
                failed.forEach { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Txt(item.error ?: "", color = FarmColors.rust)
                        TextButton(onClick = {
                            scope.launch {
                                LocalStore.removeOperation(item.id)
                                Sync.refreshCounts()
                                failed = LocalStore.failedOperations()
                            }
                        }) { Text("Discard change") }
                    }
                }
            }
        }

        Section("Team") {
            QueryContent(team) { members ->
                Panel { members.forEach { InfoRow(it.fullName, ROLE_LABELS[it.role.name] ?: "") } }
            }
            if (auth.role == Role.ADMIN) {
                Panel {
                    Field(fullName, { fullName = it }, "Name")
                    Field(email, { email = it }, "Email", keyboard = KeyboardType.Email)
                    Field(password, { password = it }, "Initial password (for new accounts)", password = true)
                    Choice(Role.entries.map { ChoiceOption(it, ROLE_LABELS.getValue(it.name)) }, role, { role = it }, "Role")
                    Button(
                        onClick = {
                            inviting = true
                            actions.run("team", onSuccess = { email = ""; fullName = ""; password = "" }) {
                                try {
                                    Api.post(
                                        "/team",
                                        body("email" to email.trim(), "fullName" to fullName.trim(), "role" to role, "password" to password.ifEmpty { null }),
                                        offline = false,
                                    )
                                } finally {
                                    inviting = false
                                }
                            }
                        },
                        enabled = email.isNotBlank() && fullName.isNotBlank() && !inviting,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Add to team") }
                }
            }
        }

        TextButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) { Text("Sign out", color = FarmColors.rust) }
    }
}
