package com.fermapp.api.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** The Spring Security authentication of a request carrying a valid access token. */
public class FarmAuthentication extends AbstractAuthenticationToken {

    private final AuthContext context;
    private final String token;

    public FarmAuthentication(AuthContext context, String token) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + context.role().name())));
        this.context = context;
        this.token = token;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return token;
    }

    @Override
    public AuthContext getPrincipal() {
        return context;
    }
}
