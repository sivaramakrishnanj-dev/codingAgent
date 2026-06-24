package com.srk.codingagent.workflow;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
 *       / checkbox / table-cell prefix (e.g. {@code - [ ] T-1.2 …}, {@code ## T-3 …},
 *       {@code | **T-1** | … |}).</li>
 *   <li>A <em>requirement reference</em> is a token naming a stated requirement symbol — a user
 *       story ({@code US-<n>}), an acceptance criterion ({@code AC-<n>} / {@code AC-<n>.<m>}), a
 *       non-functional requirement ({@code NFR-<name>}), a requirement-decision ({@code RD-<n>}), or
 *       an invariant ({@code INV-<n>}) — the requirement-symbol vocabulary ADR-0012's traceability
 *       chain is expressed in.</li>
 * </ul>
 * A task traces when its own line carries at least one requirement reference. A breakdown with no
 * recognizable task is itself untraceable (there is no breakdown to trace).
 *
 * <p><b>Real-breakdown recognition coverage (AC-2.2, AC-2.5, ADR-0012; DCR-6 amended 2026-06-24).</b>
 * A real Sonnet-style breakdown contains shapes the original single-line parser <em>miscounted</em>.
 * DCR-6 permits a miscounting-only hardening that changes recognition <b>COVERAGE, not STRICTNESS</b>
 * — which ids count as tasks ({@code T-<n>}/{@code T-<n>.<m>}, hyphen mandatory) and which refs count
 * as traces (the {@code US-}/{@code AC-}/{@code NFR-}/{@code RD-}/{@code INV-} vocabulary) are
 * <em>unchanged</em>. The four covered shapes:
 * <ol>
 *   <li><b>Repeated ids are de-duplicated in {@code untracedTasks}.</b> The same id flagged untraced
 *       on more than one recognized line is listed once (a distinct-set of untraced ids), so a real
 *       breakdown that names a task on a table row and again on a detail line is not double-counted in
 *       the untraced report. {@code taskCount} still counts each recognized occurrence (it is a count
 *       of recognized task lines, not of distinct ids).</li>
 *   <li><b>Arrow/sequencing-diagram lines are skipped.</b> A line that connects two task ids with an
 *       arrow token ({@code ->} or {@code &#8594;}), e.g. {@code T-1 -> T-2}, is a diagram, not a task
 *       — it is not counted and not flagged. Detection is conservative: a normal task line
 *       {@code T-1: do X (AC-1.1)} carries no arrow-then-id and is unaffected.</li>
 *   <li><b>Range headings are expanded.</b> A heading of the form {@code T-<a> through T-<b>} expands
 *       so {@code T-<a>}..{@code T-<b>} are each individually recognized (not silently collapsed to
 *       only {@code T-<a>}), and — preserving the same-line-ref rule — each expanded id is flagged
 *       untraced unless the heading line itself carries a valid requirement ref (in which case all
 *       expanded ids trace). Only simple integer ranges on the {@code T-<n>} form expand.</li>
 *   <li><b>Bold-wrapped table-cell ids are recognized.</b> A table row whose id is emphasis-wrapped,
 *       {@code | **T-1** | … |} (or {@code __T-1__}), is recognized as task {@code T-1} — the captured
 *       id is the bare {@code T-1}, the wrapper stripped.</li>
 * </ol>
 *
 * <p><b>Strictness is unchanged; no block scan (DCR-5 Option b stays rejected).</b> The
 * same-line-ref guarantee holds: a task whose own recognized line lacks a valid ref is flagged
 * untraced even if a sibling line (e.g. a following multi-line {@code **Refs:**} block) carries one.
 * The hardening does <em>not</em> loosen the strict gate into a block scan; single-line task rows are
 * guaranteed by the greenfield TASKS prompt, and this parser only stops miscounting the shapes a real
 * breakdown contains.
 */
public final class TaskTraceability {

    /**
     * Matches a task line: optional markdown list/heading/checkbox/table-cell prefix, an optional
     * emphasis wrapper ({@code **}/{@code __}) around the id, then a stable task id {@code T-<n>} or
     * {@code T-<n>.<m>} (AC-2.2). The id must be followed by a separator or end-of-line so {@code T-1}
     * does not match inside an unrelated token. The captured group is the bare id (the emphasis
     * wrapper, when present, is outside the group), keeping recognition COVERAGE wider (a bold-wrapped
     * table cell {@code | **T-1** |} is recognized) without relaxing STRICTNESS (the id form
     * {@code T-<n>}/{@code T-<n>.<m>} with the mandatory hyphen is unchanged) — DCR-6.
     */
    private static final Pattern TASK_LINE = Pattern.compile(
            "^\\s*(?:[-*+]\\s*(?:\\[[ xX]?\\]\\s*)?|#{1,6}\\s*|\\|\\s*)?"
                    + "(?:\\*\\*|__)?(T-\\d+(?:\\.\\d+)*)\\b");

    /**
     * Matches a requirement reference token: a user story, acceptance criterion, NFR, requirement
     * decision, or invariant — the requirement-symbol vocabulary ADR-0012's traceability chain uses.
     */
    private static final Pattern REQUIREMENT_REF = Pattern.compile(
            "\\b(?:US-\\d+|AC-\\d+(?:\\.\\d+)*|NFR-[A-Z0-9-]+|RD-\\d+|INV-\\d+)\\b");

    /**
     * Matches an arrow/sequencing-diagram line (DCR-6 coverage rule (ii)): a task id, then an arrow
     * token ({@code ->} or the unicode {@code &#8594;}) somewhere later on the line, then another task
     * id. Such a line is a diagram between tasks, not a task itself, so it is skipped (not counted, not
     * flagged). Detection is deliberately conservative — it requires the {@code id arrow id} shape, so
     * a normal task line {@code T-1: do X (AC-1.1)} (no arrow-then-id) is never mistaken for a diagram.
     */
    private static final Pattern ARROW_DIAGRAM_LINE = Pattern.compile(
            "T-\\d+(?:\\.\\d+)*\\s*(?:->|\\u2192)\\s*T-\\d+(?:\\.\\d+)*");

    /**
     * Matches a range heading (DCR-6 coverage rule (iii)): {@code T-<a> through T-<b>} on the simple
     * integer {@code T-<n>} form (no {@code .<m>} sub-id). Groups 1 and 2 are the inclusive bounds.
     * The {@code through} connector is the directive's example; only this form expands.
     */
    private static final Pattern RANGE_HEADING = Pattern.compile(
            "^\\s*(?:[-*+]\\s*(?:\\[[ xX]?\\]\\s*)?|#{1,6}\\s*|\\|\\s*)?"
                    + "(?:\\*\\*|__)?T-(\\d+)(?:\\*\\*|__)?\\s+through\\s+(?:\\*\\*|__)?T-(\\d+)\\b");

    private TaskTraceability() {
        // Static analysis utility; not instantiable.
    }

    /**
     * The result of a traceability check over a task-breakdown artifact: whether it is traceable and,
     * when not, the stable ids of the tasks that lack a requirement reference (AC-2.5).
     *
     * @param traceable     whether every recognized task references at least one requirement and at
     *                      least one task was recognized.
     * @param taskCount     the number of recognized tasks in the breakdown (each recognized task line,
     *                      and each individually-recognized id of an expanded range heading, counts
     *                      once; arrow/sequencing-diagram lines are not counted — DCR-6).
     * @param untracedTasks the <em>distinct</em> stable ids of tasks lacking a requirement reference
     *                      (empty when {@code traceable} and at least one task exists); de-duplicated
     *                      so a repeated id is listed once (DCR-6); never {@code null}.
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
     * <p>Recognition coverage (DCR-6): repeated untraced ids are de-duplicated in
     * {@link Result#untracedTasks()} (the same id flagged on more than one line is listed once);
     * arrow/sequencing-diagram lines ({@code T-1 -> T-2}) are skipped; a range heading
     * ({@code T-3 through T-8}) is expanded so each id in the range is individually recognized and
     * each is flagged untraced unless the heading line carries a valid ref; and a bold-wrapped
     * table-cell id ({@code | **T-1** |}) is recognized. The <em>strictness</em> — which ids count and
     * which refs count, and the same-line-ref rule (a task whose own line lacks a ref is flagged even
     * if a sibling line carries one; no block scan) — is unchanged.
     *
     * @param breakdownMarkdown the task-breakdown artifact content; must not be {@code null}.
     * @return the traceability result: {@code traceable} only when at least one task is recognized
     *         and every recognized task references a requirement; otherwise the (de-duplicated)
     *         untraced task ids (or an empty breakdown with {@code taskCount == 0}).
     * @throws NullPointerException if {@code breakdownMarkdown} is {@code null}.
     */
    public static Result check(String breakdownMarkdown) {
        Objects.requireNonNull(breakdownMarkdown, "breakdownMarkdown");
        Set<String> untraced = new LinkedHashSet<>();
        int taskCount = 0;
        for (String line : breakdownMarkdown.split("\n", -1)) {
            List<String> ids = recognizeTaskIds(line);
            if (ids.isEmpty()) {
                continue;
            }
            taskCount += ids.size();
            boolean lineHasRef = REQUIREMENT_REF.matcher(line).find();
            if (!lineHasRef) {
                untraced.addAll(ids);
            }
        }
        boolean traceable = taskCount > 0 && untraced.isEmpty();
        return new Result(traceable, taskCount, new ArrayList<>(untraced));
    }

    /**
     * Enumerates the stable task identifiers in the breakdown markdown, in <em>breakdown order</em>
     * &mdash; the file order in which the tasks appear (AC-2.2's stable identifier, AC-3.1's
     * breakdown order). The greenfield implement loop reads this to drive the planned tasks one at a
     * time, in order (AC-3.1), reusing the same task-line recognition the traceability check uses so
     * there is one source of truth for "what is a task line" (rather than a second, drifting parser).
     *
     * <p>A task id is emitted once per recognized task; the same id appearing on two distinct task
     * lines is emitted twice (the order, not deduplication, is what AC-3.1 needs — deduplication
     * applies only to {@link Result#untracedTasks()}). The DCR-6 recognition coverage is shared: a
     * range heading expands so each id is emitted individually, an arrow/sequencing-diagram line is
     * skipped, and a bold-wrapped table-cell id is recognized. A breakdown with no recognizable task
     * yields an empty list.
     *
     * @param breakdownMarkdown the task-breakdown artifact content; must not be {@code null}.
     * @return the stable task ids in breakdown (file) order; never {@code null}, possibly empty.
     * @throws NullPointerException if {@code breakdownMarkdown} is {@code null}.
     */
    public static List<String> tasksInOrder(String breakdownMarkdown) {
        Objects.requireNonNull(breakdownMarkdown, "breakdownMarkdown");
        List<String> tasks = new ArrayList<>();
        for (String line : breakdownMarkdown.split("\n", -1)) {
            tasks.addAll(recognizeTaskIds(line));
        }
        return List.copyOf(tasks);
    }

    /**
     * The single source of truth for "which task ids does this line contribute", shared by
     * {@link #check(String)} and {@link #tasksInOrder(String)} so both agree on recognition (DCR-6).
     * Returns, for one line:
     * <ul>
     *   <li>the empty list for a non-task line OR an arrow/sequencing-diagram line (skipped — rule
     *       (ii));</li>
     *   <li>the expanded ids {@code T-<a>}..{@code T-<b>} for a range heading {@code T-<a> through
     *       T-<b>} (rule (iii));</li>
     *   <li>the single recognized id (the bare {@code T-<n>}/{@code T-<n>.<m>}, any emphasis wrapper
     *       stripped — rule (iv)) otherwise.</li>
     * </ul>
     * This recognizes COVERAGE only; the strict id form (mandatory hyphen) and the same-line-ref rule
     * the callers apply are unchanged.
     */
    private static List<String> recognizeTaskIds(String line) {
        if (ARROW_DIAGRAM_LINE.matcher(line).find()) {
            return List.of();
        }
        Matcher rangeMatcher = RANGE_HEADING.matcher(line);
        if (rangeMatcher.find()) {
            return expandRange(rangeMatcher.group(1), rangeMatcher.group(2));
        }
        Matcher taskMatcher = TASK_LINE.matcher(line);
        if (taskMatcher.find()) {
            return List.of(taskMatcher.group(1));
        }
        return List.of();
    }

    /**
     * The largest range bound that expands. A real breakdown's task numbers are small; a bound beyond
     * this is treated as not-a-well-formed-simple-range, so a pathological heading neither over-expands
     * into a huge list nor (since the regex captures unbounded {@code \d+}) overflows integer parsing.
     */
    private static final int MAX_RANGE_BOUND = 100_000;

    /**
     * Expands an inclusive integer range heading {@code T-<a> through T-<b>} into the individual ids
     * {@code T-<a>}, {@code T-<a+1>}, …, {@code T-<b>} (DCR-6 rule (iii)). Only a well-formed ascending
     * simple integer range expands; a descending bound (b &lt; a) or any bound beyond
     * {@link #MAX_RANGE_BOUND} is not a well-formed simple range, so it yields no ids — the heading then
     * contributes no task. A bound whose digit count alone exceeds {@code MAX_RANGE_BOUND}'s width is
     * rejected without parsing, so an implausibly large captured {@code \d+} can never throw.
     */
    private static List<String> expandRange(String fromBound, String toBound) {
        if (exceedsMaxBound(fromBound) || exceedsMaxBound(toBound)) {
            return List.of();
        }
        int from = Integer.parseInt(fromBound);
        int to = Integer.parseInt(toBound);
        if (to < from) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(to - from + 1);
        for (int n = from; n <= to; n++) {
            ids.add("T-" + n);
        }
        return ids;
    }

    /**
     * Whether a captured numeric bound exceeds {@link #MAX_RANGE_BOUND}, decided on the digit string
     * (never by parsing) so an arbitrarily long {@code \d+} capture cannot overflow {@code int}. A
     * string longer than {@code MAX_RANGE_BOUND}'s width is over the bound by digit count; an
     * equal-or-shorter string is safely parseable for the exact numeric compare.
     */
    private static boolean exceedsMaxBound(String bound) {
        int maxWidth = Integer.toString(MAX_RANGE_BOUND).length();
        return bound.length() > maxWidth || Integer.parseInt(bound) > MAX_RANGE_BOUND;
    }
}
