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
 * The greenfield <b>driver-authored artifact-persistence</b> contract test (T-3.2-RD-D10, DCR-1 /
 * ADR-0012 amended 2026-06-23): driving a real {@link GreenfieldDriver} over scripted phase loops, the
 * real driver-authored {@link GreenfieldDriver.PhaseArtifactWriter} (over a real
 * {@link GreenfieldArtifactStore}), and the real timestamp-recording, traceability-enforcing
 * {@link ArtifactApprovalGate}, each pre-approval phase's END_TURN deliverable prose is persisted to
 * its design-doc artifact in code (AC-1.2/AC-2.1) and stamped with the approval timestamp (AC-1.5) —
 * <em>without any model {@code write_artifact} tool call</em>.
 *
 * <p><b>Why this test is the real, mock-stable contract the prior tests could not be.</b> The prior
 * CLI persistence tests (D7/D8/D9) scripted a {@code write_artifact} tool_use as the persistence path
 * — the exact tool_use a clean live run showed the model never emits — so they passed while live
 * persistence still failed. DCR-1 makes persistence <em>driver-guaranteed</em>: the driver writes
 * each artifact in code from the phase's settled {@link LoopOutcome#finalTextIfPresent() final text}.
 * That lets this test assert the contract deterministically with NO scripted tool_use — a phase
 * END_TURN whose final text is some prose results in the artifact file holding that prose. The oracle
 * is the spec (AC-1.2/AC-2.1 persistence-of-the-END_TURN-content; AC-1.5 stamp; AC-2.5 over the
 * written artifact), never the driver's own behaviour.
 *
 * <p><b>SUT and collaborators.</b> The SUT is the real {@link GreenfieldDriver} + the real
 * driver-authored {@link GreenfieldDriver.PhaseArtifactWriter} over a real {@link GreenfieldArtifactStore}
 * + the real {@link ArtifactApprovalGate} over a {@link TempDir}. The scripted seam is only the
 * phase-loop factory — the external boundary (the agent loop / model) is substituted with
 * {@link LoopOutcome}s, never the SUT, and the substituted loops do NOT write any artifact (the driver
 * does).
 */
class GreenfieldArtifactAuthoringTest {

    private static final String REQUEST = "build me a URL shortener";
    private static final String TS = "2026-06-23T09:00:00Z";

    /** The real driver-authored persistence seam over the target-repo store (the production shape). */
    private static GreenfieldDriver.PhaseArtifactWriter writerOver(GreenfieldArtifactStore store) {
        return new GreenfieldDriver.PhaseArtifactWriter() {
            @Override
            public void write(GreenfieldArtifact artifact, String content) {
                store.write(artifact.relativePath(), content);
            }

            @Override
            public String read(GreenfieldArtifact artifact) {
                return store.read(artifact.relativePath()).orElse("");
            }
        };
    }

    /**
     * A phase-loop factory whose loops do NOT write any artifact — they only complete each phase with
     * a per-phase final text (the deliverable prose). The driver, not the loop, must persist the
     * artifact (DCR-1). For the tasks phase a caller-supplied body is the final text so traceability
     * can be steered.
     */
    private static GreenfieldDriver.PhaseLoopFactory deliverableLoops(String tasksBody) {
        return phase -> prompt -> {
            String finalText = phase == GreenfieldPhase.TASKS
                    ? tasksBody
                    : "# " + phase.name() + " deliverable authored by the model in prose\n";
            return LoopOutcome.completed(finalText);
        };
    }

    // --- DCR-1 / AC-1.2 / AC-2.1 : the driver persists each phase's END_TURN prose, NO tool call -----

