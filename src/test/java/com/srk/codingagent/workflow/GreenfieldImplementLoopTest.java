package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.config.ResolvedConfig.Commands;
import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.loop.RemedyAttempt;
import com.srk.codingagent.loop.VerifyOutcome;
import com.srk.codingagent.tool.CommandExecutor;
import com.srk.codingagent.tool.CommandResult;
import com.srk.codingagent.tool.GreenfieldArtifactStore;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link GreenfieldImplementLoop} — the greenfield implement-one-task-at-a-time loop
 * (component C3 over C2, ADR-0012 implement clause; US-3, AC-3.1/3.2/3.3/3.4).
 *
 * <p><b>SUT and collaborators.</b> The SUT is a real {@link GreenfieldImplementLoop} over a real
 * {@link GreenfieldArtifactStore} rooted at a {@link TempDir} (the artifact read / mark-complete path
 * is real behaviour, not mocked). The two seams are scripted, not mocked SUT internals: the
 * {@link GreenfieldImplementLoop.LoopTurn} (the {@link com.srk.codingagent.loop.AgentLoop#run} shape)
 * is scripted with {@link LoopOutcome}s so each task's implementation turn and each remedy turn run
 * without a live model, and the {@link GreenfieldImplementLoop.VerifierFactory} yields a scripted
 * {@link GreenfieldImplementLoop.Verifier} (the {@link com.srk.codingagent.loop.VerifyLoop#verify}
 * shape) so per-task verify outcomes are scripted without shelling out to a real build. This mirrors
 * {@code BrownfieldDriverTest}'s scripted-seam discipline (the external boundary is substituted,
 * never the SUT).
 *
 * <p><b>Oracles trace to the US-3 implement ACs (and ADR-0012's implement clause), never to the
 * loop's code:</b> see each test's inline oracle note. Expected values are derived from the spec
 * body — "one task at a time in breakdown order" (AC-3.1), "mark it complete … before starting the
 * next" (AC-3.3), "stop and surface … rather than continuing silently" (AC-3.4) — not from observing
 * the implementation.
 */
class GreenfieldImplementLoopTest {

    private static final String IMPLEMENT_PROMPT = "Implement the planned tasks.";
    private static final String TASKS_PATH = GreenfieldArtifact.TASKS.relativePath();

    // --- Scripted LoopTurn seam: replays LoopOutcomes and records the prompts it was run with.
    //     This is the external boundary (the agent loop / model), not the SUT.
    private static final class ScriptedLoop implements GreenfieldImplementLoop.LoopTurn {
        private final Deque<LoopOutcome> script = new ArrayDeque<>();
        private final List<String> prompts = new ArrayList<>();
        private boolean autoComplete;

        ScriptedLoop thenCompleted(String text) {
            script.addLast(LoopOutcome.completed(text));
            return this;
        }

        /** Every run completes (no scripting needed); used when the test only inspects prompts. */
        ScriptedLoop autoCompleting() {
            this.autoComplete = true;
            return this;
        }

        @Override
        public LoopOutcome run(String prompt) {
            prompts.add(prompt);
            if (autoComplete) {
                return LoopOutcome.completed("done");
            }
            if (script.isEmpty()) {
                throw new IllegalStateException("scripted loop exhausted after " + prompts.size());
            }
            return script.removeFirst();
        }

        int runs() {
            return prompts.size();
        }
    }

    /**
     * A verifier factory scripted with one outcome per task (in call order). It also captures the
     * {@link RemedyAttempt} the loop supplied on the most recent {@code create}, so a test can drive
     * the remedy and assert the failure-feedback wiring (AC-20.3).
     */
    private static final class ScriptedVerifierFactory
            implements GreenfieldImplementLoop.VerifierFactory {
        private final Deque<VerifyOutcome> outcomes = new ArrayDeque<>();
        private RemedyAttempt capturedRemedy;
        private int created;

        ScriptedVerifierFactory then(VerifyOutcome outcome) {
            outcomes.addLast(outcome);
            return this;
        }

        @Override
        public GreenfieldImplementLoop.Verifier create(RemedyAttempt remedy) {
            this.capturedRemedy = remedy;
            created++;
            if (outcomes.isEmpty()) {
                throw new IllegalStateException("scripted verifier exhausted after " + created);
            }
            VerifyOutcome outcome = outcomes.removeFirst();
            return () -> outcome;
        }
    }

    private static CommandResult result(int exitCode, String stdout, String stderr) {
        return CommandResult.completed("mvn test", exitCode, stdout, stderr, 10L);
    }

    private static VerifyOutcome verified(int attempt) {
        return VerifyOutcome.verified(attempt, result(0, "BUILD SUCCESS", ""));
    }

    private static GreenfieldArtifactStore storeWithBreakdown(Path workspace, String breakdown) {
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(workspace);
        store.write(TASKS_PATH, breakdown);
        return store;
    }

    private static final String TWO_TASK_BREAKDOWN = """
            # Tasks

            - T-1 Build the parser (refs AC-1.2)
            - T-2 Wire the CLI (refs US-3)
            """;

    // --- AC-3.1 : tasks are implemented one at a time, in breakdown order ------------------------

    @Test
    @DisplayName("AC-3.1: the loop implements the tasks one at a time, in the breakdown order")
    void implementsTasksOneAtATimeInBreakdownOrder(@TempDir Path workspace) {
        // Oracle: AC-3.1 — "While implementing, the agent shall work one task at a time in breakdown
        // order." With a two-task breakdown (T-1 then T-2) and both verifying, the loop must drive an
        // implementation turn for T-1 first, then T-2 — one turn per task, in file order — each turn
        // naming the task it is implementing. The task ids and order trace to the breakdown the spec
        // describes, not to the loop's parser.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, TWO_TASK_BREAKDOWN);
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("did T-1").thenCompleted("did T-2");
        ScriptedVerifierFactory verifiers = new ScriptedVerifierFactory()
                .then(verified(1)).then(verified(1));
        GreenfieldImplementLoop implementLoop =
                new GreenfieldImplementLoop(loop, verifiers, store);

        ImplementOutcome outcome = implementLoop.run(IMPLEMENT_PROMPT);

        assertEquals(ImplementOutcome.Disposition.ALL_VERIFIED, outcome.disposition(),
                "both tasks verified, so the run is all-verified");
        assertEquals(List.of("T-1", "T-2"), outcome.completedTasks(),
                "AC-3.1: the tasks are implemented and completed in breakdown order (T-1 then T-2)");
        assertEquals(2, loop.runs(),
                "AC-3.1: exactly one implementation turn ran per task (no remedy needed)");
        assertTrue(loop.prompts.get(0).contains("T-1"),
                "AC-3.1: the first turn implements the first task in breakdown order; was: "
                        + loop.prompts.get(0));
        assertTrue(loop.prompts.get(1).contains("T-2"),
                "AC-3.1: the second turn implements the second task; was: " + loop.prompts.get(1));
    }

    @Test
    @DisplayName("AC-3.1: each task's implementation turn is scoped to its one task, not the whole breakdown")
    void eachTurnImplementsExactlyOneTask(@TempDir Path workspace) {
        // Oracle: AC-3.1 — "work one task at a time". The observable, spec-grounded property is that
        // a task's implementation turn is scoped to THAT one task and not the others: the turn for T-1
        // names T-1 and does NOT direct implementing T-2 (which is a separate, later turn). Asserting
        // "names this task, not the next task" pins "one task at a time" behaviourally, without binding
        // to the impl's exact instruction wording.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, TWO_TASK_BREAKDOWN);
        ScriptedLoop loop = new ScriptedLoop().autoCompleting();
        ScriptedVerifierFactory verifiers = new ScriptedVerifierFactory()
                .then(verified(1)).then(verified(1));

        new GreenfieldImplementLoop(loop, verifiers, store).run(IMPLEMENT_PROMPT);

        String firstTurn = loop.prompts.get(0);
        assertTrue(firstTurn.contains("T-1"),
                "AC-3.1: the first turn names the task it implements (T-1); was: " + firstTurn);
        assertFalse(firstTurn.contains("T-2"),
                "AC-3.1: the first turn is scoped to T-1 only — it does not direct implementing the "
                        + "next task (T-2) in the same turn; was: " + firstTurn);
    }

    // --- AC-3.3 : a verified task is marked complete before the next starts ----------------------

    @Test
    @DisplayName("AC-3.3: a task that passes verification is marked complete in the artifact before the next task")
    void verifiedTaskMarkedCompleteBeforeNext(@TempDir Path workspace) {
        // Oracle: AC-3.3 — "When a task passes verification, the agent shall mark it complete in the
        // task-breakdown artifact before starting the next." With both tasks verifying, the
        // task-breakdown artifact must carry a "marked complete" line for each task. The spec-grounded
        // observable for "marked complete" is a completed-checkbox markdown task line naming the id
        // ("[x] T-n", the conventional completed-task form), so assert each id appears on a completion
        // line — derived from AC-3.3, not from any impl constant.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, TWO_TASK_BREAKDOWN);
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("did T-1").thenCompleted("did T-2");
        ScriptedVerifierFactory verifiers = new ScriptedVerifierFactory()
                .then(verified(1)).then(verified(1));

        new GreenfieldImplementLoop(loop, verifiers, store).run(IMPLEMENT_PROMPT);

        String artifact = store.read(TASKS_PATH).orElseThrow();
        assertTrue(artifact.contains("[x] T-1"),
                "AC-3.3: T-1 is marked complete in the task-breakdown artifact; was:\n" + artifact);
        assertTrue(artifact.contains("[x] T-2"),
                "AC-3.3: T-2 is also marked complete after verifying; was:\n" + artifact);
    }

    @Test
    @DisplayName("AC-3.3: the completion marking precedes the next task's implementation turn")
    void markCompleteHappensBeforeStartingNextTask(@TempDir Path workspace) {
        // Oracle: AC-3.3 — mark complete "BEFORE starting the next". Use a loop whose T-2
        // implementation turn observes the artifact: by the time T-2's turn runs, the artifact must
        // already carry T-1's completion line ("[x] T-1", the spec-grounded "marked complete" shape).
        // This pins the ordering AC-3.3 requires (mark, then advance), not merely that a marker
        // eventually appears.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, TWO_TASK_BREAKDOWN);
        List<Boolean> t1MarkedWhenT2Started = new ArrayList<>();
        GreenfieldImplementLoop.LoopTurn observingLoop = prompt -> {
            if (prompt.contains("T-2")) {
                String current = store.read(TASKS_PATH).orElse("");
                t1MarkedWhenT2Started.add(current.contains("[x] T-1"));
            }
            return LoopOutcome.completed("done");
        };
        ScriptedVerifierFactory verifiers = new ScriptedVerifierFactory()
                .then(verified(1)).then(verified(1));

        new GreenfieldImplementLoop(observingLoop, verifiers, store).run(IMPLEMENT_PROMPT);

        assertEquals(List.of(true), t1MarkedWhenT2Started,
                "AC-3.3: T-1 is marked complete in the artifact BEFORE the T-2 turn begins");
    }

    // --- AC-3.4 : a task that fails verification stops the loop (no advance) ---------------------

    @Test
    @DisplayName("AC-3.4: a task that fails verification within the bound stops the loop, not advancing to the next")
    void verifyExhaustedStopsWithoutAdvancing(@TempDir Path workspace) {
        // Oracle: AC-3.4 — "If a task fails verification after NFR-VERIFY-MAX-ITERATIONS, then the
        // agent shall stop and surface the failure rather than continuing silently." With T-1's
        // verification EXHAUSTED, the loop must STOP at T-1: it must NOT run T-2's implementation turn,
        // and the outcome must name T-1 as the stopped task carrying the failing output (AC-20.5).
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, TWO_TASK_BREAKDOWN);
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("attempted T-1");
        VerifyOutcome exhausted = VerifyOutcome.exhausted(5, result(7, "ran", "1 failing test"));
        ScriptedVerifierFactory verifiers = new ScriptedVerifierFactory().then(exhausted);

        ImplementOutcome outcome = new GreenfieldImplementLoop(loop, verifiers, store)
                .run(IMPLEMENT_PROMPT);

        assertEquals(ImplementOutcome.Disposition.VERIFY_EXHAUSTED, outcome.disposition(),
                "AC-3.4: a task that fails verification stops the loop (not a clean success)");
        assertEquals("T-1", outcome.stoppedTaskIfPresent().orElseThrow(),
                "AC-3.4: the loop stops at the task that failed verification (T-1)");
        assertTrue(outcome.completedTasks().isEmpty(),
                "AC-3.4: no task was completed before the failing first task");
        assertEquals(1, loop.runs(),
                "AC-3.4: the loop STOPPED at T-1 — T-2's implementation turn never ran (no advance)");
        assertEquals("1 failing test", outcome.verifyOutcome().result().stderr(),
                "AC-20.5: the surfaced failure carries the relevant output");
    }

    @Test
    @DisplayName("AC-3.4: tasks verified before the failure are kept; the loop stops at the failing one")
    void completedTasksBeforeFailureAreKept(@TempDir Path workspace) {
        // Oracle: AC-3.4 / AC-3.3 — the loop completes tasks until one fails, then stops. With T-1
        // verifying (and marked complete) and T-2 exhausting, the outcome must list T-1 as completed
        // and stop at T-2, never running a (non-existent) third task. This pins "stop at the failure",
        // not "discard prior progress".
        String threeTasks = """
                - T-1 Parser (AC-1.2)
                - T-2 CLI (US-3)
                - T-3 Persist (AC-2.1)
                """;
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, threeTasks);
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("did T-1").thenCompleted("attempted T-2");
        ScriptedVerifierFactory verifiers = new ScriptedVerifierFactory()
                .then(verified(1))
                .then(VerifyOutcome.exhausted(5, result(1, "", "compile error")));

        ImplementOutcome outcome = new GreenfieldImplementLoop(loop, verifiers, store)
                .run(IMPLEMENT_PROMPT);

        assertEquals(ImplementOutcome.Disposition.VERIFY_EXHAUSTED, outcome.disposition());
        assertEquals(List.of("T-1"), outcome.completedTasks(),
                "AC-3.3: T-1 verified-and-completed is kept");
        assertEquals("T-2", outcome.stoppedTaskIfPresent().orElseThrow(),
                "AC-3.4: the loop stops at the failing task (T-2)");
        assertEquals(2, loop.runs(),
                "AC-3.4: T-3's turn never ran — the loop stopped at the T-2 failure");
    }

    // --- AC-20.3 : the remedy drives ANOTHER loop turn fed the failure (verify reuse) -------------

    @Test
    @DisplayName("AC-20.3: the remedy the loop supplies drives another implementation turn fed the verification failure")
    void remedyDrivesAnotherTurnWithFailure(@TempDir Path workspace) {
        // Oracle: AC-20.3 — "feed the failure output back into reasoning and attempt a remedy". The
        // RemedyAttempt the loop supplies to its (reused) verify loop must, on a failing attempt,
        // drive ANOTHER agent-loop turn whose prompt carries the failure output. Capture the remedy
        // the loop supplied, invoke it as the verify loop would between attempts, and assert a further
        // turn ran with the failure output in its prompt.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, "- T-1 (AC-1.2)\n");
        ScriptedLoop loop = new ScriptedLoop()
                .thenCompleted("implemented T-1")
                .thenCompleted("attempted a fix");
        ScriptedVerifierFactory verifiers = new ScriptedVerifierFactory().then(verified(2));

        new GreenfieldImplementLoop(loop, verifiers, store).run(IMPLEMENT_PROMPT);

        CommandResult failingRun = result(1, "compiled", "AssertionError: expected 3 but was 2");
        verifiers.capturedRemedy.attempt(failingRun);

        assertEquals(2, loop.runs(),
                "AC-20.3: the remedy drove a SECOND turn (one task turn + one remedy turn)");
        String remedyPrompt = loop.prompts.get(1);
        assertTrue(remedyPrompt.contains("AssertionError: expected 3 but was 2"),
                "AC-20.3: the remedy turn's prompt carries the failure OUTPUT to reason over; was: "
                        + remedyPrompt);
    }

    @Test
    @DisplayName("AC-20.3: the remedy prompt builder carries the failing command's output")
    void remedyPromptCarriesFailureOutput() {
        // Oracle: AC-20.3 / AC-20.5 — the failure OUTPUT (stdout/stderr) is what is fed back. The
        // remedy prompt must carry the command, its exit code, and its captured output so the model
        // reasons over the real failure, not a canned "it failed" string.
        CommandResult fail = result(2, "BUILD output", "FAILED: missing import");
        String prompt = com.srk.codingagent.loop.RemedyPrompt.forFailure(fail);

        assertTrue(prompt.contains("mvn test"), "the failing command is named");
        assertTrue(prompt.contains("2"), "the failing exit code is named");
        assertTrue(prompt.contains("BUILD output"), "AC-20.3: stdout is fed back");
        assertTrue(prompt.contains("FAILED: missing import"), "AC-20.3/AC-20.5: stderr is fed back");
    }

    // --- AC-20.6 : no test command -> reported, not papered over ---------------------------------

    @Test
    @DisplayName("AC-20.6: with no test command the loop reports NO_TEST_COMMAND (a task cannot be verified before the next)")
    void noTestCommandReported(@TempDir Path workspace) {
        // Oracle: AC-20.6 — with no test command there is nothing to verify against; the loop reports
        // it rather than papering over with an ad-hoc verification. With the verify step yielding
        // NO_TEST_COMMAND for the first task, the loop must yield a NO_TEST_COMMAND outcome and not
        // mark any task complete (nothing was verified).
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, TWO_TASK_BREAKDOWN);
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("did T-1");
        ScriptedVerifierFactory verifiers = new ScriptedVerifierFactory()
                .then(VerifyOutcome.noTestCommand());

        ImplementOutcome outcome = new GreenfieldImplementLoop(loop, verifiers, store)
                .run(IMPLEMENT_PROMPT);

        assertEquals(ImplementOutcome.Disposition.NO_TEST_COMMAND, outcome.disposition(),
                "AC-20.6: an absent test command is reported, not papered over");
        assertTrue(outcome.completedTasks().isEmpty(),
                "AC-20.6: no task is marked complete when nothing was verified");
        assertEquals(VerifyOutcome.Kind.NO_TEST_COMMAND, outcome.verifyOutcome().kind());
    }

    // --- breakdown with no recognizable task -----------------------------------------------------

    @Test
    @DisplayName("a breakdown with no recognizable task yields NO_TASKS (nothing to implement)")
    void noRecognizableTaskYieldsNoTasks(@TempDir Path workspace) {
        // Oracle: AC-3.1 operates over the breakdown's tasks; a breakdown with no stable-id task has
        // nothing to implement one-at-a-time. The loop must report NO_TASKS and drive no turn.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, "# Tasks\n\nProse, no tasks.\n");
        ScriptedLoop loop = new ScriptedLoop();
        ScriptedVerifierFactory verifiers = new ScriptedVerifierFactory();

        ImplementOutcome outcome = new GreenfieldImplementLoop(loop, verifiers, store)
                .run(IMPLEMENT_PROMPT);

        assertEquals(ImplementOutcome.Disposition.NO_TASKS, outcome.disposition());
        assertEquals(0, loop.runs(), "no implementation turn runs when there is no task");
    }

    @Test
    @DisplayName("a missing task-breakdown artifact yields NO_TASKS")
    void missingArtifactYieldsNoTasks(@TempDir Path workspace) {
        // Oracle: AC-3.1 — there is no breakdown to implement from. A missing artifact has no task.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(workspace); // nothing written
        ImplementOutcome outcome = new GreenfieldImplementLoop(
                new ScriptedLoop(), new ScriptedVerifierFactory(), store).run(IMPLEMENT_PROMPT);

        assertEquals(ImplementOutcome.Disposition.NO_TASKS, outcome.disposition(),
                "no task-breakdown artifact means no task to implement");
    }

    // --- asLoopTurn() : ImplementOutcome -> LoopOutcome for the phase seam -----------------------

    @Test
    @DisplayName("asLoopTurn: an all-verified run completes cleanly (the driver maps it to a COMPLETED greenfield run)")
    void asLoopTurnCompletesOnAllVerified(@TempDir Path workspace) {
        // Oracle: ADR-0012 — reaching and running implementation after all gates pass is a COMPLETED
        // run. The IMPLEMENT phase's LoopTurn must return a completed LoopOutcome when every task
        // verified, so GreenfieldDriver returns COMPLETED (exit 0). Assert the adapted turn completes.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, "- T-1 (AC-1.2)\n");
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("did T-1");
        ScriptedVerifierFactory verifiers = new ScriptedVerifierFactory().then(verified(1));
        GreenfieldDriver.LoopTurn turn =
                new GreenfieldImplementLoop(loop, verifiers, store).asLoopTurn();

        LoopOutcome outcome = turn.run(IMPLEMENT_PROMPT);

        assertTrue(outcome.completed(),
                "ADR-0012: an all-verified implement phase completes cleanly (COMPLETED greenfield run)");
        assertTrue(outcome.finalTextIfPresent().orElse("").contains("T-1"),
                "the completed text reports the implemented task");
    }

    @Test
    @DisplayName("asLoopTurn: a verify-exhausted run surfaces the failing task and its output in the completed text (AC-3.4/AC-20.5)")
    void asLoopTurnSurfacesFailureOnExhausted(@TempDir Path workspace) {
        // Oracle: AC-3.4 — "stop and surface the failure rather than continuing silently"; AC-20.5 —
        // surface WITH the relevant output. The adapted turn must carry the failing task id and the
        // failure output in the developer-facing text, not silently complete with nothing.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, "- T-1 (AC-1.2)\n");
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("attempted T-1");
        ScriptedVerifierFactory verifiers = new ScriptedVerifierFactory()
                .then(VerifyOutcome.exhausted(5, result(7, "test run", "T-1 spec failed")));
        GreenfieldDriver.LoopTurn turn =
                new GreenfieldImplementLoop(loop, verifiers, store).asLoopTurn();

        LoopOutcome outcome = turn.run(IMPLEMENT_PROMPT);

        String text = outcome.finalTextIfPresent().orElse("");
        assertTrue(text.contains("T-1"),
                "AC-3.4: the surfaced text names the task that failed verification; was: " + text);
        assertTrue(text.contains("T-1 spec failed"),
                "AC-20.5: the surfaced text carries the relevant failure output; was: " + text);
    }

    @Test
    @DisplayName("asLoopTurn: a verify-exhausted run surfaces the tasks completed before the stop (AC-3.3/3.4)")
    void asLoopTurnSurfacesCompletedTasksBeforeStop(@TempDir Path workspace) {
        // Oracle: AC-3.4 (stop at the failure) + AC-3.3 (tasks verified before are complete). When a
        // task fails after earlier tasks verified, the surfaced text must report BOTH the tasks
        // completed before the stop and the failing task — so the developer sees the incremental
        // progress, not just the failure. Two tasks: T-1 verifies, T-2 exhausts.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, TWO_TASK_BREAKDOWN);
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("did T-1").thenCompleted("attempted T-2");
        ScriptedVerifierFactory verifiers = new ScriptedVerifierFactory()
                .then(verified(1))
                .then(VerifyOutcome.exhausted(5, result(1, "", "T-2 broke")));
        GreenfieldDriver.LoopTurn turn =
                new GreenfieldImplementLoop(loop, verifiers, store).asLoopTurn();

        String text = turn.run(IMPLEMENT_PROMPT).finalTextIfPresent().orElse("");

        assertTrue(text.contains("T-1"),
                "AC-3.3: the surfaced text reports the task completed before the stop; was: " + text);
        assertTrue(text.contains("T-2"),
                "AC-3.4: the surfaced text names the failing task; was: " + text);
    }

    @Test
    @DisplayName("asLoopTurn: a no-test-command run surfaces that tasks cannot be verified (AC-20.6)")
    void asLoopTurnSurfacesNoTestCommand(@TempDir Path workspace) {
        // Oracle: AC-20.6 — an absent test command is reported. Through the phase seam the developer
        // must see that the implement loop could not verify (rather than a silent completion).
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, "- T-1 (AC-1.2)\n");
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("did T-1");
        ScriptedVerifierFactory verifiers = new ScriptedVerifierFactory()
                .then(VerifyOutcome.noTestCommand());
        GreenfieldDriver.LoopTurn turn =
                new GreenfieldImplementLoop(loop, verifiers, store).asLoopTurn();

        String text = turn.run(IMPLEMENT_PROMPT).finalTextIfPresent().orElse("")
                .toLowerCase(java.util.Locale.ROOT);

        assertTrue(text.contains("test command"),
                "AC-20.6: the surfaced text reports the missing test command; was: " + text);
    }

    @Test
    @DisplayName("asLoopTurn: a no-tasks run surfaces that there is nothing to implement")
    void asLoopTurnSurfacesNoTasks(@TempDir Path workspace) {
        // Oracle: AC-3.1 operates over the breakdown's tasks; a task-less breakdown has nothing to
        // implement. The phase seam must surface that plainly.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, "# Tasks\n\nprose only\n");
        GreenfieldDriver.LoopTurn turn = new GreenfieldImplementLoop(
                new ScriptedLoop(), new ScriptedVerifierFactory(), store).asLoopTurn();

        String text = turn.run(IMPLEMENT_PROMPT).finalTextIfPresent().orElse("")
                .toLowerCase(java.util.Locale.ROOT);

        assertTrue(text.contains("no task"),
                "the surfaced text reports there is no task to implement; was: " + text);
    }

    // --- NFR-VERIFY-MAX-ITERATIONS : overConfig wires the REAL verify loop, bounded by config -----

    @Test
    @DisplayName("NFR-VERIFY-MAX-ITERATIONS: overConfig bounds the per-task remedy-driven turns by the config bound")
    void overConfigBoundsRemedyTurnsByConfig(@TempDir Path workspace) {
        // Oracle: NFR-VERIFY-MAX-ITERATIONS / AC-3.4 / AC-20.3 — each task's fix-and-retry cycle is
        // bounded by the verify loop's iteration bound (config.verifyMaxIterations()), NOT unbounded.
        // overConfig wires the REAL VerifyLoop + CommandExecutor (the reuse the directive requires).
        // With an always-failing test command ("false") and a bound of 3, the single task's verify
        // loop runs 3 attempts, invoking the remedy between them (twice), so one implementation turn
        // plus two remedy turns = exactly 3 loop runs, then it surfaces EXHAUSTED. A 4th run means the
        // bound leaked. Only the command executor's subprocess is the boundary ("false" is a trivial
        // real exit).
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, "- T-1 (AC-1.2)\n");
        ScriptedLoop loop = new ScriptedLoop()
                .thenCompleted("implement T-1")  // the task's implementation turn
                .thenCompleted("remedy 1")        // remedy after failing attempt 1
                .thenCompleted("remedy 2");       // remedy after failing attempt 2 (none after 3)
        ResolvedConfig config = configWith(new Commands(null, "false", null), 3);
        GreenfieldImplementLoop implementLoop = GreenfieldImplementLoop.overConfig(
                loop, new CommandExecutor(workspace), config, store);

        ImplementOutcome outcome = implementLoop.run(IMPLEMENT_PROMPT);

        assertEquals(ImplementOutcome.Disposition.VERIFY_EXHAUSTED, outcome.disposition(),
                "the always-failing command never verifies and the loop surfaces (AC-3.4)");
        assertEquals(3, outcome.verifyOutcome().iterations(),
                "NFR-VERIFY-MAX-ITERATIONS: exactly the configured bound (3) attempts for the task");
        assertEquals(3, loop.runs(),
                "AC-20.3 bound: one task turn + two remedy turns (remedy between the 3 attempts), "
                        + "NOT unbounded");
    }

    @Test
    @DisplayName("overConfig: a passing configured test command verifies each task end-to-end (AC-3.2/RD-10)")
    void overConfigVerifiesWithPassingCommand(@TempDir Path workspace) {
        // Oracle: AC-3.2 / RD-10 — overConfig wires the CONFIGURED test command through the real
        // VerifyLoop + CommandExecutor. A task implementation turn followed by a trivially-passing
        // command ("true", exit 0) yields VERIFIED with no remedy turn. With one task, the run is
        // ALL_VERIFIED and the task is marked complete. Exercises the production verify-reuse
        // end-to-end, not a scripted verifier.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, "- T-1 (AC-1.2)\n");
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("implement T-1");
        ResolvedConfig config = configWith(new Commands(null, "true", null), 5);
        GreenfieldImplementLoop implementLoop = GreenfieldImplementLoop.overConfig(
                loop, new CommandExecutor(workspace), config, store);

        ImplementOutcome outcome = implementLoop.run(IMPLEMENT_PROMPT);

        assertTrue(outcome.allVerified(),
                "AC-3.2/RD-10: the passing 'true' command verifies the task end-to-end");
        assertEquals(List.of("T-1"), outcome.completedTasks(), "AC-3.3: T-1 is marked complete");
        assertEquals(1, loop.runs(), "no remedy turn ran (verification passed first try)");
        assertTrue(store.read(TASKS_PATH).orElseThrow().contains("[x] T-1"),
                "AC-3.3: the verified task is marked complete in the task-breakdown artifact");
    }

    // --- CompletionStamp : the marker names the task id ------------------------------------------

    @Test
    @DisplayName("AC-3.3: the completion marker names the task id and the attempt it verified on")
    void completionStampNamesTask() {
        // Oracle: AC-3.3 — the completion is recorded in the task-breakdown artifact. The recorded
        // line must name the task id (so a reader/the next session can see WHICH task completed),
        // mirroring the approval-stamp discipline. Assert the stamp carries the task id.
        String line = GreenfieldImplementLoop.CompletionStamp.line("T-2", verified(1));
        assertTrue(line.contains("T-2"), "AC-3.3: the completion marker names the task id; was: " + line);
        assertTrue(line.contains(GreenfieldImplementLoop.CompletionStamp.MARKER),
                "the completion marker is a stable, greppable token");
    }

    // --- construction + input validation ---------------------------------------------------------

    @Test
    @DisplayName("the loop requires its loop, verifier-factory, and store seams")
    void constructorRejectsNull(@TempDir Path workspace) {
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(workspace);
        assertThrows(NullPointerException.class,
                () -> new GreenfieldImplementLoop(null, remedy -> () -> verified(1), store));
        assertThrows(NullPointerException.class,
                () -> new GreenfieldImplementLoop(new ScriptedLoop(), null, store));
        assertThrows(NullPointerException.class,
                () -> new GreenfieldImplementLoop(new ScriptedLoop(),
                        remedy -> () -> verified(1), null));
    }

    @Test
    @DisplayName("overConfig requires its loop, executor, config, and store")
    void overConfigRejectsNull(@TempDir Path workspace) {
        ResolvedConfig config = configWith(new Commands(null, "true", null), 5);
        CommandExecutor executor = new CommandExecutor(workspace);
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(workspace);
        assertThrows(NullPointerException.class,
                () -> GreenfieldImplementLoop.overConfig(null, executor, config, store));
        assertThrows(NullPointerException.class,
                () -> GreenfieldImplementLoop.overConfig(new ScriptedLoop(), null, config, store));
        assertThrows(NullPointerException.class,
                () -> GreenfieldImplementLoop.overConfig(new ScriptedLoop(), executor, null, store));
        assertThrows(NullPointerException.class,
                () -> GreenfieldImplementLoop.overConfig(new ScriptedLoop(), executor, config, null));
    }

    @Test
    @DisplayName("run rejects a null or blank prompt")
    void runRejectsBlankPrompt(@TempDir Path workspace) {
        GreenfieldImplementLoop loop = new GreenfieldImplementLoop(
                new ScriptedLoop(), remedy -> () -> verified(1),
                new GreenfieldArtifactStore(workspace));

        assertThrows(NullPointerException.class, () -> loop.run(null));
        assertThrows(IllegalArgumentException.class, () -> loop.run("  "));
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
