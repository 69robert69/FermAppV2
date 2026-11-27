package com.fermapp.api.domain.model

/** Field operation types of the work log. Same values as the PostgreSQL enum type "WorkType". */
enum class WorkType {
    PLOWING,
    DISCING,
    SUBSOILING,
    SOWING,
    FERTILIZING,
    SPRAYING,
    HARVESTING,
}
