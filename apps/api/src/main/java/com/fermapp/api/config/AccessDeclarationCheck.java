package com.fermapp.api.config;

import com.fermapp.api.security.AccessInterceptor;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Fails closed: the application refuses to start if an /api/v1 endpoint does not declare
 * who may call it. In the TypeScript router the roles were a required field of every route.
 */
@Component
public class AccessDeclarationCheck {

    private final RequestMappingHandlerMapping mapping;

    public AccessDeclarationCheck(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping) {
        this.mapping = mapping;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        List<String> missing = new ArrayList<>();
        mapping.getHandlerMethods().forEach((info, method) -> {
            boolean api = info.getPatternValues().stream().anyMatch(pattern -> pattern.startsWith("/api/v1"));
            if (api && AccessInterceptor.accessOf(method) == null) {
                missing.add(info + " -> " + method);
            }
        });
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Endpoints without @Access: " + missing);
        }
    }
}
