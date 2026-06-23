package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.persistence.StopReason;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The greenfield <b>phase-gating contract test</b> (T-3.1, the dedicated test §6 of
 * {@code contract-tests.md} flags as a Phase-4 gap: "greenfield-workflow phase-gating (ADR-0012)
 * ... may warrant dedicated tests when those milestones are scoped"). It pins the orchestration
 * contract ADR-0012 makes load-bearing: greenfield is a genuine phase state machine that
 * <em>does not advance a phase without explicit developer approval</em>, and implementation begins
 * only after the design + task breakdown are approved (AC-2.3) — so a session that is not approved
 * stops at the gate before ever entering the source-writing implementation phase (AC-1.4).
 *
 * <p><b>SUT and collaborators.</b> The SUT is a real {@link GreenfieldDriver}. Its two seams are
 * scripted, not mocked SUT internals: the {@link GreenfieldDriver.PhaseLoopFactory} yields a
 * scripted {@link GreenfieldDriver.LoopTurn} per phase (the {@link com.srk.codingagent.loop.AgentLoop#run}
 * shape, scripted with {@link LoopOutcome}s so phase turns run without a live model), and the
 * {@link GreenfieldDriver.ApprovalGate} is scripted with the developer's per-phase yes/no. This
 * mirrors {@code BrownfieldDriverTest}'s scripted-seam discipline — the external boundary (model +
 * developer decision) is substituted, never the SUT.
 *
 * <p><b>Oracles trace to ADR-0012 and the cited ACs, never to the driver's code:</b> see each
 * test's inline oracle note.
 */
class GreenfieldDriverTest {

    private static final String REQUEST = "build me a URL shortener service";

    // --- Scripted PhaseLoopFactory + LoopTurn seam: replays a LoopOutcome per phase and records,
    //     per phase, the prompt the turn was run with. This is the external boundary (the agent
    //     loop / model), not the SUT. By default every phase completes with end_turn.
    private static final class ScriptedPhaseLoops implements GreenfieldDriver.PhaseLoopFactory {
        private final Map<GreenfieldPhase, Deque<LoopOutcome>> scripts =
                new EnumMap<>(GreenfieldPhase.class);
        private final List<GreenfieldPhase> phasesRun = new ArrayList<>();
        private final Map<GreenfieldPhase, String> promptsByPhase =
                new EnumMap<>(GreenfieldPhase.class);

        ScriptedPhaseLoops complete(GreenfieldPhase phase, String finalText) {
            scripts.computeIfAbsent(phase, p -> new ArrayDeque<>())
                    .addLast(LoopOutcome.completed(finalText));
            return this;
        }

        ScriptedPhaseLoops surface(GreenfieldPhase phase, StopReason reason) {
            scripts.computeIfAbsent(phase, p -> new ArrayDeque<>())
                    .addLast(LoopOutcome.surfaced(reason));
            return this;
        }

        /** Scripts every phase to complete, so a fully-approved run reaches implementation. */
        ScriptedPhaseLoops completeEveryPhase() {
            for (GreenfieldPhase phase : GreenfieldPhase.values()) {
                complete(phase, phase.name() + " deliverable");
            }
            return this;
        }

        @Override
        public GreenfieldDriver.LoopTurn loopFor(GreenfieldPhase phase) {
            return prompt -> {
                phasesRun.add(phase);
                promptsByPhase.put(phase, prompt);
                Deque<LoopOutcome> script = scripts.get(phase);
                if (script == null || script.isEmpty()) {
                    throw new IllegalStateException("no scripted outcome for phase " + phase);
                }
                return script.removeFirst();
            };
        }
    }

    /** An approval gate that records which phases it was asked about and answers from a script. */
    private static final class ScriptedGate implements GreenfieldDriver.ApprovalGate {
        private final List<GreenfieldPhase> asked = new ArrayList<>();
        private final boolean approveAll;
        private final GreenfieldPhase declineAt;

        private ScriptedGate(boolean approveAll, GreenfieldPhase declineAt) {
            this.approveAll = approveAll;
            this.declineAt = declineAt;
        }

        static ScriptedGate approveEvery() {
            return new ScriptedGate(true, null);
        }

        static ScriptedGate declineEvery() {
            return new ScriptedGate(false, null);
        }

        static ScriptedGate declineAt(GreenfieldPhase phase) {
            return new ScriptedGate(true, phase);
        }

        @Override
        public boolean approveAdvance(GreenfieldPhase completedPhase) {
            asked.add(completedPhase);
            if (declineAt != null) {
                return completedPhase != declineAt;
            }
            return approveAll;
        }
    }

    // --- AC-1.1 : the session begins in the requirements phase, before any source write ----------

    @Test
    @DisplayName("AC-1.1: the first phase the driver runs is requirements, started with the developer's request")
    void firstPhaseIsRequirementsRunWithTheRequest() {
        // Oracle: AC-1.1 — "When the developer starts the agent in greenfield mode, the agent shall
        // begin a requirements-gathering dialogue before creating or editing any source file." The
        // driver must run the requirements phase FIRST, and the first phase turn carries the
        // developer's request (the use-case to shape requirements from, US-1).
        ScriptedPhaseLoops loops = new ScriptedPhaseLoops().complete(GreenfieldPhase.REQUIREMENTS, "reqs");
        ScriptedGate gate = ScriptedGate.declineEvery();
        GreenfieldDriver driver = new GreenfieldDriver(loops, gate);

        driver.run(REQUEST);

        assertEquals(GreenfieldPhase.REQUIREMENTS, loops.phasesRun.get(0),
                "AC-1.1: the greenfield session begins in the requirements phase");
        assertEquals(REQUEST, loops.promptsByPhase.get(GreenfieldPhase.REQUIREMENTS),
                "US-1: the first phase turn is run with the developer's use-case request");
    }

    // --- AC-2.3 / AC-1.4 : an unapproved session never reaches implementation (no source write) --

    @Test
    @DisplayName("AC-2.3/AC-1.4: a session declined at the first gate stops awaiting approval and NEVER enters implementation")
    void declinedAtFirstGateStopsBeforeImplementation() {
        // Oracle: AC-2.3 — "implementation begins only after the design + task breakdown are
        // approved"; ADR-0012 — "the agent does not advance a phase without explicit developer
        // approval"; AC-1.4 — the implementation phase (where source writing begins) is never
        // entered while unapproved. With the gate declining every advance, the requirements phase
        // completes, the gate is consulted and declines, and the driver stops at that gate WITHOUT
        // running the implementation phase.
        ScriptedPhaseLoops loops = new ScriptedPhaseLoops().complete(GreenfieldPhase.REQUIREMENTS, "reqs");
        ScriptedGate gate = ScriptedGate.declineEvery();
        GreenfieldDriver driver = new GreenfieldDriver(loops, gate);

        GreenfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(GreenfieldOutcome.Disposition.AWAITING_APPROVAL, outcome.disposition(),
                "AC-2.3: a declined advance stops the session awaiting approval at the gate");
        assertEquals(GreenfieldPhase.REQUIREMENTS, outcome.phase(),
                "the session stops at the phase that was awaiting approval (requirements)");
        assertFalse(loops.phasesRun.contains(GreenfieldPhase.IMPLEMENT),
                "AC-1.4: the implementation phase (where source writing begins) is NEVER entered "
                        + "without approval — no source can be written");
        assertEquals(List.of(GreenfieldPhase.REQUIREMENTS), loops.phasesRun,
                "only the requirements phase ran; the declined gate stopped advancement");
    }

    @Test
    @DisplayName("ADR-0012: the approval gate is consulted before EVERY phase advance (requirements/design/tasks)")
    void approvalGateConsultedBeforeEveryAdvance() {
        // Oracle: ADR-0012 — "Per-sub-phase approval gates ... the agent does not advance a phase
        // without explicit developer approval". Approving every gate, the driver must consult the
        // gate for each non-terminal phase in order (requirements, design, tasks) before advancing,
        // and NOT consult it for the terminal implementation phase (there is nothing to advance into).
        ScriptedPhaseLoops loops = new ScriptedPhaseLoops().completeEveryPhase();
        ScriptedGate gate = ScriptedGate.approveEvery();
        GreenfieldDriver driver = new GreenfieldDriver(loops, gate);

        driver.run(REQUEST);

        assertEquals(
                List.of(GreenfieldPhase.REQUIREMENTS, GreenfieldPhase.DESIGN, GreenfieldPhase.TASKS),
                gate.asked,
                "ADR-0012: the gate is consulted before each non-terminal phase advance, in order; "
                        + "the terminal implementation phase has no advance gate");
    }

    @Test
    @DisplayName("AC-2.3: the gate that guards entering implementation is the one leaving the tasks phase")
    void declinedAtTasksGateStopsBeforeImplementation() {
        // Oracle: AC-2.3 — "When the design or task breakdown is presented, the agent shall request
        // developer approval before implementation begins." The boundary the approval guards is the
        // tasks->implement advance. Approving requirements and design but declining at tasks, the
        // session runs requirements/design/tasks but stops at the tasks gate WITHOUT entering
        // implementation (no source write, AC-1.4).
        ScriptedPhaseLoops loops = new ScriptedPhaseLoops()
                .complete(GreenfieldPhase.REQUIREMENTS, "reqs")
                .complete(GreenfieldPhase.DESIGN, "design")
                .complete(GreenfieldPhase.TASKS, "tasks");
        ScriptedGate gate = ScriptedGate.declineAt(GreenfieldPhase.TASKS);
        GreenfieldDriver driver = new GreenfieldDriver(loops, gate);

        GreenfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(GreenfieldOutcome.Disposition.AWAITING_APPROVAL, outcome.disposition());
        assertEquals(GreenfieldPhase.TASKS, outcome.phase(),
                "AC-2.3: the session stops at the tasks gate — the approval that guards implementation");
        assertFalse(loops.phasesRun.contains(GreenfieldPhase.IMPLEMENT),
                "AC-2.3/AC-1.4: declining the tasks gate keeps the session out of implementation "
                        + "(no source is written)");
        assertEquals(
                List.of(GreenfieldPhase.REQUIREMENTS, GreenfieldPhase.DESIGN, GreenfieldPhase.TASKS),
                loops.phasesRun,
                "the pre-approval phases ran in order; implementation did not");
    }

    // --- ADR-0012 : a fully-approved session runs every phase in order and completes -------------

    @Test
    @DisplayName("ADR-0012/AC-2.3: a fully-approved session runs requirements->design->tasks->implement and completes")
    void fullyApprovedSessionRunsEveryPhaseAndCompletes() {
        // Oracle: ADR-0012 "Phases + artifacts" (requirements -> design -> tasks -> implementation)
        // + AC-2.3 (implementation begins after approval). With every gate approved, the driver must
        // run all four phases in the ADR order and reach a COMPLETED outcome at the implementation
        // phase — the clean greenfield success.
        ScriptedPhaseLoops loops = new ScriptedPhaseLoops().completeEveryPhase();
        ScriptedGate gate = ScriptedGate.approveEvery();
        GreenfieldDriver driver = new GreenfieldDriver(loops, gate);

        GreenfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(
                List.of(GreenfieldPhase.REQUIREMENTS, GreenfieldPhase.DESIGN,
                        GreenfieldPhase.TASKS, GreenfieldPhase.IMPLEMENT),
                loops.phasesRun,
                "ADR-0012: an approved session runs every phase in requirements->design->tasks->implement order");
        assertEquals(GreenfieldOutcome.Disposition.COMPLETED, outcome.disposition(),
                "ADR-0012: reaching and running implementation after all gates pass is a COMPLETED run");
        assertEquals(GreenfieldPhase.IMPLEMENT, outcome.phase(),
                "AC-2.3: the completed run finishes in the implementation phase");
        assertTrue(outcome.completed(), "the fully-approved run is the clean greenfield success");
    }

    @Test
    @DisplayName("ADR-0012: each post-approval phase turn is run with a prompt naming the phase it entered")
    void approvedAdvanceRunsNextPhaseWithAPhaseNamingPrompt() {
        // Oracle: ADR-0012 — the phase state machine advances one phase at a time on approval; the
        // first phase opens with the developer's request, and a phase entered after approval opens
        // with a prompt that frames the phase it just entered (so the phase-specific playbook turn
        // picks up the right job). Assert the DESIGN turn (entered after approving requirements)
        // received a prompt mentioning the design phase, not the original request.
        ScriptedPhaseLoops loops = new ScriptedPhaseLoops().completeEveryPhase();
        GreenfieldDriver driver = new GreenfieldDriver(loops, ScriptedGate.approveEvery());

        driver.run(REQUEST);

        String designPrompt = loops.promptsByPhase.get(GreenfieldPhase.DESIGN).toLowerCase(java.util.Locale.ROOT);
        assertTrue(designPrompt.contains("design"),
                "ADR-0012: the phase entered after approval opens with a prompt framing that phase; was: "
                        + designPrompt);
        assertFalse(designPrompt.contains(REQUEST),
                "the post-approval phase prompt is a phase-advance prompt, not a replay of the request");
    }

    // --- state machine A : a phase turn that surfaces stops the session before any gate ----------

    @Test
    @DisplayName("state machine A: a phase turn that surfaces stops the session (no gate reached, no advance)")
    void surfacedPhaseTurnStopsWithoutReachingAGate() {
        // Oracle: state machine A S6/S7 — when a phase's agent-loop turn surfaces an edge condition
        // (e.g. the context window is exceeded) rather than completing, there is no completed
        // deliverable to approve, so no approval gate is reached and no advance happens. The driver
        // surfaces the loop outcome at the phase that surfaced.
        ScriptedPhaseLoops loops = new ScriptedPhaseLoops()
                .surface(GreenfieldPhase.REQUIREMENTS, StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED);
        ScriptedGate gate = ScriptedGate.approveEvery();
        GreenfieldDriver driver = new GreenfieldDriver(loops, gate);

        GreenfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(GreenfieldOutcome.Disposition.TURN_SURFACED, outcome.disposition(),
                "a surfaced phase turn yields TURN_SURFACED");
        assertEquals(GreenfieldPhase.REQUIREMENTS, outcome.phase(),
                "the surfaced disposition names the phase whose turn surfaced");
        assertEquals(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED, outcome.loopOutcome().stopReason(),
                "the surfaced reason is preserved for the run path to map");
        assertTrue(gate.asked.isEmpty(),
                "no approval gate is reached when a phase turn surfaces (nothing to approve)");
        assertEquals(List.of(GreenfieldPhase.REQUIREMENTS), loops.phasesRun,
                "only the surfaced phase ran; no advance, no further phase");
    }

    // --- construction + input validation ---------------------------------------------------------

    @Test
    @DisplayName("the driver requires its phase-loop-factory and approval-gate seams")
    void constructorRejectsNull() {
        assertThrows(NullPointerException.class,
                () -> new GreenfieldDriver(null, ScriptedGate.approveEvery()));
        assertThrows(NullPointerException.class,
                () -> new GreenfieldDriver(new ScriptedPhaseLoops(), null));
    }

    @Test
    @DisplayName("run rejects a null or blank request")
    void runRejectsBlankRequest() {
        GreenfieldDriver driver = new GreenfieldDriver(
                new ScriptedPhaseLoops(), ScriptedGate.approveEvery());

        assertThrows(NullPointerException.class, () -> driver.run(null));
        assertThrows(IllegalArgumentException.class, () -> driver.run("   "));
    }

    @Test
    @DisplayName("the awaiting-approval outcome carries the completed phase's loop outcome")
    void awaitingApprovalCarriesThePhaseLoopOutcome() {
        // Oracle: GreenfieldOutcome contract — loopOutcome() is always present (every disposition is
        // reached after at least one phase turn produced a LoopOutcome). On an awaiting-approval stop
        // the carried outcome is the completed phase's, so the run path can surface the phase's
        // deliverable text.
        ScriptedPhaseLoops loops = new ScriptedPhaseLoops()
                .complete(GreenfieldPhase.REQUIREMENTS, "the agreed requirements");
        GreenfieldDriver driver = new GreenfieldDriver(loops, ScriptedGate.declineEvery());

        GreenfieldOutcome outcome = driver.run(REQUEST);

        assertSame(StopReason.END_TURN, outcome.loopOutcome().stopReason(),
                "the awaiting-approval outcome carries the completed phase's (end_turn) loop outcome");
        assertEquals("the agreed requirements", outcome.loopOutcome().finalTextIfPresent().orElse(""),
                "the completed phase's deliverable text is available to surface to the developer");
    }
}
