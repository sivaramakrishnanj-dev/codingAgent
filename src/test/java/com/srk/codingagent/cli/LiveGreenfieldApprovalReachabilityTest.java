package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.tool.GreenfieldArtifactStore;
import com.srk.codingagent.workflow.ArtifactApprovalGate;
import com.srk.codingagent.workflow.GreenfieldArtifact;
import com.srk.codingagent.workflow.GreenfieldDriver;
import com.srk.codingagent.workflow.GreenfieldOutcome;
import com.srk.codingagent.workflow.GreenfieldPhase;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Live-reachability regression for the greenfield REPL approval wiring (T-3.1-RD-D6; the greenfield
 * approval analogue of {@link LiveCompactionReachabilityTest} /
 * {@link LiveGreenfieldRegistryCompositionTest}). This is the test that would catch the verified
 * defect: the interactive greenfield phase-approval {@link ArtifactApprovalGate.ApprovalDecision} was
 * hard-coded to decline on the live REPL path ({@code completedPhase -> false}), so a live greenfield
 * REPL run shaped requirements (AC-1.1) but could NEVER advance to design&rarr;tasks&rarr;implement —
 * the developer's "yes" was never read. The fix wires the stdin-backed
 * {@link InteractiveGreenfieldApproval} so the developer's REPL input actually drives phase
 * advancement.
 *
 * <p><b>SUT.</b> The real {@link InteractiveGreenfieldApproval} (the stdin-backed decision) wrapped by
 * the real {@link ArtifactApprovalGate} over a real {@link GreenfieldArtifactStore} rooted at a
 * {@link TempDir}, driven by the real {@link GreenfieldDriver}. Wired exactly as the production
 * composition wires them (decision &rarr; gate &rarr; driver). The only doubles are the external
 * boundary: the developer's stdin (a {@link Supplier} answer source) and the scripted phase loops
 * (the agent loop / model). No Bedrock client is constructed — driving a scripted phase loop plus the
 * interactive decision needs no model call (the established live-reachability pattern; cf.
 * {@link LiveGreenfieldRegistryCompositionTest}'s never-called Bedrock double).
 *
 * <p><b>Oracles trace to the cited spec, not to the wiring's code:</b>
 * <ul>
 *   <li><b>AC-1.5 / AC-2.3:</b> given an affirmative stdin line at each gate, the stdin-backed
 *       decision confirms, so the phase advances and the gate records the AC-1.5 approval timestamp in
 *       the just-approved phase's artifact.</li>
 *   <li><b>AC-1.4 / AC-2.3:</b> given {@code "n"} (and given EOF/null), the decision declines, so the
 *       session pauses {@link GreenfieldOutcome.Disposition#AWAITING_APPROVAL} and no approval is
 *       stamped (no source is written).</li>
 * </ul>
 */
class LiveGreenfieldApprovalReachabilityTest {

    private static final String REQUEST = "build me a URL shortener service";
    private static final String TS = "2026-06-23T09:00:00Z";

    /** The boundary clock (ADR-0005) the approval timestamp is drawn from. */
    private static Supplier<String> fixedClock() {
        return () -> TS;
    }

    /** An answer source that replays a fixed sequence of typed lines, then end-of-input (null). */
    private static Supplier<String> answers(String... lines) {
        Deque<String> queue = new ArrayDeque<>(List.of(lines));
        return () -> queue.isEmpty() ? null : queue.removeFirst();
    }

    /**
     * Scripted phase loops (the external agent-loop/model boundary), modelling the model producing
     * each phase's deliverable prose as its {@code END_TURN} final answer. Since DCR-1 the DRIVER (not
     * the loop) persists that prose to the phase artifact via the {@link GreenfieldDriver.PhaseArtifactWriter};
     * so this loop only RETURNS the deliverable as the completed outcome's final text and writes
     * nothing itself. The terminal IMPLEMENT phase authors no artifact.
     */
    private static final class AuthoringPhaseLoops implements GreenfieldDriver.PhaseLoopFactory {
        private final List<GreenfieldPhase> phasesRun = new ArrayList<>();
        private final Map<GreenfieldPhase, String> bodyByPhase = new EnumMap<>(GreenfieldPhase.class);

        AuthoringPhaseLoops authors(GreenfieldPhase phase, String body) {
            bodyByPhase.put(phase, body);
            return this;
        }

        @Override
        public GreenfieldDriver.LoopTurn loopFor(GreenfieldPhase phase) {
            return prompt -> {
                phasesRun.add(phase);
                String body = GreenfieldArtifact.forPhase(phase)
                        .map(artifact -> bodyByPhase.getOrDefault(
                                phase, "# " + artifact.heading() + "\n"))
                        .orElse(phase.name() + " deliverable");
                return LoopOutcome.completed(body);
            };
        }
    }

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);

    /**
     * The driver-authored persistence seam (DCR-1) over the target-repo store — the production shape:
     * the driver writes each phase's END_TURN prose to its artifact through this before the gate stamps.
     */
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
     * Wires the production approval path: the real stdin-backed {@link InteractiveGreenfieldApproval}
     * over the developer's answer source and the target-repo store, wrapped by the real timestamp-
     * recording {@link ArtifactApprovalGate}. This is the seam the live REPL composition builds; only
     * the answer source and the store root are test-controlled.
     */
    private GreenfieldDriver.ApprovalGate liveApprovalGate(
            Supplier<String> answerSource, GreenfieldArtifactStore store) {
        ArtifactApprovalGate.ApprovalDecision decision =
                new InteractiveGreenfieldApproval(answerSource, out, store);
        return new ArtifactApprovalGate(decision, store, fixedClock());
    }

    @Test
    @DisplayName("AC-1.5/AC-2.3: affirmative stdin drives phase advancement and records the approval timestamp")
    void affirmativeStdinAdvancesPhasesAndRecordsTimestamp(@TempDir Path targetRepo) {
        // Oracle: AC-2.3 — "the agent shall request developer approval before implementation begins";
        // AC-1.5 — confirming records a timestamped approval in the phase's artifact (ADR-0012
        // generalizes to each phase). With the developer typing 'y' at each gate (requirements,
        // design, tasks), the stdin-backed decision must confirm each phase, so the driver advances all
        // the way to implementation (COMPLETED) and each pre-approval phase's artifact carries the
        // AC-1.5 approval timestamp. This is the regression the defect describes: a 'yes' on stdin must
        // actually advance the live phase machine.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        AuthoringPhaseLoops loops = new AuthoringPhaseLoops()
                .authors(GreenfieldPhase.TASKS, "# Tasks\n- T-1 build (AC-1.2)\n");
        GreenfieldDriver driver = new GreenfieldDriver(
                loops, writerOver(store), liveApprovalGate(answers("y", "y", "y"), store));

        GreenfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(GreenfieldOutcome.Disposition.COMPLETED, outcome.disposition(),
                "AC-2.3: affirmative stdin at every gate advances the live machine to implementation");
        assertTrue(loops.phasesRun.contains(GreenfieldPhase.IMPLEMENT),
                "AC-2.3: stdin-driven approval reaches the implementation phase (the defect blocked this)");
        for (GreenfieldArtifact artifact : List.of(
                GreenfieldArtifact.REQUIREMENTS, GreenfieldArtifact.DESIGN, GreenfieldArtifact.TASKS)) {
            assertTrue(store.read(artifact.relativePath()).orElseThrow().contains(TS),
                    "AC-1.5: the stdin-confirmed " + artifact.heading()
                            + " phase records the approval timestamp in its artifact");
        }
    }

    @Test
    @DisplayName("AC-1.4/AC-2.3: 'n' on stdin declines — the session pauses awaiting approval, no source, no timestamp")
    void negativeStdinPausesAwaitingApproval(@TempDir Path targetRepo) {
        // Oracle: AC-2.3 — no advance without approval; AC-1.4 — no source written while unapproved.
        // With the developer typing 'n' at the first gate, the stdin-backed decision declines, so the
        // driver stops at the requirements gate AWAITING_APPROVAL, never enters implementation, and the
        // gate records NO approval timestamp (no source is written).
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        AuthoringPhaseLoops loops = new AuthoringPhaseLoops();
        GreenfieldDriver driver = new GreenfieldDriver(
                loops, writerOver(store), liveApprovalGate(answers("n"), store));

        GreenfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(GreenfieldOutcome.Disposition.AWAITING_APPROVAL, outcome.disposition(),
                "AC-2.3: a 'no' on stdin pauses the session awaiting approval at the gate");
        assertEquals(GreenfieldPhase.REQUIREMENTS, outcome.phase(),
                "the session pauses at the requirements gate the developer declined");
        assertFalse(loops.phasesRun.contains(GreenfieldPhase.IMPLEMENT),
                "AC-1.4: a declined gate keeps the session out of the source-writing implementation phase");
        assertFalse(store.read(GreenfieldArtifact.REQUIREMENTS.relativePath()).orElseThrow().contains(TS),
                "AC-1.5/AC-2.3: a declined phase records no approval timestamp");
    }

    @Test
    @DisplayName("AC-1.4: EOF/null on stdin (Ctrl-D) is a fail-closed decline — the session pauses awaiting approval")
    void endOfInputStdinPausesAwaitingApproval(@TempDir Path targetRepo) {
        // Oracle: AC-1.4 — a closed input must never silently advance; the safe default is to pause
        // awaiting approval (no source). With the answer source at end-of-input (null, Ctrl-D), the
        // stdin-backed decision declines, so the driver stops AWAITING_APPROVAL at the requirements
        // gate, mirroring NonInteractiveApprover's fail-closed stance.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        AuthoringPhaseLoops loops = new AuthoringPhaseLoops();
        GreenfieldDriver driver = new GreenfieldDriver(
                loops, writerOver(store), liveApprovalGate(() -> null, store));

        GreenfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(GreenfieldOutcome.Disposition.AWAITING_APPROVAL, outcome.disposition(),
                "AC-1.4: end-of-input declines (fail-closed); the session pauses awaiting approval");
        assertFalse(loops.phasesRun.contains(GreenfieldPhase.IMPLEMENT),
                "AC-1.4: EOF never advances into the source-writing implementation phase");
        assertFalse(store.read(GreenfieldArtifact.REQUIREMENTS.relativePath()).orElseThrow().contains(TS),
                "AC-1.5: end-of-input records no approval timestamp");
    }

    @Test
    @DisplayName("AC-2.3: stdin approving requirements+design but declining tasks stops at the tasks gate before implementation")
    void stdinDeclineAtTasksStopsBeforeImplementation(@TempDir Path targetRepo) {
        // Oracle: AC-2.3 — "When the design or task breakdown is presented, the agent shall request
        // developer approval before implementation begins." The boundary the approval guards is the
        // tasks->implement advance. With stdin approving requirements and design but declining at
        // tasks, the live machine runs requirements/design/tasks but pauses at the tasks gate WITHOUT
        // entering implementation (no source, AC-1.4) — and only the approved phases carry a timestamp.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        AuthoringPhaseLoops loops = new AuthoringPhaseLoops()
                .authors(GreenfieldPhase.TASKS, "# Tasks\n- T-1 build (AC-1.2)\n");
        GreenfieldDriver driver = new GreenfieldDriver(
                loops, writerOver(store), liveApprovalGate(answers("y", "y", "n"), store));

        GreenfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(GreenfieldOutcome.Disposition.AWAITING_APPROVAL, outcome.disposition(),
                "AC-2.3: declining the tasks gate pauses awaiting approval");
        assertEquals(GreenfieldPhase.TASKS, outcome.phase(),
                "AC-2.3: the session stops at the tasks gate — the approval that guards implementation");
        assertFalse(loops.phasesRun.contains(GreenfieldPhase.IMPLEMENT),
                "AC-1.4: a declined tasks gate keeps the session out of implementation (no source)");
        assertTrue(store.read(GreenfieldArtifact.REQUIREMENTS.relativePath()).orElseThrow().contains(TS),
                "AC-1.5: the stdin-confirmed requirements phase carries its approval timestamp");
        assertTrue(store.read(GreenfieldArtifact.DESIGN.relativePath()).orElseThrow().contains(TS),
                "AC-1.5: the stdin-confirmed design phase carries its approval timestamp");
        assertFalse(store.read(GreenfieldArtifact.TASKS.relativePath()).orElseThrow().contains(TS),
                "AC-1.5/AC-2.3: the declined tasks phase records no approval timestamp");
    }
}
