package com.srk.codingagent.loop;

import com.srk.codingagent.tool.CommandResult;
import java.util.Objects;
import java.util.Optional;

/**
 * The terminal result of one {@link VerifyLoop} run: whether the unit of work verified,
 * how many attempts it took, and the final command's output. This is the value the
 * workflow driver (T-1.6 brownfield, T-3.3 greenfield) reads to decide what to do after a
 * unit of work's changes are applied; the verify loop never calls {@code System.exit} and
 * a verify-exhausted outcome is <em>surfaced, not necessarily fatal</em> (02-architecture
 * &sect; 3.2 error matrix &mdash; the driver decides).
 *
 * <p>The three terminal shapes, by {@link Kind}:
 * <ul>
 *   <li><b>{@link Kind#VERIFIED}.</b> The configured test command exited {@code 0} on the
 *       {@link #iterations()}-th attempt (within the bound). This is the unit-of-work
 *       success signal (RD-10, INV-17, AC-20.4): success iff a zero exit from the
 *       configured test command. {@link #result()} carries that passing run.</li>
 *   <li><b>{@link Kind#EXHAUSTED}.</b> The command exited non-zero on every one of the
 *       {@code verifyMaxIterations} attempts (AC-3.4, AC-20.5, CT-SM-5). The loop stops and
 *       surfaces (state machine A, S7 Surfacing &mdash; "verify-exhausted") rather than
 *       continuing silently. {@link #result()} carries the <em>last</em> failing run, whose
 *       stdout/stderr is the relevant output to surface (AC-20.5 "with the relevant
 *       output").</li>
 *   <li><b>{@link Kind#NO_TEST_COMMAND}.</b> No test command is configured
 *       ({@link com.srk.codingagent.config.ResolvedConfig.Commands#test()} is {@code null}),
 *       so there is nothing to verify against (AC-20.6 prefers the configured command;
 *       absence is a config state to report, not to paper over with an ad-hoc command). No
 *       command ran: {@code iterations} is {@code 0} and there is no {@link #result()}.</li>
 * </ul>
 *
 * @param kind       which terminal state the verify loop reached; must not be {@code null}.
 * @param iterations the number of command attempts made: {@code 1..verifyMaxIterations}
 *                   for {@link Kind#VERIFIED} (the attempt that passed) and for
 *                   {@link Kind#EXHAUSTED} (every attempt failed, so this equals the
 *                   bound), and {@code 0} for {@link Kind#NO_TEST_COMMAND}.
 * @param result     the final {@link CommandResult} &mdash; the passing run for
 *                   {@link Kind#VERIFIED}, the last failing run for {@link Kind#EXHAUSTED},
 *                   or {@code null} for {@link Kind#NO_TEST_COMMAND} (no command ran).
 */
public record VerifyOutcome(Kind kind, int iterations, CommandResult result) {

    /** Which terminal state the verify loop reached. */
    public enum Kind {

        /** A zero exit was observed within the bound (RD-10): the unit of work verified. */
        VERIFIED,

        /**
         * Every attempt within the bound exited non-zero; the loop stops and surfaces
         * (AC-3.4, AC-20.5; state machine A, S7 "verify-exhausted").
         */
        EXHAUSTED,

        /** No test command is configured, so there is nothing to verify (AC-20.6). */
        NO_TEST_COMMAND
    }

    /**
     * Validates the outcome's invariants so an inconsistent {@code VerifyOutcome} cannot
     * exist.
     *
     * @throws NullPointerException     if {@code kind} is {@code null}.
     * @throws IllegalArgumentException if {@code iterations} is negative, or if the
     *                                  {@code result}/{@code iterations} shape does not
     *                                  match {@code kind} (a ran-command kind must carry a
     *                                  positive iteration count and a non-null result;
     *                                  {@link Kind#NO_TEST_COMMAND} must carry zero and
     *                                  {@code null}).
     */
    public VerifyOutcome {
        Objects.requireNonNull(kind, "kind");
        if (iterations < 0) {
            throw new IllegalArgumentException("iterations must be >= 0 (was " + iterations + ")");
        }
        if (kind == Kind.NO_TEST_COMMAND) {
            if (iterations != 0 || result != null) {
                throw new IllegalArgumentException(
                        "NO_TEST_COMMAND must carry iterations=0 and a null result");
            }
        } else {
            if (iterations < 1) {
                throw new IllegalArgumentException(
                        kind + " must carry iterations >= 1 (a command ran); was " + iterations);
            }
            Objects.requireNonNull(result, "result");
        }
    }

    /**
     * Builds a {@link Kind#VERIFIED} outcome.
     *
     * @param iterations the attempt count on which the command passed; {@code >= 1}.
     * @param result     the passing {@link CommandResult} ({@code exitCode == 0}); must not
     *                   be {@code null}.
     * @return a verified outcome.
     * @throws IllegalArgumentException if {@code iterations < 1}.
     * @throws NullPointerException     if {@code result} is {@code null}.
     */
    public static VerifyOutcome verified(int iterations, CommandResult result) {
        return new VerifyOutcome(Kind.VERIFIED, iterations, result);
    }

    /**
     * Builds a {@link Kind#EXHAUSTED} outcome.
     *
     * @param iterations the number of failing attempts made (equals the bound); {@code >= 1}.
     * @param result     the last failing {@link CommandResult}, carrying the relevant output
     *                   to surface (AC-20.5); must not be {@code null}.
     * @return an exhausted outcome.
     * @throws IllegalArgumentException if {@code iterations < 1}.
     * @throws NullPointerException     if {@code result} is {@code null}.
     */
    public static VerifyOutcome exhausted(int iterations, CommandResult result) {
        return new VerifyOutcome(Kind.EXHAUSTED, iterations, result);
    }

    /**
     * Builds a {@link Kind#NO_TEST_COMMAND} outcome (no test command configured; nothing
     * ran).
     *
     * @return a no-test-command outcome with {@code iterations == 0} and no result.
     */
    public static VerifyOutcome noTestCommand() {
        return new VerifyOutcome(Kind.NO_TEST_COMMAND, 0, null);
    }

    /**
     * Whether the unit of work verified (a zero exit from the configured test command
     * within the bound). This is the success predicate the workflow driver branches on
     * (RD-10, INV-17, AC-20.4).
     *
     * @return {@code true} only for a {@link Kind#VERIFIED} outcome.
     */
    public boolean verified() {
        return kind == Kind.VERIFIED;
    }

    /**
     * The final {@link CommandResult}, present when a command actually ran (i.e. not for
     * {@link Kind#NO_TEST_COMMAND}).
     *
     * @return the final result, or {@link Optional#empty()} when no command ran.
     */
    public Optional<CommandResult> resultIfPresent() {
        return Optional.ofNullable(result);
    }
}
