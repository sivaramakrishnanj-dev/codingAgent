package com.srk.codingagent.tool.memory;

import com.srk.codingagent.tool.ToolInvocationException;
import java.util.Map;

/**
 * Extracts and validates typed values from a memory tool's {@code toolUse.input} map,
 * surfacing a missing or wrong-typed field as a {@link ToolInvocationException} (turned into
 * an {@code error} tool result by the {@code ToolRegistry}) rather than a raw
 * {@code ClassCastException} that would crash the loop.
 *
 * <p>Mirrors the built-in tools' {@code ToolInputs} idiom; authored here because the memory
 * tools live in their own package and {@code ToolInputs} is package-private to the sibling
 * {@code com.srk.codingagent.tool} package. Package-private; not part of the memory-tool
 * public API.
 */
final class MemoryToolInputs {

    private MemoryToolInputs() {
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
}
