package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.loop.LoopOutcome;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The greenfield <b>mid-flow resume contract test at the driver level</b> (T-3.2-RD-D12-D13, DCR-3 /
 * ADR-0012 amended 2026-06-23; <b>CT-GF-1 orchestration half</b>). It pins that a real
 * {@link GreenfieldDriver} wired with a {@link GreenfieldDriver.PhaseStateReconstructor} <em>resumes
 * at the re-derived phase</em> rather than always restarting at {@link GreenfieldPhase#initial()},
 * and that the already-approved phases' artifacts are pre-seeded so the resume phase still sees the
 * approved upstream (AC-7.6; DCR-1 kept).
 *
 * <p><b>SUT and collaborators.</b> The SUT is a real {@link GreenfieldDriver} over scripted seams
 * (the external boundary: scripted phase loops, an in-memory artifact writer, a scripted gate, a
 * scripted turn source) plus a scripted {@link GreenfieldDriver.PhaseStateReconstructor} standing in
 * for the on-disk re-derivation — never a mock of the SUT. The re-derivation <em>logic</em> itself
 * is pinned separately in {@link GreenfieldPhaseStateTest}; this test pins that the driver
 * <em>honours</em> the reconstructed start phase + pre-seeded upstream.
 *
 * <p><b>Oracles trace to AC-7.6 / ADR-0012, never to the driver's code:</b> see each test's inline
 * oracle note.
 */
class GreenfieldDriverResumeTest {

    private static final String REQUEST = "build me a URL shortener service";

    /** Records, per phase, the prompts its loop turns were run with, and the phases run in order. */
    private static final class RecordingPhaseLoops implements GreenfieldDriver.PhaseLoopFactory {
        private final List<GreenfieldPhase> phasesRun = new ArrayList<>();
        private final Map<GreenfieldPhase, List<String>> promptsByPhase =
                new EnumMap<>(GreenfieldPhase.class);

        private List<String> prompts(GreenfieldPhase phase) {
            return promptsByPhase.getOrDefault(phase, List.of());
        }

        @Override
        public GreenfieldDriver.LoopTurn loopFor(GreenfieldPhase phase) {
            return prompt -> {
                phasesRun.add(phase);
                promptsByPhase.computeIfAbsent(phase, p -> new ArrayList<>()).add(prompt);
                return LoopOutcome.completed(phase.name() + " round deliverable");
            };
        }
    }

    /** An in-memory artifact writer (the orchestration boundary, not on-disk persistence). */
    private static final class RecordingArtifactWriter implements GreenfieldDriver.PhaseArtifactWriter {
        private final Map<GreenfieldArtifact, String> written = new EnumMap<>(GreenfieldArtifact.class);

        @Override
        public void write(GreenfieldArtifact artifact, String content) {
            written.put(artifact, content);
        }

        @Override
        public String read(GreenfieldArtifact artifact) {
            return written.getOrDefault(artifact, "");
        }
    }

    /** Approves every round (so the resumed session advances through to implementation). */
    private static GreenfieldDriver.ApprovalGate approveEveryRound() {
        return completedPhase -> true;
    }

    /** No refining turns. */
    private static GreenfieldDriver.DeveloperTurnSource noFurtherTurns() {
        return phase -> null;
    }

    /** A reconstructor that resumes at {@code resumePhase} with the given pre-seeded upstream. */
    private static GreenfieldDriver.PhaseStateReconstructor resumeAt(
            GreenfieldPhaseState state) {
        return () -> state;
    }

    private static GreenfieldPhaseState stateResumingAt(GreenfieldPhase resumePhase,
            Map<GreenfieldArtifact, String> approvedUpstream) {
        // Build a reconstructed state by scripting a probe that reports exactly the phases strictly
        // BEFORE resumePhase as approved (with the given content) — the same shape the production
        // re-derivation would produce. This keeps the test honest: the resume phase is DERIVED from
        // the stamped-set per AC-7.6, not hand-set, so a driver that ignored the resume phase fails.
        return GreenfieldPhaseState.reconstruct(new GreenfieldPhaseState.Probe() {
            @Override
            public boolean isApproved(GreenfieldArtifact artifact) {
                return approvedUpstream.containsKey(artifact);
            }

            @Override
            public String content(GreenfieldArtifact artifact) {
                return approvedUpstream.getOrDefault(artifact, "");
            }
        });
    }

    // --- AC-7.6 : the driver resumes at the re-derived phase, not at requirements ----------------

    @Test
    @DisplayName("AC-7.6 (CT-GF-1): a driver whose reconstructor resumes at DESIGN runs DESIGN first — it does NOT re-run requirements")
    void resumesAtDesignWithoutReRunningRequirements() {
        // Oracle: AC-7.6 — "reconstruct its phase-state from those on-disk artifacts and resume at the
        // first phase whose artifact is unstamped or absent, rather than restarting at requirements".
        // With requirements approved on disk (the reconstructor resumes at DESIGN), the driver's FIRST
        // phase run must be DESIGN, and the requirements phase loop must never run. Expected order
        // traces to AC-7.6's resume rule, not to driver internals.
        Map<GreenfieldArtifact, String> approvedReqs = Map.of(
                GreenfieldArtifact.REQUIREMENTS, "# APPROVED requirements\n");
        RecordingPhaseLoops loops = new RecordingPhaseLoops();
        GreenfieldDriver driver = new GreenfieldDriver(
                loops, new RecordingArtifactWriter(), approveEveryRound(), noFurtherTurns(),
                resumeAt(stateResumingAt(GreenfieldPhase.DESIGN, approvedReqs)));

        driver.run(REQUEST);

        assertEquals(GreenfieldPhase.DESIGN, loops.phasesRun.get(0),
                "AC-7.6: the resumed session runs DESIGN first (resume at the first unstamped phase)");
        assertFalse(loops.phasesRun.contains(GreenfieldPhase.REQUIREMENTS),
                "AC-7.6: the already-approved requirements phase is NOT re-run — no restart at "
                        + "requirements");
        assertEquals(
                List.of(GreenfieldPhase.DESIGN, GreenfieldPhase.TASKS, GreenfieldPhase.IMPLEMENT),
                loops.phasesRun,
                "AC-7.6: the resumed session continues design->tasks->implement from the resume phase");
    }

    @Test
    @DisplayName("AC-7.6: a driver whose reconstructor resumes at TASKS (approved requirements + design) runs TASKS first")
    void resumesAtTasksWhenRequirementsAndDesignApproved() {
        // Oracle: AC-7.6 / ADR-0012 — "a project with approved requirements + design resumes at
        // tasks". With requirements + design approved on disk, the driver's first phase run is TASKS.
        Map<GreenfieldArtifact, String> approved = Map.of(
                GreenfieldArtifact.REQUIREMENTS, "# reqs\n",
                GreenfieldArtifact.DESIGN, "# design\n");
        RecordingPhaseLoops loops = new RecordingPhaseLoops();
        GreenfieldDriver driver = new GreenfieldDriver(
                loops, new RecordingArtifactWriter(), approveEveryRound(), noFurtherTurns(),
                resumeAt(stateResumingAt(GreenfieldPhase.TASKS, approved)));

        driver.run(REQUEST);

        assertEquals(GreenfieldPhase.TASKS, loops.phasesRun.get(0),
                "AC-7.6: approved requirements + design resumes the run at tasks");
        assertFalse(loops.phasesRun.contains(GreenfieldPhase.REQUIREMENTS)
                        || loops.phasesRun.contains(GreenfieldPhase.DESIGN),
                "AC-7.6: the approved requirements and design phases are not re-run");
    }

    @Test
    @DisplayName("AC-7.6 retry-in-place: a reconstructor that resumes at REQUIREMENTS (fresh/unstamped) re-enters requirements")
    void freshOrUnstampedResumesAtRequirements() {
        // Oracle: AC-7.6 — "an unstamped/absent requirements artifact starts (or re-enters)
        // requirements (retry-in-place)". A reconstructor that derives REQUIREMENTS as the resume
        // phase (a fresh project, or an interrupted requirements phase) re-enters requirements — the
        // session opens at requirements with the developer's request, exactly as a non-resume run.
        RecordingPhaseLoops loops = new RecordingPhaseLoops();
        GreenfieldDriver driver = new GreenfieldDriver(
                loops, new RecordingArtifactWriter(), approveEveryRound(), noFurtherTurns(),
                resumeAt(stateResumingAt(GreenfieldPhase.REQUIREMENTS, Map.of())));

        driver.run(REQUEST);

        assertEquals(GreenfieldPhase.REQUIREMENTS, loops.phasesRun.get(0),
                "AC-7.6: an unstamped/absent requirements artifact re-enters requirements");
        assertTrue(loops.prompts(GreenfieldPhase.REQUIREMENTS).get(0).contains(REQUEST),
                "AC-7.6/AC-1.1: a requirements-resume opens with the developer's request");
    }

    // --- DCR-1 (kept) : the resume phase's prompt injects the pre-seeded approved upstream --------

    @Test
    @DisplayName("DCR-1 (kept) on resume: the resume phase's prompt carries the pre-seeded approved upstream artifact content")
    void resumePhasePromptInjectsPreSeededApprovedUpstream() {
        // Oracle: ADR-0012 (DCR-1 kept, cited by DCR-3) — "on resume, the already-approved phases'
        // artifacts should be loaded into [approvedArtifacts] so DESIGN/TASKS still see approved
        // upstream per the existing DCR-1 cross-phase continuity". Resuming at DESIGN over an approved
        // requirements artifact, the DESIGN phase's first-round prompt must carry that approved
        // requirements content — proving the resumed driver pre-seeded the upstream rather than
        // entering design blind. Expected fragment traces to the pre-seeded approved content + the
        // DCR-1 cross-phase-injection contract, not to driver internals.
        Map<GreenfieldArtifact, String> approvedReqs = Map.of(
                GreenfieldArtifact.REQUIREMENTS, "PRE-SEEDED-APPROVED-REQUIREMENTS");
        RecordingPhaseLoops loops = new RecordingPhaseLoops();
        GreenfieldDriver driver = new GreenfieldDriver(
                loops, new RecordingArtifactWriter(), approveEveryRound(), noFurtherTurns(),
                resumeAt(stateResumingAt(GreenfieldPhase.DESIGN, approvedReqs)));

        driver.run(REQUEST);

        String designPrompt = loops.prompts(GreenfieldPhase.DESIGN).get(0);
        assertTrue(designPrompt.contains("PRE-SEEDED-APPROVED-REQUIREMENTS"),
                "DCR-1 (resume): the resume phase's prompt injects the pre-seeded approved upstream "
                        + "(the approved requirements content); was: " + designPrompt);
        assertFalse(designPrompt.contains(REQUEST),
                "AC-7.6: a later-phase resume opens from its framing + approved upstream, not a replay "
                        + "of the original requirements request");
    }

    // --- the default (four-seam) constructor is the no-resume baseline ----------------------------

    @Test
    @DisplayName("the four-seam constructor is the no-resume baseline: it always starts at requirements")
    void fourSeamConstructorAlwaysStartsAtRequirements() {
        // Oracle: AC-7.6 / ADR-0012 — the non-resume baseline always starts at requirements. The
        // existing four-seam constructor (used by orchestration unit tests and the no-resume shape)
        // must behave exactly as before DCR-3: begin at requirements with the developer's request.
        RecordingPhaseLoops loops = new RecordingPhaseLoops();
        GreenfieldDriver driver = new GreenfieldDriver(
                loops, new RecordingArtifactWriter(), approveEveryRound(), noFurtherTurns());

        driver.run(REQUEST);

        assertEquals(GreenfieldPhase.REQUIREMENTS, loops.phasesRun.get(0),
                "the four-seam constructor begins a fresh session at requirements (no-resume baseline)");
        assertTrue(loops.prompts(GreenfieldPhase.REQUIREMENTS).get(0).contains(REQUEST),
                "the no-resume baseline opens requirements with the developer's request");
    }

    @Test
    @DisplayName("the five-seam constructor rejects a null reconstructor seam")
    void fiveSeamConstructorRejectsNullReconstructor() {
        assertThrows(NullPointerException.class, () -> new GreenfieldDriver(
                new RecordingPhaseLoops(), new RecordingArtifactWriter(), approveEveryRound(),
                noFurtherTurns(), null));
    }
}
