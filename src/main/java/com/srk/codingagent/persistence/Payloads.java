package com.srk.codingagent.persistence;

import java.util.Objects;

/**
 * Shared validation helpers for {@link EventPayload} records, so the schema-pinned
 * field constraints (non-blank ids/names, non-negative token counts) are expressed
 * once rather than copied into each payload's canonical constructor.
 *
 * <p>Package-private utility class; not part of the persistence public API.
 */
final class Payloads {

    private Payloads() {
        // Non-instantiable.
    }

    /**
     * Requires a string field to be non-{@code null} and non-blank, mirroring the
     * schema's {@code minLength 1} / non-empty constraints.
     *
     * @param value the value to check.
     * @param field the field name, used in the diagnostic message.
     * @throws NullPointerException     if {@code value} is {@code null}.
     * @throws IllegalArgumentException if {@code value} is blank.
     */
    static void requireNonBlank(String value, String field) {
        if (Objects.requireNonNull(value, field).isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
    }

    /**
     * Requires an integer field to be at least {@code min}, mirroring the schema's
     * {@code minimum} constraints (token counts, iteration counts are {@code >= 0}).
     *
     * @param value the value to check.
     * @param min   the inclusive minimum.
     * @param field the field name, used in the diagnostic message.
     * @throws IllegalArgumentException if {@code value < min}.
     */
    static void requireAtLeast(int value, int min, String field) {
        if (value < min) {
            throw new IllegalArgumentException(field + " must be >= " + min + " (was " + value + ")");
        }
    }
}
