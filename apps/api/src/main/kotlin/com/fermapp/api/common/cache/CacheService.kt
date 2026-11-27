package com.fermapp.api.common.cache

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.ObjectMapper
import com.fermapp.api.config.AppProperties
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

/**
 * Cache for external data (weather, soil, climate, tokens) and short locks for scheduled jobs.
 * With REDIS_URL it is shared between instances; without it, or while Redis is unreachable,
 * it falls back to a bounded in-memory map (lib/cache.ts). Simultaneous loads of the same key
 * share one call.
 */
@Service
class CacheService(properties: AppProperties, redis: ObjectProvider<StringRedisTemplate>, private val mapper: ObjectMapper) {

    private class Entry(val value: String, val expiresAt: Long)

    /** Insertion-ordered, so the oldest entry is evicted first. Guarded by `this`. */
    private val memory = LinkedHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Any?>>()
    private val redis: StringRedisTemplate? = if (properties.hasRedis()) redis.getIfAvailable() else null

    @Volatile
    private var redisDownUntil = 0L

    private fun redisReady(): Boolean = redis != null && System.currentTimeMillis() >= redisDownUntil

    private fun redisFailed(operation: String, error: RuntimeException) {
        redisDownUntil = System.currentTimeMillis() + REDIS_RETRY_AFTER_MS
        log.warn("Redis {} failed: {}", operation, error.message)
    }

    @Synchronized
    private fun memorySet(key: String, value: String, ttlSeconds: Long) {
        if (memory.size >= MAX_MEMORY_ENTRIES && !memory.containsKey(key)) {
            memory.remove(memory.keys.first())
        }
        memory[key] = Entry(value, System.currentTimeMillis() + ttlSeconds * 1000)
    }

    @Synchronized
    private fun memoryGet(key: String): String? {
        val entry = memory[key] ?: return null
        if (entry.expiresAt < System.currentTimeMillis()) {
            memory.remove(key)
            return null
        }
        return entry.value
    }

    private fun read(key: String): String? {
        if (redisReady()) {
            try {
                return redis!!.opsForValue().get(key)
            } catch (error: RuntimeException) {
                redisFailed("read", error)
            }
        }
        return memoryGet(key)
    }

    private fun write(key: String, value: String, ttlSeconds: Long) {
        if (redisReady()) {
            try {
                redis!!.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds))
                return
            } catch (error: RuntimeException) {
                redisFailed("write", error)
            }
        }
        memorySet(key, value, ttlSeconds)
    }

    fun <T> cached(key: String, ttlSeconds: Long, type: TypeReference<T>, load: Supplier<T>): T =
        cached(key, ttlSeconds, mapper.typeFactory.constructType(type), load)

    fun <T> cached(key: String, ttlSeconds: Long, type: Class<T>, load: Supplier<T>): T =
        cached(key, ttlSeconds, mapper.typeFactory.constructType(type), load)

    /** Returns the cached value or computes it; simultaneous calls share the same result. */
    @Suppress("UNCHECKED_CAST")
    fun <T> cached(key: String, ttlSeconds: Long, type: JavaType, load: Supplier<T>): T {
        val hit = read(key)
        if (hit != null) {
            try {
                return mapper.readValue(hit, type)
            } catch (corrupt: Exception) {
                log.warn("Ignoring unreadable cache entry {}", key)
            }
        }
        val mine = CompletableFuture<Any?>()
        val running = inFlight.putIfAbsent(key, mine)
        if (running != null) {
            try {
                return running.join() as T
            } catch (error: CompletionException) {
                throw unwrap(error)
            }
        }
        try {
            val value = load.get()
            write(key, mapper.writeValueAsString(value), ttlSeconds)
            mine.complete(value)
            return value
        } catch (error: RuntimeException) {
            mine.completeExceptionally(error)
            throw error
        } catch (error: Exception) {
            // writeValueAsString declares a checked JsonProcessingException.
            val wrapped = IllegalStateException(error)
            mine.completeExceptionally(wrapped)
            throw wrapped
        } finally {
            inFlight.remove(key, mine)
        }
    }

    /** Short lock, so a scheduled job runs on a single instance. */
    fun acquireLock(key: String, ttlSeconds: Long): Boolean {
        val lockKey = "lock:$key"
        if (redisReady()) {
            try {
                return redis!!.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(ttlSeconds)) == true
            } catch (error: RuntimeException) {
                redisFailed("lock", error)
            }
        }
        synchronized(this) {
            if (memoryGet(lockKey) != null) {
                return false
            }
            memorySet(lockKey, "1", ttlSeconds)
            return true
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(CacheService::class.java)
        const val MAX_MEMORY_ENTRIES = 5000
        const val REDIS_RETRY_AFTER_MS = 10_000L

        fun unwrap(error: CompletionException): RuntimeException = error.cause as? RuntimeException ?: error
    }
}
