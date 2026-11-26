package ro.fermapp.app.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.serializer
import kotlin.time.Clock

/**
 * A small stand-in for React Query: every screen asks for data by key, the
 * answer is shared and cached, and changes invalidate the keys they affect.
 * Keys follow the original app ("parcels", "tasks", "parcel/123"...).
 */

data class QueryState<T>(
    val data: T? = null,
    val error: Throwable? = null,
    val loading: Boolean = false,
    val fetchedAt: Long = 0,
) {
    val isError get() = error != null && data == null
}

private class Entry(val loader: suspend () -> Any?) {
    val state = MutableStateFlow(QueryState<Any?>())
}

object Queries {
    private const val STALE_MS = 30_000L
    private val entries = mutableMapOf<String, Entry>()

    private fun entry(key: String, loader: suspend () -> Any?): Entry = entries.getOrPut(key) { Entry(loader) }

    internal suspend fun fetch(key: String, loader: suspend () -> Any?, force: Boolean = false) {
        val entry = entry(key, loader)
        val now = Clock.System.now().toEpochMilliseconds()
        val current = entry.state.value
        if (!force && current.data != null && now - current.fetchedAt < STALE_MS) return
        if (current.loading) return
        entry.state.update { it.copy(loading = true) }
        try {
            val data = entry.loader()
            entry.state.value = QueryState(data, null, false, Clock.System.now().toEpochMilliseconds())
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) {
                entry.state.update { it.copy(loading = false) }
                throw error
            }
            entry.state.update { it.copy(error = error, loading = false) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    internal fun <T> stateFlow(key: String, loader: suspend () -> T): MutableStateFlow<QueryState<T>> =
        entry(key, loader).state as MutableStateFlow<QueryState<T>>

    /** Marks keys (and every key below them, "parcel" covers "parcel/1") stale and reloads what is on screen. */
    suspend fun invalidate(vararg keys: String) {
        val matching = entries.keys.filter { key -> keys.any { key == it || key.startsWith("$it/") } }
        for (key in matching) {
            val entry = entries[key] ?: continue
            fetch(key, entry.loader, force = true)
        }
    }

    suspend fun invalidateAll() = invalidate(*entries.keys.toTypedArray())

    /** Puts a value in the cache directly, after an action returned it. */
    fun <T> set(key: String, value: T) {
        entries[key]?.state?.value = QueryState(value, null, false, Clock.System.now().toEpochMilliseconds())
    }

    fun clear() = entries.clear()

    suspend fun prefetch(key: String, loader: suspend () -> Any?) = fetch(key, loader)
}

/** Data for a screen: cached, shared by key, refreshed when stale. */
@Composable
fun <T> rememberQuery(key: String, loader: suspend () -> T): QueryHandle<T> {
    val flow = remember(key) { Queries.stateFlow(key, loader) }
    val state by flow.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(key) { Queries.fetch(key, loader) }
    return QueryHandle(state, scope) { Queries.fetch(key, loader, force = true) }
}

@Composable
inline fun <reified T> rememberApi(key: String, path: String, query: Map<String, Any?> = emptyMap()): QueryHandle<T> {
    val strategy: DeserializationStrategy<T> = remember { serializer<T>() }
    return rememberQuery(key) { Api.get(path, strategy, query) }
}

class QueryHandle<T>(val state: QueryState<T>, private val scope: CoroutineScope, private val reload: suspend () -> Unit) {
    val data: T? get() = state.data
    val loading: Boolean get() = state.loading
    fun refetch() {
        scope.launch { reload() }
    }
}
