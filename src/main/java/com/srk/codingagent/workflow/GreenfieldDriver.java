package com.srk.codingagent.workflow;

import com.srk.codingagent.loop.AgentLoop;
import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.tool.GreenfieldArtifactStore;
import java.util.EnumMap;
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
 * <p><b>Driver-authored phase deliverables (DCR-1, ADR-0012 amended 2026-06-23).</b> Persistence of
 * each pre-approval phase deliverable is <em>driver-guaranteed</em>, not model-tool-dependent. On
 * each pre-approval phase's {@code END_TURN}, the driver captures the model's final deliverable prose
 * ({@link LoopOutcome#finalTextIfPresent()}) and writes it to that phase's artifact
 * ({@link GreenfieldArtifact#forPhase(GreenfieldPhase)} &rarr;
 * {@code design/00-requirements.md} / {@code 01-design.md} / {@code 02-tasks.md}) in code via the
 * injected {@link PhaseArtifactWriter} (a truncating {@code GreenfieldArtifactStore.write()}) &mdash;
 * <em>before</em> the {@link ApprovalGate} stamps the AC-1.5 approval (AC-1.2/AC-2.1). It does not
 * depend on the live model emitting a {@code write_artifact} {@code toolUse}, which empirically it
 * does not. The {@code write_artifact} design-doc tool stays registered/available in the pre-approval
 * registry but is no longer the persistence mechanism.
 *
 * <p><b>Transcript continuity (DCR-1).</b> Because each phase runs as a fresh conversation, the model
 * cannot see the approved earlier artifact in its own transcript. The driver therefore injects the
 * approved earlier-phase artifact content into each later phase's prompt (requirements &rarr; design,
 * requirements + design &rarr; tasks), so design and tasks are authored against the actual approved
 * upstream content rather than a discontinuous fresh start.
 *
 * <p><b>AC-1.4 preserved.</b> The driver's in-code artifact write is confined to the target project's
 * {@code design/} markdown (the {@link GreenfieldArtifactStore} the {@link PhaseArtifactWriter} writes
 * through refuses any path outside {@code design/}); the source-write Class-X tools
 * ({@code write_file}/{@code edit_file}/{@code run_command}) stay withheld from the pre-approval phase
 * loops. The change is to <em>who persists the deliverable</em> (the driver, in code), not to
 * <em>what may be written before approval</em> (still only {@code design/} markdown).
 *
 * <p><b>Seams (testability).</b> The driver's orchestration is exercised in isolation through three
 * injected seams: the {@link PhaseLoopFactory} (which, given a {@link GreenfieldPhase}, yields the
 * {@link AgentLoop#run(String)}-shaped {@link LoopTurn} for that phase &mdash; scripted in tests,
 * the phase-scoped real loop in production), the {@link PhaseArtifactWriter} (which persists a
 * completed pre-approval phase's deliverable prose to its artifact in code &mdash; the real
 * {@code GreenfieldArtifactStore.write()} in production, scriptable/real in tests), and the
 * {@link ApprovalGate} (the per-phase yes/no the developer gives at each gate &mdash; scripted in
 * tests, a real interactive prompt in production).
 *
 * <p>Not thread-safe: one driver runs one greenfield session on a single thread (the C2 invariant
 * the loop inherits &mdash; one in-flight model call per conversation).
 */
public final class GreenfieldDriver {

    private static final Logger LOGGER = LoggerFactory.getLogger(GreenfieldDriver.class);

    private final PhaseLoopFactory phaseLoopFactory;
    private final PhaseArtifactWriter artifactWriter;
    private final ApprovalGate approvalGate;

    /**
     * Creates a greenfield driver over its three injected seams.
     *
     * @param phaseLoopFactory yields the phase-scoped agent-loop turn for each phase (the
     *                         pre-approval phases' loops withhold the Class-X source tools, AC-1.4);
     *                         must not be {@code null}.
     * @param artifactWriter   persists each completed pre-approval phase's deliverable prose to its
     *                         design-doc artifact in code (DCR-1; AC-1.2/AC-2.1, {@code design/}-
     *                         confined so AC-1.4 is preserved); must not be {@code null}.
     * @param approvalGate     the per-phase approval seam consulted before advancing each
     *                         non-terminal phase (ADR-0012, AC-2.3); must not be {@code null}.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public GreenfieldDriver(PhaseLoopFactory phaseLoopFactory, PhaseArtifactWriter artifactWriter,
            ApprovalGate approvalGate) {
        this.phaseLoopFactory = Objects.requireNonNull(phaseLoopFactory, "phaseLoopFactory");
        this.artifactWriter = Objects.requireNonNull(artifactWriter, "artifactWriter");
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
     *       later phases run with a phase-advance prompt that injects the approved earlier-phase
     *       artifact content (DCR-1 transcript continuity).</li>
     *   <li>If the turn surfaced an edge condition rather than completing, no gate is reached:
     *       return {@link GreenfieldOutcome.Disposition#TURN_SURFACED} carrying the surfaced
     *       outcome.</li>
     *   <li>If the turn completed and the phase is terminal ({@link GreenfieldPhase#IMPLEMENT}),
     *       the session is done: return {@link GreenfieldOutcome.Disposition#COMPLETED}.</li>
     *   <li>If the turn completed and the phase is a pre-approval authoring phase, the driver writes
     *       that phase's deliverable prose ({@link LoopOutcome#finalTextIfPresent()}) to the phase
     *       artifact in code via the {@link PhaseArtifactWriter} (DCR-1; AC-1.2/AC-2.1) <em>before</em>
     *       consulting the gate &mdash; so the artifact is driver-guaranteed regardless of whether the
     *       model emitted any tool call.</li>
     *   <li>The driver then consults the {@link ApprovalGate}. On approval, it remembers the
     *       approved artifact content (for injection into the next phase's prompt), advances to
     *       {@link GreenfieldPhase#next()} and repeats. On a declined or deferred approval, stop at
     *       this gate without writing source: return
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
        LOGGER.info("Greenfield session started: requirements->design->tasks->implement "
                + "(ADR-0012, driver-authored persistence per DCR-1)");

        GreenfieldPhase phase = GreenfieldPhase.initial();
        String prompt = request;
        // DCR-1 transcript continuity: the approved earlier-phase artifact content, injected into
        // each later phase's prompt so design/tasks are authored against the approved upstream.
        Map<GreenfieldArtifact, String> approvedArtifacts = new EnumMap<>(GreenfieldArtifact.class);
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

            // DCR-1 / AC-1.2 / AC-2.1: persistence is driver-guaranteed. Before the gate stamps,
            // the driver writes this pre-approval phase's deliverable prose (the END_TURN final
            // text) to its design-doc artifact in code (design/-confined, so AC-1.4 holds). This
            // does NOT depend on the model having emitted a write_artifact tool_use.
            authorPhaseArtifact(phase, outcome);

            // ADR-0012 / AC-2.3: the agent does not advance a phase without explicit developer
            // approval. The next phase may be IMPLEMENT (the gate that lets source writing begin),
            // so a declined gate stops here without writing source (AC-1.4). At the tasks gate the
            // ArtifactApprovalGate also enforces AC-2.5 traceability over the driver-written artifact.
            if (!approvalGate.approveAdvance(phase)) {
                LOGGER.info("Greenfield phase {} not approved to advance; awaiting approval at the "
                        + "gate, no source written (AC-2.3, AC-1.4)", phase);
                return GreenfieldOutcome.awaitingApproval(phase, outcome);
            }

            rememberApprovedArtifact(phase, approvedArtifacts);
            GreenfieldPhase nextPhase = phase.next().orElseThrow();
            LOGGER.info("Greenfield phase {} approved; advancing to {}", phase, nextPhase);
            phase = nextPhase;
            prompt = advancePrompt(nextPhase, approvedArtifacts);
        }
    }

    /**
     * Writes a completed pre-approval phase's deliverable to its design-doc artifact in code (DCR-1;
     * AC-1.2/AC-2.1). The deliverable is the phase turn's settled final text
     * ({@link LoopOutcome#finalTextIfPresent()}, the empty string when the turn carried none); the
     * write is a truncating {@code GreenfieldArtifactStore.write()} confined to the target repo's
     * {@code design/} directory (AC-1.4 preserved). A phase that authors no artifact
     * ({@link GreenfieldArtifact#forPhase(GreenfieldPhase)} empty &mdash; only the terminal
     * {@link GreenfieldPhase#IMPLEMENT}, which this is never called for) is a no-op.
     */
    private void authorPhaseArtifact(GreenfieldPhase phase, LoopOutcome outcome) {
        Optional<GreenfieldArtifact> artifact = GreenfieldArtifact.forPhase(phase);
        if (artifact.isEmpty()) {
            return;
        }
        String deliverable = outcome.finalTextIfPresent().orElse("");
        artifactWriter.write(artifact.get(), deliverable);
        LOGGER.info("Greenfield {} deliverable authored to {} by the driver ({} chars) "
                + "(DCR-1; AC-1.2/AC-2.1)", phase, artifact.get().relativePath(), deliverable.length());
    }

    /**
     * Records an approved pre-approval phase's artifact content for injection into later phases'
     * prompts (DCR-1 transcript continuity). The content is read back from what the driver just
     * wrote (the deliverable, sans the gate's appended approval stamp), keyed by its
     * {@link GreenfieldArtifact}.
     */
    private void rememberApprovedArtifact(
            GreenfieldPhase phase, Map<GreenfieldArtifact, String> approvedArtifacts) {
        GreenfieldArtifact.forPhase(phase).ifPresent(
                artifact -> approvedArtifacts.put(artifact, artifactWriter.read(artifact)));
    }

    /**
     * The prompt that opens a phase the developer just approved advancing into: it names the phase
     * the session has entered so the (phase-specific playbook) loop turn picks up the right job, and
     * &mdash; for transcript continuity (DCR-1) &mdash; injects the approved earlier-phase artifact
     * content so the (fresh-conversation) phase turn authors its deliverable against the actual
     * approved upstream rather than a discontinuous fresh start. The initial request opens the first
     * ({@link GreenfieldPhase#REQUIREMENTS}) phase; this builds the prompt for every subsequent phase.
     */
    private static String advancePrompt(
            GreenfieldPhase phase, Map<GreenfieldArtifact, String> approvedArtifacts) {
        StringBuilder prompt = new StringBuilder("The previous phase is approved. Proceed with the ")
                .append(phase.name().toLowerCase()).append(" phase.");
        for (Map.Entry<GreenfieldArtifact, String> entry : approvedArtifacts.entrySet()) {
            prompt.append("\n\n--- Approved ").append(entry.getKey().heading())
                    .append(" (").append(entry.getKey().relativePath()).append(") ---\n")
                    .append(entry.getValue());
        }
        return prompt.toString();
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
     * The driver-authored artifact-persistence seam (DCR-1, ADR-0012 amended): persists a completed
     * pre-approval phase's deliverable prose to its design-doc artifact in code, and reads an approved
     * artifact's content back for the transcript-continuity prompt injection. In production both
     * operations go through the target-repo {@link GreenfieldArtifactStore} (a truncating
     * {@code write()} confined to {@code design/} markdown, so AC-1.4 holds &mdash; the source-write
     * Class-X tools stay withheld from the pre-approval loops); in tests it is the same real store
     * over a temporary directory, or a scripted double. This seam is what makes persistence
     * <em>driver-guaranteed</em> rather than dependent on a model-emitted {@code write_artifact}
     * {@code toolUse} (AC-1.2/AC-2.1).
     */
    public interface PhaseArtifactWriter {

        /**
         * Writes {@code content} (the phase's settled deliverable prose) to {@code artifact}'s
         * design-doc path, replacing any existing content (DCR-1; AC-1.2/AC-2.1). The write is
         * confined to the target repo's {@code design/} directory (AC-1.4 preserved).
         *
         * @param artifact the design-doc artifact the completed phase authors; never {@code null}.
         * @param content  the deliverable content to persist (the END_TURN final text; the empty
         *                 string when the turn carried none); must not be {@code null}.
         */
        void write(GreenfieldArtifact artifact, String content);

        /**
         * Reads back the current content of {@code artifact} for injection into a later phase's
         * prompt (DCR-1 transcript continuity). Called after the gate has approved the phase, so the
         * artifact exists; returns the empty string if it is unexpectedly absent.
         *
         * @param artifact the approved design-doc artifact to read; never {@code null}.
         * @return the artifact's current content, or the empty string if it is absent.
         */
        String read(GreenfieldArtifact artifact);
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
