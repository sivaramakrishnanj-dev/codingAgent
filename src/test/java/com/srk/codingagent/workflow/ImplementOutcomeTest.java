package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.loop.VerifyOutcome;
import com.srk.codingagent.tool.CommandResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ImplementOutcome}: the terminal result of one {@link GreenfieldImplementLoop} run
 * (US-3, AC-3.2/3.3/3.6, AC-3.4; ADR-0012 amended by DCR-7). With per-task verification removed
 * (DCR-7 — verify runs once at end of phase, not per task), the outcome reports which tasks the loop
 * implemented and marked complete on implementation, plus how the single end-of-phase verify gated
 * the phase: a passing verify (or no verify) is {@link ImplementOutcome.Disposition#ALL_IMPLEMENTED},
 * an exhausted verify is {@link ImplementOutcome.Disposition#VERIFY_FAILED} (AC-3.4/AC-20.5), and a
 * no-configured-test-command skip is {@link ImplementOutcome.Disposition#COMPLETE_WITH_WARNING}
 * (AC-3.6). The record's compact constructor enforces invariants so an inconsistent outcome cannot
 * exist.
 *
 * <p><b>Oracles trace to the amended implement ACs, not the loop's code:</b>
 * {@link ImplementOutcome.Disposition#ALL_IMPLEMENTED} is the "every task implemented and marked
 * complete on implementation, in breakdown order, end verify passed (or skipped)" result
 * (AC-3.2/3.3, ADR-0012/DCR-7); {@link ImplementOutcome.Disposition#VERIFY_FAILED} is the
 * end-of-phase verify-failed surface (AC-3.4/AC-20.5); {@link ImplementOutcome.Disposition#COMPLETE_WITH_WARNING}
 * is the no-test-command complete-with-warning terminal (AC-3.6); {@link ImplementOutcome.Disposition#NO_TASKS}
 * is the empty-breakdown result.
 */
class ImplementOutcomeTest {

    private static final String CMD = "mvn test";

    private static VerifyOutcome exhausted(int iterations) {
        return VerifyOutcome.exhausted(iterations,
                CommandResult.completed(CMD, 1, "out", "boom", 10L));
    }

    private static VerifyOutcome verified() {
        return VerifyOutcome.verified(1, CommandResult.completed(CMD, 0, "ok", "", 10L));
    }

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
                () -> new ImplementOutcome(
                        ImplementOutcome.Disposition.NO_TASKS, List.of("T-1"), null));
    }

    @Test
    @DisplayName("a no-tasks outcome must not carry a verify outcome (no verify ran)")
    void noTasksRejectsVerifyOutcome() {
        // Oracle: AC-3.2 — the end-of-phase verify runs only after tasks were implemented; a no-tasks
        // run implemented nothing and ran no verify, so carrying a verify outcome on it is inconsistent.
        assertThrows(IllegalArgumentException.class,
                () -> new ImplementOutcome(
                        ImplementOutcome.Disposition.NO_TASKS, List.of(), verified()));
    }

    @Test
    @DisplayName("the disposition and implemented-task list are required")
    void requiresDispositionAndTasks() {
        assertThrows(NullPointerException.class,
                () -> new ImplementOutcome(null, List.of(), null));
        assertThrows(NullPointerException.class,
                () -> new ImplementOutcome(ImplementOutcome.Disposition.ALL_IMPLEMENTED, null, null));
    }

    // --- ALL_IMPLEMENTED with a passing end-of-phase verify (AC-3.2 testable-only) ----------------

    @Test
    @DisplayName("AC-3.2: a verified() outcome is all-implemented and carries the passing end-of-phase verify")
    void verifiedCarriesPassingEndOfPhaseVerify() {
        // Oracle: AC-3.2 (amended DCR-7) — when all tasks are implemented the agent verifies them ONCE
        // at end of phase via the configured build/test commands. A passing end-of-phase verify is the
        // clean phase success (ALL_IMPLEMENTED), carrying the verified verify outcome. The expected
        // disposition + the carried verify trace to AC-3.2, not to the loop's code.
        VerifyOutcome verify = verified();
        ImplementOutcome outcome = ImplementOutcome.verified(List.of("T-1", "T-2"), verify);

        assertTrue(outcome.allImplemented(),
                "AC-3.2: a passing end-of-phase verify is the clean phase success (all-implemented)");
        assertEquals(List.of("T-1", "T-2"), outcome.implementedTasks(),
                "AC-3.2: the implemented ids are carried in breakdown order");
        assertSame(verify, outcome.verifyOutcomeIfPresent().orElseThrow(),
                "AC-3.2: the passing end-of-phase verify outcome is carried");
    }

    @Test
    @DisplayName("AC-3.2: an all-implemented outcome's verify, when present, must be a verified one")
    void allImplementedRejectsNonVerifiedVerify() {
        // Oracle: the record invariant grounded in AC-3.2 — ALL_IMPLEMENTED is the success disposition;
        // a non-verified end-of-phase verify is a different terminal state (VERIFY_FAILED), so pairing a
        // non-verified verify with ALL_IMPLEMENTED is inconsistent.
        assertThrows(IllegalArgumentException.class,
                () -> new ImplementOutcome(
                        ImplementOutcome.Disposition.ALL_IMPLEMENTED, List.of("T-1"), exhausted(5)));
    }

    // --- VERIFY_FAILED : the end-of-phase verify did not pass (AC-3.4/AC-20.5) --------------------

    @Test
    @DisplayName("AC-3.4/AC-20.5: a verifyFailed() outcome carries the EXHAUSTED verify and is not the success")
    void verifyFailedCarriesExhaustedVerify() {
        // Oracle: AC-3.4 — if end-of-phase verification fails after NFR-VERIFY-MAX-ITERATIONS, the
        // agent stops and surfaces the failure. The implemented tasks were implemented, but the end
        // verify did not pass: the outcome carries the EXHAUSTED verify (whose last failing run is the
        // relevant output to surface, AC-20.5) and is NOT the all-implemented success.
        VerifyOutcome verify = exhausted(5);
        ImplementOutcome outcome = ImplementOutcome.verifyFailed(List.of("T-1", "T-2"), verify);

        assertEquals(ImplementOutcome.Disposition.VERIFY_FAILED, outcome.disposition());
        assertFalse(outcome.allImplemented(),
                "AC-3.4: an end-verify failure is not the clean phase success");
        assertEquals(List.of("T-1", "T-2"), outcome.implementedTasks(),
                "AC-3.4: the tasks were implemented; only the end verify did not pass");
        assertSame(verify, outcome.verifyOutcome(),
                "AC-20.5: the EXHAUSTED verify (with the failing output) is carried for surfacing");
    }

    @Test
    @DisplayName("VERIFY_FAILED must carry a non-verified verify, and not the NO_TEST_COMMAND one")
    void verifyFailedInvariants() {
        // Oracle: the record invariant — VERIFY_FAILED is the "end verify did not pass" state, so a
        // verified verify (the verify passed) and a NO_TEST_COMMAND verify (no verify ran) are both
        // inconsistent with it; the verify is also required.
        assertThrows(IllegalArgumentException.class,
                () -> new ImplementOutcome(
                        ImplementOutcome.Disposition.VERIFY_FAILED, List.of("T-1"), null),
                "VERIFY_FAILED requires a verify outcome");
        assertThrows(IllegalArgumentException.class,
                () -> new ImplementOutcome(
                        ImplementOutcome.Disposition.VERIFY_FAILED, List.of("T-1"), verified()),
                "VERIFY_FAILED must not carry a verified() verify");
        assertThrows(IllegalArgumentException.class,
                () -> new ImplementOutcome(ImplementOutcome.Disposition.VERIFY_FAILED,
                        List.of("T-1"), VerifyOutcome.noTestCommand()),
                "VERIFY_FAILED carries EXHAUSTED, not NO_TEST_COMMAND");
    }

    // --- COMPLETE_WITH_WARNING : no test command, skip end verify with one warning (AC-3.6) -------

    @Test
    @DisplayName("AC-3.6: a completeWithWarning() outcome carries the NO_TEST_COMMAND verify (skipped, terminal)")
    void completeWithWarningCarriesNoTestCommandVerify() {
        // Oracle: AC-3.6 (new, DCR-7) — with no configured test command the agent skips the
        // end-of-phase verification with a single warning, having implemented and marked complete every
        // task, and terminates the phase deterministically. The outcome carries the NO_TEST_COMMAND
        // verify (the verify was skipped, nothing ran) and the implemented ids. Traced to AC-3.6.
        VerifyOutcome verify = VerifyOutcome.noTestCommand();
        ImplementOutcome outcome = ImplementOutcome.completeWithWarning(List.of("T-1", "T-2"), verify);

        assertEquals(ImplementOutcome.Disposition.COMPLETE_WITH_WARNING, outcome.disposition());
        assertEquals(List.of("T-1", "T-2"), outcome.implementedTasks(),
                "AC-3.6: every task was implemented and marked complete before the verify was skipped");
        assertEquals(VerifyOutcome.Kind.NO_TEST_COMMAND, outcome.verifyOutcome().kind(),
                "AC-3.6: the skipped (no-test-command) verify outcome is carried");
    }

    @Test
    @DisplayName("COMPLETE_WITH_WARNING must carry the NO_TEST_COMMAND verify, not a ran verify")
    void completeWithWarningInvariants() {
        // Oracle: the record invariant — COMPLETE_WITH_WARNING is the no-test-command skip (AC-3.6); a
        // ran verify (EXHAUSTED or VERIFIED) is inconsistent with "the verify was skipped", and the
        // verify is required.
        assertThrows(IllegalArgumentException.class,
                () -> new ImplementOutcome(
                        ImplementOutcome.Disposition.COMPLETE_WITH_WARNING, List.of("T-1"), null),
                "COMPLETE_WITH_WARNING requires a verify outcome");
        assertThrows(IllegalArgumentException.class,
                () -> new ImplementOutcome(
                        ImplementOutcome.Disposition.COMPLETE_WITH_WARNING, List.of("T-1"), exhausted(5)),
                "COMPLETE_WITH_WARNING must carry NO_TEST_COMMAND, not an EXHAUSTED verify");
    }
}
