package com.srk.codingagent.model;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;
import java.util.Objects;

/**
 * The prompt-caching capabilities of a model (ADR-0002, 03-data-model.md &sect; 2.6): the
 * checkpoint token minimum, the maximum number of cache checkpoints, and the supported
 * cache time-to-live windows. A {@code null} {@code PromptCacheCaps} on a
 * {@link ModelCapabilityProfile} means the model does <b>not</b> support prompt caching
 * (&sect; 2.6 "null = unsupported"); the loop then simply does not insert cache checkpoints
 * (graceful degradation).
 *
 * <p><b>Why these three figures (ADR-0002 Context).</b> The loop must not hard-assume cache
 * behaviour across families: "prompt-caching token minimums + checkpoint counts (Opus 4.5/4.6
 * need &ge; 4096 tokens/checkpoint, others 1024)". A cache checkpoint is only worth inserting
 * once the cached prefix reaches {@link #minTokensPerCheckpoint()}, at most
 * {@link #maxCheckpoints()} checkpoints may be set, and {@link #ttls()} is the set of TTL
 * windows the model offers.
 *
 * <p>Immutable value object (Effective Java Item 17); the {@code ttls} list is defensively
 * copied to an unmodifiable list so a shared instance cannot be mutated through it.
 *
 * @param minTokensPerCheckpoint the minimum cached-prefix token count a checkpoint is worth
 *                               setting at ({@code >= 1}); the model-specific figure ADR-0002
 *                               names (Opus 4.5/4.6: 4096; other Claude: 1024).
 * @param maxCheckpoints         the maximum number of cache checkpoints the model permits
 *                               ({@code >= 1}).
 * @param ttls                   the cache TTL windows the model offers; never {@code null}
 *                               (use an empty list when none), defensively copied.
 */
public record PromptCacheCaps(int minTokensPerCheckpoint, int maxCheckpoints, List<TimeToLive> ttls) {

    /**
     * A prompt-cache TTL window (ADR-0002; the model-capability-profile schema's
     * {@code promptCache.ttls} enum {@code "5m" | "1h"}). {@link #wireToken()} is the exact
     * string the schema and the Converse {@code cachePoint} wire form use, so a serialized
     * profile validates against the schema's {@code ttls} item enum (CT-SCH-15).
     */
    public enum TimeToLive {

        /** The five-minute cache window (schema token {@code "5m"}). */
        FIVE_MINUTES("5m"),

        /** The one-hour cache window (schema token {@code "1h"}). */
        ONE_HOUR("1h");

        private final String wireToken;

        TimeToLive(String wireToken) {
            this.wireToken = wireToken;
        }

        /**
         * The exact schema/wire token for this TTL ({@code "5m"} or {@code "1h"}). Marked
         * {@link JsonValue} so a serialized profile's {@code promptCache.ttls} entries are the
         * schema's {@code "5m"}/{@code "1h"} tokens (not the enum constant names), satisfying the
         * schema's {@code ttls} item enum (CT-SCH-15).
         *
         * @return the schema {@code promptCache.ttls} enum token; never {@code null}.
         */
        @JsonValue
        public String wireToken() {
            return wireToken;
        }
    }

    /**
     * Validates the schema-pinned invariants and defensively copies the TTL list.
     *
     * @throws NullPointerException     if {@code ttls} is {@code null}.
     * @throws IllegalArgumentException if {@code minTokensPerCheckpoint < 1} or
     *                                  {@code maxCheckpoints < 1} (schema {@code minimum: 1}).
     */
    public PromptCacheCaps {
        if (minTokensPerCheckpoint < 1) {
            throw new IllegalArgumentException(
                    "minTokensPerCheckpoint must be >= 1 (was " + minTokensPerCheckpoint + ")");
        }
        if (maxCheckpoints < 1) {
            throw new IllegalArgumentException(
                    "maxCheckpoints must be >= 1 (was " + maxCheckpoints + ")");
        }
        Objects.requireNonNull(ttls, "ttls");
        ttls = List.copyOf(ttls);
    }
}
