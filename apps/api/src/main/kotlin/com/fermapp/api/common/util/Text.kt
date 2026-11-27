package com.fermapp.api.common.util

import java.util.Locale

/** String helpers for request normalisation (`z.string().trim()`). */
object Text {

    /** `String.strip()`: removes the characters `Character.isWhitespace` accepts, not non-breaking spaces. */
    @JvmStatic
    fun trim(value: String?): String? = value?.trim { Character.isWhitespace(it) }

    /** `value.slice(0, max)`. */
    @JvmStatic
    fun slice(value: String?, max: Int): String? = value?.take(max)

    @JvmStatic
    fun isBlank(value: String?): Boolean = value.isNullOrEmpty()

    /** `String(n).padStart(width, '0')`. */
    @JvmStatic
    fun padNumber(number: Long, width: Int): String = number.toString().padStart(width, '0')

    /** Escapes the LIKE wildcards and builds a case-insensitive "contains" pattern. */
    @JvmStatic
    fun containsPattern(search: String?): String {
        if (search.isNullOrEmpty()) {
            return ""
        }
        val escaped = search.lowercase(Locale.getDefault()).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }
}
