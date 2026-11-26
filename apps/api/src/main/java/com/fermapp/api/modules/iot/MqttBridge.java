package com.fermapp.api.modules.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fermapp.api.config.AppProperties;
import com.fermapp.api.modules.iot.IotService.Telemetry;
import com.fermapp.api.realtime.TelemetryIngestor.DeviceIdentity;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Subscribes to {@code fermapp/telemetry} when MQTT_URL is set (mqtt.bridge.ts). A message has
 * the form {@code { "deviceKey": "...", "readings": [...] }}. The URL schemes of mqtt.js
 * (mqtt://, mqtts://, ws://, wss://) are mapped to the Paho ones.
 */
@Component
public class MqttBridge {

    private static final Logger log = LoggerFactory.getLogger(MqttBridge.class);
    private static final String TOPIC = "fermapp/telemetry";

    private final AppProperties properties;
    private final IotService iot;
    private final ObjectMapper mapper;
    private MqttClient client;

    public MqttBridge(AppProperties properties, IotService iot, ObjectMapper mapper) {
        this.properties = properties;
        this.iot = iot;
        this.mapper = mapper;
    }

    static String toPahoUrl(String url) {
        return url.replaceFirst("^mqtts://", "ssl://").replaceFirst("^mqtt://", "tcp://");
    }

    @PostConstruct
    void start() {
        if (!properties.hasMqtt()) {
            return;
        }
        try {
            client = new MqttClient(toPahoUrl(properties.mqttUrl()), "fermapp-api-" + UUID.randomUUID(), new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            if (properties.mqttUsername() != null && !properties.mqttUsername().isBlank()) {
                options.setUserName(properties.mqttUsername());
            }
            if (properties.mqttPassword() != null && !properties.mqttPassword().isBlank()) {
                options.setPassword(properties.mqttPassword().toCharArray());
            }
            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverUri) {
                    try {
                        client.subscribe(TOPIC, 1);
                    } catch (MqttException error) {
                        log.warn("MQTT bridge: {}", error.getMessage());
                    }
                }

                @Override
                public void connectionLost(Throwable cause) {
                    log.warn("MQTT bridge: {}", cause == null ? "connection lost" : cause.getMessage());
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    handle(message.getPayload());
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    // The bridge only subscribes.
                }
            });
            client.connect(options);
        } catch (MqttException error) {
            log.warn("MQTT bridge: {}", error.getMessage());
        }
    }

    void handle(byte[] payload) {
        try {
            JsonNode message = mapper.readTree(payload);
            DeviceIdentity device = iot.authenticate(message.path("deviceKey").asText(""));
            Telemetry telemetry = iot.parse(message);
            if (device != null && telemetry != null) {
                iot.ingest(device, telemetry);
            } else {
                log.warn("MQTT message rejected");
            }
        } catch (Exception error) {
            log.warn("Invalid MQTT message: {}", error.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (MqttException error) {
            log.debug("MQTT bridge stop: {}", error.getMessage());
        }
    }
}
