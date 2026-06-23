package com.srk.codingagent.workflow;

import java.util.Optional;

/**
 * The greenfield workflow's phase state machine (component C3, ADR-0012 greenfield side): the
 * ordered sequence a brand-new project moves through &mdash;
 * requirements&nbsp;&rarr;&nbsp;design&nbsp;&rarr;&nbsp;tasks&nbsp;&rarr;&nbsp;implement
 * (ADR-0012 "Phases + artifacts", US-1/US-2/US-3). Unlike the brownfield driver &mdash; which is
 * largely emergent model behaviour primed by a playbook &mdash; ADR-0012 makes greenfield a
 * genuine phase state machine with explicit per-phase approval gates between phases. This enum is
 * that machine: its constant order is the legal advance order, and {@link #next()} yields the
 * phase reached on approval (empty at the terminal phase).
 *
 * <p><b>Pre-approval vs. post-approval (AC-1.1, AC-1.3, AC-1.4, AC-2.3).</b> The
 * requirements-gathering and design phases are <em>pre-approval</em> phases: the agent shapes the
 * requirements/design dialogue before any source is written (AC-1.1), asks for confirmation
 * rather than writing source if implementation is requested early (AC-1.3), and does not execute
 * a Class X operation against source files while in that dialogue (AC-1.4). Implementation begins
 * only after the design and task breakdown are approved (AC-2.3). {@link #isPreApproval()}
 * captures which side of that line a phase sits on, so the driver can withhold the Class-X source
 * tools (AC-1.4) and gate the advance into implementation (AC-2.3).
 *
 * <p>The phase the developer must approve to <em>leave</em> is every non-terminal phase: ADR-0012
 * pins "the agent does not advance a phase without explicit developer approval". {@link #next()}
 * is the advance target; the driver consults the approval gate before taking it.
 *
 * <p>Artifact <em>authoring</em> for each phase (writing the requirements/design/tasks markdown
 * into the target repo, approval timestamps, AC&rarr;task traceability) is a separate task (T-3.2)
 * that plugs into this machine; this enum is the orchestration skeleton, not the artifact writer.
 */
public enum GreenfieldPhase {

    /**
     * Requirements gathering: shape personas, user stories, acceptance criteria, and NFRs through
     * dialogue before any source is written (US-1, AC-1.1). A pre-approval phase &mdash; no Class X
     * operation against source files runs here (AC-1.4).
     */
    REQUIREMENTS(true),

    /**
     * Design: turn the agreed requirements into an overview, architecture (+ ADRs), data model,
     * APIs, and operations (US-2). A pre-approval phase &mdash; still no source writes (AC-1.4); the
     * agent writes only design markdown until the breakdown is approved (ADR-0012).
     */
    DESIGN(true),

    /**
     * Tasks: break the design into discrete, traceable tasks (US-2, ADR-0012 "Tasks"). The last
     * pre-approval phase &mdash; leaving it into {@link #IMPLEMENT} is the approval gate that lets
     * implementation begin (AC-2.3).
     */
    TASKS(true),

    /**
     * Implementation: implement the planned tasks one at a time, each verified before the next
     * (US-3). The terminal phase &mdash; reached only after the design and task breakdown are
     * approved (AC-2.3); the source-write Class X tools are available here, withheld in the
     * pre-approval phases (AC-1.4).
     */
    IMPLEMENT(false);

    private final boolean preApproval;

    GreenfieldPhase(boolean preApproval) {
        this.preApproval = preApproval;
    }

    /**
     * The first phase of a greenfield session: requirements gathering begins before any source is
     * written (AC-1.1).
     *
     * @return {@link #REQUIREMENTS}; never {@code null}.
     */
    public static GreenfieldPhase initial() {
        return REQUIREMENTS;
    }

    /**
     * Whether this phase is a <em>pre-approval</em> phase &mdash; one of the
     * requirements/design/tasks dialogue phases that runs before the design + task breakdown are
     * approved and implementation begins (AC-2.3). While in a pre-approval phase the agent does not
     * execute a Class X operation against source files (AC-1.4): the driver withholds the
     * source-write tools so a source write is structurally impossible, not merely discouraged.
     *
     * @return {@code true} for {@link #REQUIREMENTS}, {@link #DESIGN}, {@link #TASKS}; {@code false}
     *         for {@link #IMPLEMENT}.
     */
    public boolean isPreApproval() {
        return preApproval;
    }

    /**
     * Whether this phase is the terminal phase of the machine (no further phase follows). The
     * terminal phase is {@link #IMPLEMENT}; reaching it means every approval gate was passed.
     *
     * @return {@code true} only for {@link #IMPLEMENT}.
     */
    public boolean isTerminal() {
        return next().isEmpty();
    }

    /**
     * The phase reached by advancing from this one when the developer approves the transition
     * (ADR-0012 "the agent does not advance a phase without explicit developer approval"). The
     * advance follows the declared enum order; the terminal phase has no successor.
     *
     * @return the next phase, or {@link Optional#empty()} if this is the terminal phase
     *         ({@link #IMPLEMENT}).
     */
    public Optional<GreenfieldPhase> next() {
        GreenfieldPhase[] phases = values();
        int index = ordinal();
        return index + 1 < phases.length ? Optional.of(phases[index + 1]) : Optional.empty();
    }
}
