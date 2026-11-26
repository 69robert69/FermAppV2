package com.fermapp.api.modules.parcels;

import com.fermapp.api.domain.geo.Geo;
import com.fermapp.api.domain.geo.Geo.Polygon;
import com.fermapp.api.domain.geo.Geo.Position;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

/**
 * A GeoJSON polygon received from the client: type "Polygon", 1-50 rings of 3-5000 points,
 * each point a valid [longitude, latitude], and an outline that does not cross itself.
 */
@PolygonInput.ValidPolygon
public record PolygonInput(String type, List<List<Position>> coordinates) {

    public Polygon toPolygon() {
        return Polygon.of(coordinates);
    }

    /** Class-level constraint with the same checks as the zod polygonSchema. */
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = Validator.class)
    public @interface ValidPolygon {
        String message() default "Invalid polygon";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static class Validator implements ConstraintValidator<ValidPolygon, PolygonInput> {

        private static boolean fail(ConstraintValidatorContext context, String property, String message) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(message.replace("{", "\\{").replace("$", "\\$"))
                    .addPropertyNode(property).addConstraintViolation();
            return false;
        }

        @Override
        public boolean isValid(PolygonInput value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            if (!"Polygon".equals(value.type())) {
                return fail(context, "type", "Invalid input: expected \"Polygon\"");
            }
            List<List<Position>> rings = value.coordinates();
            if (rings == null || rings.isEmpty() || rings.size() > 50) {
                return fail(context, "coordinates", "The polygon must have between 1 and 50 rings.");
            }
            for (List<Position> ring : rings) {
                if (ring == null || ring.size() < 3 || ring.size() > 5000) {
                    return fail(context, "coordinates", "Each ring must have between 3 and 5000 points.");
                }
                for (Position point : ring) {
                    if (point == null || point.lng() < -180 || point.lng() > 180 || point.lat() < -90 || point.lat() > 90) {
                        return fail(context, "coordinates", "Coordinates must be valid longitude and latitude values.");
                    }
                }
            }
            String problem = Geo.validatePolygon(value.toPolygon());
            return problem == null || fail(context, "coordinates", problem);
        }
    }
}
