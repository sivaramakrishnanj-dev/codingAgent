package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.loop.VerifyOutcome;
import com.srk.codingagent.tool.CommandResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BrownfieldOutcome} — the brownfield driver's terminal result value
 * (loop outcome + verify outcome + disposition). The SUT is the record; its collaborators
 * ({@link LoopOutcome}, {@link VerifyOutcome}) are real value objects, cheap to construct.
 *
 * <p><b>Oracles trace to the brownfield disposition contract:</b>
 * <ul>
 *   <li><b>AC-5.3 / RD-10:</b> {@code VERIFIED} means the change was made and the configured test
 *       command verified it — the success predicate.</li>
 *   <li><b>AC-20.5:</b> {@code VERIFY_EXHAUSTED} carries the failing verify outcome's relevant
 *       output for surfacing.</li>
 *   <li><b>AC-20.6 / state machine A S6/S7:</b> {@code NOT_VERIFIED} covers a surfaced turn (no
 *       verify ran) and a no-test-command outcome (nothing verified).</li>
 * </ul>
 * The record validates that an inconsistent outcome cannot exist (EJ Item 17), so the
 * shape-mismatch tests assert the invariant rejects an outcome whose verify state contradicts its
 * disposition.
 */
class BrownfieldOutcomeTest {

    private static final LoopOutcome COMPLETED = LoopOutcome.completed("done");
    private static final LoopOutcome SURFACED =
            LoopOutcome.surfaced(com.srk.codingagent.persistence.StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED);

    private static VerifyOutcome verifiedOnce() {
        return VerifyOutcome.verified(1, CommandResult.completed("mvn test", 0, "ok", "", 5L));
    }

    private static VerifyOutcome exhaustedAfter(int n, int lastExit) {
        return VerifyOutcome.exhausted(n,
                CommandResult.completed("mvn test", lastExit, "out", "boom", 5L));
    }

    @Test
    @DisplayName("AC-5.3/RD-10: a verified outcome is the success predicate and carries both outcomes")
    void verifiedIsSuccess() {
        // Oracle: AC-5.3/RD-10 — VERIFIED means the change was made and verified. verified() is the
        // run path's success predicate; both the loop outcome and the verifying verify outcome are
        // carried.
        VerifyOutcome verify = verifiedOnce();
        BrownfieldOutcome outcome = BrownfieldOutcome.verified(COMPLETED, verify);

        assertTrue(outcome.verified(), "AC-5.3: VERIFIED is the success predicate");
        assertEquals(BrownfieldOutcome.Disposition.VERIFIED, outcome.disposition());
        assertSame(COMPLETED, outcome.loopOutcome(), "the completed change-turn outcome is carried");
        assertSame(verify, outcome.verifyOutcome(), "the verifying verify outcome is carried");
        assertTrue(outcome.verifyOutcomeIfPresent().isPresent());
    }

    @Test
    @DisplayName("AC-20.5: a verify-exhausted outcome carries the failing verify outcome (output preserved)")
    void verifyExhaustedCarriesFailure() {
        // Oracle: AC-20.5 — an exhausted verification surfaces with the relevant output. The
        // disposition is VERIFY_EXHAUSTED, not a success, and the carried verify outcome is the
        // (non-verified) exhausted one whose last failure carries the output to surface.
        VerifyOutcome verify = exhaustedAfter(5, 7);
        BrownfieldOutcome outcome = BrownfieldOutcome.verifyExhausted(COMPLETED, verify);

        assertFalse(outcome.verified(), "AC-20.5: an exhausted verification is not a success");
        assertEquals(BrownfieldOutcome.Disposition.VERIFY_EXHAUSTED, outcome.disposition());
        assertSame(verify, outcome.verifyOutcome(), "AC-20.5: the exhausted verify outcome is carried");
        assertEquals(7, outcome.verifyOutcome().result().exitCode(),
                "AC-20.5: the last failure's output is preserved through the disposition");
    }

    @Test
    @DisplayName("state machine A S6/S7: a surfaced turn yields NOT_VERIFIED with no verify outcome")
    void turnSurfacedHasNoVerifyOutcome() {
        // Oracle: state machine A — when the turn surfaces an edge condition there is no completed
        // change, so the verify step does not run. The disposition is NOT_VERIFIED and there is no
        // verify outcome.
        BrownfieldOutcome outcome = BrownfieldOutcome.turnSurfaced(SURFACED);

        assertFalse(outcome.verified());
        assertEquals(BrownfieldOutcome.Disposition.NOT_VERIFIED, outcome.disposition());
        assertSame(SURFACED, outcome.loopOutcome());
        assertTrue(outcome.verifyOutcomeIfPresent().isEmpty(),
                "no verification ran when the turn surfaced before completing");
    }

    @Test
    @DisplayName("AC-20.6: a no-test-command outcome is NOT_VERIFIED but carries the verify outcome")
    void noTestCommandIsNotVerified() {
        // Oracle: AC-20.6 — when no test command is configured the change was made but nothing was
        // verified. The disposition is NOT_VERIFIED; the NO_TEST_COMMAND verify outcome is carried
        // (present, but not a success).
        VerifyOutcome verify = VerifyOutcome.noTestCommand();
        BrownfieldOutcome outcome = BrownfieldOutcome.noTestCommand(COMPLETED, verify);

        assertFalse(outcome.verified(), "AC-20.6: no test command is not a success signal");
        assertEquals(BrownfieldOutcome.Disposition.NOT_VERIFIED, outcome.disposition());
        assertSame(verify, outcome.verifyOutcome());
        assertEquals(VerifyOutcome.Kind.NO_TEST_COMMAND, outcome.verifyOutcome().kind());
    }

    @Test
    @DisplayName("the record rejects an inconsistent verify state for its disposition (EJ Item 17)")
    void rejectsInconsistentShapes() {
        // Oracle: EJ Item 17 invariant — an inconsistent BrownfieldOutcome cannot exist.
        // VERIFIED requires a verified() verify outcome; VERIFY_EXHAUSTED requires a non-verified
        // one; a verify outcome is required for both ran-verification dispositions.
        assertThrows(IllegalArgumentException.class,
                () -> BrownfieldOutcome.verified(COMPLETED, exhaustedAfter(3, 1)),
                "VERIFIED must carry a verified() verify outcome");
        assertThrows(IllegalArgumentException.class,
                () -> BrownfieldOutcome.verifyExhausted(COMPLETED, verifiedOnce()),
                "VERIFY_EXHAUSTED must not carry a verified() verify outcome");
        assertThrows(IllegalArgumentException.class,
                () -> new BrownfieldOutcome(
                        BrownfieldOutcome.Disposition.VERIFIED, COMPLETED, null),
                "a ran-verification disposition requires a verify outcome");
    }

    @Test
    @DisplayName("the record requires a disposition and a loop outcome")
    void rejectsNullRequiredFields() {
        assertThrows(NullPointerException.class,
                () -> new BrownfieldOutcome(null, COMPLETED, verifiedOnce()));
        assertThrows(NullPointerException.class,
                () -> BrownfieldOutcome.turnSurfaced(null));
        assertThrows(NullPointerException.class,
                () -> BrownfieldOutcome.noTestCommand(COMPLETED, null),
                "the no-test-command factory requires its verify outcome");
    }
}
