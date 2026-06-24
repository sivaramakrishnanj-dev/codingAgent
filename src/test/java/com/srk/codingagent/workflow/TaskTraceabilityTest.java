package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link TaskTraceability}: the enforcement of the greenfield task-breakdown traceability
 * guarantee — <b>every task traces to at least one stated requirement</b> (AC-2.5), <b>each task
 * carries a stable identifier</b> (AC-2.2), and the traceability chain ADR-0012 pins
 * (US&rarr;AC&rarr;NFR/ADR&rarr;task).
 *
 * <p><b>Oracles trace to the spec, not to the checker's regex:</b> a breakdown is traceable iff
 * every recognized task references a stated requirement; the inputs below are written as the kind of
 * task breakdown the spec describes (tasks with stable ids, some referencing requirements, some not).
 */
class TaskTraceabilityTest {

    // --- AC-2.5 : every task must reference at least one requirement ------------------------------

    @Test
    @DisplayName("AC-2.5: a breakdown where every task references a requirement is traceable")
    void everyTaskReferencesARequirementIsTraceable() {
        // Oracle: AC-2.5 — the agent shall ensure every task in the breakdown traces to at least one
        // stated requirement. A breakdown whose every task line names a requirement (an AC / US / etc.)
        // is traceable. The requirement-symbol vocabulary (US/AC/NFR/RD/INV) is ADR-0012's traceability
        // chain. Expected: traceable, all tasks counted, none untraced.
        String breakdown = """
                # Tasks

                - T-1 Build the parser (refs AC-1.2)
                - T-2 Wire the CLI (refs US-3, AC-2.1)
                - T-3 Add NFR budget (refs NFR-LATENCY-P99)
                """;

        TaskTraceability.Result result = TaskTraceability.check(breakdown);

        assertTrue(result.traceable(),
                "AC-2.5: every task references a requirement, so the breakdown is traceable");
        assertEquals(3, result.taskCount(), "all three tasks are recognized");
        assertTrue(result.untracedTasks().isEmpty(), "AC-2.5: no task is left untraced");
    }

    @Test
    @DisplayName("AC-2.5: a breakdown with a task that references no requirement is NOT traceable")
    void taskWithoutRequirementIsNotTraceable() {
        // Oracle: AC-2.5 — EVERY task must trace. A breakdown with one task (T-2) lacking any
        // requirement reference violates AC-2.5, so it is not traceable and T-2 is reported as the
        // untraced task. This is the violation case the tasks-approval gate must refuse.
        String breakdown = """
                - T-1 Build the parser (refs AC-1.2)
                - T-2 Refactor for fun
                - T-3 Persist results (refs AC-2.1)
                """;

        TaskTraceability.Result result = TaskTraceability.check(breakdown);

        assertFalse(result.traceable(),
                "AC-2.5: a task with no requirement reference makes the breakdown untraceable");
        assertEquals(java.util.List.of("T-2"), result.untracedTasks(),
                "AC-2.5: the untraced task (no requirement) is identified");
    }

    // --- AC-2.2 : a task is recognized by its stable identifier -----------------------------------

    @Test
    @DisplayName("AC-2.2: tasks are recognized by their stable identifier across markdown shapes")
    void recognizesTasksByStableIdAcrossShapes() {
        // Oracle: AC-2.2 — each task has a stable identifier. The checker recognizes a task by its
        // stable id (T-<n> / T-<n>.<m>) whether it appears in a list item, a heading, a checkbox, or a
        // table row — the common markdown breakdown shapes. Each of these three carries a requirement
        // reference, so the breakdown is traceable with three tasks counted.
        String breakdown = """
                ## T-1.1 Parser (AC-1.2)
                - [ ] T-2 CLI (US-3)
                | T-3.4 | Persist | AC-2.1 |
                """;

        TaskTraceability.Result result = TaskTraceability.check(breakdown);

        assertTrue(result.traceable(), "AC-2.2/AC-2.5: tasks across shapes are recognized and traced");
        assertEquals(3, result.taskCount(),
                "AC-2.2: a task carrying a stable id is recognized in heading, checkbox, and table forms");
    }

