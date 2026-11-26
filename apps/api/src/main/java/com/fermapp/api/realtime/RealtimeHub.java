package com.fermapp.api.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fermapp.api.config.AppProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Rooms of connected app clients ({@code farm:<id>}, {@code user:<id>}) and event delivery.
 * With Redis, every emit goes through a pub/sub channel so clients connected to other
 * instances receive it too, as the socket.io Redis adapter did.
 */
@Component
public class RealtimeHub {

    private static final Logger log = LoggerFactory.getLogger(RealtimeHub.class);
    private static final String CHANNEL = "fermapp:realtime";

    private final Map<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final AppProperties properties;
    private final ObjectProvider<StringRedisTemplate> redisTemplate;
    private final ObjectProvider<RedisConnectionFactory> redisFactory;
    private StringRedisTemplate redis;
    private RedisMessageListenerContainer container;

    public RealtimeHub(ObjectMapper mapper, AppProperties properties, ObjectProvider<StringRedisTemplate> redisTemplate,
                       ObjectProvider<RedisConnectionFactory> redisFactory) {
        this.mapper = mapper;
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.redisFactory = redisFactory;
    }

    @PostConstruct
    void startRelay() {
        if (!properties.hasRedis()) {
            return;
        }
        try {
            redis = redisTemplate.getIfAvailable();
            RedisConnectionFactory factory = redisFactory.getIfAvailable();
            if (redis == null || factory == null) {
                return;
            }
            container = new RedisMessageListenerContainer();
            container.setConnectionFactory(factory);
            container.addMessageListener((message, pattern) -> {
                try {
                    JsonNode envelope = mapper.readTree(message.getBody());
                    deliver(envelope.path("room").asText(), envelope.path("event").asText(), envelope.get("payload"));
                } catch (IOException error) {
                    log.warn("Invalid realtime relay message: {}", error.getMessage());
                }
            }, new ChannelTopic(CHANNEL));
            container.afterPropertiesSet();
            container.start();
        } catch (RuntimeException error) {
            log.warn("Realtime relay over Redis unavailable: {}", error.getMessage());
            redis = null;
        }
    }

    public static String farmRoom(UUID farmId) {
        return "farm:" + farmId;
    }

    public static String userRoom(UUID userId) {
        return "user:" + userId;
    }

    public void join(WebSocketSession session, String room) {
        rooms.computeIfAbsent(room, key -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void leaveAll(WebSocketSession session) {
        rooms.values().forEach(members -> members.remove(session));
        rooms.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public void emitToFarm(UUID farmId, RealtimeEvent event, Object payload) {
        emit(farmRoom(farmId), event, payload);
    }

    public void emitToUser(UUID userId, RealtimeEvent event, Object payload) {
        emit(userRoom(userId), event, payload);
    }

    private void emit(String room, RealtimeEvent event, Object payload) {
        try {
            JsonNode body = mapper.valueToTree(payload);
            if (redis != null) {
                try {
                    String envelope = mapper.writeValueAsString(Map.of("room", room, "event", event.wireName(), "payload", body));
                    redis.convertAndSend(CHANNEL, envelope);
                    return;
                } catch (RuntimeException error) {
                    log.warn("Realtime relay failed, delivering locally: {}", error.getMessage());
                }
            }
            deliver(room, event.wireName(), body);
        } catch (IOException | RuntimeException error) {
            log.warn("Realtime emit failed: {}", error.getMessage());
        }
    }

    /** Sends a socket.io EVENT packet ({@code 42["event",payload]}) to every client in the room. */
    private void deliver(String room, String event, JsonNode payload) throws IOException {
        Set<WebSocketSession> members = rooms.get(room);
        if (members == null || members.isEmpty()) {
            return;
        }
        ArrayNode packet = mapper.createArrayNode();
        packet.add(event);
        packet.add(payload);
        TextMessage message = new TextMessage("42" + mapper.writeValueAsString(packet));
        for (WebSocketSession session : members) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                session.sendMessage(message);
            } catch (IOException | IllegalStateException error) {
                log.debug("Realtime send failed: {}", error.getMessage());
            }
        }
    }

    public void shutdown() {
        if (container != null) {
            container.stop();
        }
        rooms.values().forEach(members -> members.forEach(session -> {
            try {
                session.close();
            } catch (IOException ignored) {
                // Closing anyway.
            }
        }));
        rooms.clear();
    }
}
