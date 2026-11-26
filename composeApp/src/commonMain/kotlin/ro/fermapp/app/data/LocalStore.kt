package ro.fermapp.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import okio.ByteString.Companion.encodeUtf8
import ro.fermapp.app.platform.appDataDir
import ro.fermapp.app.platform.fileSystem
import kotlin.time.Clock

/**
 * The phone's offline storage, as offline/db.ts kept it in SQLite:
 * the last answer of every read, and the changes waiting for a connection.
 * Files are small JSON documents, one per cached read plus one outbox.
 */

@Serializable
data class OutboxItem(
    val id: String,
    val method: String,
    val path: String,
    val body: JsonElement? = null,
    val createdAt: Long,
    val error: String? = null,
)

data class OutboxCounts(val pending: Int, val failed: Int)

object LocalStore {
    private val cacheDir = appDataDir / "cache"
    private val outboxFile = appDataDir / "outbox.json"
    private val lock = Mutex()

    private fun ensureDirs() {
        fileSystem.createDirectories(cacheDir)
    }

    private fun cachePath(key: String) = cacheDir / "${key.encodeUtf8().sha256().hex()}.json"

    suspend fun writeCache(key: String, value: JsonElement) = lock.withLock {
        ensureDirs()
        fileSystem.write(cachePath(key)) { writeUtf8(AppJson.encodeToString(JsonElement.serializer(), value)) }
    }

    suspend fun readCache(key: String): JsonElement? = lock.withLock {
        val path = cachePath(key)
        if (!fileSystem.exists(path)) return@withLock null
        runCatching { AppJson.parseToJsonElement(fileSystem.read(path) { readUtf8() }) }.getOrNull()
    }

    private fun readOutbox(): List<OutboxItem> {
        if (!fileSystem.exists(outboxFile)) return emptyList()
        return runCatching {
            AppJson.decodeFromString(ListSerializer(OutboxItem.serializer()), fileSystem.read(outboxFile) { readUtf8() })
        }.getOrDefault(emptyList())
    }

    private fun writeOutbox(items: List<OutboxItem>) {
        fileSystem.createDirectories(appDataDir)
        fileSystem.write(outboxFile) { writeUtf8(AppJson.encodeToString(ListSerializer(OutboxItem.serializer()), items)) }
    }

    suspend fun enqueue(id: String, method: String, path: String, body: JsonElement?) = lock.withLock {
        val items = readOutbox()
        if (items.none { it.id == id }) writeOutbox(items + OutboxItem(id, method, path, body, Clock.System.now().toEpochMilliseconds()))
    }

    /** Operations waiting to be sent, in the order they were made. */
    suspend fun pendingOperations(): List<OutboxItem> = lock.withLock { readOutbox().filter { it.error == null }.sortedBy { it.createdAt } }

    suspend fun failedOperations(): List<OutboxItem> = lock.withLock { readOutbox().filter { it.error != null }.sortedBy { it.createdAt } }

    suspend fun removeOperation(id: String) = lock.withLock { writeOutbox(readOutbox().filter { it.id != id }) }

    suspend fun failOperation(id: String, error: String) = lock.withLock {
        writeOutbox(readOutbox().map { if (it.id == id) it.copy(error = error) else it })
    }

    suspend fun counts(): OutboxCounts = lock.withLock {
        val items = readOutbox()
        OutboxCounts(items.count { it.error == null }, items.count { it.error != null })
    }

    /** The account's local data is deleted on sign-out. */
    suspend fun clear() = lock.withLock {
        if (fileSystem.exists(cacheDir)) fileSystem.deleteRecursively(cacheDir)
        if (fileSystem.exists(outboxFile)) fileSystem.delete(outboxFile)
    }
}
