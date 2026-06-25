package com.srk.codingagent.loop;

import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.tool.CommandExecutor;
import com.srk.codingagent.tool.CommandResult;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The verify loop (a C2/C10-boundary control loop, US-20): after a unit of work's changes
 * are applied, it runs the configured test command, reacts to the exit code, and either
 * converges on success or &mdash; bounded by {@code verifyMaxIterations} &mdash; stops and
 * surfaces the failure rather than continuing silently.
 *
 * <p><b>The cycle (AC-20.1/20.3/20.4/20.5, RD-10, INV-17).</b> For up to
 * {@code verifyMaxIterations} attempts:
 * <ol>
 *   <li>Run the configured test command via the injected {@link CommandRunner} (AC-20.1).</li>
 *   <li>If it exited {@code 0}, the unit of work verified &mdash; return
 *       {@link VerifyOutcome.Kind#VERIFIED} with the attempt count and the passing result
 *       (RD-10/INV-17/AC-20.4: success iff a zero exit from the configured test command).</li>
 *   <li>If it exited non-zero (including the timeout's {@code 124}) and another attempt
 *       remains, feed the failure output back via the injected {@link RemedyAttempt}
 *       (AC-20.3) and retry.</li>
 *   <li>If it exited non-zero on the last attempt, stop and surface
 *       {@link VerifyOutcome.Kind#EXHAUSTED} with the bound and the <em>last</em> failing
 *       result (AC-3.4/AC-20.5/CT-SM-5; state machine A, S7 "verify-exhausted"). The loop
 *       does not run an (N+1)-th attempt.</li>
 * </ol>
 *
 * <p><b>No test command.</b> When no test command is configured, there is nothing to verify
 * against; the loop runs nothing and returns {@link VerifyOutcome.Kind#NO_TEST_COMMAND} &mdash;
 * a config state to report, not to paper over with an ad-hoc command. This is a generic
 * verify-loop state; the consuming workflow driver binds it to its own behaviour (the
 * greenfield end-of-phase consumer treats it as the AC-3.6 complete-with-warning terminal).
 * The state is represented by a {@code null} {@link CommandRunner}; use
 * {@link #forConfig(CommandExecutor, ResolvedConfig, RemedyAttempt)} to build a loop wired
 * directly to a {@link ResolvedConfig}, which yields it automatically when
 * {@code config.commands().test() == null}.
 *
 * <p><b>Surfaced, not fatal (02-architecture &sect; 3.2).</b> An exhausted outcome is
 * surfaced for the workflow driver to act on; the verify loop itself never calls
 * {@code System.exit} (contrast a compaction failure, which is the fatal exit 5 of a
 * different machine). The remedy step &mdash; the model turn that reads the failure and edits
 * code (AC-20.3) &mdash; is the workflow driver's job (T-1.6/T-3.3), injected here as the
 * {@link RemedyAttempt} seam so this control logic is fully unit-testable in isolation.
 *
 * <p>Not thread-safe: one verify loop runs one unit of work's verification on one thread.
 */
public final class VerifyLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(VerifyLoop.class);

    private final CommandRunner runner;
    private final RemedyAttempt remedy;
    private final int maxIterations;

    /**
     * Creates a verify loop over its injected collaborators and bound.
     *
     * @param runner        the command-runner seam that runs the configured test command on
     *                      each attempt (AC-20.1), or {@code null} when no test command is
     *                      configured (the loop then verifies nothing and yields
     *                      {@link VerifyOutcome.Kind#NO_TEST_COMMAND}).
     * @param remedy        the remedy seam invoked between failing attempts to feed the
     *                      failure back and attempt a fix (AC-20.3); use
     *                      {@link RemedyAttempt#NONE} for a pure re-run; must not be
     *                      {@code null}.
     * @param maxIterations the maximum number of attempts before the loop stops and surfaces
     *                      (NFR-VERIFY-MAX-ITERATIONS, from
     *                      {@link ResolvedConfig#verifyMaxIterations()}, default 5); must be
     *                      {@code >= 1}.
     * @throws NullPointerException     if {@code remedy} is {@code null}.
     * @throws IllegalArgumentException if {@code maxIterations < 1}.
     */
    public VerifyLoop(CommandRunner runner, RemedyAttempt remedy, int maxIterations) {
        this.runner = runner;
        this.remedy = Objects.requireNonNull(remedy, "remedy");
        if (maxIterations < 1) {
            throw new IllegalArgumentException(
                    "maxIterations must be >= 1 (was " + maxIterations + ")");
        }
        this.maxIterations = maxIterations;
    }

    /**
     * Builds a verify loop wired to a resolved configuration: it runs the configured test
     * command ({@link ResolvedConfig.Commands#test()}) through the {@link CommandExecutor}
     * with the configured per-command timeout ({@link ResolvedConfig#commandTimeoutSeconds()}),
     * bounded by {@link ResolvedConfig#verifyMaxIterations()}.
     *
     * <p>When no test command is configured ({@code config.commands().test() == null}), the
     * returned loop verifies nothing and yields {@link VerifyOutcome.Kind#NO_TEST_COMMAND}; no
     * executor call is made.
     *
     * @param executor the command executor rooted at the workspace; must not be {@code null}.
     * @param config   the resolved configuration supplying the test command, the timeout,
     *                 and the iteration bound; must not be {@code null}.
     * @param remedy   the remedy seam (the workflow driver's model-driven fix, or
     *                 {@link RemedyAttempt#NONE}); must not be {@code null}.
     * @return a verify loop ready to verify one unit of work; never {@code null}.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public static VerifyLoop forConfig(
            CommandExecutor executor, ResolvedConfig config, RemedyAttempt remedy) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(remedy, "remedy");
        String testCommand = config.commands().test();
        CommandRunner runner = testCommand == null
                ? null
                : CommandRunner.over(
                        executor, testCommand, Duration.ofSeconds(config.commandTimeoutSeconds()));
        return new VerifyLoop(runner, remedy, config.verifyMaxIterations());
    }

    /**
     * Runs the bounded verify cycle for the unit of work whose changes have just been
     * applied, and returns the structured outcome.
     *
     * @return the terminal {@link VerifyOutcome}: {@link VerifyOutcome.Kind#VERIFIED} on a
     *         zero exit within the bound, {@link VerifyOutcome.Kind#EXHAUSTED} when every
     *         attempt failed, or {@link VerifyOutcome.Kind#NO_TEST_COMMAND} when no test
     *         command is configured; never {@code null}.
     */
    public VerifyOutcome verify() {
        if (runner == null) {
            LOGGER.info("No test command configured; nothing to verify "
                    + "(the consuming driver decides how to surface it)");
            return VerifyOutcome.noTestCommand();
        }

        LOGGER.info("Verifying unit of work: up to {} attempt(s)", maxIterations);
        CommandResult last = null;
        for (int attempt = 1; attempt <= maxIterations; attempt++) {
            CommandResult result = Objects.requireNonNull(
                    runner.run(), "command runner returned a null result");
            last = result;

            // RD-10 / INV-17 / AC-20.4: success iff a zero exit from the configured test
            // command. A timed-out run has a non-zero exitCode (124), so it is not success.
            if (result.exitCode() == 0) {
                LOGGER.info("Unit of work verified on attempt {} of {} (exit 0)",
                        attempt, maxIterations);
                return VerifyOutcome.verified(attempt, result);
            }

            if (attempt < maxIterations) {
                // AC-20.3: feed the failure output back into reasoning and attempt a remedy
                // before the next attempt. The remedy is NOT invoked after the last attempt
                // (no further run would consume it) nor after a success.
                LOGGER.warn("Verification attempt {} of {} exited {}; attempting a remedy",
                        attempt, maxIterations, result.exitCode());
                remedy.attempt(result);
            }
        }

        // AC-3.4 / AC-20.5 / CT-SM-5: did not verify within the bound. Stop and surface the
        // last failure with its relevant output (state machine A, S7 "verify-exhausted").
        LOGGER.error("Verification did not succeed within {} attempt(s); surfacing (exit {})",
                maxIterations, last.exitCode());
        return VerifyOutcome.exhausted(maxIterations, last);
    }
}
