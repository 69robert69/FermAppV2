package com.fermapp.api.modules.iot

import com.fermapp.api.config.AppProperties
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import java.util.UUID
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Subscribes to `fermapp/telemetry` when MQTT_URL is set (mqtt.bridge.ts). A message has
 * the form `{ "deviceKey": "...", "readings": [...] }`. The URL schemes of mqtt.js
 * (mqtt://, mqtts://, ws://, wss://) are mapped to the Paho ones.
 */
@Component
class MqttBridge(
    private val properties: AppProperties,
    private val iot: IotService,
    private val mapper: ObjectMapper,
) {

    private var client: MqttClient? = null

    @PostConstruct
    fun start() {
        if (!properties.hasMqtt()) {
            return
        }
        try {
            val mqtt = MqttClient(toPahoUrl(properties.mqttUrl()), "fermapp-api-" + UUID.randomUUID(), MemoryPersistence())
            client = mqtt
            val options = MqttConnectOptions()
            options.isAutomaticReconnect = true
            options.isCleanSession = true
            if (!properties.mqttUsername().isNullOrBlank()) {
                options.userName = properties.mqttUsername()
            }
            if (!properties.mqttPassword().isNullOrBlank()) {
                options.password = properties.mqttPassword().toCharArray()
            }
            mqtt.setCallback(
                object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverUri: String?) {
                        try {
                            mqtt.subscribe(TOPIC, 1)
                        } catch (error: MqttException) {
                            log.warn("MQTT bridge: {}", error.message)
                        }
                    }

                    override fun connectionLost(cause: Throwable?) {
                        log.warn("MQTT bridge: {}", cause?.message ?: "connection lost")
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage) {
                        handle(message.payload)
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {
                        // The bridge only subscribes.
                    }
                },
            )
            mqtt.connect(options)
        } catch (error: MqttException) {
            log.warn("MQTT bridge: {}", error.message)
        }
    }

    fun handle(payload: ByteArray) {
        try {
            val message = mapper.readTree(payload)
            val device = iot.authenticate(message.path("deviceKey").asText(""))
            val telemetry = iot.parse(message)
            if (device != null && telemetry != null) {
                iot.ingest(device, telemetry)
            } else {
                log.warn("MQTT message rejected")
            }
        } catch (error: Exception) {
            log.warn("Invalid MQTT message: {}", error.message)
        }
    }

    @PreDestroy
    fun stop() {
        val mqtt = client ?: return
        try {
            if (mqtt.isConnected) {
                mqtt.disconnect()
            }
            mqtt.close()
        } catch (error: MqttException) {
            log.debug("MQTT bridge stop: {}", error.message)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(MqttBridge::class.java)
        private const val TOPIC = "fermapp/telemetry"

        @JvmStatic
        fun toPahoUrl(url: String): String = url.replaceFirst(Regex("^mqtts://"), "ssl://").replaceFirst(Regex("^mqtt://"), "tcp://")
    }
}
