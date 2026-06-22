package com.srk.codingagent.loop;

import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.tool.CommandExecutor;
import com.srk.codingagent.tool.CommandResult;
import java.time.Duration;
import java.util.Objects;

/**
 * The command-runner seam the {@link VerifyLoop} runs the configured test command through
 * (AC-20.1, RD-10). It exists so the verify loop's bounded run&rarr;check&rarr;retry control
 * logic is unit-testable in isolation: a test scripts an exit-code sequence
 * (fail&rarr;fail&rarr;pass, or fail&times;N) without shelling out to a real build, exactly as
 * {@link com.srk.codingagent.cli.OneShotRunner.OneShotLoop} isolates the agent loop and
 * {@link BudgetGuard} isolates the budget check.
 *
 * <p>Each invocation runs the unit-of-work's configured test command once and returns the
 * structured {@link CommandResult}; the verify loop reads {@link CommandResult#exitCode()}
 * (the success signal &mdash; {@code 0} is success, any non-zero including the timeout's
 * {@code 124} is failure, INV-17/CT-INV-14) to decide whether to stop or retry.
 *
 * <p>The production wiring is {@link #over(CommandExecutor, String, Duration)}, which
 * delegates to the real {@link CommandExecutor} with the per-command timeout drawn from
 * {@link ResolvedConfig#commandTimeoutSeconds()} &mdash; the same config-timeout pattern
 * {@link com.srk.codingagent.tool.RunCommandTool} uses. The loop logic never constructs an
 * executor or reads config directly; it only calls this seam.
 */
@FunctionalInterface
public interface CommandRunner {

    /**
     * Runs the configured test command once and returns its structured result.
     *
     * @return the {@link CommandResult} of the run; never {@code null}. A
     *         {@code exitCode == 0} result is the unit-of-work success signal (RD-10).
     */
    CommandResult run();

    /**
     * Builds the production runner that delegates to the real {@link CommandExecutor},
     * running {@code command} with the configured timeout.
     *
     * @param executor the command executor rooted at the workspace; must not be
     *                 {@code null}.
     * @param command  the configured test command to run on each attempt (from
     *                 {@link ResolvedConfig.Commands#test()}); must not be {@code null} or
     *                 blank.
     * @param timeout  the per-command timeout (NFR-CMD-TIMEOUT, from
     *                 {@link ResolvedConfig#commandTimeoutSeconds()}); must not be
     *                 {@code null} and must be positive.
     * @return a runner that executes {@code command} via {@code executor} on each call.
     * @throws NullPointerException     if any argument is {@code null}.
     * @throws IllegalArgumentException if {@code command} is blank or {@code timeout} is
     *                                  not positive.
     */
    static CommandRunner over(CommandExecutor executor, String command, Duration timeout) {
        Objects.requireNonNull(executor, "executor");
        if (Objects.requireNonNull(command, "command").isBlank()) {
            throw new IllegalArgumentException("command must be non-blank");
        }
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive (was " + timeout + ")");
        }
        return () -> executor.run(command, timeout);
    }
}