    @Test
    @DisplayName("AC-2.5: a breakdown with no recognizable task is not traceable (nothing traces)")
    void emptyOrTasklessBreakdownIsNotTraceable() {
        // Oracle: AC-2.5 — traceability is over the breakdown's TASKS. A document with no task (no
        // stable id) is not a task breakdown that can be approved into implementation, so it is not
        // traceable. Both an empty string and prose-without-tasks have zero tasks.
        TaskTraceability.Result empty = TaskTraceability.check("");
        assertFalse(empty.traceable(), "an empty breakdown has no task to trace");
        assertEquals(0, empty.taskCount(), "no tasks recognized in an empty breakdown");

        TaskTraceability.Result prose = TaskTraceability.check("# Tasks\n\nWe will build things.\n");
        assertFalse(prose.traceable(), "AC-2.5: prose with no stable-id task is not a traceable breakdown");
        assertEquals(0, prose.taskCount(), "no stable-id task recognized in prose");
    }

    @Test
    @DisplayName("a requirement reference anywhere on the task line counts as the task's trace")
    void requirementReferenceOnTheTaskLineTraces() {
        // Oracle: AC-2.5 — a task traces when it references a requirement. The reference is recognized
        // on the task's own line regardless of where on the line it sits.
        TaskTraceability.Result result = TaskTraceability.check("- T-1 (RD-7) build it\n");
        assertTrue(result.traceable(), "AC-2.5: an RD reference traces the task");
        assertEquals(1, result.taskCount());
    }

