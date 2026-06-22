package com.srk.codingagent.workflow;

import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.loop.VerifyOutcome;
import java.util.Objects;
import java.util.Optional;

/**
 * The terminal result of one {@link BrownfieldDriver} run: what the agent loop produced for the
 * understand&rarr;change turn, and &mdash; when that turn completed and a change was made &mdash;
 * what the verify step (AC-5.3) found. This is the value the run path (one-shot / REPL) maps to
 * a user-facing result and an exit code; the driver never calls {@code System.exit}.
 *
 * <p>The three terminal shapes, by {@link Disposition}:
 * <ul>
 *   <li><b>{@link Disposition#VERIFIED}.</b> The understand&rarr;change turn completed
 *       ({@code end_turn}) and the configured build/test command verified the change within the
 *       bound (AC-5.3, RD-10). {@link #verifyOutcome()} is present and
 *       {@link VerifyOutcome#verified()} is {@code true}. The clean brownfield success.</li>
 *   <li><b>{@link Disposition#VERIFY_EXHAUSTED}.</b> The turn completed but the change did not
 *       verify within {@code verifyMaxIterations} attempts (AC-3.4, AC-20.5; state machine A,
 *       S7 "verify-exhausted"). {@link #verifyOutcome()} is present and carries the last failing
 *       run's output, which the run path surfaces (AC-20.5).</li>
 *   <li><b>{@link Disposition#NOT_VERIFIED}.</b> No verification was performed: either the
 *       understand&rarr;change turn surfaced an edge condition rather than completing (so there
 *       was no completed change to verify), or no test command is configured (AC-20.6 &mdash;
 *       {@link VerifyOutcome.Kind#NO_TEST_COMMAND}, present but with nothing verified). The loop
 *       outcome carries why; the run path decides how to surface it.</li>
 * </ul>
 *
 * <p>{@link #loopOutcome()} is always present &mdash; the understand&rarr;change turn always runs
 * and produces a {@link LoopOutcome}. {@link #verifyOutcome()} is present only when the verify
 * step ran (i.e. the turn completed); it is absent when the turn surfaced an edge condition
 * before any change could be verified.
 *
 * @param disposition  which terminal state the brownfield run reached; must not be {@code null}.
 * @param loopOutcome  the understand&rarr;change agent-loop outcome; must not be {@code null}.
 * @param verifyOutcome the verify-step outcome, or {@code null} when verification did not run
 *                      (the turn surfaced before completing).
 */
