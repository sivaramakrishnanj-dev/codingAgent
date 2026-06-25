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
 * <p><b>Oracles trace to the US-3 implement ACs (and ADR-0012/DCR-7), never to the loop's code:</b>
 * see each test's inline oracle note. Expected values are derived from the spec body — "one task at a
 * time in breakdown order" + "verify once at end of phase, not per task" (AC-3.2), "mark it complete …
 * as it is implemented … before starting the next" (AC-3.3), "implements all tasks … does NOT
 * hard-stop at T-1" (CT-GF-8) — not from observing the implementation.
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

    // --- CompletionStamp : the marker names the task id ------------------------------------------

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
