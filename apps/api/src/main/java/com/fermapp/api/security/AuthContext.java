package com.fermapp.api.security;

import com.fermapp.api.domain.model.Role;
import java.util.UUID;

/** Who is calling: the user, the active farm and the user's role in that farm (from the access token). */
public record AuthContext(UUID userId, UUID farmId, Role role) {

    public boolean is(Role candidate) {
        return role == candidate;
    }
}
