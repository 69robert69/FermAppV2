package com.fermapp.api.modules.parcels

import com.fermapp.api.domain.geo.Geo
import com.fermapp.api.domain.geo.Geo.Polygon
import com.fermapp.api.domain.geo.Geo.Position
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * A GeoJSON polygon received from the client: type "Polygon", 1-50 rings of 3-5000 points,
 * each point a valid [longitude, latitude], and an outline that does not cross itself.
 */
@JvmRecord
@PolygonInput.ValidPolygon
data class PolygonInput(val type: String?, val coordinates: List<List<Position>>?) {

    fun toPolygon(): Polygon = Polygon.of(coordinates)

    /** Class-level constraint with the same checks as the zod polygonSchema. */
    @Target(AnnotationTarget.CLASS, AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.RUNTIME)
    @Constraint(validatedBy = [Validator::class])
    annotation class ValidPolygon(
        val message: String = "Invalid polygon",
        val groups: Array<KClass<*>> = [],
        val payload: Array<KClass<out Payload>> = [],
    )

    class Validator : ConstraintValidator<ValidPolygon, PolygonInput> {

        override fun isValid(value: PolygonInput?, context: ConstraintValidatorContext): Boolean {
            if (value == null) {
                return true
            }
            if (value.type != "Polygon") {
                return fail(context, "type", "Invalid input: expected \"Polygon\"")
            }
            val rings = value.coordinates
            if (rings == null || rings.isEmpty() || rings.size > 50) {
                return fail(context, "coordinates", "The polygon must have between 1 and 50 rings.")
            }
            for (ring in rings) {
                @Suppress("SENSELESS_COMPARISON")
                if (ring == null || ring.size < 3 || ring.size > 5000) {
                    return fail(context, "coordinates", "Each ring must have between 3 and 5000 points.")
                }
                for (point in ring) {
                    @Suppress("SENSELESS_COMPARISON")
                    if (point == null || point.lng < -180 || point.lng > 180 || point.lat < -90 || point.lat > 90) {
                        return fail(context, "coordinates", "Coordinates must be valid longitude and latitude values.")
                    }
                }
            }
            val problem = Geo.validatePolygon(value.toPolygon())
            return problem == null || fail(context, "coordinates", problem)
        }

        private companion object {
            fun fail(context: ConstraintValidatorContext, property: String, message: String): Boolean {
                context.disableDefaultConstraintViolation()
                context.buildConstraintViolationWithTemplate(message.replace("{", "\\{").replace("$", "\\$"))
                    .addPropertyNode(property).addConstraintViolation()
                return false
            }
        }
    }
}
