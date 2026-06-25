package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.config.ResolvedConfig.Commands;
import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.tool.CommandExecutor;
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
 * Unit tests for {@link GreenfieldImplementLoop} — the greenfield implement-every-task /
 * mark-complete-on-implementation loop (component C3 over C2, ADR-0012 implement clause amended by
 * DCR-7; US-3, AC-3.2/3.3).
 *
 * <p><b>DCR-7 (resolves D3) — verify at end of phase, mark complete on implementation.</b> The
 * greenfield IMPLEMENT phase is a flat task list with no milestone substructure, so the verify
 * boundary is end-of-phase (AC-3.2), not per task. The loop therefore implements <em>every</em> task
 * in breakdown order and marks each complete <em>as it is implemented</em> (AC-3.3) with no per-task
 * verify in the loop body — a task that is not independently testable (an early scaffold, a
 * not-yet-buildable {@code pom.xml}) is implemented without per-task verification, so a scaffold-first
 * breakdown never hard-stops at the not-yet-buildable first task (CT-GF-8). The end-of-phase verify
 * that gates the phase (AC-3.2/AC-3.4) is added by T-3.9; these tests assert what T-3.8 delivers.
 *
 * <p><b>SUT and collaborators.</b> The SUT is a real {@link GreenfieldImplementLoop} over a real
 * {@link GreenfieldArtifactStore} rooted at a {@link TempDir} (the artifact read / mark-complete path
 * is real behaviour, not mocked). The one seam is scripted, not a mocked SUT internal: the
 * {@link GreenfieldImplementLoop.LoopTurn} (the {@link com.srk.codingagent.loop.AgentLoop#run} shape)
 * is scripted with {@link LoopOutcome}s so each task's implementation turn runs without a live model.
 * This mirrors {@code BrownfieldDriverTest}'s scripted-seam discipline (the external boundary is
 * substituted, never the SUT).
 *
 * <p><b>T-3.9 (DCR-7, resolves D1) — the end-of-phase verify.</b> Once every task is implemented, the
 * loop runs the configured build/test command ONCE over the whole phase (testable-only, AC-3.2) via
 * the reused {@link com.srk.codingagent.loop.VerifyLoop} and maps the single verify outcome: a passing
 * verify is the clean phase success (ALL_IMPLEMENTED); a verify that does not pass within
 * {@code NFR-VERIFY-MAX-ITERATIONS} attempts stops and surfaces the failure (VERIFY_FAILED,
 * AC-3.4/AC-20.5 — CT-GF-7); and NO configured test command skips the end verify with a single warning
 * and terminates the phase deterministically (COMPLETE_WITH_WARNING, AC-3.6 — CT-GF-5). These verify
 * tests drive the {@code overConfig} path (the carried verify collaborators are live) over the REAL
 * {@link CommandExecutor} with trivially-passing ("true") / always-failing ("false") / absent test
 * commands — the same real-executor discipline {@code VerifyLoopTest.forConfig*} uses (no scripted
 * exit-code sequence, no live build).
 *
 * <p><b>Oracles trace to the US-3 implement ACs (and ADR-0012/DCR-7), never to the loop's code:</b>
 * see each test's inline oracle note. Expected values are derived from the spec body — "one task at a
 * time in breakdown order" + "verify once at end of phase, not per task" (AC-3.2), "mark it complete …
 * as it is implemented … before starting the next" (AC-3.3), "implements all tasks … does NOT
 * hard-stop at T-1" (CT-GF-8), "verify fails after N → stop and surface" (AC-3.4/AC-20.5, CT-GF-7),
 * "no test command → skip with one warning, terminate deterministically" (AC-3.6, CT-GF-5) — not from
 * observing the implementation.
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

    // --- AC-3.2 : tasks are implemented one at a time, in breakdown order, no per-task verify -----

    @Test
    @DisplayName("AC-3.2: the loop implements every task one at a time, in the breakdown order, with no per-task verify")
    void implementsEveryTaskOneAtATimeInBreakdownOrder(@TempDir Path workspace) {
        // Oracle: AC-3.2 (amended DCR-7) — tasks are implemented one at a time in breakdown order and
        // verified once at END OF PHASE, not after each individual task. With a two-task breakdown
        // (T-1 then T-2), the loop must drive an implementation turn for T-1 first, then T-2 — exactly
        // one turn per task (no per-task verify, no remedy), in file order, each turn naming its task.
        // The task ids and order trace to the breakdown the spec describes, not to the loop's parser.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, TWO_TASK_BREAKDOWN);
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("did T-1").thenCompleted("did T-2");
        GreenfieldImplementLoop implementLoop = new GreenfieldImplementLoop(loop, store);

        ImplementOutcome outcome = implementLoop.run(IMPLEMENT_PROMPT);

        assertEquals(ImplementOutcome.Disposition.ALL_IMPLEMENTED, outcome.disposition(),
                "AC-3.2/DCR-7: every task is implemented, so the run is all-implemented");
        assertEquals(List.of("T-1", "T-2"), outcome.implementedTasks(),
                "AC-3.2: the tasks are implemented in breakdown order (T-1 then T-2)");
        assertEquals(2, loop.runs(),
                "AC-3.2/DCR-7: exactly one implementation turn ran per task — no per-task verify, "
                        + "no remedy turn");
        assertTrue(loop.prompts.get(0).contains("T-1"),
                "AC-3.2: the first turn implements the first task in breakdown order; was: "
                        + loop.prompts.get(0));
        assertTrue(loop.prompts.get(1).contains("T-2"),
                "AC-3.2: the second turn implements the second task; was: " + loop.prompts.get(1));
    }

    @Test
    @DisplayName("AC-3.2: each task's implementation turn is scoped to its one task, not the whole breakdown")
    void eachTurnImplementsExactlyOneTask(@TempDir Path workspace) {
        // Oracle: AC-3.2 — "one task at a time". The observable, spec-grounded property is that a
        // task's implementation turn is scoped to THAT one task and not the others: the turn for T-1
        // names T-1 and does NOT direct implementing T-2 (which is a separate, later turn). Asserting
        // "names this task, not the next task" pins "one task at a time" behaviourally, without binding
        // to the impl's exact instruction wording.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, TWO_TASK_BREAKDOWN);
        ScriptedLoop loop = new ScriptedLoop().autoCompleting();

        new GreenfieldImplementLoop(loop, store).run(IMPLEMENT_PROMPT);

        String firstTurn = loop.prompts.get(0);
        assertTrue(firstTurn.contains("T-1"),
                "AC-3.2: the first turn names the task it implements (T-1); was: " + firstTurn);
        assertFalse(firstTurn.contains("T-2"),
                "AC-3.2: the first turn is scoped to T-1 only — it does not direct implementing the "
                        + "next task (T-2) in the same turn; was: " + firstTurn);
    }

    // --- CT-GF-8 : scaffold-first implements ALL tasks in order, no hard-stop at T-1 -------------

    @Test
    @DisplayName("CT-GF-8: a scaffold-first breakdown implements ALL tasks in order, with no hard-stop at the not-yet-buildable T-1")
    void scaffoldFirstImplementsAllTasksNoHardStop(@TempDir Path workspace) {
        // Oracle: CT-GF-8 + AC-3.2 + ADR-0012/DCR-7 — a scaffold-first breakdown (T-1 scaffold, T-2
        // pom, later tasks add testable code) implements ALL tasks in order and does NOT hard-stop at
        // T-1, because the not-yet-buildable scaffold cannot pass a per-task verify and per-task verify
        // is DROPPED (tasks not independently testable are implemented without per-task verification).
        // The load-bearing T-3.8 assertion (the end verify itself lands in T-3.9): the loop drives an
        // implementation turn for EVERY task in order, never stopping at T-1, and reports all of them
        // implemented. Traced to AC-3.2/CT-GF-8, not to impl behaviour.
        String scaffoldFirst = """
                # Tasks

                - T-1 Scaffold the project directory layout (AC-2.1)
                - T-2 Add the build file pom.xml (AC-2.1)
                - T-3 Implement the parser with its first test (AC-1.2)
                - T-4 Wire the CLI entry point (US-3)
                """;
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, scaffoldFirst);
        ScriptedLoop loop = new ScriptedLoop()
                .thenCompleted("scaffolded T-1")   // T-1: a scaffold — NOT independently testable
                .thenCompleted("added pom T-2")     // T-2: a pom — NOT yet buildable
                .thenCompleted("parser T-3")
                .thenCompleted("cli T-4");

        ImplementOutcome outcome = new GreenfieldImplementLoop(loop, store).run(IMPLEMENT_PROMPT);

        assertEquals(ImplementOutcome.Disposition.ALL_IMPLEMENTED, outcome.disposition(),
                "CT-GF-8: a scaffold-first run implements ALL tasks — it does NOT hard-stop at T-1");
        assertEquals(List.of("T-1", "T-2", "T-3", "T-4"), outcome.implementedTasks(),
                "CT-GF-8/AC-3.2: every task — scaffold, pom, and the later testable tasks — is "
                        + "implemented in breakdown order");
        assertEquals(4, loop.runs(),
                "CT-GF-8: an implementation turn ran for EVERY task (no per-task verify hard-stop at "
                        + "the not-yet-buildable T-1 scaffold)");
        assertTrue(loop.prompts.get(0).contains("T-1"),
                "CT-GF-8: T-1 (the scaffold) was implemented first; was: " + loop.prompts.get(0));
        assertTrue(loop.prompts.get(1).contains("T-2"),
                "CT-GF-8: T-2 (the pom) was implemented after T-1, NOT blocked by T-1 being "
                        + "unbuildable; was: " + loop.prompts.get(1));
    }

    // --- AC-3.3 : a task is marked complete on implementation, before the next starts -------------

    @Test
    @DisplayName("AC-3.3: every implemented task is marked complete in the artifact (a durable on-disk marker)")
    void everyImplementedTaskMarkedCompleteInArtifact(@TempDir Path workspace) {
        // Oracle: AC-3.3 (amended DCR-7) — "When a task is implemented, the agent shall mark it
        // complete in the task-breakdown artifact as it is implemented (a durable on-disk completion
        // marker)". With both tasks implemented, the task-breakdown artifact must carry a "marked
        // complete" line for each. The spec-grounded observable for "marked complete" is a completed-
        // checkbox markdown task line naming the id ("[x] T-n", the conventional completed-task form,
        // which AC-3.3 says is read back on resume), so assert each id appears on a completion line —
        // derived from AC-3.3, not from any impl constant.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, TWO_TASK_BREAKDOWN);
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("did T-1").thenCompleted("did T-2");

        new GreenfieldImplementLoop(loop, store).run(IMPLEMENT_PROMPT);

        String artifact = store.read(TASKS_PATH).orElseThrow();
        assertTrue(artifact.contains("[x] T-1"),
                "AC-3.3: T-1 is marked complete in the task-breakdown artifact; was:\n" + artifact);
        assertTrue(artifact.contains("[x] T-2"),
                "AC-3.3: T-2 is also marked complete on implementation; was:\n" + artifact);
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

        new GreenfieldImplementLoop(observingLoop, store).run(IMPLEMENT_PROMPT);

        assertEquals(List.of(true), t1MarkedWhenT2Started,
                "AC-3.3: T-1 is marked complete in the artifact BEFORE the T-2 turn begins");
    }

    @Test
    @DisplayName("AC-3.3: the durable on-disk marker carries the task id and the [x] checkbox shape so it can be read back on resume")
    void completionMarkerIsDurableAndReadableBack(@TempDir Path workspace) {
        // Oracle: AC-3.3 — completion markers are a "durable on-disk completion marker" and are "read
        // back on resume so a re-entry skips already-completed tasks". The spec-grounded shape is a
        // completed-checkbox task line naming the id ("- [x] <taskId>"), readable back from disk after
        // the run. Assert the persisted artifact carries, on disk, the [x]-checkbox + id shape for the
        // implemented task. Traced to AC-3.3, not to any impl-private constant.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, "- T-1 Build the parser (AC-1.2)\n");
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("did T-1");

        new GreenfieldImplementLoop(loop, store).run(IMPLEMENT_PROMPT);

        // Read back from a FRESH store over the same workspace — proving the marker is durable on disk,
        // not just in-memory, so the T-3.10 intra-IMPLEMENT resume can read it back (AC-3.3/AC-7.6).
        String onDisk = new GreenfieldArtifactStore(workspace).read(TASKS_PATH).orElseThrow();
        assertTrue(onDisk.lines().anyMatch(line -> line.contains("[x]") && line.contains("T-1")),
                "AC-3.3: a durable on-disk [x]-checkbox marker naming the implemented task id is "
                        + "readable back; was:\n" + onDisk);
    }

    // --- breakdown with no recognizable task -----------------------------------------------------

    @Test
    @DisplayName("a breakdown with no recognizable task yields NO_TASKS (nothing to implement)")
    void noRecognizableTaskYieldsNoTasks(@TempDir Path workspace) {
        // Oracle: AC-3.2 operates over the breakdown's tasks; a breakdown with no stable-id task has
        // nothing to implement one-at-a-time. The loop must report NO_TASKS and drive no turn.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, "# Tasks\n\nProse, no tasks.\n");
        ScriptedLoop loop = new ScriptedLoop();

        ImplementOutcome outcome = new GreenfieldImplementLoop(loop, store).run(IMPLEMENT_PROMPT);

        assertEquals(ImplementOutcome.Disposition.NO_TASKS, outcome.disposition());
        assertEquals(0, loop.runs(), "no implementation turn runs when there is no task");
    }

    @Test
    @DisplayName("a missing task-breakdown artifact yields NO_TASKS")
    void missingArtifactYieldsNoTasks(@TempDir Path workspace) {
        // Oracle: AC-3.2 — there is no breakdown to implement from. A missing artifact has no task.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(workspace); // nothing written
        ImplementOutcome outcome =
                new GreenfieldImplementLoop(new ScriptedLoop(), store).run(IMPLEMENT_PROMPT);

        assertEquals(ImplementOutcome.Disposition.NO_TASKS, outcome.disposition(),
                "no task-breakdown artifact means no task to implement");
    }

    // --- asLoopTurn() : ImplementOutcome -> LoopOutcome for the phase seam -----------------------

    @Test
    @DisplayName("asLoopTurn: an all-implemented run completes cleanly (the driver maps it to a COMPLETED greenfield run)")
    void asLoopTurnCompletesOnAllImplemented(@TempDir Path workspace) {
        // Oracle: ADR-0012 — reaching and running implementation after all gates pass is a COMPLETED
        // run. The IMPLEMENT phase's LoopTurn must return a completed LoopOutcome when every task is
        // implemented, so GreenfieldDriver returns COMPLETED (exit 0) — no regression to the driver's
        // terminal mapping (T-3.8 item 6). Assert the adapted turn completes and reports the tasks.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, TWO_TASK_BREAKDOWN);
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("did T-1").thenCompleted("did T-2");
        GreenfieldDriver.LoopTurn turn = new GreenfieldImplementLoop(loop, store).asLoopTurn();

        LoopOutcome outcome = turn.run(IMPLEMENT_PROMPT);

        assertTrue(outcome.completed(),
                "ADR-0012: an all-implemented implement phase completes cleanly (COMPLETED run)");
        String text = outcome.finalTextIfPresent().orElse("");
        assertTrue(text.contains("T-1") && text.contains("T-2"),
                "the completed text reports the implemented tasks; was: " + text);
    }

    @Test
    @DisplayName("asLoopTurn: a no-tasks run completes cleanly and surfaces that there is nothing to implement")
    void asLoopTurnSurfacesNoTasks(@TempDir Path workspace) {
        // Oracle: AC-3.2 operates over the breakdown's tasks; a task-less breakdown has nothing to
        // implement. The phase seam must complete cleanly (no regression to the COMPLETED mapping) and
        // surface that plainly.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, "# Tasks\n\nprose only\n");
        GreenfieldDriver.LoopTurn turn =
                new GreenfieldImplementLoop(new ScriptedLoop(), store).asLoopTurn();

        LoopOutcome outcome = turn.run(IMPLEMENT_PROMPT);

        assertTrue(outcome.completed(), "a no-tasks implement phase still completes cleanly");
        String text = outcome.finalTextIfPresent().orElse("").toLowerCase(java.util.Locale.ROOT);
        assertTrue(text.contains("no task"),
                "the surfaced text reports there is no task to implement; was: " + text);
    }

    // --- overConfig : the production composition reaches the loop end-to-end ----------------------

    @Test
    @DisplayName("AC-3.2/3.3: overConfig composes a loop that implements each task and marks it complete on implementation")
    void overConfigImplementsAndMarksEachTask(@TempDir Path workspace) {
        // Oracle: AC-3.2 (one task at a time, in order) + AC-3.3 (mark complete on implementation) +
        // DCR-7 (no per-task verify). overConfig wires the production collaborators (the end-of-phase
        // verify's CommandExecutor + config are carried for T-3.9 but the per-task loop body uses
        // neither). A two-task breakdown is implemented one turn per task and each is marked complete
        // in the artifact — the production composition reaches the loop end-to-end. Traced to
        // AC-3.2/3.3, not to impl behaviour.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, TWO_TASK_BREAKDOWN);
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("did T-1").thenCompleted("did T-2");
        ResolvedConfig config = configWith(new Commands(null, "true", null), 3);
        GreenfieldImplementLoop implementLoop = GreenfieldImplementLoop.overConfig(
                loop, new CommandExecutor(workspace), config, store);

        ImplementOutcome outcome = implementLoop.run(IMPLEMENT_PROMPT);

        assertTrue(outcome.allImplemented(),
                "AC-3.2/DCR-7: the overConfig-composed loop implements every task (no per-task verify)");
        assertEquals(List.of("T-1", "T-2"), outcome.implementedTasks(),
                "AC-3.2: the tasks are implemented in breakdown order");
        assertEquals(2, loop.runs(), "AC-3.2/DCR-7: one turn per task, no per-task verify/remedy turn");
        String artifact = store.read(TASKS_PATH).orElseThrow();
        assertTrue(artifact.contains("[x] T-1") && artifact.contains("[x] T-2"),
                "AC-3.3: each implemented task is marked complete in the artifact; was:\n" + artifact);
    }

    // --- T-3.9 end-of-phase verify : VERIFIED -> all-implemented terminal (AC-3.2) ----------------

    @Test
    @DisplayName("AC-3.2: a passing end-of-phase verify (one configured run after the last task) yields the all-implemented success")
    void endOfPhaseVerifyPassesYieldsAllImplemented(@TempDir Path workspace) {
        // Oracle: AC-3.2 (amended DCR-7) — when all tasks are implemented the agent verifies them ONCE
        // at end of phase via the configured build/test command, not per task. With a trivially-passing
        // configured command ("true", exit 0) the single end-of-phase verify passes, so the run is the
        // clean ALL_IMPLEMENTED success carrying the verified verify. The expected disposition + verified
        // verify trace to AC-3.2, not to the loop's code.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, TWO_TASK_BREAKDOWN);
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("did T-1").thenCompleted("did T-2");
        ResolvedConfig config = configWith(new Commands(null, "true", null), 5);
        GreenfieldImplementLoop implementLoop = GreenfieldImplementLoop.overConfig(
                loop, new CommandExecutor(workspace), config, store);

        ImplementOutcome outcome = implementLoop.run(IMPLEMENT_PROMPT);

        assertEquals(ImplementOutcome.Disposition.ALL_IMPLEMENTED, outcome.disposition(),
                "AC-3.2: a passing end-of-phase verify is the clean phase success");
        assertEquals(List.of("T-1", "T-2"), outcome.implementedTasks(),
                "AC-3.2: every task was implemented in breakdown order before the one end verify");
        assertTrue(outcome.verifyOutcomeIfPresent().orElseThrow().verified(),
                "AC-3.2: the single end-of-phase verify passed");
        assertEquals(2, loop.runs(),
                "AC-3.2: exactly one turn per task ran; the passing end verify drove no remedy turn");
    }

    @Test
    @DisplayName("AC-3.2: the end-of-phase verify runs ONCE over the whole phase, not once per task")
    void endOfPhaseVerifyRunsOnceNotPerTask(@TempDir Path workspace) {
        // Oracle: AC-3.2 — "verify them once at the end of the phase ... not after each individual
        // task." The verify boundary is end-of-phase. With a configured command that always fails
        // ("false") and a bound of 1, the verify is attempted exactly ONCE (one end-of-phase verify of
        // bound 1), NOT once per task (which would be two attempts for two tasks). Asserting iterations
        // == 1 (the bound), not == task count, pins "once at end of phase, not per task".
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, TWO_TASK_BREAKDOWN);
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("did T-1").thenCompleted("did T-2");
        ResolvedConfig config = configWith(new Commands(null, "false", null), 1);
        GreenfieldImplementLoop implementLoop = GreenfieldImplementLoop.overConfig(
                loop, new CommandExecutor(workspace), config, store);

        ImplementOutcome outcome = implementLoop.run(IMPLEMENT_PROMPT);

        assertEquals(ImplementOutcome.Disposition.VERIFY_FAILED, outcome.disposition(),
                "AC-3.2/AC-3.4: the always-failing end verify did not pass within the bound");
        assertEquals(1, outcome.verifyOutcome().iterations(),
                "AC-3.2: the end verify ran ONCE (bound 1) over the whole phase — not once per task");
    }

    // --- CT-GF-7 : end-of-phase verify failure retries bounded then stop-and-surface --------------

    @Test
    @DisplayName("CT-GF-7/AC-3.4/AC-20.5: an end-of-phase verify that never passes retries bounded by NFR-VERIFY-MAX-ITERATIONS then stops and surfaces")
    void endOfPhaseVerifyFailureBoundedThenSurface(@TempDir Path workspace) {
        // Oracle: CT-GF-7 + AC-3.4/AC-20.5 + NFR-VERIFY-MAX-ITERATIONS — when the END-OF-PHASE verify
        // fails, the agent retries bounded by NFR-VERIFY-MAX-ITERATIONS and then stops and surfaces, and
        // the bound applies to the SINGLE end-of-phase verify (not a per-task verify). With an
        // always-failing configured command ("false") and a bound of 3, the end verify is attempted
        // EXACTLY 3 times then surfaces VERIFY_FAILED carrying the failing run's output (AC-20.5). The
        // bound (3) and the surfaced disposition trace to CT-GF-7/AC-3.4, not to the loop's code.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, TWO_TASK_BREAKDOWN);
        ScriptedLoop loop = new ScriptedLoop().autoCompleting();
        ResolvedConfig config = configWith(new Commands(null, "false", null), 3);
        GreenfieldImplementLoop implementLoop = GreenfieldImplementLoop.overConfig(
                loop, new CommandExecutor(workspace), config, store);

        ImplementOutcome outcome = implementLoop.run(IMPLEMENT_PROMPT);

        assertEquals(ImplementOutcome.Disposition.VERIFY_FAILED, outcome.disposition(),
                "CT-GF-7/AC-3.4: an end verify that never passes stops and surfaces (not all-implemented)");
        assertEquals(List.of("T-1", "T-2"), outcome.implementedTasks(),
                "CT-GF-7: every task was implemented; only the end-of-phase verify did not pass");
        assertEquals(3, outcome.verifyOutcome().iterations(),
                "CT-GF-7/NFR-VERIFY-MAX-ITERATIONS: the end verify retried up to exactly the configured "
                        + "bound (3) before stopping — the bound is on the single end-of-phase verify");
        assertFalse(outcome.verifyOutcome().verified(),
                "AC-3.4: the surfaced verify never passed");
        assertTrue(outcome.verifyOutcome().resultIfPresent().isPresent(),
                "AC-20.5: the surfaced outcome carries the failing run's relevant output");
    }

    @Test
    @DisplayName("CT-GF-7/AC-20.3: a failing end-of-phase verify drives a bounded remedy turn between attempts, then surfaces")
    void endOfPhaseVerifyFailureDrivesBoundedRemedyTurns(@TempDir Path workspace) {
        // Oracle: CT-GF-7 + AC-20.3 + NFR-VERIFY-MAX-ITERATIONS — the end-of-phase verify's bounded
        // retry feeds each failure back into a remedy turn (AC-20.3) between attempts; with a bound of 3
        // and an always-failing command, the verify is attempted 3 times and the remedy drives a turn
        // between attempts (2 remedy turns for 3 attempts, never after the last). Counting the loop's
        // total turns (2 per-task + 2 remedy = 4) pins that the remedy drove additional turns and the
        // retry is bounded (it did not loop forever). The 2-task + 2-remedy split traces to AC-20.3 +
        // the bound, not to the loop's code.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, TWO_TASK_BREAKDOWN);
        ScriptedLoop loop = new ScriptedLoop().autoCompleting();
        ResolvedConfig config = configWith(new Commands(null, "false", null), 3);
        GreenfieldImplementLoop implementLoop = GreenfieldImplementLoop.overConfig(
                loop, new CommandExecutor(workspace), config, store);

        ImplementOutcome outcome = implementLoop.run(IMPLEMENT_PROMPT);

        assertEquals(ImplementOutcome.Disposition.VERIFY_FAILED, outcome.disposition());
        assertEquals(4, loop.runs(),
                "AC-20.3: 2 per-task implementation turns + 2 remedy turns (one between each of the 3 "
                        + "bounded end-verify attempts, never after the last) = 4 turns; the bounded retry "
                        + "does not loop forever");
    }

    // --- CT-GF-5 : no configured test command -> COMPLETE_WITH_WARNING terminal (AC-3.6) ----------

    @Test
    @DisplayName("CT-GF-5/AC-3.6: no configured test command skips the end verify with a warning and terminates as complete-with-warning")
    void noTestCommandSkipsEndVerifyCompleteWithWarning(@TempDir Path workspace) {
        // Oracle: CT-GF-5 + AC-3.6 (new, DCR-7) — with no configured test command the agent skips the
        // end-of-phase verification, having implemented and marked complete every task, and terminates
        // the phase deterministically as a complete-with-warning terminal success (NOT a hard-stop, NOT
        // a re-loop). With a null test command, every task is still implemented and marked complete, and
        // the outcome is COMPLETE_WITH_WARNING carrying the NO_TEST_COMMAND verify (nothing ran). Traced
        // to AC-3.6/CT-GF-5, not to the loop's code.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, TWO_TASK_BREAKDOWN);
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("did T-1").thenCompleted("did T-2");
        ResolvedConfig config = configWith(new Commands(null, null, null), 5);
        GreenfieldImplementLoop implementLoop = GreenfieldImplementLoop.overConfig(
                loop, new CommandExecutor(workspace), config, store);

        ImplementOutcome outcome = implementLoop.run(IMPLEMENT_PROMPT);

        assertEquals(ImplementOutcome.Disposition.COMPLETE_WITH_WARNING, outcome.disposition(),
                "AC-3.6: no test command -> the end verify is skipped and the phase completes-with-warning");
        assertEquals(List.of("T-1", "T-2"), outcome.implementedTasks(),
                "AC-3.6: every task was implemented and marked complete before the skipped verify");
        assertEquals(com.srk.codingagent.loop.VerifyOutcome.Kind.NO_TEST_COMMAND,
                outcome.verifyOutcome().kind(),
                "AC-3.6: no command ran — the skipped (no-test-command) verify outcome is carried");
        assertEquals(2, loop.runs(),
                "AC-3.6: only the 2 per-task turns ran — the skipped end verify drove no remedy turn");
        String artifact = store.read(TASKS_PATH).orElseThrow();
        assertTrue(artifact.contains("[x] T-1") && artifact.contains("[x] T-2"),
                "AC-3.6/CT-GF-5: every task was marked complete on implementation; was:\n" + artifact);
    }

    @Test
    @DisplayName("CT-GF-5/AC-3.6: the no-test-command run is a terminal COMPLETED loop outcome (exit 0) — not a hard-stop, not a re-loop")
    void noTestCommandIsTerminalCompletedLoopOutcome(@TempDir Path workspace) {
        // Oracle: CT-GF-5 + AC-3.6 — the no-test-command outcome is TERMINAL (exit 0, complete-with-
        // warning); the driver/REPL must NOT re-prompt into a fresh implement attempt (the D1 livelock
        // fix). The IMPLEMENT-phase LoopTurn the driver runs must therefore map the no-test-command run
        // to a COMPLETED LoopOutcome (so the driver's COMPLETED mapping treats it as a finished turn),
        // whose final text carries the single warning. Asserting completed() + the warning in the text
        // pins "terminal complete-with-warning", traced to AC-3.6, not the loop's code.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, TWO_TASK_BREAKDOWN);
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("did T-1").thenCompleted("did T-2");
        ResolvedConfig config = configWith(new Commands(null, null, null), 5);
        GreenfieldDriver.LoopTurn turn = GreenfieldImplementLoop.overConfig(
                loop, new CommandExecutor(workspace), config, store).asLoopTurn();

        LoopOutcome outcome = turn.run(IMPLEMENT_PROMPT);

        assertTrue(outcome.completed(),
                "AC-3.6: a no-test-command run is a terminal COMPLETED loop outcome (exit 0), so the "
                        + "driver/REPL treats it as a finished turn and does not re-enter implement");
        String text = outcome.finalTextIfPresent().orElse("").toLowerCase(java.util.Locale.ROOT);
        assertTrue(text.contains("no test command") && text.contains("warning"),
                "AC-3.6: the terminal text carries the single warning that the verify was skipped; was: "
                        + text);
    }

    @Test
    @DisplayName("CT-GF-7/G4: a verify-failed run surfaces the failure in a COMPLETED loop outcome (exit 0, verification signal distinct from process exit)")
    void verifyFailedSurfacesInCompletedLoopOutcome(@TempDir Path workspace) {
        // Oracle: CT-GF-7 + AC-20.5 + exit-code contract G4 — when the end-of-phase verify fails the
        // agent stops and surfaces the failure WITH the relevant output, but the verification signal is
        // distinct from the agent-process exit (G4), the same stance the brownfield verify-exhaustion
        // takes. So the IMPLEMENT-phase LoopTurn maps a VERIFY_FAILED run to a COMPLETED LoopOutcome
        // (exit 0) whose final text surfaces the verification failure and its output. Traced to
        // CT-GF-7/AC-20.5/G4, not the loop's code.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, TWO_TASK_BREAKDOWN);
        ScriptedLoop loop = new ScriptedLoop().autoCompleting();
        ResolvedConfig config = configWith(new Commands(null, "false", null), 2);
        GreenfieldDriver.LoopTurn turn = GreenfieldImplementLoop.overConfig(
                loop, new CommandExecutor(workspace), config, store).asLoopTurn();

        LoopOutcome outcome = turn.run(IMPLEMENT_PROMPT);

        assertTrue(outcome.completed(),
                "G4: the verification failure is surfaced in a COMPLETED outcome (exit 0); the "
                        + "verification signal is distinct from the agent-process exit");
        String text = outcome.finalTextIfPresent().orElse("").toLowerCase(java.util.Locale.ROOT);
        assertTrue(text.contains("verification"),
                "AC-20.5: the surfaced text reports the end-of-phase verification did not pass; was: "
                        + text);
    }

    // --- T-3.10 (CT-GF-6) : intra-IMPLEMENT resume skips completed tasks, resumes at first incomplete

    private static final String THREE_TASK_BREAKDOWN = """
            # Tasks

            - T-1 Build the parser (refs AC-1.2)
            - T-2 Wire the CLI (refs US-3)
            - T-3 Persist results (refs AC-2.1)
            """;

    /** Appends the durable per-task completion marker T-3.8 writes (the read-back side T-3.10 reads). */
    private static void markCompletedOnDisk(GreenfieldArtifactStore store, String... taskIds) {
        for (String taskId : taskIds) {
            store.appendLine(TASKS_PATH, GreenfieldImplementLoop.CompletionStamp.line(taskId));
        }
    }

    @Test
    @DisplayName("CT-GF-6/AC-7.6/AC-3.3: a re-entry over a partially-completed breakdown resumes at the first incomplete task — completed tasks are skipped, it does NOT restart at T-1")
    void resumeOverPartiallyCompletedBreakdownResumesAtFirstIncomplete(@TempDir Path workspace) {
        // Oracle: CT-GF-6 + AC-7.6 (IMPLEMENT facet) + AC-3.3 — "a greenfield re-entry whose
        // reconstructed phase is IMPLEMENT over a partially-completed breakdown reads back the per-task
        // completion markers and resumes at the FIRST INCOMPLETE TASK, terminating — it does NOT restart
        // at the first task (T-1). Completed tasks (marked complete on implementation, AC-3.3) are
        // skipped." With T-1 already marked complete on disk (a prior interrupted run) and T-2/T-3 not,
        // the re-entry must implement ONLY T-2 then T-3 (the first incomplete onward), never re-running
        // T-1. The skipped id (T-1) and the resume point (T-2 first) trace to CT-GF-6/AC-7.6, not to the
        // loop's code.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, THREE_TASK_BREAKDOWN);
        markCompletedOnDisk(store, "T-1"); // a prior run implemented + marked T-1, then was interrupted
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("did T-2").thenCompleted("did T-3");

        ImplementOutcome outcome = new GreenfieldImplementLoop(loop, store).run(IMPLEMENT_PROMPT);

        assertEquals(2, loop.runs(),
                "CT-GF-6: only the two INCOMPLETE tasks (T-2, T-3) are implemented — the completed T-1 "
                        + "is skipped (no re-run), so the re-entry does NOT restart at T-1");
        assertTrue(loop.prompts.get(0).contains("T-2"),
                "CT-GF-6/AC-7.6: the re-entry resumes at the FIRST INCOMPLETE task (T-2), not T-1; "
                        + "was: " + loop.prompts.get(0));
        assertFalse(loop.prompts.stream().anyMatch(p -> p.contains("Implement task T-1")),
                "CT-GF-6: the already-completed T-1 is not re-implemented on the re-entry");
        assertTrue(loop.prompts.get(1).contains("T-3"),
                "CT-GF-6: after the first incomplete task, the remaining incomplete tasks follow in "
                        + "breakdown order (T-3); was: " + loop.prompts.get(1));
    }

    @Test
    @DisplayName("CT-GF-6/AC-3.2: a partially-completed re-entry reports the whole completed phase in breakdown order (the skipped completed task plus the newly implemented ones)")
    void resumeReportsWholeCompletedPhaseInBreakdownOrder(@TempDir Path workspace) {
        // Oracle: CT-GF-6 + AC-3.2 — the end-of-phase verify gates the PHASE, so the terminal outcome
        // reflects the whole completed phase, not only the tasks this resumed run touched. With T-1
        // already complete and T-2/T-3 implemented this run, every planned task is now complete, in
        // breakdown order (T-1, T-2, T-3). The reported list traces to the breakdown's order + AC-3.2's
        // phase-level gating, not to the loop's code.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, THREE_TASK_BREAKDOWN);
        markCompletedOnDisk(store, "T-1");
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("did T-2").thenCompleted("did T-3");

        ImplementOutcome outcome = new GreenfieldImplementLoop(loop, store).run(IMPLEMENT_PROMPT);

        assertEquals(ImplementOutcome.Disposition.ALL_IMPLEMENTED, outcome.disposition(),
                "CT-GF-6: with every planned task now complete the run is all-implemented");
        assertEquals(List.of("T-1", "T-2", "T-3"), outcome.implementedTasks(),
                "CT-GF-6/AC-3.2: the terminal outcome reports the whole completed phase in breakdown "
                        + "order — the skipped already-complete T-1 plus the newly implemented T-2, T-3");
    }

    @Test
    @DisplayName("CT-GF-6/AC-7.6: a re-entry that resumes mid-breakdown does NOT double-count the markers as planned tasks (planned enumeration is not polluted)")
    void resumeDoesNotDoubleCountMarkersAsPlannedTasks(@TempDir Path workspace) {
        // Oracle: CT-GF-6 / AC-7.6 critical subtlety — the completion markers (- [x] T-1 Implemented)
        // live in the SAME artifact and a checked-checkbox line is itself recognized as a task; a naive
        // enumeration would count T-1 twice (its planned line + its marker) and could re-run it. The
        // spec requires the completed task to be SKIPPED. With T-1 and T-2 marked complete on disk, only
        // the single remaining incomplete task (T-3) is implemented — exactly once — proving the markers
        // are read as "already done", not enumerated as extra planned work. Traced to AC-7.6/CT-GF-6.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, THREE_TASK_BREAKDOWN);
        markCompletedOnDisk(store, "T-1", "T-2");
        ScriptedLoop loop = new ScriptedLoop().thenCompleted("did T-3");

        ImplementOutcome outcome = new GreenfieldImplementLoop(loop, store).run(IMPLEMENT_PROMPT);

        assertEquals(1, loop.runs(),
                "CT-GF-6: exactly ONE incomplete task (T-3) is implemented — the two completed tasks are "
                        + "skipped and the markers are NOT mistaken for extra planned tasks to re-run");
        assertTrue(loop.prompts.get(0).contains("T-3"),
                "CT-GF-6: the resume point is the first incomplete task (T-3); was: " + loop.prompts.get(0));
        assertEquals(List.of("T-1", "T-2", "T-3"), outcome.implementedTasks(),
                "CT-GF-6: the completed phase is the three planned tasks in order — each listed once, "
                        + "not duplicated by its marker line");
    }

    @Test
    @DisplayName("CT-GF-6/AC-3.3: a fresh (uninterrupted) run has no completion markers, so nothing is skipped and every task is implemented")
    void freshRunWithNoMarkersImplementsEveryTask(@TempDir Path workspace) {
        // Oracle: AC-3.3/AC-7.6 — completion markers are read back "so a re-entry skips already-completed
        // tasks". A FRESH run over a breakdown with NO markers has nothing to skip, so every planned task
        // is implemented in order — the read-back is a no-op on the fresh path (no regression to the
        // T-3.8/T-3.9 implement-every-task behaviour). Traced to AC-3.3 (the read-back is for re-entries).
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, THREE_TASK_BREAKDOWN);
        ScriptedLoop loop = new ScriptedLoop()
                .thenCompleted("did T-1").thenCompleted("did T-2").thenCompleted("did T-3");

        ImplementOutcome outcome = new GreenfieldImplementLoop(loop, store).run(IMPLEMENT_PROMPT);

        assertEquals(3, loop.runs(),
                "AC-3.3: a fresh run with no markers skips nothing — every planned task is implemented");
        assertEquals(List.of("T-1", "T-2", "T-3"), outcome.implementedTasks(),
                "AC-3.3: with no markers to skip, every planned task is implemented in breakdown order");
    }

    @Test
    @DisplayName("CT-GF-6/AC-3.2: a re-entry over a FULLY-completed breakdown implements nothing and terminates — it does not restart at T-1")
    void resumeOverFullyCompletedBreakdownImplementsNothing(@TempDir Path workspace) {
        // Oracle: CT-GF-6 + AC-7.6 + AC-3.2 — when every planned task already carries a completion marker
        // (a re-entry over a fully-implemented breakdown), there is nothing left to implement: the loop
        // skips all tasks and terminates, it does NOT restart at the first task (T-1). With T-1/T-2/T-3
        // all marked complete on disk, NO implementation turn runs, and the outcome reports the whole
        // already-complete phase. The "implements nothing / does not restart" behaviour traces to
        // CT-GF-6/AC-7.6, not the loop's code.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, THREE_TASK_BREAKDOWN);
        markCompletedOnDisk(store, "T-1", "T-2", "T-3");
        ScriptedLoop loop = new ScriptedLoop(); // empty: any implementation turn would throw

        ImplementOutcome outcome = new GreenfieldImplementLoop(loop, store).run(IMPLEMENT_PROMPT);

        assertEquals(0, loop.runs(),
                "CT-GF-6: a fully-completed breakdown implements NOTHING on re-entry — no task is "
                        + "re-run, and the loop does not restart at T-1");
        assertEquals(List.of("T-1", "T-2", "T-3"), outcome.implementedTasks(),
                "CT-GF-6/AC-3.2: the terminal outcome reports the whole already-complete phase in order");
    }

    @Test
    @DisplayName("CT-GF-6/AC-3.2: a FULLY-completed re-entry still runs the end-of-phase verify ONCE over the already-complete phase (the verify gates the phase, not each task)")
    void fullyCompletedReEntryStillRunsEndOfPhaseVerifyOnce(@TempDir Path workspace) {
        // Oracle: AC-3.2 (amended DCR-7) — "when all tasks in the greenfield breakdown have been
        // implemented, the agent shall verify them ONCE at the end of the phase"; the verify gates the
        // PHASE, not each task. On a re-entry where every planned task is already complete, nothing is
        // implemented, but the end-of-phase verify still runs once over the already-complete phase
        // (consistent with a single uninterrupted run). With a trivially-passing configured command and
        // no implementation turns, the run is the clean ALL_IMPLEMENTED success carrying the verified
        // verify. The "verify still runs once" behaviour traces to AC-3.2's phase-level gating.
        GreenfieldArtifactStore store = storeWithBreakdown(workspace, TWO_TASK_BREAKDOWN);
        markCompletedOnDisk(store, "T-1", "T-2");
        ScriptedLoop loop = new ScriptedLoop(); // no implementation turns: every task already complete
        ResolvedConfig config = configWith(new Commands(null, "true", null), 5);
        GreenfieldImplementLoop implementLoop = GreenfieldImplementLoop.overConfig(
                loop, new CommandExecutor(workspace), config, store);

        ImplementOutcome outcome = implementLoop.run(IMPLEMENT_PROMPT);

        assertEquals(0, loop.runs(),
                "AC-7.6: nothing was implemented — every planned task was already complete on re-entry");
        assertEquals(ImplementOutcome.Disposition.ALL_IMPLEMENTED, outcome.disposition(),
                "AC-3.2: the end-of-phase verify gates the phase and still runs on a fully-complete "
                        + "re-entry; the trivially-passing verify is the clean phase success");
        assertTrue(outcome.verifyOutcomeIfPresent().orElseThrow().verified(),
                "AC-3.2: the single end-of-phase verify ran once over the already-complete phase and "
                        + "passed");
        assertEquals(List.of("T-1", "T-2"), outcome.implementedTasks(),
                "AC-3.2: the verified phase reports its complete tasks in breakdown order");
    }

    // --- CompletionStamp : the marker names the task id, and is read back by its own shape ---------

    @Test
    @DisplayName("AC-3.3: the completion marker names the task id with the [x] checkbox shape")
    void completionStampNamesTask() {
        // Oracle: AC-3.3 — the completion is recorded in the task-breakdown artifact as a durable
        // marker read back on resume. The recorded line must name the task id (so a reader / the
        // T-3.10 resume can see WHICH task completed) and carry the conventional completed-checkbox
        // "[x]" shape. Assert the stamp carries the task id and the checkbox.
        String line = GreenfieldImplementLoop.CompletionStamp.line("T-2");
        assertTrue(line.contains("T-2"),
                "AC-3.3: the completion marker names the task id; was: " + line);
        assertTrue(line.contains("[x]"),
                "AC-3.3: the marker carries the [x] completed-checkbox shape so it reads back; was: "
                        + line);
        assertTrue(line.contains(GreenfieldImplementLoop.CompletionStamp.MARKER),
                "the completion marker is a stable, greppable token");
    }

    @Test
    @DisplayName("AC-3.3/T-3.10: the marker line() writes is read back by isCompletionLine/taskIdOf for the SAME id (write+read round-trip)")
    void completionStampRoundTripsWriteAndRead() {
        // Oracle: AC-3.3 (amended DCR-7) — "Completion markers are read back on resume". The write side
        // (line) and the read-back side (isCompletionLine / taskIdOf) must agree on the same shape so a
        // resume skips exactly the tasks the loop marked: the line written for an id must be recognized
        // as a completion line FOR THAT id. Round-tripping the id traces to AC-3.3's write+read marker
        // contract, not to the impl's regex.
        String written = GreenfieldImplementLoop.CompletionStamp.line("T-3.4");
        assertTrue(GreenfieldImplementLoop.CompletionStamp.isCompletionLine(written),
                "AC-3.3: the line the loop writes is recognized as a completion-marker line; was: "
                        + written);
        assertEquals("T-3.4", GreenfieldImplementLoop.CompletionStamp.taskIdOf(written).orElseThrow(),
                "AC-3.3: the read-back recovers the SAME task id the marker was written for");
    }

    @Test
    @DisplayName("AC-3.3/T-3.10: a planned task line ([x] or [ ] without the Implemented marker) is NOT mistaken for a completion marker")
    void completionStampDoesNotMatchPlannedTaskLines() {
        // Oracle: AC-3.3 + the CT-GF-6 critical subtlety — the marker is distinguished by the WHOLE
        // shape (the checked box PLUS the trailing "Implemented" token), not by the [x] checkbox alone,
        // because a real planned task may itself be a checked/unchecked checkbox line that
        // TaskTraceability recognizes as a task. A planned "- [x] T-1 Build the parser" (checked, but no
        // Implemented marker) and a planned "- [ ] T-2 Wire the CLI" must NOT read as completion
        // markers, or a resume would wrongly skip an unimplemented task. Traced to AC-3.3 + CT-GF-6.
        assertFalse(GreenfieldImplementLoop.CompletionStamp.isCompletionLine(
                        "- [x] T-1 Build the parser (refs AC-1.2)"),
                "AC-3.3: a checked planned-task line without the 'Implemented' marker is NOT a completion "
                        + "marker — the box alone does not make a marker");
        assertTrue(GreenfieldImplementLoop.CompletionStamp.taskIdOf(
                        "- [ ] T-2 Wire the CLI (refs US-3)").isEmpty(),
                "AC-3.3: an unchecked planned-task line is not a completion marker");
        assertTrue(GreenfieldImplementLoop.CompletionStamp.taskIdOf("# Tasks").isEmpty(),
                "a non-task line is not a completion marker");
    }

    // --- construction + input validation ---------------------------------------------------------

    @Test
    @DisplayName("the loop requires its loop and store seams")
    void constructorRejectsNull(@TempDir Path workspace) {
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(workspace);
        assertThrows(NullPointerException.class,
                () -> new GreenfieldImplementLoop(null, store));
        assertThrows(NullPointerException.class,
                () -> new GreenfieldImplementLoop(new ScriptedLoop(), null));
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
                new ScriptedLoop(), new GreenfieldArtifactStore(workspace));

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
                300,
                10,
                300);
    }
}
