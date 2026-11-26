package com.fermapp.api.config;

import org.springframework.boot.autoconfigure.validation.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lets Jakarta Validation look inside {@code JsonNullable<T>}: constraints on a PATCH field
 * apply to its value when the field is present and are skipped when it is absent.
 */
@Configuration
public class ValidationConfig {

    @Bean
    ValidationConfigurationCustomizer jsonNullableExtractor() {
        return configuration -> configuration.addValueExtractor(new JsonNullableExtractor());
    }
}
