package com.fermapp.api.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fermapp.api.common.json.JsonFormats;
import org.openapitools.jackson.nullable.JsonNullableModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JSON settings. Requests are read as strictly as the zod schemas did: no string-to-number
 * or float-to-int coercion, unknown fields ignored (zod strips them). Responses keep the
 * JavaScript number and date formats.
 */
@Configuration
public class JacksonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer fermappJackson() {
        return builder -> builder
                .modulesToInstall(modules -> {
                    modules.add(new JsonNullableModule());
                    modules.add(JsonFormats.module());
                })
                .featuresToDisable(
                        SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.ACCEPT_FLOAT_AS_INT,
                        MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .featuresToEnable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .postConfigurer(mapper -> {
                    mapper.coercionConfigFor(LogicalType.Textual)
                            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
                    mapper.coercionConfigFor(LogicalType.Integer)
                            .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
                    mapper.coercionConfigFor(LogicalType.Float)
                            .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
                });
    }
}
