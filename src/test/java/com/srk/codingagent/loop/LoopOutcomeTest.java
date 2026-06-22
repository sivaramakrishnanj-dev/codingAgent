package com.srk.codingagent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.persistence.ModelUsagePayload;
import com.srk.codingagent.persistence.StopReason;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LoopOutcome} and {@link BudgetGuard} — the loop's terminal-result
 * value and its compaction seam. Oracles trace to state machine A (S5 end_turn completion;
 * S6/S7 surfaced edge conditions; T13 budget seam) and 02-architecture.md § 3.1.
 */
class LoopOutcomeTest {

    @Test
    @DisplayName("S5: a completed outcome carries END_TURN and the final text (state machine A T3)")
    void completedCarriesEndTurnAndText() {
        // Oracle: state machine A T3 -> S5 — end_turn renders final text. A completed outcome
        // is END_TURN with the supplied text present.
        LoopOutcome outcome = LoopOutcome.completed("the answer");

        assertEquals(LoopOutcome.Kind.COMPLETED, outcome.kind());
        assertEquals(StopReason.END_TURN, outcome.stopReason(),
                "a completed outcome's terminal reason is end_turn (T3)");
        assertTrue(outcome.completed());
        assertEquals(Optional.of("the answer"), outcome.finalTextIfPresent());
    }

    @Test
    @DisplayName("S6/S7: a surfaced outcome carries the edge reason and no final text")
    void surfacedCarriesReasonAndNoText() {
        // Oracle: state machine A T4/T5 -> S6/S7 — an edge reason is surfaced for the caller
        // to decide (compact/retry/exit); there is no final answer.
        LoopOutcome outcome = LoopOutcome.surfaced(StopReason.MAX_TOKENS);

        assertEquals(LoopOutcome.Kind.SURFACED, outcome.kind());
        assertEquals(StopReason.MAX_TOKENS, outcome.stopReason(),
                "a surfaced outcome carries the edge stop reason verbatim");
        assertFalse(outcome.completed(), "a surfaced outcome is not a completion");
        assertEquals(Optional.empty(), outcome.finalTextIfPresent(),
                "a surfaced outcome carries no final text");
    }

    @Test
    @DisplayName("LoopOutcome rejects null kind/stopReason and a null completed text")
    void rejectsNulls() {
        assertThrows(NullPointerException.class, () -> new LoopOutcome(null, StopReason.END_TURN, "x"));
        assertThrows(NullPointerException.class,
                () -> new LoopOutcome(LoopOutcome.Kind.COMPLETED, null, "x"));
        assertThrows(NullPointerException.class, () -> LoopOutcome.completed(null));
        assertThrows(NullPointerException.class, () -> LoopOutcome.surfaced(null));
    }

    @Test
    @DisplayName("BudgetGuard.NONE never requests compaction (the T-0.8 no-compaction wiring)")
    void budgetGuardNoneAlwaysContinues() {
        // Oracle: T-0.8 scope — compaction (S6) is a later task; the production wiring is a
        // no-op guard that always continues. NONE returns CONTINUE for any usage.
        assertEquals(BudgetGuard.Decision.CONTINUE,
                BudgetGuard.NONE.evaluate(ModelUsagePayload.of(1_000_000, 5)),
                "BudgetGuard.NONE must never signal compaction (no compaction at T-0.8)");
        assertEquals(BudgetGuard.Decision.CONTINUE,
                BudgetGuard.NONE.evaluate(ModelUsagePayload.of(0, 0)));
    }
}
