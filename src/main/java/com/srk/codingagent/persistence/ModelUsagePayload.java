package com.srk.codingagent.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The body of a {@code MODEL_USAGE} event: token accounting for a model turn
 * (AC-13.2; {@code 06-formal/event.schema.json}, {@code $defs.modelUsage}). The
 * schema requires {@code inputTokens} and {@code outputTokens} (both {@code >= 0})
 * and allows optional {@code cacheReadInputTokens} / {@code cacheWriteInputTokens}.
 *
 * <p>The two optional cache fields are modelled as nullable {@link Integer}s and
 * omitted from the JSON when {@code null}, matching the schema's optional-field
 * semantics (the contract fixture's usage events carry no cache fields).
 *
 * @param inputTokens           the input token count; {@code >= 0}.
 * @param outputTokens          the output token count; {@code >= 0}.
 * @param cacheReadInputTokens  cache-read input tokens, or {@code null} if not
 *                              reported; when present, {@code >= 0}.
 * @param cacheWriteInputTokens cache-write input tokens, or {@code null} if not
 *                              reported; when present, {@code >= 0}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelUsagePayload(
        int inputTokens,
        int outputTokens,
        Integer cacheReadInputTokens,
        Integer cacheWriteInputTokens) implements EventPayload {

    /**
     * Validates the payload.
     *
     * @throws IllegalArgumentException if any present token count is negative.
     */
    public ModelUsagePayload {
        Payloads.requireAtLeast(inputTokens, 0, "inputTokens");
        Payloads.requireAtLeast(outputTokens, 0, "outputTokens");
        if (cacheReadInputTokens != null) {
            Payloads.requireAtLeast(cacheReadInputTokens, 0, "cacheReadInputTokens");
        }
        if (cacheWriteInputTokens != null) {
            Payloads.requireAtLeast(cacheWriteInputTokens, 0, "cacheWriteInputTokens");
        }
    }

    /**
     * Creates a usage payload reporting only input and output tokens (no cache
     * fields), the common case the contract fixture exercises.
     *
     * @param inputTokens  the input token count; {@code >= 0}.
     * @param outputTokens the output token count; {@code >= 0}.
     * @return a usage payload with {@code null} cache fields.
     */
    public static ModelUsagePayload of(int inputTokens, int outputTokens) {
        return new ModelUsagePayload(inputTokens, outputTokens, null, null);
    }

    @Override
    public EventType type() {
        return EventType.MODEL_USAGE;
    }
}
