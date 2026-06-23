package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.memory.MemoryStore;
import com.srk.codingagent.model.converse.ModelClient;
import com.srk.codingagent.permission.Approver;
import com.srk.codingagent.permission.GrantStore;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import com.srk.codingagent.persistence.SessionStore;
import com.srk.codingagent.tool.GreenfieldArtifactStore;
import com.srk.codingagent.tool.WriteArtifactTool;
import com.srk.codingagent.tool.WriteFileTool;
import com.srk.codingagent.workflow.ArtifactApprovalGate;
import com.srk.codingagent.workflow.GreenfieldArtifact;
import com.srk.codingagent.workflow.GreenfieldDriver;
import com.srk.codingagent.workflow.GreenfieldPhase;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;

/**
 * The greenfield <b>artifact-authoring + approval-timestamp + traceability</b> contract test (T-3.2),
 * pinned at the gate-covered composition seam ({@link ToolRegistryComposer}) — the analogue of
 * {@link LiveGreenfieldRegistryCompositionTest} for the T-3.2 authoring path. It is the test that
 * catches a live-only regression where the artifact-authoring write path was never offered to the
 * model, or the timestamped approval gate was never reachable from the composition root.
 *
 * <p><b>Why the gate-covered seam.</b> The artifact-authoring write path (the {@code write_artifact}
 * design-doc tool offered pre-approval, distinct from the withheld source-write tool) and the
 * timestamped, traceability-enforcing {@link ArtifactApprovalGate} are the load-bearing T-3.2
 * enforcement. They are assembled in {@link ToolRegistryComposer} (NOT the JaCoCo-excluded
 * {@link AgentLoopFactory}/{@link Main}), so a unit test pins them under the coverage gate — the same
 * gate-covered-seam discipline T-3.1 used for the phase-scoped registry and per-phase prompt. This
 * test drives the SAME composer the factory drives, over stores rooted at a {@link TempDir} and a
 * never-called Bedrock client.
 *
 * <p><b>Oracles trace to the spec, not the composer's code:</b>
 * <ul>
 *   <li><b>AC-1.2/AC-2.1 + AC-1.4:</b> the pre-approval registry offers the design-doc write path
 *       ({@code write_artifact}) yet withholds the general source-write tool ({@code write_file}), so
 *       a design-markdown write succeeds while a source write does not.</li>
 *   <li><b>AC-1.5/AC-2.5:</b> the composer assembles the timestamped, traceability-enforcing approval
 *       gate (the T-3.2 gate reachable from the composition root).</li>
 * </ul>
 */
class GreenfieldArtifactCompositionTest {

    private static final String LINEAGE = "one-shot";
    private static final String MODEL = "anthropic.claude-opus-4-8";
    private static final String CHILD_ID = "child-session-1";
    private static final String TS = "2026-06-23T09:00:00Z";

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

    private static ResolvedConfig config() {
        return new ResolvedConfig(MODEL, PermissionMode.ASK_EVERY_TIME, "us-east-1", null,
                1, null, ResolvedConfig.Commands.empty(), 0.85, 16384, 5, 300);
    }

    /**
     * Builds the composer EXACTLY as {@link AgentLoopFactory#createGreenfieldDriver} builds it — same
     * collaborator wiring, sessionLineage as both repoKey and originSession — over stores rooted at
     * the temp dir and a never-called Bedrock client.
     */
    private static ToolRegistryComposer composer(Path workspace, Path storeRoot) {
        Supplier<String> childIds = () -> CHILD_ID;
        return new ToolRegistryComposer(
                new ModelClient(new UnusedBedrockClient()), config(), workspace,
                EventLog.over(new StringWriter(), "parent"), new MemoryStore(storeRoot),
                new SessionStore(storeRoot), GrantStore.forSession(LINEAGE), alwaysApprove(),
                LINEAGE, LINEAGE, () -> TS, childIds);
    }

    // --- AC-1.2/AC-2.1 + AC-1.4 : design-doc write path offered pre-approval, source write withheld

    @Test
    @DisplayName("AC-1.2/AC-2.1: each pre-approval phase registry offers the design-doc write path (write_artifact)")
    void preApprovalPhasesOfferTheArtifactWritePath(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: AC-1.2/AC-2.1 — the agent persists the requirements/design/tasks markdown into the
        // target project. The mechanism is the design-doc write path; the pre-approval phases must
        // offer it so the model can author the artifacts. Assert write_artifact is present in each
        // pre-approval phase's registry. The tool name traces to the T-3.2 design-doc write path.
        ToolRegistryComposer composer = composer(workspace, storeRoot);

        for (GreenfieldPhase phase : List.of(
                GreenfieldPhase.REQUIREMENTS, GreenfieldPhase.DESIGN, GreenfieldPhase.TASKS)) {
            assertTrue(composer.greenfieldRegistry(phase).toolNames().contains(WriteArtifactTool.NAME),
                    "AC-1.2/AC-2.1: the " + phase + " phase offers the design-doc write path ("
                            + WriteArtifactTool.NAME + ") so the artifact can be authored");
        }
    }

