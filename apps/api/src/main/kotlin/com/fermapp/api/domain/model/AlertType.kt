package com.fermapp.api.domain.model

/** Weather alert types. Same values as the PostgreSQL enum type "AlertType". */
enum class AlertType {
    RAIN,
    STORM,
    HAIL,
    FROST,
    HEAT,
    WIND_SPRAYING,
}
