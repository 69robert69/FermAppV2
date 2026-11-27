package com.fermapp.api.domain.model

/** Role of a user within a farm. Same values as the PostgreSQL enum type "Role". */
enum class Role {
    ADMIN,
    FARM_MANAGER,
    OPERATOR,
    AGRONOMIST,
}
