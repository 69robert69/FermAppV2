package com.fermapp.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fermapp.api.common.json.JsonFormats;
import com.fermapp.api.common.web.BodySizeLimitFilter;
import com.fermapp.api.common.web.GlobalRateLimitFilter;
import com.fermapp.api.common.web.IdempotencyFilter;
import com.fermapp.api.common.web.IdempotencyInterceptor;
import com.fermapp.api.common.web.IdempotencyService;
import com.fermapp.api.common.web.RateLimiter;
import com.fermapp.api.common.web.RouteRateLimitInterceptor;
import com.fermapp.api.security.AccessInterceptor;
import com.fermapp.api.security.AuthContextArgumentResolver;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.format.FormatterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The request pipeline, in the order of the Express app: global rate limit, body size limit,
 * then for each route its own rate limit, authentication and roles, and idempotency.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RouteRateLimitInterceptor routeRateLimit;
    private final AccessInterceptor access;
    private final IdempotencyInterceptor idempotency;
    private final AuthContextArgumentResolver authResolver;

    public WebConfig(RouteRateLimitInterceptor routeRateLimit, AccessInterceptor access,
                     IdempotencyInterceptor idempotency, AuthContextArgumentResolver authResolver) {
        this.routeRateLimit = routeRateLimit;
        this.access = access;
        this.idempotency = idempotency;
        this.authResolver = authResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(routeRateLimit).addPathPatterns("/api/v1/**").order(1);
        registry.addInterceptor(access).addPathPatterns("/api/v1/**").order(2);
        registry.addInterceptor(idempotency).addPathPatterns("/api/v1/**").order(3);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(authResolver);
    }

    /** Query dates are read like {@code z.coerce.date()}: ISO dates, ISO date-times and epoch milliseconds. */
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, Instant.class, value -> {
            Instant parsed = JsonFormats.LenientInstantDeserializer.parse(value.trim());
            if (parsed == null) {
                throw new IllegalArgumentException("Invalid date");
            }
            return parsed;
        });
    }

    @Bean
    FilterRegistrationBean<GlobalRateLimitFilter> globalRateLimitFilter(RateLimiter limiter) {
        FilterRegistrationBean<GlobalRateLimitFilter> bean = new FilterRegistrationBean<>(new GlobalRateLimitFilter(limiter));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        bean.addUrlPatterns("/api/v1/*");
        return bean;
    }

    @Bean
    FilterRegistrationBean<BodySizeLimitFilter> bodySizeLimitFilter(ObjectMapper mapper) {
        FilterRegistrationBean<BodySizeLimitFilter> bean = new FilterRegistrationBean<>(new BodySizeLimitFilter(mapper));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return bean;
    }

    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(IdempotencyService service, ObjectMapper mapper) {
        FilterRegistrationBean<IdempotencyFilter> bean = new FilterRegistrationBean<>(new IdempotencyFilter(service, mapper));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        bean.addUrlPatterns("/api/v1/*");
        return bean;
    }
}
