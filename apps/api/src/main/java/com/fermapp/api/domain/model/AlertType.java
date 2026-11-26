package com.fermapp.api.domain.model;

/** Weather alert types. Same values as the PostgreSQL enum type "AlertType". */
public enum AlertType {
    RAIN,
    STORM,
    HAIL,
    FROST,
    HEAT,
    WIND_SPRAYING
}
