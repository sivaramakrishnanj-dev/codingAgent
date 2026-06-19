package com.srk.codingagent.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * The structured result of a single command execution (ADR-0003,
 * {@code 03-data-model.md} § 2.4). Mirrors the {@code command-result.schema.json}
 * contract field-for-field: {@code command}, {@code exitCode}, {@code stdout},
 * {@code stderr}, {@code durationMs}, {@code timedOut}, {@code truncated}, and the
 * optional {@code fullRef}.
 *
 * <p><b>The verification signal (RD-10, INV-17, AC-20.4).</b> {@link #exitCode()} is
 * the unit-of-work success signal: a zero exit from the configured test command means
 * success. The verify-loop that consumes this signal to decide a unit of work's success
 * is a later task (T-1.4); here the contract is only that {@code exitCode} carries the
 * subprocess's real exit status faithfully, so {@code exitCode == 0} is exactly the
 * success signal and a non-zero status is the real failure code (CT-INV-14).
 *
 * <p><b>Output disposal (NFR-OUTPUT-MAX-INLINE, ADR-0006).</b> The executor captures the
 * full {@code stdout}/{@code stderr} and sets {@code truncated} to {@code false}; the
 * truncate/summarize strategy and the {@code fullRef} pointer to the event log are a
 * later task (T-1.5). {@code fullRef} is therefore {@code null} on every result this
 * task produces, present in the type only to match the schema's optional field.
 *
 * @param command    the command as executed; must not be {@code null}.
 * @param exitCode   the process exit code; {@code 0} = success for the unit of work
 *                   (RD-10).
 * @param stdout     the captured standard output; must not be {@code null} (empty when
 *                   the command produced none).
 * @param stderr     the captured standard error; must not be {@code null}.
 * @param durationMs the wall-clock execution time in milliseconds; {@code >= 0}
 *                   (schema {@code minimum 0}).
 * @param timedOut   {@code true} if the command was tree-killed on
 *                   NFR-CMD-TIMEOUT.
 * @param truncated  {@code true} if output exceeded NFR-OUTPUT-MAX-INLINE and the full
 *                   output lives in the event log (ADR-0006); always {@code false} for
 *                   this task.
 * @param fullRef    a pointer to the full output in the log when {@code truncated}, or
 *                   {@code null} (AC-19.2/19.3); always {@code null} for this task.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandResult(
        String command,
        int exitCode,
        String stdout,
        String stderr,
        long durationMs,
        boolean timedOut,
        boolean truncated,
        String fullRef) {

    /**
     * Validates the schema-pinned invariants so an invalid {@code CommandResult} cannot
     * exist.
     *
     * @throws NullPointerException     if {@code command}, {@code stdout}, or
     *                                  {@code stderr} is {@code null}.
     * @throws IllegalArgumentException if {@code durationMs} is negative (schema
     *                                  {@code minimum 0}).
     */
    public CommandResult {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be >= 0 (was " + durationMs + ")");
        }
    }

    /**
     * Creates a result for a command that ran to completion (not timed out, output not
     * disposed). This is the shape the executor produces for this task: the full output
     * is captured, so {@code truncated} is {@code false} and {@code fullRef} is
     * {@code null}.
     *
     * @param command    the command as executed; must not be {@code null}.
     * @param exitCode   the real subprocess exit status (the verification signal).
     * @param stdout     the full captured standard output; must not be {@code null}.
     * @param stderr     the full captured standard error; must not be {@code null}.
     * @param durationMs the wall-clock execution time in milliseconds; {@code >= 0}.
     * @return a completed, non-timed-out, non-truncated {@code CommandResult}.
     */
    public static CommandResult completed(
            String command, int exitCode, String stdout, String stderr, long durationMs) {
        return new CommandResult(command, exitCode, stdout, stderr, durationMs, false, false, null);
    }

    /**
     * Creates a result for a command that was tree-killed when it exceeded
     * NFR-CMD-TIMEOUT. {@code timedOut} is {@code true} and the exit code is the
     * conventional {@code 124} that {@code timeout(1)} uses for a killed command, so the
     * non-zero status is itself a failure signal (RD-10).
     *
     * @param command    the command as executed; must not be {@code null}.
     * @param stdout     whatever standard output was captured before the kill; must not
     *                   be {@code null}.
     * @param stderr     whatever standard error was captured before the kill; must not
     *                   be {@code null}.
     * @param durationMs the wall-clock time until the timeout fired; {@code >= 0}.
     * @return a timed-out {@code CommandResult} with {@code timedOut == true}.
     */
    public static CommandResult timedOut(
            String command, String stdout, String stderr, long durationMs) {
        return new CommandResult(command, TIMEOUT_EXIT_CODE, stdout, stderr, durationMs, true, false, null);
    }

    /**
     * The exit code reported for a command that was killed on timeout. Matches the
     * convention of POSIX {@code timeout(1)}, which exits {@code 124} when it terminates
     * the command. A timed-out command is a tool failure (ADR-0003), and this non-zero
     * code makes that a failure signal under RD-10.
     */
    public static final int TIMEOUT_EXIT_CODE = 124;
}
