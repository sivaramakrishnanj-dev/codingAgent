package com.srk.codingagent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.tool.CommandResult;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VerifyOutcome} — the verify loop's terminal-result value. Oracles
 * trace to the verify-behaviour spec, never to the impl:
 * <ul>
 *   <li><b>RD-10 / INV-17 / AC-20.4:</b> the unit of work verified iff a zero exit from the
 *       configured test command; {@link VerifyOutcome#verified()} is the success predicate
 *       and is {@code true} only for {@link VerifyOutcome.Kind#VERIFIED}.</li>
 *   <li><b>AC-20.5:</b> an exhausted outcome surfaces the relevant output — the last failing
 *       result is carried, not dropped.</li>
 *   <li><b>AC-20.6:</b> a no-test-command outcome is a distinct config state, with no
 *       command having run (iterations 0, no result).</li>
 * </ul>
 */
class VerifyOutcomeTest {

    private static CommandResult result(int exitCode) {
        return CommandResult.completed("mvn test", exitCode, "out", "err", 12L);
    }

    @Test
    @DisplayName("RD-10/AC-20.4: a verified outcome is the success predicate and carries the passing run")
    void verifiedIsSuccessAndCarriesResult() {
        // Oracle: RD-10/INV-17/AC-20.4 — success iff a zero exit from the configured test
        // command. A VERIFIED outcome is the success signal (verified() true), carrying the
        // attempt count and the passing CommandResult.
        CommandResult passing = result(0);
        VerifyOutcome outcome = VerifyOutcome.verified(2, passing);

        assertEquals(VerifyOutcome.Kind.VERIFIED, outcome.kind());
        assertTrue(outcome.verified(), "RD-10/AC-20.4: VERIFIED is the unit-of-work success signal");
        assertEquals(2, outcome.iterations(), "the attempt count on which the command passed");
        assertSame(passing, outcome.result(), "the passing run is carried");
        assertEquals(Optional.of(passing), outcome.resultIfPresent());
    }

    @Test
    @DisplayName("AC-20.5: an exhausted outcome is not success and carries the last failing run")
    void exhaustedIsNotSuccessAndCarriesLastFailure() {
        // Oracle: AC-20.5/AC-3.4 — verification did not succeed within the bound; the loop
        // surfaces the failure "with the relevant output". An EXHAUSTED outcome is NOT a
        // success and carries the last failing result so its output can be surfaced.
        CommandResult lastFailure = result(1);
        VerifyOutcome outcome = VerifyOutcome.exhausted(5, lastFailure);

        assertEquals(VerifyOutcome.Kind.EXHAUSTED, outcome.kind());
        assertFalse(outcome.verified(), "AC-20.4: a non-verified outcome is not the success signal");
        assertEquals(5, outcome.iterations(), "the number of failing attempts (equals the bound)");
        assertSame(lastFailure, outcome.result(),
                "AC-20.5: the last failing run is carried so the relevant output is surfaced");
    }

    @Test
    @DisplayName("AC-20.6: a no-test-command outcome is a distinct state with no command run")
    void noTestCommandIsDistinctStateWithNoRun() {
        // Oracle: AC-20.6 — when no test command is configured there is nothing to verify;
        // the outcome is a distinct config state, NOT a success and NOT a failure, with no
        // command having run (iterations 0, no result).
        VerifyOutcome outcome = VerifyOutcome.noTestCommand();

        assertEquals(VerifyOutcome.Kind.NO_TEST_COMMAND, outcome.kind());
        assertFalse(outcome.verified(),
                "AC-20.6/AC-20.4: no test command is not the zero-exit success signal");
        assertEquals(0, outcome.iterations(), "no command ran, so the attempt count is 0");
        assertEquals(Optional.empty(), outcome.resultIfPresent(), "no command ran, so no result");
    }

    @Test
    @DisplayName("VerifyOutcome rejects a null kind")
    void rejectsNullKind() {
        assertThrows(NullPointerException.class,
                () -> new VerifyOutcome(null, 1, result(0)));
    }

    @Test
    @DisplayName("a ran-command outcome must carry a positive iteration count and a non-null result")
    void ranCommandOutcomeRequiresIterationAndResult() {
        // Oracle: the invariant that a command-ran outcome (VERIFIED/EXHAUSTED) reflects at
        // least one attempt with its result — guards against an outcome that claims success
        // or exhaustion without the run that produced it (AC-20.5 "with the relevant output").
        assertThrows(IllegalArgumentException.class,
                () -> VerifyOutcome.verified(0, result(0)),
                "a verified outcome with zero attempts is inconsistent");
        assertThrows(NullPointerException.class,
                () -> VerifyOutcome.verified(1, null),
                "a verified outcome must carry the passing run");
        assertThrows(IllegalArgumentException.class,
                () -> VerifyOutcome.exhausted(0, result(1)),
                "an exhausted outcome with zero attempts is inconsistent");
        assertThrows(NullPointerException.class,
                () -> VerifyOutcome.exhausted(1, null),
                "an exhausted outcome must carry the last failing run (AC-20.5)");
    }

    @Test
    @DisplayName("a no-test-command outcome must carry iterations 0 and a null result")
    void noTestCommandShapeIsEnforced() {
        // Oracle: AC-20.6 — the no-command state means nothing ran; the type forbids
        // constructing it with a phantom iteration count or result.
        assertThrows(IllegalArgumentException.class,
                () -> new VerifyOutcome(VerifyOutcome.Kind.NO_TEST_COMMAND, 1, null),
                "NO_TEST_COMMAND cannot claim an attempt was made");
        assertThrows(IllegalArgumentException.class,
                () -> new VerifyOutcome(VerifyOutcome.Kind.NO_TEST_COMMAND, 0, result(0)),
                "NO_TEST_COMMAND cannot carry a command result");
    }

    @Test
    @DisplayName("VerifyOutcome rejects a negative iteration count")
    void rejectsNegativeIterations() {
        assertThrows(IllegalArgumentException.class,
                () -> new VerifyOutcome(VerifyOutcome.Kind.VERIFIED, -1, result(0)));
    }
}
