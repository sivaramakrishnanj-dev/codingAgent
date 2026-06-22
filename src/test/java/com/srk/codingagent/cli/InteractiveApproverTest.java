package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.permission.GateRequest;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InteractiveApprover} — the REPL approval seam (AC-10.1, AC-10.2).
 *
 * <p><b>SUT and collaborators.</b> The SUT is a real {@link InteractiveApprover}. Its two
 * collaborators are the boundary it talks to a terminal through: the answer source (a
 * {@link Supplier} of typed lines) and the prompt stream (a captured {@link PrintStream}).
 * Neither is the SUT; both are real, controllable doubles for the terminal so the
 * approve/deny round-trip is exercised without a live console.
 *
 * <p><b>Oracles.</b> Expected behaviour traces to the acceptance criteria, never to the
 * approver's code:
 * <ul>
 *   <li><b>AC-10.1</b> — "present the exact operation ... before executing": the presentation
 *       (from {@link GateRequest#presentation()}) is written to the prompt stream <em>before</em>
 *       the answer is read.</li>
 *   <li><b>AC-10.2</b> — "when the developer denies ... shall not execute it": a non-affirmative
 *       answer (incl. blank / unrecognized / end-of-input) yields
 *       {@link PermissionDecisionOutcome#DENY}, so the gate runs no handler.</li>
 * </ul>
 */
class InteractiveApproverTest {

    private final ByteArrayOutputStream promptBytes = new ByteArrayOutputStream();
    private final PrintStream prompt = new PrintStream(promptBytes, true, StandardCharsets.UTF_8);

    private String promptText() {
        return promptBytes.toString(StandardCharsets.UTF_8);
    }

    /** An answer source that replays a fixed sequence of typed lines, then end-of-input. */
    private static Supplier<String> answers(String... lines) {
        Deque<String> queue = new ArrayDeque<>(List.of(lines));
        return () -> queue.isEmpty() ? null : queue.removeFirst();
    }

    @Test
    @DisplayName("AC-10.1: the exact command operation is presented before the decision is read")
    void presentsExactCommandBeforeReadingDecision() {
        // Oracle: AC-10.1 — "present the exact operation (command string ...) before executing".
        // The answer source asserts, at the moment it is consulted, that the presentation is
        // ALREADY on the prompt stream — so the operation is shown before the decision is taken.
        GateRequest request = GateRequest.forCommand("tu_1", "rm -rf build");
        String expectedPresentation = request.presentation(); // tool name + ": " + command
        Supplier<String> answerWhenPromptShown = () -> {
            assertTrue(promptText().contains(expectedPresentation),
                    "AC-10.1: the exact operation must be presented BEFORE the decision is read; "
                            + "prompt was: " + promptText());
            return "y";
        };
        InteractiveApprover approver = new InteractiveApprover(answerWhenPromptShown, prompt);

        PermissionDecisionOutcome outcome = approver.confirm(request);

        assertEquals(PermissionDecisionOutcome.APPROVE, outcome,
                "an affirmative answer approves");
        assertTrue(promptText().contains("rm -rf build"),
                "AC-10.1: the exact command string is presented; was: " + promptText());
    }

    @Test
    @DisplayName("AC-10.1: a file write presents the path and change summary before deciding")
    void presentsWritePathAndSummary() {
        // Oracle: AC-10.1 — "(... or file path + change summary) before executing". The write
        // presentation (path + summary) must appear on the prompt stream.
        GateRequest request = GateRequest.forWrite("tu_2", "src/Main.java", "rewrite main");
        InteractiveApprover approver = new InteractiveApprover(answers("yes"), prompt);

        approver.confirm(request);

        assertTrue(promptText().contains("src/Main.java"),
                "AC-10.1: the file path is presented; was: " + promptText());
        assertTrue(promptText().contains("rewrite main"),
                "AC-10.1: the change summary is presented; was: " + promptText());
    }

    @Test
    @DisplayName("AC-10.1/AC-10.2: an affirmative 'y' approves the operation")
    void affirmativeYApproves() {
        // Oracle: AC-10.2 (inverse) — an explicit approval lets the operation run; the approver
        // returns APPROVE so the gate dispatches the tool.
        InteractiveApprover approver = new InteractiveApprover(answers("y"), prompt);

        assertEquals(PermissionDecisionOutcome.APPROVE,
                approver.confirm(GateRequest.forCommand("tu", "ls")),
                "AC-10.2: an affirmative answer approves the operation");
    }

    @Test
    @DisplayName("AC-10.2: a 'no' answer denies — the operation is not run")
    void negativeAnswerDenies() {
        // Oracle: AC-10.2 — "the agent shall not execute it". A non-affirmative answer denies;
        // the gate then runs no handler (no side effect).
        InteractiveApprover approver = new InteractiveApprover(answers("n"), prompt);

        assertEquals(PermissionDecisionOutcome.DENY,
                approver.confirm(GateRequest.forCommand("tu", "rm -rf /")),
                "AC-10.2: a denial means the operation is not executed");
    }

    @Test
    @DisplayName("AC-10.2: end-of-input (Ctrl-D at the prompt) is a fail-closed denial")
    void endOfInputDenies() {
        // Oracle: AC-10.2 — a closed input must NEVER be a silent approval; the safe default is
        // denial (the operation does not run). The answer source returns null (end-of-input).
        InteractiveApprover approver = new InteractiveApprover(() -> null, prompt);

        assertEquals(PermissionDecisionOutcome.DENY,
                approver.confirm(GateRequest.forCommand("tu", "rm -rf /")),
                "AC-10.2: end-of-input denies (fail-closed), never silently approves");
    }

    @Test
    @DisplayName("AC-10.2: a blank answer denies (fail-closed)")
    void blankAnswerDenies() {
        // Oracle: AC-10.2 — only an explicit affirmative approves; a blank line is not an
        // affirmation, so it denies.
        InteractiveApprover approver = new InteractiveApprover(answers("   "), prompt);

        assertEquals(PermissionDecisionOutcome.DENY,
                approver.confirm(GateRequest.forCommand("tu", "deploy")),
                "AC-10.2: a blank answer is not an approval");
    }

    @Test
    @DisplayName("AC-10.2: an unrecognized answer denies (only y/yes approve)")
    void unrecognizedAnswerDenies() {
        // Oracle: AC-10.2 — anything that is not an explicit affirmative denies, so a fat-finger
        // never accidentally runs a side-effecting operation.
        InteractiveApprover approver = new InteractiveApprover(answers("maybe"), prompt);

        assertEquals(PermissionDecisionOutcome.DENY,
                approver.confirm(GateRequest.forCommand("tu", "push")),
                "AC-10.2: an unrecognized answer denies");
    }

    @Test
    @DisplayName("an affirmative answer is case-insensitive and ignores surrounding whitespace")
    void affirmativeIsCaseInsensitiveAndTrimmed() {
        // Oracle: AC-10.2 (inverse) — the affirmative recognition is robust to casing and stray
        // whitespace so a clearly-intended yes is honoured. ("YES" with padding approves.)
        InteractiveApprover approver = new InteractiveApprover(answers("  YES  "), prompt);

        assertEquals(PermissionDecisionOutcome.APPROVE,
                approver.confirm(GateRequest.forCommand("tu", "ls")),
                "a padded, upper-case YES still approves");
    }

    @Test
    @DisplayName("the approver requires its answer source and prompt stream (composition contract)")
    void constructorRejectsNull() {
        assertThrows(NullPointerException.class,
                () -> new InteractiveApprover(null, prompt));
        assertThrows(NullPointerException.class,
                () -> new InteractiveApprover(answers("y"), null));
    }
}
