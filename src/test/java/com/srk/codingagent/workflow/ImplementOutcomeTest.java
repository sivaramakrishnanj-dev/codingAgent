package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.loop.VerifyOutcome;
import com.srk.codingagent.tool.CommandResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ImplementOutcome}: the terminal result of one {@link GreenfieldImplementLoop} run
 * (US-3, AC-3.1/3.3/3.4). The record's compact constructor enforces invariants so an inconsistent
 * outcome cannot exist — a verify-exhausted outcome must name the stopped task and carry the failing
 * verify outcome (AC-3.4/AC-20.5), the other dispositions must not name a stopped task.
 *
 * <p><b>Oracles trace to the implement-loop contract:</b> {@link ImplementOutcome.Disposition#ALL_VERIFIED}
 * is the "every task verified and marked complete" success (AC-3.1/3.3);
 * {@link ImplementOutcome.Disposition#VERIFY_EXHAUSTED} is the "stop at the failing task with its
 * output" surface (AC-3.4/AC-20.5).
 */
class ImplementOutcomeTest {

    private static VerifyOutcome verified() {
        return VerifyOutcome.verified(1, CommandResult.completed("mvn test", 0, "ok", "", 5L));
    }

    private static VerifyOutcome exhausted() {
        return VerifyOutcome.exhausted(5, CommandResult.completed("mvn test", 1, "", "boom", 5L));
    }

    // --- ALL_VERIFIED ----------------------------------------------------------------------------

    @Test
    @DisplayName("AC-3.1/3.3: an all-verified outcome carries the completed task ids in order and is the success")
    void allVerifiedCarriesCompletedTasks() {
        // Oracle: AC-3.1/3.3 — an all-verified run implemented, verified, and marked complete every
        // task in breakdown order. The outcome lists those tasks (in order) and is the success signal.
        ImplementOutcome outcome = ImplementOutcome.allVerified(List.of("T-1", "T-2"), verified());

        assertTrue(outcome.allVerified(), "AC-3.1/3.3: an all-verified run is the implement success");
        assertEquals(List.of("T-1", "T-2"), outcome.completedTasks(),
                "the completed task ids are carried in breakdown order");
        assertTrue(outcome.stoppedTaskIfPresent().isEmpty(),
                "an all-verified run did not stop at any task");
        assertTrue(outcome.verifyOutcomeIfPresent().isPresent(),
                "the last task's verifying outcome is carried");
    }

    @Test
    @DisplayName("the completed-task list is defensively copied (the outcome is immutable)")
    void completedTasksDefensivelyCopied() {
        // Oracle: EJ Item 50 — an immutable value object copies its mutable input. Mutating the input
        // list after construction must not change the outcome.
        List<String> mutable = new ArrayList<>(List.of("T-1"));
        ImplementOutcome outcome = ImplementOutcome.allVerified(mutable, verified());
        mutable.add("T-2");

        assertEquals(List.of("T-1"), outcome.completedTasks(),
                "the outcome's task list is unaffected by later mutation of the input");
    }

    // --- VERIFY_EXHAUSTED ------------------------------------------------------------------------

    @Test
    @DisplayName("AC-3.4/AC-20.5: a verify-exhausted outcome names the stopped task and carries the failing output")
    void verifyExhaustedNamesStoppedTask() {
        // Oracle: AC-3.4 — stop at the failing task; AC-20.5 — surface with the relevant output. The
        // outcome names the stopped task and carries the non-verified verify outcome.
        ImplementOutcome outcome =
                ImplementOutcome.verifyExhausted(List.of("T-1"), "T-2", exhausted());

        assertFalse(outcome.allVerified(), "AC-3.4: a verify-exhausted run is not a success");
        assertEquals("T-2", outcome.stoppedTaskIfPresent().orElseThrow(),
                "AC-3.4: the outcome names the task the loop stopped at");
        assertEquals(List.of("T-1"), outcome.completedTasks(),
                "the tasks completed before the failure are kept");
        assertEquals(VerifyOutcome.Kind.EXHAUSTED, outcome.verifyOutcome().kind(),
                "AC-20.5: the failing verify outcome is carried for surfacing");
    }

    @Test
    @DisplayName("VERIFY_EXHAUSTED must name the stopped task")
    void verifyExhaustedRequiresStoppedTask() {
        // Oracle: the record invariant — a verify-exhausted outcome that does not name the task it
        // stopped at is inconsistent (AC-3.4 requires identifying the failing task).
        assertThrows(IllegalArgumentException.class,
                () -> new ImplementOutcome(
                        ImplementOutcome.Disposition.VERIFY_EXHAUSTED, List.of(), null, exhausted()));
        assertThrows(IllegalArgumentException.class,
                () -> new ImplementOutcome(
                        ImplementOutcome.Disposition.VERIFY_EXHAUSTED, List.of(), " ", exhausted()));
    }

    @Test
    @DisplayName("VERIFY_EXHAUSTED must carry a non-verified verify outcome")
    void verifyExhaustedRejectsVerifiedOutcome() {
        // Oracle: AC-3.4 — the disposition is reached because verification did NOT pass; carrying a
        // verified() outcome would contradict the disposition.
        assertThrows(IllegalArgumentException.class,
                () -> new ImplementOutcome(
                        ImplementOutcome.Disposition.VERIFY_EXHAUSTED, List.of(), "T-1", verified()));
        assertThrows(IllegalArgumentException.class,
                () -> new ImplementOutcome(
                        ImplementOutcome.Disposition.VERIFY_EXHAUSTED, List.of(), "T-1", null));
    }

    // --- NO_TEST_COMMAND / NO_TASKS --------------------------------------------------------------

    @Test
    @DisplayName("AC-20.6: a no-test-command outcome carries the NO_TEST_COMMAND verify outcome and no completed tasks")
    void noTestCommandOutcome() {
        // Oracle: AC-20.6 — an absent test command is reported. No task is marked complete (nothing
        // was verified), and the carried verify outcome is NO_TEST_COMMAND.
        ImplementOutcome outcome = ImplementOutcome.noTestCommand(VerifyOutcome.noTestCommand());

        assertEquals(ImplementOutcome.Disposition.NO_TEST_COMMAND, outcome.disposition());
        assertTrue(outcome.completedTasks().isEmpty(), "AC-20.6: no task completed without verification");
        assertEquals(VerifyOutcome.Kind.NO_TEST_COMMAND, outcome.verifyOutcome().kind());
        assertTrue(outcome.stoppedTaskIfPresent().isEmpty(),
                "a no-test-command outcome did not stop at a failing task");
    }

    @Test
    @DisplayName("a no-tasks outcome carries no completed tasks and no verify outcome")
    void noTasksOutcome() {
        // Oracle: AC-3.1 operates over the breakdown's tasks; a no-tasks outcome ran no verification.
        ImplementOutcome outcome = ImplementOutcome.noTasks();

        assertEquals(ImplementOutcome.Disposition.NO_TASKS, outcome.disposition());
        assertTrue(outcome.completedTasks().isEmpty());
        assertTrue(outcome.verifyOutcomeIfPresent().isEmpty(), "no verification ran for a no-tasks run");
        assertTrue(outcome.stoppedTaskIfPresent().isEmpty());
    }

    @Test
    @DisplayName("a non-exhausted disposition must not name a stopped task")
    void nonExhaustedRejectsStoppedTask() {
        // Oracle: only a verify-exhausted run stops AT a task; naming a stopped task on another
        // disposition is inconsistent.
        assertThrows(IllegalArgumentException.class,
                () -> new ImplementOutcome(
                        ImplementOutcome.Disposition.ALL_VERIFIED, List.of("T-1"), "T-1", verified()));
        assertThrows(IllegalArgumentException.class,
                () -> new ImplementOutcome(
                        ImplementOutcome.Disposition.NO_TASKS, List.of(), "T-1", null));
    }

    @Test
    @DisplayName("the disposition and completed-task list are required")
    void requiresDispositionAndTasks() {
        assertThrows(NullPointerException.class,
                () -> new ImplementOutcome(null, List.of(), null, null));
        assertThrows(NullPointerException.class,
                () -> new ImplementOutcome(
                        ImplementOutcome.Disposition.ALL_VERIFIED, null, null, verified()));
    }
}
