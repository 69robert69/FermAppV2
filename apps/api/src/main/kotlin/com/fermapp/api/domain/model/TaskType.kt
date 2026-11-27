package com.fermapp.api.domain.model

/** Task types. Same values as the PostgreSQL enum type "TaskType". */
enum class TaskType {
    FERTILIZATION,
    HERBICIDE,
    FUNGICIDE,
    INSECTICIDE,
    PEST_CHECK,
    DISEASE_CHECK,
    MOISTURE_CHECK,
    LEAF_ANALYSIS,
    HARVEST,
    SOIL_PREP,
    SEED_PURCHASE,
    SOWING,
    EMERGENCE_CHECK,
    HARVEST_PREP,
}
