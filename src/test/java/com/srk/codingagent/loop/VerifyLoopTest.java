package com.srk.codingagent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.config.ResolvedConfig.Commands;
import com.srk.codingagent.tool.CommandExecutor;
import com.srk.codingagent.tool.CommandResult;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link VerifyLoop} — the bounded run&rarr;check&rarr;remedy&rarr;retry control
 * loop (US-20). The SUT is a real {@link VerifyLoop}; the only collaborators are the
 * injected {@link CommandRunner} seam (scripted with an exit-code sequence, the external
 * boundary so no real build is shelled out) and the {@link RemedyAttempt} seam.
 *
 * <p><b>Oracles trace to the verify-behaviour spec, never to the loop's code:</b>
 * <ul>
 *   <li><b>RD-10 / INV-17 / CT-INV-14 (both directions):</b> success iff a zero exit from the
 *       configured test command; any non-zero (including the timeout's 124) is failure.</li>
 *   <li><b>AC-3.4 / AC-20.5 / CT-SM-5:</b> after exactly {@code verifyMaxIterations} non-zero
 *       attempts the loop stops and surfaces the last failure with its output, and does NOT
 *       run an (N+1)-th attempt.</li>
 *   <li><b>NFR-VERIFY-MAX-ITERATIONS:</b> the bound is the config value (default 5), exercised
 *       at several values to prove it is not a hardcoded literal.</li>
 *   <li><b>AC-20.3:</b> a failing attempt feeds its output back to the remedy seam before the
 *       next attempt — and the remedy is not invoked after the last attempt or after success.</li>
 *   <li><b>AC-20.6:</b> with no test command configured, nothing is run.</li>
 * </ul>
 */
class VerifyLoopTest {

    private static final String TEST_CMD = "mvn test";

    // --- A scripted command runner: returns a programmed exit-code sequence and counts runs.
    // This is the external boundary (a stand-in for the real CommandExecutor), not the SUT,
    // letting a test drive fail/pass sequences without shelling out (mirrors OneShotRunnerTest's
    // ScriptedBedrockClient).
    private static final class ScriptedRunner implements CommandRunner {
        private final Deque<CommandResult> script = new ArrayDeque<>();
        private int runs;

        ScriptedRunner then(CommandResult result) {
            script.addLast(result);
            return this;
        }

        ScriptedRunner thenExits(int exitCode) {
            return then(CommandResult.completed(TEST_CMD, exitCode, "stdout-" + exitCode,
                    "stderr-" + exitCode, 10L));
        }

        @Override
        public CommandResult run() {
            runs++;
            if (script.isEmpty()) {
                throw new IllegalStateException("scripted runner exhausted after " + runs + " run(s)");
            }
            return script.removeFirst();
        }

        int runs() {
            return runs;
        }
    }

    /** A remedy seam that records the failure outputs fed back to it (AC-20.3). */
    private static final class RecordingRemedy implements RemedyAttempt {
        private final List<CommandResult> failuresSeen = new ArrayList<>();

        @Override
        public void attempt(CommandResult failure) {
            failuresSeen.add(failure);
        }

        int invocations() {
            return failuresSeen.size();
        }
    }

    // --- RD-10 / INV-17 / CT-INV-14 : the success predicate, both directions -------------

    @Test
    @DisplayName("RD-10/CT-INV-14: a zero exit on the first attempt verifies (success direction)")
    void zeroExitFirstAttemptVerifies() {
        // Oracle: RD-10/INV-17/AC-20.4 — success iff a zero exit from the configured test
        // command. A first-attempt exit 0 yields VERIFIED at iteration 1, and the runner is
        // invoked exactly once (no needless retry after success).
        ScriptedRunner runner = new ScriptedRunner().thenExits(0);
        VerifyLoop loop = new VerifyLoop(runner, RemedyAttempt.NONE, 5);

        VerifyOutcome outcome = loop.verify();

        assertTrue(outcome.verified(), "RD-10: a zero exit is the success signal");
        assertEquals(VerifyOutcome.Kind.VERIFIED, outcome.kind());
        assertEquals(1, outcome.iterations(), "verified on the first attempt");
        assertEquals(0, outcome.result().exitCode(), "the carried result is the passing (exit-0) run");
        assertEquals(1, runner.runs(), "a success runs the command exactly once (no retry)");
    }

    @Test
    @DisplayName("CT-INV-14: a non-zero exit is NOT success (failure direction), surfaces after the bound")
    void nonZeroExitIsNotSuccess() {
        // Oracle: CT-INV-14/INV-17 (failure direction) — any non-zero exit is not success.
        // A persistent exit 1 never verifies; within a bound of 1 it surfaces immediately.
        ScriptedRunner runner = new ScriptedRunner().thenExits(1);
        VerifyLoop loop = new VerifyLoop(runner, RemedyAttempt.NONE, 1);

        VerifyOutcome outcome = loop.verify();

        assertFalse(outcome.verified(), "CT-INV-14: a non-zero exit is not the success signal");
        assertEquals(VerifyOutcome.Kind.EXHAUSTED, outcome.kind());
    }

    @Test
    @DisplayName("CT-INV-14: a timed-out run (exit 124) counts as failure, not success")
    void timedOutRunIsFailure() {
        // Oracle: CT-INV-14 / dep-API note — a timed-out command has exitCode 124 (non-zero),
        // so it is NOT the zero-exit success signal. The loop treats it as a failed attempt.
        CommandResult timedOut = CommandResult.timedOut(TEST_CMD, "partial", "killed", 300_000L);
        ScriptedRunner runner = new ScriptedRunner().then(timedOut);
        VerifyLoop loop = new VerifyLoop(runner, RemedyAttempt.NONE, 1);

        VerifyOutcome outcome = loop.verify();

        assertEquals(124, timedOut.exitCode(), "guard: the timeout exit code is the non-zero 124");
        assertFalse(outcome.verified(), "CT-INV-14: a 124 (timed-out) run is not success");
        assertEquals(VerifyOutcome.Kind.EXHAUSTED, outcome.kind());
        assertSame(timedOut, outcome.result(),
                "AC-20.5: the surfaced result is the timed-out run, output preserved");
    }

    // --- convergence : fail -> fail -> pass reports the iteration count -------------------

    @Test
    @DisplayName("AC-20.3/RD-10: fail->fail->pass converges and reports the passing attempt number")
    void failFailPassConvergesAndReportsIterations() {
        // Oracle: AC-20.3 (react to non-zero, retry) + RD-10 (zero = success). A
        // fail(1)->fail(2)->pass(0) sequence converges to VERIFIED on the THIRD attempt, so
        // iterations() == 3, and the remedy is fed the two failures (one between each pair of
        // attempts), never after the success.
        ScriptedRunner runner = new ScriptedRunner().thenExits(1).thenExits(2).thenExits(0);
        RecordingRemedy remedy = new RecordingRemedy();
        VerifyLoop loop = new VerifyLoop(runner, remedy, 5);

        VerifyOutcome outcome = loop.verify();

        assertTrue(outcome.verified(), "RD-10: the final zero exit is success");
        assertEquals(3, outcome.iterations(), "converged on the third attempt");
        assertEquals(0, outcome.result().exitCode(), "the carried result is the passing run");
        assertEquals(3, runner.runs(), "ran exactly three attempts (fail, fail, pass)");
        assertEquals(2, remedy.invocations(),
                "AC-20.3: a remedy is attempted after each of the two failures, not after success");
    }

    // --- the bound : exactly N attempts then surface, NOT N+1 (AC-3.4 / AC-20.5 / CT-SM-5) -

    @Test
    @DisplayName("CT-SM-5/AC-3.4: N consecutive failures stop after exactly N attempts and surface")
    void exactlyMaxIterationsFailuresThenSurface() {
        // Oracle: CT-SM-5/AC-3.4/AC-20.5 — verification that never succeeds stops after
        // exactly NFR-VERIFY-MAX-ITERATIONS attempts and surfaces. With a bound of 5 and a
        // runner scripted with EXACTLY 5 failures, the loop runs all 5 and surfaces; it must
        // NOT attempt a 6th (the scripted runner would throw if a 6th run were made, proving
        // the off-by-one boundary holds).
        ScriptedRunner runner = new ScriptedRunner()
                .thenExits(1).thenExits(1).thenExits(1).thenExits(1).thenExits(7);
        RecordingRemedy remedy = new RecordingRemedy();
        VerifyLoop loop = new VerifyLoop(runner, remedy, 5);

        VerifyOutcome outcome = loop.verify();

        assertFalse(outcome.verified(), "AC-3.4: never succeeded — not verified");
        assertEquals(VerifyOutcome.Kind.EXHAUSTED, outcome.kind());
        assertEquals(5, outcome.iterations(), "AC-20.5: stopped after exactly the bound (5) attempts");
        assertEquals(5, runner.runs(), "CT-SM-5: ran exactly 5 attempts, NOT 6 (no run past the bound)");
        assertEquals(4, remedy.invocations(),
                "AC-20.3: a remedy is attempted between attempts (4 times for 5 attempts), "
                        + "never after the final attempt");
    }

    @Test
    @DisplayName("AC-20.5: the surfaced outcome carries the LAST attempt's relevant output")
    void surfacedOutcomeCarriesLastFailureOutput() {
        // Oracle: AC-20.5 — "stop and surface the failure WITH THE RELEVANT OUTPUT". The
        // surfaced result must be the LAST attempt's failing run (exit 7 here), with its
        // stdout/stderr intact, not the first failure and not a dropped/empty result.
        ScriptedRunner runner = new ScriptedRunner().thenExits(1).thenExits(2).thenExits(7);
        VerifyLoop loop = new VerifyLoop(runner, RemedyAttempt.NONE, 3);

        VerifyOutcome outcome = loop.verify();

        assertEquals(VerifyOutcome.Kind.EXHAUSTED, outcome.kind());
        assertEquals(7, outcome.result().exitCode(),
                "AC-20.5: the surfaced result is the LAST failing attempt (exit 7), not the first");
        assertEquals("stdout-7", outcome.result().stdout(),
                "AC-20.5: the last attempt's stdout is preserved (relevant output not dropped)");
        assertEquals("stderr-7", outcome.result().stderr(),
                "AC-20.5: the last attempt's stderr is preserved (relevant output not dropped)");
    }

    @Test
    @DisplayName("the bound boundary: N-1 failures then a pass on attempt N verifies (not surfaces)")
    void nMinusOneFailuresThenPassVerifies() {
        // Oracle: RD-10 + AC-3.4 boundary — the success on the very last permitted attempt
        // (attempt N) must VERIFY, not surface. With bound 5, four failures then a pass on the
        // fifth attempt yields VERIFIED at iteration 5, exercising the off-by-one on the
        // success side (the loop does not give up one attempt too early).
        ScriptedRunner runner = new ScriptedRunner()
                .thenExits(1).thenExits(1).thenExits(1).thenExits(1).thenExits(0);
        VerifyLoop loop = new VerifyLoop(runner, RemedyAttempt.NONE, 5);

        VerifyOutcome outcome = loop.verify();

        assertTrue(outcome.verified(),
                "RD-10: a zero exit on the last permitted attempt (N) still verifies");
        assertEquals(5, outcome.iterations(), "verified on the fifth (last permitted) attempt");
        assertEquals(5, runner.runs(), "ran exactly five attempts (four fails, then the pass)");
    }

    // --- the bound is the CONFIG value, not a literal 5 (NFR-VERIFY-MAX-ITERATIONS) -------

    @Test
    @DisplayName("NFR-VERIFY-MAX-ITERATIONS: the bound is the config value, exercised at 1, 2 and 3")
    void boundIsTheConfiguredValueNotALiteral() {
        // Oracle: NFR-VERIFY-MAX-ITERATIONS — the loop bound is config.verifyMaxIterations(),
        // not a hardcoded 5. Drive the loop at several bounds and assert it surfaces after
        // exactly that many failing attempts. A loop that hardcoded 5 would fail these.
        for (int bound : new int[] {1, 2, 3}) {
            ScriptedRunner runner = new ScriptedRunner();
            for (int i = 0; i < bound; i++) {
                runner.thenExits(1);
            }
            VerifyLoop loop = new VerifyLoop(runner, RemedyAttempt.NONE, bound);

            VerifyOutcome outcome = loop.verify();

            assertEquals(VerifyOutcome.Kind.EXHAUSTED, outcome.kind(),
                    "bound " + bound + ": never succeeded, surfaces");
            assertEquals(bound, outcome.iterations(),
                    "bound " + bound + ": stops after exactly the configured number of attempts");
            assertEquals(bound, runner.runs(),
                    "bound " + bound + ": runs exactly the configured number of attempts");
        }
    }

    // --- AC-20.3 : the remedy receives the failing output --------------------------------

    @Test
    @DisplayName("AC-20.3: the remedy is fed the failing attempt's output before the next attempt")
    void remedyReceivesFailingOutput() {
        // Oracle: AC-20.3 — "feed the failure output back into its reasoning and attempt a
        // remedy". After the first failing attempt (exit 1, captured stderr), the remedy seam
        // is invoked with THAT failing result, before the retry. The remedy here flips nothing
        // (the runner is scripted to pass on the second attempt independently).
        ScriptedRunner runner = new ScriptedRunner().thenExits(1).thenExits(0);
        RecordingRemedy remedy = new RecordingRemedy();
        VerifyLoop loop = new VerifyLoop(runner, remedy, 5);

        VerifyOutcome outcome = loop.verify();

        assertTrue(outcome.verified());
        assertEquals(1, remedy.invocations(), "AC-20.3: the remedy is attempted once, after the failure");
        assertEquals(1, remedy.failuresSeen.get(0).exitCode(),
                "AC-20.3: the remedy is fed the FAILING attempt's result (exit 1)");
        assertEquals("stderr-1", remedy.failuresSeen.get(0).stderr(),
                "AC-20.3: the failure OUTPUT is what is fed back for reasoning");
    }

    @Test
    @DisplayName("AC-20.3: the remedy is NOT invoked when the first attempt already succeeds")
    void remedyNotInvokedOnImmediateSuccess() {
        // Oracle: AC-20.3 fires only on a non-zero exit. A first-attempt success must not
        // trigger a remedy (nothing failed to feed back).
        ScriptedRunner runner = new ScriptedRunner().thenExits(0);
        RecordingRemedy remedy = new RecordingRemedy();
        VerifyLoop loop = new VerifyLoop(runner, remedy, 5);

        loop.verify();

        assertEquals(0, remedy.invocations(),
                "AC-20.3: no failure, so no remedy is attempted");
    }

    // --- AC-20.6 : no test command configured --------------------------------------------

    @Test
    @DisplayName("AC-20.6: a null command runner verifies nothing and reports NO_TEST_COMMAND")
    void noTestCommandRunnerYieldsNoTestCommand() {
        // Oracle: AC-20.6 — when no test command is configured there is nothing to verify;
        // the loop runs nothing and reports the distinct NO_TEST_COMMAND state (not a crash,
        // not an invented ad-hoc command).
        VerifyLoop loop = new VerifyLoop(null, RemedyAttempt.NONE, 5);

        VerifyOutcome outcome = loop.verify();

        assertEquals(VerifyOutcome.Kind.NO_TEST_COMMAND, outcome.kind());
        assertFalse(outcome.verified(), "AC-20.6: no test command is not a success signal");
        assertEquals(0, outcome.iterations(), "AC-20.6: nothing ran");
    }

    @Test
    @DisplayName("AC-20.6: forConfig with no configured test command yields NO_TEST_COMMAND")
    void forConfigWithNoTestCommandYieldsNoTestCommand(@TempDir Path workspace) {
        // Oracle: AC-20.6 + dep API (Commands.test() is null when unconfigured). A config
        // whose Commands has a null test command produces a loop that verifies nothing,
        // without shelling out (the real executor is constructed but never invoked).
        ResolvedConfig config = configWith(new Commands(null, null, null), 5);
        VerifyLoop loop = VerifyLoop.forConfig(new CommandExecutor(workspace), config, RemedyAttempt.NONE);

        VerifyOutcome outcome = loop.verify();

        assertEquals(VerifyOutcome.Kind.NO_TEST_COMMAND, outcome.kind(),
                "AC-20.6: an absent configured test command is reported, not papered over");
    }

    // --- forConfig wiring : uses the configured bound and test command --------------------

    @Test
    @DisplayName("forConfig: a configured test command runs through the real executor (RD-10 success)")
    void forConfigRunsConfiguredTestCommandToSuccess(@TempDir Path workspace) {
        // Oracle: AC-20.1/AC-20.6/RD-10 — forConfig wires the CONFIGURED test command through
        // the real CommandExecutor. A trivially-passing command ("true", exit 0) verifies on
        // the first attempt. This exercises the production wiring (config -> command ->
        // executor) end-to-end through the real executor, not a scripted runner.
        ResolvedConfig config = configWith(new Commands(null, "true", null), 5);
        VerifyLoop loop = VerifyLoop.forConfig(new CommandExecutor(workspace), config, RemedyAttempt.NONE);

        VerifyOutcome outcome = loop.verify();

        assertTrue(outcome.verified(), "RD-10: the configured 'true' command exits 0 and verifies");
        assertEquals(1, outcome.iterations(), "verified on the first attempt");
    }

    @Test
    @DisplayName("forConfig: the loop bound is config.verifyMaxIterations (a failing command surfaces)")
    void forConfigUsesConfiguredBound(@TempDir Path workspace) {
        // Oracle: NFR-VERIFY-MAX-ITERATIONS + AC-3.4 — forConfig draws its bound from
        // config.verifyMaxIterations(). A config with bound 2 and a configured command that
        // always fails ("false", exit 1) surfaces EXHAUSTED after exactly 2 attempts, proving
        // the wiring carries the config bound (not a literal 5) into the loop.
        ResolvedConfig config = configWith(new Commands(null, "false", null), 2);
        VerifyLoop loop = VerifyLoop.forConfig(new CommandExecutor(workspace), config, RemedyAttempt.NONE);

        VerifyOutcome outcome = loop.verify();

        assertEquals(VerifyOutcome.Kind.EXHAUSTED, outcome.kind(),
                "AC-3.4: the always-failing 'false' command never verifies and surfaces");
        assertEquals(2, outcome.iterations(),
                "NFR-VERIFY-MAX-ITERATIONS: stopped after exactly the configured bound (2)");
    }

    // --- construction validation ----------------------------------------------------------

    @Test
    @DisplayName("VerifyLoop requires a non-null remedy and a bound >= 1")
    void constructorValidatesRemedyAndBound() {
        assertThrows(NullPointerException.class,
                () -> new VerifyLoop(new ScriptedRunner(), null, 5),
                "the remedy seam is required");
        assertThrows(IllegalArgumentException.class,
                () -> new VerifyLoop(new ScriptedRunner(), RemedyAttempt.NONE, 0),
                "NFR-VERIFY-MAX-ITERATIONS schema range is >= 1");
    }

    @Test
    @DisplayName("forConfig requires its executor, config and remedy")
    void forConfigValidatesArguments(@TempDir Path workspace) {
        ResolvedConfig config = configWith(new Commands(null, "true", null), 5);
        CommandExecutor executor = new CommandExecutor(workspace);
        assertThrows(NullPointerException.class,
                () -> VerifyLoop.forConfig(null, config, RemedyAttempt.NONE));
        assertThrows(NullPointerException.class,
                () -> VerifyLoop.forConfig(executor, null, RemedyAttempt.NONE));
        assertThrows(NullPointerException.class,
                () -> VerifyLoop.forConfig(executor, config, null));
    }

    private static ResolvedConfig configWith(Commands commands, int verifyMaxIterations) {
        return new ResolvedConfig(
                "anthropic.claude-opus-4-8",
                PermissionMode.ASK_EVERY_TIME,
                "us-east-1",
                null,
                1,
                null,
                commands,
                0.85,
                16384,
                verifyMaxIterations,
                300);
    }
}
