package com.fermapp.api.domain.model

/** Weather alert severity. Same values as the PostgreSQL enum type "AlertSeverity". */
enum class AlertSeverity {
    INFO,
    WARNING,
    SEVERE,
}
