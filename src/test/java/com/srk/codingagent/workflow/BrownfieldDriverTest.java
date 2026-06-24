package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.config.ResolvedConfig.Commands;
import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.loop.RemedyAttempt;
import com.srk.codingagent.loop.VerifyOutcome;
import com.srk.codingagent.persistence.StopReason;
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
 * Unit tests for {@link BrownfieldDriver} — the brownfield understand&rarr;change&rarr;verify
 * orchestration over the shared engine (component C3, ADR-0012 brownfield side, US-4/US-5).
 *
 * <p><b>SUT and collaborators.</b> The SUT is a real {@link BrownfieldDriver}. The two seams are
 * scripted, not mocked SUT internals: the {@link BrownfieldDriver.LoopTurn} (the
 * {@link com.srk.codingagent.loop.AgentLoop#run} shape) is scripted with {@link LoopOutcome}s so
 * the understand&rarr;change turn and each remedy turn are driven without a live model, and the
 * {@link BrownfieldDriver.VerifierFactory} yields a scripted {@link BrownfieldDriver.Verifier}
 * (the {@link com.srk.codingagent.loop.VerifyLoop#verify} shape) so verify outcomes are scripted
 * without shelling out to a real build. This mirrors {@code OneShotRunnerTest}'s scripted-Bedrock
 * and {@code VerifyLoopTest}'s scripted-runner discipline (the external boundary is substituted,
 * never the SUT).
 *
 * <p><b>M0-lesson discipline.</b> A structurally-blind test that asserts only "the driver called
 * run()" would tell nothing about whether the real explore&rarr;change&rarr;verify arc works.
 * These tests assert the actual orchestration contract: a completed change-turn triggers
 * verification (AC-5.3); a VERIFIED outcome ends clean while an EXHAUSTED one surfaces with the
 * failure output (AC-20.5) and NO_TEST_COMMAND is handled (AC-20.6); and the {@link RemedyAttempt}
 * the driver supplies drives <em>another</em> loop turn fed the failure (AC-20.3), bounded by the
 * verify loop.
 *
 * <p><b>Oracles trace to the brownfield ACs, never to the driver's code:</b> see each test's
 * inline oracle note.
 */
class BrownfieldDriverTest {

    private static final String REQUEST = "add a null check to OrderService.validate";

    // --- Scripted LoopTurn seam: replays LoopOutcomes and records the prompts it was run with.
    //     This is the external boundary (the agent loop / model), not the SUT.
    private static final class ScriptedLoop implements BrownfieldDriver.LoopTurn {
        private final Deque<LoopOutcome> script = new ArrayDeque<>();
        private final List<String> prompts = new ArrayList<>();

        ScriptedLoop then(LoopOutcome outcome) {
            script.addLast(outcome);
            return this;
        }

        ScriptedLoop thenCompleted(String text) {
            return then(LoopOutcome.completed(text));
        }

        @Override
        public LoopOutcome run(String prompt) {
            prompts.add(prompt);
            if (script.isEmpty()) {
                throw new IllegalStateException("scripted loop exhausted after " + prompts.size() + " run(s)");
            }
            return script.removeFirst();
        }

        int runs() {
            return prompts.size();
        }
    }

    // --- A scripted verifier factory: yields a verifier that returns a programmed outcome and
    //     captures the RemedyAttempt the driver supplies, so a test can both assert the outcome
    //     mapping AND drive the remedy to assert the failure-feedback wiring (AC-20.3).
    private static final class CapturingVerifierFactory implements BrownfieldDriver.VerifierFactory {
        private final VerifyOutcome outcome;
        private RemedyAttempt capturedRemedy;

        CapturingVerifierFactory(VerifyOutcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public BrownfieldDriver.Verifier create(RemedyAttempt remedy) {
            this.capturedRemedy = remedy;
            return () -> outcome;
        }
    }

    private static CommandResult failure(int exitCode, String stdout, String stderr) {
        return CommandResult.completed("mvn test", exitCode, stdout, stderr, 12L);
    }

    // --- AC-5.3 : a completed change-turn triggers verification --------------------------

    @Test
    @DisplayName("AC-5.3: a completed understand->change turn invokes the verify step")
    void completedChangeTurnTriggersVerification() {
        // Oracle: AC-5.3 — "when a change is applied, the agent shall verify it via the configured
        // build/test commands". A completed change-turn must trigger the verifier; the driver runs
        // the understand->change turn with the developer's request, then verifies.
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("made the change");
        VerifyOutcome verified = VerifyOutcome.verified(1, failure(0, "ok", ""));
        CapturingVerifierFactory factory = new CapturingVerifierFactory(verified);
        BrownfieldDriver driver = new BrownfieldDriver(loop, factory);

        BrownfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(REQUEST, loop.prompts.get(0),
                "the understand->change turn runs with the developer's request");
        assertTrue(outcome.verified(), "AC-5.3: a completed change is verified");
        assertEquals(BrownfieldOutcome.Disposition.VERIFIED, outcome.disposition());
        assertSame(verified, outcome.verifyOutcome(), "the verify outcome is carried back");
    }

    // --- state machine A : a surfaced change-turn does NOT verify ------------------------

    @Test
    @DisplayName("state machine A: a surfaced change-turn does not verify (nothing to verify yet)")
    void surfacedChangeTurnDoesNotVerify() {
        // Oracle: state machine A S6/S7 — when the turn surfaces an edge condition there is no
        // completed change to verify. The driver must NOT run the verifier (the verifier factory
        // here would NPE if invoked, since its outcome is null), and must surface the loop outcome.
        ScriptedLoop loop = new ScriptedLoop()
                .then(LoopOutcome.surfaced(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED));
        BrownfieldDriver.VerifierFactory neverCalled = remedy -> {
            throw new AssertionError("the verifier must not be built when the turn surfaced");
        };
        BrownfieldDriver driver = new BrownfieldDriver(loop, neverCalled);

        BrownfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(BrownfieldOutcome.Disposition.NOT_VERIFIED, outcome.disposition(),
                "a surfaced turn yields NOT_VERIFIED");
        assertFalse(outcome.verified());
        assertEquals(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED, outcome.loopOutcome().stopReason(),
                "the surfaced reason is preserved for the run path to map");
        assertEquals(1, loop.runs(), "only the change-turn ran; no verify, no remedy");
    }

    // --- AC-20.5 : an exhausted verification surfaces with the failure output ------------

    @Test
    @DisplayName("AC-20.5: an exhausted verification surfaces VERIFY_EXHAUSTED with the failure output")
    void exhaustedVerificationSurfaces() {
        // Oracle: AC-20.5 / AC-3.4 — when verification never succeeds the loop stops and surfaces
        // the failure WITH the relevant output. The driver maps an EXHAUSTED verify outcome to
        // VERIFY_EXHAUSTED, carrying the last failing run's output.
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("made the change");
        VerifyOutcome exhausted = VerifyOutcome.exhausted(5, failure(7, "tests run", "1 failure"));
        BrownfieldDriver driver = new BrownfieldDriver(loop, new CapturingVerifierFactory(exhausted));

        BrownfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(BrownfieldOutcome.Disposition.VERIFY_EXHAUSTED, outcome.disposition(),
                "AC-20.5: an exhausted verification surfaces, not a clean success");
        assertFalse(outcome.verified());
        assertEquals(7, outcome.verifyOutcome().result().exitCode(),
                "AC-20.5: the surfaced result is the last failing attempt");
        assertEquals("1 failure", outcome.verifyOutcome().result().stderr(),
                "AC-20.5: the failure's relevant output is preserved");
    }

    // --- AC-20.6 : no test command -> change made but not verified -----------------------

    @Test
    @DisplayName("AC-20.6: a NO_TEST_COMMAND verify outcome yields NOT_VERIFIED (change made, not verified)")
    void noTestCommandYieldsNotVerified() {
        // Oracle: AC-20.6 — with no test command there is nothing to verify; the change-turn
        // completed but the change is not verified. The driver maps NO_TEST_COMMAND to NOT_VERIFIED
        // (not a crash, not a false success).
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("made the change");
        BrownfieldDriver driver = new BrownfieldDriver(
                loop, new CapturingVerifierFactory(VerifyOutcome.noTestCommand()));

        BrownfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(BrownfieldOutcome.Disposition.NOT_VERIFIED, outcome.disposition());
        assertFalse(outcome.verified(), "AC-20.6: no test command is not a success signal");
        assertEquals(VerifyOutcome.Kind.NO_TEST_COMMAND, outcome.verifyOutcome().kind());
    }

    // --- AC-20.3 : the driver's remedy drives ANOTHER loop turn fed the failure ----------

    @Test
    @DisplayName("AC-20.3: the driver's remedy drives another loop turn fed the verification failure")
    void remedyDrivesAnotherLoopTurnWithFailure() {
        // Oracle: AC-20.3 — "feed the failure output back into reasoning and attempt a remedy".
        // The RemedyAttempt the driver supplies to the verify loop must, on a failing attempt,
        // drive ANOTHER agent-loop turn, and that turn's prompt must carry the failure output so
        // the model reasons over it. Here the verifier captures the remedy; we then invoke it (as
        // the verify loop would between attempts) and assert a second loop turn ran with the
        // failure output in its prompt.
        ScriptedLoop loop = new ScriptedLoop()
                .thenCompleted("made the change")   // the understand->change turn
                .thenCompleted("attempted a fix");  // the remedy turn the driver drives
        VerifyOutcome verified = VerifyOutcome.verified(2, failure(0, "ok", ""));
        CapturingVerifierFactory factory = new CapturingVerifierFactory(verified);
        BrownfieldDriver driver = new BrownfieldDriver(loop, factory);

        driver.run(REQUEST);

        // The driver supplied a remedy to the verifier; drive it as the verify loop would.
        assertNotNull(factory.capturedRemedy, "the driver supplied a remedy to the verify step");
        CommandResult failingRun = failure(1, "compile ok", "AssertionError: expected null check");
        factory.capturedRemedy.attempt(failingRun);

        assertEquals(2, loop.runs(),
                "AC-20.3: the remedy drove a SECOND loop turn (one change-turn + one remedy turn)");
        String remedyPrompt = loop.prompts.get(1);
        assertTrue(remedyPrompt.contains("AssertionError: expected null check"),
                "AC-20.3: the remedy turn's prompt carries the failure OUTPUT to reason over; was: "
                        + remedyPrompt);
        assertTrue(remedyPrompt.contains("1"),
                "AC-20.3: the remedy prompt names the failing exit code");
    }

    @Test
    @DisplayName("AC-20.3: the remedy prompt builder carries the failing command's output")
    void remedyPromptCarriesFailureOutput() {
        // Oracle: AC-20.3 / AC-20.5 — the failure OUTPUT (stdout/stderr) is what is fed back. The
        // remedy prompt must carry the command, its exit code, and its captured output, so the
        // model reasons over the real failure rather than a canned "it failed" string.
        CommandResult fail = failure(2, "BUILD output here", "FAILED: missing import");
        String prompt = com.srk.codingagent.loop.RemedyPrompt.forFailure(fail);

        assertTrue(prompt.contains("mvn test"), "the failing command is named");
        assertTrue(prompt.contains("2"), "the failing exit code is named");
        assertTrue(prompt.contains("BUILD output here"), "AC-20.3: stdout is fed back");
        assertTrue(prompt.contains("FAILED: missing import"), "AC-20.3/AC-20.5: stderr is fed back");
    }

    // --- AC-20.3 bound : the remedy is bounded by the verify loop (forConfig wiring) ------

    @Test
    @DisplayName("NFR-VERIFY-MAX-ITERATIONS: overConfig bounds the remedy-driven turns by the config bound")
    void overConfigBoundsRemedyTurnsByConfig(@TempDir Path workspace) {
        // Oracle: NFR-VERIFY-MAX-ITERATIONS / AC-20.3 — the fix-and-retry cycle is bounded by the
        // verify loop's iteration bound (config.verifyMaxIterations()), NOT unbounded. With a
        // configured test command that always fails ("false", exit 1) and a bound of 3, the verify
        // loop runs 3 attempts and invokes the remedy between them (twice), so the change-turn plus
        // two remedy turns run = exactly 3 loop runs, then it surfaces EXHAUSTED. A 4th loop run
        // would mean the bound leaked. The driver runs through the REAL VerifyLoop via overConfig
        // (only the command executor's subprocess is the boundary; "false" is a trivial real exit).
        ScriptedLoop loop = new ScriptedLoop()
                .thenCompleted("change")     // understand->change turn
                .thenCompleted("remedy 1")   // remedy after attempt 1
                .thenCompleted("remedy 2");  // remedy after attempt 2 (no remedy after attempt 3)
        ResolvedConfig config = configWith(new Commands(null, "false", null), 3);
        BrownfieldDriver driver = BrownfieldDriver.overConfig(
                loop, new CommandExecutor(workspace), config);

        BrownfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(BrownfieldOutcome.Disposition.VERIFY_EXHAUSTED, outcome.disposition(),
                "the always-failing command never verifies and surfaces");
        assertEquals(3, outcome.verifyOutcome().iterations(),
                "NFR-VERIFY-MAX-ITERATIONS: exactly the configured bound (3) attempts");
        assertEquals(3, loop.runs(),
                "AC-20.3 bound: one change-turn + two remedy turns (remedy between the 3 attempts), "
                        + "NOT unbounded");
    }

    @Test
    @DisplayName("overConfig: a passing configured test command verifies the change end-to-end (AC-5.3)")
    void overConfigVerifiesWithPassingCommand(@TempDir Path workspace) {
        // Oracle: AC-5.3 / RD-10 — overConfig wires the CONFIGURED test command through the real
        // VerifyLoop + CommandExecutor. A completed change-turn followed by a trivially-passing
        // command ("true", exit 0) yields VERIFIED with no remedy turn (the command passed on the
        // first attempt). Exercises the production composition end-to-end through the real verify
        // loop, not a scripted verifier.
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("change");
        ResolvedConfig config = configWith(new Commands(null, "true", null), 5);
        BrownfieldDriver driver = BrownfieldDriver.overConfig(
                loop, new CommandExecutor(workspace), config);

        BrownfieldOutcome outcome = driver.run(REQUEST);

        assertTrue(outcome.verified(), "AC-5.3/RD-10: the passing 'true' command verifies the change");
        assertEquals(1, outcome.verifyOutcome().iterations(), "verified on the first attempt");
        assertEquals(1, loop.runs(), "no remedy turn ran (verification passed first try)");
    }

    @Test
    @DisplayName("overConfig: no configured test command -> change made, NOT_VERIFIED (AC-20.6)")
    void overConfigNoTestCommandNotVerified(@TempDir Path workspace) {
        // Oracle: AC-20.6 — overConfig with a null test command yields a verify loop that runs
        // nothing; the driver maps it to NOT_VERIFIED. No command shells out.
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("change");
        ResolvedConfig config = configWith(new Commands(null, null, null), 5);
        BrownfieldDriver driver = BrownfieldDriver.overConfig(
                loop, new CommandExecutor(workspace), config);

        BrownfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(BrownfieldOutcome.Disposition.NOT_VERIFIED, outcome.disposition());
        assertEquals(VerifyOutcome.Kind.NO_TEST_COMMAND, outcome.verifyOutcome().kind(),
                "AC-20.6: an absent test command is reported, not papered over");
    }

    // --- construction + input validation -------------------------------------------------

    @Test
    @DisplayName("the driver requires its loop and verifier-factory seams")
    void constructorRejectsNull() {
        assertThrows(NullPointerException.class,
                () -> new BrownfieldDriver(null, remedy -> () -> VerifyOutcome.noTestCommand()));
        assertThrows(NullPointerException.class,
                () -> new BrownfieldDriver(new ScriptedLoop(), null));
    }

    @Test
    @DisplayName("overConfig requires its loop, executor, and config")
    void overConfigRejectsNull(@TempDir Path workspace) {
        ResolvedConfig config = configWith(new Commands(null, "true", null), 5);
        CommandExecutor executor = new CommandExecutor(workspace);
        assertThrows(NullPointerException.class,
                () -> BrownfieldDriver.overConfig(null, executor, config));
        assertThrows(NullPointerException.class,
                () -> BrownfieldDriver.overConfig(new ScriptedLoop(), null, config));
        assertThrows(NullPointerException.class,
                () -> BrownfieldDriver.overConfig(new ScriptedLoop(), executor, null));
    }

    @Test
    @DisplayName("run rejects a null or blank request")
    void runRejectsBlankRequest() {
        BrownfieldDriver driver = new BrownfieldDriver(
                new ScriptedLoop(), remedy -> () -> VerifyOutcome.noTestCommand());

        assertThrows(NullPointerException.class, () -> driver.run(null));
        assertThrows(IllegalArgumentException.class, () -> driver.run("  "));
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
                300,
                10,
                300);
    }
}