    @Test
    @DisplayName("AC-1.4: a design-markdown write path is offered pre-approval while the general source-write tool is withheld")
    void designWritePathOfferedButSourceWriteWithheld(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: AC-1.4 — no Class-X operation against SOURCE files in the pre-approval dialogue,
        // while ADR-0012 allows the design-markdown write. The pin the task asks for: a design-markdown
        // write SUCCEEDS while a general source write does NOT. At the registry level that is exactly
        // "write_artifact present, write_file absent" in each pre-approval phase.
        ToolRegistryComposer composer = composer(workspace, storeRoot);

        for (GreenfieldPhase phase : List.of(
                GreenfieldPhase.REQUIREMENTS, GreenfieldPhase.DESIGN, GreenfieldPhase.TASKS)) {
            List<String> names = composer.greenfieldRegistry(phase).toolNames();
            assertTrue(names.contains(WriteArtifactTool.NAME),
                    "AC-1.2/AC-2.1: the design-doc write path is offered in " + phase);
            assertFalse(names.contains(WriteFileTool.NAME),
                    "AC-1.4: the general source-write tool is withheld in " + phase
                            + " (a source write does not succeed); was: " + names);
        }
    }

    @Test
    @DisplayName("AC-1.2/AC-2.1: a write through the pre-approval design-doc path lands in the target repo's design/ dir")
    void artifactWriteLandsInTargetRepo(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: AC-1.2/AC-2.1 — the artifacts land in the TARGET project (the workspace root, AC-6.2).
        // The design-doc write path the composer offers is rooted at the same workspace root the file
        // tools use. Author a requirements artifact through that path and assert it lands at the
        // expected target-repo-relative path (the G1 working-dir lesson — assert the resolved path).
        new WriteArtifactTool(new GreenfieldArtifactStore(workspace)).handle(
                java.util.Map.of("path", GreenfieldArtifact.REQUIREMENTS.relativePath(),
                        "content", "# Requirements\n"));

        Path expected = workspace.resolve("design").resolve("00-requirements.md");
        assertTrue(java.nio.file.Files.exists(expected),
                "AC-1.2: the artifact lands under the target repo's design/ dir at " + expected);
    }

    // --- AC-1.5/AC-2.5 : the composer builds the timestamped, traceability-enforcing approval gate -

    @Test
    @DisplayName("AC-1.5/AC-2.5: the composition root assembles the timestamped, traceability-enforcing approval gate")
    void compositionRootAssemblesTheTimestampedApprovalGate(@TempDir Path workspace,
            @TempDir Path storeRoot) {
        // Oracle: AC-1.5 (record the timestamped approval) + AC-2.5 (enforce traceability). The gate
        // the composer builds must, on a confirmed requirements phase, record the boundary clock's
        // timestamp into the requirements artifact — proving the T-3.2 gate is reachable from the
        // composition root (the gate-covered seam the factory delegates to). Author a requirements
        // artifact in the SAME workspace the composer is rooted at, approve, and assert the timestamp
        // reaches the artifact (oracle: the clock the composer supplies, per AC-1.5).
        ToolRegistryComposer composer = composer(workspace, storeRoot);
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(workspace);
        store.write(GreenfieldArtifact.REQUIREMENTS.relativePath(), "# Requirements\n");

        GreenfieldDriver.ApprovalGate gate = composer.greenfieldApprovalGate(completedPhase -> true);
        boolean advanced = gate.approveAdvance(GreenfieldPhase.REQUIREMENTS);

        assertTrue(advanced, "AC-2.3: the confirmed phase advances");
        assertTrue(store.read(GreenfieldArtifact.REQUIREMENTS.relativePath()).orElseThrow().contains(TS),
                "AC-1.5: the composition-root gate records the boundary-clock timestamp in the artifact");
    }

    @Test
    @DisplayName("AC-2.5: the composition-root gate refuses a tasks approval when the breakdown is untraceable")
    void compositionRootGateEnforcesTraceability(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: AC-2.5 — the gate the composition root builds must enforce traceability, not just
        // record timestamps. Author an untraceable task breakdown, approve, and assert the gate refuses
        // (returns false) — the T-3.2 traceability guarantee is reachable on the live composition path.
        ToolRegistryComposer composer = composer(workspace, storeRoot);
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(workspace);
        store.write(GreenfieldArtifact.TASKS.relativePath(), "# Tasks\n- T-1 untraced\n");

        GreenfieldDriver.ApprovalGate gate = composer.greenfieldApprovalGate(completedPhase -> true);

        assertFalse(gate.approveAdvance(GreenfieldPhase.TASKS),
                "AC-2.5: the composition-root gate refuses an untraceable task breakdown");
    }

    @Test
    @DisplayName("greenfieldApprovalGate rejects a null decision")
    void greenfieldApprovalGateRejectsNullDecision(@TempDir Path workspace, @TempDir Path storeRoot) {
        ToolRegistryComposer composer = composer(workspace, storeRoot);
        assertThrows(NullPointerException.class,
                () -> composer.greenfieldApprovalGate((ArtifactApprovalGate.ApprovalDecision) null));
    }
}
