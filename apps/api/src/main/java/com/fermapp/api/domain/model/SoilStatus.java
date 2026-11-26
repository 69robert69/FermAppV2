package com.fermapp.api.domain.model;

/** Status of the SoilGrids analysis. Same values as the PostgreSQL enum type "SoilStatus". */
public enum SoilStatus {
    PENDING,
    READY,
    FAILED
}