public record BrownfieldOutcome(
        Disposition disposition, LoopOutcome loopOutcome, VerifyOutcome verifyOutcome) {

    /** Which terminal state the brownfield understand&rarr;change&rarr;verify run reached. */
    public enum Disposition {

        /** The change was made and the configured test command verified it (AC-5.3, RD-10). */
        VERIFIED,

        /**
         * The change was made but did not verify within the bound; surfaced with the failure
         * output (AC-3.4, AC-20.5).
         */
        VERIFY_EXHAUSTED,

        /**
         * No verification disposition was reached: the turn surfaced an edge condition before a
         * change could be verified, or no test command is configured (AC-20.6).
         */
        NOT_VERIFIED
    }

    /**
     * Validates the outcome's invariants so an inconsistent {@code BrownfieldOutcome} cannot
     * exist: a {@link Disposition#VERIFIED} / {@link Disposition#VERIFY_EXHAUSTED} disposition
     * must carry a verify outcome (a verification ran), and that verify outcome must agree with
     * the disposition (verified iff {@code VERIFIED}).
     *
     * @throws NullPointerException     if {@code disposition} or {@code loopOutcome} is
     *                                  {@code null}.
     * @throws IllegalArgumentException if the {@code verifyOutcome} shape does not match the
     *                                  {@code disposition}.
     */
    public BrownfieldOutcome {
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(loopOutcome, "loopOutcome");
        switch (disposition) {
            case VERIFIED -> {
                requireVerifyPresent(verifyOutcome, disposition);
                if (!verifyOutcome.verified()) {
                    throw new IllegalArgumentException(
                            "VERIFIED must carry a verified() verify outcome");
                }
            }
            case VERIFY_EXHAUSTED -> {
                requireVerifyPresent(verifyOutcome, disposition);
                if (verifyOutcome.verified()) {
                    throw new IllegalArgumentException(
                            "VERIFY_EXHAUSTED must not carry a verified() verify outcome");
                }
            }
            case NOT_VERIFIED -> {
                // verifyOutcome may be null (turn surfaced) or NO_TEST_COMMAND (nothing verified);
                // either way it must not be a verified() outcome.
                if (verifyOutcome != null && verifyOutcome.verified()) {
                    throw new IllegalArgumentException(
                            "NOT_VERIFIED must not carry a verified() verify outcome");
                }
            }
            // The switch is exhaustive over Disposition (all three constants handled above); no
            // default arm is needed and an unreachable one would only be dead code.
        }
    }

    private static void requireVerifyPresent(VerifyOutcome verifyOutcome, Disposition disposition) {
        if (verifyOutcome == null) {
            throw new IllegalArgumentException(disposition + " must carry a verify outcome");
        }
    }

    /**
     * Builds a {@link Disposition#VERIFIED} outcome (the change was made and verified).
     *
     * @param loopOutcome   the completed understand&rarr;change loop outcome; must not be
     *                      {@code null}.
     * @param verifyOutcome the verifying verify outcome ({@link VerifyOutcome#verified()} true);
     *                      must not be {@code null}.
     * @return a verified brownfield outcome.
     */
    public static BrownfieldOutcome verified(LoopOutcome loopOutcome, VerifyOutcome verifyOutcome) {
        return new BrownfieldOutcome(Disposition.VERIFIED, loopOutcome, verifyOutcome);
    }

    /**
     * Builds a {@link Disposition#VERIFY_EXHAUSTED} outcome (the change was made but did not
     * verify within the bound; AC-20.5).
     *
     * @param loopOutcome   the completed understand&rarr;change loop outcome; must not be
     *                      {@code null}.
     * @param verifyOutcome the exhausted verify outcome carrying the last failure's output; must
     *                      not be {@code null}.
     * @return a verify-exhausted brownfield outcome.
     */
    public static BrownfieldOutcome verifyExhausted(
            LoopOutcome loopOutcome, VerifyOutcome verifyOutcome) {
        return new BrownfieldOutcome(Disposition.VERIFY_EXHAUSTED, loopOutcome, verifyOutcome);
    }

    /**
     * Builds a {@link Disposition#NOT_VERIFIED} outcome where the understand&rarr;change turn
     * surfaced an edge condition before any change could be verified (no verify step ran).
     *
     * @param loopOutcome the surfaced loop outcome; must not be {@code null}.
     * @return a not-verified brownfield outcome with no verify outcome.
     */
    public static BrownfieldOutcome turnSurfaced(LoopOutcome loopOutcome) {
        return new BrownfieldOutcome(Disposition.NOT_VERIFIED, loopOutcome, null);
    }

    /**
     * Builds a {@link Disposition#NOT_VERIFIED} outcome where the turn completed but there was
     * no test command to verify against (AC-20.6 &mdash; {@link VerifyOutcome.Kind#NO_TEST_COMMAND}).
     *
     * @param loopOutcome   the completed loop outcome; must not be {@code null}.
     * @param verifyOutcome the {@link VerifyOutcome.Kind#NO_TEST_COMMAND} outcome; must not be
     *                      {@code null} and must not be a verified outcome.
     * @return a not-verified brownfield outcome carrying the no-test-command verify outcome.
     */
    public static BrownfieldOutcome noTestCommand(
            LoopOutcome loopOutcome, VerifyOutcome verifyOutcome) {
        return new BrownfieldOutcome(
                Disposition.NOT_VERIFIED, loopOutcome,
                Objects.requireNonNull(verifyOutcome, "verifyOutcome"));
    }

    /**
     * Whether the brownfield run reached the clean success state: the change was made and the
     * configured test command verified it (AC-5.3). This is the success predicate the run path
     * branches on.
     *
     * @return {@code true} only for a {@link Disposition#VERIFIED} outcome.
     */
    public boolean verified() {
        return disposition == Disposition.VERIFIED;
    }

    /**
     * The verify-step outcome, present when verification ran (the understand&rarr;change turn
     * completed), absent when the turn surfaced an edge condition before any change.
     *
     * @return the verify outcome, or {@link Optional#empty()} when no verification ran.
     */
    public Optional<VerifyOutcome> verifyOutcomeIfPresent() {
        return Optional.ofNullable(verifyOutcome);
    }
}
