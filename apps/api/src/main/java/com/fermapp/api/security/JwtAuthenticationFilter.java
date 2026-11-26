package com.fermapp.api.security;

import com.fermapp.api.common.error.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the Bearer token. A valid token authenticates the request; an invalid one is only
 * remembered, because public endpoints ignore the header and protected ones answer 401 with
 * the "session expired" message (see {@link AccessInterceptor}).
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String TOKEN_ERROR_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".error";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                AuthContext auth = jwtService.verify(token);
                SecurityContextHolder.getContext().setAuthentication(new FarmAuthentication(auth, token));
            } catch (ApiException error) {
                request.setAttribute(TOKEN_ERROR_ATTRIBUTE, error.getMessage());
            }
        }
        chain.doFilter(request, response);
    }
}
