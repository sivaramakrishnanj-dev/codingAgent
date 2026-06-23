package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.persistence.StopReason;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GreenfieldRunner} — the adapter that maps a {@link GreenfieldOutcome} to the
 * {@link LoopOutcome} the run path's exit-code mapper consumes, so the greenfield driver can be
 * wired into {@code codingagent --mode greenfield} without changing the runners' exit-code logic
 * (the same role {@link BrownfieldRunner} plays for brownfield).
 *
 * <p><b>SUT and collaborators.</b> The SUT is a real {@link GreenfieldRunner} over a real
 * {@link GreenfieldDriver} whose two seams are scripted (a scripted per-phase loop factory and a
 * scripted approval gate), so the end-to-end map (driver outcome &rarr; LoopOutcome) is exercised
 * with the real driver, only the model/developer-decision boundary substituted. The driver is the
 * real collaborator, not a mock.
 *
 * <p><b>Oracles trace to the exit-code contract's process-exit semantics and the greenfield
 * dispositions:</b>
 * <ul>
 *   <li><b>US-6 / exit-code 0:</b> a COMPLETED greenfield run maps to the completed loop outcome
 *       (the run path exits 0).</li>
 *   <li><b>ADR-0012 / AC-2.3 / cli-exit-codes G4:</b> an AWAITING_APPROVAL run is a paused session,
 *       not an agent-process failure; it maps to a completed outcome (exit 0) whose text surfaces
 *       that approval is required to advance — rather than masking it as an error exit.</li>
 *   <li><b>state machine A S6/S7:</b> a TURN_SURFACED run passes the surfaced loop outcome through
 *       unchanged, so the run path's existing surfaced-reason mapping (e.g. context exhausted &rarr;
 *       exit 5) still applies.</li>
 * </ul>
 */
class GreenfieldRunnerTest {

    private static final String REQUEST = "build me a CLI todo app";

    /**
     * A no-op driver-authored persistence seam (DCR-1): this test pins the GreenfieldOutcome &rarr;
     * LoopOutcome mapping, not artifact persistence, so the driver's per-phase write/read need no real
     * store here.
     */
    private static GreenfieldDriver.PhaseArtifactWriter noopWriter() {
        return new GreenfieldDriver.PhaseArtifactWriter() {
            @Override
            public void write(GreenfieldArtifact artifact, String content) {
                // no-op: persistence is exercised in GreenfieldArtifactAuthoringTest
            }

            @Override
            public String read(GreenfieldArtifact artifact) {
                return "";
            }
        };
    }

    /** No further developer turns (each phase runs one round; DCR-2 multi-turn dialogue seam). */
    private static GreenfieldDriver.DeveloperTurnSource noFurtherTurns() {
        return phase -> null;
    }

    /** A driver whose every phase completes, and whose gate answers from the supplied decision. */
    private static GreenfieldDriver driverWith(GreenfieldDriver.ApprovalGate gate) {
        GreenfieldDriver.PhaseLoopFactory loops = phase ->
                prompt -> LoopOutcome.completed(phase.name() + " deliverable");
        return new GreenfieldDriver(loops, noopWriter(), gate, noFurtherTurns());
    }

    @Test
    @DisplayName("US-6: a COMPLETED greenfield run maps to a completed LoopOutcome (the run path exits 0)")
    void completedMapsToCompleted() {
        // Oracle: US-6 / exit-code 0 — a greenfield run that reached and ran implementation after all
        // gates passed is the clean success. The runner returns the implementation phase's completed
        // loop outcome unchanged so the run path's mapOutcome exits 0 with the phase's answer.
        GreenfieldRunner runner = new GreenfieldRunner(driverWith(completedPhase -> true));

        LoopOutcome mapped = runner.run(REQUEST);

        assertTrue(mapped.completed(), "US-6: a completed greenfield run is a completed (exit-0) outcome");
        assertEquals("IMPLEMENT deliverable", mapped.finalTextIfPresent().orElse(""),
                "the implementation phase's answer is carried through on a completed run");
    }

