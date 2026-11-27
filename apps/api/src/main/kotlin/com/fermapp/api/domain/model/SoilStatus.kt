package com.fermapp.api.domain.model

/** Status of the SoilGrids analysis. Same values as the PostgreSQL enum type "SoilStatus". */
enum class SoilStatus {
    PENDING,
    READY,
    FAILED,
}
