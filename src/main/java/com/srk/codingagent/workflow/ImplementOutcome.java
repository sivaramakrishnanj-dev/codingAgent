package com.srk.codingagent.workflow;

import java.util.List;
import java.util.Objects;

/**
 * The terminal result of one {@link GreenfieldImplementLoop} run: which tasks the
 * implement-every-task-in-order loop implemented and marked complete on implementation (component
 * C3, ADR-0012 implement clause amended by DCR-7; US-3, AC-3.2/3.3). This is the value the run path
 * maps to a user-facing result; the implement loop never calls {@code System.exit}.
 *
 * <p><b>DCR-7 — verify at end of phase, mark complete on implementation.</b> The greenfield IMPLEMENT
 * phase is a flat task list with no milestone substructure, so the verify boundary is end-of-phase
 * (a single configured build/test run after the last task), not per task (AC-3.2, ADR-0012). The
 * implement loop therefore implements <em>every</em> task in breakdown order and marks each complete
 * <em>as it is implemented</em> (a durable on-disk completion marker, AC-3.3) &mdash; a task is marked
 * complete on implementation, not on passing an individual verify. There is no per-task verify cycle
 * in the loop body, so this outcome no longer carries a per-task verify result or a per-task
 * verify-exhausted stop: the loop reports the tasks it implemented (the {@code completedList}
 * end-of-phase verification, T-3.9, will gate the phase around).
 *
 * <p>The two terminal shapes, by {@link Disposition}:
 * <ul>
 *   <li><b>{@link Disposition#ALL_IMPLEMENTED}.</b> Every task in the breakdown was implemented and
 *       marked complete in the task-breakdown artifact, in breakdown order (AC-3.2/3.3).
 *       {@link #implementedTasks()} lists the stable ids of the tasks marked complete (in order); the
 *       clean implement-loop result. End-of-phase verification (T-3.9, AC-3.2) is what subsequently
 *       gates the phase &mdash; this outcome reports the implemented tasks the end verify wraps
 *       around.</li>
 *   <li><b>{@link Disposition#NO_TASKS}.</b> The approved task-breakdown artifact carried no
 *       recognizable task to implement (an empty or task-less breakdown). The loop has nothing to do
 *       and reports it. {@link #implementedTasks()} is empty.</li>
 * </ul>
 *
 * @param disposition      which terminal state the implement loop reached; must not be {@code null}.
 * @param implementedTasks the stable ids of the tasks implemented-and-marked-complete, in breakdown
 *                         order (AC-3.2/3.3); never {@code null} (empty when none implemented).
 */
public record ImplementOutcome(Disposition disposition, List<String> implementedTasks) {

    /** Which terminal state the implement-every-task-in-order loop reached (DCR-7). */
    public enum Disposition {

        /**
         * Every task was implemented and marked complete in breakdown order, as it was implemented
         * (AC-3.2/3.3, DCR-7). End-of-phase verification (T-3.9) gates the phase around this result.
         */
        ALL_IMPLEMENTED,

        /** The approved breakdown carried no recognizable task to implement. */
        NO_TASKS
    }

    /**
     * Validates the outcome's invariants so an inconsistent {@code ImplementOutcome} cannot exist: a
     * {@link Disposition#NO_TASKS} outcome implemented nothing (its task list is empty); the
     * implemented-task list is always defensively copied.
     *
     * @throws NullPointerException     if {@code disposition} or {@code implementedTasks} is
     *                                  {@code null}.
     * @throws IllegalArgumentException if a {@link Disposition#NO_TASKS} outcome names implemented
     *                                  tasks.
     */
    public ImplementOutcome {
        Objects.requireNonNull(disposition, "disposition");
        implementedTasks = List.copyOf(Objects.requireNonNull(implementedTasks, "implementedTasks"));
        if (disposition == Disposition.NO_TASKS && !implementedTasks.isEmpty()) {
            throw new IllegalArgumentException("NO_TASKS must not name any implemented task");
        }
    }

    /**
     * Builds an {@link Disposition#ALL_IMPLEMENTED} outcome: every task was implemented and marked
     * complete on implementation, in breakdown order (AC-3.2/3.3, DCR-7).
     *
     * @param implementedTasks the stable ids marked complete, in breakdown order; must not be
     *                         {@code null}.
     * @return an all-implemented implement outcome.
     */
    public static ImplementOutcome allImplemented(List<String> implementedTasks) {
        return new ImplementOutcome(Disposition.ALL_IMPLEMENTED, implementedTasks);
    }

    /**
     * Builds a {@link Disposition#NO_TASKS} outcome: the approved breakdown carried no recognizable
     * task to implement.
     *
     * @return a no-tasks implement outcome with no implemented tasks.
     */
    public static ImplementOutcome noTasks() {
        return new ImplementOutcome(Disposition.NO_TASKS, List.of());
    }

    /**
     * Whether the implement loop implemented every task in the breakdown and marked each complete on
     * implementation (AC-3.2/3.3, DCR-7). This is the success predicate the run path branches on.
     *
     * @return {@code true} only for an {@link Disposition#ALL_IMPLEMENTED} outcome.
     */
    public boolean allImplemented() {
        return disposition == Disposition.ALL_IMPLEMENTED;
    }
}
