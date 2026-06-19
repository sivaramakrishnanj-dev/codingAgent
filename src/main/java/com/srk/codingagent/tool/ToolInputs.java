package com.srk.codingagent.tool;

import java.util.Map;

/**
 * Extracts and validates typed values from a model-supplied {@code toolUse.input} map,
 * so each {@link ToolHandler} reads its inputs once with consistent diagnostics. A
 * missing required field, a wrong-typed field, or an out-of-range optional field is
 * surfaced as a {@link ToolInvocationException} (turned into an {@code error} tool result
 * by the {@link ToolRegistry}), not a raw {@code ClassCastException} that would crash the
 * loop.
 *
 * <p>Package-private; not part of the tool public API.
 */
final class ToolInputs {

    private ToolInputs() {
        // Non-instantiable.
    }

    /**
     * Requires a non-blank string field.
     *
     * @param input the input map.
     * @param field the field name.
     * @return the field value.
     * @throws ToolInvocationException if the field is absent, not a string, or blank.
     */
    static String requireString(Map<String, Object> input, String field) {
        Object value = input.get(field);
        if (value == null) {
            throw new ToolInvocationException("missing required input '" + field + "'");
        }
        if (!(value instanceof String s)) {
            throw new ToolInvocationException(
                    "input '" + field + "' must be a string but was " + value.getClass().getSimpleName());
        }
        if (s.isBlank()) {
            throw new ToolInvocationException("input '" + field + "' must be non-blank");
        }
        return s;
    }

    /**
     * Reads an optional integer field that, when present, must be a positive integer.
     * JSON integers arrive as {@link Integer} or {@link Long} depending on the decoder
     * (DocumentConverter unwraps whole numbers to {@code Long}), so both are accepted.
     *
     * @param input the input map.
     * @param field the field name.
     * @return the positive integer value, or {@code null} when the field is absent.
     * @throws ToolInvocationException if present but not an integer or not positive.
     */
    static Integer optionalPositiveInt(Map<String, Object> input, String field) {
        Object value = input.get(field);
        if (value == null) {
            return null;
        }
        int parsed = toInt(value, field);
        if (parsed <= 0) {
            throw new ToolInvocationException(
                    "input '" + field + "' must be a positive integer but was " + parsed);
        }
        return parsed;
    }

    private static int toInt(Object value, String field) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Long l) {
            if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                throw new ToolInvocationException("input '" + field + "' is out of int range: " + l);
            }
            return l.intValue();
        }
        throw new ToolInvocationException(
                "input '" + field + "' must be an integer but was " + value.getClass().getSimpleName());
    }
}
