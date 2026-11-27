package com.fermapp.api.domain.model

/** Status of a timed operation (field work or irrigation). Same values as the PostgreSQL enum type "RunStatus". */
enum class RunStatus {
    RUNNING,
    PAUSED,
    COMPLETED,
    CANCELLED,
}
