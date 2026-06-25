package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.tool.GreenfieldArtifactStore;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>CT-GF-1 (positive) end-to-end</b>: a fresh {@code --mode greenfield} run over a target project
 * whose {@code design/00-requirements.md} is present <b>and AC-1.5 approval-stamped</b> reconstructs
 * its phase-state <em>from the on-disk artifacts</em> and <b>resumes at the design phase</b> — it
 * does <b>not</b> restart at requirements; symmetrically, an unstamped/absent requirements artifact
 * starts (or re-enters) requirements (retry-in-place) (DCR-3, AC-7.6, ADR-0012, AC-1.5).
 *
 * <p><b>Why this test, on top of {@link GreenfieldPhaseStateTest} / {@link GreenfieldDriverResumeTest}.</b>
 * Those pin the re-derivation logic and the driver's honouring of it with scripted probes. This test
 * wires the <em>real</em> {@link GreenfieldArtifactStore} over a {@link TempDir} target repo, using
 * the SAME production resume probe shape {@code AgentLoopFactory.createGreenfieldDriver} wires
 * ({@link GreenfieldArtifactStore#isApprovalStamped(String)} for the stamp signal + {@code read} for
 * the upstream content) — so it pins the full on-disk path CT-GF-1 specifies, including that the
 * resume re-derivation and the D13 clobber guard key on the SAME AC-1.5 stamp (one durable on-disk
 * fact). The on-disk stamp is written exactly as the production {@link ArtifactApprovalGate} writes
 * it (via {@link ApprovalStamp#line}), never hand-faked, so a drift between the stamp written and
 * the stamp detected would fail here.
 *
 * <p><b>SUT and collaborators.</b> The SUT is a real {@link GreenfieldDriver} over a real
 * {@link GreenfieldArtifactStore} (the on-disk re-derivation probe + the driver's artifact writer)
 * with scripted phase loops + a scripted approval gate (the external model + developer boundary),
 * never a mock of the SUT.
 */
class GreenfieldResumeContractTest {

    private static final String REQUEST = "build me a URL shortener service";
    private static final String TS = "2026-06-23T09:00:00Z";

    /** The production resume probe shape: stamp signal + content read over the target-repo store. */
    private static GreenfieldPhaseState.Probe resumeProbeOver(GreenfieldArtifactStore store) {
        return new GreenfieldPhaseState.Probe() {
            @Override
            public boolean isApproved(GreenfieldArtifact artifact) {
                return store.isApprovalStamped(artifact.relativePath());
            }

            @Override
            public String content(GreenfieldArtifact artifact) {
                return store.read(artifact.relativePath()).orElse("");
            }
        };
    }

    /** The production driver-authored persistence seam over the target-repo store. */
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

    /** Records the phases run and per-phase prompts; each round completes with scripted prose. */
    private static final class RecordingPhaseLoops implements GreenfieldDriver.PhaseLoopFactory {
        private final List<GreenfieldPhase> phasesRun = new ArrayList<>();
        private final Map<GreenfieldPhase, List<String>> promptsByPhase =
                new EnumMap<>(GreenfieldPhase.class);

        @Override
        public GreenfieldDriver.LoopTurn loopFor(GreenfieldPhase phase) {
            return prompt -> {
                phasesRun.add(phase);
                promptsByPhase.computeIfAbsent(phase, p -> new ArrayList<>()).add(prompt);
                // A traceable tasks body so the production AC-2.5 gate does not refuse the tasks phase.
                String body = phase == GreenfieldPhase.TASKS
                        ? "# Tasks\n- T-1 build (AC-1.1)\n"
                        : "# " + phase.name() + " deliverable\n";
                return LoopOutcome.completed(body);
            };
        }
    }

    /** Builds a resume-aware driver over the real target-repo store (the production wiring shape). */
    private static GreenfieldDriver resumeDriverOver(GreenfieldArtifactStore store,
            RecordingPhaseLoops loops) {
        ArtifactApprovalGate gate = new ArtifactApprovalGate(completedPhase -> true, store, () -> TS);
        GreenfieldDriver.PhaseStateReconstructor reconstructor =
                () -> GreenfieldPhaseState.reconstruct(resumeProbeOver(store));
        return new GreenfieldDriver(
                loops, writerOver(store), gate, phase -> null, reconstructor);
    }

    // --- CT-GF-1 (positive) : a stamped requirements artifact resumes at design over disk ---------

    @Test
    @DisplayName("CT-GF-1: a fresh greenfield run over a project with a present AND AC-1.5-stamped design/00-requirements.md resumes at DESIGN, not requirements")
    void stampedRequirementsOnDiskResumesAtDesign(@TempDir java.nio.file.Path targetRepo) {
        // Oracle: CT-GF-1 / AC-7.6 / AC-1.5 — "a fresh --mode greenfield run over a target project
        // whose design/00-requirements.md is present and AC-1.5 approval-stamped reconstructs
        // phase-state from the on-disk artifacts and resumes at the design phase — it does NOT restart
        // at requirements". A PRIOR finalized run leaves an approved + AC-1.5-stamped requirements
        // artifact on disk (the stamp written exactly as the production gate writes it). A fresh run's
        // driver re-derives the phase-state from disk and resumes at DESIGN. Expected resume phase
        // traces to CT-GF-1 / AC-7.6, and the on-disk stamp is the real AC-1.5 stamp.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        store.write(GreenfieldArtifact.REQUIREMENTS.relativePath(),
                "# Requirements\n\nThe APPROVED requirements deliverable.\n");
        store.appendLine(GreenfieldArtifact.REQUIREMENTS.relativePath(),
                ApprovalStamp.line(GreenfieldArtifact.REQUIREMENTS, TS));

        RecordingPhaseLoops loops = new RecordingPhaseLoops();
        GreenfieldDriver freshRun = resumeDriverOver(store, loops);

        freshRun.run(REQUEST);

        assertEquals(GreenfieldPhase.DESIGN, loops.phasesRun.get(0),
                "CT-GF-1: the fresh run reconstructs phase-state from the stamped on-disk requirements "
                        + "and resumes at DESIGN");
        assertFalse(loops.phasesRun.contains(GreenfieldPhase.REQUIREMENTS),
                "CT-GF-1: the fresh run does NOT restart at requirements — the approved requirements "
                        + "phase is not re-run");
    }

    @Test
    @DisplayName("CT-GF-1 (symmetric): an unstamped/absent requirements artifact starts (or re-enters) requirements (retry-in-place)")
    void unstampedRequirementsOnDiskReEntersRequirements(@TempDir java.nio.file.Path targetRepo) {
        // Oracle: CT-GF-1 / AC-7.6 — "symmetrically, an unstamped/absent requirements artifact starts
        // (or re-enters) requirements (retry-in-place)". The target repo has NO design/ artifacts (a
        // fresh project) — or an interrupted requirements phase left an unstamped draft. The fresh
        // run's driver re-derives requirements as the resume phase and runs requirements first (opened
        // with the developer's request). Expected resume phase traces to CT-GF-1's symmetric clause.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        // A half-written, UNSTAMPED requirements draft (a transient interruption left no stamp).
        store.write(GreenfieldArtifact.REQUIREMENTS.relativePath(),
                "# Requirements (draft — interrupted, no approval stamp)\n");

        RecordingPhaseLoops loops = new RecordingPhaseLoops();
        GreenfieldDriver freshRun = resumeDriverOver(store, loops);

        freshRun.run(REQUEST);

        assertEquals(GreenfieldPhase.REQUIREMENTS, loops.phasesRun.get(0),
                "CT-GF-1: an unstamped requirements artifact re-enters requirements (retry-in-place)");
    }

    @Test
    @DisplayName("CT-GF-1 retry-in-place: a transient interruption mid-design (approved requirements, unstamped design) re-enters DESIGN on the next run")
    void interruptedMidDesignReEntersDesign(@TempDir java.nio.file.Path targetRepo) {
        // Oracle: AC-7.6 — "an interrupted mid-phase (whose artifact is unstamped) is re-entered, so a
        // transient failure is retryable in place". Approved + stamped requirements, plus an unstamped
        // design draft (a transient model-backend failure mid-design wrote a draft but never stamped
        // it). The next run re-derives DESIGN as the resume phase (retry-in-place) and runs DESIGN
        // first WITHOUT re-running requirements. Expected resume phase traces to AC-7.6 retry-in-place.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        store.write(GreenfieldArtifact.REQUIREMENTS.relativePath(), "# Requirements\n");
        store.appendLine(GreenfieldArtifact.REQUIREMENTS.relativePath(),
                ApprovalStamp.line(GreenfieldArtifact.REQUIREMENTS, TS));
        store.write(GreenfieldArtifact.DESIGN.relativePath(),
                "# Design (interrupted draft — no approval stamp)\n");

        RecordingPhaseLoops loops = new RecordingPhaseLoops();
        GreenfieldDriver nextRun = resumeDriverOver(store, loops);

        nextRun.run(REQUEST);

        assertEquals(GreenfieldPhase.DESIGN, loops.phasesRun.get(0),
                "AC-7.6: the interrupted mid-design phase is re-entered (retry-in-place)");
        assertFalse(loops.phasesRun.contains(GreenfieldPhase.REQUIREMENTS),
                "AC-7.6: the approved requirements phase is not re-run on the retry");
    }

    @Test
    @DisplayName("CT-GF-1 (DCR-1 kept): on disk-driven resume at design, the design prompt injects the on-disk approved requirements content")
    void diskResumeInjectsApprovedUpstream(@TempDir java.nio.file.Path targetRepo) {
        // Oracle: ADR-0012 (DCR-1 kept, cited by DCR-3) — on resume the already-approved phases'
        // artifacts are loaded so the resume phase sees the approved upstream. Resuming at DESIGN over
        // an on-disk approved requirements artifact, the DESIGN prompt must carry that on-disk
        // requirements content. Expected fragment traces to the on-disk approved content + the DCR-1
        // cross-phase-injection contract.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        store.write(GreenfieldArtifact.REQUIREMENTS.relativePath(),
                "ON-DISK-APPROVED-REQUIREMENTS-CONTENT\n");
        store.appendLine(GreenfieldArtifact.REQUIREMENTS.relativePath(),
                ApprovalStamp.line(GreenfieldArtifact.REQUIREMENTS, TS));

        RecordingPhaseLoops loops = new RecordingPhaseLoops();
        GreenfieldDriver freshRun = resumeDriverOver(store, loops);

        freshRun.run(REQUEST);

        String designPrompt = loops.promptsByPhase.get(GreenfieldPhase.DESIGN).get(0);
        assertTrue(designPrompt.contains("ON-DISK-APPROVED-REQUIREMENTS-CONTENT"),
                "DCR-1 (resume): the design prompt injects the on-disk approved requirements content; "
                        + "was: " + designPrompt);
    }

    // --- CT-GF-6 (DCR-7) : intra-IMPLEMENT resume over disk skips completed, resumes at first incomplete

    /** Stamps the given artifact with the production AC-1.5 approval stamp (never hand-faked). */
    private static void writeAndStamp(GreenfieldArtifactStore store, GreenfieldArtifact artifact,
            String body) {
        store.write(artifact.relativePath(), body);
        store.appendLine(artifact.relativePath(), ApprovalStamp.line(artifact, TS));
    }

    /**
     * A phase-loop factory that routes the terminal IMPLEMENT phase to the REAL
     * {@link GreenfieldImplementLoop} over the target-repo store (so the on-disk read-back + skip is
     * exercised end-to-end), and routes any pre-approval phase to a stub that records it ran. On a
     * resume that reconstructs to IMPLEMENT (all three pre-approval artifacts stamped) the pre-approval
     * stubs never run; if one did, the recorded phase would expose a regression.
     */
    private static final class ImplementBackedPhaseLoops implements GreenfieldDriver.PhaseLoopFactory {
        private final List<GreenfieldPhase> phasesRun = new ArrayList<>();
        private final List<String> implementTaskPrompts = new ArrayList<>();
        private final GreenfieldImplementLoop.LoopTurn implementTurns;
        private final GreenfieldArtifactStore store;

        ImplementBackedPhaseLoops(GreenfieldArtifactStore store,
                GreenfieldImplementLoop.LoopTurn implementTurns) {
            this.store = store;
            this.implementTurns = implementTurns;
        }

        @Override
        public GreenfieldDriver.LoopTurn loopFor(GreenfieldPhase phase) {
            phasesRun.add(phase);
            if (phase == GreenfieldPhase.IMPLEMENT) {
                // Real implement loop: it reads the planned tasks + the on-disk completion markers and
                // implements only the incomplete ones (the per-task implementation turn is the scripted
                // seam, recording which task each turn names).
                GreenfieldImplementLoop.LoopTurn recordingTurns = prompt -> {
                    implementTaskPrompts.add(prompt);
                    return implementTurns.run(prompt);
                };
                return new GreenfieldImplementLoop(recordingTurns, store).asLoopTurn();
            }
            return prompt -> LoopOutcome.completed("# " + phase.name() + " deliverable\n");
        }
    }

    @Test
    @DisplayName("CT-GF-6/AC-7.6/AC-3.3: a fresh greenfield run reconstructing to IMPLEMENT over a partially-completed breakdown resumes at the first incomplete task — completed tasks skipped, NOT a restart at T-1")
    void reconstructedImplementOverPartialBreakdownResumesAtFirstIncomplete(
            @TempDir java.nio.file.Path targetRepo) {
        // Oracle: CT-GF-6 / AC-7.6 (IMPLEMENT facet) / AC-3.3 — "a greenfield re-entry whose
        // reconstructed phase is IMPLEMENT over a partially-completed breakdown reads back the per-task
        // completion markers and resumes at the FIRST INCOMPLETE TASK, terminating — it does NOT restart
        // at the first task (T-1). Completed tasks are skipped." A PRIOR run approved + stamped all three
        // pre-approval artifacts (so reconstruct resolves to IMPLEMENT, the phase boundary, T-3.4), then
        // implemented + marked T-1 before being interrupted. A fresh run re-derives IMPLEMENT from disk
        // and the implement loop reads the marker back, resuming at T-2 (skipping T-1). The on-disk
        // stamps + markers are written exactly as production writes them. Expected resume-at-T-2 +
        // skip-T-1 trace to CT-GF-6 / AC-7.6, end-to-end over disk.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        writeAndStamp(store, GreenfieldArtifact.REQUIREMENTS, "# Requirements\n");
        writeAndStamp(store, GreenfieldArtifact.DESIGN, "# Design\n");
        writeAndStamp(store, GreenfieldArtifact.TASKS,
                "# Tasks\n- T-1 Parser (AC-1.2)\n- T-2 CLI (US-3)\n- T-3 Persist (AC-2.1)\n");
        // A prior interrupted run implemented + marked T-1 (the durable on-disk marker, AC-3.3).
        store.appendLine(GreenfieldArtifact.TASKS.relativePath(),
                GreenfieldImplementLoop.CompletionStamp.line("T-1"));

        ImplementBackedPhaseLoops loops = new ImplementBackedPhaseLoops(
                store, prompt -> LoopOutcome.completed("did a task"));
        ArtifactApprovalGate gate = new ArtifactApprovalGate(completedPhase -> true, store, () -> TS);
        GreenfieldDriver.PhaseStateReconstructor reconstructor =
                () -> GreenfieldPhaseState.reconstruct(resumeProbeOver(store));
        GreenfieldDriver freshRun = new GreenfieldDriver(
                loops, writerOver(store), gate, phase -> null, reconstructor);

        freshRun.run(REQUEST);

        assertEquals(GreenfieldPhase.IMPLEMENT, loops.phasesRun.get(0),
                "CT-GF-6: the fresh run reconstructs to IMPLEMENT (all three pre-approval phases are "
                        + "stamped on disk) — the phase boundary resumes at IMPLEMENT");
        assertFalse(loops.phasesRun.contains(GreenfieldPhase.REQUIREMENTS),
                "CT-GF-6: the approved pre-approval phases are not re-run");
        assertEquals(2, loops.implementTaskPrompts.size(),
                "CT-GF-6: within IMPLEMENT only the two INCOMPLETE tasks (T-2, T-3) are implemented — "
                        + "the completed T-1 is skipped, so it does NOT restart at T-1");
        assertTrue(loops.implementTaskPrompts.get(0).contains("T-2"),
                "CT-GF-6/AC-7.6: the resume point within IMPLEMENT is the first incomplete task (T-2); "
                        + "was: " + loops.implementTaskPrompts.get(0));
        assertFalse(loops.implementTaskPrompts.stream().anyMatch(p -> p.contains("Implement task T-1")),
                "CT-GF-6: the already-completed T-1 is not re-implemented on the disk-driven resume");
    }
}
