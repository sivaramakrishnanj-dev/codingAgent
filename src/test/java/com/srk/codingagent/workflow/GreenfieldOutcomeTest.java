package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.persistence.StopReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GreenfieldOutcome} — the greenfield driver's terminal result value
 * (disposition + the phase the session finished in + the last phase turn's loop outcome). The SUT
 * is the record; its collaborator {@link LoopOutcome} is a real value object, cheap to construct.
 *
 * <p><b>Oracles trace to the greenfield disposition contract (ADR-0012 / the cited ACs), never to
 * the record's code:</b>
 * <ul>
 *   <li><b>ADR-0012 / AC-2.3:</b> {@code COMPLETED} means every approval gate passed and the
 *       implementation phase ran — so the finished phase is {@link GreenfieldPhase#IMPLEMENT} and
 *       {@link GreenfieldOutcome#completed()} is the success predicate.</li>
 *   <li><b>ADR-0012 / AC-2.3 / AC-1.4:</b> {@code AWAITING_APPROVAL} means a phase completed but the
 *       developer did not approve advancing; the session stops at that phase's gate without writing
 *       source — so it is NOT a completed run.</li>
 *   <li><b>state machine A S6/S7:</b> {@code TURN_SURFACED} carries the surfaced loop outcome's edge
 *       reason for the run path to map.</li>
 * </ul>
 */
class GreenfieldOutcomeTest {

    private static final LoopOutcome COMPLETED = LoopOutcome.completed("done");
    private static final LoopOutcome SURFACED =
            LoopOutcome.surfaced(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED);

    @Test
    @DisplayName("ADR-0012/AC-2.3: a completed outcome is the success predicate and finishes at the implementation phase")
    void completedIsSuccessAtImplementPhase() {
        // Oracle: ADR-0012 / AC-2.3 — a COMPLETED greenfield run is one where every approval gate
        // passed and the implementation phase ran. completed() is the success predicate; the phase
        // is IMPLEMENT (the terminal phase). The factory pins the phase to IMPLEMENT so a completed
        // run cannot claim to have finished anywhere else.
        GreenfieldOutcome outcome = GreenfieldOutcome.completed(COMPLETED);

        assertTrue(outcome.completed(), "ADR-0012: COMPLETED is the clean greenfield success predicate");
        assertEquals(GreenfieldOutcome.Disposition.COMPLETED, outcome.disposition());
        assertEquals(GreenfieldPhase.IMPLEMENT, outcome.phase(),
                "AC-2.3: a completed run finished in the implementation phase (reached after approval)");
        assertSame(COMPLETED, outcome.loopOutcome(), "the implementation phase's loop outcome is carried");
    }

    @Test
    @DisplayName("ADR-0012/AC-2.3/AC-1.4: an awaiting-approval outcome is NOT a success and names the gated phase")
    void awaitingApprovalIsNotSuccessAndNamesPhase() {
        // Oracle: ADR-0012 "the agent does not advance a phase without explicit developer approval" +
        // AC-2.3 (approval guards implementation) + AC-1.4 (no source write while unapproved). An
        // AWAITING_APPROVAL outcome is not a completed run; it names the phase that completed and is
        // awaiting approval (here, design), so the developer sees where the session paused.
        GreenfieldOutcome outcome =
                GreenfieldOutcome.awaitingApproval(GreenfieldPhase.DESIGN, COMPLETED);

        assertFalse(outcome.completed(),
                "AC-2.3/AC-1.4: a session awaiting approval has NOT completed (no source written)");
        assertEquals(GreenfieldOutcome.Disposition.AWAITING_APPROVAL, outcome.disposition());
        assertEquals(GreenfieldPhase.DESIGN, outcome.phase(),
                "the awaiting-approval outcome names the phase that is awaiting approval");
        assertSame(COMPLETED, outcome.loopOutcome(),
                "the completed phase's loop outcome is carried (its deliverable can be surfaced)");
    }

    @Test
    @DisplayName("state machine A S6/S7: a turn-surfaced outcome carries the surfaced edge reason and the surfaced phase")
    void turnSurfacedCarriesEdgeReasonAndPhase() {
        // Oracle: state machine A S6/S7 — a phase turn that surfaces an edge condition (e.g. context
        // window exceeded) before an approval gate is reached yields TURN_SURFACED. It is not a
        // completed run; it names the phase whose turn surfaced and carries the surfaced loop outcome
        // (whose stopReason the run path maps to an exit code).
        GreenfieldOutcome outcome =
                GreenfieldOutcome.turnSurfaced(GreenfieldPhase.REQUIREMENTS, SURFACED);

        assertFalse(outcome.completed(), "a surfaced turn is not a completed run");
        assertEquals(GreenfieldOutcome.Disposition.TURN_SURFACED, outcome.disposition());
        assertEquals(GreenfieldPhase.REQUIREMENTS, outcome.phase(),
                "TURN_SURFACED names the phase whose turn surfaced");
        assertEquals(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED, outcome.loopOutcome().stopReason(),
                "the surfaced edge reason is preserved for the run path to map");
    }

    @Test
    @DisplayName("the record requires a disposition, a phase, and a loop outcome (loopOutcome always present)")
    void rejectsNullComponents() {
        // Oracle: the GreenfieldOutcome contract — every component is required; loopOutcome() is
        // always present (every disposition is reached after at least one phase turn produced a
        // LoopOutcome). A null component is a programming error the record rejects (EJ Item 17).
        assertThrows(NullPointerException.class,
                () -> new GreenfieldOutcome(null, GreenfieldPhase.IMPLEMENT, COMPLETED));
        assertThrows(NullPointerException.class,
                () -> new GreenfieldOutcome(
                        GreenfieldOutcome.Disposition.COMPLETED, null, COMPLETED));
        assertThrows(NullPointerException.class,
                () -> new GreenfieldOutcome(
                        GreenfieldOutcome.Disposition.COMPLETED, GreenfieldPhase.IMPLEMENT, null));
        assertThrows(NullPointerException.class,
                () -> GreenfieldOutcome.awaitingApproval(null, COMPLETED));
        assertThrows(NullPointerException.class,
                () -> GreenfieldOutcome.turnSurfaced(GreenfieldPhase.DESIGN, null));
    }
}
