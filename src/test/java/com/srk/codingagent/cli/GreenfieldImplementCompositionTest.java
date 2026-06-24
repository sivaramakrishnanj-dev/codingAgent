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
 * The greenfield <b>implement-loop reachability</b> contract test (T-3.3), pinned at the gate-covered
 * composition seam ({@link ToolRegistryComposer}) — the analogue of
 * {@link LiveGreenfieldRegistryCompositionTest} / {@link GreenfieldArtifactCompositionTest} for the
 * T-3.3 implement loop. It is the test that catches a live-only regression where the
 * implement-one-task-at-a-time loop was built but never reachable from the composition root (the
 * built-but-not-wired defect class), or where the per-task verify step was not wired to the configured
 * test command.
 *
 * <p><b>Why the gate-covered seam.</b> The implement loop's orchestration — read the approved
 * breakdown, implement one task at a time, verify each (reusing the T-1.4 verify loop) before the
 * next, mark each verified task complete (reusing the T-3.2 artifact store), stop on a failure
 * (AC-3.1/3.2/3.3/3.4) — is the load-bearing T-3.3 enforcement. It is assembled in
 * {@link ToolRegistryComposer#greenfieldImplementLoopTurn} (NOT the JaCoCo-excluded
 * {@link AgentLoopFactory}/{@link Main}), so a unit test pins under the coverage gate that the
 * implement loop is constructed and reachable, with the verify step wired to the configured test
 * command. This test drives the SAME composer the factory drives, over stores rooted at a
 * {@link TempDir} and a never-called Bedrock client (assembling the loop turn makes no Converse call).
 *
 * <p><b>Oracles trace to the spec, not the composer's code:</b>
 * <ul>
 *   <li><b>AC-3.1/3.2/3.3:</b> the composition-root implement loop, driven with a stub
 *       implementation turn over a real configured test command ({@code "true"}), implements each
 *       task in the approved breakdown one at a time, verifies it (the reused verify loop), and marks
 *       it complete in the task-breakdown artifact.</li>
 *   <li><b>NFR-VERIFY-MAX-ITERATIONS:</b> the per-task verify step is the configured test command
 *       (a failing {@code "false"} command surfaces a verify-exhausted stop bounded by the config),
 *       proving the verify loop is wired to config, not stubbed.</li>
 * </ul>
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

    /** A config whose test command is wired by overConfig into the per-task verify loop. */
    private static ResolvedConfig config(String testCommand) {
        return new ResolvedConfig(MODEL, PermissionMode.ASK_EVERY_TIME, "us-east-1", null,
                1, null, new ResolvedConfig.Commands(null, testCommand, null), 0.85, 16384, 3, 300,
                10, 300);
    }

    /**
     * Builds the composer EXACTLY as {@link AgentLoopFactory} builds it for the greenfield path —
     * same collaborator wiring, sessionLineage as both repoKey and originSession — over stores rooted
     * at the temp dir and a never-called Bedrock client, with the given config (whose test command the
     * implement loop's per-task verify step is wired to).
     */
    private static ToolRegistryComposer composer(Path workspace, Path storeRoot, ResolvedConfig config) {
        Supplier<String> childIds = () -> CHILD_ID;
        return new ToolRegistryComposer(
                new ModelClient(new UnusedBedrockClient()), config, workspace,
                EventLog.over(new StringWriter(), "parent"), new MemoryStore(storeRoot),
                new SessionStore(storeRoot), GrantStore.forSession(LINEAGE), alwaysApprove(),
                LINEAGE, LINEAGE, () -> TS, childIds);
    }

    // --- AC-3.1/3.2/3.3 : the composition-root implement loop runs each task one at a time --------

    @Test
    @DisplayName("AC-3.1/3.2/3.3: the composition-root implement-phase turn implements each task one at a time, verifies, and marks complete")
    void compositionRootImplementLoopRunsTasksOneAtATime(@TempDir Path workspace,
            @TempDir Path storeRoot) {
        // Oracle: AC-3.1 (one task at a time, in order) + AC-3.2 (verify via the configured command) +
        // AC-3.3 (mark complete in the artifact). The implement-phase turn the composition root builds
        // must, given the approved breakdown in the target repo's design/ dir and a trivially-passing
        // configured test command ("true"), implement T-1 then T-2 (one stub turn per task), verify
        // each (the reused verify loop over the configured command), and mark each complete in the
        // task-breakdown artifact. Proves the implement loop is REACHABLE from the composition root
        // and wired to the configured test command (not stubbed). The expected per-task turns + the
        // artifact markers trace to AC-3.1/3.3, not the composer.
        ToolRegistryComposer composer = composer(workspace, storeRoot, config("true"));
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(workspace);
        store.write(TASKS_PATH, TWO_TASK_BREAKDOWN);

        // A stub per-task implementation turn that records the task prompts it was driven with. This
        // stands in for the live IMPLEMENT-phase AgentLoop (the only substituted boundary); the verify
        // step is the REAL configured-command verify loop the composer wires.
        List<String> taskPrompts = new ArrayList<>();
        GreenfieldImplementLoop.LoopTurn stubImplementTurn = prompt -> {
            taskPrompts.add(prompt);
            return LoopOutcome.completed("implemented");
        };
        GreenfieldDriver.LoopTurn implementPhaseTurn =
                composer.greenfieldImplementLoopTurn(stubImplementTurn);

        LoopOutcome outcome = implementPhaseTurn.run(IMPLEMENT_PROMPT);

        assertTrue(outcome.completed(),
                "AC-3.2/RD-10: the passing configured 'true' command verifies each task; the run completes");
        assertEquals(2, taskPrompts.size(),
                "AC-3.1: one implementation turn ran per task (T-1, T-2), one at a time");
        assertTrue(taskPrompts.get(0).contains("T-1") && taskPrompts.get(1).contains("T-2"),
                "AC-3.1: the tasks are implemented in breakdown order; were: " + taskPrompts);
        // AC-3.3 oracle: each verified task is "marked complete" in the task-breakdown artifact. The
        // observable, spec-grounded shape is a completed-checkbox markdown task line naming the id
        // (the conventional "marked complete" form), so assert each id appears on a "[x]" completion
        // line — not against any impl-private constant.
        String artifact = store.read(TASKS_PATH).orElseThrow();
        assertTrue(artifact.contains("[x] T-1") && artifact.contains("[x] T-2"),
                "AC-3.3: each verified task is marked complete in the task-breakdown artifact; was:\n"
                        + artifact);
    }

    // --- NFR-VERIFY-MAX-ITERATIONS : the per-task verify step is the configured command ----------

    @Test
    @DisplayName("AC-3.4/NFR-VERIFY-MAX-ITERATIONS: the composition-root verify step is the configured test command (a failing command surfaces a bounded stop)")
    void compositionRootVerifyStepIsConfiguredCommand(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: AC-3.2 (verify via the CONFIGURED command) + AC-3.4 (stop and surface on failure) +
        // NFR-VERIFY-MAX-ITERATIONS (bounded). If the per-task verify step were stubbed rather than
        // wired to the configured test command, an always-failing command ("false") could not surface
        // a verify-exhausted stop. With "false" configured and a stub implementation turn, the
        // composition-root implement loop must surface (the completed text names the stopped task) —
        // proving the verify loop is wired to config. The bound (3) is the config's; a remedy turn
        // runs between the bounded attempts.
        ToolRegistryComposer composer = composer(workspace, storeRoot, config("false"));
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(workspace);
        store.write(TASKS_PATH, "- T-1 Build the parser (refs AC-1.2)\n");

        GreenfieldImplementLoop.LoopTurn stubImplementTurn = prompt -> LoopOutcome.completed("tried");
        GreenfieldDriver.LoopTurn implementPhaseTurn =
                composer.greenfieldImplementLoopTurn(stubImplementTurn);

        LoopOutcome outcome = implementPhaseTurn.run(IMPLEMENT_PROMPT);

        String text = outcome.finalTextIfPresent().orElse("");
        assertTrue(text.contains("T-1"),
                "AC-3.4: the always-failing CONFIGURED command stops the loop at T-1 and surfaces it; "
                        + "was: " + text);
        assertTrue(text.toLowerCase(java.util.Locale.ROOT).contains("verification")
                        || text.toLowerCase(java.util.Locale.ROOT).contains("did not pass"),
                "AC-3.4/AC-20.5: the surfaced text reports the verification failure; was: " + text);
    }

    @Test
    @DisplayName("greenfieldImplementLoopTurn rejects a null implementation turn")
    void implementLoopTurnRejectsNull(@TempDir Path workspace, @TempDir Path storeRoot) {
        ToolRegistryComposer composer = composer(workspace, storeRoot, config("true"));
        assertThrows(NullPointerException.class,
                () -> composer.greenfieldImplementLoopTurn(null));
    }
}
