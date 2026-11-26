package com.fermapp.api.common.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fermapp.api.config.AppProperties;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Cache for external data (weather, soil, climate, tokens) and short locks for scheduled jobs.
 * With REDIS_URL it is shared between instances; without it, or while Redis is unreachable,
 * it falls back to a bounded in-memory map (lib/cache.ts). Simultaneous loads of the same key
 * share one call.
 */
@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);
    private static final int MAX_MEMORY_ENTRIES = 5000;
    private static final long REDIS_RETRY_AFTER_MS = 10_000;

    private record Entry(String value, long expiresAt) {
    }

    private final Map<String, Entry> memory = new LinkedHashMap<>();
    private final Map<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private volatile long redisDownUntil;

    public CacheService(AppProperties properties, ObjectProvider<StringRedisTemplate> redis, ObjectMapper mapper) {
        this.redis = properties.hasRedis() ? redis.getIfAvailable() : null;
        this.mapper = mapper;
    }

    private boolean redisReady() {
        return redis != null && System.currentTimeMillis() >= redisDownUntil;
    }

    private void redisFailed(String operation, RuntimeException error) {
        redisDownUntil = System.currentTimeMillis() + REDIS_RETRY_AFTER_MS;
        log.warn("Redis {} failed: {}", operation, error.getMessage());
    }

    private synchronized void memorySet(String key, String value, long ttlSeconds) {
        if (memory.size() >= MAX_MEMORY_ENTRIES && !memory.containsKey(key)) {
            Iterator<String> oldest = memory.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        memory.put(key, new Entry(value, System.currentTimeMillis() + ttlSeconds * 1000));
    }

    private synchronized String memoryGet(String key) {
        Entry entry = memory.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt() < System.currentTimeMillis()) {
            memory.remove(key);
            return null;
        }
        return entry.value();
    }

    private String read(String key) {
        if (redisReady()) {
            try {
                return redis.opsForValue().get(key);
            } catch (RuntimeException error) {
                redisFailed("read", error);
            }
        }
        return memoryGet(key);
    }

    private void write(String key, String value, long ttlSeconds) {
        if (redisReady()) {
            try {
                redis.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds));
                return;
            } catch (RuntimeException error) {
                redisFailed("write", error);
            }
        }
        memorySet(key, value, ttlSeconds);
    }

    public <T> T cached(String key, long ttlSeconds, TypeReference<T> type, Supplier<T> load) {
        return cached(key, ttlSeconds, mapper.getTypeFactory().constructType(type), load);
    }

    public <T> T cached(String key, long ttlSeconds, Class<T> type, Supplier<T> load) {
        return cached(key, ttlSeconds, mapper.getTypeFactory().constructType(type), load);
    }

    /** Returns the cached value or computes it; simultaneous calls share the same result. */
    @SuppressWarnings("unchecked")
    public <T> T cached(String key, long ttlSeconds, JavaType type, Supplier<T> load) {
        String hit = read(key);
        if (hit != null) {
            try {
                return mapper.readValue(hit, type);
            } catch (Exception corrupt) {
                log.warn("Ignoring unreadable cache entry {}", key);
            }
        }
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            try {
                return (T) running.join();
            } catch (CompletionException error) {
                throw unwrap(error);
            }
        }
        try {
            T value = load.get();
            write(key, mapper.writeValueAsString(value), ttlSeconds);
            mine.complete(value);
            return value;
        } catch (RuntimeException error) {
            mine.completeExceptionally(error);
            throw error;
        } catch (Exception error) {
            IllegalStateException wrapped = new IllegalStateException(error);
            mine.completeExceptionally(wrapped);
            throw wrapped;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private static RuntimeException unwrap(CompletionException error) {
        return error.getCause() instanceof RuntimeException runtime ? runtime : error;
    }

    /** Short lock, so a scheduled job runs on a single instance. */
    public boolean acquireLock(String key, long ttlSeconds) {
        String lockKey = "lock:" + key;
        if (redisReady()) {
            try {
                return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(ttlSeconds)));
            } catch (RuntimeException error) {
                redisFailed("lock", error);
            }
        }
        synchronized (this) {
            if (memoryGet(lockKey) != null) {
                return false;
            }
            memorySet(lockKey, "1", ttlSeconds);
            return true;
        }
    }
}
