package com.fermapp.api.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fermapp.api.common.error.ApiException;
import com.fermapp.api.security.AuthContext;
import com.fermapp.api.security.JwtService;
import java.io.IOException;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * A socket.io v4 server (Engine.IO protocol 4, Socket.IO protocol 5) over the WebSocket
 * transport, on the same port as the API, so the app's {@code socket.io-client} connects
 * unchanged with {@code transports: ['websocket']}.
 *
 * <ul>
 *   <li>Namespace {@code /}: the CONNECT packet carries {@code { token }}; a valid access token joins
 *       the farm and user rooms, otherwise the client gets {@code connect_error} "UNAUTHORIZED".</li>
 *   <li>Namespace {@code /iot}: sensors authenticate with {@code { deviceKey }} and send
 *       {@code telemetry} events; the acknowledgement is {@code { ok, accepted }} or {@code { ok: false, error }}.</li>
 * </ul>
 */
@Component
public class SocketIoHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SocketIoHandler.class);
    private static final long PING_INTERVAL_MS = 25_000;
    private static final long PING_TIMEOUT_MS = 20_000;
    private static final int MAX_PAYLOAD = 1_000_000;
    private static final String IOT = "/iot";

    private record Connection(WebSocketSession session, String sid, Map<String, Object> namespaces,
                              ScheduledFuture<?> heartbeat, long[] lastPong) {
    }

    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private final ObjectMapper mapper;
    private final JwtService jwtService;
    private final RealtimeHub hub;
    private final TelemetryIngestor telemetry;

    public SocketIoHandler(ObjectMapper mapper, JwtService jwtService, RealtimeHub hub, TelemetryIngestor telemetry) {
        this.mapper = mapper;
        this.jwtService = jwtService;
        this.hub = hub;
        this.telemetry = telemetry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 10_000, 512 * 1024);
        String sid = UUID.randomUUID().toString().replace("-", "");
        long[] lastPong = {System.currentTimeMillis()};
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(() -> ping(raw.getId()),
                PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
        connections.put(raw.getId(), new Connection(session, sid, new ConcurrentHashMap<>(), heartbeat, lastPong));
        String open = mapper.writeValueAsString(Map.of(
                "sid", sid, "upgrades", new String[0], "pingInterval", PING_INTERVAL_MS,
                "pingTimeout", PING_TIMEOUT_MS, "maxPayload", MAX_PAYLOAD));
        session.sendMessage(new TextMessage("0" + open));
    }

    private void ping(String id) {
        Connection connection = connections.get(id);
        if (connection == null) {
            return;
        }
        try {
            if (System.currentTimeMillis() - connection.lastPong()[0] > PING_INTERVAL_MS + PING_TIMEOUT_MS) {
                connection.session().close(CloseStatus.GOING_AWAY);
                return;
            }
            connection.session().sendMessage(new TextMessage("2"));
        } catch (IOException | IllegalStateException error) {
            log.debug("Ping failed: {}", error.getMessage());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) throws Exception {
        Connection connection = connections.get(raw.getId());
        String text = message.getPayload();
        if (connection == null || text.isEmpty()) {
            return;
        }
        switch (text.charAt(0)) {
            case '3' -> connection.lastPong()[0] = System.currentTimeMillis();
            case '1' -> raw.close(CloseStatus.NORMAL);
            case '4' -> handleSocketPacket(connection, text.substring(1));
            default -> {
                // Engine.IO upgrade and noop packets need no answer on a pure WebSocket transport.
            }
        }
    }

    /** A parsed Socket.IO packet: type, namespace, optional acknowledgement id and JSON data. */
    private record Packet(char type, String namespace, Long ackId, JsonNode data) {
    }

    private Packet parse(String text) throws IOException {
        char type = text.charAt(0);
        int index = 1;
        String namespace = "/";
        if (index < text.length() && text.charAt(index) == '/') {
            int comma = text.indexOf(',', index);
            namespace = comma < 0 ? text.substring(index) : text.substring(index, comma);
            index = comma < 0 ? text.length() : comma + 1;
        }
        int ackStart = index;
        while (index < text.length() && Character.isDigit(text.charAt(index))) {
            index++;
        }
        Long ackId = index > ackStart ? Long.parseLong(text.substring(ackStart, index)) : null;
        JsonNode data = index < text.length() ? mapper.readTree(text.substring(index)) : null;
        return new Packet(type, namespace, ackId, data);
    }

    private String prefix(String namespace) {
        return "/".equals(namespace) ? "" : namespace + ",";
    }

    private void send(Connection connection, String packet) {
        try {
            connection.session().sendMessage(new TextMessage(packet));
        } catch (IOException | IllegalStateException error) {
            log.debug("Socket send failed: {}", error.getMessage());
        }
    }

    private void connectError(Connection connection, String namespace, String reason) throws IOException {
        send(connection, "44" + prefix(namespace) + mapper.writeValueAsString(Map.of("message", reason)));
    }

    private void handleSocketPacket(Connection connection, String text) throws IOException {
        if (text.isEmpty()) {
            return;
        }
        Packet packet = parse(text);
        switch (packet.type()) {
            case '0' -> connect(connection, packet);
            case '1' -> connection.namespaces().remove(packet.namespace());
            case '2' -> event(connection, packet);
            default -> {
                // Acknowledgements from the client and binary packets are not used by the app.
            }
        }
    }

    private void connect(Connection connection, Packet packet) throws IOException {
        JsonNode auth = packet.data() == null ? mapper.createObjectNode() : packet.data();
        if ("/".equals(packet.namespace())) {
            try {
                AuthContext context = jwtService.verify(auth.path("token").asText(""));
                hub.join(connection.session(), RealtimeHub.farmRoom(context.farmId()));
                hub.join(connection.session(), RealtimeHub.userRoom(context.userId()));
                connection.namespaces().put("/", context);
            } catch (ApiException error) {
                connectError(connection, "/", "UNAUTHORIZED");
                return;
            }
        } else if (IOT.equals(packet.namespace())) {
            TelemetryIngestor.DeviceIdentity device;
            try {
                device = telemetry.authenticate(auth.path("deviceKey").asText(""));
            } catch (RuntimeException error) {
                device = null;
            }
            if (device == null) {
                connectError(connection, IOT, "UNAUTHORIZED");
                return;
            }
            connection.namespaces().put(IOT, device);
        } else {
            connectError(connection, packet.namespace(), "Invalid namespace");
            return;
        }
        String sid = UUID.randomUUID().toString().replace("-", "");
        send(connection, "40" + prefix(packet.namespace()) + mapper.writeValueAsString(Map.of("sid", sid)));
    }

    private void event(Connection connection, Packet packet) throws IOException {
        if (!IOT.equals(packet.namespace()) || !(connection.namespaces().get(IOT) instanceof TelemetryIngestor.DeviceIdentity device)) {
            return;
        }
        if (packet.data() == null || !packet.data().isArray() || !"telemetry".equals(packet.data().path(0).asText())) {
            return;
        }
        JsonNode payload = packet.data().size() > 1 ? packet.data().get(1) : null;
        Map<String, Object> result;
        try {
            OptionalInt accepted = telemetry.ingest(device, payload);
            result = accepted.isPresent()
                    ? Map.of("ok", true, "accepted", accepted.getAsInt())
                    : Map.of("ok", false, "error", "INVALID_PAYLOAD");
        } catch (RuntimeException error) {
            log.error("WebSocket telemetry failed", error);
            result = Map.of("ok", false, "error", "INGEST_FAILED");
        }
        if (packet.ackId() != null) {
            ArrayNode args = mapper.createArrayNode();
            args.add(mapper.valueToTree(result));
            send(connection, "43" + prefix(IOT) + packet.ackId() + mapper.writeValueAsString(args));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        Connection connection = connections.remove(raw.getId());
        if (connection != null) {
            connection.heartbeat().cancel(false);
            hub.leaveAll(connection.session());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession raw, Throwable exception) {
        log.debug("Socket transport error: {}", exception.getMessage());
    }

    public void shutdown() {
        scheduler.shutdownNow();
        hub.shutdown();
    }
}
