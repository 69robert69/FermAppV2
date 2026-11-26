package com.fermapp.api.config;

import jakarta.validation.valueextraction.ExtractedValue;
import jakarta.validation.valueextraction.UnwrapByDefault;
import jakarta.validation.valueextraction.ValueExtractor;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * Jakarta Validation extractor for {@code JsonNullable<T>} (the library ships one only for
 * javax.validation). A present value is validated; an absent field is skipped.
 */
@UnwrapByDefault
public class JsonNullableExtractor implements ValueExtractor<JsonNullable<@ExtractedValue ?>> {

    @Override
    public void extractValues(JsonNullable<?> originalValue, ValueReceiver receiver) {
        if (originalValue != null && originalValue.isPresent()) {
            receiver.value(null, originalValue.get());
        }
    }
}
