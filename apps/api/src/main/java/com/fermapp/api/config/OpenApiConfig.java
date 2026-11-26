package com.fermapp.api.config;

import com.fermapp.api.domain.model.Role;
import com.fermapp.api.security.Access;
import com.fermapp.api.security.AccessGroup;
import com.fermapp.api.security.AccessInterceptor;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Map;
import java.util.stream.Collectors;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document at /api/docs.json and Swagger UI at /api/docs, as before. Each operation
 * states who may call it, from its {@code @Access} annotation.
 */
@Configuration
public class OpenApiConfig {

    private static final Map<Role, String> ROLE_NAMES = Map.of(
            Role.ADMIN, "Administrator",
            Role.FARM_MANAGER, "Farm Manager",
            Role.OPERATOR, "Machine Operator",
            Role.AGRONOMIST, "Agronomist Consultant");

    @Bean
    OpenAPI fermappOpenApi() {
        return new OpenAPI()
                .info(new Info().title("FermApp API").version("1.0.0")
                        .description("Farm management API. Successful responses have the form `{ \"data\": ... }`."))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }

    @Bean
    OperationCustomizer accessDescription() {
        return (operation, handler) -> {
            Access access = AccessInterceptor.accessOf(handler);
            if (access == null || access.value() == AccessGroup.PUBLIC) {
                operation.setDescription("Public");
                return operation;
            }
            String roles = access.value().roles().stream().map(ROLE_NAMES::get).collect(Collectors.joining(", "));
            operation.setDescription("Roles: " + roles);
            operation.addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
            return operation;
        };
    }
}