    @Test
    @DisplayName("DCR-1/AC-1.2/AC-2.1: each pre-approval phase's END_TURN deliverable prose is persisted to its artifact by the driver, with no model write_artifact tool call")
    void driverPersistsEachPhaseEndTurnProseWithoutAToolCall(@TempDir Path targetRepo) {
        // Oracle: AC-1.2 — "the agent shall persist the agreed requirements as a markdown artifact ...
        // The persistence is driver-guaranteed: the driver writes the artifact in code from the phase's
        // settled output (ADR-0012), not via a model-emitted tool call"; AC-2.1 — same for design +
        // tasks. The phase loops here write NOTHING (no scripted tool_use); the driver must, on each
        // pre-approval phase END_TURN, write that phase's final-text prose to its artifact. Assert each
        // artifact on disk CONTAINS the phase's END_TURN deliverable prose — proving the driver authored
        // it. Expected content traces to the scripted deliverable + AC-1.2/AC-2.1, not to driver code.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        String traceableTasks = "# Tasks\n- T-1 build (AC-1.2)\n- T-2 wire (AC-2.1)\n";
        ArtifactApprovalGate gate =
                new ArtifactApprovalGate(completedPhase -> true, store, () -> TS);
        GreenfieldDriver driver = new GreenfieldDriver(
                deliverableLoops(traceableTasks), writerOver(store), gate);

        GreenfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(GreenfieldOutcome.Disposition.COMPLETED, outcome.disposition(),
                "ADR-0012/AC-2.3: a fully-approved, traceable, driver-persisted run reaches implementation");
        for (GreenfieldArtifact artifact : GreenfieldArtifact.values()) {
            String persisted = store.read(artifact.relativePath()).orElseThrow();
            String expectedProse = artifact == GreenfieldArtifact.TASKS
                    ? traceableTasks
                    : "# " + artifact.phase().name() + " deliverable authored by the model in prose\n";
            assertTrue(persisted.contains(expectedProse),
                    "AC-1.2/AC-2.1 (DCR-1): the driver persisted the " + artifact.heading()
                            + " phase's END_TURN deliverable prose to its artifact (no tool call); was: "
                            + persisted);
        }
    }

    @Test
    @DisplayName("DCR-1/AC-1.2/AC-1.5: after the driver writes the requirements prose, the gate stamps the approval — the artifact holds BOTH the deliverable content AND the timestamped stamp")
    void requirementsArtifactHoldsDriverProseThenApprovalStamp(@TempDir Path targetRepo) {
        // Oracle: AC-1.2 (driver-guaranteed content persistence) + AC-1.5 ("record the approval with a
        // timestamp in the requirements artifact"). The real on-disk contract DCR-1 names: the driver
        // writes the END_TURN prose, THEN the gate appends the timestamped approval line. Assert the
        // requirements artifact contains both the prose (oracle: the scripted deliverable, AC-1.2) and
        // the boundary-clock timestamp (oracle: the clock reading, AC-1.5).
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        String traceableTasks = "# Tasks\n- T-1 build (AC-1.2)\n";
        ArtifactApprovalGate gate =
                new ArtifactApprovalGate(completedPhase -> true, store, () -> TS);
        GreenfieldDriver driver = new GreenfieldDriver(
                deliverableLoops(traceableTasks), writerOver(store), gate);

        driver.run(REQUEST);

        String requirements = store.read(GreenfieldArtifact.REQUIREMENTS.relativePath()).orElseThrow();
        assertTrue(requirements.contains(
                        "# REQUIREMENTS deliverable authored by the model in prose"),
                "AC-1.2 (DCR-1): the requirements deliverable content is driver-persisted; was: "
                        + requirements);
        assertTrue(requirements.contains(TS),
                "AC-1.5: the approval timestamp is recorded in the requirements artifact after the "
                        + "driver wrote the content; was: " + requirements);
    }

    // --- DCR-1 transcript continuity : later phase prompts inject approved earlier artifacts ---------

