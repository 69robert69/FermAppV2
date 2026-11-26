package com.fermapp.api.domain.model;

/** Machine types of the fleet. Same values as the PostgreSQL enum type "MachineType". */
public enum MachineType {
    TRACTOR,
    COMBINE,
    SPRAYER,
    SEEDER,
    DISC,
    CULTIVATOR,
    SUBSOILER,
    TRAILER
}
