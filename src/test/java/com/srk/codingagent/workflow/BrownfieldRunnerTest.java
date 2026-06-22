package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.loop.VerifyOutcome;
import com.srk.codingagent.persistence.StopReason;
import com.srk.codingagent.tool.CommandResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BrownfieldRunner} — the adapter that maps a {@link BrownfieldOutcome} to
 * the {@link LoopOutcome} the run path's exit-code mapper consumes, so the brownfield driver can be
 * wired into {@code codingagent -p} / the REPL without changing the runners' exit-code logic.
 *
 * <p><b>SUT and collaborators.</b> The SUT is a real {@link BrownfieldRunner} over a real
 * {@link BrownfieldDriver} whose seams are scripted (a scripted loop + a scripted verifier), so the
 * end-to-end map (driver outcome &rarr; LoopOutcome) is exercised with the real driver, only the
 * model/build boundary substituted. The driver is the real collaborator, not a mock.
 *
 * <p><b>Oracles trace to the exit-code contract's verification-vs-process-exit separation (G4) and
 * the brownfield dispositions:</b>
 * <ul>
 *   <li><b>US-6 / exit-code 0:</b> a VERIFIED change maps to a completed outcome (the run path
 *       exits 0).</li>
 *   <li><b>cli-exit-codes G4 / AC-20.5:</b> a VERIFY_EXHAUSTED change is a verification result, not
 *       an agent-process failure code; it maps to a <em>completed</em> outcome whose text surfaces
 *       the failure output (so the run path exits 0 but the developer sees the change did not pass
 *       tests) rather than masking it as an internal error.</li>
 *   <li><b>state machine A S6/S7:</b> a surfaced change-turn passes the surfaced LoopOutcome through
 *       unchanged, so the run path's existing surfaced-reason mapping (e.g. context exhausted &rarr;
 *       exit 5) still applies.</li>
 *   <li><b>AC-20.6:</b> a NO_TEST_COMMAND change maps to the completed change-turn outcome (exit 0).</li>
 * </ul>
 */
class BrownfieldRunnerTest {

    private static final String REQUEST = "fix the failing test";

    /** A driver over a one-shot scripted loop + a fixed verify outcome (the external boundary). */
    private static BrownfieldDriver driverWith(LoopOutcome changeTurn, VerifyOutcome verify) {
        BrownfieldDriver.LoopTurn loop = prompt -> changeTurn;
        BrownfieldDriver.VerifierFactory factory = remedy -> () -> verify;
        return new BrownfieldDriver(loop, factory);
    }

    @Test
    @DisplayName("US-6: a VERIFIED change maps to a completed LoopOutcome (the run path exits 0)")
    void verifiedMapsToCompleted() {
        // Oracle: US-6 / exit-code 0 — a verified change is the clean success. The runner returns
        // the completed loop outcome unchanged so the run path's mapOutcome exits 0 and prints the
        // change-turn's answer.
        LoopOutcome change = LoopOutcome.completed("Added the null check.");
        VerifyOutcome verified = VerifyOutcome.verified(1, CommandResult.completed("mvn test", 0, "ok", "", 5L));
        BrownfieldRunner runner = new BrownfieldRunner(driverWith(change, verified));

        LoopOutcome mapped = runner.run(REQUEST);

        assertTrue(mapped.completed(), "US-6: a verified change is a completed (exit-0) outcome");
        assertEquals("Added the null check.", mapped.finalTextIfPresent().orElse(""),
                "the change-turn's answer is carried through");
    }

    @Test
    @DisplayName("cli-exit-codes G4/AC-20.5: a VERIFY_EXHAUSTED change completes but surfaces the failure output")
    void verifyExhaustedMapsToCompletedWithSurfacedOutput() {
        // Oracle: cli-exit-codes G4 — the agent-process exit code is DISTINCT from the build/test
        // verification signal; a change that does not pass tests is not an agent-process failure
        // code. AC-20.5 — the failure must be surfaced WITH the relevant output. So a
        // VERIFY_EXHAUSTED change maps to a COMPLETED outcome (the run path exits 0, the agent did
        // its job) whose final text carries the change answer AND the verification failure output,
        // rather than being masked as an internal-error exit.
        LoopOutcome change = LoopOutcome.completed("Edited OrderService.");
        VerifyOutcome exhausted = VerifyOutcome.exhausted(
                5, CommandResult.completed("mvn test", 1, "Tests run: 9", "FAIL: validateOrderTest", 5L));
        BrownfieldRunner runner = new BrownfieldRunner(driverWith(change, exhausted));

        LoopOutcome mapped = runner.run(REQUEST);

        assertTrue(mapped.completed(),
                "cli-exit-codes G4: the verification signal is not the agent-process exit code; "
                        + "the agent completed its work so the outcome is completed (exit 0)");
        String text = mapped.finalTextIfPresent().orElse("");
        assertTrue(text.contains("Edited OrderService."),
                "the change-turn answer is preserved");
        assertTrue(text.contains("FAIL: validateOrderTest"),
                "AC-20.5: the verification failure's relevant output is surfaced to the developer");
        assertTrue(text.contains("5"),
                "AC-20.5: the number of attempts is surfaced so the developer sees it was retried");
    }

    @Test
    @DisplayName("state machine A: a surfaced change-turn passes the surfaced LoopOutcome through unchanged")
    void surfacedTurnPassesThrough() {
        // Oracle: state machine A S6/S7 — a surfaced edge condition (e.g. context window exceeded)
        // has no completed change; the runner passes the surfaced LoopOutcome through unchanged so
        // the run path's existing surfaced-reason mapping (context exhausted -> exit 5) still
        // applies, rather than being folded into a spurious completed outcome.
        LoopOutcome surfaced = LoopOutcome.surfaced(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED);
        BrownfieldDriver.LoopTurn loop = prompt -> surfaced;
        BrownfieldDriver.VerifierFactory neverCalled = remedy -> () -> {
            throw new AssertionError("verifier must not run on a surfaced turn");
        };
        BrownfieldRunner runner = new BrownfieldRunner(new BrownfieldDriver(loop, neverCalled));

        LoopOutcome mapped = runner.run(REQUEST);

        assertSame(surfaced, mapped,
                "the surfaced LoopOutcome is passed through unchanged for the run path to map");
        assertEquals(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED, mapped.stopReason(),
                "the surfaced reason is preserved (so the run path can map it to exit 5)");
    }

    @Test
    @DisplayName("AC-20.6: a NO_TEST_COMMAND change maps to the completed change-turn (exit 0)")
    void noTestCommandMapsToCompleted() {
        // Oracle: AC-20.6 — with no test command the change-turn completed and nothing was verified;
        // the agent did its work, so the run path exits 0 with the change answer. NO_TEST_COMMAND is
        // not a failure code.
        LoopOutcome change = LoopOutcome.completed("Made the change; no test command configured.");
        BrownfieldRunner runner = new BrownfieldRunner(driverWith(change, VerifyOutcome.noTestCommand()));

        LoopOutcome mapped = runner.run(REQUEST);

        assertTrue(mapped.completed(), "AC-20.6: the change-turn completed; exit 0");
        assertSame(change, mapped, "the completed change-turn outcome is returned unchanged");
    }

    @Test
    @DisplayName("the runner requires its driver")
    void constructorRejectsNull() {
        assertThrows(NullPointerException.class, () -> new BrownfieldRunner(null));
    }
}
