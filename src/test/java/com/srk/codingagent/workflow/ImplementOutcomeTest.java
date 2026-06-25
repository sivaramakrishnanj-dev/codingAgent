package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ImplementOutcome}: the terminal result of one {@link GreenfieldImplementLoop} run
 * (US-3, AC-3.2/3.3; ADR-0012 amended by DCR-7). With per-task verification removed (DCR-7 — verify
 * runs once at end of phase, not per task), the outcome no longer carries a per-task verify result or
 * a per-task verify-exhausted stop: it reports which tasks the loop implemented and marked complete
 * on implementation. The record's compact constructor still enforces an invariant so an inconsistent
 * outcome cannot exist — a no-tasks outcome implemented nothing.
 *
 * <p><b>Oracles trace to the amended implement ACs, not the loop's code:</b>
 * {@link ImplementOutcome.Disposition#ALL_IMPLEMENTED} is the "every task implemented and marked
 * complete on implementation, in breakdown order" result (AC-3.2/3.3, ADR-0012/DCR-7);
 * {@link ImplementOutcome.Disposition#NO_TASKS} is the empty-breakdown result.
 */
class ImplementOutcomeTest {

    // --- ALL_IMPLEMENTED -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-3.2/3.3: an all-implemented outcome carries the implemented task ids in order and is the success")
    void allImplementedCarriesImplementedTasks() {
        // Oracle: AC-3.2/3.3 + ADR-0012/DCR-7 — an all-implemented run implemented every task in
        // breakdown order and marked each complete on implementation. The outcome lists those tasks
        // (in order) and is the implement-loop success signal the run path branches on. End-of-phase
        // verification (T-3.9) gates the phase around this result; the outcome itself carries no
        // per-task verify result (per-task verify dropped, DCR-7).
        ImplementOutcome outcome = ImplementOutcome.allImplemented(List.of("T-1", "T-2"));

        assertTrue(outcome.allImplemented(),
                "AC-3.2/3.3: an all-implemented run is the implement-loop success");
        assertEquals(List.of("T-1", "T-2"), outcome.implementedTasks(),
                "AC-3.2/3.3: the implemented task ids are carried in breakdown order");
    }

    @Test
    @DisplayName("the implemented-task list is defensively copied (the outcome is immutable)")
    void implementedTasksDefensivelyCopied() {
        // Oracle: EJ Item 50 — an immutable value object copies its mutable input. Mutating the input
        // list after construction must not change the outcome.
        List<String> mutable = new ArrayList<>(List.of("T-1"));
        ImplementOutcome outcome = ImplementOutcome.allImplemented(mutable);
        mutable.add("T-2");

        assertEquals(List.of("T-1"), outcome.implementedTasks(),
                "the outcome's task list is unaffected by later mutation of the input");
    }

    // --- NO_TASKS --------------------------------------------------------------------------------

    @Test
    @DisplayName("a no-tasks outcome carries no implemented tasks and is not a success")
    void noTasksOutcome() {
        // Oracle: AC-3.2 operates over the breakdown's tasks; a no-tasks outcome implemented nothing.
        ImplementOutcome outcome = ImplementOutcome.noTasks();

        assertEquals(ImplementOutcome.Disposition.NO_TASKS, outcome.disposition());
        assertTrue(outcome.implementedTasks().isEmpty(), "a no-tasks run implemented no task");
        assertFalse(outcome.allImplemented(), "a no-tasks run is not the implement success");
    }

    @Test
    @DisplayName("a no-tasks outcome must not name any implemented task")
    void noTasksRejectsImplementedTasks() {
        // Oracle: the record invariant — a no-tasks outcome implemented nothing, so naming an
        // implemented task on it is inconsistent.
        assertThrows(IllegalArgumentException.class,
                () -> new ImplementOutcome(ImplementOutcome.Disposition.NO_TASKS, List.of("T-1")));
    }

    @Test
    @DisplayName("the disposition and implemented-task list are required")
    void requiresDispositionAndTasks() {
        assertThrows(NullPointerException.class,
                () -> new ImplementOutcome(null, List.of()));
        assertThrows(NullPointerException.class,
                () -> new ImplementOutcome(ImplementOutcome.Disposition.ALL_IMPLEMENTED, null));
    }
}
