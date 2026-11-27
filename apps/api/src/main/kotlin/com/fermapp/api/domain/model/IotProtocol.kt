package com.fermapp.api.domain.model

/** How an IoT device sends its readings. Same values as the PostgreSQL enum type "IotProtocol". */
enum class IotProtocol {
    MQTT,
    REST,
    WEBSOCKET,
}
