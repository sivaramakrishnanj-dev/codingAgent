package com.srk.codingagent.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enforces the greenfield task-breakdown traceability guarantee (component C3, ADR-0012 greenfield
 * side): <b>every task in the breakdown traces to at least one stated requirement</b> (AC-2.5), and
 * <b>each task carries a stable identifier</b> (AC-2.2).
 *
 * <p><b>Why this is enforced, not advised.</b> AC-2.5 is a universal ("U") acceptance criterion —
 * "the agent shall ensure every task … traces to at least one stated requirement". The
 * {@link ArtifactApprovalGate} consults this check before recording the tasks-phase approval, so the
 * task breakdown cannot be approved (and the session cannot advance into implementation, AC-2.3)
 * while a task lacks a requirement reference. That makes the traceability chain ADR-0012 pins
 * (US&rarr;AC&rarr;NFR/ADR&rarr;task) a property the gate guarantees, not merely a prompt the model
 * is asked to honour.
 *
 * <p><b>What a task and a requirement reference are.</b> The breakdown is markdown; this check reads
 * it line-structurally rather than imposing a rigid template:
 * <ul>
 *   <li>A <em>task</em> is a line that opens with a stable task identifier — {@code T-<n>} or
 *       {@code T-<n>.<m>} (AC-2.2's "stable identifier"), optionally inside a markdown list / heading
 *       / checkbox prefix (e.g. {@code - [ ] T-1.2 …}, {@code ## T-3 …}).</li>
 *   <li>A <em>requirement reference</em> is a token naming a stated requirement symbol — a user
 *       story ({@code US-<n>}), an acceptance criterion ({@code AC-<n>} / {@code AC-<n>.<m>}), a
 *       non-functional requirement ({@code NFR-<name>}), a requirement-decision ({@code RD-<n>}), or
 *       an invariant ({@code INV-<n>}) — the requirement-symbol vocabulary ADR-0012's traceability
 *       chain is expressed in.</li>
 * </ul>
 * A task traces when its own line carries at least one requirement reference. A breakdown with no
 * recognizable task is itself untraceable (there is no breakdown to trace).
 */
public final class TaskTraceability {

    /**
     * Matches a task line: optional markdown list/heading/checkbox prefix, then a stable task id
     * {@code T-<n>} or {@code T-<n>.<m>} (AC-2.2). The id must be followed by a separator or
     * end-of-line so {@code T-1} does not match inside an unrelated token.
     */
    private static final Pattern TASK_LINE = Pattern.compile(
            "^\\s*(?:[-*+]\\s*(?:\\[[ xX]?\\]\\s*)?|#{1,6}\\s*|\\|\\s*)?(T-\\d+(?:\\.\\d+)*)\\b");

    /**
     * Matches a requirement reference token: a user story, acceptance criterion, NFR, requirement
     * decision, or invariant — the requirement-symbol vocabulary ADR-0012's traceability chain uses.
     */
    private static final Pattern REQUIREMENT_REF = Pattern.compile(
            "\\b(?:US-\\d+|AC-\\d+(?:\\.\\d+)*|NFR-[A-Z0-9-]+|RD-\\d+|INV-\\d+)\\b");

    private TaskTraceability() {
        // Static analysis utility; not instantiable.
    }

    /**
     * The result of a traceability check over a task-breakdown artifact: whether it is traceable and,
     * when not, the stable ids of the tasks that lack a requirement reference (AC-2.5).
     *
     * @param traceable     whether every recognized task references at least one requirement and at
     *                      least one task was recognized.
     * @param taskCount     the number of recognized tasks in the breakdown.
     * @param untracedTasks the stable ids of tasks lacking a requirement reference (empty when
     *                      {@code traceable} and at least one task exists); never {@code null}.
     */
    public record Result(boolean traceable, int taskCount, List<String> untracedTasks) {

        /** Compact constructor: defensively copies the untraced-task list. */
        public Result {
            untracedTasks = List.copyOf(Objects.requireNonNull(untracedTasks, "untracedTasks"));
        }
    }

    /**
     * Checks that every task in the breakdown markdown traces to at least one requirement (AC-2.5)
     * and that at least one task carrying a stable id (AC-2.2) is present.
     *
     * @param breakdownMarkdown the task-breakdown artifact content; must not be {@code null}.
     * @return the traceability result: {@code traceable} only when at least one task is recognized
     *         and every recognized task references a requirement; otherwise the untraced task ids
     *         (or an empty breakdown with {@code taskCount == 0}).
     * @throws NullPointerException if {@code breakdownMarkdown} is {@code null}.
     */
    public static Result check(String breakdownMarkdown) {
        Objects.requireNonNull(breakdownMarkdown, "breakdownMarkdown");
        List<String> untraced = new ArrayList<>();
        int taskCount = 0;
        for (String line : breakdownMarkdown.split("\n", -1)) {
            Matcher taskMatcher = TASK_LINE.matcher(line);
            if (!taskMatcher.find()) {
                continue;
            }
            taskCount++;
            String taskId = taskMatcher.group(1);
            if (!REQUIREMENT_REF.matcher(line).find()) {
                untraced.add(taskId);
            }
        }
        boolean traceable = taskCount > 0 && untraced.isEmpty();
        return new Result(traceable, taskCount, untraced);
    }
}