    @Test
    @DisplayName("check rejects null input")
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> TaskTraceability.check(null));
    }

    // --- DCR-5 regression : the strict gate refuses the prior live-failing forms ------------------
    // These pin the EXACT class of defect the pre-DCR-5 suite could not catch by construction (the
    // ten tests above all use gate-conformant forms only). DCR-5 Option a keeps the gate strict and
    // fixes the greenfield playbook prompt to EMIT the gate's vocabulary; these tests document why
    // the prior live output (hyphen-less T-ids, bare R-refs) is correctly refused, so it can never
    // regress silently.

    @Test
    @DisplayName("AC-2.2 (DCR-5): a hyphen-less id (T1) is NOT recognized as a task; the hyphen form (T-1) is")
    void hyphenLessTaskIdIsNotRecognized() {
        // Oracle: AC-2.2 — "each [task] with a stable identifier of the form T-<n> or T-<n>.<m> (the
        // hyphen is mandatory)". A line whose only id is hyphen-less (T1) is therefore NOT a task; the
        // breakdown has zero recognized tasks and is not a traceable breakdown. The hyphen form (T-1)
        // IS a task. This is the live-failing shape the strict gate (correctly) refuses and the DCR-5
        // prompt change steers the model away from authoring.
        TaskTraceability.Result hyphenLess = TaskTraceability.check("- T1 Build the parser (AC-1.2)\n");
        assertEquals(0, hyphenLess.taskCount(),
                "AC-2.2: a hyphen-less id (T1) carries no stable T-<n> identifier, so it is not a task");
        assertFalse(hyphenLess.traceable(),
                "AC-2.2: with no recognized task the breakdown is not traceable");

        TaskTraceability.Result hyphenForm = TaskTraceability.check("- T-1 Build the parser (AC-1.2)\n");
        assertEquals(1, hyphenForm.taskCount(),
                "AC-2.2: the mandatory-hyphen form (T-1) is recognized as a task");
        assertTrue(hyphenForm.traceable(),
                "AC-2.2/AC-2.5: the T-1 task cites AC-1.2, so the breakdown is traceable");
    }

    @Test
    @DisplayName("AC-2.5 (DCR-5): a bare R-ref (R5) does NOT satisfy traceability; a gate-vocabulary ref (AC/US/NFR) does")
    void bareRequirementRefDoesNotTrace() {
        // Oracle: AC-2.5 — for greenfield the traceability vocabulary is the model-authored requirement
        // symbols "AC-<n>.<m>, US-<n>, NFR-<NAME>" (plus RD-<n>/INV-<n> per ADR-0012's fixed set). A
        // bare R5 is NOT in that vocabulary, so a task citing only R5 traces to no stated requirement
        // and the breakdown is untraceable. A task citing a gate-vocabulary symbol (AC-1.2 / US-3 /
        // NFR-FOO) does trace. This is the second half of the prior live-failing shape (R1-R6 refs).
        TaskTraceability.Result bareRef = TaskTraceability.check("- T-1 Build it (R5)\n");
        assertFalse(bareRef.traceable(),
                "AC-2.5: a bare R5 is not a requirement symbol in the traceability vocabulary");
        assertEquals(java.util.List.of("T-1"), bareRef.untracedTasks(),
                "AC-2.5: the task citing only a bare R-ref is reported untraced");

        assertTrue(TaskTraceability.check("- T-1 Build it (AC-1.2)\n").traceable(),
                "AC-2.5: an acceptance-criterion symbol (AC-1.2) traces the task");
        assertTrue(TaskTraceability.check("- T-1 Build it (US-3)\n").traceable(),
                "AC-2.5: a user-story symbol (US-3) traces the task");
        assertTrue(TaskTraceability.check("- T-1 Build it (NFR-FOO)\n").traceable(),
                "AC-2.5: a non-functional-requirement symbol (NFR-FOO) traces the task");
    }

    @Test
    @DisplayName("AC-2.2/AC-2.5 (DCR-5): the exact prior live-failing breakdown (T1/T2/T10 citing R1-R6) is refused — 0 tasks recognized")
    void priorLiveFailingBreakdownIsRefused() {
        // Oracle: ADR-0012 (DCR-5) — the strict gate "recognizes a task only when a line carries a
        // stable id of the form T-<n> / T-<n>.<m> (the hyphen is mandatory)". The prior greenfield live
        // run authored hyphen-less ids (T1/T2/T10) citing bare R-refs (R1-R6); none of those ids carry
        // a T-<n> stable identifier (AC-2.2), so the strict gate recognizes ZERO tasks and refuses the
        // breakdown as untraceable. This is the live failure DCR-5 fixes at the prompt; pinning it here
        // makes the defect impossible to reintroduce silently.
        String priorLiveFailing = """
                # Tasks

                - T1 Build the parser (R1)
                - T2 Wire the CLI (R2, R3)
                - T10 Persist results (R5, R6)
                """;

        TaskTraceability.Result result = TaskTraceability.check(priorLiveFailing);

        assertEquals(0, result.taskCount(),
                "AC-2.2 (DCR-5): hyphen-less T1/T2/T10 carry no stable T-<n> id, so zero tasks recognized");
        assertFalse(result.traceable(),
                "ADR-0012 (DCR-5): the strict gate refuses the prior live-failing hyphen-less breakdown");
        assertTrue(result.untracedTasks().isEmpty(),
                "AC-2.2 (DCR-5): no task is recognized, so there are no untraced task ids to report");
    }

    @Test
    @DisplayName("AC-2.2/AC-2.5 (DCR-5): a full greenfield breakdown in the NEW prompt vocabulary (T-<n> ids citing AC/US/NFR) passes")
    void gateVocabularyBreakdownPasses() {
        // Oracle: AC-2.5 (DCR-5) — "the requirements phase authors gate-recognizable
        // AC-<n>.<m>/US-<n>/NFR-<NAME> symbols; the tasks phase emits T-<n>/T-<n>.<m> task ids each
        // citing >= 1 such symbol, so the project self-conforms to the strict gate". A breakdown shaped
        // the way the DCR-5 prompt steers the model — T-<n>/T-<n>.<m> ids each citing an AC/US/NFR
        // symbol — must pass the unchanged strict gate as traceable, with every task counted. This is
        // the positive counterpart of the refused prior live-failing breakdown above.
        String gateVocabulary = """
                # Tasks

                - T-1 Build the parser (AC-1.2)
                - T-2 Wire the CLI (US-3, AC-2.1)
                - T-2.1 Add the budget guard (NFR-LATENCY)
                - T-10 Persist results (AC-3.4)
                """;

        TaskTraceability.Result result = TaskTraceability.check(gateVocabulary);

        assertTrue(result.traceable(),
                "AC-2.5 (DCR-5): every T-<n> task cites a gate-vocabulary requirement symbol, so traceable");
        assertEquals(4, result.taskCount(),
                "AC-2.2 (DCR-5): all four T-<n>/T-<n>.<m> tasks (incl. T-10, T-2.1) are recognized");
        assertTrue(result.untracedTasks().isEmpty(),
                "AC-2.5 (DCR-5): no task is left untraced under the gate vocabulary");
    }

    // --- CT-GF-3 (DCR-6) : real-breakdown recognition COVERAGE (no strictness relaxation) ---------
    // CT-GF-3 pins the four miscounting shapes a real Sonnet-style breakdown contains, plus a full
    // breakdown that now passes. The hardening changes recognition COVERAGE, not STRICTNESS: which ids
    // count (T-<n>/T-<n>.<m>, hyphen mandatory) and which refs count (US/AC/NFR/RD/INV) are unchanged,
    // the strict same-line-ref rule holds (a task whose own line lacks a ref is still flagged even if a
    // sibling line carries one), and no loose block scan is introduced (DCR-5 Option b stays rejected).

    @Test
    @DisplayName("CT-GF-3(a) (DCR-6): a multi-line **Refs:** block does not break the same-line-ref rule — an on-line ref traces, a Refs:-only task is still flagged")
    void multiLineRefsBlockKeepsSameLineRefRule() {
        // Oracle: CT-GF-3(a) — a multi-line **Refs:** block under a task does not cause the same-line-
        // ref rule to mis-flag a task that DOES carry an on-line ref; AND a task whose ONLY ref is on a
        // following **Refs:** line is STILL flagged untraced (the strict same-line-ref rule holds — the
        // block scan is NOT done). T-1 cites AC-1.1 on its own line (traced); T-2's only ref is on a
        // following **Refs:** line, so T-2 is untraced and the breakdown is not traceable.
        String breakdown = """
                # Tasks

                - T-1: do X (AC-1.1)
                  **Refs:** AC-1.1
                - T-2: do Y
                  **Refs:** AC-2.1
                """;

        TaskTraceability.Result result = TaskTraceability.check(breakdown);

        assertFalse(result.traceable(),
                "CT-GF-3(a): T-2's only ref is on a following **Refs:** line — same-line-ref rule, not traceable");
        assertEquals(2, result.taskCount(),
                "CT-GF-3(a): both T-1 and T-2 are recognized; the **Refs:** lines are not tasks");
        assertEquals(java.util.List.of("T-2"), result.untracedTasks(),
                "CT-GF-3(a): only T-2 is untraced — its sibling **Refs:** line is NOT scanned (no block scan)");
    }

    @Test
    @DisplayName("CT-GF-3(b) (DCR-6): a range heading T-3 through T-8 expands to 6 tasks; with no on-line ref all six are flagged untraced (not collapsed to only T-3)")
    void rangeHeadingExpandsAndFlagsEachUntraced() {
        // Oracle: CT-GF-3(b) — a range heading T-3 through T-8 expands so T-3..T-8 are each individually
        // recognized (6 tasks), and if the line carries no valid ref ALL six are flagged untraced (not
        // silently collapsed to only T-3). taskCount=6; untracedTasks=[T-3..T-8].
        String breakdown = """
                # Tasks

                ## T-3 through T-8
                """;

        TaskTraceability.Result result = TaskTraceability.check(breakdown);

        assertFalse(result.traceable(),
                "CT-GF-3(b): the range heading carries no on-line ref, so it is not traceable");
        assertEquals(6, result.taskCount(),
                "CT-GF-3(b): T-3..T-8 are each individually recognized — 6 tasks, not collapsed to 1");
        assertEquals(java.util.List.of("T-3", "T-4", "T-5", "T-6", "T-7", "T-8"), result.untracedTasks(),
                "CT-GF-3(b): each expanded id is flagged untraced when the heading carries no valid ref");
    }

    @Test
    @DisplayName("CT-GF-3(b) (DCR-6): a range heading whose line carries a valid ref traces all six expanded tasks")
    void rangeHeadingWithRefTracesAllExpandedTasks() {
        // Oracle: CT-GF-3(b) + the same-line-ref rule — a range heading whose line DOES carry a valid
        // requirement ref traces all expanded ids (the ref is on the heading's own line, so each
        // expanded T-<n> shares it). taskCount=6; traceable=true; none untraced.
        String breakdown = """
                # Tasks

                ## T-3 through T-8 (AC-4.1)
                """;

        TaskTraceability.Result result = TaskTraceability.check(breakdown);

        assertTrue(result.traceable(),
                "CT-GF-3(b): the heading line carries AC-4.1, so all six expanded tasks trace");
        assertEquals(6, result.taskCount(),
                "CT-GF-3(b): T-3..T-8 are each individually recognized — 6 tasks");
        assertTrue(result.untracedTasks().isEmpty(),
                "CT-GF-3(b): with an on-line ref, no expanded task is left untraced");
    }

    @Test
    @DisplayName("CT-GF-3(c) (DCR-6): an arrow/sequencing-diagram line (T-1 -> T-2, and unicode T-1 → T-2) is skipped, not counted as a task")
    void arrowDiagramLineIsSkipped() {
        // Oracle: CT-GF-3(c) — an arrow/sequencing-diagram line connecting task ids (T-1 -> T-2, or the
        // unicode T-1 → T-2) is NOT a task line; it is skipped (not counted, not flagged). Here the only
        // task is the real row T-3 (AC-1.1); the two diagram lines contribute zero tasks.
        String breakdown = """
                # Tasks

                T-1 -> T-2
                T-1 → T-2
                - T-3: do real work (AC-1.1)
                """;

        TaskTraceability.Result result = TaskTraceability.check(breakdown);

        assertTrue(result.traceable(),
                "CT-GF-3(c): the only task (T-3) cites AC-1.1; the arrow diagrams are skipped, so traceable");
        assertEquals(1, result.taskCount(),
                "CT-GF-3(c): both arrow-diagram lines are skipped — only the real T-3 row counts");
        assertTrue(result.untracedTasks().isEmpty(),
                "CT-GF-3(c): an arrow-diagram line is not a task, so it is not flagged untraced");
    }

    @Test
    @DisplayName("CT-GF-3(c) (DCR-6): a normal task line containing an arrow that is NOT id->id is still a task")
    void normalTaskLineWithNonIdArrowIsStillATask() {
        // Oracle: CT-GF-3(c) — detection is conservative: a line is a diagram only when an arrow
        // connects two task IDS. A normal task line whose description happens to contain an arrow to a
        // non-id ("map A -> B") is still a task. T-1 here cites AC-1.1 on its own line, so it traces.
        TaskTraceability.Result result = TaskTraceability.check("- T-1: map A -> B (AC-1.1)\n");

        assertTrue(result.traceable(),
                "CT-GF-3(c): 'T-1 ... -> B' is not id->id, so T-1 is a real task and traces via AC-1.1");
        assertEquals(1, result.taskCount(),
                "CT-GF-3(c): a non-id arrow does not turn a real task row into a skipped diagram");
    }

    @Test
    @DisplayName("CT-GF-3(d) (DCR-6): a bold-wrapped id in a table row | **T-1** | ... | is recognized as task T-1 and traced by its on-line ref")
    void boldWrappedTableCellIdIsRecognized() {
        // Oracle: CT-GF-3(d) — a bold-wrapped id in a table row (| **T-1** | does X | AC-1.1 |) is
        // recognized as task T-1 (the wrapper stripped), traced by the on-line AC-1.1. taskCount=1;
        // traceable=true; none untraced.
        TaskTraceability.Result result =
                TaskTraceability.check("| **T-1** | does X | AC-1.1 |\n");

        assertTrue(result.traceable(),
                "CT-GF-3(d): the bold-wrapped table-cell id T-1 is recognized and traces via AC-1.1");
        assertEquals(1, result.taskCount(),
                "CT-GF-3(d): | **T-1** | ... | is recognized as exactly one task");
        assertTrue(result.untracedTasks().isEmpty(),
                "CT-GF-3(d): the recognized T-1 carries an on-line ref, so it is not untraced");
    }

    @Test
    @DisplayName("CT-GF-3(d) (DCR-6): a bold-wrapped table-cell id with no on-line ref is recognized as T-1 and flagged untraced (strictness held)")
    void boldWrappedTableCellIdWithoutRefIsFlagged() {
        // Oracle: CT-GF-3(d) + the same-line-ref rule — recognition COVERAGE widens (the bold-wrapped
        // id is now recognized) but STRICTNESS is unchanged: a recognized task whose own line carries no
        // valid ref is still flagged untraced. | **T-1** | does X | (no ref) is recognized as T-1 and
        // flagged untraced, so the captured id is the bare T-1 (not "**T-1**").
        TaskTraceability.Result result =
                TaskTraceability.check("| **T-1** | does X |\n");

        assertFalse(result.traceable(),
                "CT-GF-3(d): the recognized T-1 carries no on-line ref, so it is untraced");
        assertEquals(java.util.List.of("T-1"), result.untracedTasks(),
                "CT-GF-3(d): the captured id is the bare T-1 (the ** wrapper is stripped)");
    }

    @Test
    @DisplayName("CT-GF-3 (DCR-6): a repeated untraced id is listed once in untracedTasks (dedup)")
    void repeatedUntracedIdIsDeduplicated() {
        // Oracle: CT-GF-3 / ADR-0012 (DCR-6) rule (i) — dedup: a repeated id must not be double-counted
        // in untracedTasks. A real breakdown names a task on a table row and again on a later detail
        // line, both lacking an on-line ref. taskCount counts each recognized occurrence (2 lines), but
        // untracedTasks lists the distinct id T-1 exactly once. (The same-line-ref strictness still
        // holds — both lines lack a ref, so T-1 is untraced.)
        String breakdown = """
                # Tasks

                | T-1 | the parser row |
                - T-1: parser detail line
                """;

        TaskTraceability.Result result = TaskTraceability.check(breakdown);

        assertFalse(result.traceable(),
                "CT-GF-3: T-1 carries no on-line ref on either line, so it is untraced");
        assertEquals(2, result.taskCount(),
                "CT-GF-3: each recognized occurrence is counted (taskCount = recognized task lines)");
        assertEquals(java.util.List.of("T-1"), result.untracedTasks(),
                "CT-GF-3 (DCR-6) rule (i): the repeated untraced id is listed ONCE (dedup)");
    }

    @Test
    @DisplayName("CT-GF-3(b) (DCR-6): only a well-formed ASCENDING range expands; a descending range (T-8 through T-3) contributes no task")
    void descendingRangeHeadingContributesNoTask() {
        // Oracle: CT-GF-3(b) / ADR-0012 (DCR-6) — "only well-formed simple integer ranges expand". A
        // descending range (T-8 through T-3) is not a well-formed ascending range, so it contributes no
        // recognized task (it is not silently treated as a single task, nor does it error). The
        // breakdown then has zero recognized tasks and is not traceable. This pins the malformed-range
        // edge so the expansion can never produce a backwards or garbage count.
        String breakdown = """
                # Tasks

                ## T-8 through T-3
                """;

        TaskTraceability.Result result = TaskTraceability.check(breakdown);

        assertEquals(0, result.taskCount(),
                "CT-GF-3(b): a descending range is not well-formed, so it expands to no task");
        assertFalse(result.traceable(),
                "CT-GF-3(b): with no recognized task the breakdown is not traceable");
        assertTrue(TaskTraceability.tasksInOrder(breakdown).isEmpty(),
                "CT-GF-3(b): tasksInOrder agrees — a descending range contributes no task id");
    }

    @Test
    @DisplayName("CT-GF-3(b) (DCR-6): an implausibly large range bound is rejected cleanly (no exception) and contributes no task")
    void implausiblyLargeRangeBoundIsRejectedCleanly() {
        // Oracle: CT-GF-3(b) / ADR-0012 (DCR-6) — "only well-formed simple integer ranges expand", and
        // the gate processes model-generated markdown so it must refuse a malformed shape cleanly rather
        // than throw. A bound far larger than any real task number (here 11 digits) is not a well-formed
        // simple range: it expands to no task and check() returns normally (it does NOT propagate a
        // NumberFormatException). This pins the gate's robustness on untrusted-ish range input.
        String breakdown = """
                # Tasks

                ## T-1 through T-99999999999
                """;

        TaskTraceability.Result result = TaskTraceability.check(breakdown);

        assertEquals(0, result.taskCount(),
                "CT-GF-3(b): an out-of-bound range is not well-formed, so it expands to no task");
        assertFalse(result.traceable(),
                "CT-GF-3(b): with no recognized task the breakdown is not traceable");
        assertTrue(TaskTraceability.tasksInOrder(breakdown).isEmpty(),
                "CT-GF-3(b): tasksInOrder agrees — an out-of-bound range contributes no task id");
    }

    @Test
    @DisplayName("CT-GF-3(b) (DCR-6): a parseable-but-over-bound range (T-1 through T-200000) is rejected — guard is on the numeric value, not only digit count")
    void parseableButOverBoundRangeIsRejected() {
        // Oracle: CT-GF-3(b) / ADR-0012 (DCR-6) — "only well-formed simple integer ranges expand". A
        // bound that parses fine but exceeds the plausible-range cap (200000) is still not a well-formed
        // simple task range — a real breakdown's task numbers are small. It expands to no task. This
        // pins the numeric-value half of the bound guard (distinct from the implausibly-long-digit-count
        // half above), so the cap is enforced on the value, not merely on the digit width.
        String breakdown = """
                # Tasks

                ## T-1 through T-200000
                """;

        TaskTraceability.Result result = TaskTraceability.check(breakdown);

        assertEquals(0, result.taskCount(),
                "CT-GF-3(b): a parseable bound above the plausible-range cap expands to no task");
        assertFalse(result.traceable(),
                "CT-GF-3(b): with no recognized task the breakdown is not traceable");
    }

    @Test
    @DisplayName("CT-GF-3 (DCR-6): a full Sonnet-style breakdown (arrow diagram + bold-id table + range heading), every task line carrying a valid ref, now PASSES the gate")
    void fullSonnetStyleBreakdownNowPasses() {
        // Oracle: CT-GF-3 — a full Sonnet-style breakdown containing the four shapes (a header arrow
        // diagram, a bold-id table of tasks each citing a requirement on its own line, a range heading
        // with a ref), every task line carrying >= 1 valid US/AC/NFR/RD/INV ref, now PASSES the gate
        // (traceable=true) with the correct taskCount. Previously the parser miscounted the arrow line
        // as a task (untraced), recognized only T-3 of the range (undercount), and failed to recognize
        // the bold-wrapped ids at all — so it refused. The correct count now:
        //   - arrow diagram "T-1 -> T-2": skipped (0)
        //   - bold-id table rows: T-1, T-2 (2), each with an on-line AC ref
        //   - range heading "T-3 through T-5 (NFR-LATENCY)": expands to T-3, T-4, T-5 (3), ref on line
        //   - a plain row T-6 (US-1) (1)
        // => taskCount = 6, all traced.
        String sonnetStyle = """
                # Task breakdown

                ## Sequencing
                T-1 -> T-2

                ## Tasks

                | ID | Description | Refs |
                | --- | --- | --- |
                | **T-1** | Build the parser | AC-1.2 |
                | **T-2** | Wire the CLI | US-3, AC-2.1 |

                ## Batch of follow-ups
                ## T-3 through T-5 (NFR-LATENCY)

                - T-6: Persist results (US-1)
                """;

        TaskTraceability.Result result = TaskTraceability.check(sonnetStyle);

        assertTrue(result.traceable(),
                "CT-GF-3: every recognized task carries a valid on-line ref, so the breakdown now passes");
        assertEquals(6, result.taskCount(),
                "CT-GF-3: arrow line skipped; bold T-1/T-2 recognized; range T-3..T-5 expanded; T-6 plain => 6 tasks");
        assertTrue(result.untracedTasks().isEmpty(),
                "CT-GF-3: no task is left untraced under the correctly-counted real breakdown");
    }

    // --- AC-3.1 : the implement loop reads the tasks in breakdown order --------------------------

    @Test
    @DisplayName("AC-3.1: tasksInOrder enumerates the stable task ids in breakdown (file) order")
    void tasksInOrderReturnsTaskIdsInBreakdownOrder() {
        // Oracle: AC-3.1 — "work one task at a time IN BREAKDOWN ORDER". The greenfield implement loop
        // reads the breakdown's tasks to drive them in order; tasksInOrder must return the stable ids
        // (AC-2.2) in the file order they appear. Expected order traces to the breakdown's line order.
        String breakdown = """
                # Tasks

                - T-1 Build the parser (refs AC-1.2)
                - T-2 Wire the CLI (refs US-3)
                - T-3 Persist results (refs AC-2.1)
                """;

        assertEquals(java.util.List.of("T-1", "T-2", "T-3"),
                TaskTraceability.tasksInOrder(breakdown),
                "AC-3.1: the task ids are returned in breakdown order");
    }

    @Test
    @DisplayName("AC-2.2: tasksInOrder recognizes tasks across markdown shapes, preserving order")
    void tasksInOrderRecognizesTasksAcrossShapes() {
        // Oracle: AC-2.2 — a task is recognized by its stable id whether in a heading, checkbox, or
        // table row. The order is the file order across those shapes (AC-3.1). Reuses the SAME
        // task-line recognition the traceability check uses (one source of truth).
        String breakdown = """
                ## T-1.1 Parser (AC-1.2)
                - [ ] T-2 CLI (US-3)
                | T-3.4 | Persist | AC-2.1 |
                """;

        assertEquals(java.util.List.of("T-1.1", "T-2", "T-3.4"),
                TaskTraceability.tasksInOrder(breakdown),
                "AC-2.2/AC-3.1: tasks across shapes are enumerated in file order");
    }

    @Test
    @DisplayName("tasksInOrder of a breakdown with no recognizable task is empty")
    void tasksInOrderEmptyWhenNoTask() {
        // Oracle: AC-3.1 operates over the breakdown's tasks; prose with no stable-id task yields no
        // tasks to implement.
        assertTrue(TaskTraceability.tasksInOrder("# Tasks\n\nWe will build things.\n").isEmpty(),
                "no stable-id task means an empty task order");
        assertTrue(TaskTraceability.tasksInOrder("").isEmpty(), "an empty breakdown has no tasks");
    }

    @Test
    @DisplayName("tasksInOrder rejects null input")
    void tasksInOrderRejectsNull() {
        assertThrows(NullPointerException.class, () -> TaskTraceability.tasksInOrder(null));
    }

    // --- CT-GF-3 (DCR-6) : tasksInOrder shares the SAME recognition as check (one source of truth) -
    // The directive requires check(...) and tasksInOrder(...) to agree on what a task line is. These
    // mirror (b)/(c)/(d) on tasksInOrder: a range heading also expands, an arrow line is also skipped,
    // a bold-wrapped table-cell id is also recognized — in breakdown (file) order (AC-3.1, AC-2.2).

    @Test
    @DisplayName("CT-GF-3(b) (DCR-6): tasksInOrder expands a range heading so each id appears in breakdown order")
    void tasksInOrderExpandsRangeHeading() {
        // Oracle: CT-GF-3(b) + AC-3.1 — the implement loop drives tasks in breakdown order; a range
        // heading T-3 through T-6 must expand so each of T-3..T-6 is enumerated individually, in order,
        // consistent with check()'s expansion (one source of truth for "what is a task line").
        String breakdown = """
                # Tasks

                ## T-3 through T-6 (AC-4.1)
                """;

        assertEquals(java.util.List.of("T-3", "T-4", "T-5", "T-6"),
                TaskTraceability.tasksInOrder(breakdown),
                "CT-GF-3(b)/AC-3.1: a range heading expands to its individual ids in breakdown order");
    }

    @Test
    @DisplayName("CT-GF-3(c)/(d) (DCR-6): tasksInOrder skips an arrow diagram and recognizes a bold-wrapped table-cell id, in breakdown order")
    void tasksInOrderSkipsArrowAndRecognizesBoldCell() {
        // Oracle: CT-GF-3(c)/(d) + AC-3.1 — tasksInOrder must share check()'s recognition: an arrow/
        // sequencing-diagram line (T-1 -> T-2) is skipped (contributes no task), and a bold-wrapped
        // table-cell id (| **T-1** | ... |) is recognized as T-1. The enumerated order is the file order
        // across the recognized rows.
        String breakdown = """
                # Tasks

                T-1 -> T-2
                | **T-1** | Build the parser | AC-1.2 |
                | **T-2** | Wire the CLI | US-3 |
                """;

        assertEquals(java.util.List.of("T-1", "T-2"),
                TaskTraceability.tasksInOrder(breakdown),
                "CT-GF-3(c)/(d)/AC-3.1: arrow diagram skipped, bold-wrapped ids recognized, in file order");
    }
}
