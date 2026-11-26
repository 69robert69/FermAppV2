package ro.fermapp.app.data

import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

data class SyncState(val online: Boolean = true, val pending: Int = 0, val failed: Int = 0, val syncing: Boolean = false)

/** The outbox sender of offline/sync.ts: changes go out in order when the connection returns. */
object Sync {
    private val state = MutableStateFlow(SyncState())
    val flow: StateFlow<SyncState> = state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val flushing = Mutex()

    suspend fun refreshCounts() {
        val counts = LocalStore.counts()
        state.update { it.copy(pending = counts.pending, failed = counts.failed) }
    }

    /** Any request tells us whether the server is reachable; coming back online sends the outbox. */
    fun markOnline(online: Boolean) {
        val wasOffline = !state.value.online
        state.update { it.copy(online = online) }
        if (online && wasOffline) scope.launch { flush() }
    }

    fun start(): Job = scope.launch { refreshCounts() }

    /** Sends operations in order; stops at the first connection problem. */
    suspend fun flush() {
        if (!flushing.tryLock()) return
        try {
            if (Auth.current.session == null) return
            state.update { it.copy(syncing = true) }
            var sent = 0
            for (operation in LocalStore.pendingOperations()) {
                try {
                    Api.sendJson(HttpMethod.parse(operation.method), operation.path, operation.body, offline = false, operationId = operation.id)
                    LocalStore.removeOperation(operation.id)
                    sent++
                } catch (error: ApiError) {
                    if (error.status in 1..499) {
                        // The server rejected the operation permanently, so it stays visible to the user.
                        LocalStore.failOperation(operation.id, error.message)
                        continue
                    }
                    break
                }
            }
            refreshCounts()
            state.update { it.copy(syncing = false) }
            if (sent > 0) Queries.invalidateAll()
        } finally {
            flushing.unlock()
        }
    }
}

/** Short message shown over any screen, for example when an operation completes. */
object Toast {
    private val state = MutableStateFlow<String?>(null)
    val flow: StateFlow<String?> = state.asStateFlow()
    fun show(message: String) {
        state.value = message
    }
    fun hide() {
        state.value = null
    }
}
