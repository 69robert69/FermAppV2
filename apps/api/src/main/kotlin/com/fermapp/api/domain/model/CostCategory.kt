package com.fermapp.api.domain.model

/** Categories of recorded expenses. Same values as the PostgreSQL enum type "CostCategory". */
enum class CostCategory {
    SEED,
    FERTILIZER,
    PESTICIDE,
    FUEL,
    LABOR,
    IRRIGATION,
    OTHER,
}