    @Test
    @DisplayName("ADR-0012/AC-2.3/G4: an AWAITING_APPROVAL run completes (exit 0) but its text surfaces that approval is required")
    void awaitingApprovalMapsToCompletedWithApprovalNote() {
        // Oracle: ADR-0012 / AC-2.3 — a session that stops at an approval gate without advancing has
        // not failed as a process; the agent did its job for the completed phase and wrote no source
        // (AC-1.4). cli-exit-codes G4 — the agent-process exit code is distinct from a "needs your
        // approval" pause. So an AWAITING_APPROVAL run maps to a COMPLETED outcome (exit 0) whose
        // final text carries the phase's deliverable AND a note that approval is required to advance.
        GreenfieldRunner runner = new GreenfieldRunner(driverWith(completedPhase -> false));

        LoopOutcome mapped = runner.run(REQUEST);

        assertTrue(mapped.completed(),
                "AC-2.3/G4: a paused-awaiting-approval session is not an agent-process failure; exit 0");
        String text = mapped.finalTextIfPresent().orElse("").toLowerCase(Locale.ROOT);
        assertTrue(text.contains("requirements"),
                "the first phase (requirements) is where a deny-every gate stops; its name is surfaced");
        assertTrue(text.contains("approv"),
                "AC-2.3: the developer sees plainly that approval is required before the next phase begins");
    }

    @Test
    @DisplayName("state machine A: a TURN_SURFACED run passes the surfaced LoopOutcome through unchanged")
    void surfacedRunPassesThrough() {
        // Oracle: state machine A S6/S7 — a phase turn that surfaces an edge condition (e.g. context
        // window exceeded) has no completed deliverable; the runner passes the surfaced LoopOutcome
        // through unchanged so the run path's existing surfaced-reason mapping (context exhausted ->
        // exit 5) still applies, rather than being folded into a spurious completed outcome.
        LoopOutcome surfaced = LoopOutcome.surfaced(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED);
        GreenfieldDriver.PhaseLoopFactory loops = phase -> prompt -> surfaced;
        GreenfieldRunner runner = new GreenfieldRunner(
                new GreenfieldDriver(loops, noopWriter(), completedPhase -> true, noFurtherTurns()));

        LoopOutcome mapped = runner.run(REQUEST);

        assertSame(surfaced, mapped,
                "the surfaced LoopOutcome is passed through unchanged for the run path to map");
        assertEquals(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED, mapped.stopReason(),
                "the surfaced reason is preserved (so the run path can map it to exit 5)");
    }

    @Test
    @DisplayName("AC-2.3: the awaiting-approval note is appended to the phase's deliverable text, not replacing it")
    void awaitingApprovalAppendsNoteToDeliverable() {
        // Oracle: AC-2.3 / ADR-0012 — the developer needs to see BOTH the phase's deliverable (to
        // review and approve) AND that approval is required. So on an awaiting-approval stop the
        // mapped final text carries the phase's deliverable text with the approval note appended,
        // not the note alone. The deliverable text traces to the scripted phase turn; the note's
        // intent traces to AC-2.3.
        GreenfieldDriver.PhaseLoopFactory loops = phase ->
                prompt -> LoopOutcome.completed("Here are the agreed requirements.");
        GreenfieldRunner runner = new GreenfieldRunner(
                new GreenfieldDriver(loops, noopWriter(), completedPhase -> false, noFurtherTurns()));

        LoopOutcome mapped = runner.run(REQUEST);

        String text = mapped.finalTextIfPresent().orElse("");
        assertTrue(text.contains("Here are the agreed requirements."),
                "the phase's deliverable text is preserved so the developer can review it");
        assertTrue(text.toLowerCase(Locale.ROOT).contains("approv"),
                "AC-2.3: the approval note is appended so the developer knows approval is required");
    }

    @Test
    @DisplayName("AC-2.3: an awaiting-approval phase with no deliverable text still surfaces the approval note")
    void awaitingApprovalWithBlankDeliverableStillSurfacesTheNote() {
        // Oracle: AC-2.3 — the developer must always see that approval is required to advance, even
        // in the edge case where the completed phase produced no final text (an empty end_turn). So
        // an awaiting-approval stop on a blank-text phase outcome must still map to a completed
        // outcome whose final text carries the approval note (not an empty string that hides the
        // gate). The blank deliverable traces to LoopOutcome.completed("") (the spec-allowed
        // empty-text completion); the note's presence traces to AC-2.3.
        GreenfieldDriver.PhaseLoopFactory loops = phase -> prompt -> LoopOutcome.completed("");
        GreenfieldRunner runner = new GreenfieldRunner(
                new GreenfieldDriver(loops, noopWriter(), completedPhase -> false, noFurtherTurns()));

        LoopOutcome mapped = runner.run(REQUEST);

        assertTrue(mapped.completed(), "AC-2.3: a paused session is exit 0 even with no deliverable text");
        assertTrue(mapped.finalTextIfPresent().orElse("").toLowerCase(Locale.ROOT).contains("approv"),
                "AC-2.3: the approval note is surfaced even when the phase produced no final text");
    }

    @Test
    @DisplayName("the runner requires its driver")
    void constructorRejectsNull() {
        assertThrows(NullPointerException.class, () -> new GreenfieldRunner(null));
    }
}
