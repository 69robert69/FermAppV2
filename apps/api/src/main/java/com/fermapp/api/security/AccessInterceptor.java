package com.fermapp.api.security;

import com.fermapp.api.common.error.Errors;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link Access} before the handler runs, and before a multipart body is read,
 * so anonymous uploads never reach memory: 401 without a valid token, 403 for a role outside
 * the group.
 */
@Component
public class AccessInterceptor implements HandlerInterceptor {

    public static final String AUTH_ATTRIBUTE = AccessInterceptor.class.getName() + ".auth";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        Access access = accessOf(method);
        if (access == null || access.value() == AccessGroup.PUBLIC) {
            return true;
        }
        AuthContext auth = current();
        if (auth == null) {
            Object tokenError = request.getAttribute(JwtAuthenticationFilter.TOKEN_ERROR_ATTRIBUTE);
            throw tokenError != null ? Errors.unauthorized(tokenError.toString()) : Errors.unauthorized();
        }
        if (!access.value().allows(auth.role())) {
            throw Errors.forbidden();
        }
        request.setAttribute(AUTH_ATTRIBUTE, auth);
        return true;
    }

    public static Access accessOf(HandlerMethod method) {
        Access access = method.getMethodAnnotation(Access.class);
        return access != null ? access : method.getBeanType().getAnnotation(Access.class);
    }

    public static AuthContext current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof FarmAuthentication farm ? farm.getPrincipal() : null;
    }
}
