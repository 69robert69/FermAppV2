package com.fermapp.api.domain.model

/** Task status. Same values as the PostgreSQL enum type "TaskStatus". */
enum class TaskStatus {
    TODO,
    IN_PROGRESS,
    DONE,
}
