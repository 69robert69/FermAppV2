package com.fermapp.api.common.util;

/** String helpers for request normalisation ({@code z.string().trim()}). */
public final class Text {

    private Text() {
    }

    public static String trim(String value) {
        return value == null ? null : value.strip();
    }

    /** {@code value.slice(0, max)}. */
    public static String slice(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    public static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }

    /** {@code String(n).padStart(width, '0')}. */
    public static String padNumber(long number, int width) {
        String text = Long.toString(number);
        return text.length() >= width ? text : "0".repeat(width - text.length()) + text;
    }

    /** Escapes the LIKE wildcards and builds a case-insensitive "contains" pattern. */
    public static String containsPattern(String search) {
        if (search == null || search.isEmpty()) {
            return "";
        }
        String escaped = search.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
