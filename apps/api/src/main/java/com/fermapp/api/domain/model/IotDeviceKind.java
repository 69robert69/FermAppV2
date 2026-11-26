package com.fermapp.api.domain.model;

/** Kinds of IoT sensors. Same values as the PostgreSQL enum type "IotDeviceKind". */
public enum IotDeviceKind {
    WEATHER_STATION,
    SOIL_MOISTURE,
    SOIL_TEMPERATURE,
    CONDUCTIVITY,
    FLOW_METER,
    TANK_LEVEL
}
