package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.memory.MemoryStore;
import com.srk.codingagent.model.converse.ModelClient;
import com.srk.codingagent.permission.Approver;
import com.srk.codingagent.permission.GrantStore;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import com.srk.codingagent.persistence.SessionStore;
import com.srk.codingagent.tool.GreenfieldArtifactStore;
import com.srk.codingagent.workflow.GreenfieldArtifact;
import com.srk.codingagent.workflow.GreenfieldDriver;
import com.srk.codingagent.workflow.GreenfieldImplementLoop;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;

/**
 * The greenfield <b>implement-loop reachability</b> contract test (T-3.3, reworked by T-3.8/DCR-7),
 * pinned at the gate-covered composition seam ({@link ToolRegistryComposer}) — the analogue of
 * {@link LiveGreenfieldRegistryCompositionTest} / {@link GreenfieldArtifactCompositionTest} for the
 * implement loop. It is the test that catches a live-only regression where the implement-loop was
 * built but never reachable from the composition root (the built-but-not-wired defect class).
 *
 * <p><b>DCR-7 (resolves D3) — verify at end of phase, mark complete on implementation.</b> The
 * greenfield IMPLEMENT phase is a flat task list with no milestone substructure, so the verify
 * boundary is end-of-phase (AC-3.2), not per task. The composition-root implement loop therefore
 * implements <em>every</em> task in breakdown order and marks each complete <em>as it is
 * implemented</em> (AC-3.3) with no per-task verify in the loop body. The end-of-phase verify that
 * gates the phase (AC-3.2/AC-3.4) is wired by T-3.9; this test asserts what T-3.8 delivers — the
 * implement loop is reachable from the composition root and implements-and-marks each task.
 *
 * <p><b>Why the gate-covered seam.</b> The implement loop's orchestration — read the approved
 * breakdown, implement every task one at a time, mark each complete on implementation (reusing the
 * T-3.2 artifact store), no per-task hard-stop (AC-3.2/3.3, DCR-7) — is the load-bearing enforcement.
 * It is assembled in {@link ToolRegistryComposer#greenfieldImplementLoopTurn} (NOT the
 * JaCoCo-excluded {@link AgentLoopFactory}/{@link Main}), so a unit test pins under the coverage gate
 * that the implement loop is constructed and reachable from the composition root. This test drives the
 * SAME composer the factory drives, over stores rooted at a {@link TempDir} and a never-called Bedrock
 * client (assembling the loop turn makes no Converse call).
 *
 * <p><b>Oracles trace to the spec, not the composer's code:</b> AC-3.2 (one task at a time, in order;
 * verify once at end of phase, not per task) + AC-3.3 (mark complete on implementation in the
 * task-breakdown artifact) + ADR-0012/DCR-7.
 */
class GreenfieldImplementCompositionTest {

    private static final String LINEAGE = "one-shot";
    private static final String MODEL = "anthropic.claude-opus-4-8";
    private static final String CHILD_ID = "child-session-1";
    private static final String TS = "2026-06-23T09:00:00Z";
    private static final String TASKS_PATH = GreenfieldArtifact.TASKS.relativePath();
    private static final String IMPLEMENT_PROMPT = "Proceed with the implement phase.";

    private static final String TWO_TASK_BREAKDOWN = """
            # Tasks

            - T-1 Build the parser (refs AC-1.2)
            - T-2 Wire the CLI (refs US-3)
            """;

    /** A {@link BedrockRuntimeClient} that never expects a call (no model turn is exercised). */
    private static final class UnusedBedrockClient implements BedrockRuntimeClient {
        @Override
        public ConverseResponse converse(ConverseRequest request) {
            throw new IllegalStateException("no model call is expected when assembling the seam");
        }

