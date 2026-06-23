package com.srk.codingagent.workflow;

import com.srk.codingagent.loop.LoopOutcome;
import java.util.Objects;

/**
 * The terminal result of one {@link GreenfieldDriver} run: the phase the greenfield session
 * finished in, why it stopped, and the last agent-loop outcome the session produced. This is the
 * value the run path (one-shot / REPL) maps to a user-facing result; the driver never calls
 * {@code System.exit}.
 *
 * <p>The three terminal shapes, by {@link Disposition}:
 * <ul>
 *   <li><b>{@link Disposition#COMPLETED}.</b> Every phase ran and every approval gate was passed:
 *       the machine reached and ran {@link GreenfieldPhase#IMPLEMENT}. {@link #phase()} is
 *       {@link GreenfieldPhase#IMPLEMENT}. The clean greenfield success.</li>
 *   <li><b>{@link Disposition#AWAITING_APPROVAL}.</b> A phase completed and the agent presented its
 *       deliverable, but the developer did not approve advancing to the next phase (ADR-0012 "the
 *       agent does not advance a phase without explicit developer approval"; AC-2.3 for the
 *       advance into implementation). {@link #phase()} is the phase that was awaiting approval; the
 *       session stops at that gate without writing source (AC-1.4) and is resumable.</li>
 *   <li><b>{@link Disposition#TURN_SURFACED}.</b> A phase's agent-loop turn surfaced an edge
 *       condition (e.g. the context window was exceeded) rather than completing, so no approval
 *       gate was reached. {@link #phase()} is the phase whose turn surfaced; {@link #loopOutcome()}
 *       carries the surfaced reason for the run path to map.</li>
 * </ul>
 *
 * <p>{@link #loopOutcome()} is always present &mdash; every disposition is reached after at least
 * one phase turn ran and produced a {@link LoopOutcome}.
 *
 * @param disposition which terminal state the greenfield run reached; must not be {@code null}.
 * @param phase       the phase the session finished in (the implemented phase on a completed run,
 *                    the awaiting-approval phase on an approval stop, or the surfaced phase);
 *                    must not be {@code null}.
 * @param loopOutcome the last phase turn's agent-loop outcome; must not be {@code null}.
 */
public record GreenfieldOutcome(
        Disposition disposition, GreenfieldPhase phase, LoopOutcome loopOutcome) {

    /** Which terminal state the greenfield requirements&rarr;design&rarr;tasks&rarr;implement run reached. */
    public enum Disposition {

        /** Every phase ran and every approval gate passed; the machine reached implementation. */
        COMPLETED,

        /**
         * A phase completed and its deliverable was presented, but the developer did not approve
         * advancing; the session stops at the gate (ADR-0012, AC-2.3) without writing source.
         */
        AWAITING_APPROVAL,

        /**
         * A phase's agent-loop turn surfaced an edge condition before an approval gate was reached
         * (the loop outcome carries why).
         */
        TURN_SURFACED
    }

    /**
     * Validates the outcome.
     *
     * @throws NullPointerException if any component is {@code null}.
     */
    public GreenfieldOutcome {
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(loopOutcome, "loopOutcome");
    }

    /**
     * Builds a {@link Disposition#COMPLETED} outcome: every approval gate passed and the
     * implementation phase ran.
     *
     * @param loopOutcome the implementation phase's loop outcome; must not be {@code null}.
     * @return a completed greenfield outcome at {@link GreenfieldPhase#IMPLEMENT}.
     */
    public static GreenfieldOutcome completed(LoopOutcome loopOutcome) {
        return new GreenfieldOutcome(Disposition.COMPLETED, GreenfieldPhase.IMPLEMENT, loopOutcome);
    }

    /**
     * Builds a {@link Disposition#AWAITING_APPROVAL} outcome: the named phase completed and
     * presented its deliverable, but the developer did not approve advancing (the session stops at
     * the gate; AC-2.3).
     *
     * @param phase       the phase awaiting approval to advance; must not be {@code null}.
     * @param loopOutcome that phase's completed loop outcome; must not be {@code null}.
     * @return an awaiting-approval greenfield outcome at {@code phase}.
     */
    public static GreenfieldOutcome awaitingApproval(
            GreenfieldPhase phase, LoopOutcome loopOutcome) {
        return new GreenfieldOutcome(Disposition.AWAITING_APPROVAL, phase, loopOutcome);
    }

    /**
     * Builds a {@link Disposition#TURN_SURFACED} outcome: the named phase's agent-loop turn surfaced
     * an edge condition before an approval gate was reached.
     *
     * @param phase       the phase whose turn surfaced; must not be {@code null}.
     * @param loopOutcome the surfaced loop outcome carrying the edge reason; must not be
     *                    {@code null}.
     * @return a turn-surfaced greenfield outcome at {@code phase}.
     */
    public static GreenfieldOutcome turnSurfaced(
            GreenfieldPhase phase, LoopOutcome loopOutcome) {
        return new GreenfieldOutcome(Disposition.TURN_SURFACED, phase, loopOutcome);
    }

    /**
     * Whether the greenfield run reached the clean success state: every approval gate passed and the
     * implementation phase ran.
     *
     * @return {@code true} only for a {@link Disposition#COMPLETED} outcome.
     */
    public boolean completed() {
        return disposition == Disposition.COMPLETED;
    }
}
