package com.srk.codingagent.workflow;

import com.srk.codingagent.loop.AgentLoop;
import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.tool.GreenfieldArtifactStore;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * driver runs each pre-approval {@link GreenfieldPhase} as a <em>multi-turn conversation</em> and
 * consults the {@link ApprovalGate} each round before advancing. "The agent does not advance a
 * phase without explicit developer approval" (ADR-0012); implementation begins only after the
 * design and task breakdown are approved (AC-2.3).
 *
 * <p><b>Multi-turn phase dialogue, approve = finalize (DCR-2, ADR-0012 amended 2026-06-23).</b>
 * Each pre-approval phase (requirements, design, tasks) is a multi-turn conversation, not a single
 * model turn: the developer converses across several turns to shape the deliverable; the model
 * refines it each round and may ask clarifying questions (AC-1.1). <b>The phase transcript carries
 * across turns within the phase</b> &mdash; the driver injects the in-phase turns so far into each
 * round's prompt, so the model sees its own prior turns within the phase (fixing the
 * fresh-conversation-per-phase in-phase discontinuity that made the single-turn shape capture
 * <em>questions</em> rather than a converged deliverable). Each round the developer is offered the
 * approval prompt; <b>approve = finalize</b>: on approval the driver captures the model's latest
 * substantive deliverable in the phase conversation, persists it (the driver-authored path) and the
 * gate stamps the AC-1.5 approval, then the session advances (AC-2.3). A <b>non-approve</b> answer
 * is <em>not</em> persist-and-stop &mdash; it keeps the phase conversation going (another refining
 * turn, which also satisfies AC-2.4's revise-and-re-request). The conversation stops only when the
 * developer supplies no further turn (end-of-input), in which case the session pauses awaiting
 * approval and is resumable.
 *
 * <p><b>Phase-scoped loops are the AC-1.4 enforcement (the load-bearing safety AC).</b> The driver
 * runs each phase through a {@link LoopTurn} obtained from the injected {@link PhaseLoopFactory}.
 * The factory yields a phase-scoped loop: the pre-approval phases
 * ({@link GreenfieldPhase#isPreApproval()}) get a loop whose tool registry <em>withholds</em> the
 * Class-X source tools ({@code write_file}/{@code edit_file}/{@code run_command}), so a source
 * write is <em>structurally impossible</em> across <em>every</em> turn of the multi-turn dialogue
 * (AC-1.4) &mdash; not merely discouraged by the prompt. The implementation phase's loop carries
 * the full toolset. The phase-scoped assembly lives in the gate-covered composition seam
 * (the CLI's {@code ToolRegistryComposer}); the driver only routes each phase to its loop.
 *
 * <p><b>Driver-authored phase deliverables, captured at approval (DCR-1; trigger refined by
 * DCR-2).</b> Persistence of each pre-approval phase deliverable is <em>driver-guaranteed</em>, not
 * model-tool-dependent. The capture-and-persist trigger is <b>phase approval</b> (DCR-2 moves it
 * from each phase's first {@code END_TURN} so the <em>converged</em> deliverable is what is
 * written): each round the driver writes the model's latest deliverable prose
 * ({@link LoopOutcome#finalTextIfPresent()}) to that phase's artifact
 * ({@link GreenfieldArtifact#forPhase(GreenfieldPhase)} &rarr;
 * {@code design/00-requirements.md} / {@code 01-design.md} / {@code 02-tasks.md}) in code via the
 * injected {@link PhaseArtifactWriter} (a truncating {@code GreenfieldArtifactStore.write()}) so the
 * artifact reflects exactly what the developer is being asked to approve this round, then consults
 * the {@link ApprovalGate}. On approval the gate stamps the just-written converged deliverable with
 * the AC-1.5 timestamp; on a non-approve no stamp is appended and the next round's write
 * (truncating) supersedes it. It does not depend on the live model emitting a {@code write_artifact}
 * {@code toolUse}, which empirically it does not. The {@code write_artifact} design-doc tool stays
 * registered/available in the pre-approval registry but is no longer the persistence mechanism.
 *
 * <p><b>Cross-phase transcript continuity (DCR-1, kept).</b> Later phases inject the approved
 * earlier-phase artifact content into each later phase's prompt (requirements &rarr; design,
 * requirements + design &rarr; tasks), so design and tasks are authored against the actual approved
 * upstream content. DCR-2 adds in-phase continuity; DCR-1's cross-phase injection is unchanged.
 *
 * <p><b>AC-1.4 preserved.</b> The driver's in-code artifact write is confined to the target
 * project's {@code design/} markdown (the {@link GreenfieldArtifactStore} the
 * {@link PhaseArtifactWriter} writes through refuses any path outside {@code design/}); the
 * source-write Class-X tools ({@code write_file}/{@code edit_file}/{@code run_command}) stay
 * withheld from the pre-approval phase loops across every turn. The change is to <em>how the phase
 * converges</em> (multi-turn) and <em>when the deliverable is persisted</em> (at approval), not to
 * <em>what may be written before approval</em> (still only {@code design/} markdown).
 *
 * <p><b>Seams (testability).</b> The driver's orchestration is exercised in isolation through four
 * injected seams: the {@link PhaseLoopFactory} (which, given a {@link GreenfieldPhase}, yields the
 * {@link AgentLoop#run(String)}-shaped {@link LoopTurn} for that phase), the
 * {@link PhaseArtifactWriter} (which persists a phase's deliverable prose to its artifact in code),
 * the {@link ApprovalGate} (the per-round yes/no the developer gives at each gate), and the
 * {@link DeveloperTurnSource} (which supplies each refining developer turn within a phase &mdash;
 * the same REPL input the approval decision reads in production, a scripted source in tests).
 *
 * <p>Not thread-safe: one driver runs one greenfield session on a single thread (the C2 invariant
 * the loop inherits &mdash; one in-flight model call per conversation).
 */
public final class GreenfieldDriver {

    private static final Logger LOGGER = LoggerFactory.getLogger(GreenfieldDriver.class);

    private final PhaseLoopFactory phaseLoopFactory;
    private final PhaseArtifactWriter artifactWriter;
    private final ApprovalGate approvalGate;
    private final DeveloperTurnSource turnSource;

    /**
     * Creates a greenfield driver over its four injected seams.
     *
     * @param phaseLoopFactory yields the phase-scoped agent-loop turn for each phase (the
     *                         pre-approval phases' loops withhold the Class-X source tools, AC-1.4);
     *                         must not be {@code null}.
     * @param artifactWriter   persists each pre-approval phase's deliverable prose to its
     *                         design-doc artifact in code (DCR-1; AC-1.2/AC-2.1, {@code design/}-
     *                         confined so AC-1.4 is preserved); must not be {@code null}.
     * @param approvalGate     the per-round approval seam consulted before advancing each
     *                         non-terminal phase (ADR-0012, AC-2.3); must not be {@code null}.
     * @param turnSource       the source of each refining developer turn within a pre-approval phase
     *                         (DCR-2 multi-turn dialogue): returns the next developer input, or
     *                         {@code null} at end-of-input (the session then pauses awaiting
     *                         approval, resumable); must not be {@code null}.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public GreenfieldDriver(PhaseLoopFactory phaseLoopFactory, PhaseArtifactWriter artifactWriter,
            ApprovalGate approvalGate, DeveloperTurnSource turnSource) {
        this.phaseLoopFactory = Objects.requireNonNull(phaseLoopFactory, "phaseLoopFactory");
        this.artifactWriter = Objects.requireNonNull(artifactWriter, "artifactWriter");
        this.approvalGate = Objects.requireNonNull(approvalGate, "approvalGate");
        this.turnSource = Objects.requireNonNull(turnSource, "turnSource");
    }

    /**
     * Runs one greenfield session from the developer's initial use-case, driving the
     * requirements&rarr;design&rarr;tasks&rarr;implement state machine with a multi-turn dialogue
     * and an approval gate (approve = finalize) at each pre-approval phase.
     *
     * <p>The flow, starting at {@link GreenfieldPhase#initial()}:
     * <ol>
     *   <li><b>Pre-approval phase (requirements/design/tasks).</b> Run the phase as a multi-turn
     *       conversation (DCR-2). Each round the driver runs the phase-scoped loop (the pre-approval
     *       loops cannot write source, AC-1.4) with a prompt that injects the approved earlier-phase
     *       artifacts (cross-phase continuity, DCR-1), the in-phase transcript so far (in-phase
     *       continuity, DCR-2), and the current developer turn (the request for the very first
     *       round, then turns from the {@link DeveloperTurnSource}). If a round's turn surfaces an
     *       edge condition rather than completing, the session stops at that phase:
     *       {@link GreenfieldOutcome.Disposition#TURN_SURFACED}. Otherwise the driver writes the
     *       round's deliverable prose to the phase artifact in code (so the artifact reflects what
     *       is being approved) and consults the {@link ApprovalGate}. On <b>approval</b> the gate
     *       stamps the converged deliverable (AC-1.5; AC-2.5 traceability at the tasks gate) and the
     *       driver advances to {@link GreenfieldPhase#next()}. On a <b>non-approve</b> the driver
     *       takes another refining turn from the {@link DeveloperTurnSource} (AC-2.4) without
     *       stopping; when the source is exhausted (end-of-input) the session pauses:
     *       {@link GreenfieldOutcome.Disposition#AWAITING_APPROVAL} at this phase (no source written,
     *       AC-1.4; resumable).</li>
     *   <li><b>Terminal phase ({@link GreenfieldPhase#IMPLEMENT}).</b> Reached only after every
     *       pre-approval phase was approved (AC-2.3). Its loop runs once (the implement-one-task-at-
     *       a-time loop); on completion the session is done:
     *       {@link GreenfieldOutcome.Disposition#COMPLETED}, else TURN_SURFACED.</li>
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
        LOGGER.info("Greenfield session started: requirements->design->tasks->implement "
                + "(ADR-0012; multi-turn phase dialogue + approve-to-finalize per DCR-2)");

        GreenfieldPhase phase = GreenfieldPhase.initial();
        // DCR-1 cross-phase continuity: the approved earlier-phase artifact content, injected into
        // each later phase's prompt so design/tasks are authored against the approved upstream.
        Map<GreenfieldArtifact, String> approvedArtifacts = new EnumMap<>(GreenfieldArtifact.class);
        // The session's opening developer input opens the requirements phase (US-1, AC-1.1); after
        // that, refining turns come from the developer-turn source.
        String openingInput = request;

        while (true) {
            if (phase.isTerminal()) {
                return runImplementPhase(phase, approvedArtifacts);
            }
            PhaseResult result = runPreApprovalPhase(phase, approvedArtifacts, openingInput);
            openingInput = null;
            if (result.terminalOutcome != null) {
                return result.terminalOutcome;
            }
            // Approved: the converged deliverable was persisted and stamped; advance.
            rememberApprovedArtifact(phase, approvedArtifacts);
            GreenfieldPhase nextPhase = phase.next().orElseThrow();
            LOGGER.info("Greenfield phase {} approved (finalize); advancing to {}", phase, nextPhase);
            phase = nextPhase;
        }
    }

    /**
     * Runs one pre-approval phase as a multi-turn conversation (DCR-2) until the developer approves
     * (the phase finalizes and the driver advances) or the developer supplies no further turn (the
     * session pauses awaiting approval) or a turn surfaces an edge condition. Returns a
     * {@link PhaseResult}: an advance signal (no terminal outcome) on approval, or a terminal
     * {@link GreenfieldOutcome} otherwise.
     */
    private PhaseResult runPreApprovalPhase(GreenfieldPhase phase,
            Map<GreenfieldArtifact, String> approvedArtifacts, String openingInput) {
        List<PhaseTurn> inPhase = new ArrayList<>();
        // The phase opens automatically: its first round is driven by the phase framing (plus the
        // approved upstream artifacts, DCR-1) and the session's opening developer input — the
        // developer's initial use-case for requirements (US-1), or no extra developer text for a
        // later phase entered on approval. The developer does not have to type to *start* a phase;
        // only *refining* turns (after a non-approve) come from the developer-turn source.
        String developerInput = openingInput;
        LoopOutcome lastOutcome = null;
        while (true) {
            String prompt = phaseTurnPrompt(phase, approvedArtifacts, inPhase, developerInput);
            LoopOutcome outcome = phaseLoopFactory.loopFor(phase).run(prompt);
            if (!outcome.completed()) {
                LOGGER.warn("Greenfield phase {} turn surfaced ({}) before completing; stopping",
                        phase, outcome.stopReason());
                return PhaseResult.terminal(GreenfieldOutcome.turnSurfaced(phase, outcome));
            }
            lastOutcome = outcome;
            String deliverable = outcome.finalTextIfPresent().orElse("");
            // DCR-2 in-phase continuity: record this round (developer turn + the model's
            // deliverable) so the NEXT round's prompt carries the model's own prior turns within the
            // phase, fixing the in-phase discontinuity (ADR-0012).
            inPhase.add(new PhaseTurn(developerInput, deliverable));

            // DCR-1 / DCR-2 / AC-1.2 / AC-2.1: persistence is driver-guaranteed and captured at
            // approval. Write this round's latest deliverable to the phase artifact in code (design/-
            // confined, AC-1.4) BEFORE consulting the gate, so the artifact holds exactly what the
            // developer is asked to approve this round (the gate also reads it to present + to verify
            // tasks traceability). A non-approve leaves it unstamped and the next round supersedes it
            // (truncating write); an approve stamps this converged deliverable.
            authorPhaseArtifact(phase, deliverable);

            // ADR-0012 / AC-2.3: approve = finalize. The gate returns true only on an approved
            // advance (and, at the tasks gate, only when the driver-written breakdown is traceable —
            // AC-2.5); on true it has stamped the AC-1.5 approval into the converged artifact.
            if (approvalGate.approveAdvance(phase)) {
                return PhaseResult.approvedAdvance();
            }

            // Non-approve (a revision request, any non-affirmative answer, or a refused approval):
            // NOT persist-and-stop. Take another refining turn in the SAME phase conversation
            // (AC-2.4 revise-and-re-request); the loop re-offers the approval prompt. When the
            // developer supplies no further turn (end-of-input), the phase pauses awaiting approval —
            // no source written (AC-1.4), resumable (ADR-0012, AC-2.3).
            LOGGER.info("Greenfield phase {} not approved this round; continuing the phase dialogue "
                    + "(another refining turn, AC-2.4)", phase);
            developerInput = turnSource.nextTurn(phase);
            if (developerInput == null) {
                LOGGER.info("Greenfield phase {} has no further developer turn; pausing awaiting "
                        + "approval (AC-2.3, AC-1.4)", phase);
                return PhaseResult.terminal(GreenfieldOutcome.awaitingApproval(phase, lastOutcome));
            }
        }
    }

    /**
     * Runs the terminal {@link GreenfieldPhase#IMPLEMENT} phase once (reached only after every
     * pre-approval phase was approved, AC-2.3): the implement-one-task-at-a-time loop. On completion
     * the session is a clean greenfield success; a surfaced turn passes through for the run path to
     * map.
     */
    private GreenfieldOutcome runImplementPhase(
            GreenfieldPhase phase, Map<GreenfieldArtifact, String> approvedArtifacts) {
        String prompt = advancePrompt(phase, approvedArtifacts);
        LoopOutcome outcome = phaseLoopFactory.loopFor(phase).run(prompt);
        if (!outcome.completed()) {
            LOGGER.warn("Greenfield implementation turn surfaced ({}) before completing; stopping",
                    outcome.stopReason());
            return GreenfieldOutcome.turnSurfaced(phase, outcome);
        }
        LOGGER.info("Greenfield reached implementation; all phase gates passed (AC-2.3)");
        return GreenfieldOutcome.completed(outcome);
    }

    /**
     * Writes a pre-approval phase's latest-round deliverable to its design-doc artifact in code
     * (DCR-1; AC-1.2/AC-2.1). The deliverable is the phase turn's settled final text (the empty
     * string when the turn carried none); the write is a truncating {@code GreenfieldArtifactStore
     * .write()} confined to the target repo's {@code design/} directory (AC-1.4 preserved). A phase
     * that authors no artifact (only the terminal {@link GreenfieldPhase#IMPLEMENT}, never passed
     * here) is a no-op.
     */
    private void authorPhaseArtifact(GreenfieldPhase phase, String deliverable) {
        Optional<GreenfieldArtifact> artifact = GreenfieldArtifact.forPhase(phase);
        if (artifact.isEmpty()) {
            return;
        }
        artifactWriter.write(artifact.get(), deliverable);
        LOGGER.info("Greenfield {} deliverable authored to {} by the driver ({} chars) "
                + "(DCR-1/DCR-2; AC-1.2/AC-2.1)",
                phase, artifact.get().relativePath(), deliverable.length());
    }

    /**
     * Records an approved pre-approval phase's artifact content for injection into later phases'
     * prompts (DCR-1 cross-phase continuity). The content is read back from what the driver wrote
     * (the converged deliverable, sans the gate's appended approval stamp), keyed by its
     * {@link GreenfieldArtifact}.
     */
    private void rememberApprovedArtifact(
            GreenfieldPhase phase, Map<GreenfieldArtifact, String> approvedArtifacts) {
        GreenfieldArtifact.forPhase(phase).ifPresent(
                artifact -> approvedArtifacts.put(artifact, artifactWriter.read(artifact)));
    }

    /**
     * The prompt for one round of a pre-approval phase's multi-turn conversation. It names the phase
     * the session is in (so the phase-specific playbook loop turn picks up the right job), injects
     * the approved earlier-phase artifact content (cross-phase continuity, DCR-1), replays the
     * in-phase transcript so far so the model sees its own prior turns within the phase (in-phase
     * continuity, DCR-2), and ends with the current developer turn.
     */
    private static String phaseTurnPrompt(GreenfieldPhase phase,
            Map<GreenfieldArtifact, String> approvedArtifacts, List<PhaseTurn> inPhase,
            String developerInput) {
        StringBuilder prompt = new StringBuilder(advancePrompt(phase, approvedArtifacts));
        for (PhaseTurn turn : inPhase) {
            prompt.append("\n\n--- Earlier in this ").append(phase.name().toLowerCase())
                    .append(" conversation ---");
            if (turn.developerInput() != null) {
                prompt.append("\nDeveloper: ").append(turn.developerInput());
            }
            prompt.append("\nAgent: ").append(turn.deliverable());
        }
        // A later phase entered on approval opens without extra developer text (the phase framing +
        // approved upstream carry it); only the requirements phase's opening turn and refining turns
        // carry a developer line.
        if (developerInput != null) {
            prompt.append("\n\nDeveloper: ").append(developerInput);
        }
        return prompt.toString();
    }

    /**
     * The opening of a phase: it names the phase the session is in (so the phase-specific playbook
     * loop turn picks up the right job) and &mdash; for cross-phase continuity (DCR-1) &mdash;
     * injects the approved earlier-phase artifact content so the phase authors its deliverable
     * against the actual approved upstream rather than a discontinuous fresh start.
     */
    private static String advancePrompt(
            GreenfieldPhase phase, Map<GreenfieldArtifact, String> approvedArtifacts) {
        StringBuilder prompt = new StringBuilder("Proceed with the ")
                .append(phase.name().toLowerCase()).append(" phase.");
        for (Map.Entry<GreenfieldArtifact, String> entry : approvedArtifacts.entrySet()) {
            prompt.append("\n\n--- Approved ").append(entry.getKey().heading())
                    .append(" (").append(entry.getKey().relativePath()).append(") ---\n")
                    .append(entry.getValue());
        }
        return prompt.toString();
    }

    /** One round of a phase's multi-turn conversation: the developer turn and the model's reply. */
    private record PhaseTurn(String developerInput, String deliverable) {
    }

    /**
     * The outcome of running one pre-approval phase: either an approved advance (the phase
     * finalized; the driver advances to the next phase) or a terminal {@link GreenfieldOutcome}
     * (awaiting-approval pause, or a surfaced turn) that ends the session.
     */
    private static final class PhaseResult {
        private final GreenfieldOutcome terminalOutcome;

        private PhaseResult(GreenfieldOutcome terminalOutcome) {
            this.terminalOutcome = terminalOutcome;
        }

        static PhaseResult approvedAdvance() {
            return new PhaseResult(null);
        }

        static PhaseResult terminal(GreenfieldOutcome outcome) {
            return new PhaseResult(Objects.requireNonNull(outcome, "outcome"));
        }
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
         * @param prompt the prompt for this phase turn (each round carries the phase framing, the
         *               approved upstream artifacts, the in-phase transcript so far, and the current
         *               developer turn); non-blank.
         * @return the terminal outcome; never {@code null}.
         */
        LoopOutcome run(String prompt);
    }

    /**
     * Yields the phase-scoped {@link LoopTurn} for a given {@link GreenfieldPhase}. This is the seam
     * that carries the AC-1.4 enforcement: in production the factory returns a loop whose tool
     * registry withholds the Class-X source tools for a pre-approval phase
     * ({@link GreenfieldPhase#isPreApproval()}) across every turn of the multi-turn dialogue, and
     * offers the full toolset for the implementation phase. In tests it returns a scripted loop,
     * optionally asserting the phase it was asked for.
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
     * The driver-authored artifact-persistence seam (DCR-1, ADR-0012 amended; trigger refined by
     * DCR-2): persists a pre-approval phase's deliverable prose to its design-doc artifact in code,
     * and reads an approved artifact's content back for the cross-phase-continuity prompt injection.
     * In production both operations go through the target-repo {@link GreenfieldArtifactStore} (a
     * truncating {@code write()} confined to {@code design/} markdown, so AC-1.4 holds &mdash; the
     * source-write Class-X tools stay withheld from the pre-approval loops); in tests it is the same
     * real store over a temporary directory, or a scripted double. This seam is what makes
     * persistence <em>driver-guaranteed</em> rather than dependent on a model-emitted
     * {@code write_artifact} {@code toolUse} (AC-1.2/AC-2.1).
     */
    public interface PhaseArtifactWriter {

        /**
         * Writes {@code content} (the phase's settled deliverable prose) to {@code artifact}'s
         * design-doc path, replacing any existing content (DCR-1; AC-1.2/AC-2.1). The write is
         * confined to the target repo's {@code design/} directory (AC-1.4 preserved).
         *
         * @param artifact the design-doc artifact the phase authors; never {@code null}.
         * @param content  the deliverable content to persist (the round's final text; the empty
         *                 string when the turn carried none); must not be {@code null}.
         */
        void write(GreenfieldArtifact artifact, String content);

        /**
         * Reads back the current content of {@code artifact} for injection into a later phase's
         * prompt (DCR-1 cross-phase continuity). Called after the gate has approved the phase, so the
         * artifact exists; returns the empty string if it is unexpectedly absent.
         *
         * @param artifact the approved design-doc artifact to read; never {@code null}.
         * @return the artifact's current content, or the empty string if it is absent.
         */
        String read(GreenfieldArtifact artifact);
    }

    /**
     * The per-round approval seam (ADR-0012, AC-2.3; approve = finalize per DCR-2): given the phase
     * whose latest deliverable was just presented, the developer decides whether to approve
     * advancing to the next phase. The driver does not advance without an approval; a non-approve
     * keeps the phase conversation going (another refining turn, AC-2.4), and only when the
     * developer supplies no further turn does the session pause awaiting approval without writing
     * source (AC-1.4). On approval the gate records the AC-1.5 timestamp (and, at the tasks gate,
     * enforces AC-2.5 traceability over the driver-written breakdown).
     */
    @FunctionalInterface
    public interface ApprovalGate {

        /**
         * Whether the developer approves advancing out of the just-completed phase to the next
         * phase. On {@code true} the gate has finalized the phase (stamped the AC-1.5 approval into
         * the converged artifact, and verified AC-2.5 traceability at the tasks gate).
         *
         * @param completedPhase the phase whose deliverable was just presented; never {@code null}.
         *                       The advance target is {@link GreenfieldPhase#next()}.
         * @return {@code true} to advance (the phase is finalized), {@code false} to keep the phase
         *         conversation going (another refining turn).
         */
        boolean approveAdvance(GreenfieldPhase completedPhase);
    }

    /**
     * The per-turn developer-input seam for a pre-approval phase's multi-turn dialogue (DCR-2,
     * ADR-0012): supplies each refining developer turn within a phase, analogous to how the REPL
     * feeds turns. In production it is the same REPL input supplier the
     * {@code InteractiveGreenfieldApproval} reads (the developer types each refining turn at the
     * same prompt); in tests it is a scripted source. The session's <em>first</em> turn is the
     * developer's initial use-case ({@code run(request)}); every subsequent turn (and the opening of
     * each later phase) comes from this source. Returning {@code null} means end-of-input: the
     * current phase pauses awaiting approval (resumable), mirroring the REPL's clean EOF stop.
     */
    @FunctionalInterface
    public interface DeveloperTurnSource {

        /**
         * The next developer turn to drive the given phase's conversation with, or {@code null} at
         * end-of-input.
         *
         * @param phase the pre-approval phase the conversation is in; never {@code null}.
         * @return the developer's next turn (non-{@code null}, non-blank when present), or
         *         {@code null} when the developer supplies no further turn.
         */
        String nextTurn(GreenfieldPhase phase);
    }
}
