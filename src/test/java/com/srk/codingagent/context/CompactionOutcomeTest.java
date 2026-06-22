package com.srk.codingagent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CompactionOutcome}. Oracles: state-machine B (LT3 success → derived,
 * LT4 failure → exit 5) and cli-exit-codes 5 (context-exhausted).
 */
class CompactionOutcomeTest {

    @Test
    @DisplayName("a derived outcome succeeds and carries the derived session id (LT3 → L2)")
    void derivedOutcome() {
        // Oracle: state-machine B LT3 → L2 — a successful compaction produces a derived session.
        CompactionOutcome outcome = CompactionOutcome.derived("sess-derived");

        assertTrue(outcome.succeeded(), "a derived outcome succeeded");
        assertEquals("sess-derived", outcome.derivedSessionIdIfPresent().orElseThrow(),
                "a derived outcome carries the derived session id");
        assertTrue(outcome.failureExitCodeIfPresent().isEmpty(),
                "a successful compaction carries no failure exit code");
    }

    @Test
    @DisplayName("a failed outcome carries the context-exhausted exit code 5 (LT4 → exit 5)")
    void failedOutcome() {
        // Oracle: state-machine B LT4 / cli-exit-codes 5 — an unrecoverable compaction maps to
        // exit 5 (context-exhausted).
        CompactionOutcome outcome = CompactionOutcome.failed();

        assertFalse(outcome.succeeded(), "a failed outcome did not succeed");
        assertEquals(5, outcome.failureExitCodeIfPresent().orElse(-1),
                "cli-exit-codes 5: a failed compaction maps to the context-exhausted code");
        assertEquals(5, CompactionOutcome.CONTEXT_EXHAUSTED_EXIT_CODE,
                "the context-exhausted exit code constant is 5");
        assertTrue(outcome.derivedSessionIdIfPresent().isEmpty(),
                "a failed compaction has no derived session");
    }

    @Test
    @DisplayName("the outcome rejects a field set inconsistent with its kind (defensive invariant)")
    void rejectsInconsistentFields() {
        // Oracle: a DERIVED outcome must carry a derived id; a FAILED outcome must carry an exit
        // code. The canonical constructor enforces this so an inconsistent outcome cannot exist.
        assertThrows(IllegalArgumentException.class,
                () -> new CompactionOutcome(CompactionOutcome.Kind.DERIVED, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new CompactionOutcome(CompactionOutcome.Kind.FAILED, null, null));
    }
}
