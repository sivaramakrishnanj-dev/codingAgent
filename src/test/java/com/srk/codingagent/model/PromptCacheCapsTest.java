package com.srk.codingagent.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.srk.codingagent.model.PromptCacheCaps.TimeToLive;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PromptCacheCaps} — the prompt-cache capability value object (ADR-0002,
 * 03-data-model.md &sect; 2.6). The SUT is the real record; nothing is mocked.
 *
 * <p><b>Oracles:</b>
 * <ul>
 *   <li><b>model-capability-profile.schema.json</b> {@code promptCache} — {@code minTokensPerCheckpoint}
 *       and {@code maxCheckpoints} have {@code minimum: 1}; {@code ttls} items are the enum
 *       {@code "5m"}/{@code "1h"}.</li>
 *   <li><b>Effective Java Item 17</b> — an immutable value object defensively copies its mutable
 *       collection input so a shared instance cannot be mutated through the caller's list.</li>
 * </ul>
 */
class PromptCacheCapsTest {

    @Test
    @DisplayName("TimeToLive wire tokens are the schema's 5m / 1h (model-capability-profile.schema.json)")
    void timeToLive_wireTokensMatchSchemaEnum() {
        // Oracle: model-capability-profile.schema.json promptCache.ttls item enum "5m"/"1h" — the
        // TimeToLive constants must expose exactly those tokens so a serialized profile validates.
        assertEquals("5m", TimeToLive.FIVE_MINUTES.wireToken(),
                "the 5-minute TTL serializes to the schema token '5m'");
        assertEquals("1h", TimeToLive.ONE_HOUR.wireToken(),
                "the 1-hour TTL serializes to the schema token '1h'");
    }

    @Test
    @DisplayName("a non-positive minTokensPerCheckpoint is rejected (schema minimum: 1)")
    void nonPositiveMinTokens_rejected() {
        // Oracle: schema promptCache.minTokensPerCheckpoint minimum 1.
        assertThrows(IllegalArgumentException.class,
                () -> new PromptCacheCaps(0, 4, List.of(TimeToLive.FIVE_MINUTES)),
                "a zero checkpoint minimum is below the schema minimum of 1");
        assertThrows(IllegalArgumentException.class,
                () -> new PromptCacheCaps(-1, 4, List.of(TimeToLive.FIVE_MINUTES)),
                "a negative checkpoint minimum is invalid");
    }

    @Test
    @DisplayName("a non-positive maxCheckpoints is rejected (schema minimum: 1)")
    void nonPositiveMaxCheckpoints_rejected() {
        // Oracle: schema promptCache.maxCheckpoints minimum 1.
        assertThrows(IllegalArgumentException.class,
                () -> new PromptCacheCaps(4096, 0, List.of(TimeToLive.ONE_HOUR)),
                "a zero max-checkpoint count is below the schema minimum of 1");
    }

    @Test
    @DisplayName("a null ttls list is rejected (the list is required, use empty for none)")
    void nullTtls_rejected() {
        // Oracle: schema promptCache.ttls is an array; the value object requires a non-null list
        // (callers pass an empty list when a model offers no TTL windows).
        assertThrows(NullPointerException.class,
                () -> new PromptCacheCaps(4096, 4, null),
                "a null ttls list is a programming error");
    }

    @Test
    @DisplayName("the ttls list is defensively copied (Effective Java Item 17 immutability)")
    void ttlsList_isDefensivelyCopied() {
        // Oracle: EJ Item 17 — a value object must not let a caller mutate its state after
        // construction. Mutating the source list after construction must not change the record's
        // ttls, and the record's accessor must return an unmodifiable view.
        List<TimeToLive> source = new ArrayList<>();
        source.add(TimeToLive.FIVE_MINUTES);
        PromptCacheCaps caps = new PromptCacheCaps(4096, 4, source);

        source.add(TimeToLive.ONE_HOUR);
        assertEquals(1, caps.ttls().size(),
                "mutating the source list must not change the record's ttls (defensive copy)");
        assertThrows(UnsupportedOperationException.class,
                () -> caps.ttls().add(TimeToLive.ONE_HOUR),
                "the accessor returns an unmodifiable list (EJ Item 17)");
    }
}
