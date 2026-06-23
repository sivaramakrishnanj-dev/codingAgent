package com.srk.codingagent.workflow;

import com.srk.codingagent.loop.VerifyOutcome;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The terminal result of one {@link GreenfieldImplementLoop} run: how far the implement-one-task-
 * at-a-time loop got through the approved task breakdown, and &mdash; when it stopped on a task that
 * did not verify &mdash; which task stopped it and the verification failure that surfaced (component
 * C3, ADR-0012 implement clause; US-3, AC-3.1/3.3/3.4). This is the value the run path maps to a
 * user-facing result; the implement loop never calls {@code System.exit} (AC-3.4 surfaces, it does
 * not exit). Mirrors {@link BrownfieldOutcome}'s rich-disposition shape: the verify-exhaustion is a
 * first-class disposition carrying the failing {@link VerifyOutcome}, not a model {@code stopReason}
 * (verify-exhaustion is not part of the Converse stop-reason vocabulary).
 *
 * <p>The four terminal shapes, by {@link Disposition}:
 * <ul>
 *   <li><b>{@link Disposition#ALL_VERIFIED}.</b> Every task in the breakdown was implemented,
 *       verified, and marked complete in the task-breakdown artifact, in breakdown order
 *       (AC-3.1/3.2/3.3). {@link #completedTasks()} lists the stable ids of the tasks marked
 *       complete (in order); the clean implement success. {@link #verifyOutcomeIfPresent()} carries
 *       the last task's verifying outcome (or is empty when there was no test command &mdash; see
 *       {@link Disposition#NO_TEST_COMMAND}).</li>
 *   <li><b>{@link Disposition#VERIFY_EXHAUSTED}.</b> A task did not verify within
 *       {@code verifyMaxIterations} attempts (AC-3.4, AC-20.5). The loop <em>stops</em> at that task
 *       rather than advancing to the next (AC-3.4). {@link #stoppedTask()} is the id of the task that
 *       failed verification; {@link #completedTasks()} lists the tasks verified-and-marked before it;
 *       {@link #verifyOutcomeIfPresent()} carries the last failing run's output, which the run path
 *       surfaces (AC-20.5).</li>
 *   <li><b>{@link Disposition#NO_TEST_COMMAND}.</b> No test command is configured (AC-20.6), so no
 *       task could be verified before the next. The loop does not advance task-by-task on an unverifiable
 *       basis; it reports the missing test command (the configured command is preferred &mdash; absence
 *       is a config state to report, not to substitute an ad-hoc verification for). {@link #completedTasks()}
 *       is empty; {@link #verifyOutcomeIfPresent()} carries the {@link VerifyOutcome.Kind#NO_TEST_COMMAND}
 *       outcome of the first task's attempted verification.</li>
 *   <li><b>{@link Disposition#NO_TASKS}.</b> The approved task-breakdown artifact carried no
 *       recognizable task to implement (an empty or task-less breakdown). The loop has nothing to do
 *       and reports it. {@link #completedTasks()} is empty and there is no verify outcome.</li>
 * </ul>
 *
 * @param disposition    which terminal state the implement loop reached; must not be {@code null}.
 * @param completedTasks the stable ids of the tasks verified-and-marked-complete, in breakdown order
 *                       (AC-3.1/3.3); never {@code null} (empty when none completed).
 * @param stoppedTask    the stable id of the task that failed verification for a
 *                       {@link Disposition#VERIFY_EXHAUSTED} outcome, or {@code null} otherwise.
 * @param verifyOutcome  the relevant verify outcome &mdash; the last task's verifying outcome for
 *                       {@link Disposition#ALL_VERIFIED}, the failing outcome for
 *                       {@link Disposition#VERIFY_EXHAUSTED}, the {@link VerifyOutcome.Kind#NO_TEST_COMMAND}
 *                       outcome for {@link Disposition#NO_TEST_COMMAND}, or {@code null} for
 *                       {@link Disposition#NO_TASKS}.
 */
public record ImplementOutcome(
        Disposition disposition,
        List<String> completedTasks,
        String stoppedTask,
        VerifyOutcome verifyOutcome) {

    /** Which terminal state the implement-one-task-at-a-time loop reached. */
    public enum Disposition {

        /** Every task was implemented, verified, and marked complete in order (AC-3.1/3.2/3.3). */
        ALL_VERIFIED,

        /**
         * A task did not verify within the bound; the loop stopped at it rather than advancing to
         * the next task (AC-3.4, AC-20.5).
         */
        VERIFY_EXHAUSTED,

        /** No test command is configured, so a task cannot be verified before the next (AC-20.6). */
        NO_TEST_COMMAND,

        /** The approved breakdown carried no recognizable task to implement. */
        NO_TASKS
    }

    /**
     * Validates the outcome's invariants so an inconsistent {@code ImplementOutcome} cannot exist:
     * a {@link Disposition#VERIFY_EXHAUSTED} outcome must name the stopped task and carry the failing
     * verify outcome; the other dispositions must not name a stopped task.
     *
     * @throws NullPointerException     if {@code disposition} or {@code completedTasks} is
     *                                  {@code null}.
     * @throws IllegalArgumentException if the {@code stoppedTask}/{@code verifyOutcome} shape does
     *                                  not match the {@code disposition}.
     */
    public ImplementOutcome {
        Objects.requireNonNull(disposition, "disposition");
        completedTasks = List.copyOf(Objects.requireNonNull(completedTasks, "completedTasks"));
        switch (disposition) {
            case ALL_VERIFIED -> requireNoStoppedTask(stoppedTask, disposition);
            case VERIFY_EXHAUSTED -> {
                if (stoppedTask == null || stoppedTask.isBlank()) {
                    throw new IllegalArgumentException(
                            "VERIFY_EXHAUSTED must name the task that failed verification");
                }
                if (verifyOutcome == null || verifyOutcome.verified()) {
                    throw new IllegalArgumentException(
                            "VERIFY_EXHAUSTED must carry a non-verified verify outcome");
                }
            }
            case NO_TEST_COMMAND -> requireNoStoppedTask(stoppedTask, disposition);
            case NO_TASKS -> requireNoStoppedTask(stoppedTask, disposition);
        }
    }

    private static void requireNoStoppedTask(String stoppedTask, Disposition disposition) {
        if (stoppedTask != null) {
            throw new IllegalArgumentException(disposition + " must not name a stopped task");
        }
    }

    /**
     * Builds an {@link Disposition#ALL_VERIFIED} outcome: every task was verified and marked complete.
     *
     * @param completedTasks the stable ids marked complete, in breakdown order; must not be
     *                       {@code null}.
     * @param verifyOutcome  the last task's verifying outcome, or {@code null} when there was no test
     *                       command to verify against on an empty task set (not expected when at least
     *                       one task ran).
     * @return an all-verified implement outcome.
     */
    public static ImplementOutcome allVerified(
            List<String> completedTasks, VerifyOutcome verifyOutcome) {
        return new ImplementOutcome(Disposition.ALL_VERIFIED, completedTasks, null, verifyOutcome);
    }

    /**
     * Builds a {@link Disposition#VERIFY_EXHAUSTED} outcome: the named task did not verify within the
     * bound, and the loop stopped at it (AC-3.4).
     *
     * @param completedTasks the tasks verified-and-marked before the stop, in order; must not be
     *                       {@code null}.
     * @param stoppedTask    the stable id of the task that failed verification; non-blank.
     * @param verifyOutcome  the exhausted verify outcome carrying the last failure's output (AC-20.5);
     *                       must not be {@code null} and must not be a verified outcome.
     * @return a verify-exhausted implement outcome.
     */
    public static ImplementOutcome verifyExhausted(
            List<String> completedTasks, String stoppedTask, VerifyOutcome verifyOutcome) {
        return new ImplementOutcome(
                Disposition.VERIFY_EXHAUSTED, completedTasks, stoppedTask, verifyOutcome);
    }

    /**
     * Builds a {@link Disposition#NO_TEST_COMMAND} outcome: no test command is configured, so a task
     * cannot be verified before the next (AC-20.6).
     *
     * @param verifyOutcome the {@link VerifyOutcome.Kind#NO_TEST_COMMAND} outcome; must not be
     *                      {@code null}.
     * @return a no-test-command implement outcome with no completed tasks.
     */
    public static ImplementOutcome noTestCommand(VerifyOutcome verifyOutcome) {
        return new ImplementOutcome(
                Disposition.NO_TEST_COMMAND, List.of(), null,
                Objects.requireNonNull(verifyOutcome, "verifyOutcome"));
    }

    /**
     * Builds a {@link Disposition#NO_TASKS} outcome: the approved breakdown carried no recognizable
     * task to implement.
     *
     * @return a no-tasks implement outcome with no completed tasks and no verify outcome.
     */
    public static ImplementOutcome noTasks() {
        return new ImplementOutcome(Disposition.NO_TASKS, List.of(), null, null);
    }

    /**
     * Whether the implement loop reached the clean success state: every task in the breakdown was
     * implemented, verified, and marked complete (AC-3.1/3.2/3.3). This is the success predicate the
     * run path branches on.
     *
     * @return {@code true} only for an {@link Disposition#ALL_VERIFIED} outcome.
     */
    public boolean allVerified() {
        return disposition == Disposition.ALL_VERIFIED;
    }

    /**
     * The id of the task the loop stopped at because it failed verification, present only for a
     * {@link Disposition#VERIFY_EXHAUSTED} outcome.
     *
     * @return the stopped task id, or {@link Optional#empty()} when the loop did not stop on a task.
     */
    public Optional<String> stoppedTaskIfPresent() {
        return Optional.ofNullable(stoppedTask);
    }

    /**
     * The relevant verify outcome, present whenever a verification ran (i.e. not for
     * {@link Disposition#NO_TASKS}).
     *
     * @return the verify outcome, or {@link Optional#empty()} when no verification ran.
     */
    public Optional<VerifyOutcome> verifyOutcomeIfPresent() {
        return Optional.ofNullable(verifyOutcome);
    }
}
