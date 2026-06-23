package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.tool.GreenfieldArtifactStore;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The greenfield <b>driver-authored artifact-persistence</b> contract test (T-3.2-RD-D11, DCR-2 /
 * ADR-0012 amended 2026-06-23): driving a real {@link GreenfieldDriver} over scripted phase loops,
 * the real driver-authored {@link GreenfieldDriver.PhaseArtifactWriter} (over a real
 * {@link GreenfieldArtifactStore}), and the real timestamp-recording, traceability-enforcing
 * {@link ArtifactApprovalGate}, each pre-approval phase's <em>converged</em> deliverable prose is
 * persisted to its design-doc artifact in code <b>at phase approval</b> (AC-1.2/AC-2.1) and stamped
 * with the approval timestamp (AC-1.5) — <em>without any model {@code write_artifact} tool call</em>.
 *
 * <p><b>What DCR-2 changed about persistence.</b> DCR-1 made persistence driver-guaranteed and
 * triggered the capture-and-persist on each phase's first {@code END_TURN}. Live G3 (after DCR-1)
 * showed the first turn captured the model's AC-1.1 clarifying <em>questions</em> rather than a
 * converged deliverable. DCR-2 moves the capture-and-persist trigger to <b>phase approval</b>, so
 * the <em>converged</em> multi-turn deliverable (the one the developer approves) is what is written.
 * These tests assert that contract deterministically with NO scripted tool_use: the artifact on disk
 * holds the latest-round (converged) deliverable at approval, never a first-round draft. The oracle
 * is the spec (AC-1.2/AC-2.1 persistence of the converged deliverable at approval; AC-1.5 stamp;
 * AC-2.5 over the written artifact), never the driver's own behaviour.
 *
 * <p><b>SUT and collaborators.</b> The SUT is the real {@link GreenfieldDriver} + the real
 * driver-authored {@link GreenfieldDriver.PhaseArtifactWriter} over a real {@link GreenfieldArtifactStore}
 * + the real {@link ArtifactApprovalGate} over a {@link TempDir}. The scripted seams are the
 * external boundary (the agent loop / model with {@link LoopOutcome}s, the developer's per-round
 * decision via the gate's {@link ArtifactApprovalGate.ApprovalDecision}, and the developer's
 * refining turns via the {@link GreenfieldDriver.DeveloperTurnSource}), never the SUT; the
 * substituted loops do NOT write any artifact (the driver does).
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

    /** No further developer turns (each phase approves on its first round). */
    private static GreenfieldDriver.DeveloperTurnSource noFurtherTurns() {
        return phase -> null;
    }

    /**
     * A phase-loop factory whose loops do NOT write any artifact — they only complete each round
     * with a scripted final text (the deliverable prose), one outcome per round. The driver, not the
     * loop, must persist the artifact (DCR-1/DCR-2).
     */
    private static GreenfieldDriver.PhaseLoopFactory deliverableLoops(String tasksBody) {
        return phase -> prompt -> {
            String finalText = phase == GreenfieldPhase.TASKS
                    ? tasksBody
                    : "# " + phase.name() + " deliverable authored by the model in prose\n";
            return LoopOutcome.completed(finalText);
        };
    }

    // --- DCR-1/AC-1.2/AC-2.1 : the driver persists each phase's converged prose at approval, NO tool call

    @Test
    @DisplayName("DCR-1/AC-1.2/AC-2.1: each pre-approval phase's converged deliverable prose is persisted to its artifact by the driver at approval, with no model write_artifact tool call")
    void driverPersistsEachPhaseConvergedProseAtApprovalWithoutAToolCall(@TempDir Path targetRepo) {
        // Oracle: AC-1.2 — "the agent shall persist the agreed requirements as a markdown artifact ...
        // The persistence is driver-guaranteed: the driver writes the artifact in code from the
        // phase's settled output (ADR-0012), not via a model-emitted tool call"; AC-2.1 — same for
        // design + tasks. The phase loops here write NOTHING (no scripted tool_use); the driver must,
        // on each phase approval, write that phase's deliverable prose to its artifact. With each
        // phase approved on its first round, assert each artifact on disk CONTAINS the phase's
        // deliverable prose. Expected content traces to the scripted deliverable + AC-1.2/AC-2.1, not
        // to driver code.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        String traceableTasks = "# Tasks\n- T-1 build (AC-1.2)\n- T-2 wire (AC-2.1)\n";
        ArtifactApprovalGate gate =
                new ArtifactApprovalGate(completedPhase -> true, store, () -> TS);
        GreenfieldDriver driver = new GreenfieldDriver(
                deliverableLoops(traceableTasks), writerOver(store), gate, noFurtherTurns());

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
                            + " phase's deliverable prose to its artifact (no tool call); was: "
                            + persisted);
        }
    }

    @Test
    @DisplayName("DCR-2/AC-1.5: approve = finalize — the CONVERGED (latest-round) requirements prose is what the artifact holds at approval, not a first-round draft")
    void approvalPersistsTheConvergedRequirementsNotTheFirstDraft(@TempDir Path targetRepo) {
        // Oracle: AC-1.5 (amended) — "on approval the driver captures the converged deliverable from
        // the phase conversation, persists it (AC-1.2), records this timestamp, and advances; a
        // non-approval keeps the dialogue going rather than finalizing." DCR-2 moved the
        // capture-and-persist trigger to approval so the CONVERGED deliverable is written. Declining
        // the requirements gate on round 1 (a first-draft of questions) and approving on round 2 (the
        // converged requirements), the requirements artifact the driver persisted must hold the
        // round-2 converged prose (and the stamp), NOT the round-1 first draft. The expected content
        // traces to the scripted converged round-2 deliverable + AC-1.5, not to driver internals.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        Map<GreenfieldPhase, Deque<String>> roundTexts = new EnumMap<>(GreenfieldPhase.class);
        roundTexts.put(GreenfieldPhase.REQUIREMENTS, new ArrayDeque<>(java.util.List.of(
                "# Clarifying questions — what auth? what scale?\n",
                "# CONVERGED requirements\n- US-1 shorten a URL\n")));
        GreenfieldDriver.PhaseLoopFactory loops = phase -> prompt -> {
            Deque<String> queued = roundTexts.get(phase);
            if (queued != null && !queued.isEmpty()) {
                return LoopOutcome.completed(queued.removeFirst());
            }
            String finalText = phase == GreenfieldPhase.TASKS
                    ? "# Tasks\n- T-1 build (US-1)\n"
                    : "# " + phase.name() + " deliverable\n";
            return LoopOutcome.completed(finalText);
        };
        // The gate approves requirements only on the SECOND consultation (declining round 1), and
        // approves design + tasks immediately.
        ArtifactApprovalGate gate = new ArtifactApprovalGate(
                new SecondRoundApprovesRequirements(), store, () -> TS);
        GreenfieldDriver.DeveloperTurnSource turns = new GreenfieldDriver.DeveloperTurnSource() {
            private boolean handedRequirementsRefine = false;

            @Override
            public String nextTurn(GreenfieldPhase phase) {
                if (phase == GreenfieldPhase.REQUIREMENTS && !handedRequirementsRefine) {
                    handedRequirementsRefine = true;
                    return "answers: oauth, ~1000 rps — please write the requirements";
                }
                return null;
            }
        };
        GreenfieldDriver driver = new GreenfieldDriver(loops, writerOver(store), gate, turns);

        GreenfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(GreenfieldOutcome.Disposition.COMPLETED, outcome.disposition(),
                "a converged, approved, traceable run reaches implementation");
        String requirements = store.read(GreenfieldArtifact.REQUIREMENTS.relativePath()).orElseThrow();
        assertTrue(requirements.contains("# CONVERGED requirements"),
                "AC-1.5/DCR-2: the artifact holds the CONVERGED (round-2) requirements at approval; was: "
                        + requirements);
        assertFalse(requirements.contains("Clarifying questions"),
                "AC-1.5/DCR-2: the first-round draft of questions is NOT what is finalized; was: "
                        + requirements);
        assertTrue(requirements.contains(TS),
                "AC-1.5: the approval timestamp is recorded in the converged requirements artifact; was: "
                        + requirements);
    }

    /** Approves the requirements phase only on its second consultation; approves all other phases. */
    private static final class SecondRoundApprovesRequirements
            implements ArtifactApprovalGate.ApprovalDecision {
        private int requirementsAsks = 0;

        @Override
        public boolean approve(GreenfieldPhase completedPhase) {
            if (completedPhase == GreenfieldPhase.REQUIREMENTS) {
                return ++requirementsAsks >= 2;
            }
            return true;
        }
    }

    // --- DCR-1 (kept) cross-phase continuity : later phase prompts inject approved earlier artifacts -

    @Test
    @DisplayName("DCR-1 cross-phase continuity (kept): the design phase prompt carries the approved requirements content; the tasks phase prompt carries approved requirements + design")
    void laterPhasePromptsInjectApprovedEarlierArtifacts(@TempDir Path targetRepo) {
        // Oracle: ADR-0012 (DCR-1, retained under DCR-2) — "Later phases inject the approved
        // earlier-phase artifact content into their conversation context (requirements -> design ->
        // tasks)." Capture each phase's first-round prompt; assert the DESIGN prompt carries the
        // approved requirements content and the TASKS prompt carries both approved requirements AND
        // design content. Expected fragments trace to what the driver persisted for the upstream
        // phases, per the cross-phase-injection contract, not to driver internals.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        String traceableTasks = "# Tasks\n- T-1 build (AC-1.2)\n";
        Map<GreenfieldPhase, String> firstPrompt = new EnumMap<>(GreenfieldPhase.class);
        GreenfieldDriver.PhaseLoopFactory loops = phase -> prompt -> {
            firstPrompt.putIfAbsent(phase, prompt);
            String finalText = phase == GreenfieldPhase.TASKS
                    ? traceableTasks
                    : "# " + phase.name() + " deliverable authored by the model in prose\n";
            return LoopOutcome.completed(finalText);
        };
        ArtifactApprovalGate gate =
                new ArtifactApprovalGate(completedPhase -> true, store, () -> TS);
        GreenfieldDriver driver = new GreenfieldDriver(loops, writerOver(store), gate, noFurtherTurns());

        driver.run(REQUEST);

        String designPrompt = firstPrompt.get(GreenfieldPhase.DESIGN);
        assertTrue(designPrompt.contains(
                        "# REQUIREMENTS deliverable authored by the model in prose"),
                "DCR-1: the design phase prompt injects the approved requirements content; was: "
                        + designPrompt);
        String tasksPrompt = firstPrompt.get(GreenfieldPhase.TASKS);
        assertTrue(tasksPrompt.contains("# REQUIREMENTS deliverable authored by the model in prose"),
                "DCR-1: the tasks phase prompt injects the approved requirements content; was: "
                        + tasksPrompt);
        assertTrue(tasksPrompt.contains("# DESIGN deliverable authored by the model in prose"),
                "DCR-1: the tasks phase prompt also injects the approved design content; was: "
                        + tasksPrompt);
    }

    // --- AC-2.5 / AC-1.4 : traceability is verified against the DRIVER-written converged tasks artifact

    @Test
    @DisplayName("AC-2.5/AC-1.4: an untraceable converged task breakdown (driver-written, out of turns) stops the run at the tasks gate, before implementation")
    void untraceableDriverWrittenBreakdownStopsBeforeImplementation(@TempDir Path targetRepo) {
        // Oracle: AC-2.5 (amended) — "Traceability is verified against the driver-written
        // task-breakdown artifact ... which, under the multi-turn phase dialogue (DCR-2), holds the
        // converged breakdown the developer approved"; AC-2.3/AC-1.4 — an unapproved tasks phase
        // never enters implementation (no source written). The tasks phase's converged prose here is
        // an untraceable breakdown the DRIVER persists; the gate reads that driver-written artifact,
        // finds an untraced task, refuses the approval. With no further tasks turns, the driver stops
        // awaiting approval at the tasks gate and never reaches implementation.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        String untraceableTasks = "# Tasks\n- T-1 build (AC-1.2)\n- T-2 untraced task\n";
        ArtifactApprovalGate gate =
                new ArtifactApprovalGate(completedPhase -> true, store, () -> TS);
        GreenfieldDriver driver = new GreenfieldDriver(
                deliverableLoops(untraceableTasks), writerOver(store), gate, noFurtherTurns());

        GreenfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(GreenfieldOutcome.Disposition.AWAITING_APPROVAL, outcome.disposition(),
                "AC-2.5/AC-2.3: an untraceable driver-written breakdown is not approved, so the run "
                        + "(out of tasks turns) awaits approval");
        assertEquals(GreenfieldPhase.TASKS, outcome.phase(),
                "the run stops at the tasks gate (the gate that guards implementation)");
        String tasks = store.read(GreenfieldArtifact.TASKS.relativePath()).orElseThrow();
        assertTrue(tasks.contains("T-2 untraced task"),
                "AC-2.5: the driver still persisted the (untraceable) converged breakdown — it is the "
                        + "artifact traceability was checked against; was: " + tasks);
        assertFalse(tasks.contains(TS),
                "AC-2.5: no approval is recorded for the untraceable task breakdown");
    }
}
