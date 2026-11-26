package ro.fermapp.app.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import ro.fermapp.app.platform.appDataDir
import ro.fermapp.app.platform.fileSystem
import ro.fermapp.app.ui.isRunActive
import ro.fermapp.app.ui.rememberNow
import ro.fermapp.domain.FieldStatus
import ro.fermapp.domain.FieldTask
import ro.fermapp.domain.TillageSetting
import ro.fermapp.domain.TillageSettings
import ro.fermapp.domain.TillageSystem
import ro.fermapp.domain.fieldStatus

/** The queries most screens share, with the keys of the original app. */

@Composable
fun rememberParcels() = rememberApi<List<Parcel>>("parcels", "/parcels")

@Composable
fun rememberTasks() = rememberApi<List<Task>>("tasks", "/tasks")

@Composable
fun rememberWork() = rememberApi<List<WorkSession>>("work", "/work")

@Composable
fun rememberMachines() = rememberApi<List<Machine>>("machines", "/machines")

@Composable
fun rememberTeam() = rememberApi<List<TeamMember>>("team", "/team")

@Composable
fun rememberMapOverview() = rememberApi<List<MapParcelInfo>>("map-overview", "/map/overview")

@Composable
fun rememberAuth(): AuthState {
    val state by Auth.flow.collectAsState()
    return state
}

fun Task.toFieldTask() = FieldTask(id, title, type, ro.fermapp.domain.TaskStatus.valueOf(status.name), dueDate)

/**
 * The plan status of every parcel: tasks due, late or done, and machines at
 * work right now. Recomputed every minute, so "today" and "late" stay true.
 */
@Composable
fun rememberFieldStatuses(): Map<String, FieldStatus> {
    val parcels = rememberParcels().data
    val tasks = rememberTasks().data
    val overview = rememberMapOverview().data
    val now = rememberNow(60_000)
    return remember(parcels, tasks, overview, now) {
        val byParcel = (tasks ?: emptyList()).filter { it.parcel != null }.groupBy { it.parcel!!.id }
        val active = (overview ?: emptyList()).associate { item -> item.parcelId to item.work.count { isRunActive(it, now) } }
        (parcels ?: emptyList()).associate { parcel ->
            parcel.id to fieldStatus(byParcel[parcel.id].orEmpty().map { it.toFieldTask() }, active[parcel.id] ?: 0, now)
        }
    }
}

/**
 * Small preferences kept on the phone (tillage costs, catalog filters, diesel price),
 * as zustand's persist kept them in AsyncStorage. One JSON file per store.
 */
class Prefs<T>(private val name: String, private val serializer: KSerializer<T>, default: T) {
    private val file = appDataDir / "prefs" / "$name.json"
    private val state = MutableStateFlow(load() ?: default)
    val flow: StateFlow<T> = state.asStateFlow()
    val value: T get() = state.value

    private fun load(): T? = runCatching {
        if (!fileSystem.exists(file)) null else AppJson.decodeFromString(serializer, fileSystem.read(file) { readUtf8() })
    }.getOrNull()

    fun update(transform: (T) -> T) {
        val next = transform(state.value)
        state.value = next
        runCatching {
            fileSystem.createDirectories(file.parent!!)
            fileSystem.write(file) { writeUtf8(AppJson.encodeToString(serializer, next)) }
        }
    }
}

/** The user's own cost (€/ha) and diesel (l/ha) per tillage technology. Missing values use the defaults. */
object TillagePrefs {
    val store = Prefs("tillage-prefs", MapSerializer(TillageSystem.serializer(), TillageSetting.serializer()), emptyMap())

    fun setValue(system: TillageSystem, costPerHa: Double? = store.value[system]?.costPerHa, fuelLPerHa: Double? = store.value[system]?.fuelLPerHa) =
        store.update { it + (system to TillageSetting(costPerHa, fuelLPerHa)) }

    fun reset() = store.update { emptyMap() }
}

@Composable
fun rememberTillageSettings(): TillageSettings {
    val settings by TillagePrefs.store.flow.collectAsState()
    return settings
}

/** Runs a change, refreshes the affected keys, and reports errors as the original Alert did. */
class ActionRunner(private val scope: CoroutineScope, private val onError: (String) -> Unit) {
    fun run(vararg invalidate: String, onSuccess: () -> Unit = {}, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
                Queries.invalidate(*invalidate)
                onSuccess()
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                onError(errorMessage(error))
            }
        }
    }
}

@Composable
fun rememberActions(): ActionRunner {
    val scope = rememberCoroutineScope()
    return remember(scope) { ActionRunner(scope) { message -> Toast.show("The action failed: $message") } }
}

@Serializable
data class Empty(val ok: Boolean = true)

/** Builds a JSON object body from pairs, skipping nulls, for apiSend-style calls. */
fun body(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.filter { it.second != null }.associate { (key, value) -> key to toJson(value) },
)

private fun toJson(value: Any?): JsonElement = when (value) {
    null -> kotlinx.serialization.json.JsonNull
    is JsonElement -> value
    is String -> kotlinx.serialization.json.JsonPrimitive(value)
    is Number -> kotlinx.serialization.json.JsonPrimitive(value)
    is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
    is Enum<*> -> kotlinx.serialization.json.JsonPrimitive(value.name)
    is List<*> -> kotlinx.serialization.json.JsonArray(value.map(::toJson))
    is Map<*, *> -> JsonObject(value.entries.filter { it.value != null }.associate { (k, v) -> k.toString() to toJson(v) })
    else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
}

@Suppress("unused")
private val stringSerializer = String.serializer()
