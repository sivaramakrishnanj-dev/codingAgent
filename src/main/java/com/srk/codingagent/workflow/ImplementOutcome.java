package com.srk.codingagent.workflow;

import com.srk.codingagent.loop.VerifyOutcome;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The terminal result of one {@link GreenfieldImplementLoop} run: which tasks the
 * implement-every-task-in-order loop implemented and marked complete on implementation, and &mdash;
 * once every task is implemented &mdash; how the single <em>end-of-phase</em> verify gated the phase
 * (component C3, ADR-0012 implement clause amended by DCR-7; US-3, AC-3.2/3.3/3.6, AC-3.4/AC-20.5).
 * This is the value the run path maps to a user-facing result; the implement loop never calls
 * {@code System.exit}.
 *
 * <p><b>DCR-7 — verify at end of phase, mark complete on implementation.</b> The greenfield IMPLEMENT
 * phase is a flat task list with no milestone substructure, so the verify boundary is end-of-phase
 * (a single configured build/test run after the last task), not per task (AC-3.2, ADR-0012). The
 * implement loop therefore implements <em>every</em> task in breakdown order and marks each complete
 * <em>as it is implemented</em> (a durable on-disk completion marker, AC-3.3) &mdash; a task is marked
 * complete on implementation, not on passing an individual verify. There is no per-task verify cycle
 * in the loop body; instead, once every task is implemented, the loop runs the configured build/test
 * <em>once</em> (testable-only, AC-3.2) via the reused T-1.4 {@link com.srk.codingagent.loop.VerifyLoop}
 * and maps that one verify outcome to this result.
 *
 * <p>The four terminal shapes, by {@link Disposition}:
 * <ul>
 *   <li><b>{@link Disposition#ALL_IMPLEMENTED}.</b> Every task in the breakdown was implemented and
 *       marked complete in the task-breakdown artifact, in breakdown order (AC-3.2/3.3), and the
 *       end-of-phase verify either passed (a {@link VerifyOutcome.Kind#VERIFIED} verify, the clean
 *       phase success) or did not run (no verify collaborators were wired &mdash; the per-task unit
 *       path). {@link #implementedTasks()} lists the stable ids of the tasks marked complete (in
 *       order); {@link #verifyOutcomeIfPresent()} carries the passing verify when one ran.</li>
 *   <li><b>{@link Disposition#VERIFY_FAILED}.</b> Every task was implemented, but the end-of-phase
 *       verify did not pass within {@code NFR-VERIFY-MAX-ITERATIONS} attempts (AC-3.4/AC-20.5): the
 *       phase stops and surfaces the failure rather than continuing silently. {@link #verifyOutcome()}
 *       is the {@link VerifyOutcome.Kind#EXHAUSTED} outcome carrying the last failing run's output to
 *       surface (AC-20.5). The implemented ids are still listed (the tasks were implemented; only the
 *       verify did not pass).</li>
 *   <li><b>{@link Disposition#COMPLETE_WITH_WARNING}.</b> Every task was implemented and marked
 *       complete, but no test command is configured, so the end-of-phase verify was skipped with a
 *       single warning and the phase terminates deterministically (AC-3.6 &mdash; a
 *       complete-with-warning terminal success, consistent with the brownfield no-verify precedent;
 *       NOT a hard-stop, NOT a re-loop into a fresh implement attempt). {@link #verifyOutcome()} is
 *       the {@link VerifyOutcome.Kind#NO_TEST_COMMAND} outcome; the implemented ids are listed.</li>
 *   <li><b>{@link Disposition#NO_TASKS}.</b> The approved task-breakdown artifact carried no
 *       recognizable task to implement (an empty or task-less breakdown). The loop has nothing to do
 *       and reports it. {@link #implementedTasks()} is empty and no verify ran.</li>
 * </ul>
 *
 * @param disposition      which terminal state the implement loop reached; must not be {@code null}.
 * @param implementedTasks the stable ids of the tasks implemented-and-marked-complete, in breakdown
 *                         order (AC-3.2/3.3); never {@code null} (empty when none implemented).
 * @param verifyOutcome    the end-of-phase verify outcome, or {@code null} when no verify ran (a
 *                         {@link Disposition#NO_TASKS} result, or an {@link Disposition#ALL_IMPLEMENTED}
 *                         result whose loop carried no verify collaborators). When present it must
 *                         agree with the disposition (see the compact constructor's invariants).
 */
public record ImplementOutcome(
        Disposition disposition, List<String> implementedTasks, VerifyOutcome verifyOutcome) {

    /** Which terminal state the implement-every-task-in-order loop reached (DCR-7). */
    public enum Disposition {

        /**
         * Every task was implemented and marked complete in breakdown order, as it was implemented
         * (AC-3.2/3.3, DCR-7), and the end-of-phase verify passed (or did not run). The clean phase
         * success.
         */
        ALL_IMPLEMENTED,

        /**
         * Every task was implemented, but the end-of-phase verify did not pass within
         * {@code NFR-VERIFY-MAX-ITERATIONS} attempts; the phase stops and surfaces the failure
         * (AC-3.4/AC-20.5, DCR-7).
         */
        VERIFY_FAILED,

        /**
         * Every task was implemented and marked complete, but no test command is configured, so the
         * end-of-phase verify was skipped with a single warning and the phase terminates
         * deterministically &mdash; a complete-with-warning terminal success (AC-3.6, DCR-7), NOT a
         * hard-stop and NOT a re-loop into a fresh implement attempt.
         */
        COMPLETE_WITH_WARNING,

        /** The approved breakdown carried no recognizable task to implement. */
        NO_TASKS
    }

    /**
     * Validates the outcome's invariants so an inconsistent {@code ImplementOutcome} cannot exist: a
     * {@link Disposition#NO_TASKS} outcome implemented nothing (its task list is empty) and ran no
     * verify; a {@link Disposition#VERIFY_FAILED} outcome carries a non-verified verify outcome (the
     * verify did not pass); a {@link Disposition#COMPLETE_WITH_WARNING} outcome carries a
     * {@link VerifyOutcome.Kind#NO_TEST_COMMAND} verify outcome (the verify was skipped); an
     * {@link Disposition#ALL_IMPLEMENTED} outcome's verify, when present, is a verified one (the
     * end-of-phase verify passed). The implemented-task list is always defensively copied.
     *
     * @throws NullPointerException     if {@code disposition} or {@code implementedTasks} is
     *                                  {@code null}.
     * @throws IllegalArgumentException if the {@code verifyOutcome} / {@code implementedTasks} shape
     *                                  does not match {@code disposition}.
     */
    public ImplementOutcome {
        Objects.requireNonNull(disposition, "disposition");
        implementedTasks = List.copyOf(Objects.requireNonNull(implementedTasks, "implementedTasks"));
        switch (disposition) {
            case NO_TASKS -> {
                if (!implementedTasks.isEmpty()) {
                    throw new IllegalArgumentException("NO_TASKS must not name any implemented task");
                }
                if (verifyOutcome != null) {
                    throw new IllegalArgumentException("NO_TASKS must not carry a verify outcome");
                }
            }
            case ALL_IMPLEMENTED -> {
                if (verifyOutcome != null && !verifyOutcome.verified()) {
                    throw new IllegalArgumentException(
                            "ALL_IMPLEMENTED's verify outcome, when present, must be a verified() one");
                }
            }
            case VERIFY_FAILED -> {
                requireVerifyPresent(verifyOutcome, disposition);
                if (verifyOutcome.verified()) {
                    throw new IllegalArgumentException(
                            "VERIFY_FAILED must not carry a verified() verify outcome");
                }
                if (verifyOutcome.kind() == VerifyOutcome.Kind.NO_TEST_COMMAND) {
                    throw new IllegalArgumentException(
                            "VERIFY_FAILED carries the EXHAUSTED verify, not NO_TEST_COMMAND");
                }
            }
            case COMPLETE_WITH_WARNING -> {
                requireVerifyPresent(verifyOutcome, disposition);
                if (verifyOutcome.kind() != VerifyOutcome.Kind.NO_TEST_COMMAND) {
                    throw new IllegalArgumentException(
                            "COMPLETE_WITH_WARNING must carry the NO_TEST_COMMAND verify outcome");
                }
            }
            // The switch is exhaustive over Disposition; an unreachable default would be dead code.
        }
    }

    private static void requireVerifyPresent(VerifyOutcome verifyOutcome, Disposition disposition) {
        if (verifyOutcome == null) {
            throw new IllegalArgumentException(disposition + " must carry a verify outcome");
        }
    }

    /**
     * Builds an {@link Disposition#ALL_IMPLEMENTED} outcome where no end-of-phase verify ran: every
     * task was implemented and marked complete on implementation, in breakdown order (AC-3.2/3.3,
     * DCR-7), but the loop carried no verify collaborators (the per-task unit path).
     *
     * @param implementedTasks the stable ids marked complete, in breakdown order; must not be
     *                         {@code null}.
     * @return an all-implemented implement outcome with no verify outcome.
     */
    public static ImplementOutcome allImplemented(List<String> implementedTasks) {
        return new ImplementOutcome(Disposition.ALL_IMPLEMENTED, implementedTasks, null);
    }

    /**
     * Builds an {@link Disposition#ALL_IMPLEMENTED} outcome whose end-of-phase verify passed: every
     * task was implemented and the single configured build/test run verified the phase within the
     * bound (AC-3.2 testable-only end-of-phase verify; RD-10). The clean phase success.
     *
     * @param implementedTasks the stable ids marked complete, in breakdown order; must not be
     *                         {@code null}.
     * @param verifyOutcome    the passing end-of-phase verify ({@link VerifyOutcome#verified()} true);
     *                         must not be {@code null}.
     * @return an all-implemented, verified implement outcome.
     */
    public static ImplementOutcome verified(
            List<String> implementedTasks, VerifyOutcome verifyOutcome) {
        return new ImplementOutcome(
                Disposition.ALL_IMPLEMENTED, implementedTasks,
                Objects.requireNonNull(verifyOutcome, "verifyOutcome"));
    }

    /**
     * Builds a {@link Disposition#VERIFY_FAILED} outcome: every task was implemented, but the
     * end-of-phase verify did not pass within {@code NFR-VERIFY-MAX-ITERATIONS} attempts; the phase
     * stops and surfaces the failure (AC-3.4/AC-20.5, DCR-7).
     *
     * @param implementedTasks the stable ids marked complete, in breakdown order; must not be
     *                         {@code null}.
     * @param verifyOutcome    the {@link VerifyOutcome.Kind#EXHAUSTED} verify carrying the last
     *                         failing run's output to surface (AC-20.5); must not be {@code null} and
     *                         must not be a verified outcome.
     * @return an end-verify-failed implement outcome.
     */
    public static ImplementOutcome verifyFailed(
            List<String> implementedTasks, VerifyOutcome verifyOutcome) {
        return new ImplementOutcome(
                Disposition.VERIFY_FAILED, implementedTasks,
                Objects.requireNonNull(verifyOutcome, "verifyOutcome"));
    }

    /**
     * Builds a {@link Disposition#COMPLETE_WITH_WARNING} outcome: every task was implemented and
     * marked complete, but no test command is configured, so the end-of-phase verify was skipped with
     * a single warning and the phase terminates deterministically &mdash; a complete-with-warning
     * terminal success (AC-3.6, DCR-7), NOT a hard-stop and NOT a re-loop.
     *
     * @param implementedTasks the stable ids marked complete, in breakdown order; must not be
     *                         {@code null}.
     * @param verifyOutcome    the {@link VerifyOutcome.Kind#NO_TEST_COMMAND} verify outcome; must not
     *                         be {@code null}.
     * @return a complete-with-warning implement outcome.
     */
    public static ImplementOutcome completeWithWarning(
            List<String> implementedTasks, VerifyOutcome verifyOutcome) {
        return new ImplementOutcome(
                Disposition.COMPLETE_WITH_WARNING, implementedTasks,
                Objects.requireNonNull(verifyOutcome, "verifyOutcome"));
    }

    /**
     * Builds a {@link Disposition#NO_TASKS} outcome: the approved breakdown carried no recognizable
     * task to implement.
     *
     * @return a no-tasks implement outcome with no implemented tasks and no verify outcome.
     */
    public static ImplementOutcome noTasks() {
        return new ImplementOutcome(Disposition.NO_TASKS, List.of(), null);
    }

    /**
     * Whether the implement loop implemented every task in the breakdown and the phase reached a
     * clean success: every task implemented and marked complete on implementation (AC-3.2/3.3,
     * DCR-7) and the end-of-phase verify passed (or did not run). This is the success predicate the
     * run path branches on.
     *
     * @return {@code true} only for an {@link Disposition#ALL_IMPLEMENTED} outcome.
     */
    public boolean allImplemented() {
        return disposition == Disposition.ALL_IMPLEMENTED;
    }

    /**
     * The end-of-phase verify outcome, present when a verify ran (every task was implemented and the
     * loop carried verify collaborators), absent for a {@link Disposition#NO_TASKS} result or an
     * {@link Disposition#ALL_IMPLEMENTED} result whose loop ran no end verify.
     *
     * @return the verify outcome, or {@link Optional#empty()} when no end-of-phase verify ran.
     */
    public Optional<VerifyOutcome> verifyOutcomeIfPresent() {
        return Optional.ofNullable(verifyOutcome);
    }
}
