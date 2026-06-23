package com.srk.codingagent.workflow;

import com.srk.codingagent.loop.AgentLoop;
import com.srk.codingagent.loop.LoopOutcome;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The greenfield workflow driver (component C3, ADR-0012 greenfield side): the
 * requirements&nbsp;&rarr;&nbsp;design&nbsp;&rarr;&nbsp;tasks&nbsp;&rarr;&nbsp;implement phase state
 * machine with explicit per-phase approval gates, orchestrated over the shared engine (the
 * {@link AgentLoop}, the file/search tools, the permission gate). It lets a developer build a
 * brand-new project from idea to implementation, shaping requirements before any source is written
 * (US-1/US-2/US-3, AC-1.1/AC-1.3/AC-2.3/AC-1.4).
 *
 * <p><b>A genuine state machine, unlike brownfield (ADR-0012).</b> The {@link BrownfieldDriver}'s
 * arc is largely emergent model behaviour primed by a single playbook, with one explicit piece of
 * orchestration (the verify step). ADR-0012 makes greenfield a genuine phase state machine: the
 * driver runs each {@link GreenfieldPhase} as an agent-loop turn, then &mdash; for every
 * non-terminal phase &mdash; consults the {@link ApprovalGate} before advancing. "The agent does
 * not advance a phase without explicit developer approval" (ADR-0012); implementation begins only
 * after the design and task breakdown are approved (AC-2.3).
 *
 * <p><b>Phase-scoped loops are the AC-1.4 enforcement (the load-bearing safety AC).</b> The driver
 * runs each phase through a {@link LoopTurn} obtained from the injected {@link PhaseLoopFactory}.
 * The factory yields a phase-scoped loop: the pre-approval phases
 * ({@link GreenfieldPhase#isPreApproval()}) get a loop whose tool registry <em>withholds</em> the
 * Class-X source tools ({@code write_file}/{@code edit_file}/{@code run_command}), so a source
 * write is <em>structurally impossible</em> while in the requirements/design/tasks dialogue
 * (AC-1.4) &mdash; not merely discouraged by the prompt. The implementation phase's loop carries
 * the full toolset. The phase-scoped assembly lives in the gate-covered composition seam
 * (the CLI's {@code ToolRegistryComposer}); the driver only routes each phase to its loop.
 *
 * <p><b>Seams (testability).</b> The driver's orchestration is exercised in isolation through two
 * injected seams: the {@link PhaseLoopFactory} (which, given a {@link GreenfieldPhase}, yields the
 * {@link AgentLoop#run(String)}-shaped {@link LoopTurn} for that phase &mdash; scripted in tests,
 * the phase-scoped real loop in production) and the {@link ApprovalGate} (the per-phase yes/no the
 * developer gives at each gate &mdash; scripted in tests, a real interactive prompt in production).
 * This mirrors the {@link BrownfieldDriver}'s two-seam shape.
 *
 * <p>Not thread-safe: one driver runs one greenfield session on a single thread (the C2 invariant
 * the loop inherits &mdash; one in-flight model call per conversation).
 */
public final class GreenfieldDriver {

    private static final Logger LOGGER = LoggerFactory.getLogger(GreenfieldDriver.class);

    private final PhaseLoopFactory phaseLoopFactory;
    private final ApprovalGate approvalGate;

    /**
     * Creates a greenfield driver over its two injected seams.
     *
     * @param phaseLoopFactory yields the phase-scoped agent-loop turn for each phase (the
     *                         pre-approval phases' loops withhold the Class-X source tools, AC-1.4);
     *                         must not be {@code null}.
     * @param approvalGate     the per-phase approval seam consulted before advancing each
     *                         non-terminal phase (ADR-0012, AC-2.3); must not be {@code null}.
     * @throws NullPointerException if either argument is {@code null}.
     */
    public GreenfieldDriver(PhaseLoopFactory phaseLoopFactory, ApprovalGate approvalGate) {
        this.phaseLoopFactory = Objects.requireNonNull(phaseLoopFactory, "phaseLoopFactory");
        this.approvalGate = Objects.requireNonNull(approvalGate, "approvalGate");
    }

    /**
     * Runs one greenfield session from the developer's initial use-case, driving the
     * requirements&rarr;design&rarr;tasks&rarr;implement state machine with an approval gate
     * between each phase.
     *
     * <p>The flow, starting at {@link GreenfieldPhase#initial()}:
     * <ol>
     *   <li>Run the current phase's turn through its phase-scoped loop (the pre-approval phases'
     *       loops cannot write source, AC-1.4). The first phase runs with the developer's request;
     *       later phases run with a phase-advance prompt.</li>
     *   <li>If the turn surfaced an edge condition rather than completing, no gate is reached:
     *       return {@link GreenfieldOutcome.Disposition#TURN_SURFACED} carrying the surfaced
     *       outcome.</li>
     *   <li>If the turn completed and the phase is terminal ({@link GreenfieldPhase#IMPLEMENT}),
     *       the session is done: return {@link GreenfieldOutcome.Disposition#COMPLETED}.</li>
     *   <li>If the turn completed and the phase is non-terminal, consult the {@link ApprovalGate}.
     *       On approval, advance to {@link GreenfieldPhase#next()} and repeat. On a declined or
     *       deferred approval, stop at this gate without writing source: return
     *       {@link GreenfieldOutcome.Disposition#AWAITING_APPROVAL} (ADR-0012, AC-2.3).</li>
     * </ol>
     *
     * @param request the developer's initial use-case to shape requirements from (US-1); must not
     *                be {@code null} or blank.
     * @return the terminal {@link GreenfieldOutcome}; never {@code null}.
     * @throws NullPointerException     if {@code request} is {@code null}.
     * @throws IllegalArgumentException if {@code request} is blank.
     */
    public GreenfieldOutcome run(String request) {
        if (Objects.requireNonNull(request, "request").isBlank()) {
            throw new IllegalArgumentException("request must be non-blank");
        }
        LOGGER.info("Greenfield session started: requirements->design->tasks->implement (ADR-0012)");

        GreenfieldPhase phase = GreenfieldPhase.initial();
        String prompt = request;
        while (true) {
            LoopOutcome outcome = phaseLoopFactory.loopFor(phase).run(prompt);

            if (!outcome.completed()) {
                LOGGER.warn("Greenfield phase {} turn surfaced ({}) before completing; stopping",
                        phase, outcome.stopReason());
                return GreenfieldOutcome.turnSurfaced(phase, outcome);
            }

            if (phase.isTerminal()) {
                LOGGER.info("Greenfield reached implementation; all phase gates passed (AC-2.3)");
                return GreenfieldOutcome.completed(outcome);
            }

            // ADR-0012 / AC-2.3: the agent does not advance a phase without explicit developer
            // approval. The next phase may be IMPLEMENT (the gate that lets source writing begin),
            // so a declined gate stops here without writing source (AC-1.4).
            if (!approvalGate.approveAdvance(phase)) {
                LOGGER.info("Greenfield phase {} not approved to advance; awaiting approval at the "
                        + "gate, no source written (AC-2.3, AC-1.4)", phase);
                return GreenfieldOutcome.awaitingApproval(phase, outcome);
            }

            GreenfieldPhase nextPhase = phase.next().orElseThrow();
            LOGGER.info("Greenfield phase {} approved; advancing to {}", phase, nextPhase);
            phase = nextPhase;
            prompt = advancePrompt(nextPhase);
        }
    }

    /**
     * The prompt that opens a phase the developer just approved advancing into: it names the phase
     * the session has entered so the (phase-specific playbook) loop turn picks up the right job.
     * The initial request opens the first ({@link GreenfieldPhase#REQUIREMENTS}) phase; this builds
     * the prompt for every subsequent phase.
     */
    private static String advancePrompt(GreenfieldPhase phase) {
        return "The previous phase is approved. Proceed with the " + phase.name().toLowerCase()
                + " phase.";
    }

    /**
     * The per-phase agent-loop turn seam: the {@link AgentLoop#run(String)} shape, obtained from the
     * {@link PhaseLoopFactory} for the phase being run, isolated so the driver's orchestration is
     * testable with scripted loops, mirroring {@link BrownfieldDriver.LoopTurn}.
     */
    @FunctionalInterface
    public interface LoopTurn {

        /**
         * Runs the agent loop for one prompt to its terminal {@link LoopOutcome}.
         *
         * @param prompt the prompt for this phase turn (the developer request for the first phase,
         *               or a phase-advance prompt for later phases); non-blank.
         * @return the terminal outcome; never {@code null}.
         */
        LoopOutcome run(String prompt);
    }

    /**
     * Yields the phase-scoped {@link LoopTurn} for a given {@link GreenfieldPhase}. This is the seam
     * that carries the AC-1.4 enforcement: in production the factory returns a loop whose tool
     * registry withholds the Class-X source tools for a pre-approval phase
     * ({@link GreenfieldPhase#isPreApproval()}) and offers the full toolset for the implementation
     * phase. In tests it returns a scripted loop, optionally asserting the phase it was asked for.
     */
    @FunctionalInterface
    public interface PhaseLoopFactory {

        /**
         * Builds (or selects) the agent-loop turn scoped to the given phase.
         *
         * @param phase the phase whose loop is needed; never {@code null}.
         * @return the phase-scoped loop turn; never {@code null}.
         */
        LoopTurn loopFor(GreenfieldPhase phase);
    }

    /**
     * The per-phase approval seam (ADR-0012, AC-2.3): given the phase the agent just completed and
     * presented, the developer decides whether to approve advancing to the next phase. The driver
     * does not advance without an approval; a declined approval stops the session at the gate
     * without writing source (AC-1.4). T-3.2 layers approval-timestamp recording over this seam.
     */
    @FunctionalInterface
    public interface ApprovalGate {

        /**
         * Whether the developer approves advancing out of the just-completed phase to the next
         * phase.
         *
         * @param completedPhase the phase whose deliverable was just presented; never {@code null}.
         *                       The advance target is {@link GreenfieldPhase#next()}.
         * @return {@code true} to advance, {@code false} to stop at this gate (awaiting approval).
         */
        boolean approveAdvance(GreenfieldPhase completedPhase);
    }
}