        @Override
        public String serviceName() {
            return "bedrock-runtime";
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static Approver alwaysApprove() {
        return req -> PermissionDecisionOutcome.APPROVE;
    }

    /** A config whose collaborators overConfig carries for the end-of-phase verify (T-3.9). */
    private static ResolvedConfig config(String testCommand) {
        return new ResolvedConfig(MODEL, PermissionMode.ASK_EVERY_TIME, "us-east-1", null,
                1, null, new ResolvedConfig.Commands(null, testCommand, null), 0.85, 16384, 3, 300,
                10, 300);
    }

    /**
     * Builds the composer EXACTLY as {@link AgentLoopFactory} builds it for the greenfield path —
     * same collaborator wiring, sessionLineage as both repoKey and originSession — over stores rooted
     * at the temp dir and a never-called Bedrock client, with the given config.
     */
    private static ToolRegistryComposer composer(Path workspace, Path storeRoot, ResolvedConfig config) {
        Supplier<String> childIds = () -> CHILD_ID;
        return new ToolRegistryComposer(
                new ModelClient(new UnusedBedrockClient()), config, workspace,
                EventLog.over(new StringWriter(), "parent"), new MemoryStore(storeRoot),
                new SessionStore(storeRoot), GrantStore.forSession(LINEAGE), alwaysApprove(),
                LINEAGE, LINEAGE, () -> TS, childIds);
    }

    // --- AC-3.2/3.3 : the composition-root implement loop implements every task and marks complete -

    @Test
    @DisplayName("AC-3.2/3.3: the composition-root implement-phase turn implements every task one at a time and marks each complete on implementation")
    void compositionRootImplementLoopImplementsAndMarksEachTask(@TempDir Path workspace,
            @TempDir Path storeRoot) {
        // Oracle: AC-3.2 (one task at a time, in order; verify once at end of phase, not per task) +
        // AC-3.3 (mark complete on implementation) + ADR-0012/DCR-7. The implement-phase turn the
        // composition root builds must, given the approved breakdown in the target repo's design/ dir,
        // implement T-1 then T-2 (one stub turn per task, no per-task verify) and mark each complete in
        // the task-breakdown artifact. Proves the implement loop is REACHABLE from the composition
        // root. The expected per-task turns + the artifact markers trace to AC-3.2/3.3, not the
        // composer.
        ToolRegistryComposer composer = composer(workspace, storeRoot, config("true"));
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(workspace);
        store.write(TASKS_PATH, TWO_TASK_BREAKDOWN);

        // A stub per-task implementation turn that records the task prompts it was driven with. This
        // stands in for the live IMPLEMENT-phase AgentLoop (the only substituted boundary).
        List<String> taskPrompts = new ArrayList<>();
        GreenfieldImplementLoop.LoopTurn stubImplementTurn = prompt -> {
            taskPrompts.add(prompt);
            return LoopOutcome.completed("implemented");
        };
        GreenfieldDriver.LoopTurn implementPhaseTurn =
                composer.greenfieldImplementLoopTurn(stubImplementTurn);

        LoopOutcome outcome = implementPhaseTurn.run(IMPLEMENT_PROMPT);

        assertTrue(outcome.completed(),
                "AC-3.2/DCR-7: every task is implemented (no per-task verify); the run completes");
        assertEquals(2, taskPrompts.size(),
                "AC-3.2: one implementation turn ran per task (T-1, T-2), one at a time");
        assertTrue(taskPrompts.get(0).contains("T-1") && taskPrompts.get(1).contains("T-2"),
                "AC-3.2: the tasks are implemented in breakdown order; were: " + taskPrompts);
        // AC-3.3 oracle: each implemented task is "marked complete" in the task-breakdown artifact. The
        // observable, spec-grounded shape is a completed-checkbox markdown task line naming the id (the
        // conventional "marked complete" form, read back on resume), so assert each id appears on a
        // "[x]" completion line — not against any impl-private constant.
        String artifact = store.read(TASKS_PATH).orElseThrow();
        assertTrue(artifact.contains("[x] T-1") && artifact.contains("[x] T-2"),
                "AC-3.3: each implemented task is marked complete in the task-breakdown artifact; "
                        + "was:\n" + artifact);
    }

    // --- CT-GF-8 : a scaffold-first composition-root run implements all tasks, no hard-stop at T-1 -

    @Test
    @DisplayName("CT-GF-8: a scaffold-first breakdown through the composition root implements ALL tasks in order, no hard-stop at the not-yet-buildable T-1")
    void compositionRootScaffoldFirstImplementsAllTasks(@TempDir Path workspace,
            @TempDir Path storeRoot) {
        // Oracle: CT-GF-8 + AC-3.2 + ADR-0012/DCR-7 — a scaffold-first breakdown (T-1 scaffold, T-2
        // pom, later tasks add testable code) implements ALL tasks in order and does NOT hard-stop at
        // T-1, because per-task verify is DROPPED (a not-yet-buildable scaffold is implemented without
        // per-task verification). Through the composition root, the loop must drive a turn for EVERY
        // task and complete — no per-task verify hard-stop at the unbuildable T-1/T-2. The end verify
        // itself is T-3.9; the load-bearing T-3.8 assertion is "all tasks implemented, no hard-stop".
        ToolRegistryComposer composer = composer(workspace, storeRoot, config("true"));
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(workspace);
        store.write(TASKS_PATH, """
                # Tasks

                - T-1 Scaffold the project directory layout (AC-2.1)
                - T-2 Add the build file pom.xml (AC-2.1)
                - T-3 Implement the parser with its first test (AC-1.2)
                """);

        List<String> taskPrompts = new ArrayList<>();
        GreenfieldImplementLoop.LoopTurn stubImplementTurn = prompt -> {
            taskPrompts.add(prompt);
            return LoopOutcome.completed("implemented");
        };
        GreenfieldDriver.LoopTurn implementPhaseTurn =
                composer.greenfieldImplementLoopTurn(stubImplementTurn);

        LoopOutcome outcome = implementPhaseTurn.run(IMPLEMENT_PROMPT);

        assertTrue(outcome.completed(),
                "CT-GF-8: a scaffold-first run implements ALL tasks and completes — no hard-stop at T-1");
        assertEquals(3, taskPrompts.size(),
                "CT-GF-8: an implementation turn ran for EVERY task (scaffold, pom, and the testable "
                        + "task) — no per-task verify hard-stop; were: " + taskPrompts);
        assertTrue(taskPrompts.get(0).contains("T-1") && taskPrompts.get(1).contains("T-2")
                        && taskPrompts.get(2).contains("T-3"),
                "CT-GF-8/AC-3.2: every task implemented in breakdown order; were: " + taskPrompts);
        String artifact = store.read(TASKS_PATH).orElseThrow();
        assertTrue(artifact.contains("[x] T-1") && artifact.contains("[x] T-2")
                        && artifact.contains("[x] T-3"),
                "AC-3.3: every task — including the not-yet-buildable scaffold and pom — is marked "
                        + "complete on implementation; was:\n" + artifact);
    }

    @Test
    @DisplayName("greenfieldImplementLoopTurn rejects a null implementation turn")
    void implementLoopTurnRejectsNull(@TempDir Path workspace, @TempDir Path storeRoot) {
        ToolRegistryComposer composer = composer(workspace, storeRoot, config("true"));
        assertThrows(NullPointerException.class,
                () -> composer.greenfieldImplementLoopTurn(null));
    }
}
