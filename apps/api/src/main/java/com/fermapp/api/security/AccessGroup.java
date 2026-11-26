package com.fermapp.api.security;

import com.fermapp.api.domain.model.Role;
import java.util.EnumSet;
import java.util.Set;

/** The role groups of the TypeScript ROLES constant, plus PUBLIC for endpoints without authentication. */
public enum AccessGroup {
    PUBLIC(EnumSet.noneOf(Role.class)),
    ALL(EnumSet.of(Role.ADMIN, Role.FARM_MANAGER, Role.OPERATOR, Role.AGRONOMIST)),
    MANAGERS(EnumSet.of(Role.ADMIN, Role.FARM_MANAGER)),
    PLANNERS(EnumSet.of(Role.ADMIN, Role.FARM_MANAGER, Role.AGRONOMIST)),
    FIELD(EnumSet.of(Role.ADMIN, Role.FARM_MANAGER, Role.OPERATOR)),
    ADMIN(EnumSet.of(Role.ADMIN));

    private final Set<Role> roles;

    AccessGroup(Set<Role> roles) {
        this.roles = roles;
    }

    public Set<Role> roles() {
        return roles;
    }

    public boolean allows(Role role) {
        return roles.contains(role);
    }
}
