package com.srk.codingagent.cli;

import com.srk.codingagent.permission.Approver;
import com.srk.codingagent.permission.GateRequest;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import java.io.PrintStream;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link Approver} used on the interactive ({@code codingagent}) REPL path: it presents
 * the exact operation that requires approval and reads the developer's answer from the
 * terminal (AC-10.1, AC-10.2).
 *
 * <p><b>AC-10.1 — present the exact operation before executing.</b> The gate consults this
 * approver only when a prompt is actually required (Class-R reads, {@code UNRESTRICTED}-mode
 * auto-approvals, and grant-matched auto-approvals never reach an {@link Approver}). When it
 * does, this approver writes {@link GateRequest#presentation()} — the exact command string,
 * or the file path plus its change summary — to the prompt stream <em>before</em> reading the
 * decision, so the developer always sees precisely what would run before answering.
 *
 * <p><b>AC-10.2 — a denial blocks the side effect.</b> An answer that is not an affirmative
 * yes is treated as {@link PermissionDecisionOutcome#DENY}: the gate then runs no handler, so
 * the side effect never happens. End-of-input (the developer closed the prompt's input, e.g.
 * Ctrl-D) is also a denial — the safe, fail-closed default — never a silent approval.
 *
 * <p>This is the REPL twin of {@link NonInteractiveApprover} (the one-shot path, which cannot
 * prompt and so aborts the run). It is a thin, fully unit-testable seam: the input source is a
 * {@link Supplier} of answer lines (returning {@code null} at end-of-input) and the prompt
 * stream is injected, so the approve/deny round-trip is exercised without a live terminal.
 */
public final class InteractiveApprover implements Approver {

    private static final Logger LOGGER = LoggerFactory.getLogger(InteractiveApprover.class);

    private final Supplier<String> answerSource;
    private final PrintStream prompt;

    /**
     * Creates an interactive approver that presents operations on {@code prompt} and reads
     * the developer's answers from {@code answerSource}.
     *
     * @param answerSource the source of answer lines; each call returns the next line the
     *                     developer typed, or {@code null} at end-of-input. Must not be
     *                     {@code null}.
     * @param prompt       the stream the approval prompt is written to (the REPL owns
     *                     user-facing output, 04-apis § 1.6); must not be {@code null}.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public InteractiveApprover(Supplier<String> answerSource, PrintStream prompt) {
        this.answerSource = Objects.requireNonNull(answerSource, "answerSource");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
    }

    /**
     * Presents the exact operation (AC-10.1) and returns the developer's approve/deny answer.
     *
     * <p>The presentation line is written before the answer is read, so the developer sees
     * the operation before deciding. An affirmative answer ({@code y} / {@code yes},
     * case-insensitive, surrounding whitespace ignored) approves; anything else — including a
     * blank line, an unrecognized answer, or end-of-input — denies (fail-closed, AC-10.2).
     *
     * @param request the gated operation to present; never {@code null}.
     * @return {@link PermissionDecisionOutcome#APPROVE} when the developer affirmed, otherwise
     *         {@link PermissionDecisionOutcome#DENY}; never {@code null}.
     */
    @Override
    public PermissionDecisionOutcome confirm(GateRequest request) {
        // AC-10.1: present the exact operation BEFORE collecting the decision.
        prompt.println("Approve operation? " + request.presentation() + " [y/N]");
        String answer = answerSource.get();
        if (isAffirmative(answer)) {
            LOGGER.info("Operation approved by developer: {}", request.presentation());
            return PermissionDecisionOutcome.APPROVE;
        }
        // AC-10.2: a non-affirmative answer (incl. EOF / blank) denies; no side effect runs.
        LOGGER.info("Operation denied by developer: {}", request.presentation());
        return PermissionDecisionOutcome.DENY;
    }

    private static boolean isAffirmative(String answer) {
        if (answer == null) {
            return false;
        }
        String normalized = answer.strip().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("y") || normalized.equals("yes");
    }
}