    @Test
    @DisplayName("DCR-1 transcript continuity: the design phase prompt carries the approved requirements content; the tasks phase prompt carries approved requirements + design")
    void laterPhasePromptsInjectApprovedEarlierArtifacts(@TempDir Path targetRepo) {
        // Oracle: ADR-0012 amended (DCR-1) — "the driver injects the approved earlier-phase artifact
        // content into each later phase's prompt (requirements -> design -> tasks), so design and tasks
        // are authored against the actual approved upstream content rather than a discontinuous fresh
        // start." Capture each phase's prompt; assert the DESIGN prompt carries the approved requirements
        // content and the TASKS prompt carries both the approved requirements AND design content. The
        // expected fragments trace to what the driver persisted for the upstream phases, per the DCR-1
        // injection contract, not to driver internals.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        String traceableTasks = "# Tasks\n- T-1 build (AC-1.2)\n";
        java.util.Map<GreenfieldPhase, String> prompts =
                new java.util.EnumMap<>(GreenfieldPhase.class);
        GreenfieldDriver.PhaseLoopFactory loops = phase -> prompt -> {
            prompts.put(phase, prompt);
            String finalText = phase == GreenfieldPhase.TASKS
                    ? traceableTasks
                    : "# " + phase.name() + " deliverable authored by the model in prose\n";
            return LoopOutcome.completed(finalText);
        };
        ArtifactApprovalGate gate =
                new ArtifactApprovalGate(completedPhase -> true, store, () -> TS);
        GreenfieldDriver driver = new GreenfieldDriver(loops, writerOver(store), gate);

        driver.run(REQUEST);

        String designPrompt = prompts.get(GreenfieldPhase.DESIGN);
        assertTrue(designPrompt.contains(
                        "# REQUIREMENTS deliverable authored by the model in prose"),
                "DCR-1: the design phase prompt injects the approved requirements content; was: "
                        + designPrompt);
        String tasksPrompt = prompts.get(GreenfieldPhase.TASKS);
        assertTrue(tasksPrompt.contains("# REQUIREMENTS deliverable authored by the model in prose"),
                "DCR-1: the tasks phase prompt injects the approved requirements content; was: "
                        + tasksPrompt);
        assertTrue(tasksPrompt.contains("# DESIGN deliverable authored by the model in prose"),
                "DCR-1: the tasks phase prompt also injects the approved design content; was: "
                        + tasksPrompt);
    }

    // --- AC-2.5 / AC-1.4 : traceability is verified against the DRIVER-written tasks artifact --------

    @Test
    @DisplayName("AC-2.5/AC-1.4: an untraceable END_TURN task breakdown (driver-written) stops the run at the tasks gate, before implementation")
    void untraceableDriverWrittenBreakdownStopsBeforeImplementation(@TempDir Path targetRepo) {
        // Oracle: AC-2.5 — "every task in the breakdown traces to at least one stated requirement.
        // Traceability is verified against the driver-written task-breakdown artifact"; AC-2.3/AC-1.4 —
        // an unapproved tasks phase never enters implementation (no source written). The tasks phase's
        // END_TURN prose here is an untraceable breakdown the DRIVER persists; the gate reads that
        // driver-written artifact, finds an untraced task, refuses the approval, so the driver stops
        // awaiting approval at the tasks gate and never reaches implementation.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        String untraceableTasks = "# Tasks\n- T-1 build (AC-1.2)\n- T-2 untraced task\n";
        ArtifactApprovalGate gate =
                new ArtifactApprovalGate(completedPhase -> true, store, () -> TS);
        GreenfieldDriver driver = new GreenfieldDriver(
                deliverableLoops(untraceableTasks), writerOver(store), gate);

        GreenfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(GreenfieldOutcome.Disposition.AWAITING_APPROVAL, outcome.disposition(),
                "AC-2.5/AC-2.3: an untraceable driver-written breakdown is not approved, so the run "
                        + "awaits approval");
        assertEquals(GreenfieldPhase.TASKS, outcome.phase(),
                "the run stops at the tasks gate (the gate that guards implementation)");
        String tasks = store.read(GreenfieldArtifact.TASKS.relativePath()).orElseThrow();
        assertTrue(tasks.contains("T-2 untraced task"),
                "AC-2.5: the driver still persisted the (untraceable) breakdown — it is the artifact "
                        + "traceability was checked against; was: " + tasks);
        assertFalse(tasks.contains(TS),
                "AC-2.5: no approval is recorded for the untraceable task breakdown");
    }
}
