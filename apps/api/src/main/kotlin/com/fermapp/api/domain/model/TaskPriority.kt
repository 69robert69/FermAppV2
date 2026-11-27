package com.fermapp.api.domain.model

/** Task priority. Same values as the PostgreSQL enum type "TaskPriority". */
enum class TaskPriority {
    LOW,
    MEDIUM,
    HIGH,
    URGENT,
}
