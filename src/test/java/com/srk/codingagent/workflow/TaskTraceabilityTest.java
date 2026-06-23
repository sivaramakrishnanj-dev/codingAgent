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
}
