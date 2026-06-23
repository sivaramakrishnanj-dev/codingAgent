package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.tool.GreenfieldArtifactStore;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end test that the T-3.2 {@link ArtifactApprovalGate} plugs into the T-3.1
 * {@link GreenfieldDriver} machine as designed: driving a real {@link GreenfieldDriver} over scripted
 * phase loops and the real timestamp-recording, traceability-enforcing gate, the per-phase approvals
 * are recorded into the target-repo artifacts (AC-1.5) and an untraceable task breakdown stops the
 * session before implementation (AC-2.5/AC-2.3/AC-1.4).
 *
 * <p><b>SUT and collaborators.</b> The SUT is the real {@link GreenfieldDriver} + real
 * {@link ArtifactApprovalGate} + real {@link GreenfieldArtifactStore} over a {@link TempDir}. The
 * scripted seam is the phase-loop factory — the external boundary (the agent loop / model) is
 * substituted with {@link LoopOutcome}s, never the SUT.
 */
class GreenfieldArtifactAuthoringTest {

    private static final String REQUEST = "build me a URL shortener";
    private static final String TS = "2026-06-23T09:00:00Z";

    /** A phase-loop factory that completes each phase, authoring its artifact via the store. */
    private static GreenfieldDriver.PhaseLoopFactory authoringLoops(GreenfieldArtifactStore store,
            String tasksBody) {
        return phase -> prompt -> {
            GreenfieldArtifact.forPhase(phase).ifPresent(artifact -> {
                String body = phase == GreenfieldPhase.TASKS
                        ? tasksBody
                        : "# " + artifact.heading() + "\n";
                store.write(artifact.relativePath(), body);
            });
            return LoopOutcome.completed(phase.name() + " done");
        };
    }

    @Test
    @DisplayName("AC-1.5: a fully-approved greenfield run records a timestamped approval in each authored artifact")
    void fullyApprovedRunStampsEachArtifact(@TempDir Path targetRepo) {
        // Oracle: AC-1.5 + ADR-0012 (per-phase timestamped approval). Driving the real driver over the
        // real gate with every phase approved and a traceable task breakdown, each authoring phase's
        // artifact must carry the approval timestamp after the run. Assert all three artifacts contain
        // the boundary-clock timestamp (the oracle is the clock reading, per AC-1.5).
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        String traceableTasks = "# Tasks\n- T-1 build (AC-1.2)\n- T-2 wire (AC-2.1)\n";
        ArtifactApprovalGate gate =
                new ArtifactApprovalGate(completedPhase -> true, store, () -> TS);
        GreenfieldDriver driver = new GreenfieldDriver(authoringLoops(store, traceableTasks), gate);

        GreenfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(GreenfieldOutcome.Disposition.COMPLETED, outcome.disposition(),
                "ADR-0012/AC-2.3: a fully-approved, traceable run reaches implementation");
        for (GreenfieldArtifact artifact : GreenfieldArtifact.values()) {
            assertTrue(store.read(artifact.relativePath()).orElseThrow().contains(TS),
                    "AC-1.5: the " + artifact.heading() + " artifact records the approval timestamp");
        }
    }

    @Test
    @DisplayName("AC-2.5/AC-1.4: an untraceable task breakdown stops the run at the tasks gate, before implementation")
    void untraceableBreakdownStopsBeforeImplementation(@TempDir Path targetRepo) {
        // Oracle: AC-2.5 — every task must trace; AC-2.3/AC-1.4 — an unapproved tasks phase never
        // enters implementation (no source written). With an untraceable task breakdown, the gate
        // refuses the tasks approval, so the driver stops awaiting approval at the tasks phase and
        // never reaches implementation.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        String untraceableTasks = "# Tasks\n- T-1 build (AC-1.2)\n- T-2 untraced task\n";
        ArtifactApprovalGate gate =
                new ArtifactApprovalGate(completedPhase -> true, store, () -> TS);
        GreenfieldDriver driver = new GreenfieldDriver(authoringLoops(store, untraceableTasks), gate);

        GreenfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(GreenfieldOutcome.Disposition.AWAITING_APPROVAL, outcome.disposition(),
                "AC-2.5/AC-2.3: an untraceable breakdown is not approved, so the run awaits approval");
        assertEquals(GreenfieldPhase.TASKS, outcome.phase(),
                "the run stops at the tasks gate (the gate that guards implementation)");
        assertFalse(store.read(GreenfieldArtifact.TASKS.relativePath()).orElseThrow().contains(TS),
                "AC-2.5: no approval is recorded for the untraceable task breakdown");
    }
}
