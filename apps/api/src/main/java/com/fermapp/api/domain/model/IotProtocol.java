package com.fermapp.api.domain.model;

/** How an IoT device sends its readings. Same values as the PostgreSQL enum type "IotProtocol". */
public enum IotProtocol {
    MQTT,
    REST,
    WEBSOCKET
}
