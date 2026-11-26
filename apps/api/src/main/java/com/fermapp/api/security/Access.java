package com.fermapp.api.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares who may call an endpoint. Every handler under /api/v1 must carry it; the
 * {@link AccessInterceptor} enforces it and the startup check refuses handlers without it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Access {

    AccessGroup value();
}
