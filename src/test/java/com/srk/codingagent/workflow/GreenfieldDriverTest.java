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
 * The greenfield <b>phase-gating + multi-turn-dialogue contract test</b> (T-3.2-RD-D11, DCR-2 /
 * ADR-0012 amended 2026-06-23). It pins the orchestration contract DCR-2 makes load-bearing: each
 * pre-approval phase is a <em>multi-turn conversation</em> with <b>approve = finalize</b>; a
 * non-approve answer keeps the phase conversation going (another refining turn, AC-2.4) rather than
 * persist-and-stop; the developer's prior turns within the phase carry forward (in-phase
 * continuity, AC-1.1); and a session that is never approved (and runs out of developer turns) stops
 * at its gate before ever entering the source-writing implementation phase (AC-2.3/AC-1.4).
 *
 * <p><b>SUT and collaborators.</b> The SUT is a real {@link GreenfieldDriver}. Its four seams are
 * scripted, not mocked SUT internals: the {@link GreenfieldDriver.PhaseLoopFactory} yields a
 * scripted {@link GreenfieldDriver.LoopTurn} per phase (the {@link com.srk.codingagent.loop.AgentLoop#run}
 * shape, scripted with {@link LoopOutcome}s so phase turns run without a live model, one outcome
 * consumed per round), the {@link GreenfieldDriver.ApprovalGate} is scripted with the developer's
 * per-round yes/no, and the {@link GreenfieldDriver.DeveloperTurnSource} is scripted with the
 * refining turns the developer supplies within each phase. This mirrors {@code BrownfieldDriverTest}'s
 * scripted-seam discipline — the external boundary (model + developer decision + developer input) is
 * substituted, never the SUT.
 *
 * <p><b>Oracles trace to ADR-0012 (DCR-2) and the amended ACs, never to the driver's code:</b> see
 * each test's inline oracle note.
 */
class GreenfieldDriverTest {

    private static final String REQUEST = "build me a URL shortener service";

    // --- Scripted PhaseLoopFactory + LoopTurn seam: replays a LoopOutcome per ROUND and records,
    //     per phase, every prompt the turns were run with (a phase may run multiple rounds under the
    //     multi-turn dialogue). This is the external boundary (the agent loop / model), not the SUT.
    private static final class ScriptedPhaseLoops implements GreenfieldDriver.PhaseLoopFactory {
        private final Map<GreenfieldPhase, Deque<LoopOutcome>> scripts =
                new EnumMap<>(GreenfieldPhase.class);
        private final List<GreenfieldPhase> phasesRun = new ArrayList<>();
        private final Map<GreenfieldPhase, List<String>> promptsByPhase =
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

        /** Scripts each phase to complete once, so a fully-approved (one round each) run reaches implementation. */
        ScriptedPhaseLoops completeEveryPhaseOnce() {
            for (GreenfieldPhase phase : GreenfieldPhase.values()) {
                complete(phase, phase.name() + " deliverable");
            }
            return this;
        }

        private List<String> prompts(GreenfieldPhase phase) {
            return promptsByPhase.getOrDefault(phase, List.of());
        }

        @Override
        public GreenfieldDriver.LoopTurn loopFor(GreenfieldPhase phase) {
            return prompt -> {
                phasesRun.add(phase);
                promptsByPhase.computeIfAbsent(phase, p -> new ArrayList<>()).add(prompt);
                Deque<LoopOutcome> script = scripts.get(phase);
                if (script == null || script.isEmpty()) {
                    throw new IllegalStateException("no scripted outcome for phase " + phase);
                }
                return script.removeFirst();
            };
        }
    }

    // --- A recording artifact writer: this test pins the ORCHESTRATION contract (not the on-disk
    //     persistence, which GreenfieldArtifactAuthoringTest pins). The driver calls write() each
    //     round and read() on approval; an in-memory writer keeps those side-effect-free.
    private static final class RecordingArtifactWriter implements GreenfieldDriver.PhaseArtifactWriter {
        private final Map<GreenfieldArtifact, String> written =
                new EnumMap<>(GreenfieldArtifact.class);

        @Override
        public void write(GreenfieldArtifact artifact, String content) {
            written.put(artifact, content);
        }

        @Override
        public String read(GreenfieldArtifact artifact) {
            return written.getOrDefault(artifact, "");
        }
    }

    /**
     * An approval gate scripted with the developer's per-round yes/no. Records which phases it was
     * asked about (once per round). By default it approves every round; a phase can be scripted to
     * be approved only on its Nth round (declining earlier rounds), or never.
     */
    private static final class ScriptedGate implements GreenfieldDriver.ApprovalGate {
        private final List<GreenfieldPhase> asked = new ArrayList<>();
        private final Map<GreenfieldPhase, Integer> approveOnRound =
                new EnumMap<>(GreenfieldPhase.class);
        private final Map<GreenfieldPhase, Integer> roundsAsked =
                new EnumMap<>(GreenfieldPhase.class);
        private final boolean approveAllByDefault;

        private ScriptedGate(boolean approveAllByDefault) {
            this.approveAllByDefault = approveAllByDefault;
        }

        static ScriptedGate approveEveryRound() {
            return new ScriptedGate(true);
        }

        static ScriptedGate neverApprove() {
            return new ScriptedGate(false);
        }

        /** Approves the given phase only on its {@code round}-th consultation (1-based), declining earlier. */
        ScriptedGate approve(GreenfieldPhase phase, int round) {
            approveOnRound.put(phase, round);
            return this;
        }

        @Override
        public boolean approveAdvance(GreenfieldPhase completedPhase) {
            asked.add(completedPhase);
            int round = roundsAsked.merge(completedPhase, 1, Integer::sum);
            if (approveOnRound.containsKey(completedPhase)) {
                return round >= approveOnRound.get(completedPhase);
            }
            return approveAllByDefault;
        }
    }

    /**
     * A developer-turn source scripted with the refining turns the developer supplies per phase
     * (consumed in order); returns {@code null} when a phase's turns are exhausted (end-of-input).
     * The session's first turn is the {@code run(request)} argument, not drawn from here.
     */
    private static final class ScriptedTurnSource implements GreenfieldDriver.DeveloperTurnSource {
        private final Map<GreenfieldPhase, Deque<String>> turns = new EnumMap<>(GreenfieldPhase.class);

        ScriptedTurnSource turn(GreenfieldPhase phase, String input) {
            turns.computeIfAbsent(phase, p -> new ArrayDeque<>()).addLast(input);
            return this;
        }

        @Override
        public String nextTurn(GreenfieldPhase phase) {
            Deque<String> queued = turns.get(phase);
            return queued == null || queued.isEmpty() ? null : queued.removeFirst();
        }
    }

    /** The empty developer-turn source: no refining turns (a one-shot-style, no-terminal session). */
    private static GreenfieldDriver.DeveloperTurnSource noFurtherTurns() {
        return phase -> null;
    }

    // --- AC-1.1 : the session begins in the requirements phase, opened with the developer's request -

    @Test
    @DisplayName("AC-1.1: the first phase the driver runs is requirements, opened with the developer's request")
    void firstPhaseIsRequirementsOpenedWithTheRequest() {
        // Oracle: AC-1.1 (amended) — "When the developer starts the agent in greenfield mode, the
        // agent shall begin a requirements-gathering dialogue before creating or editing any source
        // file. The dialogue is multi-turn ...". The driver must run the requirements phase FIRST,
        // and the first phase round carries the developer's request (the use-case to shape
        // requirements from, US-1).
        ScriptedPhaseLoops loops = new ScriptedPhaseLoops().complete(GreenfieldPhase.REQUIREMENTS, "reqs");
        GreenfieldDriver driver = new GreenfieldDriver(
                loops, new RecordingArtifactWriter(), ScriptedGate.neverApprove(), noFurtherTurns());

        driver.run(REQUEST);

        assertEquals(GreenfieldPhase.REQUIREMENTS, loops.phasesRun.get(0),
                "AC-1.1: the greenfield session begins in the requirements phase");
        assertTrue(loops.prompts(GreenfieldPhase.REQUIREMENTS).get(0).contains(REQUEST),
                "US-1/AC-1.1: the first phase round carries the developer's use-case request");
    }

    // --- AC-2.4 / AC-1.1 : a non-approve answer drives another refining turn (no persist-and-stop) --

    @Test
    @DisplayName("AC-2.4: a non-approve answer keeps the phase conversation going — another refining turn — rather than persist-and-stop")
    void nonApproveAnswerDrivesAnotherRefiningTurn() {
        // Oracle: AC-2.4 (amended) — "a non-approval ... is realized as another refining turn in the
        // same phase conversation — it does not persist-and-stop — and the agent re-offers the
        // approval prompt after revising." Scripting the requirements gate to decline the first
        // round and approve the second, with one refining developer turn available, the driver must
        // run the requirements phase TWICE (two rounds) before advancing — proving a non-approve
        // drove another refining turn, not a stop.
        ScriptedPhaseLoops loops = new ScriptedPhaseLoops()
                .complete(GreenfieldPhase.REQUIREMENTS, "draft 1")
                .complete(GreenfieldPhase.REQUIREMENTS, "draft 2 (converged)")
                .complete(GreenfieldPhase.DESIGN, "design")
                .complete(GreenfieldPhase.TASKS, "# Tasks\n- T-1 (AC-1.1)\n")
                .complete(GreenfieldPhase.IMPLEMENT, "implemented");
        ScriptedGate gate = ScriptedGate.approveEveryRound().approve(GreenfieldPhase.REQUIREMENTS, 2);
        ScriptedTurnSource turns = new ScriptedTurnSource()
                .turn(GreenfieldPhase.REQUIREMENTS, "please add an auth requirement");
        GreenfieldDriver driver = new GreenfieldDriver(
                loops, new RecordingArtifactWriter(), gate, turns);

        driver.run(REQUEST);

        long requirementsRounds = loops.phasesRun.stream()
                .filter(p -> p == GreenfieldPhase.REQUIREMENTS).count();
        assertEquals(2, requirementsRounds,
                "AC-2.4: the declined first round drove a SECOND refining requirements turn (not a stop)");
        assertEquals(2,
                loops.prompts(GreenfieldPhase.REQUIREMENTS).size(),
                "the requirements phase ran two conversation rounds before approval");
    }

    @Test
    @DisplayName("AC-1.1 in-phase continuity: a refining round's prompt carries the model's prior turn within the phase")
    void refiningRoundPromptCarriesPriorInPhaseTurn() {
        // Oracle: AC-1.1 (amended) — "the model's prior turns within the phase carry forward". The
        // second requirements round's prompt must carry the FIRST round's deliverable (the model's
        // own prior turn within the phase) and the developer's refining turn — fixing the in-phase
        // discontinuity. The expected fragments trace to the scripted first-round deliverable + the
        // scripted refining turn, per the in-phase-carry contract, not to driver internals.
        ScriptedPhaseLoops loops = new ScriptedPhaseLoops()
                .complete(GreenfieldPhase.REQUIREMENTS, "ROUND-ONE-DELIVERABLE")
                .complete(GreenfieldPhase.REQUIREMENTS, "round two");
        ScriptedGate gate = ScriptedGate.neverApprove();
        ScriptedTurnSource turns = new ScriptedTurnSource()
                .turn(GreenfieldPhase.REQUIREMENTS, "REFINE-WITH-THIS");
        GreenfieldDriver driver = new GreenfieldDriver(
                loops, new RecordingArtifactWriter(), gate, turns);

        driver.run(REQUEST);

        String secondRoundPrompt = loops.prompts(GreenfieldPhase.REQUIREMENTS).get(1);
        assertTrue(secondRoundPrompt.contains("ROUND-ONE-DELIVERABLE"),
                "AC-1.1: the refining round's prompt carries the model's prior in-phase turn; was: "
                        + secondRoundPrompt);
        assertTrue(secondRoundPrompt.contains("REFINE-WITH-THIS"),
                "AC-1.1: the refining round's prompt carries the developer's refining turn; was: "
                        + secondRoundPrompt);
    }

    // --- AC-1.5 / AC-2.3 : approve = finalize — capture the converged deliverable, persist, advance -

    @Test
    @DisplayName("AC-1.5/AC-2.3: approve = finalize — the converged (latest-round) deliverable is what the driver persists at approval")
    void approveFinalizesTheConvergedDeliverable() {
        // Oracle: AC-1.5 (amended) — "on approval the driver captures the converged deliverable from
        // the phase conversation, persists it (AC-1.2), records this timestamp, and advances"; the
        // DCR-2 trigger moves capture-and-persist to approval so the CONVERGED deliverable (the
        // latest round), not a first-round draft, is what is written. Declining round 1 and approving
        // round 2, the requirements artifact the driver persisted must hold round 2's deliverable.
        // Expected content traces to the scripted converged (round-2) deliverable + AC-1.5, not to
        // driver code.
        ScriptedPhaseLoops loops = new ScriptedPhaseLoops()
                .complete(GreenfieldPhase.REQUIREMENTS, "first draft (questions)")
                .complete(GreenfieldPhase.REQUIREMENTS, "CONVERGED requirements")
                .complete(GreenfieldPhase.DESIGN, "design")
                .complete(GreenfieldPhase.TASKS, "# Tasks\n- T-1 (AC-1.1)\n")
                .complete(GreenfieldPhase.IMPLEMENT, "implemented");
        ScriptedGate gate = ScriptedGate.approveEveryRound().approve(GreenfieldPhase.REQUIREMENTS, 2);
        ScriptedTurnSource turns = new ScriptedTurnSource()
                .turn(GreenfieldPhase.REQUIREMENTS, "refine please");
        RecordingArtifactWriter writer = new RecordingArtifactWriter();
        GreenfieldDriver driver = new GreenfieldDriver(loops, writer, gate, turns);

        driver.run(REQUEST);

        assertEquals("CONVERGED requirements", writer.read(GreenfieldArtifact.REQUIREMENTS),
                "AC-1.5/DCR-2: the persisted requirements artifact holds the CONVERGED (latest-round) "
                        + "deliverable at approval, not the first-round draft");
    }

    @Test
    @DisplayName("ADR-0012/AC-2.3: a fully-approved (one round each) session runs requirements->design->tasks->implement and completes")
    void fullyApprovedSessionRunsEveryPhaseAndCompletes() {
        // Oracle: ADR-0012 "Phases + artifacts" (requirements -> design -> tasks -> implementation) +
        // AC-2.3 (implementation begins after approval). With every round approved (one round per
        // phase), the driver runs all four phases in the ADR order and reaches a COMPLETED outcome at
        // the implementation phase — the clean greenfield success.
        ScriptedPhaseLoops loops = new ScriptedPhaseLoops().completeEveryPhaseOnce();
        ScriptedGate gate = ScriptedGate.approveEveryRound();
        GreenfieldDriver driver = new GreenfieldDriver(
                loops, new RecordingArtifactWriter(), gate, noFurtherTurns());

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
    @DisplayName("AC-2.3: the approval prompt is offered each round, for each non-terminal phase, before any advance")
    void approvalGateConsultedEachRoundBeforeEveryAdvance() {
        // Oracle: AC-2.3 (amended) — "the approval prompt is offered each round and developer
        // approval is the finalize signal that persists the converged artifact and advances";
        // ADR-0012 — the gate is consulted before each non-terminal advance, NOT for the terminal
        // implementation phase. With one round per phase approved, the gate is asked exactly once per
        // non-terminal phase, in order.
        ScriptedPhaseLoops loops = new ScriptedPhaseLoops().completeEveryPhaseOnce();
        ScriptedGate gate = ScriptedGate.approveEveryRound();
        GreenfieldDriver driver = new GreenfieldDriver(
                loops, new RecordingArtifactWriter(), gate, noFurtherTurns());

        driver.run(REQUEST);

        assertEquals(
                List.of(GreenfieldPhase.REQUIREMENTS, GreenfieldPhase.DESIGN, GreenfieldPhase.TASKS),
                gate.asked,
                "AC-2.3: the approval prompt is offered for each non-terminal phase, in order; the "
                        + "terminal implementation phase has no advance gate");
    }

    // --- AC-2.3 / AC-1.4 : a never-approved session that runs out of turns stops before implementation

    @Test
    @DisplayName("AC-2.3/AC-1.4: a session never approved (and out of developer turns) stops awaiting approval and NEVER enters implementation")
    void neverApprovedSessionStopsBeforeImplementation() {
        // Oracle: AC-2.3 — "implementation begins only after the design + task breakdown are
        // approved"; AC-1.4 — the implementation phase (where source writing begins) is never
        // entered while unapproved. With the gate never approving and NO further developer turns, the
        // requirements phase runs its opening round, the gate declines, the driver tries to take
        // another refining turn (AC-2.4) but the developer supplies none (end-of-input), so the
        // session pauses awaiting approval at requirements WITHOUT entering implementation.
        ScriptedPhaseLoops loops = new ScriptedPhaseLoops()
                .complete(GreenfieldPhase.REQUIREMENTS, "reqs");
        GreenfieldDriver driver = new GreenfieldDriver(
                loops, new RecordingArtifactWriter(), ScriptedGate.neverApprove(), noFurtherTurns());

        GreenfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(GreenfieldOutcome.Disposition.AWAITING_APPROVAL, outcome.disposition(),
                "AC-2.3: an unapproved session out of developer turns pauses awaiting approval");
        assertEquals(GreenfieldPhase.REQUIREMENTS, outcome.phase(),
                "the session stops at the phase that was awaiting approval (requirements)");
        assertFalse(loops.phasesRun.contains(GreenfieldPhase.IMPLEMENT),
                "AC-1.4: the implementation phase (where source writing begins) is NEVER entered "
                        + "without approval — no source can be written");
        assertEquals(List.of(GreenfieldPhase.REQUIREMENTS), loops.phasesRun,
                "only the requirements phase ran; the unapproved, out-of-turns session stopped");
    }

    @Test
    @DisplayName("AC-2.3: declining at the tasks gate (out of turns) stops at the tasks gate — the approval that guards implementation")
    void neverApprovedAtTasksGateStopsBeforeImplementation() {
        // Oracle: AC-2.3 — "When the design or task breakdown is presented, the agent shall request
        // developer approval before implementation begins." The boundary the approval guards is the
        // tasks->implement advance. Approving requirements and design but never approving at tasks
        // (and out of tasks turns), the session runs requirements/design/tasks but stops at the tasks
        // gate WITHOUT entering implementation (no source write, AC-1.4).
        ScriptedPhaseLoops loops = new ScriptedPhaseLoops()
                .complete(GreenfieldPhase.REQUIREMENTS, "reqs")
                .complete(GreenfieldPhase.DESIGN, "design")
                .complete(GreenfieldPhase.TASKS, "tasks");
        ScriptedGate gate = ScriptedGate.approveEveryRound()
                .approve(GreenfieldPhase.TASKS, 99); // never reached: only one tasks round runs
        GreenfieldDriver driver = new GreenfieldDriver(
                loops, new RecordingArtifactWriter(), gate, noFurtherTurns());

        GreenfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(GreenfieldOutcome.Disposition.AWAITING_APPROVAL, outcome.disposition());
        assertEquals(GreenfieldPhase.TASKS, outcome.phase(),
                "AC-2.3: the session stops at the tasks gate — the approval that guards implementation");
        assertFalse(loops.phasesRun.contains(GreenfieldPhase.IMPLEMENT),
                "AC-2.3/AC-1.4: not approving the tasks gate keeps the session out of implementation");
        assertEquals(
                List.of(GreenfieldPhase.REQUIREMENTS, GreenfieldPhase.DESIGN, GreenfieldPhase.TASKS),
                loops.phasesRun,
                "the pre-approval phases ran in order; implementation did not");
    }

    // --- DCR-1 (kept) cross-phase continuity : later phase prompts inject approved earlier artifacts -

    @Test
    @DisplayName("DCR-1 (kept): the post-approval design phase opens with a prompt naming the design phase and the approved requirements")
    void approvedAdvanceOpensNextPhaseWithCrossPhaseInjection() {
        // Oracle: ADR-0012 (DCR-1, retained under DCR-2) — later phases inject the approved
        // earlier-phase artifact into their conversation; the phase entered after approval opens with
        // a prompt framing that phase. The DESIGN phase's first-round prompt (entered after approving
        // requirements) must name the design phase and carry the approved requirements deliverable,
        // not replay the original request.
        ScriptedPhaseLoops loops = new ScriptedPhaseLoops()
                .complete(GreenfieldPhase.REQUIREMENTS, "APPROVED-REQUIREMENTS-CONTENT")
                .complete(GreenfieldPhase.DESIGN, "design")
                .complete(GreenfieldPhase.TASKS, "# Tasks\n- T-1 (AC-1.1)\n")
                .complete(GreenfieldPhase.IMPLEMENT, "implemented");
        GreenfieldDriver driver = new GreenfieldDriver(loops, new RecordingArtifactWriter(),
                ScriptedGate.approveEveryRound(), noFurtherTurns());

        driver.run(REQUEST);

        String designPrompt = loops.prompts(GreenfieldPhase.DESIGN).get(0)
                .toLowerCase(java.util.Locale.ROOT);
        assertTrue(designPrompt.contains("design"),
                "ADR-0012: the phase entered after approval opens with a prompt framing that phase; was: "
                        + designPrompt);
        assertTrue(designPrompt.contains("approved-requirements-content"),
                "DCR-1: the design prompt injects the approved requirements content; was: " + designPrompt);
        assertFalse(designPrompt.contains(REQUEST),
                "the post-approval phase prompt is a phase prompt, not a replay of the original request");
    }

    @Test
    @DisplayName("AC-1.1 in-phase continuity (later phase): a design refining round replays the design phase's prior turn (opened with no developer line)")
    void laterPhaseRefiningRoundReplaysPriorTurnWithNoOpeningDeveloperLine() {
        // Oracle: AC-1.1 (amended) — "the model's prior turns within the phase carry forward". A
        // later phase (design) opens automatically with the phase framing (no developer line); if its
        // first round is not approved, the refining round's prompt must still replay the model's prior
        // design turn (the model's own earlier turn within the phase). Approving requirements (round
        // 1) then declining design round 1 and approving design round 2, the design refining prompt
        // must carry the design round-1 deliverable. Expected fragment traces to the scripted design
        // round-1 deliverable per the in-phase-carry contract, not to driver internals.
        ScriptedPhaseLoops loops = new ScriptedPhaseLoops()
                .complete(GreenfieldPhase.REQUIREMENTS, "reqs")
                .complete(GreenfieldPhase.DESIGN, "DESIGN-ROUND-ONE")
                .complete(GreenfieldPhase.DESIGN, "design round two")
                .complete(GreenfieldPhase.TASKS, "# Tasks\n- T-1 (AC-1.1)\n")
                .complete(GreenfieldPhase.IMPLEMENT, "implemented");
        ScriptedGate gate = ScriptedGate.approveEveryRound().approve(GreenfieldPhase.DESIGN, 2);
        ScriptedTurnSource turns = new ScriptedTurnSource()
                .turn(GreenfieldPhase.DESIGN, "tighten the API surface");
        GreenfieldDriver driver = new GreenfieldDriver(
                loops, new RecordingArtifactWriter(), gate, turns);

        driver.run(REQUEST);

        String designRefinePrompt = loops.prompts(GreenfieldPhase.DESIGN).get(1);
        assertTrue(designRefinePrompt.contains("DESIGN-ROUND-ONE"),
                "AC-1.1: the design refining round replays the model's prior design turn; was: "
                        + designRefinePrompt);
        assertTrue(designRefinePrompt.contains("tighten the API surface"),
                "AC-1.1: the design refining round carries the developer's refining turn; was: "
                        + designRefinePrompt);
    }

    // --- state machine A : a phase turn that surfaces stops the session before any gate ------------

    @Test
    @DisplayName("state machine A: the IMPLEMENT-phase turn surfacing passes through as TURN_SURFACED")
    void implementPhaseSurfacingPassesThrough() {
        // Oracle: state machine A S6/S7 — when the terminal implementation phase's loop surfaces an
        // edge condition (e.g. the context window is exceeded) rather than completing, the session
        // surfaces at the implementation phase for the run path to map (it does not masquerade as a
        // clean COMPLETED). Approving every pre-approval gate so the machine reaches implementation,
        // and scripting the implementation turn to surface, the driver yields TURN_SURFACED at
        // IMPLEMENT carrying the surfaced reason.
        ScriptedPhaseLoops loops = new ScriptedPhaseLoops()
                .complete(GreenfieldPhase.REQUIREMENTS, "reqs")
                .complete(GreenfieldPhase.DESIGN, "design")
                .complete(GreenfieldPhase.TASKS, "# Tasks\n- T-1 (AC-1.1)\n")
                .surface(GreenfieldPhase.IMPLEMENT, StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED);
        GreenfieldDriver driver = new GreenfieldDriver(loops, new RecordingArtifactWriter(),
                ScriptedGate.approveEveryRound(), noFurtherTurns());

        GreenfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(GreenfieldOutcome.Disposition.TURN_SURFACED, outcome.disposition(),
                "a surfaced implementation turn yields TURN_SURFACED, not COMPLETED");
        assertEquals(GreenfieldPhase.IMPLEMENT, outcome.phase(),
                "the surfaced disposition names the implementation phase");
        assertEquals(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED, outcome.loopOutcome().stopReason(),
                "the surfaced reason is preserved for the run path to map");
    }

    @Test
    @DisplayName("state machine A: a phase round that surfaces stops the session (no gate reached, no advance)")
    void surfacedPhaseTurnStopsWithoutReachingAGate() {
        // Oracle: state machine A S6/S7 — when a phase's agent-loop round surfaces an edge condition
        // (e.g. the context window is exceeded) rather than completing, there is no completed
        // deliverable to approve, so no approval gate is reached and no advance happens. The driver
        // surfaces the loop outcome at the phase that surfaced.
        ScriptedPhaseLoops loops = new ScriptedPhaseLoops()
                .surface(GreenfieldPhase.REQUIREMENTS, StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED);
        ScriptedGate gate = ScriptedGate.approveEveryRound();
        GreenfieldDriver driver = new GreenfieldDriver(
                loops, new RecordingArtifactWriter(), gate, noFurtherTurns());

        GreenfieldOutcome outcome = driver.run(REQUEST);

        assertEquals(GreenfieldOutcome.Disposition.TURN_SURFACED, outcome.disposition(),
                "a surfaced phase round yields TURN_SURFACED");
        assertEquals(GreenfieldPhase.REQUIREMENTS, outcome.phase(),
                "the surfaced disposition names the phase whose round surfaced");
        assertEquals(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED, outcome.loopOutcome().stopReason(),
                "the surfaced reason is preserved for the run path to map");
        assertTrue(gate.asked.isEmpty(),
                "no approval gate is reached when a phase round surfaces (nothing to approve)");
        assertEquals(List.of(GreenfieldPhase.REQUIREMENTS), loops.phasesRun,
                "only the surfaced phase ran; no advance, no further phase");
    }

    // --- construction + input validation ---------------------------------------------------------

    @Test
    @DisplayName("the driver requires its phase-loop-factory, artifact-writer, approval-gate, and developer-turn-source seams")
    void constructorRejectsNull() {
        // DCR-2: the developer-turn source (the multi-turn dialogue's per-turn input seam) is a
        // required collaborator alongside the phase-loop factory, the artifact writer, and the gate.
        assertThrows(NullPointerException.class,
                () -> new GreenfieldDriver(null, new RecordingArtifactWriter(),
                        ScriptedGate.approveEveryRound(), noFurtherTurns()));
        assertThrows(NullPointerException.class,
                () -> new GreenfieldDriver(new ScriptedPhaseLoops(), null,
                        ScriptedGate.approveEveryRound(), noFurtherTurns()));
        assertThrows(NullPointerException.class,
                () -> new GreenfieldDriver(new ScriptedPhaseLoops(), new RecordingArtifactWriter(),
                        null, noFurtherTurns()));
        assertThrows(NullPointerException.class,
                () -> new GreenfieldDriver(new ScriptedPhaseLoops(), new RecordingArtifactWriter(),
                        ScriptedGate.approveEveryRound(), null));
    }

    @Test
    @DisplayName("run rejects a null or blank request")
    void runRejectsBlankRequest() {
        GreenfieldDriver driver = new GreenfieldDriver(new ScriptedPhaseLoops(),
                new RecordingArtifactWriter(), ScriptedGate.approveEveryRound(), noFurtherTurns());

        assertThrows(NullPointerException.class, () -> driver.run(null));
        assertThrows(IllegalArgumentException.class, () -> driver.run("   "));
    }

    @Test
    @DisplayName("the awaiting-approval outcome carries the awaiting phase's loop outcome")
    void awaitingApprovalCarriesThePhaseLoopOutcome() {
        // Oracle: GreenfieldOutcome contract — loopOutcome() is always present. On an
        // awaiting-approval stop (never approved, out of turns) the carried outcome is the phase's
        // last completed round, so the run path can surface the phase's deliverable text.
        ScriptedPhaseLoops loops = new ScriptedPhaseLoops()
                .complete(GreenfieldPhase.REQUIREMENTS, "the agreed requirements");
        GreenfieldDriver driver = new GreenfieldDriver(loops, new RecordingArtifactWriter(),
                ScriptedGate.neverApprove(), noFurtherTurns());

        GreenfieldOutcome outcome = driver.run(REQUEST);

        assertSame(StopReason.END_TURN, outcome.loopOutcome().stopReason(),
                "the awaiting-approval outcome carries the phase's last (end_turn) loop outcome");
        assertEquals("the agreed requirements", outcome.loopOutcome().finalTextIfPresent().orElse(""),
                "the phase's deliverable text is available to surface to the developer");
    }
}
